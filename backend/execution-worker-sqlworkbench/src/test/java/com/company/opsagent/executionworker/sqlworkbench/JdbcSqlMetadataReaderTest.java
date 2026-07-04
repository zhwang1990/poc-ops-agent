package com.company.opsagent.executionworker.sqlworkbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.company.opsagent.contracts.sqlworkbench.SqlConnectionSummary;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryAction;
import java.time.Clock;
import java.util.List;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class JdbcSqlMetadataReaderTest {

  @Test
  void readsTablesColumnsAndIndexesFromJdbcMetadataWithoutRows() throws Exception {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:sql-metadata;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=TRUE");
    try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
      statement.execute("""
          create table PUBLIC.ORDERS (
            ORDER_ID integer primary key,
            STATUS varchar(20) not null,
            AMOUNT decimal(12, 2)
          )
          """);
      statement.execute("create index IDX_ORDERS_STATUS on PUBLIC.ORDERS(STATUS)");
      statement.execute("insert into PUBLIC.ORDERS values (1, 'READY', 12.50)");
    }
    JdbcSqlMetadataReader reader = new JdbcSqlMetadataReader(
        new SqlDataSourceRegistry() {
          @Override
          public javax.sql.DataSource resolve(com.company.opsagent.contracts.sqlworkbench.SqlQueryExecutionRequest request) {
            return dataSource;
          }

          @Override
          public javax.sql.DataSource resolve(SqlConnectionSummary connection) {
            return dataSource;
          }
        },
        Clock.systemUTC());

    var metadata = reader.read(connection(), "PUBLIC");

    assertEquals("PUBLIC", metadata.schema());
    assertEquals("ORDERS", metadata.objects().getFirst().name());
    assertEquals("TABLE", metadata.objects().getFirst().type());
    assertEquals(List.of("ORDER_ID", "STATUS", "AMOUNT"), metadata.objects().getFirst().columns().stream()
        .map(column -> column.name())
        .toList());
    assertFalse(metadata.objects().getFirst().columns().get(1).nullable());
    assertTrue(metadata.objects().getFirst().indexes().stream()
        .anyMatch(index -> index.columns().contains("STATUS")));
    assertFalse(metadata.toString().contains("READY"));
  }

  private SqlConnectionSummary connection() {
    return new SqlConnectionSummary(
        "1.0",
        "h2-local-test",
        "H2 Local Test",
        "sit",
        "H2",
        "localhost",
        9092,
        "PUBLIC",
        List.of("PUBLIC"),
        List.of(SqlQueryAction.VALIDATE, SqlQueryAction.RUN_READ_ONLY, SqlQueryAction.PREFLIGHT_DML, SqlQueryAction.COMMIT_DML),
        "h2-local-readonly",
        "READY",
        500,
        30);
  }
}
