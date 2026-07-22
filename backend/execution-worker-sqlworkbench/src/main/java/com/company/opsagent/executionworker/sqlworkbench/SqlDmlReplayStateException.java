package com.company.opsagent.executionworker.sqlworkbench;

/** 表示 Worker 无法可靠建立或持久化受控 DML 防重放状态。 */
public final class SqlDmlReplayStateException extends RuntimeException {

  public SqlDmlReplayStateException(String message) {
    super(message);
  }

  public SqlDmlReplayStateException(String message, Throwable cause) {
    super(message, cause);
  }
}
