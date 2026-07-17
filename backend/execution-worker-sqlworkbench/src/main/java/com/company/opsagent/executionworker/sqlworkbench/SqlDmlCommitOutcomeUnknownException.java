package com.company.opsagent.executionworker.sqlworkbench;

/** Indicates that JDBC commit was attempted but its durable outcome could not be established. */
public final class SqlDmlCommitOutcomeUnknownException extends IllegalStateException {

  public SqlDmlCommitOutcomeUnknownException(Throwable cause) {
    super("controlled JDBC DML commit outcome is unknown", cause);
  }
}
