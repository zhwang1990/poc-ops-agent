package com.company.opsagent.controlplane.bootstrap.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.company.opsagent.contracts.sqlworkbench.SqlConnectionSummary;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryAction;
import com.company.opsagent.controlplane.modules.sqlworkbench.SqlConnectionCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.spi.ConnectionFactories;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;

class SqlWorkbenchConfigurationTest {

  @Test
  void sqlWorkbenchInitializerSeedsLocalH2Connection() {
    var connectionFactory = ConnectionFactories.get(
        "r2dbc:h2:mem:///bootstrap-sql-workbench-seed-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
    var configuration = new SqlWorkbenchConfiguration();

    configuration.sqlWorkbenchSchemaInitializer(connectionFactory).afterPropertiesSet();

    SqlConnectionCatalog catalog = configuration.sqlConnectionCatalog(
        DatabaseClient.create(connectionFactory),
        new ObjectMapper());
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
  }
}
