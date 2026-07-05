package com.company.opsagent.controlplane.bootstrap.config;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.company.opsagent.controlplane.bootstrap.audit.FileBackedAuditTrail;
import com.company.opsagent.controlplane.modules.audit.R2dbcAuditTrail;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.spi.ConnectionFactories;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.r2dbc.connection.init.ConnectionFactoryInitializer;
import org.springframework.r2dbc.connection.init.ResourceDatabasePopulator;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.core.io.ClassPathResource;

class SecurityConfigurationAuditStorageTest {

  @Test
  void createsFileAuditTrailForFileStorageMode() {
    AuditProperties properties = new AuditProperties("file", "target/test-audit/file-mode.jsonl");

    var auditTrail = new SecurityConfiguration().auditTrail(
        properties,
        new ObjectMapper(),
        databaseClientProvider(databaseClient()));

    assertInstanceOf(FileBackedAuditTrail.class, auditTrail);
  }

  @Test
  void createsDatabaseAuditTrailForDatabaseStorageMode() {
    AuditProperties properties = new AuditProperties("database", "");

    var auditTrail = new SecurityConfiguration().auditTrail(
        properties,
        new ObjectMapper(),
        databaseClientProvider(databaseClient()));

    assertInstanceOf(R2dbcAuditTrail.class, auditTrail);
  }

  @Test
  void rejectsDatabaseStorageModeWithoutDatabaseClient() {
    AuditProperties properties = new AuditProperties("database", "");

    assertThrows(IllegalStateException.class, () -> new SecurityConfiguration().auditTrail(
        properties,
        new ObjectMapper(),
        databaseClientProvider(null)));
  }

  private ObjectProvider<DatabaseClient> databaseClientProvider(DatabaseClient databaseClient) {
    return new ObjectProvider<>() {
      @Override
      public DatabaseClient getObject() {
        return databaseClient;
      }

      @Override
      public DatabaseClient getIfAvailable() {
        return databaseClient;
      }
    };
  }

  private DatabaseClient databaseClient() {
    var connectionFactory = ConnectionFactories.get(
        "r2dbc:h2:mem:///audit-config-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
    var initializer = new ConnectionFactoryInitializer();
    initializer.setConnectionFactory(connectionFactory);
    initializer.setDatabasePopulator(new ResourceDatabasePopulator(
        new ClassPathResource("sql/migrations/V001__audit_event_schema.sql")));
    initializer.afterPropertiesSet();
    return DatabaseClient.create(connectionFactory);
  }
}
