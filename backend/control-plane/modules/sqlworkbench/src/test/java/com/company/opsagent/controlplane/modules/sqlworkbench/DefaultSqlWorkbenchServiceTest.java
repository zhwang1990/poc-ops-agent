package com.company.opsagent.controlplane.modules.sqlworkbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.company.opsagent.contracts.sqlworkbench.SqlConnectionCreateRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlConnectionProbeResult;
import com.company.opsagent.contracts.sqlworkbench.SqlConnectionSummary;
import com.company.opsagent.contracts.sqlworkbench.SqlConnectionUpdateRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlAssistantAction;
import com.company.opsagent.contracts.sqlworkbench.SqlAssistantRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlAssistantResponse;
import com.company.opsagent.contracts.sqlworkbench.SqlAssistantStatus;
import com.company.opsagent.contracts.sqlworkbench.SqlAssistantSuggestion;
import com.company.opsagent.contracts.sqlworkbench.SqlDmlCommitRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlDmlConfirmation;
import com.company.opsagent.contracts.sqlworkbench.SqlDmlImpactPreview;
import com.company.opsagent.contracts.sqlworkbench.SqlDmlPreflightExecutionRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlDmlPreflightResult;
import com.company.opsagent.contracts.sqlworkbench.SqlControlledDmlExecutionRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryAction;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryExecutionRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryExecutionResult;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryLimits;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlDatabaseMetadata;
import com.company.opsagent.contracts.sqlworkbench.SqlMetadataColumn;
import com.company.opsagent.contracts.sqlworkbench.SqlMetadataIndex;
import com.company.opsagent.contracts.sqlworkbench.SqlMetadataObject;
import com.company.opsagent.contracts.sqlworkbench.SqlResultColumn;
import com.company.opsagent.contracts.sqlworkbench.SqlResultPage;
import com.company.opsagent.contracts.sqlworkbench.SqlValidationLevel;
import com.company.opsagent.contracts.workflow.OperatorContext;
import com.company.opsagent.contracts.workflow.PolicyDecisionReference;
import com.company.opsagent.contracts.workflow.TraceContext;
import com.company.opsagent.controlplane.modules.workflow.ControlledSqlDmlWorkflowRequest;
import com.company.opsagent.controlplane.modules.workflow.ControlledSqlDmlWorkflowService;
import com.company.opsagent.controlplane.modules.workflow.ControlledSqlDmlWorkflowStore;
import com.fasterxml.jackson.databind.node.TextNode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class DefaultSqlWorkbenchServiceTest {

  private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-27T00:00:00Z"), ZoneOffset.UTC);
  private final RecordingSqlWorkbenchWorkerClient workerClient = new RecordingSqlWorkbenchWorkerClient();
  private final RecordingSqlAssistantClient assistantClient = new RecordingSqlAssistantClient();
  private final RecordingControlledSqlDmlExecutor dmlExecutor = new RecordingControlledSqlDmlExecutor();
  private final SqlDmlPreflightReceiptService receiptService = receiptService();
  private final DefaultSqlWorkbenchService service = new DefaultSqlWorkbenchService(
      new InMemorySqlConnectionCatalog(List.of(new SqlConnectionSummary(
          "1.0",
          "as400-development",
          "AS/400 Development",
          "development",
          "DB2_FOR_I",
          List.of("ORDERS"),
          List.of(
              SqlQueryAction.VALIDATE,
              SqlQueryAction.RUN_READ_ONLY,
              SqlQueryAction.PREFLIGHT_DML,
              SqlQueryAction.COMMIT_DML)))),
      new CalciteSqlValidationService(),
      workerClient,
      assistantClient,
      controlledDmlPolicy(),
      receiptService,
      dmlExecutor,
      environment -> true,
      true,
      CLOCK);

  @Test
  void rejectsSchemaOutsideConnectionAllowList() {
    assertThrows(IllegalArgumentException.class, () -> service.validate(request("FINANCE")));
  }

  @Test
  void validatesAllowedRequest() {
    assertEquals(SqlValidationLevel.VALIDATED, service.validate(request("ORDERS")).validationLevel());
  }

  @Test
  void rejectsSqlThatReferencesAnotherSchema() {
    SqlQueryRequest request = request("ORDERS");
    var crossSchemaRequest = new SqlQueryRequest(
        request.contractVersion(),
        request.connectionId(),
        request.targetEnvironment(),
        request.schema(),
        request.action(),
        "select * from FINANCE.PAYROLL",
        request.parameters(),
        request.limits(),
        request.idempotencyKey());

    assertThrows(IllegalArgumentException.class, () -> service.validate(crossSchemaRequest));
  }

  @Test
  void createsDevelopmentConnectionWithPendingWorkerBindingStatus() {
    workerClient.probeStatus = "CREDENTIAL_ALIAS_NOT_FOUND";

    SqlConnectionSummary created = service.createConnection(new SqlConnectionCreateRequest(
        "1.0",
        "AS/400 Dev Sandbox",
        "development",
        "DB2_FOR_I",
        "as400-dev.internal",
        446,
        "ORDERS",
        List.of("ORDERS"),
        List.of(SqlQueryAction.VALIDATE, SqlQueryAction.RUN_READ_ONLY, SqlQueryAction.PREFLIGHT_DML, SqlQueryAction.COMMIT_DML),
        "as400-dev-readonly",
        500,
        30));

    assertEquals("PENDING_WORKER_BINDING", created.status());
    assertEquals("as400-dev-readonly", created.credentialAlias());
    assertEquals("ORDERS", created.defaultSchema());
    assertEquals(446, created.port());
  }

  @Test
  void promotesCreatedConnectionToReadyWhenWorkerProbeSucceeds() {
    SqlConnectionSummary created = service.createConnection(new SqlConnectionCreateRequest(
        "1.0",
        "H2 Local Test",
        "test",
        "H2",
        "localhost",
        9092,
        "PUBLIC",
        List.of("PUBLIC"),
        List.of(SqlQueryAction.VALIDATE, SqlQueryAction.RUN_READ_ONLY, SqlQueryAction.PREFLIGHT_DML, SqlQueryAction.COMMIT_DML),
        "h2-local-readonly",
        500,
        30));

    assertEquals("READY", created.status());
    assertEquals(1, workerClient.probeCount);
    assertEquals("h2-local-test", workerClient.lastProbeConnection.connectionId());
    assertEquals("H2", workerClient.lastProbeConnection.platformType());
  }

  @Test
  void rejectsConnectionCreateRequestWithJdbcCredentialMaterialInHost() {
    assertThrows(IllegalArgumentException.class, () -> service.createConnection(new SqlConnectionCreateRequest(
        "1.0",
        "AS/400 Dev Unsafe",
        "development",
        "DB2_FOR_I",
        "jdbc:as400://user:password@as400-dev.internal",
        446,
        "ORDERS",
        List.of("ORDERS"),
        List.of(SqlQueryAction.VALIDATE, SqlQueryAction.RUN_READ_ONLY),
        "as400-dev-readonly",
        500,
        30)));
  }

  @Test
  void updatesConnectionMetadataAndResetsWorkerBindingStatus() {
    SqlConnectionSummary updated = service.updateConnection(
        "as400-development",
        new SqlConnectionUpdateRequest(
            "1.0",
            "AS/400 Reporting",
            "test",
            "MYSQL",
            "mysql-reporting.internal",
            3306,
            "REPORTING",
            List.of("REPORTING"),
            List.of(SqlQueryAction.VALIDATE, SqlQueryAction.RUN_READ_ONLY, SqlQueryAction.PREFLIGHT_DML, SqlQueryAction.COMMIT_DML),
            "mysql-reporting-readonly",
            250,
            45));

    assertEquals("as400-development", updated.connectionId());
    assertEquals("AS/400 Reporting", updated.displayName());
    assertEquals("sit", updated.targetEnvironment());
    assertEquals("MYSQL", updated.platformType());
    assertEquals("mysql-reporting.internal", updated.host());
    assertEquals("REPORTING", updated.defaultSchema());
    assertEquals("mysql-reporting-readonly", updated.credentialAlias());
    assertEquals("PENDING_WORKER_BINDING", updated.status());
    assertEquals(250, updated.maxRowsDefault());
    assertEquals(0, workerClient.probeCount);
  }

  @Test
  void deletesConnectionFromCatalog() {
    service.deleteConnection("as400-development");

    assertEquals(0, service.listConnections().size());
    assertThrows(IllegalArgumentException.class, () -> service.validate(request("ORDERS")));
  }

  @Test
  void runReadOnlyRejectsAnyOtherActionBeforeWorkerSubmission() {
    SqlQueryRequest request = request("ORDERS", SqlQueryAction.PREFLIGHT_DML, "delete from ORDERS.ORDERS where id = 1");

    assertThrows(IllegalArgumentException.class, () -> service.runReadOnlyQuery(
        request,
        operator(),
        policy(),
        trace()));
    assertEquals(0, workerClient.executeCount);
  }

  @Test
  void runReadOnlyReportsValidationDetailsBeforeWorkerSubmission() {
    SqlQueryRequest request = request(
        "ORDERS",
        SqlQueryAction.RUN_READ_ONLY,
        "update ORDERS.ORDERS set status = 'READY' where order_id = 42");

    IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> service.runReadOnlyQuery(
        request,
        operator(),
        policy(),
        trace()));

    assertTrue(exception.getMessage().contains("SELECT 执行未通过服务端只读校验"));
    assertTrue(exception.getMessage().contains("statementType=UPDATE"));
    assertTrue(exception.getMessage().contains("validationLevel=REJECTED"));
    assertTrue(exception.getMessage().contains("rejectionReasons=DML execution is prohibited for read-only actions"));
    assertTrue(exception.getMessage().contains("sqlHash=sha256:"));
    assertEquals(0, workerClient.executeCount);
  }

  @Test
  void runReadOnlySubmitsAuthorizedExecutionEnvelope() {
    SqlQueryRequest request = request("ORDERS");
    String expectedHash = service.validate(request).sqlHash();

    SqlQueryExecutionResult result = service.runReadOnlyQuery(request, operator(), policy(), trace());

    assertEquals("SUCCEEDED", result.status());
    assertEquals(1, workerClient.executeCount);
    SqlQueryExecutionRequest submitted = workerClient.lastExecutionRequest;
    assertEquals(SqlQueryAction.RUN_READ_ONLY, submitted.query().action());
    assertEquals(expectedHash, submitted.validationHash());
    assertEquals("operator-1", submitted.operator().operatorId());
    assertEquals("decision-1", submitted.policyDecision().decisionId());
    assertEquals("trace-1", submitted.trace().traceId());
    assertEquals("idempotency-key", submitted.query().idempotencyKey());
  }

  @Test
  void commitControlledDmlDelegatesConfirmedRequestToPersistentWorkflow() {
    SqlQueryRequest request = request(
        "ORDERS",
        SqlQueryAction.COMMIT_DML,
        "update ORDERS.ORDERS set status = 'READY' where order_id = 42");
    SqlQueryExecutionResult result = service.commitControlledDml(
        commitRequest(request, List.of("CONTROLLED_DML_CONFIRMED")),
        operator(),
        policy(),
        trace());

    assertEquals("SUCCEEDED", result.status());
    assertEquals(1, dmlExecutor.executeCount);
    assertEquals("operator-1", dmlExecutor.lastRequest.operator().operatorId());
    assertEquals(service.validate(request).sqlHash().substring("sha256:".length()), dmlExecutor.lastRequest.sqlHash());
    assertEquals(0, workerClient.executeCount);
  }

  @Test
  void preflightControlledDmlUsesServerPolicySelectionAndWorkerPreview() {
    SqlQueryRequest request = request(
        "ORDERS",
        SqlQueryAction.PREFLIGHT_DML,
        "update ORDERS.ORDERS set status = 'READY' where order_id = 42");

    SqlDmlPreflightResult result = service.preflightControlledDml(
        request, operator(), policy(), trace());

    assertEquals(3L, result.impactPreview().affectedRows());
    assertEquals("1.1", result.contractVersion());
    assertEquals(
        com.company.opsagent.contracts.workflow.WorkerRequestSignature.sqlDmlImpactPreviewDigest(
            workerClient.lastPreflightPreview),
        result.receipt().impactPreviewHash());
    assertEquals(1, workerClient.preflightCount);
    assertEquals(List.of("ORDER_ID"), workerClient.lastPreflightRequest.previewSelection().sampleColumns());
    assertEquals("operator-1", workerClient.lastPreflightRequest.operator().operatorId());
  }

  @Test
  void mapsStableWorkflowErrorsAtSqlWorkbenchBoundary() {
    SqlQueryRequest request = request(
        "ORDERS",
        SqlQueryAction.COMMIT_DML,
        "update ORDERS.ORDERS set status = 'READY' where order_id = 42");
    SqlDmlCommitRequest commitRequest = commitRequest(request, List.of("CONTROLLED_DML_CONFIRMED"));
    dmlExecutor.failure = new ControlledSqlDmlWorkflowService.WorkflowException(
        "SQL_DML_RESULT_UNKNOWN", "DML result requires human handoff");

    SqlWorkbenchException exception = assertThrows(
        SqlWorkbenchException.class,
        () -> service.commitControlledDml(commitRequest, operator(), policy(), trace()));

    assertEquals("SQL_DML_RESULT_UNKNOWN", exception.code());
  }

  @Test
  void passesTypedUnknownHandoffThroughSqlWorkbenchBoundary() {
    SqlQueryRequest request = request(
        "ORDERS",
        SqlQueryAction.COMMIT_DML,
        "update ORDERS.ORDERS set status = 'READY' where order_id = 42");
    dmlExecutor.result = new SqlQueryExecutionResult(
        "1.0",
        "workflow-handoff-dml",
        "workflow-handoff",
        "UNKNOWN_REQUIRES_HANDOFF",
        null,
        "SQL_DML_RESULT_UNKNOWN",
        null,
        null);

    SqlQueryExecutionResult result = service.commitControlledDml(
        commitRequest(request, List.of("CONTROLLED_DML_CONFIRMED")),
        operator(),
        policy(),
        trace());

    assertEquals("UNKNOWN_REQUIRES_HANDOFF", result.status());
    assertEquals("workflow-handoff", result.workflowId());
    assertEquals("SQL_DML_RESULT_UNKNOWN", result.errorCode());
    assertNull(result.resultId());
    assertNull(result.errorMessage());
    assertNull(result.affectedRows());
  }

  @Test
  void removesDmlCapabilitiesAndRejectsDirectDmlWhenTransactionalAuditIsUnavailable() {
    DefaultSqlWorkbenchService unavailable = new DefaultSqlWorkbenchService(
        new InMemorySqlConnectionCatalog(service.listConnections()),
        new CalciteSqlValidationService(),
        workerClient,
        assistantClient,
        controlledDmlPolicy(),
        dmlExecutor,
        environment -> true,
        false,
        CLOCK);
    SqlConnectionSummary connection = unavailable.listConnections().getFirst();

    assertEquals(
        List.of(SqlQueryAction.VALIDATE, SqlQueryAction.RUN_READ_ONLY),
        connection.capabilities());
    SqlWorkbenchException exception = assertThrows(
        SqlWorkbenchException.class,
        () -> unavailable.preflightControlledDml(
            request(
                "ORDERS",
                SqlQueryAction.PREFLIGHT_DML,
                "update ORDERS.ORDERS set status = 'READY' where order_id = 42"),
            operator(),
            policy(),
            trace()));
    assertEquals("SQL_DML_TRANSACTIONAL_AUDIT_REQUIRED", exception.code());
    assertEquals(0, workerClient.preflightCount);
  }

  @Test
  void removesDmlCapabilitiesWhenWorkerDmlTransportIsUnavailable() {
    DefaultSqlWorkbenchService unavailable = new DefaultSqlWorkbenchService(
        new InMemorySqlConnectionCatalog(service.listConnections()),
        new CalciteSqlValidationService(),
        workerClient,
        assistantClient,
        controlledDmlPolicy(),
        dmlExecutor,
        environment -> false,
        true,
        CLOCK);

    assertEquals(
        List.of(SqlQueryAction.VALIDATE, SqlQueryAction.RUN_READ_ONLY),
        unavailable.listConnections().getFirst().capabilities());
  }

  @Test
  void removesDmlCapabilitiesAndFailsClosedWhenCompatibilityConstructorHasNoReceiptSigner() {
    DefaultSqlWorkbenchService unavailable = new DefaultSqlWorkbenchService(
        new InMemorySqlConnectionCatalog(service.listConnections()),
        new CalciteSqlValidationService(),
        workerClient,
        assistantClient,
        controlledDmlPolicy(),
        dmlExecutor,
        environment -> true,
        true,
        CLOCK);
    SqlQueryRequest preflightRequest = request(
        "ORDERS",
        SqlQueryAction.PREFLIGHT_DML,
        "update ORDERS.ORDERS set status = 'READY' where order_id = 42");

    assertEquals(
        List.of(SqlQueryAction.VALIDATE, SqlQueryAction.RUN_READ_ONLY),
        unavailable.listConnections().getFirst().capabilities());
    SqlWorkbenchException preflightException = assertThrows(
        SqlWorkbenchException.class,
        () -> unavailable.preflightControlledDml(preflightRequest, operator(), policy(), trace()));

    assertEquals("SQL_DML_PREFLIGHT_RECEIPT_UNAVAILABLE", preflightException.code());
    assertEquals(0, workerClient.preflightCount);

    SqlQueryRequest commitQuery = request(
        "ORDERS",
        SqlQueryAction.COMMIT_DML,
        "update ORDERS.ORDERS set status = 'READY' where order_id = 42");
    SqlWorkbenchException commitException = assertThrows(
        SqlWorkbenchException.class,
        () -> unavailable.commitControlledDml(
            commitRequest(commitQuery, List.of("CONTROLLED_DML_CONFIRMED")),
            operator(), policy(), trace()));

    assertEquals("SQL_DML_PREFLIGHT_RECEIPT_UNAVAILABLE", commitException.code());
    assertEquals(0, dmlExecutor.executeCount);
  }

  @Test
  void commitControlledDmlRequiresSecondConfirmationForUpdateWithoutWhere() {
    SqlQueryRequest request = request(
        "ORDERS",
        SqlQueryAction.COMMIT_DML,
        "update ORDERS.ORDERS set status = 'READY'");

    IllegalArgumentException exception = assertThrows(
        IllegalArgumentException.class,
        () -> service.commitControlledDml(
            new SqlDmlCommitRequest("1.0", request, null),
            operator(),
            policy(),
            trace()));

    assertTrue(exception.getMessage().contains("DML 提交需要操作人二次确认"));
    assertTrue(exception.getMessage().contains("UPDATE_WITHOUT_WHERE"));
    assertEquals(0, workerClient.executeCount);
  }

  @Test
  void rejectsCommitWithoutServerIssuedPreflightReceiptBeforeWorkflowInvocation() {
    SqlQueryRequest request = request(
        "ORDERS",
        SqlQueryAction.COMMIT_DML,
        "update ORDERS.ORDERS set status = 'READY' where order_id = 42");
    SqlWorkbenchException exception = assertThrows(
        SqlWorkbenchException.class,
        () -> service.commitControlledDml(
            new SqlDmlCommitRequest(
                "1.0",
                request,
                new SqlDmlConfirmation(
                    "1.0",
                    service.validate(request).sqlHash(),
                    List.of("CONTROLLED_DML_CONFIRMED"),
                    SqlDmlConfirmation.RISK_CONFIRMATION_CODE)),
            operator(),
            policy(),
            trace()));

    assertEquals("SQL_DML_PREFLIGHT_RECEIPT_REQUIRED", exception.code());
    assertEquals(0, dmlExecutor.executeCount);
  }

  @Test
  void rejectsReceiptThatDoesNotMatchCommitBeforeWorkflowInvocation() {
    SqlQueryRequest request = request(
        "ORDERS",
        SqlQueryAction.COMMIT_DML,
        "update ORDERS.ORDERS set status = 'READY' where order_id = 42");
    SqlDmlCommitRequest valid = commitRequest(request, List.of("CONTROLLED_DML_CONFIRMED"));
    SqlDmlCommitRequest tampered = new SqlDmlCommitRequest(
        "1.1",
        valid.query(),
        valid.confirmation(),
        new com.company.opsagent.contracts.sqlworkbench.SqlDmlPreflightReceipt(
            valid.receipt().contractVersion(),
            valid.receipt().receiptId(),
            valid.receipt().keyId(),
            valid.receipt().issuedAt(),
            valid.receipt().expiresAt(),
            "other-operator",
            valid.receipt().requestHash(),
            valid.receipt().connectionId(),
            valid.receipt().targetEnvironment(),
            valid.receipt().schema(),
            valid.receipt().sqlHash(),
            valid.receipt().parametersHash(),
            valid.receipt().policyVersion(),
            valid.receipt().policySelectionHash(),
            valid.receipt().impactPreviewHash(),
            valid.receipt().preflightHash(),
            valid.receipt().signature()));

    SqlWorkbenchException exception = assertThrows(
        SqlWorkbenchException.class,
        () -> service.commitControlledDml(tampered, operator(), policy(), trace()));

    assertEquals("SQL_DML_PREFLIGHT_RECEIPT_INVALID", exception.code());
    assertEquals(0, dmlExecutor.executeCount);
  }

  @Test
  void allowsAnAuthenticatedExpiredReceiptThroughM09ForM05Recovery() {
    SqlQueryRequest request = request(
        "ORDERS",
        SqlQueryAction.COMMIT_DML,
        "update ORDERS.ORDERS set status = 'READY' where order_id = 42");
    SqlDmlCommitRequest commitRequest = commitRequest(
        request, List.of("CONTROLLED_DML_CONFIRMED"));
    Clock afterExpiry = Clock.offset(CLOCK, Duration.ofMinutes(6));
    DefaultSqlWorkbenchService recoveredBoundary = new DefaultSqlWorkbenchService(
        new InMemorySqlConnectionCatalog(service.listConnections()),
        new CalciteSqlValidationService(),
        workerClient,
        assistantClient,
        controlledDmlPolicy(),
        receiptService(afterExpiry),
        dmlExecutor,
        environment -> true,
        true,
        afterExpiry);

    SqlQueryExecutionResult result = recoveredBoundary.commitControlledDml(
        commitRequest, operator(), policy(), trace());

    assertEquals("SUCCEEDED", result.status());
    assertEquals(1, dmlExecutor.executeCount);
  }

  @Test
  void commitControlledDmlAcceptsMatchingSecondConfirmation() {
    SqlQueryRequest request = request(
        "ORDERS",
        SqlQueryAction.COMMIT_DML,
        "update ORDERS.ORDERS set status = 'READY'");
    SqlQueryExecutionResult result = service.commitControlledDml(
        commitRequest(request, List.of("UPDATE_WITHOUT_WHERE")),
        operator(),
        policy(),
        trace());

    assertEquals("SUCCEEDED", result.status());
    assertEquals(1, dmlExecutor.executeCount);
    assertEquals(0, workerClient.executeCount);
  }

  @Test
  void derivesSameBindingForFreshEquivalentPolicyDecisionRetry() {
    SqlQueryRequest request = request(
        "ORDERS",
        SqlQueryAction.COMMIT_DML,
        "update ORDERS.ORDERS set status = 'READY' where order_id = 42");

    service.commitControlledDml(
        commitRequest(request, List.of("CONTROLLED_DML_CONFIRMED")),
        operator(), policy(), trace());
    String firstBindingHash = dmlExecutor.lastRequest.binding().bindingHash();
    service.commitControlledDml(
        commitRequest(request, List.of("CONTROLLED_DML_CONFIRMED")),
        operator(), policy("decision-2"), trace());

    assertEquals(firstBindingHash, dmlExecutor.lastRequest.binding().bindingHash());
  }

  @Test
  void readsResultPageThroughWorkerClient() {
    SqlResultPage page = service.readResultPage("result-1");

    assertEquals("result-1", page.resultId());
    assertEquals(1, workerClient.readCount);
  }

  @Test
  void readsDatabaseMetadataOnlyForAllowedSchemaThroughWorkerClient() {
    SqlDatabaseMetadata metadata = service.readMetadata("as400-development", "ORDERS");

    assertEquals("ORDERS", metadata.schema());
    assertEquals("ORDERS", metadata.objects().getFirst().name());
    assertEquals(1, workerClient.metadataCount);
    assertEquals("as400-development", workerClient.lastMetadataConnection.connectionId());
    assertEquals("ORDERS", workerClient.lastMetadataSchema);
  }

  @Test
  void rejectsDatabaseMetadataForSchemaOutsideAllowList() {
    assertThrows(IllegalArgumentException.class, () -> service.readMetadata("as400-development", "FINANCE"));
    assertEquals(0, workerClient.metadataCount);
  }

  @Test
  void probesConnectionThroughWorkerClient() {
    SqlConnectionProbeResult result = service.probeConnection("as400-development");

    assertEquals("as400-development", result.connectionId());
    assertEquals("READY", result.status());
    assertEquals(OffsetDateTime.now(CLOCK), result.probedAt());
    assertEquals(1, workerClient.probeCount);
    assertEquals("as400-development", workerClient.lastProbeConnection.connectionId());
    assertEquals("as400-development", workerClient.lastProbeConnection.credentialAlias());
  }

  @Test
  void assistantValidatesSqlBeforeReturningAdvisorySuggestions() {
    SqlAssistantResponse response = service.assist(assistantRequest(
        SqlAssistantAction.OPTIMIZE_SQL,
        "select * from ORDERS.ORDERS",
        null));

    assertEquals(SqlAssistantStatus.SUCCEEDED, response.status());
    assertEquals(true, response.validationRequired());
    assertEquals(1, assistantClient.askCount);
    assertEquals(SqlValidationLevel.VALIDATED, assistantClient.lastPrompt.validationReport().validationLevel());
    assertEquals("DB2_FOR_I", assistantClient.lastPrompt.platformType());
    assertEquals("as400-development", assistantClient.lastPrompt.connectionId());
  }

  @Test
  void assistantRejectsUnsafeRequestBeforeModelCall() {
    SqlAssistantRequest request = assistantRequest(
        SqlAssistantAction.EXPLAIN_SQL,
        "select * from FINANCE.PAYROLL",
        null);

    assertThrows(IllegalArgumentException.class, () -> service.assist(request));
    assertEquals(0, assistantClient.askCount);
  }

  @Test
  void assistantCanGenerateSelectFromNaturalLanguageWithoutStaticSqlValidation() {
    SqlAssistantResponse response = service.assist(assistantRequest(
        SqlAssistantAction.GENERATE_SELECT,
        "用户要求：查询未完成订单",
        "naturalLanguage=查询未完成订单"));

    assertEquals(SqlAssistantStatus.SUCCEEDED, response.status());
    assertEquals(1, assistantClient.askCount);
    assertEquals(SqlValidationLevel.PARTIAL, assistantClient.lastPrompt.validationReport().validationLevel());
    assertTrue(assistantClient.lastPrompt.validationReport().risks().contains("ADVISORY_ONLY"));
    assertTrue(assistantClient.lastPrompt.diagnosticContext().contains("naturalLanguage=查询未完成订单"));
  }

  private SqlQueryRequest request(String schema) {
    return request(schema, SqlQueryAction.RUN_READ_ONLY, "select * from ORDERS.ORDERS");
  }

  private SqlQueryRequest request(String schema, SqlQueryAction action, String sql) {
    return new SqlQueryRequest(
        "1.0",
        "as400-development",
        "development",
        schema,
        action,
        sql,
        List.of(),
        new SqlQueryLimits(500, 5_000_000, 30),
        "idempotency-key");
  }

  private OperatorContext operator() {
    return new OperatorContext("operator-1", List.of("ROLE_ops-reader"));
  }

  private PolicyDecisionReference policy() {
    return policy("decision-1");
  }

  private PolicyDecisionReference policy(String decisionId) {
    return new PolicyDecisionReference(decisionId, "policy-v1", "ALLOW");
  }

  private SqlDmlCommitRequest commitRequest(SqlQueryRequest request, List<String> confirmedRisks) {
    SqlQueryRequest preflightRequest = new SqlQueryRequest(
        request.contractVersion(),
        request.connectionId(),
        request.targetEnvironment(),
        request.schema(),
        SqlQueryAction.PREFLIGHT_DML,
        request.sql(),
        request.parameters(),
        request.limits(),
        request.idempotencyKey());
    SqlDmlPreflightResult preflight = service.preflightControlledDml(
        preflightRequest, operator(), policy(), trace());
    return new SqlDmlCommitRequest(
        "1.1",
        request,
        new SqlDmlConfirmation(
            "1.0",
            service.validate(request).sqlHash(),
            confirmedRisks,
            SqlDmlConfirmation.RISK_CONFIRMATION_CODE),
        preflight.receipt());
  }

  private static SqlDmlPreflightReceiptService receiptService() {
    return receiptService(CLOCK);
  }

  private static SqlDmlPreflightReceiptService receiptService(Clock clock) {
    var properties = new SqlDmlPreflightReceiptProperties();
    properties.setKeyId("test-receipt-key");
    properties.setHmacSecret("test-only-receipt-secret");
    return new SqlDmlPreflightReceiptService(properties, clock);
  }

  private TraceContext trace() {
    return new TraceContext("trace-1", "request-1");
  }

  private static ControlledSqlDmlPolicy controlledDmlPolicy() {
    var properties = new ControlledSqlDmlProperties();
    properties.setEnabledEnvironments(Set.of("dev"));
    var scopedUpdate = updateRule(Set.of("ORDER_ID"), Set.of("EQUALS"));
    var unscopedUpdate = updateRule(Set.of(), Set.of());
    properties.setRules(List.of(scopedUpdate, unscopedUpdate));
    return new ControlledSqlDmlPolicy(properties, new CalciteSqlDmlAnalysis());
  }

  private static ControlledSqlDmlProperties.Rule updateRule(
      Set<String> predicateColumns, Set<String> operators) {
    var rule = new ControlledSqlDmlProperties.Rule();
    rule.setConnectionId("as400-development");
    rule.setSchema("ORDERS");
    rule.setTable("ORDERS");
    rule.setStatementType(com.company.opsagent.contracts.sqlworkbench.SqlStatementType.UPDATE);
    rule.setChangedColumns(Set.of("STATUS"));
    rule.setPredicateColumns(predicateColumns);
    rule.setOperators(operators);
    rule.setPreviewSampleColumns(List.of("ORDER_ID"));
    rule.setMaskedPreviewColumns(List.of());
    return rule;
  }

  private SqlAssistantRequest assistantRequest(
      SqlAssistantAction action,
      String sql,
      String diagnosticContext) {
    return new SqlAssistantRequest(
        "1.0",
        "as400-development",
        "development",
        "ORDERS",
        action,
        sql,
        new SqlQueryLimits(500, 5_000_000, 30),
        diagnosticContext,
        "sql-assistant-key");
  }

  private static final class RecordingSqlAssistantClient implements SqlAssistantClient {

    private int askCount;
    private SqlAssistantPrompt lastPrompt;

    @Override
    public SqlAssistantResponse ask(SqlAssistantPrompt prompt) {
      askCount++;
      lastPrompt = prompt;
      return new SqlAssistantResponse(
          "1.0",
          SqlAssistantStatus.SUCCEEDED,
          prompt.assistantAction(),
          "Prefer explicit columns and keep the statement read-only.",
          List.of(new SqlAssistantSuggestion(
              "Limit returned columns",
              "A narrower projection reduces transfer and review scope.",
              "select order_id, status from ORDERS.ORDERS")),
          List.of("AI suggestions must be validated by the server before execution."),
          true,
          "provider:fingerprint");
    }
  }

  private static final class RecordingSqlWorkbenchWorkerClient implements SqlWorkbenchWorkerClient {

    private int executeCount;
    private int readCount;
    private int probeCount;
    private int metadataCount;
    private int preflightCount;
    private String probeStatus = "READY";
    private SqlConnectionSummary lastProbeConnection;
    private SqlConnectionSummary lastMetadataConnection;
    private String lastMetadataSchema;
    private SqlQueryExecutionRequest lastExecutionRequest;
    private SqlDmlPreflightExecutionRequest lastPreflightRequest;
    private SqlDmlImpactPreview lastPreflightPreview;

    @Override
    public SqlConnectionProbeResult probe(SqlConnectionSummary connection) {
      probeCount++;
      lastProbeConnection = connection;
      return new SqlConnectionProbeResult(
          "1.0",
          connection.connectionId(),
          probeStatus,
          "READY".equals(probeStatus)
              ? "SQL connection probe succeeded"
              : "SQL connection probe did not match worker binding",
          OffsetDateTime.now(CLOCK));
    }

    @Override
    public SqlQueryExecutionResult execute(SqlQueryExecutionRequest request) {
      executeCount++;
      lastExecutionRequest = request;
      return new SqlQueryExecutionResult(
          "1.0",
          request.executionRequestId(),
          request.workflowId(),
          "SUCCEEDED",
          request.query().action() == SqlQueryAction.RUN_READ_ONLY ? "result-1" : null,
          null,
          null,
          request.query().action() == SqlQueryAction.COMMIT_DML ? 3 : null);
    }

    @Override
    public SqlDmlImpactPreview preflightDml(SqlDmlPreflightExecutionRequest request) {
      preflightCount++;
      lastPreflightRequest = request;
      lastPreflightPreview = new SqlDmlImpactPreview("1.0", 3L, List.of(), List.of(), List.of());
      return lastPreflightPreview;
    }

    @Override
    public SqlQueryExecutionResult executeControlledDml(SqlControlledDmlExecutionRequest request) {
      throw new AssertionError("M09 must submit controlled DML through M05");
    }

    @Override
    public SqlResultPage readResultPage(String resultId) {
      readCount++;
      return new SqlResultPage(
          "1.0",
          resultId,
          List.of(new SqlResultColumn("STATUS", "VARCHAR", false)),
          List.of(List.of(TextNode.valueOf("READY"))),
          null,
          false,
          OffsetDateTime.now(CLOCK).plusMinutes(15));
    }

    @Override
    public SqlDatabaseMetadata readMetadata(SqlConnectionSummary connection, String schema) {
      metadataCount++;
      lastMetadataConnection = connection;
      lastMetadataSchema = schema;
      return new SqlDatabaseMetadata(
          "1.0",
          connection.connectionId(),
          schema,
          List.of(new SqlMetadataObject(
              schema,
              "ORDERS",
              "TABLE",
              List.of(new SqlMetadataColumn("ORDER_ID", "INTEGER", false, 1, false)),
              List.of(new SqlMetadataIndex("PRIMARY_KEY_ORDERS", true, List.of("ORDER_ID"))))),
          false,
          OffsetDateTime.now(CLOCK));
    }
  }

  private static final class RecordingControlledSqlDmlExecutor
      implements Function<ControlledSqlDmlWorkflowRequest, SqlQueryExecutionResult> {

    private int executeCount;
    private ControlledSqlDmlWorkflowRequest lastRequest;
    private RuntimeException failure;
    private SqlQueryExecutionResult result;

    @Override
    public SqlQueryExecutionResult apply(ControlledSqlDmlWorkflowRequest request) {
      executeCount++;
      lastRequest = request;
      if (failure != null) {
        throw failure;
      }
      if (result != null) {
        return result;
      }
      return new SqlQueryExecutionResult(
          "1.0",
          "execution-1",
          "workflow-1",
          "SUCCEEDED",
          null,
          null,
          null,
          3);
    }
  }
}
