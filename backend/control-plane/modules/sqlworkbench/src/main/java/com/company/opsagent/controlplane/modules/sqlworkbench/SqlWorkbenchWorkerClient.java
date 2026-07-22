package com.company.opsagent.controlplane.modules.sqlworkbench;

import com.company.opsagent.contracts.sqlworkbench.SqlQueryExecutionRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryExecutionResult;
import com.company.opsagent.contracts.sqlworkbench.SqlResultPage;
import com.company.opsagent.contracts.sqlworkbench.SqlDatabaseMetadata;
import com.company.opsagent.contracts.sqlworkbench.SqlConnectionProbeResult;
import com.company.opsagent.contracts.sqlworkbench.SqlConnectionSummary;
import com.company.opsagent.contracts.sqlworkbench.SqlControlledDmlExecutionRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlDmlImpactPreview;
import com.company.opsagent.contracts.sqlworkbench.SqlDmlPreflightExecutionRequest;

/**
 * 控制面提交 SQL 工作台专用执行信封到受限 Worker 的端口。
 */
public interface SqlWorkbenchWorkerClient {

  SqlConnectionProbeResult probe(SqlConnectionSummary connection);

  SqlQueryExecutionResult execute(SqlQueryExecutionRequest request);

  default SqlDmlImpactPreview preflightDml(SqlDmlPreflightExecutionRequest request) {
    throw new SqlWorkbenchException(
        "SQL_DML_WORKER_NOT_CONFIGURED",
        "Controlled DML preflight worker is not configured");
  }

  default SqlQueryExecutionResult executeControlledDml(SqlControlledDmlExecutionRequest request) {
    return new SqlQueryExecutionResult(
        "1.0",
        request.executionRequestId(),
        request.workflowId(),
        "FAILED",
        null,
        "SQL_DML_WORKER_NOT_CONFIGURED",
        "Controlled DML worker is not configured",
        null);
  }

  default boolean supportsControlledDml(String targetEnvironment) {
    return false;
  }

  SqlResultPage readResultPage(String resultId);

  SqlDatabaseMetadata readMetadata(SqlConnectionSummary connection, String schema);
}
