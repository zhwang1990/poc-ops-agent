package com.company.opsagent.controlplane.modules.sqlworkbench;

import com.company.opsagent.contracts.sqlworkbench.SqlConnectionSummary;
import com.company.opsagent.contracts.sqlworkbench.SqlConnectionCreateRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlConnectionProbeResult;
import com.company.opsagent.contracts.sqlworkbench.SqlConnectionUpdateRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlAssistantRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlAssistantResponse;
import com.company.opsagent.contracts.sqlworkbench.SqlDatabaseMetadata;
import com.company.opsagent.contracts.sqlworkbench.SqlDmlCommitRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlDmlPreflightResult;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryExecutionResult;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlResultPage;
import com.company.opsagent.contracts.sqlworkbench.SqlValidationReport;
import com.company.opsagent.contracts.workflow.OperatorContext;
import com.company.opsagent.contracts.workflow.PolicyDecisionReference;
import com.company.opsagent.contracts.workflow.TraceContext;
import java.util.List;

/**
 * SQL 工作台公开给控制面 API 的应用边界。
 */
public interface SqlWorkbenchService {

  List<SqlConnectionSummary> listConnections();

  SqlConnectionSummary createConnection(SqlConnectionCreateRequest request);

  SqlConnectionSummary updateConnection(String connectionId, SqlConnectionUpdateRequest request);

  void deleteConnection(String connectionId);

  SqlConnectionProbeResult probeConnection(String connectionId);

  SqlValidationReport validate(SqlQueryRequest request);

  SqlAssistantResponse assist(SqlAssistantRequest request);

  SqlQueryExecutionResult runReadOnlyQuery(
      SqlQueryRequest request,
      OperatorContext operator,
      PolicyDecisionReference policyDecision,
      TraceContext trace);

  SqlDmlPreflightResult preflightControlledDml(
      SqlQueryRequest request,
      OperatorContext operator,
      PolicyDecisionReference policyDecision,
      TraceContext trace);

  SqlQueryExecutionResult commitControlledDml(
      SqlDmlCommitRequest request,
      OperatorContext operator,
      PolicyDecisionReference policyDecision,
      TraceContext trace);

  SqlResultPage readResultPage(String resultId);

  SqlDatabaseMetadata readMetadata(String connectionId, String schema);
}
