package com.company.opsagent.contracts.sqlworkbench;

import static com.company.opsagent.contracts.ContractValues.requiredText;

/**
 * Worker 返回的 SQL 查询执行结果引用。
 */
public record SqlQueryExecutionResult(
    String contractVersion,
    String executionRequestId,
    String workflowId,
    String status,
    String resultId,
    String errorCode,
    String errorMessage,
    Integer affectedRows) {

  public SqlQueryExecutionResult(
      String contractVersion,
      String executionRequestId,
      String workflowId,
      String status,
      String resultId,
      String errorCode,
      String errorMessage) {
    this(contractVersion, executionRequestId, workflowId, status, resultId, errorCode, errorMessage, null);
  }

  public SqlQueryExecutionResult {
    if (!"1.0".equals(contractVersion)) {
      throw new IllegalArgumentException("contractVersion must be 1.0");
    }
    executionRequestId = requiredText(executionRequestId, "executionRequestId");
    workflowId = requiredText(workflowId, "workflowId");
    status = requiredText(status, "status");
  }
}
