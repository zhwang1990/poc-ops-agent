package com.company.opsagent.executionworker.sqlworkbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.company.opsagent.contracts.sqlworkbench.SqlQueryAction;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryExecutionRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryLimits;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryRequest;
import com.company.opsagent.contracts.workflow.OperatorContext;
import com.company.opsagent.contracts.workflow.PolicyDecisionReference;
import com.company.opsagent.contracts.workflow.TraceContext;
import java.time.OffsetDateTime;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class ConfiguredSqlDataSourceRegistryTest {

  @Test
  void rejectsH2DmlWhenDedicatedWriteCredentialIsUnavailable() {
    WorkerSqlConnectionDescriptor descriptor = h2Descriptor("demo-h2-writer", "DEMO_H2_WRITER");
    ConfiguredSqlDataSourceRegistry registry = registry(descriptor, alias -> {
      throw new IllegalStateException("SQL credential KeyStore is not configured");
    });

    WorkerSqlEgressException exception = assertThrows(
        WorkerSqlEgressException.class,
        () -> registry.resolve(request(SqlQueryAction.COMMIT_DML)));

    assertEquals("SQL_DML_WORKER_DISABLED", exception.errorCode());
  }

  @Test
  void rejectsDb2DmlWhenDedicatedWriteCredentialIsUnavailable() {
    WorkerSqlConnectionDescriptor descriptor = new WorkerSqlConnectionDescriptor(
        "as400-sit",
        "sit",
        "DB2_FOR_I",
        "as400-sit.internal",
        446,
        "as400-sit-readonly",
        "readonly-user",
        true,
        true,
        "as400-sit-writer",
        "as400-sit-writer");
    ConfiguredSqlDataSourceRegistry registry = registry(descriptor, alias -> {
      throw new IllegalStateException("failed to unlock SQL credential KeyStore");
    });

    WorkerSqlEgressException exception = assertThrows(
        WorkerSqlEgressException.class,
        () -> registry.resolve(request(descriptor, SqlQueryAction.COMMIT_DML)));

    assertEquals("SQL_DML_WORKER_DISABLED", exception.errorCode());
  }

  @Test
  void usesSeparateH2DataSourceForValidatedDmlCredential() {
    WorkerSqlConnectionDescriptor descriptor = h2Descriptor("demo-h2-writer", "DEMO_H2_WRITER");
    ConfiguredSqlDataSourceRegistry registry = registry(descriptor, alias -> "write-password".toCharArray());

    DataSource readDataSource = registry.resolve(request(SqlQueryAction.RUN_READ_ONLY));
    DataSource writeDataSource = registry.resolve(request(descriptor, SqlQueryAction.COMMIT_DML));

    assertNotSame(readDataSource, writeDataSource);
  }

  @Test
  void doesNotReinitializeH2DemoDataWhenCreatingDedicatedWriteSource() throws Exception {
    WorkerSqlConnectionDescriptor descriptor = h2Descriptor("demo-h2-writer", "DEMO_H2_WRITER");
    ConfiguredSqlDataSourceRegistry registry = registry(descriptor, alias -> "write-password".toCharArray());
    DataSource readDataSource = registry.resolve(request(SqlQueryAction.RUN_READ_ONLY));
    try (var connection = readDataSource.getConnection(); var statement = connection.createStatement()) {
      statement.executeUpdate("update PUBLIC.ORDERS set STATUS = 'CHANGED' where ORDER_ID = 2");
    }

    DataSource writeDataSource = registry.resolve(request(SqlQueryAction.COMMIT_DML));
    try (var connection = writeDataSource.getConnection();
        var statement = connection.createStatement();
        var rows = statement.executeQuery("select STATUS from PUBLIC.ORDERS where ORDER_ID = 2")) {
      assertTrue(rows.next());
      assertEquals("CHANGED", rows.getString(1));
    }
  }

  @Test
  void rejectsH2DmlWhenDedicatedWriteUsernameIsMissing() {
    WorkerSqlConnectionDescriptor descriptor = h2Descriptor("demo-h2-writer", null);
    ConfiguredSqlDataSourceRegistry registry = registry(descriptor, alias -> "write-password".toCharArray());

    WorkerSqlEgressException exception = assertThrows(
        WorkerSqlEgressException.class,
        () -> registry.resolve(request(SqlQueryAction.COMMIT_DML)));

    assertEquals("SQL_DML_WORKER_DISABLED", exception.errorCode());
  }

  @Test
  void rejectsH2DmlWhenDedicatedWriteAliasIsMissing() {
    WorkerSqlConnectionDescriptor descriptor = h2Descriptor(null, "DEMO_H2_WRITER");
    ConfiguredSqlDataSourceRegistry registry = registry(descriptor, alias -> "write-password".toCharArray());

    WorkerSqlEgressException exception = assertThrows(
        WorkerSqlEgressException.class,
        () -> registry.resolve(request(descriptor, SqlQueryAction.COMMIT_DML)));

    assertEquals("SQL_DML_WORKER_DISABLED", exception.errorCode());
  }

  @Test
  void rejectsH2DmlWhenDedicatedWritePasswordIsMissing() {
    WorkerSqlConnectionDescriptor descriptor = h2Descriptor("demo-h2-writer", "DEMO_H2_WRITER");
    ConfiguredSqlDataSourceRegistry registry = registry(descriptor, alias -> new char[0]);

    WorkerSqlEgressException exception = assertThrows(
        WorkerSqlEgressException.class,
        () -> registry.resolve(request(SqlQueryAction.COMMIT_DML)));

    assertEquals("SQL_DML_WORKER_DISABLED", exception.errorCode());
  }

  @Test
  void usesConfiguredH2WriterIdentityAndPasswordForControlledDml() throws Exception {
    WorkerSqlConnectionDescriptor descriptor = h2Descriptor("demo-h2-writer", "DEMO_H2_WRITER");
    ConfiguredSqlDataSourceRegistry registry = registry(descriptor, alias -> "write-password".toCharArray());
    DataSource readDataSource = registry.resolve(request(SqlQueryAction.RUN_READ_ONLY));

    DataSource writeDataSource = registry.resolve(request(SqlQueryAction.COMMIT_DML));
    try (var connection = writeDataSource.getConnection();
        var statement = connection.createStatement()) {
      assertEquals("DEMO_H2_WRITER", connection.getMetaData().getUserName());
      assertEquals(1, statement.executeUpdate(
          "update PUBLIC.ORDERS set STATUS = 'WRITER_READY' where ORDER_ID = 1"));
    }

    try (var connection = readDataSource.getConnection();
        var statement = connection.createStatement();
        var rows = statement.executeQuery("select STATUS from PUBLIC.ORDERS where ORDER_ID = 1")) {
      assertTrue(rows.next());
      assertEquals("WRITER_READY", rows.getString(1));
    }
  }

  @Test
  void initializesH2DemoDataBeforeTheFirstWriterConnection() throws Exception {
    WorkerSqlConnectionDescriptor descriptor = h2Descriptor(
        "writer-first-h2",
        "demo-h2-writer",
        "DEMO_H2_WRITER");
    ConfiguredSqlDataSourceRegistry registry = registry(descriptor, alias -> "write-password".toCharArray());

    DataSource writeDataSource = registry.resolve(request(descriptor, SqlQueryAction.COMMIT_DML));
    try (var connection = writeDataSource.getConnection();
        var statement = connection.createStatement()) {
      assertEquals(1, statement.executeUpdate(
          "update PUBLIC.ORDERS set STATUS = 'WRITER_FIRST' where ORDER_ID = 1"));
    }

    DataSource readDataSource = registry.resolve(request(descriptor, SqlQueryAction.RUN_READ_ONLY));
    try (var connection = readDataSource.getConnection();
        var statement = connection.createStatement();
        var rows = statement.executeQuery("select STATUS from PUBLIC.ORDERS where ORDER_ID = 1")) {
      assertTrue(rows.next());
      assertEquals("WRITER_FIRST", rows.getString(1));
    }
  }

  private ConfiguredSqlDataSourceRegistry registry(
      WorkerSqlConnectionDescriptor descriptor,
      SqlPasswordProvider passwordProvider) {
    return new ConfiguredSqlDataSourceRegistry(
        new WorkerSqlEgressPolicy(
            List.of(descriptor),
            List.of(new WorkerSqlEgressTarget(descriptor.host(), descriptor.port()))),
        passwordProvider,
        new Jt400DataSourceFactory(),
        new H2SqlDataSourceFactory());
  }

  private WorkerSqlConnectionDescriptor h2Descriptor(String dmlCredentialAlias, String dmlUsername) {
    return h2Descriptor("demo-h2", dmlCredentialAlias, dmlUsername);
  }

  private WorkerSqlConnectionDescriptor h2Descriptor(
      String connectionId,
      String dmlCredentialAlias,
      String dmlUsername) {
    return new WorkerSqlConnectionDescriptor(
        connectionId,
        "dev",
        "H2",
        "localhost",
        9092,
        "demo-h2-readonly",
        "readonly-user",
        true,
        true,
        dmlCredentialAlias,
        dmlUsername);
  }

  private SqlQueryExecutionRequest request(SqlQueryAction action) {
    return request(h2Descriptor("demo-h2-writer", "DEMO_H2_WRITER"), action);
  }

  private SqlQueryExecutionRequest request(
      WorkerSqlConnectionDescriptor descriptor,
      SqlQueryAction action) {
    return new SqlQueryExecutionRequest(
        "1.0",
        "execution-1",
        "workflow-1",
        new SqlQueryRequest(
            "1.0",
            descriptor.connectionId(),
            descriptor.targetEnvironment(),
            "PUBLIC",
            action,
            "update PUBLIC.ORDERS set STATUS = 'READY' where ORDER_ID = 1",
            List.of(),
            new SqlQueryLimits(20, 1_000_000, 30),
            "idempotency-1"),
        "sha256:validation",
        new OperatorContext("operator-1", List.of("ROLE_sql-operator")),
        new PolicyDecisionReference("decision-1", "policy-v1", "ALLOW"),
        new TraceContext("trace-1", "request-1"),
        OffsetDateTime.now().plusSeconds(30));
  }

}
