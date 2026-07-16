package com.company.opsagent.controlplane.modules.workflow;

import com.company.opsagent.contracts.sqlworkbench.SqlStatementType;
import com.company.opsagent.controlplane.modules.audit.AuditEvent;
import com.company.opsagent.controlplane.modules.audit.AuditTrail;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/** 基于 R2DBC 的受控 SQL DML 工作流事实源。 */
public final class R2dbcControlledSqlDmlWorkflowStore implements ControlledSqlDmlWorkflowStore {

  private static final String ACTION_CREATED = "SQL_DML_CREATED";
  private static final String ACTION_CONFIRMED = "SQL_DML_CONFIRMED";
  private static final String ACTION_SUBMITTED = "SQL_DML_SUBMITTED";
  private static final String ACTION_SUCCEEDED = "SQL_DML_SUCCEEDED";
  private static final String ACTION_FAILED = "SQL_DML_FAILED";
  private static final String ACTION_HANDOFF_REQUIRED = "SQL_DML_HANDOFF_REQUIRED";
  private static final Pattern SHA_256_HEX = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern SAFE_FAILURE_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,127}");

  private final DatabaseClient databaseClient;
  private final AuditTrail auditTrail;
  private final TransactionalOperator transactions;

  public R2dbcControlledSqlDmlWorkflowStore(
      DatabaseClient databaseClient,
      AuditTrail auditTrail) {
    this.databaseClient = Objects.requireNonNull(databaseClient, "databaseClient");
    this.auditTrail = Objects.requireNonNull(auditTrail, "auditTrail");
    this.transactions = TransactionalOperator.create(
        new R2dbcTransactionManager(databaseClient.getConnectionFactory()));
  }

  @Override
  public Mono<ControlledSqlDmlWorkflow> create(ControlledSqlDmlWorkflow workflow) {
    if (!hasValidPersistedHashes(workflow)) {
      return Mono.error(new IllegalArgumentException(
          "controlled SQL DML persisted hash is invalid"));
    }
    if (workflow.status() != ControlledSqlDmlWorkflow.Status.CREATED
        || workflow.attemptCount() != 0
        || workflow.affectedRowCount() != null
        || workflow.failureCode() != null
        || workflow.confirmedAt() != null
        || workflow.completedAt() != null) {
      return Mono.error(new IllegalArgumentException(
          "controlled SQL DML workflow has invalid initial state"));
    }
    Mono<ControlledSqlDmlWorkflow> existing = assertCompatible(
        workflow.idempotencyKey(),
        workflow.operatorId(),
        workflow.targetEnvironment(),
        workflow.bindingHash());
    Mono<ControlledSqlDmlWorkflow> create = transactions.transactional(insert(workflow))
        .onErrorResume(DataIntegrityViolationException.class, error ->
            assertCompatible(
                    workflow.idempotencyKey(),
                    workflow.operatorId(),
                    workflow.targetEnvironment(),
                    workflow.bindingHash())
                .switchIfEmpty(Mono.error(error)));
    return existing.switchIfEmpty(Mono.defer(() -> create));
  }

  @Override
  public Mono<ControlledSqlDmlWorkflow> findByIdempotency(
      String idempotencyKey,
      String operatorId,
      String targetEnvironment) {
    return databaseClient.sql("""
            select *
            from controlled_sql_dml_workflow
            where idempotency_key = :idempotencyKey
              and operator_id = :operatorId
              and target_environment = :targetEnvironment
            """)
        .bind("idempotencyKey", idempotencyKey)
        .bind("operatorId", operatorId)
        .bind("targetEnvironment", targetEnvironment)
        .map((row, metadata) -> mapWorkflow(row))
        .one();
  }

  @Override
  public Mono<ControlledSqlDmlWorkflow> assertCompatible(
      String idempotencyKey,
      String operatorId,
      String targetEnvironment,
      String bindingHash) {
    if (!isSha256Hex(bindingHash)) {
      return Mono.error(new IllegalArgumentException(
          "controlled SQL DML binding hash is invalid"));
    }
    return findByIdempotency(idempotencyKey, operatorId, targetEnvironment)
        .flatMap(existing -> Objects.equals(existing.bindingHash(), bindingHash)
            ? Mono.just(existing)
            : Mono.error(new IdempotencyConflictException()));
  }

  @Override
  public Mono<ControlledSqlDmlWorkflow> markConfirmed(
      String workflowId,
      OffsetDateTime confirmedAt) {
    return transactions.transactional(databaseClient.sql("""
            update controlled_sql_dml_workflow
            set confirmed_at = :confirmedAt,
                updated_at = :updatedAt
            where workflow_id = :workflowId
              and status = :status
              and confirmed_at is null
            """)
        .bind("confirmedAt", confirmedAt)
        .bind("updatedAt", confirmedAt)
        .bind("workflowId", workflowId)
        .bind("status", ControlledSqlDmlWorkflow.Status.CREATED.name())
        .fetch()
        .rowsUpdated()
        .flatMap(rows -> requireUpdated(rows, workflowId, "confirm"))
        .flatMap(workflow -> recordAudit(
            workflow,
            ACTION_CONFIRMED,
            "CONFIRMED",
            "status=" + workflow.status().name(),
            confirmedAt)));
  }

  @Override
  public Mono<ControlledSqlDmlWorkflow> markSubmitted(
      String workflowId,
      OffsetDateTime submittedAt) {
    return transactions.transactional(databaseClient.sql("""
            update controlled_sql_dml_workflow
            set status = :runningStatus,
                attempt_count = attempt_count + 1,
                updated_at = :updatedAt
            where workflow_id = :workflowId
              and status = :createdStatus
              and confirmed_at is not null
            """)
        .bind("runningStatus", ControlledSqlDmlWorkflow.Status.RUNNING.name())
        .bind("updatedAt", submittedAt)
        .bind("workflowId", workflowId)
        .bind("createdStatus", ControlledSqlDmlWorkflow.Status.CREATED.name())
        .fetch()
        .rowsUpdated()
        .flatMap(rows -> requireUpdated(rows, workflowId, "submit"))
        .flatMap(workflow -> recordAudit(
            workflow,
            ACTION_SUBMITTED,
            ControlledSqlDmlWorkflow.Status.RUNNING.name(),
            "status=" + workflow.status().name()
                + ";attemptCount=" + workflow.attemptCount(),
            submittedAt)));
  }

  @Override
  public Mono<ControlledSqlDmlWorkflow> markSucceeded(
      String workflowId,
      int affectedRowCount,
      OffsetDateTime completedAt) {
    return complete(
        workflowId,
        ControlledSqlDmlWorkflow.Status.SUCCEEDED,
        affectedRowCount,
        null,
        completedAt,
        ACTION_SUCCEEDED,
        "affectedRowCount=" + affectedRowCount);
  }

  @Override
  public Mono<ControlledSqlDmlWorkflow> markFailed(
      String workflowId,
      String failureCode,
      OffsetDateTime completedAt) {
    if (!isSafeFailureCode(failureCode)) {
      return Mono.error(new IllegalArgumentException("controlled SQL DML failure code is invalid"));
    }
    return complete(
        workflowId,
        ControlledSqlDmlWorkflow.Status.FAILED,
        null,
        failureCode,
        completedAt,
        ACTION_FAILED,
        "failureCode=" + failureCode);
  }

  @Override
  public Mono<ControlledSqlDmlWorkflow> markHandoffRequired(
      String workflowId,
      String failureCode,
      OffsetDateTime completedAt) {
    if (!isSafeFailureCode(failureCode)) {
      return Mono.error(new IllegalArgumentException("controlled SQL DML failure code is invalid"));
    }
    return complete(
        workflowId,
        ControlledSqlDmlWorkflow.Status.UNKNOWN_REQUIRES_HANDOFF,
        null,
        failureCode,
        completedAt,
        ACTION_HANDOFF_REQUIRED,
        "failureCode=" + failureCode);
  }

  private Mono<ControlledSqlDmlWorkflow> insert(ControlledSqlDmlWorkflow workflow) {
    DatabaseClient.GenericExecuteSpec spec = databaseClient.sql("""
            insert into controlled_sql_dml_workflow (
              workflow_id,
              idempotency_key,
              operator_id,
              target_environment,
              binding_hash,
              connection_id,
              schema_name,
              statement_type,
              sql_hash,
              parameters_hash,
              preflight_hash,
              confirmation_hash,
              policy_decision_id,
              policy_version,
              trace_id,
              request_id,
              status,
              attempt_count,
              affected_row_count,
              failure_code,
              confirmed_at,
              created_at,
              updated_at,
              completed_at
            ) values (
              :workflowId,
              :idempotencyKey,
              :operatorId,
              :targetEnvironment,
              :bindingHash,
              :connectionId,
              :schemaName,
              :statementType,
              :sqlHash,
              :parametersHash,
              :preflightHash,
              :confirmationHash,
              :policyDecisionId,
              :policyVersion,
              :traceId,
              :requestId,
              :status,
              :attemptCount,
              :affectedRowCount,
              :failureCode,
              :confirmedAt,
              :createdAt,
              :updatedAt,
              :completedAt
            )
            """)
        .bind("workflowId", workflow.workflowId())
        .bind("idempotencyKey", workflow.idempotencyKey())
        .bind("operatorId", workflow.operatorId())
        .bind("targetEnvironment", workflow.targetEnvironment())
        .bind("bindingHash", workflow.bindingHash())
        .bind("connectionId", workflow.connectionId())
        .bind("schemaName", workflow.schemaName())
        .bind("statementType", workflow.statementType().name())
        .bind("sqlHash", workflow.sqlHash())
        .bind("parametersHash", workflow.parametersHash())
        .bind("preflightHash", workflow.preflightHash())
        .bind("confirmationHash", workflow.confirmationHash())
        .bind("policyDecisionId", workflow.policyDecisionId())
        .bind("policyVersion", workflow.policyVersion())
        .bind("traceId", workflow.traceId())
        .bind("requestId", workflow.requestId())
        .bind("status", workflow.status().name())
        .bind("attemptCount", workflow.attemptCount())
        .bind("createdAt", workflow.createdAt())
        .bind("updatedAt", workflow.updatedAt());
    spec = bindNullable(spec, "affectedRowCount", workflow.affectedRowCount(), Integer.class);
    spec = bindNullable(spec, "failureCode", workflow.failureCode(), String.class);
    spec = bindNullable(spec, "confirmedAt", workflow.confirmedAt(), OffsetDateTime.class);
    spec = bindNullable(spec, "completedAt", workflow.completedAt(), OffsetDateTime.class);
    return spec.fetch()
        .rowsUpdated()
        .then(findByWorkflowId(workflow.workflowId()))
        .flatMap(created -> recordAudit(
            created,
            ACTION_CREATED,
            ControlledSqlDmlWorkflow.Status.CREATED.name(),
            "status=" + created.status().name(),
            created.createdAt()));
  }

  private Mono<ControlledSqlDmlWorkflow> complete(
      String workflowId,
      ControlledSqlDmlWorkflow.Status status,
      Integer affectedRowCount,
      String failureCode,
      OffsetDateTime completedAt,
      String action,
      String safeResultFact) {
    DatabaseClient.GenericExecuteSpec spec = databaseClient.sql("""
            update controlled_sql_dml_workflow
            set status = :status,
                affected_row_count = :affectedRowCount,
                failure_code = :failureCode,
                updated_at = :updatedAt,
                completed_at = :completedAt
            where workflow_id = :workflowId and status = :runningStatus
            """)
        .bind("status", status.name())
        .bind("updatedAt", completedAt)
        .bind("completedAt", completedAt)
        .bind("workflowId", workflowId)
        .bind("runningStatus", ControlledSqlDmlWorkflow.Status.RUNNING.name());
    spec = bindNullable(spec, "affectedRowCount", affectedRowCount, Integer.class);
    spec = bindNullable(spec, "failureCode", failureCode, String.class);
    return transactions.transactional(spec.fetch()
        .rowsUpdated()
        .flatMap(rows -> requireUpdated(rows, workflowId, "complete as " + status.name()))
        .flatMap(workflow -> recordAudit(
            workflow,
            action,
            status.name(),
            "status=" + workflow.status().name() + ";" + safeResultFact,
            completedAt)));
  }

  private Mono<ControlledSqlDmlWorkflow> requireUpdated(
      long rowsUpdated,
      String workflowId,
      String transition) {
    if (rowsUpdated == 1) {
      return findByWorkflowId(workflowId);
    }
    return Mono.error(new IllegalStateException(
        "controlled SQL DML workflow cannot " + transition + " from its current state"));
  }

  private Mono<ControlledSqlDmlWorkflow> findByWorkflowId(String workflowId) {
    return databaseClient.sql("""
            select *
            from controlled_sql_dml_workflow
            where workflow_id = :workflowId
            """)
        .bind("workflowId", workflowId)
        .map((row, metadata) -> mapWorkflow(row))
        .one();
  }

  private ControlledSqlDmlWorkflow mapWorkflow(io.r2dbc.spi.Row row) {
    return new ControlledSqlDmlWorkflow(
        row.get("workflow_id", String.class),
        row.get("idempotency_key", String.class),
        row.get("operator_id", String.class),
        row.get("target_environment", String.class),
        row.get("binding_hash", String.class),
        row.get("connection_id", String.class),
        row.get("schema_name", String.class),
        SqlStatementType.valueOf(row.get("statement_type", String.class)),
        row.get("sql_hash", String.class),
        row.get("parameters_hash", String.class),
        row.get("preflight_hash", String.class),
        row.get("confirmation_hash", String.class),
        row.get("policy_decision_id", String.class),
        row.get("policy_version", String.class),
        row.get("trace_id", String.class),
        row.get("request_id", String.class),
        ControlledSqlDmlWorkflow.Status.valueOf(row.get("status", String.class)),
        valueOrZero(row.get("attempt_count", Integer.class)),
        row.get("affected_row_count", Integer.class),
        row.get("failure_code", String.class),
        row.get("confirmed_at", OffsetDateTime.class),
        row.get("created_at", OffsetDateTime.class),
        row.get("updated_at", OffsetDateTime.class),
        row.get("completed_at", OffsetDateTime.class));
  }

  private Mono<ControlledSqlDmlWorkflow> recordAudit(
      ControlledSqlDmlWorkflow workflow,
      String action,
      String result,
      String reason,
      OffsetDateTime timestamp) {
    AuditEvent event = new AuditEvent(
        UUID.randomUUID().toString(),
        workflow.requestId(),
        workflow.traceId(),
        workflow.operatorId(),
        action,
        "sql-workbench:" + workflow.connectionId()
            + ":" + workflow.schemaName()
            + ":" + workflow.statementType().name(),
        workflow.policyVersion(),
        result,
        reason,
        timestamp);
    return auditTrail.recordReactive(event).thenReturn(workflow);
  }

  private DatabaseClient.GenericExecuteSpec bindNullable(
      DatabaseClient.GenericExecuteSpec spec,
      String name,
      Object value,
      Class<?> type) {
    return value == null ? spec.bindNull(name, type) : spec.bind(name, value);
  }

  private int valueOrZero(Integer value) {
    return value == null ? 0 : value;
  }

  private boolean isSafeFailureCode(String failureCode) {
    return failureCode != null && SAFE_FAILURE_CODE.matcher(failureCode).matches();
  }

  private boolean hasValidPersistedHashes(ControlledSqlDmlWorkflow workflow) {
    return isSha256Hex(workflow.bindingHash())
        && isSha256Hex(workflow.sqlHash())
        && isSha256Hex(workflow.parametersHash())
        && isSha256Hex(workflow.preflightHash())
        && isSha256Hex(workflow.confirmationHash());
  }

  private boolean isSha256Hex(String value) {
    return value != null && SHA_256_HEX.matcher(value).matches();
  }
}
