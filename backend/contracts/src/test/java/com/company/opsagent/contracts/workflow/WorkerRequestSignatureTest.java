package com.company.opsagent.contracts.workflow;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.company.opsagent.contracts.sqlworkbench.SqlControlledDmlExecutionRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlDmlCommitRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlDmlConfirmation;
import com.company.opsagent.contracts.sqlworkbench.SqlDmlExecutionBinding;
import com.company.opsagent.contracts.sqlworkbench.SqlDmlPreflightExecutionRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlDmlPreviewSelection;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryAction;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryExecutionRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryLimits;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlTypedParameter;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证 DML 预检和提交信封的签名绑定。
 */
class WorkerRequestSignatureTest {

  private static final String KEY_ID = "worker-key-a";
  private static final String TIMESTAMP = "2026-07-16T12:00:00Z";
  private static final String SECRET = "worker-transport-test-secret";

  @Test
  void controlledDmlSignatureChangesWhenSqlChanges() {
    String first = signature(controlledDml("UPDATE customer SET status = ? WHERE customer_id = ?", "ACTIVE",
        SqlDmlConfirmation.RISK_CONFIRMATION_CODE, "policy-v1", "confirmation-hash"));
    String second = signature(controlledDml("UPDATE customer SET status = ? WHERE account_id = ?", "ACTIVE",
        SqlDmlConfirmation.RISK_CONFIRMATION_CODE, "policy-v1", "confirmation-hash"));

    assertNotEquals(first, second);
  }

  @Test
  void controlledDmlSignatureChangesWhenParametersChange() {
    String first = signature(controlledDml("UPDATE customer SET status = ? WHERE customer_id = ?", "ACTIVE",
        SqlDmlConfirmation.RISK_CONFIRMATION_CODE, "policy-v1", "confirmation-hash"));
    String second = signature(controlledDml("UPDATE customer SET status = ? WHERE customer_id = ?", "SUSPENDED",
        SqlDmlConfirmation.RISK_CONFIRMATION_CODE, "policy-v1", "confirmation-hash"));

    assertNotEquals(first, second);
  }

  @Test
  void controlledDmlSignatureChangesWhenConfirmationChanges() {
    String first = signature(controlledDml("UPDATE customer SET status = ? WHERE customer_id = ?", "ACTIVE",
        SqlDmlConfirmation.RISK_CONFIRMATION_CODE, "policy-v1", "confirmation-hash-a"));
    String second = signature(controlledDml("UPDATE customer SET status = ? WHERE customer_id = ?", "ACTIVE",
        "CONFIRM_DIFFERENT_RISK", "policy-v1", "confirmation-hash-b"));

    assertNotEquals(first, second);
  }

  @Test
  void controlledDmlSignatureChangesWhenPolicyChanges() {
    String first = signature(controlledDml("UPDATE customer SET status = ? WHERE customer_id = ?", "ACTIVE",
        SqlDmlConfirmation.RISK_CONFIRMATION_CODE, "policy-v1", "confirmation-hash"));
    String second = signature(controlledDml("UPDATE customer SET status = ? WHERE customer_id = ?", "ACTIVE",
        SqlDmlConfirmation.RISK_CONFIRMATION_CODE, "policy-v2", "confirmation-hash"));

    assertNotEquals(first, second);
  }

  @Test
  void controlledDmlSignatureChangesWhenBindingHashChanges() {
    String first = signature(controlledDml("UPDATE customer SET status = ? WHERE customer_id = ?", "ACTIVE",
        SqlDmlConfirmation.RISK_CONFIRMATION_CODE, "policy-v1", "confirmation-hash-a"));
    String second = signature(controlledDml("UPDATE customer SET status = ? WHERE customer_id = ?", "ACTIVE",
        SqlDmlConfirmation.RISK_CONFIRMATION_CODE, "policy-v1", "confirmation-hash-b"));

    assertNotEquals(first, second);
  }

  @Test
  void preflightSignatureChangesWhenMaskedColumnsChange() {
    String first = preflightSignature(new SqlDmlPreviewSelection(
        "1.0", List.of("customer_id", "email"), List.of("email")));
    String second = preflightSignature(new SqlDmlPreviewSelection(
        "1.0", List.of("customer_id", "email"), List.of()));

    assertNotEquals(first, second);
  }

  @Test
  void preflightSignatureDistinguishesSampleColumnBoundaries() {
    String first = preflightSignature(new SqlDmlPreviewSelection(
        "1.0", List.of("customer_id,email"), List.of()));
    String second = preflightSignature(new SqlDmlPreviewSelection(
        "1.0", List.of("customer_id", "email"), List.of()));

    assertNotEquals(first, second);
  }

  @Test
  void readOnlyCanonicalPayloadKeepsReadOnlyDiscriminator() {
    String payload = WorkerRequestSignature.canonicalSqlPayload(
        KEY_ID, TIMESTAMP, readOnlyRequest());

    assertTrue(payload.contains("\nsql-query-execution-v1\n"));
  }

  private String signature(SqlControlledDmlExecutionRequest request) {
    return WorkerRequestSignature.sign(
        SECRET,
        WorkerRequestSignature.canonicalControlledSqlDmlPayload(KEY_ID, TIMESTAMP, request));
  }

  private String preflightSignature(SqlDmlPreviewSelection selection) {
    SqlDmlPreflightExecutionRequest request = new SqlDmlPreflightExecutionRequest(
        "1.0",
        "preflight-execution-1",
        "workflow-1",
        query(SqlQueryAction.PREFLIGHT_DML, "ACTIVE"),
        "validation-hash",
        selection,
        operator(),
        policy("policy-v1"),
        trace(),
        expiresAt());
    return WorkerRequestSignature.sign(
        SECRET,
        WorkerRequestSignature.canonicalSqlDmlPreflightPayload(KEY_ID, TIMESTAMP, request));
  }

  private SqlControlledDmlExecutionRequest controlledDml(
      String sql,
      String status,
      String confirmationCode,
      String policyVersion,
      String confirmationHash) {
    SqlDmlCommitRequest commitRequest = new SqlDmlCommitRequest(
        "1.0",
        query(SqlQueryAction.COMMIT_DML, sql, status),
        new SqlDmlConfirmation(
            "1.0",
            "sql-hash",
            List.of("UPDATE_WITHOUT_WHERE"),
            confirmationCode));
    return new SqlControlledDmlExecutionRequest(
        "1.0",
        "controlled-execution-1",
        "workflow-1",
        commitRequest,
        new SqlDmlExecutionBinding(
            "binding-hash",
            "parameters-hash",
            "preflight-hash",
            confirmationHash),
        operator(),
        policy(policyVersion),
        trace(),
        expiresAt());
  }

  private SqlQueryExecutionRequest readOnlyRequest() {
    return new SqlQueryExecutionRequest(
        "1.0",
        "read-only-execution-1",
        "workflow-1",
        query(SqlQueryAction.RUN_READ_ONLY, "ACTIVE"),
        "validation-hash",
        operator(),
        policy("policy-v1"),
        trace(),
        expiresAt());
  }

  private SqlQueryRequest query(SqlQueryAction action, String status) {
    return query(action, "UPDATE customer SET status = ? WHERE customer_id = ?", status);
  }

  private SqlQueryRequest query(SqlQueryAction action, String sql, String status) {
    return new SqlQueryRequest(
        "1.0",
        "connection-1",
        "dev",
        "APP",
        action,
        sql,
        List.of(
            new SqlTypedParameter("status", "VARCHAR", JsonNodeFactory.instance.textNode(status)),
            new SqlTypedParameter("customer_id", "INTEGER", JsonNodeFactory.instance.numberNode(7))),
        new SqlQueryLimits(100, 10_000, 30),
        "idempotency-1");
  }

  private OperatorContext operator() {
    return new OperatorContext("operator-1", List.of("ROLE_SQL_DML"));
  }

  private PolicyDecisionReference policy(String version) {
    return new PolicyDecisionReference("decision-1", version, "ALLOW");
  }

  private TraceContext trace() {
    return new TraceContext("trace-1", "request-1");
  }

  private OffsetDateTime expiresAt() {
    return OffsetDateTime.parse("2026-07-16T12:30:00Z");
  }
}
