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

  Mono<ControlledSqlDmlWorkflow> markSubmitted(String workflowId, OffsetDateTime submittedAt);

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
}
