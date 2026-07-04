package com.company.opsagent.executionworker.sqlworkbench;

import com.company.opsagent.contracts.sqlworkbench.SqlQueryExecutionRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlConnectionSummary;
import javax.sql.DataSource;

/**
 * Worker 内 SQL 执行请求到受控 JDBC 数据源的解析边界。
 */
@FunctionalInterface
public interface SqlDataSourceRegistry {

  DataSource resolve(SqlQueryExecutionRequest request);

  default DataSource resolve(SqlConnectionSummary connection) {
    throw new UnsupportedOperationException("SQL connection metadata resolution is not supported");
  }
}
