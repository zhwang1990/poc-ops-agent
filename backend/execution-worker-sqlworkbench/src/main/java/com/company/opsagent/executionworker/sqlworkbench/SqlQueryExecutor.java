package com.company.opsagent.executionworker.sqlworkbench;

import com.company.opsagent.contracts.sqlworkbench.SqlQueryExecutionRequest;

/**
 * 已通过 Worker 双重校验后的 Db2 for i 查询执行边界。
 */
@FunctionalInterface
public interface SqlQueryExecutor {

  String execute(SqlQueryExecutionRequest request);

  default int executeDml(SqlQueryExecutionRequest request) {
    throw new UnsupportedOperationException("SQL DML execution is not configured");
  }
}
