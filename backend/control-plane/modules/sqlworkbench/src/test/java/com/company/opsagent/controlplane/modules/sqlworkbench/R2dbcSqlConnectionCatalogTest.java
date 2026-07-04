package com.company.opsagent.controlplane.modules.sqlworkbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.company.opsagent.contracts.sqlworkbench.SqlConnectionCreateRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlConnectionSummary;
import com.company.opsagent.contracts.sqlworkbench.SqlConnectionUpdateRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryAction;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.r2dbc.connection.init.ConnectionFactoryInitializer;
import org.springframework.r2dbc.connection.init.ResourceDatabasePopulator;
import org.springframework.r2dbc.core.DatabaseClient;

class R2dbcSqlConnectionCatalogTest {

  @Test
  void createsUpdatesAndDeletesConnectionsInDatabase() {
    R2dbcSqlConnectionCatalog catalog = testCatalog();

    SqlConnectionSummary created = catalog.create(new SqlConnectionCreateRequest(
        "1.0",
        "H2 Local Test",
        "test",
        "H2",
        "localhost",
        9092,
        "PUBLIC",
        List.of("PUBLIC"),
        List.of(SqlQueryAction.VALIDATE, SqlQueryAction.RUN_READ_ONLY, SqlQueryAction.PREFLIGHT_DML, SqlQueryAction.COMMIT_DML),
        "h2-local-readonly",
        500,
        30));

    assertEquals("h2-local-test", created.connectionId());
    assertEquals("PENDING_WORKER_BINDING", created.status());
    assertEquals("h2-local-readonly", created.credentialAlias());
    assertTrue(catalog.find("h2-local-test").isPresent());

    SqlConnectionSummary updated = catalog.update("h2-local-test", new SqlConnectionUpdateRequest(
        "1.0",
        "H2 Local Test",
        "test",
        "H2",
        "localhost",
        9092,
        "PUBLIC",
        List.of("PUBLIC"),
        List.of(SqlQueryAction.VALIDATE, SqlQueryAction.RUN_READ_ONLY),
        "h2-local-readonly",
        250,
        20));

    assertEquals(List.of(SqlQueryAction.VALIDATE, SqlQueryAction.RUN_READ_ONLY), updated.capabilities());
    assertEquals(250, updated.maxRowsDefault());
    assertEquals("PENDING_WORKER_BINDING", updated.status());

    SqlConnectionSummary ready = catalog.updateStatus("h2-local-test", "READY");
    assertEquals("READY", ready.status());

    catalog.delete("h2-local-test");
    assertFalse(catalog.find("h2-local-test").isPresent());
  }

  @Test
  void localStartupSeedProvidesH2LocalTestConnection() {
    R2dbcSqlConnectionCatalog catalog = seededTestCatalog();

    SqlConnectionSummary connection = catalog.find("h2-local-test").orElseThrow();

    assertEquals("H2 Local Test", connection.displayName());
    assertEquals("sit", connection.targetEnvironment());
    assertEquals("H2", connection.platformType());
    assertEquals("localhost", connection.host());
    assertEquals(9092, connection.port());
    assertEquals("PUBLIC", connection.defaultSchema());
    assertEquals(List.of("PUBLIC"), connection.allowedSchemas());
    assertEquals(
        List.of(SqlQueryAction.VALIDATE, SqlQueryAction.RUN_READ_ONLY, SqlQueryAction.PREFLIGHT_DML, SqlQueryAction.COMMIT_DML),
        connection.capabilities());
    assertEquals("h2-local-readonly", connection.credentialAlias());
    assertEquals("READY", connection.status());
    assertEquals(500, connection.maxRowsDefault());
    assertEquals(30, connection.timeoutSecondsDefault());
  }

  @Test
  void localStartupSeedDoesNotDuplicateExistingH2Connection() {
    var connectionFactory = connectionFactory("sql-connection-existing-h2");
    initialize(connectionFactory, new ClassPathResource("sql/migrations/V001__sql_connection_catalog_schema.sql"));
    R2dbcSqlConnectionCatalog catalog = catalog(connectionFactory);
    catalog.create(new SqlConnectionCreateRequest(
        "1.0",
        "H2 Local Test",
        "test",
        "H2",
        "localhost",
        9092,
        "PUBLIC",
        List.of("PUBLIC"),
        List.of(SqlQueryAction.VALIDATE),
        "h2-local-readonly",
        100,
        10));

    initialize(
        connectionFactory,
        new ClassPathResource("sql/migrations/V002__local_h2_sql_connection_seed.sql"),
        new ClassPathResource("sql/migrations/V003__local_h2_sql_connection_controlled_crud.sql"));

    assertEquals(1, catalog.list().size());
    SqlConnectionSummary connection = catalog.find("h2-local-test").orElseThrow();
    assertEquals("sit", connection.targetEnvironment());
    assertEquals(
        List.of(SqlQueryAction.VALIDATE, SqlQueryAction.RUN_READ_ONLY, SqlQueryAction.PREFLIGHT_DML, SqlQueryAction.COMMIT_DML),
        connection.capabilities());
    assertEquals(100, connection.maxRowsDefault());
  }

  private R2dbcSqlConnectionCatalog testCatalog() {
    var connectionFactory = connectionFactory("sql-connection-catalog");
    initialize(connectionFactory, new ClassPathResource("sql/migrations/V001__sql_connection_catalog_schema.sql"));
    return catalog(connectionFactory);
  }

  private R2dbcSqlConnectionCatalog seededTestCatalog() {
    var connectionFactory = connectionFactory("sql-connection-seed");
    initialize(
        connectionFactory,
        new ClassPathResource("sql/migrations/V001__sql_connection_catalog_schema.sql"),
        new ClassPathResource("sql/migrations/V002__local_h2_sql_connection_seed.sql"),
        new ClassPathResource("sql/migrations/V003__local_h2_sql_connection_controlled_crud.sql"));
    return catalog(connectionFactory);
  }

  private R2dbcSqlConnectionCatalog catalog(ConnectionFactory connectionFactory) {
    return new R2dbcSqlConnectionCatalog(DatabaseClient.create(connectionFactory), new ObjectMapper());
  }

  private ConnectionFactory connectionFactory(String prefix) {
    return ConnectionFactories.get("r2dbc:h2:mem:///" + prefix + "-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
  }

  private void initialize(ConnectionFactory connectionFactory, ClassPathResource... scripts) {
    var initializer = new ConnectionFactoryInitializer();
    initializer.setConnectionFactory(connectionFactory);
    initializer.setDatabasePopulator(new ResourceDatabasePopulator(scripts));
    initializer.afterPropertiesSet();
  }
}
