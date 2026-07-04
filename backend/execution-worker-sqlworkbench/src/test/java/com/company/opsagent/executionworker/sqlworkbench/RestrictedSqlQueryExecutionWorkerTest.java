package com.company.opsagent.executionworker.sqlworkbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.company.opsagent.contracts.sqlworkbench.SqlQueryAction;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryExecutionRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryLimits;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryRequest;
import com.company.opsagent.contracts.workflow.OperatorContext;
import com.company.opsagent.contracts.workflow.PolicyDecisionReference;
import com.company.opsagent.contracts.workflow.TraceContext;
import java.sql.SQLSyntaxErrorException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class RestrictedSqlQueryExecutionWorkerTest {

  private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-12T00:00:00Z"), ZoneOffset.UTC);

  @Test
  void rejectsDmlHiddenInsideReadOnlyExecutionEnvelope() {
    var worker = new RestrictedSqlQueryExecutionWorker(new CalciteSqlReadOnlyGuard(), request -> "result-1", CLOCK);

    var result = worker.execute(request("update ORDERS.ORDERS set status = 'READY'", "development", 30));

    assertEquals("REJECTED", result.status());
    assertEquals("SQL_NOT_READ_ONLY", result.errorCode());
  }

  @Test
  void rejectsExpiredRequest() {
    var worker = new RestrictedSqlQueryExecutionWorker(new CalciteSqlReadOnlyGuard(), request -> "result-1", CLOCK);

    var result = worker.execute(request("select * from ORDERS.ORDERS", "development", -1));

    assertEquals("REJECTED", result.status());
    assertEquals("REQUEST_EXPIRED", result.errorCode());
  }

  @Test
  void acceptsValidatedSelect() {
    var worker = new RestrictedSqlQueryExecutionWorker(new CalciteSqlReadOnlyGuard(), request -> "result-1", CLOCK);

    var result = worker.execute(request("select * from ORDERS.ORDERS", "development", 30));

    assertEquals("SUCCEEDED", result.status());
    assertEquals("result-1", result.resultId());
  }

  @Test
  void acceptsProductionReadOnlyQuery() {
    var worker = new RestrictedSqlQueryExecutionWorker(new CalciteSqlReadOnlyGuard(), request -> "result-1", CLOCK);

    var result = worker.execute(request(
        "select * from ORDERS.ORDERS",
        "production",
        SqlQueryAction.RUN_READ_ONLY,
        30));

    assertEquals("SUCCEEDED", result.status());
    assertEquals("result-1", result.resultId());
  }

  @Test
  void acceptsControlledDmlInSitAndReturnsAffectedRows() {
    var worker = new RestrictedSqlQueryExecutionWorker(
        new CalciteSqlReadOnlyGuard(),
        new CalciteSqlDmlGuard(),
        executor(2),
        CLOCK);

    var result = worker.execute(request(
        "update ORDERS.ORDERS set status = 'READY' where order_id = 42",
        "sit",
        SqlQueryAction.COMMIT_DML,
        30));

    assertEquals("SUCCEEDED", result.status());
    assertEquals(2, result.affectedRows());
  }

  @Test
  void rejectsProductionControlledDmlBeforeWorkerSubmission() {
    assertThrows(IllegalArgumentException.class, () -> request(
        "delete from ORDERS.ORDERS where order_id = 42",
        "production",
        SqlQueryAction.COMMIT_DML,
        30));
  }

  @Test
  void mapsEgressPolicyRejectionToRejectedResult() {
    var worker = new RestrictedSqlQueryExecutionWorker(
        new CalciteSqlReadOnlyGuard(),
        request -> {
          throw new WorkerSqlEgressException("SQL_EGRESS_NOT_ALLOWED", "SQL egress target is not allowed");
        },
        CLOCK);

    var result = worker.execute(request("select * from ORDERS.ORDERS", "development", 30));

    assertEquals("REJECTED", result.status());
    assertEquals("SQL_EGRESS_NOT_ALLOWED", result.errorCode());
  }

  @Test
  void includesSafeRootCauseForExecutionFailure() {
    var worker = new RestrictedSqlQueryExecutionWorker(
        new CalciteSqlReadOnlyGuard(),
        request -> {
          throw new IllegalStateException(
              "read-only JDBC query failed",
              new SQLSyntaxErrorException(
                  "Table \"ELADREFP\" not found; SQL statement: select * from eladrefp [42102-224]",
                  "42S02",
                  42102));
        },
        CLOCK);

    var result = worker.execute(request("select * from eladrefp", "development", 30));

    assertEquals("FAILED", result.status());
    assertEquals("SQL_EXECUTION_FAILED", result.errorCode());
    assertEquals(
        String.join(
            System.lineSeparator(),
            "SQL query execution failed",
            "failureType=SQLSyntaxErrorException",
            "sqlState=42S02",
            "vendorCode=42102",
            "message=Table \"ELADREFP\" not found"),
        result.errorMessage());
  }

  private SqlQueryExecutionRequest request(String sql, String environment, int expiresInSeconds) {
    return request(sql, environment, SqlQueryAction.RUN_READ_ONLY, expiresInSeconds);
  }

  private SqlQueryExecutionRequest request(
      String sql,
      String environment,
      SqlQueryAction action,
      int expiresInSeconds) {
    var query = new SqlQueryRequest(
        "1.0",
        "as400-development",
        environment,
        "ORDERS",
        action,
        sql,
        List.of(),
        new SqlQueryLimits(500, 5_000_000, 30),
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
        OffsetDateTime.now(CLOCK).plusSeconds(expiresInSeconds));
  }

  private SqlQueryExecutor executor(int affectedRows) {
    return new SqlQueryExecutor() {
      @Override
      public String execute(SqlQueryExecutionRequest request) {
        return "result-1";
      }

      @Override
      public int executeDml(SqlQueryExecutionRequest request) {
        return affectedRows;
      }
    };
  }
}
