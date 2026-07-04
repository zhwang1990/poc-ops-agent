package com.company.opsagent.contracts.sqlworkbench;

/**
 * SQL 工作台允许提交的请求动作。
 */
public enum SqlQueryAction {
  VALIDATE,
  EXPLAIN,
  RUN_READ_ONLY,
  PREFLIGHT_DML,
  COMMIT_DML
}
