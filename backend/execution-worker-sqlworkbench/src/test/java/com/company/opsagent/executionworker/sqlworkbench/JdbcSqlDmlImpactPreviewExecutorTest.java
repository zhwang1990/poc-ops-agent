package com.company.opsagent.executionworker.sqlworkbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.opsagent.contracts.sqlworkbench.SqlDmlImpactPreview;
import com.company.opsagent.contracts.sqlworkbench.SqlDmlPreflightExecutionRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlDmlPreviewSelection;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryAction;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryLimits;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlTypedParameter;
import com.company.opsagent.contracts.workflow.OperatorContext;
import com.company.opsagent.contracts.workflow.PolicyDecisionReference;
import com.company.opsagent.contracts.workflow.TraceContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JdbcSqlDmlImpactPreviewExecutorTest {

  private JdbcDataSource database;

  @BeforeEach
  void setUp() throws Exception {
    database = new JdbcDataSource();
    database.setURL("jdbc:h2:mem:dml-preview-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
    try (Connection connection = database.getConnection(); var statement = connection.createStatement()) {
      statement.execute("create table ORDERS (ORDER_ID integer primary key, STATUS varchar(20), CUSTOMER_ID varchar(20))");
      statement.execute("insert into ORDERS values (1, 'PENDING', 'CUST-001'), (2, 'PENDING', 'CUST-002'), (3, 'READY', 'CUST-003')");
    }
  }

  @Test
  void previewsUpdateUsingReadOnlyCountAndMaskedSamples() throws Exception {
    Connection connection = spy(database.getConnection());
    DataSource dataSource = dataSource(connection);
    var executor = new JdbcSqlDmlImpactPreviewExecutor(request -> dataSource, new ObjectMapper());

    SqlDmlImpactPreview preview = executor.preview(preflight(
        List.of("ORDER_ID", "CUSTOMER_ID"),
        List.of("CUSTOMER_ID"))).block();

    assertEquals(2L, preview.affectedRows());
    assertEquals(2, preview.sampleRows().size());
    assertEquals("***", preview.sampleRows().getFirst().get(1).asText());
    assertTrue(preview.sampleColumns().get(1).masked());
    verify(connection).setReadOnly(true);
    verify(connection).setAutoCommit(false);
    verify(connection).rollback();
    verify(connection, never()).commit();
  }

  @Test
  void returnsNoSamplesWhenPolicyDoesNotSelectPreviewColumns() throws Exception {
    Connection connection = spy(database.getConnection());
    DataSource dataSource = dataSource(connection);
    var executor = new JdbcSqlDmlImpactPreviewExecutor(
        request -> dataSource,
        new ObjectMapper());

    SqlDmlImpactPreview preview = executor.preview(preflight(List.of(), List.of())).block();

    assertEquals(2L, preview.affectedRows());
    assertTrue(preview.sampleColumns().isEmpty());
    assertTrue(preview.sampleRows().isEmpty());
    verify(connection).rollback();
    verify(connection, never()).commit();
  }

  @Test
  void rollsBackPreviewTransactionWhenSampleQueryFails() throws Exception {
    Connection connection = spy(database.getConnection());
    DataSource dataSource = dataSource(connection);
    var executor = new JdbcSqlDmlImpactPreviewExecutor(
        request -> dataSource,
        new ObjectMapper());

    RuntimeException failure = assertThrows(
        RuntimeException.class,
        () -> executor.preview(preflight(List.of("MISSING_COLUMN"), List.of())).block());

    assertEquals("read-only JDBC DML preview failed", failure.getMessage());
    verify(connection).rollback();
    verify(connection, never()).commit();
  }

  @Test
  void capsPreviewSamplesAtTwentyRows() throws Exception {
    try (Connection connection = database.getConnection(); var statement = connection.createStatement()) {
      for (int orderId = 4; orderId <= 28; orderId++) {
        statement.execute("insert into ORDERS values (" + orderId + ", 'PENDING', 'CUST-" + orderId + "')");
      }
    }
    var executor = new JdbcSqlDmlImpactPreviewExecutor(request -> database, new ObjectMapper());

    SqlDmlImpactPreview preview = executor.preview(preflight(
        List.of("ORDER_ID", "CUSTOMER_ID"),
        List.of("CUSTOMER_ID"))).block();

    assertEquals(27L, preview.affectedRows());
    assertEquals(20, preview.sampleRows().size());
    assertTrue(preview.sampleRows().stream().allMatch(row -> "***".equals(row.get(1).asText())));
  }

  @Test
  void estimatesSingleValuesInsertWithoutDatabaseAccess() {
    AtomicBoolean databaseAccessed = new AtomicBoolean();
    var executor = new JdbcSqlDmlImpactPreviewExecutor(request -> {
      databaseAccessed.set(true);
      return database;
    }, new ObjectMapper());

    SqlDmlImpactPreview preview = executor.preview(preflight(
        "insert into ORDERS (ORDER_ID, STATUS, CUSTOMER_ID) values (?, ?, ?)",
        List.of(
            new SqlTypedParameter("orderId", "INTEGER", IntNode.valueOf(29)),
            new SqlTypedParameter("status", "STRING", TextNode.valueOf("PENDING")),
            new SqlTypedParameter("customerId", "STRING", TextNode.valueOf("CUST-029"))),
        List.of(),
        List.of())).block();

    assertEquals(1L, preview.affectedRows());
    assertTrue(preview.sampleRows().isEmpty());
    assertTrue(preview.unverifiedItems().size() > 0);
    assertTrue(!databaseAccessed.get());
  }

  private DataSource dataSource(Connection connection) throws Exception {
    DataSource dataSource = org.mockito.Mockito.mock(DataSource.class);
    when(dataSource.getConnection()).thenReturn(connection);
    return dataSource;
  }

  private SqlDmlPreflightExecutionRequest preflight(
      List<String> sampleColumns,
      List<String> maskedColumns) {
    return preflight(
        "update ORDERS set STATUS = ? where ORDER_ID >= ? and STATUS = 'PENDING'",
        List.of(
            new SqlTypedParameter("status", "STRING", TextNode.valueOf("READY")),
            new SqlTypedParameter("minimumOrderId", "INTEGER", IntNode.valueOf(1))),
        sampleColumns,
        maskedColumns);
  }

  private SqlDmlPreflightExecutionRequest preflight(
      String sql,
      List<SqlTypedParameter> parameters,
      List<String> sampleColumns,
      List<String> maskedColumns) {
    SqlQueryRequest query = new SqlQueryRequest(
        "1.0",
        "h2-sit",
        "sit",
        "PUBLIC",
        SqlQueryAction.PREFLIGHT_DML,
        sql,
        parameters,
        new SqlQueryLimits(500, 5_000_000, 30),
        "preview-key");
    return new SqlDmlPreflightExecutionRequest(
        "1.0",
        "preflight-execution-1",
        "workflow-1",
        query,
        "sha256:validation",
        new SqlDmlPreviewSelection("1.0", sampleColumns, maskedColumns),
        new OperatorContext("operator-1", List.of("ROLE_sql-operator")),
        new PolicyDecisionReference("decision-1", "policy-v1", "ALLOW"),
        new TraceContext("trace-1", "request-1"),
        OffsetDateTime.now().plusMinutes(1));
  }
}
