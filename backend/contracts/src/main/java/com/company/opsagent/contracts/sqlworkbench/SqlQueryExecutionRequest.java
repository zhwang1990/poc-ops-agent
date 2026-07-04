package com.company.opsagent.contracts.sqlworkbench;

import static com.company.opsagent.contracts.ContractValues.required;
import static com.company.opsagent.contracts.ContractValues.requiredText;

import com.company.opsagent.contracts.workflow.OperatorContext;
import com.company.opsagent.contracts.workflow.PolicyDecisionReference;
import com.company.opsagent.contracts.workflow.TraceContext;
import java.time.OffsetDateTime;

/**
 * 控制面向 Worker 提交的已授权 SQL 查询执行信封。
 */
public record SqlQueryExecutionRequest(
    String contractVersion,
    String executionRequestId,
    String workflowId,
    SqlQueryRequest query,
    String validationHash,
    OperatorContext operator,
    PolicyDecisionReference policyDecision,
    TraceContext trace,
    OffsetDateTime expiresAt) {

  public SqlQueryExecutionRequest {
    if (!"1.0".equals(contractVersion)) {
      throw new IllegalArgumentException("contractVersion must be 1.0");
    }
    executionRequestId = requiredText(executionRequestId, "executionRequestId");
    workflowId = requiredText(workflowId, "workflowId");
    query = required(query, "query");
    if (query.action() != SqlQueryAction.RUN_READ_ONLY && query.action() != SqlQueryAction.COMMIT_DML) {
      throw new IllegalArgumentException("Worker execution only accepts RUN_READ_ONLY or COMMIT_DML");
    }
    validationHash = requiredText(validationHash, "validationHash");
    operator = required(operator, "operator");
    policyDecision = required(policyDecision, "policyDecision");
    trace = required(trace, "trace");
    expiresAt = required(expiresAt, "expiresAt");
  }
}
