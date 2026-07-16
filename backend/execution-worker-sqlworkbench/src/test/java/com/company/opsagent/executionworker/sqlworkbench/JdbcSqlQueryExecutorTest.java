package com.company.opsagent.executionworker.sqlworkbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.opsagent.contracts.sqlworkbench.SqlQueryAction;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryExecutionRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryLimits;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlTypedParameter;
import com.company.opsagent.contracts.workflow.OperatorContext;
import com.company.opsagent.contracts.workflow.PolicyDecisionReference;
import com.company.opsagent.contracts.workflow.TraceContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class JdbcSqlQueryExecutorTest {

  @Test
  void storesBoundedReadOnlyResultPage() throws Exception {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:sql-worker;DB_CLOSE_DELAY=-1");
    try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
      statement.execute("create table ORDERS (ORDER_ID integer primary key, STATUS varchar(20))");
      statement.execute("insert into ORDERS values (1, 'READY'), (2, 'PENDING')");
    }
    Clock clock = Clock.systemUTC();
    InMemorySqlResultStore store = new InMemorySqlResultStore(clock);
    JdbcSqlQueryExecutor executor = new JdbcSqlQueryExecutor(
        request -> dataSource,
        store,
        new ObjectMapper(),
        clock);

    String resultId = executor.execute(request());
    var page = store.find(resultId).orElseThrow();

    assertEquals(1, page.rows().size());
    assertEquals("ORDER_ID", page.columns().get(0).name());
    assertTrue(page.truncated());
  }

  @Test
  void commitsControlledDmlInShortTransaction() throws Exception {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:sql-worker-dml;DB_CLOSE_DELAY=-1");
    try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
      statement.execute("create table ORDERS (ORDER_ID integer primary key, STATUS varchar(20))");
      statement.execute("insert into ORDERS values (1, 'PENDING'), (2, 'PENDING')");
    }
    Clock clock = Clock.systemUTC();
    JdbcSqlQueryExecutor executor = new JdbcSqlQueryExecutor(
        request -> dataSource,
        new InMemorySqlResultStore(clock),
        new ObjectMapper(),
        clock);

    int affectedRows = executor.executeDml(request(
        SqlQueryAction.COMMIT_DML,
        "update ORDERS set STATUS = 'READY' where ORDER_ID = ?"));

    assertEquals(1, affectedRows);
    try (var connection = dataSource.getConnection();
        var statement = connection.createStatement();
        var resultSet = statement.executeQuery("select STATUS from ORDERS where ORDER_ID = 1")) {
      resultSet.next();
      assertEquals("READY", resultSet.getString(1));
    }
  }

  @Test
  void rollsBackDmlWhenSchemaSetupFailsAfterAutoCommitIsDisabled() throws Exception {
    Connection connection = mock(Connection.class);
    DataSource dataSource = mock(DataSource.class);
    when(dataSource.getConnection()).thenReturn(connection);
    doThrow(new SQLException("schema is unavailable")).when(connection).setSchema("MISSING");
    JdbcSqlQueryExecutor executor = executor(dataSource);

    assertThrows(IllegalStateException.class, () -> executor.executeDml(dmlRequest(
        "MISSING",
        List.of())));

    verify(connection).setAutoCommit(false);
    verify(connection).rollback();
  }

  @Test
  void rollsBackDmlWhenRuntimeParameterBindingFailsAfterAutoCommitIsDisabled() throws Exception {
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    DataSource dataSource = mock(DataSource.class);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.prepareStatement("update ORDERS set STATUS = ? where ORDER_ID = 1")).thenReturn(statement);
    JdbcSqlQueryExecutor executor = executor(dataSource);

    assertThrows(IllegalArgumentException.class, () -> executor.executeDml(dmlRequest(
        "PUBLIC",
        List.of(new SqlTypedParameter("status", "UNSUPPORTED", IntNode.valueOf(1))))));

    verify(connection).setAutoCommit(false);
    verify(connection).rollback();
  }

  @Test
  void closesDmlStatementBeforeCommit() throws Exception {
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    DataSource dataSource = mock(DataSource.class);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.prepareStatement("update ORDERS set STATUS = ? where ORDER_ID = 1")).thenReturn(statement);
    when(statement.executeUpdate()).thenReturn(1);
    JdbcSqlQueryExecutor executor = executor(dataSource);

    assertEquals(1, executor.executeDml(dmlRequest(
        "PUBLIC",
        List.of(new SqlTypedParameter("status", "STRING", new com.fasterxml.jackson.databind.node.TextNode("READY"))))));

    var order = inOrder(statement, connection);
    order.verify(statement).close();
    order.verify(connection).commit();
  }

  @Test
  void preservesSuccessfulCommitWhenConnectionCleanupFails() throws Exception {
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    DataSource dataSource = mock(DataSource.class);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.prepareStatement("update ORDERS set STATUS = ? where ORDER_ID = 1")).thenReturn(statement);
    when(statement.executeUpdate()).thenReturn(1);
    doThrow(new SQLException("connection cleanup failed")).when(connection).close();
    JdbcSqlQueryExecutor executor = executor(dataSource);

    int affectedRows = executor.executeDml(dmlRequest(
        "PUBLIC",
        List.of(new SqlTypedParameter("status", "STRING", new com.fasterxml.jackson.databind.node.TextNode("READY")))));

    assertEquals(1, affectedRows);
    verify(connection).commit();
    verify(connection, never()).rollback();
  }

  private SqlQueryExecutionRequest request() {
    return request(
        SqlQueryAction.RUN_READ_ONLY,
        "select ORDER_ID, STATUS from ORDERS where ORDER_ID >= ? order by ORDER_ID");
  }

  private SqlQueryExecutionRequest request(SqlQueryAction action, String sql) {
    return request(action, sql, "PUBLIC", List.of(new SqlTypedParameter("minimumOrderId", "INTEGER", IntNode.valueOf(1))));
  }

  private SqlQueryExecutionRequest dmlRequest(String schema, List<SqlTypedParameter> parameters) {
    return request(
        SqlQueryAction.COMMIT_DML,
        "update ORDERS set STATUS = ? where ORDER_ID = 1",
        schema,
        parameters);
  }

  private SqlQueryExecutionRequest request(
      SqlQueryAction action,
      String sql,
      String schema,
      List<SqlTypedParameter> parameters) {
    var query = new SqlQueryRequest(
        "1.0",
        "as400-development",
        "development",
        schema,
        action,
        sql,
        parameters,
        new SqlQueryLimits(1, 5_000_000, 30),
        "key");
    return new SqlQueryExecutionRequest(
        "1.0",
        "execution-1",
        "workflow-1",
        query,
        "sha256:test",
        new OperatorContext("operator-1", List.of("ROLE_ops-reader")),
        new PolicyDecisionReference("decision-1", "policy-v1", "ALLOW"),
        new TraceContext("trace-1", "request-1"),
        OffsetDateTime.now().plusSeconds(30));
  }

  private JdbcSqlQueryExecutor executor(DataSource dataSource) {
    Clock clock = Clock.systemUTC();
    return new JdbcSqlQueryExecutor(dataSourceRequest -> dataSource, new InMemorySqlResultStore(clock), new ObjectMapper(), clock);
  }
}
