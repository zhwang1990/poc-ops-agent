package com.company.opsagent.controlplane.modules.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.company.opsagent.contracts.sqlworkbench.SqlControlledDmlExecutionRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlDmlCommitRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlDmlConfirmation;
import com.company.opsagent.contracts.sqlworkbench.SqlDmlExecutionBinding;
import com.company.opsagent.contracts.sqlworkbench.SqlDmlPreviewSelection;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryAction;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryExecutionResult;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryLimits;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlStatementType;
import com.company.opsagent.contracts.sqlworkbench.SqlValidationLevel;
import com.company.opsagent.contracts.sqlworkbench.SqlValidationReport;
import com.company.opsagent.contracts.workflow.OperatorContext;
import com.company.opsagent.contracts.workflow.PolicyDecisionReference;
import com.company.opsagent.contracts.workflow.TraceContext;
import com.company.opsagent.controlplane.modules.audit.AuditEvent;
import com.company.opsagent.controlplane.modules.audit.R2dbcAuditTrail;
import io.r2dbc.spi.ConnectionFactories;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.r2dbc.connection.init.ConnectionFactoryInitializer;
import org.springframework.r2dbc.connection.init.ResourceDatabasePopulator;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;

class ControlledSqlDmlWorkflowServiceTest {

  private static final Clock CLOCK = Clock.fixed(
      Instant.parse("2026-07-17T10:00:00Z"), ZoneOffset.UTC);
  private static final String SQL_HASH = "a".repeat(64);
  private static final String PARAMETERS_HASH = "b".repeat(64);
  private static final String PREFLIGHT_HASH = "c".repeat(64);
  private static final String CONFIRMATION_HASH = "d".repeat(64);
  private static final String BINDING_HASH = "e".repeat(64);

  private RecordingGateway gateway;
  private R2dbcControlledSqlDmlWorkflowStore store;
  private R2dbcAuditTrail auditTrail;
  private ControlledSqlDmlWorkflowService service;

  @BeforeEach
  void setUp() {
    var connectionFactory = ConnectionFactories.get(
        "r2dbc:h2:mem:///controlled-sql-dml-service-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
    var initializer = new ConnectionFactoryInitializer();
    initializer.setConnectionFactory(connectionFactory);
    initializer.setDatabasePopulator(new ResourceDatabasePopulator(
        new ClassPathResource("sql/migrations/V001__audit_event_schema.sql"),
        new ClassPathResource("sql/migrations/V004__controlled_sql_dml_workflow.sql"),
        new ClassPathResource("sql/migrations/V005__controlled_sql_dml_execution_expiry.sql")));
    initializer.afterPropertiesSet();
    DatabaseClient databaseClient = DatabaseClient.create(connectionFactory);
    auditTrail = new R2dbcAuditTrail(databaseClient);
    store = new R2dbcControlledSqlDmlWorkflowStore(databaseClient, auditTrail);
    gateway = new RecordingGateway();
    service = new ControlledSqlDmlWorkflowService(store, gateway, request -> { }, CLOCK);
  }

  @Test
  void persistsAuditsAndSubmitsDmlOnlyAfterConfirmation() {
    SqlQueryExecutionResult result = service.execute(request(BINDING_HASH));

    assertEquals("SUCCEEDED", result.status());
    assertEquals(1, result.affectedRows());
    assertEquals(1, gateway.requests.size());
    ControlledSqlDmlWorkflow persisted = persisted();
    assertEquals(ControlledSqlDmlWorkflow.Status.SUCCEEDED, persisted.status());
    assertEquals(1, persisted.attemptCount());
    assertEquals(List.of(
        "SQL_DML_CREATED",
        "SQL_DML_CONFIRMED",
        "SQL_DML_SUBMITTED",
        "SQL_DML_SUCCEEDED"),
        auditTrail.snapshot().stream().map(AuditEvent::action).toList());
  }

  @Test
  void reusesPersistedTerminalResultForSameIdempotencyBinding() {
    SqlQueryExecutionResult first = service.execute(request(BINDING_HASH));
    SqlQueryExecutionResult second = service.execute(request(BINDING_HASH));

    assertEquals(first, second);
    assertEquals(1, gateway.requests.size());
    assertEquals(1, persisted().attemptCount());
  }

  @Test
  void reusesTerminalResultWhenFreshPolicyDecisionHasSameSemanticBinding() {
    SqlQueryExecutionResult first = service.execute(request(BINDING_HASH, "decision-1"));
    SqlQueryExecutionResult second = service.execute(request(BINDING_HASH, "decision-2"));

    assertEquals(first, second);
    assertEquals(1, gateway.requests.size());
    assertEquals("decision-1", persisted().policyDecisionId());
  }

  @Test
  void reusesTerminalWorkflowAfterReceiptExpiryWithoutCallingWorkerAgain() {
    RecordingReceiptVerifier receiptVerifier = new RecordingReceiptVerifier();
    ControlledSqlDmlWorkflowService recovered = new ControlledSqlDmlWorkflowService(
        store, gateway, receiptVerifier, CLOCK);

    SqlQueryExecutionResult first = recovered.execute(request(BINDING_HASH));
    receiptVerifier.expired = true;

    SqlQueryExecutionResult second = recovered.execute(request(BINDING_HASH));

    assertEquals(first, second);
    assertEquals(1, gateway.requests.size());
    assertEquals(2, receiptVerifier.authenticityAndBindingChecks);
    assertEquals(2, receiptVerifier.dispatchChecks);
  }

  @Test
  void rejectsChangedBindingWithoutSecondWorkerSubmission() {
    service.execute(request(BINDING_HASH));

    ControlledSqlDmlWorkflowService.WorkflowException exception = assertThrows(
        ControlledSqlDmlWorkflowService.WorkflowException.class,
        () -> service.execute(request("f".repeat(64))));

    assertEquals("SQL_DML_IDEMPOTENCY_CONFLICT", exception.code());
    assertEquals(1, gateway.requests.size());
  }

  @Test
  void leavesUnknownResultForHumanHandoffWhenWorkerTimesOutAndNeverRetries() {
    gateway.timeout = true;

    SqlQueryExecutionResult first = service.execute(request(BINDING_HASH));
    SqlQueryExecutionResult second = service.execute(request(BINDING_HASH));

    assertHandoffResult(first, first.workflowId());
    assertEquals(first, second);
    assertEquals(1, gateway.requests.size());
    assertEquals(
        ControlledSqlDmlWorkflow.Status.UNKNOWN_REQUIRES_HANDOFF,
        persisted().status());
  }

  @Test
  void convertsExpiredRunningWorkflowToHandoffBeforeReturningDuplicate() {
    createRunningWorkflow(
        "workflow-expired", OffsetDateTime.now(CLOCK).minusSeconds(1));

    SqlQueryExecutionResult result = service.execute(request(BINDING_HASH));

    assertHandoffResult(result, "workflow-expired");
    assertEquals(0, gateway.requests.size());
    assertEquals(ControlledSqlDmlWorkflow.Status.UNKNOWN_REQUIRES_HANDOFF, persisted().status());
    assertEquals(
        "SQL_DML_HANDOFF_REQUIRED",
        auditTrail.snapshot().getLast().action());
  }

  @Test
  void reconcilesStaleRunningWorkflowAfterReceiptExpiryWithoutWorkerReplay() {
    createRunningWorkflow(
        "workflow-expired", OffsetDateTime.now(CLOCK).minusSeconds(1));
    RecordingReceiptVerifier receiptVerifier = new RecordingReceiptVerifier();
    receiptVerifier.expired = true;
    ControlledSqlDmlWorkflowService recovered = new ControlledSqlDmlWorkflowService(
        store, gateway, receiptVerifier, CLOCK);

    SqlQueryExecutionResult result = recovered.execute(request(BINDING_HASH));

    assertHandoffResult(result, "workflow-expired");
    assertEquals(ControlledSqlDmlWorkflow.Status.UNKNOWN_REQUIRES_HANDOFF, persisted().status());
    assertEquals(0, gateway.requests.size());
    assertEquals(1, receiptVerifier.authenticityAndBindingChecks);
    assertEquals(0, receiptVerifier.dispatchChecks);
  }

  @Test
  void recoversCreatedWorkflowAndDispatchesWorkerExactlyOnce() {
    ControlledSqlDmlWorkflow workflow = createCreatedWorkflow("workflow-created");
    RecordingReceiptVerifier receiptVerifier = new RecordingReceiptVerifier();
    ControlledSqlDmlWorkflowService recovered = new ControlledSqlDmlWorkflowService(
        store, gateway, receiptVerifier, CLOCK);

    SqlQueryExecutionResult first = recovered.execute(request(BINDING_HASH));
    SqlQueryExecutionResult second = recovered.execute(request(BINDING_HASH));

    assertEquals(first, second);
    assertEquals(1, gateway.requests.size());
    assertEquals(ControlledSqlDmlWorkflow.Status.SUCCEEDED, persisted().status());
    assertEquals(2, receiptVerifier.authenticityAndBindingChecks);
    assertEquals(1, receiptVerifier.dispatchChecks);
    assertTrue(auditTrail.snapshot().stream().map(AuditEvent::action)
        .anyMatch("SQL_DML_SUBMITTED"::equals));
  }

  @Test
  void movesCreatedWorkflowToAuditedHandoffWhenReceiptHasExpired() {
    createCreatedWorkflow("workflow-created");
    RecordingReceiptVerifier receiptVerifier = new RecordingReceiptVerifier();
    receiptVerifier.expired = true;
    ControlledSqlDmlWorkflowService recovered = new ControlledSqlDmlWorkflowService(
        store, gateway, receiptVerifier, CLOCK);

    SqlQueryExecutionResult result = recovered.execute(request(BINDING_HASH));

    assertHandoffResult(result, "workflow-created");
    assertEquals(ControlledSqlDmlWorkflow.Status.UNKNOWN_REQUIRES_HANDOFF, persisted().status());
    assertEquals(0, gateway.requests.size());
    assertTrue(auditTrail.snapshot().stream().map(AuditEvent::action)
        .anyMatch("SQL_DML_HANDOFF_REQUIRED"::equals));
    assertEquals(1, receiptVerifier.authenticityAndBindingChecks);
    assertEquals(1, receiptVerifier.dispatchChecks);
  }

  @Test
  void movesCreatedWorkflowToAuditedHandoffWhenReceiptIsInvalid() {
    createCreatedWorkflow("workflow-created");
    RecordingReceiptVerifier receiptVerifier = new RecordingReceiptVerifier();
    receiptVerifier.invalid = true;
    ControlledSqlDmlWorkflowService recovered = new ControlledSqlDmlWorkflowService(
        store, gateway, receiptVerifier, CLOCK);

    SqlQueryExecutionResult result = recovered.execute(request(BINDING_HASH));

    assertHandoffResult(result, "workflow-created");
    assertEquals(ControlledSqlDmlWorkflow.Status.UNKNOWN_REQUIRES_HANDOFF, persisted().status());
    assertEquals(0, gateway.requests.size());
    assertTrue(auditTrail.snapshot().stream().map(AuditEvent::action)
        .anyMatch("SQL_DML_HANDOFF_REQUIRED"::equals));
    assertEquals(1, receiptVerifier.authenticityAndBindingChecks);
    assertEquals(0, receiptVerifier.dispatchChecks);
  }

  @Test
  void failsClosedWhenUnknownHandoffCannotBePersisted() {
    gateway.timeout = true;
    ControlledSqlDmlWorkflowService unavailable = new ControlledSqlDmlWorkflowService(
        storeWithHandoffFailure(), gateway, request -> { }, CLOCK);

    ControlledSqlDmlWorkflowService.WorkflowException exception = assertThrows(
        ControlledSqlDmlWorkflowService.WorkflowException.class,
        () -> unavailable.execute(request(BINDING_HASH)));

    assertEquals("SQL_DML_HANDOFF_PERSISTENCE_FAILED", exception.code());
    assertEquals(1, gateway.requests.size());
    assertEquals(ControlledSqlDmlWorkflow.Status.RUNNING, persisted().status());
  }

  @Test
  void rejectsDirectWorkflowInvocationWithoutGenuinePreflightReceipt() {
    ControlledSqlDmlWorkflowService guarded = new ControlledSqlDmlWorkflowService(
        store,
        gateway,
        request -> {
          throw new ControlledSqlDmlWorkflowService.WorkflowException(
              "SQL_DML_PREFLIGHT_RECEIPT_REQUIRED", "A server-issued preflight receipt is required");
        },
        CLOCK);

    ControlledSqlDmlWorkflowService.WorkflowException exception = assertThrows(
        ControlledSqlDmlWorkflowService.WorkflowException.class,
        () -> guarded.execute(request(BINDING_HASH)));

    assertEquals("SQL_DML_PREFLIGHT_RECEIPT_REQUIRED", exception.code());
    assertEquals(0, gateway.requests.size());
  }

  @Test
  void rejectsDuplicateWhileOriginalSubmissionIsRunningWithoutChangingItsState() {
    ControlledSqlDmlWorkflowRequest request = request(BINDING_HASH);
    createRunningWorkflow("workflow-running", OffsetDateTime.now(CLOCK).plusSeconds(30));

    ControlledSqlDmlWorkflowService.WorkflowException exception = assertThrows(
        ControlledSqlDmlWorkflowService.WorkflowException.class,
        () -> service.execute(request));

    assertEquals("SQL_DML_WORKFLOW_IN_PROGRESS", exception.code());
    assertEquals(ControlledSqlDmlWorkflow.Status.RUNNING, persisted().status());
    assertEquals(0, gateway.requests.size());
  }

  @Test
  void persistsWorkerRejectionAsDefinitiveFailure() {
    gateway.rejectionCode = "SQL_DML_EGRESS_DENIED";

    SqlQueryExecutionResult result = service.execute(request(BINDING_HASH));

    assertEquals("FAILED", result.status());
    assertEquals("SQL_DML_EGRESS_DENIED", result.errorCode());
    assertEquals(ControlledSqlDmlWorkflow.Status.FAILED, persisted().status());
    assertEquals(1, gateway.requests.size());
  }

  @Test
  void persistsWorkerUnknownOutcomeAndReturnsSanitizedHandoffWithoutReplay() {
    gateway.unknown = true;

    SqlQueryExecutionResult first = service.execute(request(BINDING_HASH));
    SqlQueryExecutionResult duplicate = service.execute(request(BINDING_HASH));

    assertHandoffResult(first, first.workflowId());
    assertEquals(first, duplicate);
    assertEquals(ControlledSqlDmlWorkflow.Status.UNKNOWN_REQUIRES_HANDOFF, persisted().status());
    assertEquals(1, gateway.requests.size());
    assertEquals("SQL_DML_HANDOFF_REQUIRED", auditTrail.snapshot().getLast().action());
  }

  @Test
  void mapsWorkflowLookupInfrastructureFailureWithoutCallingWorker() {
    ControlledSqlDmlWorkflowService unavailable = serviceWithFailingLookup(
        new IllegalStateException("workflow database is unavailable"));

    ControlledSqlDmlWorkflowService.WorkflowException exception = assertThrows(
        ControlledSqlDmlWorkflowService.WorkflowException.class,
        () -> unavailable.execute(request(BINDING_HASH)));

    assertEquals("SQL_DML_WORKFLOW_PERSISTENCE_FAILED", exception.code());
    assertEquals(0, gateway.requests.size());
  }

  @Test
  void mapsMissingTransactionalAuditDuringWorkflowLookupWithoutCallingWorker() {
    ControlledSqlDmlWorkflowService unavailable = serviceWithFailingLookup(
        new ControlledSqlDmlWorkflowStore.TransactionalAuditRequiredException());

    ControlledSqlDmlWorkflowService.WorkflowException exception = assertThrows(
        ControlledSqlDmlWorkflowService.WorkflowException.class,
        () -> unavailable.execute(request(BINDING_HASH)));

    assertEquals("SQL_DML_TRANSACTIONAL_AUDIT_REQUIRED", exception.code());
    assertEquals(0, gateway.requests.size());
  }

  private ControlledSqlDmlWorkflow persisted() {
    return store.findByIdempotency("dml-key-1", "operator-1", "sit").block();
  }

  private void assertHandoffResult(SqlQueryExecutionResult result, String workflowId) {
    assertEquals("UNKNOWN_REQUIRES_HANDOFF", result.status());
    assertEquals(workflowId, result.workflowId());
    assertEquals(workflowId + "-dml", result.executionRequestId());
    assertEquals("SQL_DML_RESULT_UNKNOWN", result.errorCode());
    assertNull(result.resultId());
    assertNull(result.errorMessage());
    assertNull(result.affectedRows());
  }

  private ControlledSqlDmlWorkflowService serviceWithFailingLookup(RuntimeException failure) {
    ControlledSqlDmlWorkflowStore failingStore = (ControlledSqlDmlWorkflowStore) Proxy.newProxyInstance(
        getClass().getClassLoader(),
        new Class<?>[] {ControlledSqlDmlWorkflowStore.class},
        (proxy, method, arguments) -> {
          if ("assertCompatible".equals(method.getName())) {
            return Mono.error(failure);
          }
          throw new AssertionError("Unexpected workflow-store call: " + method.getName());
        });
    return new ControlledSqlDmlWorkflowService(failingStore, gateway, request -> { }, CLOCK);
  }

  private ControlledSqlDmlWorkflowStore storeWithHandoffFailure() {
    return (ControlledSqlDmlWorkflowStore) Proxy.newProxyInstance(
        getClass().getClassLoader(),
        new Class<?>[] {ControlledSqlDmlWorkflowStore.class},
        (proxy, method, arguments) -> {
          if ("markHandoffRequired".equals(method.getName())) {
            return Mono.error(new IllegalStateException("audit transaction unavailable"));
          }
          return method.invoke(store, arguments);
        });
  }

  private ControlledSqlDmlWorkflow createRunningWorkflow(
      String workflowId,
      OffsetDateTime executionExpiresAt) {
    OffsetDateTime now = OffsetDateTime.now(CLOCK);
    ControlledSqlDmlWorkflow workflow = new ControlledSqlDmlWorkflow(
        workflowId,
        "dml-key-1",
        "operator-1",
        "sit",
        BINDING_HASH,
        "as400-sit",
        "OPS",
        SqlStatementType.UPDATE,
        SQL_HASH,
        PARAMETERS_HASH,
        PREFLIGHT_HASH,
        CONFIRMATION_HASH,
        "decision-1",
        "policy-v1",
        "trace-1",
        "request-1",
        ControlledSqlDmlWorkflow.Status.CREATED,
        0,
        null,
        null,
        null,
        now,
        now,
        null,
        null);
    store.create(workflow).block();
    OffsetDateTime submittedAt = executionExpiresAt.isAfter(now)
        ? now
        : executionExpiresAt.minusSeconds(1);
    store.markConfirmed(workflow.workflowId(), submittedAt).block();
    return store.markSubmitted(workflow.workflowId(), submittedAt, executionExpiresAt).block();
  }

  private ControlledSqlDmlWorkflow createCreatedWorkflow(String workflowId) {
    OffsetDateTime now = OffsetDateTime.now(CLOCK);
    ControlledSqlDmlWorkflow workflow = new ControlledSqlDmlWorkflow(
        workflowId,
        "dml-key-1",
        "operator-1",
        "sit",
        BINDING_HASH,
        "as400-sit",
        "OPS",
        SqlStatementType.UPDATE,
        SQL_HASH,
        PARAMETERS_HASH,
        PREFLIGHT_HASH,
        CONFIRMATION_HASH,
        "decision-1",
        "policy-v1",
        "trace-1",
        "request-1",
        ControlledSqlDmlWorkflow.Status.CREATED,
        0,
        null,
        null,
        null,
        now,
        now,
        null,
        null);
    return store.create(workflow).block();
  }

  private ControlledSqlDmlWorkflowRequest request(String bindingHash) {
    return request(bindingHash, "decision-1");
  }

  private ControlledSqlDmlWorkflowRequest request(String bindingHash, String decisionId) {
    SqlQueryRequest query = new SqlQueryRequest(
        "1.0",
        "as400-sit",
        "sit",
        "OPS",
        SqlQueryAction.COMMIT_DML,
        "update OPS.ACCOUNT set STATUS = 'READY' where ACCOUNT_ID = 42",
        List.of(),
        new SqlQueryLimits(500, 5_000_000, 30),
        "dml-key-1");
    SqlDmlConfirmation confirmation = new SqlDmlConfirmation(
        "1.0",
        "sha256:" + SQL_HASH,
        List.of("CONTROLLED_DML_CONFIRMED"),
        SqlDmlConfirmation.RISK_CONFIRMATION_CODE);
    return new ControlledSqlDmlWorkflowRequest(
        new SqlDmlCommitRequest("1.0", query, confirmation),
        new SqlDmlExecutionBinding(
            bindingHash,
            PARAMETERS_HASH,
            PREFLIGHT_HASH,
            CONFIRMATION_HASH),
        new SqlValidationReport(
            "1.0",
            SqlStatementType.UPDATE,
            SqlValidationLevel.VALIDATED,
            "sha256:" + SQL_HASH,
            List.of("OPS.ACCOUNT"),
            List.of(),
            List.of(),
            List.of()),
        new SqlDmlPreviewSelection("1.0", List.of(), List.of()),
        new OperatorContext("operator-1", List.of("ROLE_ops-admin")),
        new PolicyDecisionReference(decisionId, "policy-v1", "ALLOW"),
        new TraceContext("trace-1", "request-1"));
  }

  private static final class RecordingGateway implements ControlledSqlDmlWorkerGateway {

    private final List<SqlControlledDmlExecutionRequest> requests = new ArrayList<>();
    private boolean timeout;
    private boolean unknown;
    private String rejectionCode;

    @Override
    public SqlQueryExecutionResult execute(SqlControlledDmlExecutionRequest request) {
      requests.add(request);
      if (timeout) {
        throw new IllegalStateException("worker response timed out");
      }
      if (unknown) {
        return new SqlQueryExecutionResult(
            "1.0",
            request.executionRequestId(),
            request.workflowId(),
            "UNKNOWN_REQUIRES_HANDOFF",
            "internal-result-id",
            "SQL_DML_COMMIT_OUTCOME_UNKNOWN",
            "internal JDBC commit detail",
            99);
      }
      if (rejectionCode != null) {
        return new SqlQueryExecutionResult(
            "1.0",
            request.executionRequestId(),
            request.workflowId(),
            "REJECTED",
            null,
            rejectionCode,
            "Worker rejected controlled DML",
            null);
      }
      return new SqlQueryExecutionResult(
          "1.0",
          request.executionRequestId(),
          request.workflowId(),
          "SUCCEEDED",
          null,
          null,
          null,
          1);
    }
  }

  private static final class RecordingReceiptVerifier
      implements ControlledSqlDmlPreflightReceiptVerifier {

    private boolean expired;
    private boolean invalid;
    private int authenticityAndBindingChecks;
    private int dispatchChecks;

    @Override
    public void verifyAuthenticityAndBinding(ControlledSqlDmlWorkflowRequest request) {
      authenticityAndBindingChecks++;
      if (invalid) {
        throw new ControlledSqlDmlWorkflowService.WorkflowException(
            "SQL_DML_PREFLIGHT_RECEIPT_INVALID", "The preflight receipt is invalid");
      }
    }

    @Override
    public void verifyUsableForDispatch(ControlledSqlDmlWorkflowRequest request) {
      dispatchChecks++;
      if (expired) {
        throw new ControlledSqlDmlWorkflowService.WorkflowException(
            "SQL_DML_PREFLIGHT_RECEIPT_EXPIRED", "The preflight receipt has expired");
      }
    }
  }
}
