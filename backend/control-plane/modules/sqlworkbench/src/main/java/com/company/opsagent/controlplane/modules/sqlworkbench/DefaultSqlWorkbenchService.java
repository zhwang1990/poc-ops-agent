package com.company.opsagent.controlplane.modules.sqlworkbench;

import com.company.opsagent.contracts.sqlworkbench.SqlConnectionCreateRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlConnectionProbeResult;
import com.company.opsagent.contracts.sqlworkbench.SqlConnectionSummary;
import com.company.opsagent.contracts.sqlworkbench.SqlConnectionUpdateRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlDatabaseMetadata;
import com.company.opsagent.contracts.sqlworkbench.SqlDmlCommitRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlDmlConfirmation;
import com.company.opsagent.contracts.sqlworkbench.SqlAssistantAction;
import com.company.opsagent.contracts.sqlworkbench.SqlAssistantRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlAssistantResponse;
import com.company.opsagent.contracts.sqlworkbench.SqlAssistantStatus;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryAction;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryExecutionRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryExecutionResult;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlResultPage;
import com.company.opsagent.contracts.sqlworkbench.SqlStatementType;
import com.company.opsagent.contracts.sqlworkbench.SqlTargetEnvironments;
import com.company.opsagent.contracts.sqlworkbench.SqlValidationLevel;
import com.company.opsagent.contracts.sqlworkbench.SqlValidationReport;
import com.company.opsagent.contracts.workflow.OperatorContext;
import com.company.opsagent.contracts.workflow.PolicyDecisionReference;
import com.company.opsagent.contracts.workflow.TraceContext;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * SQL 工作台应用服务，先校验连接目录边界，再委托 AST 校验。
 */
public class DefaultSqlWorkbenchService implements SqlWorkbenchService {

  private final SqlConnectionCatalog connectionCatalog;
  private final SqlValidationService validationService;
  private final SqlWorkbenchWorkerClient workerClient;
  private final SqlAssistantClient assistantClient;
  private final Clock clock;

  public DefaultSqlWorkbenchService(
      SqlConnectionCatalog connectionCatalog,
      SqlValidationService validationService) {
    this(
        connectionCatalog,
        validationService,
        new FailClosedSqlWorkbenchWorkerClient(),
        new FailClosedSqlAssistantClient(),
        Clock.systemUTC());
  }

  public DefaultSqlWorkbenchService(
      SqlConnectionCatalog connectionCatalog,
      SqlValidationService validationService,
      SqlWorkbenchWorkerClient workerClient,
      Clock clock) {
    this(
        connectionCatalog,
        validationService,
        workerClient,
        new FailClosedSqlAssistantClient(),
        clock);
  }

  public DefaultSqlWorkbenchService(
      SqlConnectionCatalog connectionCatalog,
      SqlValidationService validationService,
      SqlWorkbenchWorkerClient workerClient,
      SqlAssistantClient assistantClient,
      Clock clock) {
    this.connectionCatalog = connectionCatalog;
    this.validationService = validationService;
    this.workerClient = workerClient;
    this.assistantClient = assistantClient;
    this.clock = clock;
  }

  @Override
  public List<SqlConnectionSummary> listConnections() {
    return connectionCatalog.list();
  }

  @Override
  public SqlConnectionSummary createConnection(SqlConnectionCreateRequest request) {
    SqlConnectionSummary created = connectionCatalog.create(request);
    try {
      SqlConnectionProbeResult probe = workerClient.probe(created);
      if ("READY".equalsIgnoreCase(probe.status())) {
        return connectionCatalog.updateStatus(created.connectionId(), "READY");
      }
    } catch (RuntimeException ignored) {
      // New connections remain pending until the worker binding can be verified.
    }
    return created;
  }

  @Override
  public SqlConnectionSummary updateConnection(String connectionId, SqlConnectionUpdateRequest request) {
    return connectionCatalog.update(connectionId, request);
  }

  @Override
  public void deleteConnection(String connectionId) {
    connectionCatalog.delete(connectionId);
  }

  @Override
  public SqlConnectionProbeResult probeConnection(String connectionId) {
    SqlConnectionSummary connection = connectionCatalog.find(connectionId)
        .orElseThrow(() -> new IllegalArgumentException("SQL connection is not available"));
    return workerClient.probe(connection);
  }

  @Override
  public SqlValidationReport validate(SqlQueryRequest request) {
    SqlConnectionSummary connection = connectionCatalog.find(request.connectionId())
        .orElseThrow(() -> new IllegalArgumentException("SQL connection is not available"));
    if (!SqlTargetEnvironments.same(connection.targetEnvironment(), request.targetEnvironment())) {
      throw new IllegalArgumentException("target environment does not match connection");
    }
    boolean schemaAllowed = connection.allowedSchemas().stream()
        .anyMatch(schema -> schema.equalsIgnoreCase(request.schema()));
    if (!schemaAllowed) {
      throw new IllegalArgumentException("schema is not allowed for connection");
    }
    if (!connection.capabilities().contains(request.action())) {
      throw new IllegalArgumentException("action is not allowed for connection");
    }
    SqlValidationReport report = validationService.validate(request);
    boolean crossSchemaReference = report.referencedObjects().stream()
        .filter(object -> object.contains("."))
        .map(object -> object.substring(0, object.indexOf('.')))
        .anyMatch(referencedSchema -> connection.allowedSchemas().stream()
            .noneMatch(allowed -> allowed.equalsIgnoreCase(referencedSchema)));
    if (crossSchemaReference) {
      throw new IllegalArgumentException("SQL references a schema outside the connection allow list");
    }
    return report;
  }

  @Override
  public SqlAssistantResponse assist(SqlAssistantRequest request) {
    SqlConnectionSummary connection = requireAssistantConnection(request);
    SqlValidationReport report = requiresSqlValidation(request.assistantAction())
        ? validate(new SqlQueryRequest(
            "1.0",
            request.connectionId(),
            request.targetEnvironment(),
            request.schema(),
            SqlQueryAction.VALIDATE,
            request.sql(),
            List.of(),
            request.limits(),
            request.idempotencyKey()))
        : advisoryOnlyReport(request);
    return assistantClient.ask(new SqlAssistantPrompt(
        request.assistantAction(),
        connection.connectionId(),
        connection.targetEnvironment(),
        request.schema(),
        connection.platformType(),
        request.sql(),
        report,
        request.diagnosticContext()));
  }

  private SqlConnectionSummary requireAssistantConnection(SqlAssistantRequest request) {
    SqlConnectionSummary connection = connectionCatalog.find(request.connectionId())
        .orElseThrow(() -> new IllegalArgumentException("SQL connection is not available"));
    if (!SqlTargetEnvironments.same(connection.targetEnvironment(), request.targetEnvironment())) {
      throw new IllegalArgumentException("target environment does not match connection");
    }
    boolean schemaAllowed = connection.allowedSchemas().stream()
        .anyMatch(schema -> schema.equalsIgnoreCase(request.schema()));
    if (!schemaAllowed) {
      throw new IllegalArgumentException("schema is not allowed for connection");
    }
    return connection;
  }

  private boolean requiresSqlValidation(SqlAssistantAction action) {
    return action != SqlAssistantAction.GENERATE_SELECT
        && action != SqlAssistantAction.COMPARE_SUMMARY;
  }

  private SqlValidationReport advisoryOnlyReport(SqlAssistantRequest request) {
    return new SqlValidationReport(
        "1.0",
        SqlStatementType.UNSUPPORTED,
        SqlValidationLevel.PARTIAL,
        "sha256:advisory-context",
        List.of(),
        List.of("ADVISORY_ONLY"),
        List.of(),
        List.of("sqlValidationSkippedForAssistantAction=" + request.assistantAction()));
  }

  @Override
  public SqlQueryExecutionResult runReadOnlyQuery(
      SqlQueryRequest request,
      OperatorContext operator,
      PolicyDecisionReference policyDecision,
      TraceContext trace) {
    if (request.action() != SqlQueryAction.RUN_READ_ONLY) {
      throw new IllegalArgumentException("queries/run only accepts RUN_READ_ONLY");
    }
    SqlValidationReport report = validate(request);
    if (report.validationLevel() != SqlValidationLevel.VALIDATED) {
      throw new IllegalArgumentException(readOnlyValidationFailureMessage(report));
    }
    SqlQueryExecutionRequest executionRequest = new SqlQueryExecutionRequest(
        "1.0",
        UUID.randomUUID().toString(),
        UUID.randomUUID().toString(),
        request,
        report.sqlHash(),
        operator,
        policyDecision,
        trace,
        OffsetDateTime.now(clock).plusSeconds(request.limits().timeoutSeconds()));
    return workerClient.execute(executionRequest);
  }

  @Override
  public SqlQueryExecutionResult commitControlledDml(
      SqlDmlCommitRequest commitRequest,
      OperatorContext operator,
      PolicyDecisionReference policyDecision,
      TraceContext trace) {
    SqlQueryRequest request = commitRequest.query();
    if (request.action() != SqlQueryAction.COMMIT_DML) {
      throw new IllegalArgumentException("queries/commit only accepts COMMIT_DML");
    }
    if (!SqlTargetEnvironments.allowsCrud(request.targetEnvironment())) {
      throw new IllegalArgumentException("SQL DML is allowed only in dev, sit, or uat");
    }
    SqlValidationReport report = validate(request);
    if (report.validationLevel() == SqlValidationLevel.REJECTED) {
      throw new IllegalArgumentException(dmlValidationFailureMessage(report));
    }
    if (report.statementType() != SqlStatementType.INSERT
        && report.statementType() != SqlStatementType.UPDATE
        && report.statementType() != SqlStatementType.DELETE) {
      throw new IllegalArgumentException(dmlValidationFailureMessage(report));
    }
    requireRiskConfirmation(report, commitRequest.confirmation());
    SqlQueryExecutionRequest executionRequest = new SqlQueryExecutionRequest(
        "1.0",
        UUID.randomUUID().toString(),
        UUID.randomUUID().toString(),
        request,
        report.sqlHash(),
        operator,
        policyDecision,
        trace,
        OffsetDateTime.now(clock).plusSeconds(request.limits().timeoutSeconds()));
    return workerClient.execute(executionRequest);
  }

  private void requireRiskConfirmation(SqlValidationReport report, SqlDmlConfirmation confirmation) {
    if (report.risks().isEmpty()) {
      return;
    }
    if (confirmation == null) {
      throw new IllegalArgumentException(dmlConfirmationFailureMessage(report, "DML risk confirmation is required"));
    }
    if (!report.sqlHash().equals(confirmation.sqlHash())) {
      throw new IllegalArgumentException(dmlConfirmationFailureMessage(report, "DML confirmation SQL hash does not match"));
    }
    if (!SqlDmlConfirmation.RISK_CONFIRMATION_CODE.equals(confirmation.confirmationCode())) {
      throw new IllegalArgumentException(dmlConfirmationFailureMessage(report, "DML confirmation code is invalid"));
    }
    if (!confirmation.confirmedRisks().containsAll(report.risks())) {
      throw new IllegalArgumentException(dmlConfirmationFailureMessage(report, "DML confirmation risks do not match"));
    }
  }

  @Override
  public SqlResultPage readResultPage(String resultId) {
    return workerClient.readResultPage(resultId);
  }

  @Override
  public SqlDatabaseMetadata readMetadata(String connectionId, String schema) {
    SqlConnectionSummary connection = connectionCatalog.find(connectionId)
        .orElseThrow(() -> new IllegalArgumentException("SQL connection is not available"));
    if (schema == null || schema.isBlank()) {
      throw new IllegalArgumentException("schema must not be blank");
    }
    String requestedSchema = schema.trim();
    boolean schemaAllowed = connection.allowedSchemas().stream()
        .anyMatch(allowed -> allowed.equalsIgnoreCase(requestedSchema));
    if (!schemaAllowed) {
      throw new IllegalArgumentException("schema is not allowed for connection");
    }
    return workerClient.readMetadata(connection, requestedSchema);
  }

  private String readOnlyValidationFailureMessage(SqlValidationReport report) {
    return String.join(
        System.lineSeparator(),
        "SELECT 执行未通过服务端只读校验。控制面没有向 Worker 提交执行请求。",
        "statementType=" + report.statementType(),
        "validationLevel=" + report.validationLevel(),
        "rejectionReasons=" + formatReportValues(report.rejectionReasons()),
        "risks=" + formatReportValues(report.risks()),
        "referencedObjects=" + formatReportValues(report.referencedObjects()),
        "unverifiedItems=" + formatReportValues(report.unverifiedItems()),
        "sqlHash=" + report.sqlHash(),
        "nextStep=先执行校验查看完整报告；如果是 INSERT、UPDATE 或 DELETE，请改用 DML 预检。");
  }

  private String dmlValidationFailureMessage(SqlValidationReport report) {
    return String.join(
        System.lineSeparator(),
        "DML 提交未通过服务端受控校验，控制面没有向 Worker 提交执行请求。",
        "statementType=" + report.statementType(),
        "validationLevel=" + report.validationLevel(),
        "rejectionReasons=" + formatReportValues(report.rejectionReasons()),
        "risks=" + formatReportValues(report.risks()),
        "referencedObjects=" + formatReportValues(report.referencedObjects()),
        "unverifiedItems=" + formatReportValues(report.unverifiedItems()),
        "sqlHash=" + report.sqlHash(),
        "nextStep=仅 dev、sit、uat 环境可提交单条 INSERT、UPDATE 或 DELETE；无 WHERE 的 UPDATE/DELETE 必须二次确认。");
  }

  private String dmlConfirmationFailureMessage(SqlValidationReport report, String reason) {
    return String.join(
        System.lineSeparator(),
        "DML 提交需要操作人二次确认，控制面没有向 Worker 提交执行请求。",
        "reason=" + reason,
        "statementType=" + report.statementType(),
        "risks=" + formatReportValues(report.risks()),
        "sqlHash=" + report.sqlHash(),
        "confirmationCode=" + SqlDmlConfirmation.RISK_CONFIRMATION_CODE);
  }

  private String formatReportValues(List<String> values) {
    return values.isEmpty() ? "none" : String.join(" / ", values);
  }

  private static final class FailClosedSqlWorkbenchWorkerClient implements SqlWorkbenchWorkerClient {

    @Override
    public SqlConnectionProbeResult probe(SqlConnectionSummary connection) {
      return new SqlConnectionProbeResult(
          "1.0",
          connection.connectionId(),
          "PROBE_FAILED",
          "SQL workbench worker client is not configured",
          OffsetDateTime.now());
    }

    @Override
    public SqlQueryExecutionResult execute(SqlQueryExecutionRequest request) {
      return new SqlQueryExecutionResult(
          "1.0",
          request.executionRequestId(),
          request.workflowId(),
          "FAILED",
          null,
          "SQL_WORKER_NOT_CONFIGURED",
          "SQL workbench worker client is not configured");
    }

    @Override
    public SqlResultPage readResultPage(String resultId) {
      throw new IllegalStateException("SQL workbench worker client is not configured");
    }

    @Override
    public SqlDatabaseMetadata readMetadata(SqlConnectionSummary connection, String schema) {
      throw new IllegalStateException("SQL workbench worker client is not configured");
    }
  }

  private static final class FailClosedSqlAssistantClient implements SqlAssistantClient {

    @Override
    public SqlAssistantResponse ask(SqlAssistantPrompt prompt) {
      return new SqlAssistantResponse(
          "1.0",
          SqlAssistantStatus.MODEL_NOT_CONFIGURED,
          prompt.assistantAction(),
          "SQL assistant model provider is not configured.",
          List.of(),
          List.of("AI SQL assistant is advisory only and cannot execute SQL."),
          true,
          null);
    }
  }
}
