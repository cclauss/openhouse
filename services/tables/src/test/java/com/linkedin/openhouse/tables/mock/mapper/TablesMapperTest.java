package com.linkedin.openhouse.tables.mock.mapper;

import static com.linkedin.openhouse.tables.model.TableModelConstants.*;

import com.linkedin.openhouse.tables.api.spec.v0.request.CreateUpdateTableRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.TimePartitionSpec;
import com.linkedin.openhouse.tables.common.TableType;
import com.linkedin.openhouse.tables.dto.mapper.TablesMapper;
import com.linkedin.openhouse.tables.model.TableDto;
import com.linkedin.openhouse.tables.model.TableDtoPrimaryKey;
import com.linkedin.openhouse.tables.model.TableModelConstants;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
public class TablesMapperTest {

  @Autowired protected TablesMapper tablesMapper;

  // Note that LOAD_ICEBERG_SNAPSHOT_REQUEST_BODY and PATCH_ICEBERG_SNAPSHOTS_BODY
  // are affiliated with CREATE_TABLE_REQUEST_BODY
  private static final TableDto TABLE_DTO =
      TableDto.builder()
          .databaseId(CREATE_TABLE_REQUEST_BODY_WITHIN_SNAPSHOTS_REQUEST.getDatabaseId())
          .tableId(CREATE_TABLE_REQUEST_BODY_WITHIN_SNAPSHOTS_REQUEST.getTableId())
          .clusterId(CREATE_TABLE_REQUEST_BODY_WITHIN_SNAPSHOTS_REQUEST.getClusterId())
          .tableProperties(ImmutableMap.of("oldk", "oldv"))
          .timePartitioning(
              TimePartitionSpec.builder()
                  .columnName("ts")
                  .granularity(TimePartitionSpec.Granularity.DAY)
                  .build())
          .tableVersion("v1")
          .schema("schema")
          .build();

  @Test
  public void testToGetTableResponseBody() {
    Assertions.assertEquals(
        TableModelConstants.GET_TABLE_RESPONSE_BODY,
        tablesMapper.toGetTableResponseBody(TableModelConstants.TABLE_DTO));
  }

  @Test
  public void testToTableDtoWithoutSnapshots() {
    TableDto tableDto = TableDto.builder().build();
    TableDto tableDto1 = tablesMapper.toTableDto(tableDto, CREATE_TABLE_REQUEST_BODY);
    Assertions.assertEquals(tableDto1.getDatabaseId(), CREATE_TABLE_REQUEST_BODY.getDatabaseId());
    Assertions.assertEquals(tableDto1.getTableId(), CREATE_TABLE_REQUEST_BODY.getTableId());
    Assertions.assertEquals(tableDto1.getClusterId(), CREATE_TABLE_REQUEST_BODY.getClusterId());
    Assertions.assertEquals(tableDto1.getSchema(), CREATE_TABLE_REQUEST_BODY.getSchema());
    Assertions.assertEquals(
        tableDto1.getTableProperties(), CREATE_TABLE_REQUEST_BODY.getTableProperties());
  }

  @Test
  public void testToTableDtoWithPutSnapshots() {
    TableDto tableDto1 =
        tablesMapper.toTableDto(TABLE_DTO, TableModelConstants.ICEBERG_SNAPSHOTS_REQUEST_BODY);
    Assertions.assertEquals(tableDto1.getDatabaseId(), TABLE_DTO.getDatabaseId());
    Assertions.assertEquals(tableDto1.getTableId(), TABLE_DTO.getTableId());
    Assertions.assertEquals(tableDto1.getClusterId(), TABLE_DTO.getClusterId());
    Assertions.assertEquals(
        tableDto1.getTableVersion(),
        TableModelConstants.ICEBERG_SNAPSHOTS_REQUEST_BODY.getBaseTableVersion());
    Assertions.assertEquals(
        tableDto1.getJsonSnapshots(), ICEBERG_SNAPSHOTS_REQUEST_BODY.getJsonSnapshots());

    // Metadata fields should come from metadata portion of patch snapshots body
    Assertions.assertEquals(
        tableDto1.getSchema(), CREATE_TABLE_REQUEST_BODY_WITHIN_SNAPSHOTS_REQUEST.getSchema());
    Assertions.assertEquals(
        tableDto1.getTableProperties(),
        CREATE_TABLE_REQUEST_BODY_WITHIN_SNAPSHOTS_REQUEST.getTableProperties());
    Assertions.assertEquals(
        tableDto1.getTimePartitioning(),
        CREATE_TABLE_REQUEST_BODY_WITHIN_SNAPSHOTS_REQUEST.getTimePartitioning());
  }

  @Test
  public void testToTableDtoPutSnapshotsWithTableType() {
    TableDto tableDto1 =
        tablesMapper.toTableDto(TABLE_DTO, TableModelConstants.ICEBERG_SNAPSHOTS_REQUEST_BODY);
    Assertions.assertEquals(tableDto1.getDatabaseId(), TABLE_DTO.getDatabaseId());
    Assertions.assertEquals(tableDto1.getTableId(), TABLE_DTO.getTableId());
    Assertions.assertEquals(tableDto1.getClusterId(), TABLE_DTO.getClusterId());
    Assertions.assertEquals(tableDto1.getTableType(), TableType.REPLICA_TABLE);
  }

  @Test
  public void testToTableDtoWithStagedCreate() {
    TableDto tableDto = TableDto.builder().build();
    TableDto tableDto1 =
        tablesMapper.toTableDto(
            tableDto,
            CreateUpdateTableRequestBody.builder()
                .tableId("t1")
                .databaseId("create")
                .clusterId(CLUSTER_NAME)
                .schema(HEALTH_SCHEMA_LITERAL)
                .policies(TABLE_POLICIES)
                .stageCreate(true)
                .build());
    Assertions.assertEquals(tableDto1.getDatabaseId(), CREATE_TABLE_REQUEST_BODY.getDatabaseId());
    Assertions.assertEquals(tableDto1.getTableId(), CREATE_TABLE_REQUEST_BODY.getTableId());
    Assertions.assertEquals(tableDto1.getClusterId(), CREATE_TABLE_REQUEST_BODY.getClusterId());
    Assertions.assertEquals(tableDto1.getSchema(), CREATE_TABLE_REQUEST_BODY.getSchema());
    Assertions.assertTrue(tableDto1.isStageCreate());
  }

  @Test
  public void testToTableDtoPrimaryKey() {
    TableIdentifier tableIdentifier = TableIdentifier.of("d1", "t1");
    TableDtoPrimaryKey tableDtoPrimaryKey = tablesMapper.toTableDtoPrimaryKey(tableIdentifier);
    Assertions.assertEquals("d1", tableDtoPrimaryKey.getDatabaseId());
    Assertions.assertEquals("t1", tableDtoPrimaryKey.getTableId());
  }
}
