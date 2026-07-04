package com.company.opsagent.controlplane.modules.sqlworkbench;

import com.company.opsagent.contracts.sqlworkbench.SqlQueryExecutionRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryExecutionResult;
import com.company.opsagent.contracts.sqlworkbench.SqlResultPage;
import com.company.opsagent.contracts.sqlworkbench.SqlDatabaseMetadata;
import com.company.opsagent.contracts.sqlworkbench.SqlConnectionProbeResult;
import com.company.opsagent.contracts.sqlworkbench.SqlConnectionSummary;

/**
 * 控制面提交 SQL 工作台专用执行信封到受限 Worker 的端口。
 */
public interface SqlWorkbenchWorkerClient {

  SqlConnectionProbeResult probe(SqlConnectionSummary connection);

  SqlQueryExecutionResult execute(SqlQueryExecutionRequest request);

  SqlResultPage readResultPage(String resultId);

  SqlDatabaseMetadata readMetadata(SqlConnectionSummary connection, String schema);
}
