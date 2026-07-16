package com.company.opsagent.controlplane.modules.workflow;

import java.time.OffsetDateTime;
import reactor.core.publisher.Mono;

/** 受控 SQL DML 工作流事实源。 */
public interface ControlledSqlDmlWorkflowStore {

  Mono<ControlledSqlDmlWorkflow> create(ControlledSqlDmlWorkflow workflow);

  Mono<ControlledSqlDmlWorkflow> findByIdempotency(
      String idempotencyKey,
      String operatorId,
      String targetEnvironment);

  Mono<ControlledSqlDmlWorkflow> assertCompatible(
      String idempotencyKey,
      String operatorId,
      String targetEnvironment,
      String bindingHash);

  Mono<ControlledSqlDmlWorkflow> markConfirmed(String workflowId, OffsetDateTime confirmedAt);

  Mono<ControlledSqlDmlWorkflow> markSubmitted(
      String workflowId,
      OffsetDateTime submittedAt,
      OffsetDateTime executionExpiresAt);

  Mono<ControlledSqlDmlWorkflow> markSucceeded(
      String workflowId,
      int affectedRowCount,
      OffsetDateTime completedAt);

  Mono<ControlledSqlDmlWorkflow> markFailed(
      String workflowId,
      String failureCode,
      OffsetDateTime completedAt);

  Mono<ControlledSqlDmlWorkflow> markHandoffRequired(
      String workflowId,
      String failureCode,
      OffsetDateTime completedAt);

  /** 稳定的幂等绑定冲突错误。 */
  final class IdempotencyConflictException extends RuntimeException {

    public static final String CODE = "SQL_DML_IDEMPOTENCY_CONFLICT";

    public IdempotencyConflictException() {
      super("Idempotency binding does not match the persisted workflow");
    }

    public String code() {
      return CODE;
    }
  }

  /** 鍙楁帶 DML 缂哄皯鍚屼簨鍔″璁¤兘鍔涙椂鐨勭ǔ瀹氬畨鍏ㄩ敊璇€?*/
  final class TransactionalAuditRequiredException extends IllegalStateException {

    public static final String CODE = "SQL_DML_TRANSACTIONAL_AUDIT_REQUIRED";

    public TransactionalAuditRequiredException() {
      super(CODE);
    }

    public String code() {
      return CODE;
    }
  }
}
