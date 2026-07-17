package com.company.opsagent.controlplane.modules.workflow;

import com.company.opsagent.contracts.sqlworkbench.SqlControlledDmlExecutionRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryExecutionResult;
import com.company.opsagent.contracts.sqlworkbench.SqlStatementType;
import com.company.opsagent.contracts.sqlworkbench.SqlTargetEnvironments;
import com.company.opsagent.contracts.sqlworkbench.SqlValidationLevel;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** 受控 SQL DML 的持久化、幂等和单次 Worker 提交编排。 */
public final class ControlledSqlDmlWorkflowService {

  private static final Pattern SHA_256_HEX = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern SAFE_FAILURE_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,127}");
  private static final String RESULT_UNKNOWN = "SQL_DML_RESULT_UNKNOWN";

  private final ControlledSqlDmlWorkflowStore store;
  private final ControlledSqlDmlWorkerGateway workerGateway;
  private final ControlledSqlDmlPreflightReceiptVerifier receiptVerifier;
  private final Clock clock;

  public ControlledSqlDmlWorkflowService(
      ControlledSqlDmlWorkflowStore store,
      ControlledSqlDmlWorkerGateway workerGateway,
      Clock clock) {
    this(
        store,
        workerGateway,
        request -> {
          throw new WorkflowException(
              "SQL_DML_PREFLIGHT_RECEIPT_REQUIRED",
              "A server-issued preflight receipt is required");
        },
        clock);
  }

  public ControlledSqlDmlWorkflowService(
      ControlledSqlDmlWorkflowStore store,
      ControlledSqlDmlWorkerGateway workerGateway,
      ControlledSqlDmlPreflightReceiptVerifier receiptVerifier,
      Clock clock) {
    this.store = Objects.requireNonNull(store, "store");
    this.workerGateway = Objects.requireNonNull(workerGateway, "workerGateway");
    this.receiptVerifier = Objects.requireNonNull(receiptVerifier, "receiptVerifier");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public SqlQueryExecutionResult execute(ControlledSqlDmlWorkflowRequest request) {
    Objects.requireNonNull(request, "request");
    validateTrustedRequest(request);
    RuntimeException receiptFailure = authenticityAndBindingFailure(request);
    ControlledSqlDmlWorkflow existing;
    try {
      existing = findCompatible(request);
    } catch (RuntimeException exception) {
      if (receiptFailure != null) {
        throw receiptFailure;
      }
      throw exception;
    }
    if (receiptFailure != null) {
      if (existing != null && existing.status() == ControlledSqlDmlWorkflow.Status.CREATED) {
        return handoffResult(existing.workflowId());
      }
      throw receiptFailure;
    }
    if (existing != null) {
      return reuse(existing, request);
    }

    receiptVerifier.verifyUsableForDispatch(request);
    OffsetDateTime now = OffsetDateTime.now(clock);
    String workflowId = UUID.randomUUID().toString();
    ControlledSqlDmlWorkflow created = create(request, workflowId, now);
    if (!workflowId.equals(created.workflowId())) {
      return reuse(created, request);
    }

    return dispatchCreatedWorkflow(request, created);
  }

  private RuntimeException authenticityAndBindingFailure(
      ControlledSqlDmlWorkflowRequest request) {
    try {
      receiptVerifier.verifyAuthenticityAndBinding(request);
      return null;
    } catch (RuntimeException exception) {
      return exception;
    }
  }

  private SqlQueryExecutionResult dispatchCreatedWorkflow(
      ControlledSqlDmlWorkflowRequest request,
      ControlledSqlDmlWorkflow workflow) {
    try {
      receiptVerifier.verifyUsableForDispatch(request);
    } catch (RuntimeException exception) {
      return handoffResult(workflow.workflowId());
    }

    OffsetDateTime submittedAt = OffsetDateTime.now(clock);
    OffsetDateTime executionExpiresAt = submittedAt
        .plusSeconds(request.commitRequest().query().limits().timeoutSeconds());
    try {
      if (workflow.confirmedAt() == null) {
        store.markConfirmed(workflow.workflowId(), submittedAt).block();
      }
      store.markSubmitted(
          workflow.workflowId(),
          submittedAt,
          executionExpiresAt).block();
    } catch (ControlledSqlDmlWorkflowStore.TransactionalAuditRequiredException exception) {
      throw workflowFailure(exception.code(), "Transactional audit is required for controlled DML");
    } catch (RuntimeException exception) {
      throw workflowFailure("SQL_DML_WORKFLOW_PERSISTENCE_FAILED", "Controlled DML workflow could not be persisted");
    }

    String workflowId = workflow.workflowId();
    String executionRequestId = executionRequestId(workflowId);
    SqlControlledDmlExecutionRequest workerRequest = request.toWorkerRequest(
        workflowId,
        executionRequestId,
        executionExpiresAt);
    SqlQueryExecutionResult result;
    try {
      result = workerGateway.execute(workerRequest);
    } catch (RuntimeException exception) {
      return handoffResult(workflowId);
    }
    return complete(workflowId, executionRequestId, result);
  }

  private ControlledSqlDmlWorkflow findCompatible(ControlledSqlDmlWorkflowRequest request) {
    try {
      return store.assertCompatible(
          request.commitRequest().query().idempotencyKey(),
          request.operator().operatorId(),
          request.commitRequest().query().targetEnvironment(),
          request.binding().bindingHash()).block();
    } catch (ControlledSqlDmlWorkflowStore.IdempotencyConflictException exception) {
      throw workflowFailure(exception.code(), exception.getMessage());
    } catch (ControlledSqlDmlWorkflowStore.TransactionalAuditRequiredException exception) {
      throw workflowFailure(exception.code(), "Transactional audit is required for controlled DML");
    } catch (RuntimeException exception) {
      throw workflowFailure(
          "SQL_DML_WORKFLOW_PERSISTENCE_FAILED",
          "Controlled DML workflow state could not be read");
    }
  }

  private ControlledSqlDmlWorkflow create(
      ControlledSqlDmlWorkflowRequest request,
      String workflowId,
      OffsetDateTime now) {
    var query = request.commitRequest().query();
    var workflow = new ControlledSqlDmlWorkflow(
        workflowId,
        query.idempotencyKey(),
        request.operator().operatorId(),
        query.targetEnvironment(),
        request.binding().bindingHash(),
        query.connectionId(),
        query.schema(),
        request.validation().statementType(),
        request.sqlHash(),
        request.binding().parametersHash(),
        request.binding().preflightHash(),
        request.binding().confirmationHash(),
        request.policyDecision().decisionId(),
        request.policyDecision().policyVersion(),
        request.trace().traceId(),
        request.trace().requestId(),
        ControlledSqlDmlWorkflow.Status.CREATED,
        0,
        null,
        null,
        null,
        now,
        now,
        null,
        null);
    try {
      return store.create(workflow).block();
    } catch (ControlledSqlDmlWorkflowStore.IdempotencyConflictException exception) {
      throw workflowFailure(exception.code(), exception.getMessage());
    } catch (ControlledSqlDmlWorkflowStore.TransactionalAuditRequiredException exception) {
      throw workflowFailure(exception.code(), "Transactional audit is required for controlled DML");
    } catch (RuntimeException exception) {
      throw workflowFailure("SQL_DML_WORKFLOW_PERSISTENCE_FAILED", "Controlled DML workflow could not be created");
    }
  }

  private SqlQueryExecutionResult complete(
      String workflowId,
      String executionRequestId,
      SqlQueryExecutionResult result) {
    if (result == null
        || !executionRequestId.equals(result.executionRequestId())
        || !workflowId.equals(result.workflowId())) {
      return handoffResult(workflowId);
    }
    OffsetDateTime completedAt = OffsetDateTime.now(clock);
    if ("SUCCEEDED".equals(result.status())
        && result.affectedRows() != null
        && result.affectedRows() >= 0) {
      try {
        store.markSucceeded(workflowId, result.affectedRows(), completedAt).block();
      } catch (RuntimeException exception) {
        return handoffResult(workflowId);
      }
      return successfulResult(workflowId, result.affectedRows());
    }
    if ("FAILED".equals(result.status()) || "REJECTED".equals(result.status())) {
      String failureCode = safeFailureCode(result.errorCode());
      try {
        store.markFailed(workflowId, failureCode, completedAt).block();
      } catch (RuntimeException exception) {
        return handoffResult(workflowId);
      }
      return failedResult(workflowId, failureCode);
    }
    return handoffResult(workflowId);
  }

  private SqlQueryExecutionResult reuse(
      ControlledSqlDmlWorkflow workflow,
      ControlledSqlDmlWorkflowRequest request) {
    return switch (workflow.status()) {
      case SUCCEEDED -> successfulResult(workflow.workflowId(), workflow.affectedRowCount());
      case FAILED -> failedResult(workflow.workflowId(), safeFailureCode(workflow.failureCode()));
      case UNKNOWN_REQUIRES_HANDOFF -> unknownResult(workflow.workflowId());
      case RUNNING -> reuseRunningWorkflow(workflow);
      case CREATED -> dispatchCreatedWorkflow(request, workflow);
    };
  }

  private SqlQueryExecutionResult successfulResult(String workflowId, Integer affectedRows) {
    if (affectedRows == null || affectedRows < 0) {
      throw workflowFailure(RESULT_UNKNOWN, "DML result requires human handoff");
    }
    return new SqlQueryExecutionResult(
        "1.0", executionRequestId(workflowId), workflowId, "SUCCEEDED",
        null, null, null, affectedRows);
  }

  private SqlQueryExecutionResult failedResult(String workflowId, String failureCode) {
    return new SqlQueryExecutionResult(
        "1.0", executionRequestId(workflowId), workflowId, "FAILED",
        null, failureCode, "Controlled DML worker rejected the request", null);
  }

  private SqlQueryExecutionResult handoffResult(String workflowId) {
    markUnknownOrFail(workflowId);
    return unknownResult(workflowId);
  }

  private SqlQueryExecutionResult unknownResult(String workflowId) {
    return new SqlQueryExecutionResult(
        "1.0",
        executionRequestId(workflowId),
        workflowId,
        "UNKNOWN_REQUIRES_HANDOFF",
        null,
        RESULT_UNKNOWN,
        null,
        null);
  }

  private SqlQueryExecutionResult reuseRunningWorkflow(ControlledSqlDmlWorkflow workflow) {
    OffsetDateTime executionExpiresAt = workflow.executionExpiresAt();
    if (executionExpiresAt == null || !executionExpiresAt.isAfter(OffsetDateTime.now(clock))) {
      return handoffResult(workflow.workflowId());
    }
    throw workflowFailure(
        "SQL_DML_WORKFLOW_IN_PROGRESS", "Controlled DML workflow is already in progress");
  }

  private void markUnknownOrFail(String workflowId) {
    try {
      store.markHandoffRequired(
          workflowId, RESULT_UNKNOWN, OffsetDateTime.now(clock)).block();
    } catch (RuntimeException exception) {
      throw workflowFailure(
          "SQL_DML_HANDOFF_PERSISTENCE_FAILED",
          "Controlled DML handoff could not be persisted");
    }
  }

  private void validateTrustedRequest(ControlledSqlDmlWorkflowRequest request) {
    var query = request.commitRequest().query();
    if (!SqlTargetEnvironments.allowsCrud(query.targetEnvironment())) {
      throw workflowFailure("SQL_DML_ENVIRONMENT_NOT_ALLOWED", "Controlled DML is not allowed in this environment");
    }
    if (!"ALLOW".equals(request.policyDecision().decision())) {
      throw workflowFailure("SQL_DML_POLICY_DENIED", "Controlled DML policy did not allow execution");
    }
    if (request.commitRequest().confirmation() == null
        || !request.validation().sqlHash().equals(request.commitRequest().confirmation().sqlHash())) {
      throw workflowFailure("SQL_DML_CONFIRMATION_INVALID", "Controlled DML confirmation is invalid");
    }
    if (request.validation().validationLevel() == SqlValidationLevel.REJECTED
        || !isDml(request.validation().statementType())) {
      throw workflowFailure("SQL_DML_STATIC_ANALYSIS_REJECTED", "Controlled DML validation is invalid");
    }
    requireHash(request.sqlHash(), "sqlHash");
    requireHash(request.binding().bindingHash(), "bindingHash");
    requireHash(request.binding().parametersHash(), "parametersHash");
    requireHash(request.binding().preflightHash(), "preflightHash");
    requireHash(request.binding().confirmationHash(), "confirmationHash");
  }

  private boolean isDml(SqlStatementType type) {
    return type == SqlStatementType.INSERT
        || type == SqlStatementType.UPDATE
        || type == SqlStatementType.DELETE;
  }

  private void requireHash(String value, String fieldName) {
    if (value == null || !SHA_256_HEX.matcher(value).matches()) {
      throw workflowFailure("SQL_DML_BINDING_INVALID", fieldName + " is invalid");
    }
  }

  private String safeFailureCode(String value) {
    return value != null && SAFE_FAILURE_CODE.matcher(value).matches()
        ? value
        : "SQL_DML_WORKER_FAILED";
  }

  private String executionRequestId(String workflowId) {
    return workflowId + "-dml";
  }

  private WorkflowException workflowFailure(String code, String message) {
    return new WorkflowException(code, message);
  }

  /** M05 对上游暴露的稳定工作流错误，不依赖 M09 类型。 */
  public static final class WorkflowException extends RuntimeException {

    private final String code;

    public WorkflowException(String code, String message) {
      super(message);
      if (code == null || code.isBlank()) {
        throw new IllegalArgumentException("code must not be blank");
      }
      this.code = code;
    }

    public String code() {
      return code;
    }
  }
}
