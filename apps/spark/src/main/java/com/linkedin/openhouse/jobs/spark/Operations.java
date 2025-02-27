package com.linkedin.openhouse.jobs.spark;

import com.google.common.collect.Lists;
import com.linkedin.openhouse.jobs.util.AppConstants;
import com.linkedin.openhouse.jobs.util.SparkJobUtil;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.Meter;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocatedFileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.RemoteIterator;
import org.apache.iceberg.CatalogUtil;
import org.apache.iceberg.StructLike;
import org.apache.iceberg.Table;
import org.apache.iceberg.actions.DeleteOrphanFiles;
import org.apache.iceberg.actions.ExpireSnapshots;
import org.apache.iceberg.actions.RewriteDataFiles;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.spark.actions.SparkActions;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import scala.collection.JavaConverters;

/**
 * Utility class to hold table maintenance operations using Spark engine, and supporting methods.
 */
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Slf4j
public final class Operations implements AutoCloseable {
  // assume that catalog name is fixed
  private static final String CATALOG = "openhouse";

  private final SparkSession spark;

  private final Meter meter;

  public static Operations of(SparkSession spark, Meter meter) {
    return new Operations(spark, meter);
  }

  public static Operations withCatalog(SparkSession spark, Meter meter) {
    Operations ops = of(spark, meter);
    ops.useCatalog();
    return ops;
  }

  public SparkSession spark() {
    return spark;
  }

  /** Get default file system from Spark Hadoop configuration. */
  public FileSystem fs() throws IOException {
    return FileSystem.get(spark.sparkContext().hadoopConfiguration());
  }

  public Table getTable(String fqtn) {
    Catalog catalog = getCatalog();
    return catalog.loadTable(TableIdentifier.parse(fqtn));
  }

  /**
   * Run DeleteOrphanFiles operation on the given fully-qualified table name, moves files to the
   * given trash directory. It moves file older than default 3 days.
   */
  public DeleteOrphanFiles.Result deleteOrphanFiles(String fqtn, String trashDir) {
    return deleteOrphanFiles(getTable(fqtn), trashDir);
  }

  /**
   * Run DeleteOrphanFiles operation on the given {@link Table}, moves files to the given trash
   * directory. It moves file older than default 3 days.
   */
  public DeleteOrphanFiles.Result deleteOrphanFiles(Table table, String trashDir) {
    return deleteOrphanFiles(table, trashDir, 0, false);
  }

  /**
   * Run DeleteOrphanFiles operation on the given table with time filter, moves files to the given
   * trash directory. It moves files older than the provided timestamp.
   */
  public DeleteOrphanFiles.Result deleteOrphanFiles(
      Table table, String trashDir, long olderThanTimestampMillis, boolean skipStaging) {

    DeleteOrphanFiles operation = SparkActions.get(spark).deleteOrphanFiles(table);
    // if time filter is not provided it defaults to 3 days
    if (olderThanTimestampMillis > 0) {
      operation = operation.olderThan(olderThanTimestampMillis);
    }
    operation =
        operation.deleteWith(
            file -> {
              log.info("Detected orphan file {}", file);
              if (file.endsWith("metadata.json")) {
                // Don't remove metadata.json files since current metadata.json is recognized as
                // orphan because of inclusion of the scheme in its file path returned by catalog.
                // Also, we want Iceberg commits to remove the metadata.json files not the OFD job.
                log.info("Skipped deleting metadata file {}", file);
              } else if (!skipStaging) {
                // files present in .trash dir should not be considered orphan
                Path trashFolderPath = getTrashPath(table, file, trashDir);
                if (!file.contains(trashFolderPath.toString())) {
                  log.info("Moving orphan file {} to {}", file, trashFolderPath);
                  try {
                    rename(new Path(file), trashFolderPath);
                  } catch (IOException e) {
                    log.error(String.format("Move operation failed for file: %s", file), e);
                  }
                }
              } else {
                log.info("Deleting orphan file {}", file);
                try {
                  fs().delete(new Path(file), false);
                } catch (IOException e) {
                  log.error(String.format("Delete operation failed for file: %s", file), e);
                }
              }
            });
    return operation.execute();
  }

  /** Run ExpireSnapshots operation on a given fully-qualified table name. */
  public ExpireSnapshots.Result expireSnapshots(String fqtn, long expireBeforeTimestampMs) {
    return expireSnapshots(getTable(fqtn), expireBeforeTimestampMs);
  }

  /** Run ExpireSnapshots operation on a given {@link Table}. */
  public ExpireSnapshots.Result expireSnapshots(Table table, long expireBeforeTimestampMs) {
    return SparkActions.get(spark)
        .expireSnapshots(table)
        .expireOlderThan(expireBeforeTimestampMs)
        .deleteWith(
            (file) -> {
              // skip deletion
              log.info("Detected file {} that is not part of survived snapshots", file);
            })
        .execute();
  }

  /**
   * Run table retention operation if there are rows with partition column (@columnName) value older
   * than @count @granularity.
   *
   * @param fqtn fully qualified tableName e.g test_db.test_table
   * @param columnName partition column based on which records are retained. Schema type
   *     of @columnName could be timestamp or String
   * @param columnPattern If schema type of @columnName is String @columnPattern is used to parse it
   *     to a timestamp. columnPattern should always represent a valid DateTimeFormat
   * @param granularity time granularity in Hour, Day, Month, Year
   * @param count granularity count representing retention timeline for @fqtn records
   */
  public void runRetention(
      String fqtn, String columnName, String columnPattern, String granularity, int count) {
    checkRecords(fqtn, columnName, columnPattern);
    final String statement =
        SparkJobUtil.createDeleteStatement(fqtn, columnName, columnPattern, granularity, count);
    Dataset<Row> dsWithExpiredRows =
        spark.sql(
            SparkJobUtil.createSelectLimitStatement(
                fqtn, columnName, columnPattern, granularity, count, 1));
    if (!dsWithExpiredRows.isEmpty()) {
      spark.sql(statement).show();
    }
  }

  /**
   * CheckRecords verifies a sample from fqtn against @columnPattern to validate parsing with
   * columnPattern. The metrics emitted helps to verify if records in table are not adhering to
   * DateTimeFormat standards
   *
   * @param fqtn - fully-qualified table name
   * @param columnName - retention column name
   * @param columnPattern - retention column pattern
   */
  public void checkRecords(String fqtn, String columnName, String columnPattern) {
    String quotedFqtn = SparkJobUtil.getQuotedFqtn(fqtn);
    if (!StringUtils.isBlank(columnPattern)) {
      String query =
          String.format(
              "SELECT COALESCE(to_timestamp(%s, '%s'), 'Invalid') AS parsed_timestamp FROM %s LIMIT 10",
              columnName, columnPattern, quotedFqtn);
      log.info("Pattern verification query {}", query);
      List<Row> resultDF = spark.sql(query).collectAsList();
      if (resultDF.stream()
          .map(r -> r.getString(r.fieldIndex("parsed_timestamp")))
          .collect(Collectors.toList())
          .contains("Invalid")) {
        meter
            .counterBuilder(AppConstants.INCOMPATIBLE_DATE_COLUMN)
            .build()
            .add(1, Attributes.of(AttributeKey.stringKey(AppConstants.TABLE_NAME), fqtn));
        log.warn(
            "Failed to parse column {} with provided retention column pattern {} for table {}",
            columnName,
            columnPattern,
            fqtn);
      }
    }
  }

  /**
   * Get trash path location for a file in a table. It replaces the tableLocation part from filePath
   * with trashPath which contains <tableLocation>+/.trash
   */
  public Path getTrashPath(Table table, String filePath, String trashDir) {
    return new Path(
        filePath.replace(table.location(), new Path(table.location(), trashDir).toString()));
  }

  /**
   * Run deleteStagedFiles operation for the given table with time filter. It deletes files older
   * than the provided timestamp from the staged directory.
   */
  public List<Path> deleteStagedFiles(Path baseDir, int olderThanDays, boolean recursive)
      throws IOException {
    List<Path> matchingFiles = Lists.newArrayList();
    Predicate<FileStatus> predicate =
        file ->
            file.getModificationTime()
                < System.currentTimeMillis() - TimeUnit.DAYS.toMillis(olderThanDays);
    FileSystem fs = fs();
    if (fs.exists(baseDir)) {
      listFiles(baseDir, predicate, recursive, matchingFiles);
      Long modTimeThreshold = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(olderThanDays);
      log.info(
          "Deleting {} files from {} that are older than {} modificationTimeThreshold {}",
          matchingFiles.size(),
          baseDir,
          olderThanDays,
          modTimeThreshold);
      for (Path p : matchingFiles) {
        try {
          if (!fs.delete(p, false)) {
            log.error(String.format("Failed to delete file %s", p));
          }
        } catch (IOException e) {
          log.error(String.format("Exception while deleting file %s", p), e);
        }
      }
    } else {
      log.info("Trash dir {} does not exist", baseDir);
    }
    return matchingFiles;
  }

  /**
   * Run RewriteDataFiles operation for the given {@link Table}. Check
   * https://iceberg.apache.org/docs/latest/spark-procedures/#rewrite_data_files for more
   * configuration.
   *
   * <p>Compaction organizes files into file groups before doing rewrite. For partitioned tables,
   * there could be 0 or more file groups per partition. File groups don't cross partition
   * boundaries. Those file groups could be processed in parallel, controlled by
   * max-concurrent-file-group-rewrites. As file groups rewrite complete, the app can do commits if
   * partial-progress is enabled. It can do up to partial-progress.max-commits commits per run,
   * multiple file group rewrites can be part of the same commit. Generally, the algorithm is to
   * commit about the same number of groups every time. Compaction does full scan, so in the first
   * run, it can compact all partitions, hence we should be careful and to set the
   * max-concurrent-file-group-rewrites to lower number.
   */
  public RewriteDataFiles.Result rewriteDataFiles(
      Table table,
      long targetByteSize,
      long minByteSize,
      long maxByteSize,
      int minInputFiles,
      int maxConcurrentFileGroupRewrites,
      boolean partialProgressEnabled,
      int partialProgressMaxCommits) {
    return SparkActions.get(spark)
        .rewriteDataFiles(table)
        .binPack()
        // maximum number of file groups to be simultaneously rewritten
        .option(
            "max-concurrent-file-group-rewrites", Integer.toString(maxConcurrentFileGroupRewrites))
        // enable committing groups of files prior to the entire rewrite completing
        .option("partial-progress.enabled", Boolean.toString(partialProgressEnabled))
        // maximum amount of commits that this rewrite is allowed to produce if partial progress is
        // enabled
        .option("partial-progress.max-commits", Integer.toString(partialProgressMaxCommits))
        // any file group exceeding this number of files will be rewritten regardless of other
        // criteria
        .option("min-input-files", Integer.toString(minInputFiles))
        // 512MB
        .option("target-file-size-bytes", Long.toString(targetByteSize))
        // files under this threshold will be considered for rewriting regardless of any other
        // criteria
        .option("min-file-size-bytes", Long.toString(minByteSize))
        // files with sizes above this threshold will be considered for rewriting regardless of any
        // other criteria
        .option("max-file-size-bytes", Long.toString(maxByteSize))
        .execute();
  }

  public void rename(final Path src, final Path dest) throws IOException {
    final FileSystem fs = fs();
    if (!fs.exists(dest.getParent())) {
      fs.mkdirs(dest.getParent());
    }
    if (fs.rename(src, dest)) {
      log.info("Moved " + src + " to " + dest);
    } else {
      throw new IOException(
          String.format("FileSystem move operation failed from src: %s to dest %s", src, dest));
    }
  }

  /**
   * Perform recursive lookup of files in a directory and collect files matching the predicate
   * filter.
   */
  public void listFiles(
      Path dir, Predicate<FileStatus> predicate, boolean recursive, List<Path> matchingFiles) {
    try {
      RemoteIterator<LocatedFileStatus> it = fs().listFiles(dir, recursive);
      log.info(
          "StagedFiles Deletion: Path {}, recursive {}, testing predicate {}",
          dir,
          recursive,
          predicate.toString());
      while (it.hasNext()) {
        LocatedFileStatus status = it.next();
        if (predicate.test(status)) {
          matchingFiles.add(status.getPath());
        }
      }
    } catch (IOException e) {
      throw new RuntimeException(
          String.format("Failed to list files in dir %s: ", dir.toString()), e);
    }
  }

  @Override
  public void close() throws Exception {
    spark.close();
  }

  private Catalog getCatalog() {
    final Map<String, String> catalogProperties = new HashMap<>();
    final String catalogPropertyPrefix = String.format("spark.sql.catalog.%s.", CATALOG);
    final Map<String, String> sparkProperties = JavaConverters.mapAsJavaMap(spark.conf().getAll());
    for (Map.Entry<String, String> entry : sparkProperties.entrySet()) {
      if (entry.getKey().startsWith(catalogPropertyPrefix)) {
        catalogProperties.put(
            entry.getKey().substring(catalogPropertyPrefix.length()), entry.getValue());
      }
    }
    // this initializes the catalog based on runtime Catalog class passed in catalog-impl conf.
    return CatalogUtil.loadCatalog(
        sparkProperties.get("spark.sql.catalog.openhouse.catalog-impl"),
        CATALOG,
        catalogProperties,
        spark.sparkContext().hadoopConfiguration());
  }

  private void useCatalog() {
    spark.sql("USE " + CATALOG);
  }

  static String groupInfoToString(RewriteDataFiles.FileGroupInfo groupInfo) {
    return "(partition="
        + partitionToString(groupInfo.partition())
        + ", partitionIndex="
        + groupInfo.partitionIndex()
        + ", globalIndex="
        + groupInfo.globalIndex()
        + ")";
  }

  /**
   * Converts partition to string representation.
   *
   * @param partition - StructLike is a map of column names to values
   * @return string representation of partition
   */
  static String partitionToString(StructLike partition) {
    StringBuilder ret = new StringBuilder();
    ret.append("{");
    for (int i = 0; i < partition.size(); i++) {
      if (i > 0) {
        ret.append(", ");
      }
      ret.append(partition.get(i, Object.class));
    }
    ret.append("}");
    return ret.toString();
  }
}
