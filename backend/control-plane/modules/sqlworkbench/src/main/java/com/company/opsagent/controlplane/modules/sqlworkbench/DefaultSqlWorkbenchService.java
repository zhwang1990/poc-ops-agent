package com.company.opsagent.controlplane.modules.sqlworkbench;

import com.company.opsagent.contracts.sqlworkbench.SqlConnectionCreateRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlConnectionProbeResult;
import com.company.opsagent.contracts.sqlworkbench.SqlConnectionSummary;
import com.company.opsagent.contracts.sqlworkbench.SqlConnectionUpdateRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlControlledDmlExecutionRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlDatabaseMetadata;
import com.company.opsagent.contracts.sqlworkbench.SqlDmlCommitRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlDmlConfirmation;
import com.company.opsagent.contracts.sqlworkbench.SqlDmlExecutionBinding;
import com.company.opsagent.contracts.sqlworkbench.SqlDmlImpactPreview;
import com.company.opsagent.contracts.sqlworkbench.SqlDmlPreflightExecutionRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlDmlPreflightResult;
import com.company.opsagent.contracts.sqlworkbench.SqlDmlPreviewSelection;
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
import com.company.opsagent.controlplane.modules.workflow.ControlledSqlDmlWorkflowRequest;
import com.company.opsagent.controlplane.modules.workflow.ControlledSqlDmlWorkflowService;
import com.company.opsagent.controlplane.modules.workflow.ControlledSqlDmlWorkflowStore;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * SQL 工作台应用服务，先校验连接目录边界，再委托 AST 校验。
 */
public class DefaultSqlWorkbenchService implements SqlWorkbenchService {

  private final SqlConnectionCatalog connectionCatalog;
  private final SqlValidationService validationService;
  private final SqlWorkbenchWorkerClient workerClient;
  private final SqlAssistantClient assistantClient;
  private final ControlledSqlDmlPolicy controlledDmlPolicy;
  private final Function<ControlledSqlDmlWorkflowRequest, SqlQueryExecutionResult> controlledDmlExecutor;
  private final Predicate<String> controlledDmlEnvironmentEnabled;
  private final boolean transactionalAuditAvailable;
  private final Clock clock;

  public DefaultSqlWorkbenchService(
      SqlConnectionCatalog connectionCatalog,
      SqlValidationService validationService) {
    this(
        connectionCatalog,
        validationService,
        new FailClosedSqlWorkbenchWorkerClient(),
        new FailClosedSqlAssistantClient(),
        failClosedDmlPolicy(),
        request -> {
          throw new ControlledSqlDmlWorkflowService.WorkflowException(
              "SQL_DML_WORKFLOW_REQUIRED", "Controlled DML workflow is not configured");
        },
        environment -> false,
        false,
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
        failClosedDmlPolicy(),
        request -> {
          throw new ControlledSqlDmlWorkflowService.WorkflowException(
              "SQL_DML_WORKFLOW_REQUIRED", "Controlled DML workflow is not configured");
        },
        environment -> false,
        false,
        clock);
  }

  public DefaultSqlWorkbenchService(
      SqlConnectionCatalog connectionCatalog,
      SqlValidationService validationService,
      SqlWorkbenchWorkerClient workerClient,
      SqlAssistantClient assistantClient,
      Clock clock) {
    this(
        connectionCatalog,
        validationService,
        workerClient,
        assistantClient,
        failClosedDmlPolicy(),
        request -> {
          throw new ControlledSqlDmlWorkflowService.WorkflowException(
              "SQL_DML_WORKFLOW_REQUIRED", "Controlled DML workflow is not configured");
        },
        environment -> false,
        false,
        clock);
  }

  public DefaultSqlWorkbenchService(
      SqlConnectionCatalog connectionCatalog,
      SqlValidationService validationService,
      SqlWorkbenchWorkerClient workerClient,
      SqlAssistantClient assistantClient,
      ControlledSqlDmlPolicy controlledDmlPolicy,
      Function<ControlledSqlDmlWorkflowRequest, SqlQueryExecutionResult> controlledDmlExecutor,
      Predicate<String> controlledDmlEnvironmentEnabled,
      boolean transactionalAuditAvailable,
      Clock clock) {
    this.connectionCatalog = connectionCatalog;
    this.validationService = validationService;
    this.workerClient = workerClient;
    this.assistantClient = assistantClient;
    this.controlledDmlPolicy = controlledDmlPolicy;
    this.controlledDmlExecutor = controlledDmlExecutor;
    this.controlledDmlEnvironmentEnabled = controlledDmlEnvironmentEnabled;
    this.transactionalAuditAvailable = transactionalAuditAvailable;
    this.clock = clock;
  }

  @Override
  public List<SqlConnectionSummary> listConnections() {
    return connectionCatalog.list().stream().map(this::serverVisibleConnection).toList();
  }

  @Override
  public SqlConnectionSummary createConnection(SqlConnectionCreateRequest request) {
    SqlConnectionSummary created = connectionCatalog.create(request);
    try {
      SqlConnectionProbeResult probe = workerClient.probe(created);
      if ("READY".equalsIgnoreCase(probe.status())) {
        return serverVisibleConnection(connectionCatalog.updateStatus(created.connectionId(), "READY"));
      }
    } catch (RuntimeException ignored) {
      // New connections remain pending until the worker binding can be verified.
    }
    return serverVisibleConnection(created);
  }

  @Override
  public SqlConnectionSummary updateConnection(String connectionId, SqlConnectionUpdateRequest request) {
    return serverVisibleConnection(connectionCatalog.update(connectionId, request));
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
  public SqlDmlPreflightResult preflightControlledDml(
      SqlQueryRequest request,
      OperatorContext operator,
      PolicyDecisionReference policyDecision,
      TraceContext trace) {
    if (request.action() != SqlQueryAction.PREFLIGHT_DML) {
      throw new IllegalArgumentException("queries/preflight only accepts PREFLIGHT_DML");
    }
    requireAllowedPolicyDecision(policyDecision);
    requireControlledDmlAvailable(request);
    SqlValidationReport report = validate(request);
    requireValidatedDml(report);
    SqlDmlPreviewSelection previewSelection = controlledDmlPolicy.authorize(request, report);
    String workflowId = UUID.randomUUID().toString();
    SqlDmlPreflightExecutionRequest workerRequest = new SqlDmlPreflightExecutionRequest(
        "1.0",
        UUID.randomUUID().toString(),
        workflowId,
        request,
        validationHash(report, previewSelection),
        previewSelection,
        operator,
        policyDecision,
        trace,
        OffsetDateTime.now(clock).plusSeconds(request.limits().timeoutSeconds()));
    SqlDmlImpactPreview impactPreview = workerClient.preflightDml(workerRequest);
    return new SqlDmlPreflightResult("1.0", report, impactPreview);
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
    requireAllowedPolicyDecision(policyDecision);
    requireControlledDmlAvailable(request);
    SqlValidationReport report = validate(request);
    requireValidatedDml(report);
    SqlDmlPreviewSelection previewSelection = controlledDmlPolicy.authorize(request, report);
    requireRiskConfirmation(report, commitRequest.confirmation());
    SqlDmlExecutionBinding binding = executionBinding(
        commitRequest, report, previewSelection, operator, policyDecision);
    try {
      return controlledDmlExecutor.apply(new ControlledSqlDmlWorkflowRequest(
          commitRequest,
          binding,
          report,
          operator,
          policyDecision,
          trace));
    } catch (ControlledSqlDmlWorkflowService.WorkflowException exception) {
      throw new SqlWorkbenchException(exception.code(), exception.getMessage());
    } catch (ControlledSqlDmlWorkflowStore.IdempotencyConflictException exception) {
      throw new SqlWorkbenchException(exception.code(), exception.getMessage());
    } catch (ControlledSqlDmlWorkflowStore.TransactionalAuditRequiredException exception) {
      throw new SqlWorkbenchException(exception.code(), exception.getMessage());
    }
  }

  private void requireRiskConfirmation(SqlValidationReport report, SqlDmlConfirmation confirmation) {
    if (confirmation == null) {
      throw new IllegalArgumentException(dmlConfirmationFailureMessage(report, "DML confirmation is required"));
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

  private void requireAllowedPolicyDecision(PolicyDecisionReference policyDecision) {
    if (!"ALLOW".equals(policyDecision.decision())) {
      throw new SqlWorkbenchException(
          "SQL_DML_POLICY_DENIED", "Controlled DML policy did not allow execution");
    }
  }

  private void requireControlledDmlAvailable(SqlQueryRequest request) {
    String targetEnvironment = request.targetEnvironment();
    if (!SqlTargetEnvironments.allowsCrud(targetEnvironment)) {
      throw new SqlWorkbenchException(
          "SQL_DML_DISABLED", "SQL DML is allowed only in dev, sit, or uat");
    }
    if (!transactionalAuditAvailable) {
      throw new SqlWorkbenchException(
          ControlledSqlDmlWorkflowStore.TransactionalAuditRequiredException.CODE,
          "Transactional audit storage is required for controlled SQL DML");
    }
    if (!controlledDmlEnvironmentEnabled.test(targetEnvironment)) {
      throw new SqlWorkbenchException(
          "SQL_DML_DISABLED", "DML execution is disabled for the target environment");
    }
    SqlConnectionSummary connection = connectionCatalog.find(request.connectionId())
        .orElseThrow(() -> new IllegalArgumentException("SQL connection is not available"));
    if (!"READY".equalsIgnoreCase(connection.status())
        || !controlledDmlPolicy.supports(connection)) {
      throw new SqlWorkbenchException(
          "SQL_DML_DISABLED", "DML execution is disabled for the SQL connection");
    }
  }

  private void requireValidatedDml(SqlValidationReport report) {
    if (report.validationLevel() == SqlValidationLevel.REJECTED
        || (report.statementType() != SqlStatementType.INSERT
            && report.statementType() != SqlStatementType.UPDATE
            && report.statementType() != SqlStatementType.DELETE)) {
      throw new IllegalArgumentException(dmlValidationFailureMessage(report));
    }
  }

  private SqlDmlExecutionBinding executionBinding(
      SqlDmlCommitRequest commitRequest,
      SqlValidationReport report,
      SqlDmlPreviewSelection previewSelection,
      OperatorContext operator,
      PolicyDecisionReference policyDecision) {
    String parametersHash = hashComponents(commitRequest.query().parameters().stream()
        .map(parameter -> canonicalList(List.of(
            parameter.name(), parameter.type(), canonicalJson(parameter.value())), false))
        .sorted()
        .toArray(String[]::new));
    String preflightHash = validationHash(report, previewSelection);
    SqlDmlConfirmation confirmation = commitRequest.confirmation();
    String confirmationHash = hashComponents(
        confirmation.sqlHash(),
        canonicalList(confirmation.confirmedRisks(), true),
        confirmation.confirmationCode());
    String bindingHash = hashComponents(
        commitRequest.query().connectionId(),
        commitRequest.query().targetEnvironment(),
        commitRequest.query().schema(),
        commitRequest.query().action().name(),
        report.sqlHash(),
        parametersHash,
        preflightHash,
        confirmationHash,
        operator.operatorId(),
        policyDecision.decisionId(),
        policyDecision.policyVersion(),
        policyDecision.decision());
    return new SqlDmlExecutionBinding(
        bindingHash, parametersHash, preflightHash, confirmationHash);
  }

  private String validationHash(
      SqlValidationReport report,
      SqlDmlPreviewSelection previewSelection) {
    return hashComponents(
        report.statementType().name(),
        report.validationLevel().name(),
        report.sqlHash(),
        canonicalList(report.referencedObjects(), true),
        canonicalList(report.risks(), true),
        canonicalList(report.rejectionReasons(), true),
        canonicalList(report.unverifiedItems(), true),
        canonicalList(previewSelection.sampleColumns(), false),
        canonicalList(previewSelection.maskedSampleColumns(), false));
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

  private SqlConnectionSummary serverVisibleConnection(SqlConnectionSummary connection) {
    if (transactionalAuditAvailable
        && controlledDmlEnvironmentEnabled.test(connection.targetEnvironment())
        && controlledDmlPolicy.supports(connection)
        && "READY".equalsIgnoreCase(connection.status())) {
      return connection;
    }
    List<SqlQueryAction> visibleCapabilities = connection.capabilities().stream()
        .filter(action -> action != SqlQueryAction.PREFLIGHT_DML && action != SqlQueryAction.COMMIT_DML)
        .toList();
    return new SqlConnectionSummary(
        connection.contractVersion(),
        connection.connectionId(),
        connection.displayName(),
        connection.targetEnvironment(),
        connection.platformType(),
        connection.host(),
        connection.port(),
        connection.defaultSchema(),
        connection.allowedSchemas(),
        visibleCapabilities,
        connection.credentialAlias(),
        connection.status(),
        connection.maxRowsDefault(),
        connection.timeoutSecondsDefault());
  }

  private String hashComponents(String... components) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (String component : components) {
        byte[] bytes = component.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is not available", exception);
    }
  }

  private String canonicalList(List<String> values, boolean sort) {
    List<String> canonical = new ArrayList<>(values);
    if (sort) {
      canonical.sort(Comparator.naturalOrder());
    }
    StringBuilder output = new StringBuilder();
    for (String value : canonical) {
      appendCanonicalComponent(output, value);
    }
    return output.toString();
  }

  private String canonicalJson(JsonNode value) {
    if (value == null || value.isNull()) {
      return "null";
    }
    if (value.isObject()) {
      StringBuilder output = new StringBuilder("object:");
      value.properties().stream()
          .sorted(java.util.Map.Entry.comparingByKey())
          .forEach(entry -> {
            appendCanonicalComponent(output, entry.getKey());
            appendCanonicalComponent(output, canonicalJson(entry.getValue()));
          });
      return output.toString();
    }
    if (value.isArray()) {
      StringBuilder output = new StringBuilder("array:");
      value.forEach(element -> appendCanonicalComponent(output, canonicalJson(element)));
      return output.toString();
    }
    return value.getNodeType().name() + ":" + value;
  }

  private void appendCanonicalComponent(StringBuilder output, String component) {
    byte[] bytes = component.getBytes(StandardCharsets.UTF_8);
    output.append(bytes.length).append(':').append(component);
  }

  private static ControlledSqlDmlPolicy failClosedDmlPolicy() {
    return new ControlledSqlDmlPolicy(
        new ControlledSqlDmlProperties(),
        new CalciteSqlDmlAnalysis());
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
    public SqlDmlImpactPreview preflightDml(SqlDmlPreflightExecutionRequest request) {
      throw new IllegalStateException("SQL workbench worker client is not configured");
    }

    @Override
    public SqlQueryExecutionResult executeControlledDml(SqlControlledDmlExecutionRequest request) {
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
