package com.company.opsagent.executionworker.sqlworkbench;

/** 在目标数据库访问前原子消费受控 DML 执行请求标识。 */
@FunctionalInterface
public interface SqlDmlExecutionReplayGuard {

  boolean consume(String executionRequestId);

  static SqlDmlExecutionReplayGuard unavailable() {
    return executionRequestId -> {
      throw new SqlDmlReplayStateException("SQL DML replay state is not configured");
    };
  }
}
