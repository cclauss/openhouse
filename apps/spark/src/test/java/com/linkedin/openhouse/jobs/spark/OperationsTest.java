package com.linkedin.openhouse.jobs.spark;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.linkedin.openhouse.jobs.util.OtelConfig;
import com.linkedin.openhouse.jobs.util.SparkJobUtil;
import com.linkedin.openhouse.tables.client.model.Policies;
import com.linkedin.openhouse.tables.client.model.Retention;
import com.linkedin.openhouse.tablestest.OpenHouseSparkITest;
import io.opentelemetry.api.metrics.Meter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Triple;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.Table;
import org.apache.iceberg.actions.DeleteOrphanFiles;
import org.apache.iceberg.actions.RewriteDataFiles;
import org.apache.spark.sql.Row;
import org.assertj.core.util.Lists;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@Slf4j
public class OperationsTest extends OpenHouseSparkITest {
  private static final String TRASH_DIR = ".trash";
  private final Meter meter = OtelConfig.getMeter(this.getClass().getName());

  @Test
  public void testRetentionSparkApp() throws Exception {
    final String tableName = "db.test_retention_sql";
    try (Operations ops = Operations.withCatalog(getSparkSession(), meter)) {
      prepareTableWithPolicies(ops, tableName, "1d", true);
      populateTable(ops, tableName, 3);
      populateTable(ops, tableName, 2, 2);
      ops.runRetention(tableName, "ts", "", "day", 1);
      verifyRowCount(ops, tableName, 3);
      verifyPolicies(ops, tableName, 1, Retention.GranularityEnum.DAY, true);
    }
  }

  @Test
  public void testRetentionSparkAppWithStringPartitionColumns() throws Exception {
    final String tableName1 = "db.test_retention_string_partition1";
    final String tableName2 = "db.test_retention_string_partition2";
    final String tableName3 = "db.test_retention_string_partition3";
    final String tableName4 = "db.test_retention_string_partition4";

    List<String> rowValue = new ArrayList<>();
    try (Operations ops = Operations.withCatalog(getSparkSession(), meter)) {
      rowValue.add("202%s-07-16");
      runRetentionJobWithStringPartitionColumns(
          ops, tableName1, rowValue, "datePartition", "yyyy-MM-dd", "day");
      verifyRowCount(ops, tableName1, 0);
      rowValue.clear();

      rowValue.add("202%s-07-16-12");
      runRetentionJobWithStringPartitionColumns(
          ops, tableName2, rowValue, "datePartition", "yyyy-MM-dd-HH", "day");
      verifyRowCount(ops, tableName2, 0);
      rowValue.clear();

      rowValue.add("202%s-07-2218:46:19-0700");
      runRetentionJobWithStringPartitionColumns(
          ops, tableName3, rowValue, "datePartition", "yyyy-MM-ddHH:mm:ssZ", "day");
      verifyRowCount(ops, tableName3, 0);
      rowValue.clear();

      rowValue.add("202%s-07-16-12");
      rowValue.add("202%s-07-2218:46:19-0700");
      runRetentionJobWithStringPartitionColumns(
          ops, tableName4, rowValue, "datePartition", "yyyy-MM-dd-HH", "day");
      verifyRowCount(ops, tableName4, 3);
      rowValue.clear();
    }
  }

  private void runRetentionJobWithStringPartitionColumns(
      Operations ops,
      String tableName,
      List<String> dataFormats,
      String column,
      String pattern,
      String granularity) {
    prepareTableWithStringColumn(ops, tableName);
    populateTableWithStringColumn(ops, tableName, 3, dataFormats);
    ops.runRetention(tableName, column, pattern, granularity, 2);
  }

  @Test
  public void testRetentionDoesNotCreateSnapshotsOnNoOpDelete() throws Exception {
    final String tableName = "db_test.test_retention_sql";
    try (Operations ops = Operations.withCatalog(getSparkSession(), meter)) {
      prepareTable(ops, tableName);
      populateTable(ops, tableName, 4);
      List<Long> snapshots = getSnapshotIds(ops, tableName);
      // check if there are existing snapshots
      Assertions.assertTrue(snapshots.size() > 0);
      ops.runRetention(tableName, "ts", "", "day", 2);
      verifyRowCount(ops, tableName, 4);
      List<Long> snapshotsAfter = getSnapshotIds(ops, tableName);
      Assertions.assertEquals(snapshots.size(), snapshotsAfter.size());
    }
  }

  @Test
  public void testOrphanFilesDeletionJavaAPI() throws Exception {
    final String tableName = "db.test_ofd_java";
    final String testOrphanFileName = "test_orphan_file.orc";
    final int numInserts = 3;
    try (Operations ops = Operations.withCatalog(getSparkSession(), meter)) {
      prepareTable(ops, tableName);
      populateTable(ops, tableName, numInserts);
      Table table = ops.getTable(tableName);
      log.info("Loaded table {}, location {}", table.name(), table.location());
      List<Row> snapshots =
          ops.spark().sql(String.format("SELECT * from %s.history", tableName)).collectAsList();
      Assertions.assertEquals(numInserts, snapshots.size());
      log.info("Found {} snapshots", snapshots.size());
      for (Row metadataFileRow : snapshots) {
        log.info(metadataFileRow.toString());
      }
      Path orphanFilePath = new Path(table.location(), testOrphanFileName);
      FileSystem fs = ops.fs();
      fs.createNewFile(orphanFilePath);
      log.info("Created orphan file {}", testOrphanFileName);
      DeleteOrphanFiles.Result result =
          ops.deleteOrphanFiles(table, TRASH_DIR, System.currentTimeMillis(), false);
      List<String> orphanFiles = Lists.newArrayList(result.orphanFileLocations().iterator());
      log.info("Detected {} orphan files", orphanFiles.size());
      for (String of : orphanFiles) {
        log.info("File {}", of);
      }
      Assertions.assertTrue(
          fs.exists(new Path(table.location(), new Path(TRASH_DIR, testOrphanFileName))));
      Assertions.assertEquals(1, orphanFiles.size());
      Assertions.assertTrue(
          orphanFiles.get(0).endsWith(table.location() + "/" + testOrphanFileName));
      Assertions.assertFalse(fs.exists(orphanFilePath));
    }
  }

  @Test
  public void testOrphanFilesDeletionIgnoresFilesInTrash() throws Exception {
    final String tableName = "db.test_ofd_java";
    final String testOrphanFileName = "test_orphan_file.orc";
    final int numInserts = 3;
    try (Operations ops = Operations.withCatalog(getSparkSession(), meter)) {
      prepareTable(ops, tableName);
      populateTable(ops, tableName, numInserts);
      Table table = ops.getTable(tableName);
      log.info("Loaded table {}, location {}", table.name(), table.location());
      Path orphanFilePath = new Path(table.location(), testOrphanFileName);
      FileSystem fs = ops.fs();
      fs.createNewFile(orphanFilePath);
      log.info("Created orphan file {}", testOrphanFileName);
      DeleteOrphanFiles.Result result =
          ops.deleteOrphanFiles(table, TRASH_DIR, System.currentTimeMillis(), false);
      List<String> orphanFiles = Lists.newArrayList(result.orphanFileLocations().iterator());
      log.info("Detected {} orphan files", orphanFiles.size());
      for (String of : orphanFiles) {
        log.info("File {}", of);
      }
      Path trashFilePath = new Path(table.location(), new Path(TRASH_DIR, testOrphanFileName));
      Assertions.assertTrue(fs.exists(trashFilePath));
      // run delete operation again and verify that files in .trash are not listed as Orphan
      DeleteOrphanFiles.Result result2 =
          ops.deleteOrphanFiles(table, TRASH_DIR, System.currentTimeMillis(), false);
      List<String> orphanFiles2 = Lists.newArrayList(result2.orphanFileLocations().iterator());
      log.info("Detected {} orphan files", orphanFiles2.size());
      Assertions.assertEquals(0, orphanFiles2.size());
      Assertions.assertTrue(fs.exists(trashFilePath));
    }
  }

  @Test
  public void testOrphanFilesDeletionNoStaging() throws Exception {
    final String tableName = "db.test_ofd";
    final String testOrphanFileName = "test_orphan_file.orc";
    final int numInserts = 3;
    try (Operations ops = Operations.withCatalog(getSparkSession(), meter)) {
      prepareTable(ops, tableName);
      populateTable(ops, tableName, numInserts);
      Table table = ops.getTable(tableName);
      log.info("Loaded table {}, location {}", table.name(), table.location());
      List<Row> snapshots =
          ops.spark().sql(String.format("SELECT * from %s.history", tableName)).collectAsList();
      Assertions.assertEquals(numInserts, snapshots.size());
      log.info("Found {} snapshots", snapshots.size());
      for (Row metadataFileRow : snapshots) {
        log.info(metadataFileRow.toString());
      }
      Path orphanFilePath = new Path(table.location(), testOrphanFileName);
      FileSystem fs = ops.fs();
      fs.createNewFile(orphanFilePath);
      log.info("Created orphan file {}", testOrphanFileName);
      DeleteOrphanFiles.Result result =
          ops.deleteOrphanFiles(table, TRASH_DIR, System.currentTimeMillis(), true);
      List<String> orphanFiles = Lists.newArrayList(result.orphanFileLocations().iterator());
      log.info("Detected {} orphan files", orphanFiles.size());
      for (String of : orphanFiles) {
        log.info("File {}", of);
      }
      Assertions.assertFalse(
          fs.exists(new Path(table.location(), new Path(TRASH_DIR, testOrphanFileName))));
      Assertions.assertEquals(1, orphanFiles.size());
      Assertions.assertTrue(
          orphanFiles.get(0).endsWith(table.location() + "/" + testOrphanFileName));
      Assertions.assertFalse(fs.exists(orphanFilePath));
    }
  }

  @Test
  public void testSnapshotsExpirationJavaAPI() throws Exception {
    final String tableName = "db.test_es_java";
    final int numInserts = 3;
    List<Long> snapshotIds;
    try (Operations ops = Operations.withCatalog(getSparkSession(), meter)) {
      prepareTable(ops, tableName);
      populateTable(ops, tableName, numInserts);
      snapshotIds = getSnapshotIds(ops, tableName);
      Assertions.assertEquals(
          numInserts,
          snapshotIds.size(),
          String.format("There must be %d snapshot(s) after inserts", numInserts));
      Table table = ops.getTable(tableName);
      log.info("Loaded table {}, location {}", table.name(), table.location());
      ops.expireSnapshots(table, System.currentTimeMillis());
      // verify that table object snapshots are updated
      checkSnapshots(table, snapshotIds.subList(2, snapshotIds.size()));
    }
    // restart the app to reload catalog cache
    try (Operations ops = Operations.withCatalog(getSparkSession(), meter)) {
      // verify that new apps see snapshots correctly
      checkSnapshots(ops, tableName, snapshotIds.subList(2, snapshotIds.size()));
    }
  }

  @Test
  public void testStagedFilesDelete() throws Exception {
    final String tableName = "db.test_staged_delete";
    final int numInserts = 3;
    final String testOrphanFile1 = "data/test_orphan_file1.orc";
    final String testOrphanFile2 = "test_orphan_file2.orc";
    try (Operations ops = Operations.withCatalog(getSparkSession(), meter)) {
      prepareTable(ops, tableName);
      populateTable(ops, tableName, numInserts);
      Table table = ops.getTable(tableName);
      log.info("Loaded table {}, location {}", table.name(), table.location());
      Path orphanFilePath1 = new Path(table.location(), testOrphanFile1);
      Path orphanFilePath2 = new Path(table.location(), testOrphanFile2);
      FileSystem fs = ops.fs();
      fs.createNewFile(orphanFilePath1);
      fs.createNewFile(orphanFilePath2);
      log.info("Created orphan file {}", testOrphanFile1);
      log.info("Created orphan file {}", testOrphanFile2);
      ops.deleteOrphanFiles(table, TRASH_DIR, System.currentTimeMillis(), false);
      Assertions.assertTrue(
          fs.exists(new Path(table.location(), (new Path(TRASH_DIR, testOrphanFile1)))));
      Assertions.assertTrue(
          fs.exists((new Path(table.location(), (new Path(TRASH_DIR, testOrphanFile2))))));
      Assertions.assertFalse(fs.exists(orphanFilePath1));
      Assertions.assertFalse(fs.exists(orphanFilePath2));
      // set timestamp for an orphan file in trash dir to 4 days old
      SparkJobUtil.setModifiedTimeStamp(
          fs, new Path(table.location(), new Path(TRASH_DIR, testOrphanFile1)), 4);
      ops.deleteStagedFiles(new Path(table.location(), TRASH_DIR), 3, true);
      Assertions.assertFalse(
          fs.exists(new Path(table.location(), new Path(TRASH_DIR, testOrphanFile1))));
      Assertions.assertTrue(
          fs.exists(new Path(table.location(), new Path(TRASH_DIR, testOrphanFile2))));
    }
  }

  @Test
  public void testDataCompactionPartialProgressNonPartitionedTable() throws Exception {
    final String tableName = "db.test_data_compaction";
    final int numInserts = 3;

    BiFunction<Operations, Table, RewriteDataFiles.Result> rewriteFunc =
        (ops, table) ->
            ops.rewriteDataFiles(
                table,
                1024 * 1024, // 1MB
                1024, // 1KB
                1024 * 1024 * 2, // 2MB
                2,
                1,
                true,
                10);

    try (Operations ops = Operations.withCatalog(getSparkSession(), meter)) {
      prepareTable(ops, tableName);
      populateTable(ops, tableName, numInserts);
      Table table = ops.getTable(tableName);
      log.info("Loaded table {}, location {}", table.name(), table.location());
      RewriteDataFiles.Result result = rewriteFunc.apply(ops, table);
      log.info(
          "Added {} data files, rewritten {} data files, rewritten {} bytes",
          result.addedDataFilesCount(),
          result.rewrittenDataFilesCount(),
          result.rewrittenBytesCount());
      Assertions.assertEquals(1, result.addedDataFilesCount());
      Assertions.assertEquals(3, result.rewrittenDataFilesCount());
    }
    // restart the app to reload catalog cache
    try (Operations ops = Operations.withCatalog(getSparkSession(), meter)) {
      long expectedNumSnapshots = numInserts + 1;
      List<Long> snapshotIds = getSnapshotIds(ops, tableName);
      Assertions.assertEquals(
          expectedNumSnapshots,
          snapshotIds.size(),
          String.format(
              "There must be %d snapshot(s) after %d inserts and 1 data files rewrite",
              expectedNumSnapshots, numInserts));
      // check that no rewrite happens second time
      Table table = ops.getTable(tableName);
      log.info("Loaded table {}, location {}", table.name(), table.location());
      RewriteDataFiles.Result result = rewriteFunc.apply(ops, table);
      log.info(
          "Added {} data files, rewritten {} data files, rewritten {} bytes",
          result.addedDataFilesCount(),
          result.rewrittenDataFilesCount(),
          result.rewrittenBytesCount());
      Assertions.assertEquals(0, result.addedDataFilesCount());
      Assertions.assertEquals(0, result.rewrittenDataFilesCount());
      Assertions.assertEquals(0, result.rewrittenBytesCount());
    }
  }

  @Test
  public void testDataCompactionPartialProgressPartitionedTable() throws Exception {
    final String tableName = "db.test_data_compaction_partitioned";
    final int numInsertsPerPartition = 3;
    final int numDailyPartitions = 10;
    final int maxCommits = 5;

    BiFunction<Operations, Table, RewriteDataFiles.Result> rewriteFunc =
        (ops, table) ->
            ops.rewriteDataFiles(
                table,
                1024 * 1024, // 1MB
                1024, // 1KB
                1024 * 1024 * 2, // 2MB
                2,
                1,
                true,
                maxCommits);

    try (Operations ops = Operations.withCatalog(getSparkSession(), meter)) {
      prepareTable(ops, tableName, true);
      long fixedTimestampSeconds = System.currentTimeMillis() / 1000;
      for (int daysLag = 0; daysLag < numDailyPartitions; ++daysLag) {
        populateTable(ops, tableName, numInsertsPerPartition, daysLag, fixedTimestampSeconds);
      }
      log.info("Produced the following data files:");
      getDataFiles(ops, tableName).forEach(f -> log.info(f.toString()));
      Table table = ops.getTable(tableName);
      log.info("Loaded table {}, location {}", table.name(), table.location());
      RewriteDataFiles.Result result = rewriteFunc.apply(ops, table);
      log.info(
          "Added {} data files, rewritten {} data files, rewritten {} bytes",
          result.addedDataFilesCount(),
          result.rewrittenDataFilesCount(),
          result.rewrittenBytesCount());
      Assertions.assertEquals(numDailyPartitions, result.addedDataFilesCount());
      Assertions.assertEquals(
          numInsertsPerPartition * numDailyPartitions, result.rewrittenDataFilesCount());
      result
          .rewriteResults()
          .forEach(
              fileGroupRewriteResult -> {
                log.info(
                    "File group {} has {} added files, {} rewritten files, {} rewritten bytes",
                    Operations.groupInfoToString(fileGroupRewriteResult.info()),
                    fileGroupRewriteResult.addedDataFilesCount(),
                    fileGroupRewriteResult.rewrittenDataFilesCount(),
                    fileGroupRewriteResult.rewrittenBytesCount());
              });
    }
    // restart the app to reload catalog cache
    try (Operations ops = Operations.withCatalog(getSparkSession(), meter)) {
      // all rewritten files must be in the same commit
      long expectedNumSnapshots = numInsertsPerPartition * numDailyPartitions + 5;
      List<Triple<String, String, Long>> dataFiles = getDataFiles(ops, tableName);
      Assertions.assertEquals(numDailyPartitions, dataFiles.size());
      log.info(
          String.format("Produced the following %d data files after rewrite:", dataFiles.size()));
      dataFiles.forEach(f -> log.info(f.toString()));
      List<Long> snapshotIds = getSnapshotIds(ops, tableName);
      Assertions.assertEquals(
          expectedNumSnapshots,
          snapshotIds.size(),
          String.format(
              "There must be %d snapshot(s) after %d inserts and %d commits during 1 data files rewrite",
              expectedNumSnapshots,
              numInsertsPerPartition * numDailyPartitions,
              numDailyPartitions));
      Table table = ops.getTable(tableName);
      log.info("Loaded table {}, location {}", table.name(), table.location());
      // check that no rewrite happens second time
      RewriteDataFiles.Result result = rewriteFunc.apply(ops, table);
      log.info(
          "Added {} data files, rewritten {} data files, rewritten {} bytes",
          result.addedDataFilesCount(),
          result.rewrittenDataFilesCount(),
          result.rewrittenBytesCount());
      Assertions.assertEquals(0, result.addedDataFilesCount());
      Assertions.assertEquals(0, result.rewrittenDataFilesCount());
      Assertions.assertEquals(0, result.rewrittenBytesCount());
    }
  }

  private void verifyPolicies(
      Operations ops,
      String tableName,
      int expectedRetentionCount,
      Retention.GranularityEnum expectedRetentionGranularity,
      boolean expectedSharing) {
    List<Row> resultRows =
        ops.spark().sql(String.format("SHOW TBLPROPERTIES %s", tableName)).collectAsList();
    Map<String, String> collect =
        resultRows.stream().collect(Collectors.toMap(r -> r.getString(0), r -> r.getString(1)));
    String policiesStr = String.valueOf(collect.get("policies"));
    Gson gson = new GsonBuilder().setPrettyPrinting().create();
    Policies policies = gson.fromJson(policiesStr, Policies.class);
    Assertions.assertEquals(policies.getRetention().getCount(), expectedRetentionCount);
    Assertions.assertEquals(policies.getRetention().getGranularity(), expectedRetentionGranularity);
    Assertions.assertEquals(policies.getSharingEnabled().booleanValue(), expectedSharing);
  }

  private void verifyRowCount(Operations ops, String tableName, int expectedRowCount) {
    List<Row> resultRows =
        ops.spark().sql(String.format("SELECT * FROM %s", tableName)).collectAsList();
    Assertions.assertEquals(expectedRowCount, resultRows.size());
  }

  private static void populateTable(
      Operations ops, String tableName, int numRows, int dayLag, long timestampSeconds) {
    String timestampEntry =
        String.format("date_sub(from_unixtime(%d), %d)", timestampSeconds, dayLag);
    for (int row = 0; row < numRows; ++row) {
      ops.spark()
          .sql(String.format("INSERT INTO %s VALUES ('v%d', %s)", tableName, row, timestampEntry))
          .show();
    }
  }

  private static void populateTable(Operations ops, String tableName, int numRows, int dayLag) {
    populateTable(ops, tableName, numRows, dayLag, System.currentTimeMillis() / 1000);
  }

  private static void populateTable(Operations ops, String tableName, int numRows) {
    populateTable(ops, tableName, numRows, 0);
  }

  private static void populateTableWithStringColumn(
      Operations ops, String tableName, int numRows, List<String> dataFormats) {
    for (String dataFormat : dataFormats) {
      for (int row = 0; row < numRows; ++row) {
        ops.spark()
            .sql(
                String.format(
                    "INSERT INTO %s VALUES ('%s', '%s')",
                    tableName, row, String.format(dataFormat, row)))
            .show();
      }
    }
  }

  private static void prepareTable(Operations ops, String tableName) {
    prepareTable(ops, tableName, false);
  }

  private static void prepareTable(Operations ops, String tableName, boolean isPartitioned) {
    ops.spark().sql(String.format("DROP TABLE IF EXISTS %s", tableName)).show();
    if (isPartitioned) {
      ops.spark()
          .sql(
              String.format(
                  "CREATE TABLE %s (data string, ts timestamp) partitioned by (days(ts))",
                  tableName))
          .show();
    } else {
      ops.spark()
          .sql(String.format("CREATE TABLE %s (data string, ts timestamp)", tableName))
          .show();
    }
    ops.spark().sql(String.format("DESCRIBE %s", tableName)).show();
  }

  private static void prepareTableWithStringColumn(Operations ops, String tableName) {
    ops.spark().sql(String.format("DROP TABLE IF EXISTS %s", tableName)).show();
    ops.spark()
        .sql(String.format("CREATE TABLE %s (data string, datePartition String)", tableName))
        .show();
    ops.spark().sql(String.format("DESCRIBE %s", tableName)).show();
  }

  private static void prepareTableWithPolicies(
      Operations ops, String tableName, String retention, boolean sharing) {
    ops.spark().sql(String.format("DROP TABLE IF EXISTS %s", tableName)).show();
    ops.spark()
        .sql(
            String.format(
                "CREATE TABLE %s (data string, ts timestamp) PARTITIONED BY (days(ts))", tableName))
        .show();
    ops.spark()
        .sql(String.format("ALTER TABLE %s SET POLICY (RETENTION=%s)", tableName, retention));
    ops.spark().sql(String.format("ALTER TABLE %s SET POLICY (SHARING=%s)", tableName, sharing));
    ops.spark().sql(String.format("DESCRIBE %s", tableName)).show();
  }

  private static void checkSnapshots(
      Operations ops, String tableName, List<Long> expectedSnapshotIds) {
    log.info("Checking snapshots");
    List<Long> foundSnapshotIds = getSnapshotIds(ops, tableName);
    Assertions.assertEquals(expectedSnapshotIds, foundSnapshotIds, "Incorrect list of snapshots");
  }

  private static void checkSnapshots(Table table, List<Long> expectedSnapshotIds) {
    log.info("Checking snapshots");
    List<Long> foundSnapshotIds =
        Lists.newArrayList(table.snapshots()).stream()
            .map(Snapshot::snapshotId)
            .collect(Collectors.toList());
    Assertions.assertEquals(expectedSnapshotIds, foundSnapshotIds, "Incorrect list of snapshots");
  }

  private static List<Long> getSnapshotIds(Operations ops, String tableName) {
    log.info("Getting snapshots");
    List<Row> snapshots =
        ops.spark().sql(String.format("SELECT * FROM %s.snapshots", tableName)).collectAsList();
    snapshots.forEach(s -> log.info(s.toString()));
    return snapshots.stream()
        .map(r -> r.getLong(r.fieldIndex("snapshot_id")))
        .collect(Collectors.toList());
  }

  private static List<Triple<String, String, Long>> getDataFiles(Operations ops, String tableName) {
    List<Row> dataFiles =
        ops.spark().sql(String.format("SELECT * FROM %s.data_files", tableName)).collectAsList();
    return dataFiles.stream()
        .map(
            r ->
                Triple.of(
                    r.getStruct(r.fieldIndex("partition")).json(),
                    r.getString(r.fieldIndex("file_path")),
                    r.getLong(r.fieldIndex("record_count"))))
        .collect(Collectors.toList());
  }
}
