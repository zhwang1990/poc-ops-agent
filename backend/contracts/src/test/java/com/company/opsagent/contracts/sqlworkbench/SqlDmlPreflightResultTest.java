package com.company.opsagent.contracts.sqlworkbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.company.opsagent.contracts.workflow.OperatorContext;
import com.company.opsagent.contracts.workflow.PolicyDecisionReference;
import com.company.opsagent.contracts.workflow.TraceContext;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证受控 DML 预检、绑定与 Worker 信封的边界。
 */
class SqlDmlPreflightResultTest {

  @Test
  void updatePreflightRequiresImpactPreview() {
    assertThrows(IllegalArgumentException.class, () ->
        new SqlDmlPreflightResult("1.0", updateValidation(), null));
  }

  @Test
  void previewSelectionDefaultsToNoSampleColumns() {
    SqlDmlPreviewSelection selection = new SqlDmlPreviewSelection("1.0", null, null);

    assertEquals(List.of(), selection.sampleColumns());
    assertEquals(List.of(), selection.maskedSampleColumns());
  }

  @Test
  void previewSelectionRejectsMaskedColumnsOutsideSampleColumns() {
    assertThrows(IllegalArgumentException.class, () ->
        new SqlDmlPreviewSelection("1.0", List.of("customer_id"), List.of("email")));
  }

  @Test
  void preflightExecutionOnlyAcceptsPreflightDml() {
    assertThrows(IllegalArgumentException.class, () -> new SqlDmlPreflightExecutionRequest(
        "1.0",
        "execution-1",
        "workflow-1",
        query(SqlQueryAction.RUN_READ_ONLY),
        "validation-hash",
        selection(),
        operator(),
        policy(),
        trace(),
        expiresAt()));
  }

  @Test
  void controlledDmlExecutionRequiresBinding() {
    assertThrows(IllegalArgumentException.class, () -> new SqlControlledDmlExecutionRequest(
        "1.0", "execution-1", "workflow-1", commitRequest(), null,
        operator(), policy(), trace(), expiresAt()));
  }

  @Test
  void impactPreviewRequiresRowsToMatchSelectedColumns() {
    assertThrows(IllegalArgumentException.class, () -> new SqlDmlImpactPreview(
        "1.0",
        1L,
        List.of(new SqlResultColumn("customer_id", "INTEGER", false)),
        List.of(List.of(JsonNodeFactory.instance.numberNode(7), JsonNodeFactory.instance.textNode("extra"))),
        List.of()));
  }

  private SqlValidationReport updateValidation() {
    return new SqlValidationReport(
        "1.0",
        SqlStatementType.UPDATE,
        SqlValidationLevel.VALIDATED,
        "sql-hash",
        List.of("CUSTOMER"),
        List.of(),
        List.of(),
        List.of());
  }

  private SqlQueryRequest query(SqlQueryAction action) {
    return new SqlQueryRequest(
        "1.0",
        "connection-1",
        "dev",
        "APP",
        action,
        "UPDATE customer SET email = ? WHERE customer_id = ?",
        List.of(
            new SqlTypedParameter("email", "VARCHAR", JsonNodeFactory.instance.textNode("person@example.test")),
            new SqlTypedParameter("customer_id", "INTEGER", JsonNodeFactory.instance.numberNode(7))),
        new SqlQueryLimits(100, 10_000, 30),
        "idempotency-1");
  }

  private SqlDmlCommitRequest commitRequest() {
    return new SqlDmlCommitRequest("1.0", query(SqlQueryAction.COMMIT_DML), confirmation());
  }

  private SqlDmlConfirmation confirmation() {
    return new SqlDmlConfirmation(
        "1.0",
        "sql-hash",
        List.of("UPDATE_WITHOUT_WHERE"),
        SqlDmlConfirmation.RISK_CONFIRMATION_CODE);
  }

  private SqlDmlPreviewSelection selection() {
    return new SqlDmlPreviewSelection("1.0", List.of("customer_id", "email"), List.of("email"));
  }

  private SqlDmlExecutionBinding binding() {
    return new SqlDmlExecutionBinding(
        "binding-hash",
        "parameters-hash",
        "preflight-hash",
        "confirmation-hash");
  }

  private OperatorContext operator() {
    return new OperatorContext("operator-1", List.of("ROLE_SQL_DML"));
  }

  private PolicyDecisionReference policy() {
    return new PolicyDecisionReference("decision-1", "policy-v1", "ALLOW");
  }

  private TraceContext trace() {
    return new TraceContext("trace-1", "request-1");
  }

  private OffsetDateTime expiresAt() {
    return OffsetDateTime.parse("2026-07-16T12:30:00Z");
  }
}
