package com.company.opsagent.executionworker.sqlworkbench;

/**
 * Worker-local boundary for controlled SQL DML statements.
 */
public interface SqlDmlGuard {

  boolean isControlledDml(String sql);
}
