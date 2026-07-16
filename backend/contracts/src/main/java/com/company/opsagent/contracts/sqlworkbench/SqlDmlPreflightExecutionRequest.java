package com.company.opsagent.contracts.sqlworkbench;

import static com.company.opsagent.contracts.ContractValues.required;
import static com.company.opsagent.contracts.ContractValues.requiredText;

import com.company.opsagent.contracts.workflow.OperatorContext;
import com.company.opsagent.contracts.workflow.PolicyDecisionReference;
import com.company.opsagent.contracts.workflow.TraceContext;
import java.time.OffsetDateTime;

/**
 * 控制面向 Worker 提交的已授权 DML 只读预检信封。
 */
public record SqlDmlPreflightExecutionRequest(
    String contractVersion,
    String executionRequestId,
    String workflowId,
    SqlQueryRequest query,
    String validationHash,
    SqlDmlPreviewSelection previewSelection,
    OperatorContext operator,
    PolicyDecisionReference policyDecision,
    TraceContext trace,
    OffsetDateTime expiresAt) {

  public SqlDmlPreflightExecutionRequest {
    if (!"1.0".equals(contractVersion)) {
      throw new IllegalArgumentException("contractVersion must be 1.0");
    }
    executionRequestId = requiredText(executionRequestId, "executionRequestId");
    workflowId = requiredText(workflowId, "workflowId");
    query = required(query, "query");
    if (query.action() != SqlQueryAction.PREFLIGHT_DML) {
      throw new IllegalArgumentException("DML preflight only accepts PREFLIGHT_DML");
    }
    validationHash = requiredText(validationHash, "validationHash");
    previewSelection = required(previewSelection, "previewSelection");
    operator = required(operator, "operator");
    policyDecision = required(policyDecision, "policyDecision");
    trace = required(trace, "trace");
    expiresAt = required(expiresAt, "expiresAt");
  }
}
