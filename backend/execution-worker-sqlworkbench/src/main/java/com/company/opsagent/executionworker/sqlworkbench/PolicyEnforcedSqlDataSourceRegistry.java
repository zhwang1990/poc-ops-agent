package com.company.opsagent.executionworker.sqlworkbench;

import com.company.opsagent.contracts.sqlworkbench.SqlQueryExecutionRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlConnectionSummary;
import javax.sql.DataSource;

/**
 * 在返回 JDBC DataSource 前强制执行 Worker SQL 出口策略。
 */
public final class PolicyEnforcedSqlDataSourceRegistry implements SqlDataSourceRegistry {

  private final WorkerSqlEgressPolicy egressPolicy;
  private final SqlDataSourceRegistry delegate;

  public PolicyEnforcedSqlDataSourceRegistry(
      WorkerSqlEgressPolicy egressPolicy,
      SqlDataSourceRegistry delegate) {
    this.egressPolicy = egressPolicy;
    this.delegate = delegate;
  }

  @Override
  public DataSource resolve(SqlQueryExecutionRequest request) {
    egressPolicy.validate(request);
    return delegate.resolve(request);
  }

  @Override
  public DataSource resolve(SqlConnectionSummary connection) {
    egressPolicy.validate(connection);
    return delegate.resolve(connection);
  }
}
