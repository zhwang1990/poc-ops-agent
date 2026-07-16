package com.company.opsagent.controlplane.modules.workflow;

import com.company.opsagent.contracts.sqlworkbench.SqlStatementType;
import java.time.OffsetDateTime;

/**
 * 受控 SQL DML 工作流的持久化事实。
 *
 * <p>该模型只携带获准的目标元数据、哈希、策略引用和安全结果，不携带 SQL、参数、凭据或样例值。
 */
public record ControlledSqlDmlWorkflow(
    String workflowId,
    String idempotencyKey,
    String operatorId,
    String targetEnvironment,
    String bindingHash,
    String connectionId,
    String schemaName,
    SqlStatementType statementType,
    String sqlHash,
    String parametersHash,
    String preflightHash,
    String confirmationHash,
    String policyDecisionId,
    String policyVersion,
    String traceId,
    String requestId,
    Status status,
    int attemptCount,
    Integer affectedRowCount,
    String failureCode,
    OffsetDateTime confirmedAt,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    OffsetDateTime executionExpiresAt,
    OffsetDateTime completedAt) {

  public enum Status {
    CREATED(false),
    RUNNING(false),
    SUCCEEDED(true),
    FAILED(true),
    UNKNOWN_REQUIRES_HANDOFF(true);

    private final boolean terminal;

    Status(boolean terminal) {
      this.terminal = terminal;
    }

    public boolean isTerminal() {
      return terminal;
    }

    /**
     * 受控 DML 具有副作用，任何状态都不得进入只读工作流重放队列。
     */
    public boolean isReplayable() {
      return false;
    }
  }
}
