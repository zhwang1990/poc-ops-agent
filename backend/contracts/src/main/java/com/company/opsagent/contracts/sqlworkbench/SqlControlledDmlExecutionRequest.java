package com.company.opsagent.contracts.sqlworkbench;

import static com.company.opsagent.contracts.ContractValues.required;
import static com.company.opsagent.contracts.ContractValues.requiredText;

import com.company.opsagent.contracts.workflow.OperatorContext;
import com.company.opsagent.contracts.workflow.PolicyDecisionReference;
import com.company.opsagent.contracts.workflow.TraceContext;
import java.time.OffsetDateTime;

/**
 * 控制面向 Worker 提交的已授权受控 DML 信封。
 */
public record SqlControlledDmlExecutionRequest(
    String contractVersion,
    String executionRequestId,
    String workflowId,
    SqlDmlCommitRequest commitRequest,
    SqlDmlExecutionBinding binding,
    OperatorContext operator,
    PolicyDecisionReference policyDecision,
    TraceContext trace,
    OffsetDateTime expiresAt) {

  public SqlControlledDmlExecutionRequest {
    if (!"1.0".equals(contractVersion)) {
      throw new IllegalArgumentException("contractVersion must be 1.0");
    }
    executionRequestId = requiredText(executionRequestId, "executionRequestId");
    workflowId = requiredText(workflowId, "workflowId");
    commitRequest = required(commitRequest, "commitRequest");
    if (commitRequest.query().action() != SqlQueryAction.COMMIT_DML) {
      throw new IllegalArgumentException("controlled DML only accepts COMMIT_DML");
    }
    if (binding == null) {
      throw new IllegalArgumentException("binding is required");
    }
    operator = required(operator, "operator");
    policyDecision = required(policyDecision, "policyDecision");
    trace = required(trace, "trace");
    expiresAt = required(expiresAt, "expiresAt");
  }
}
