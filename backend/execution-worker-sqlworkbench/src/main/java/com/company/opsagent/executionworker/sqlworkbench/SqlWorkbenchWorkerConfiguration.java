package com.company.opsagent.executionworker.sqlworkbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SQL 工作台 Worker 侧适配模块装配。
 */
@Configuration
@EnableConfigurationProperties({
    WorkerSqlEgressProperties.class,
    SqlWorkerTransportAuthProperties.class,
    WorkerSqlCredentialProperties.class
})
public class SqlWorkbenchWorkerConfiguration {

  /**
   * 构建 Worker 本地 SQL 出口策略，默认空配置即拒绝所有 SQL 目标。
   */
  @Bean
  WorkerSqlEgressPolicy workerSqlEgressPolicy(WorkerSqlEgressProperties properties) {
    return properties.toPolicy();
  }

  /**
   * 构建 Worker 本地 DML 功能与写凭据门禁。
   */
  @Bean
  WorkerSqlDmlExecutionPolicy workerSqlDmlExecutionPolicy(WorkerSqlEgressProperties properties) {
    return new WorkerSqlDmlExecutionPolicy(
        properties.getConnections().stream()
            .map(WorkerSqlEgressProperties.Connection::toDescriptor)
            .toList());
  }

  /**
   * SQL 结果短期存储，P1 仅用于只读诊断结果暂存。
   */
  @Bean
  SqlResultStore sqlResultStore(Clock workerClock) {
    return new InMemorySqlResultStore(workerClock);
  }

  /**
   * SQL Worker HTTP 边界传输认证器。
   */
  @Bean
  SqlWorkerTransportAuthenticator sqlWorkerTransportAuthenticator(
      SqlWorkerTransportAuthProperties properties,
      Clock workerClock) {
    return new SqlWorkerTransportAuthenticator(properties, workerClock);
  }

  /**
   * SQL 凭据解析器。真实 KeyStore 接入前默认失败关闭，不读取环境中的明文密码。
   */
  @Bean
  SqlPasswordProvider sqlPasswordProvider(WorkerSqlCredentialProperties properties) {
    if (properties.isComplete()) {
      return new JavaKeyStorePasswordProvider(
          properties.getKeyStorePath(),
          properties.getStorePassword().toCharArray());
    }
    if (properties.isConfigured()) {
      throw new IllegalStateException("SQL credential KeyStore path and store password must be configured together");
    }
    return credentialAlias -> {
      throw new IllegalStateException("SQL credential KeyStore is not configured");
    };
  }

  /**
   * 构建连接探测 Worker。
   */
  @Bean
  SqlConnectionProbeWorker sqlConnectionProbeWorker(
      WorkerSqlEgressPolicy workerSqlEgressPolicy,
      SqlPasswordProvider sqlPasswordProvider,
      Clock workerClock) {
    return new SqlConnectionProbeWorker(workerSqlEgressPolicy, sqlPasswordProvider, workerClock);
  }

  /**
   * SQL 数据源解析边界，真实连接配置接入前仍先强制执行出口 allowlist。
   */
  @Bean
  ConfiguredSqlDataSourceRegistry sqlDataSourceRegistry(
      WorkerSqlEgressPolicy workerSqlEgressPolicy,
      SqlPasswordProvider sqlPasswordProvider) {
    return new ConfiguredSqlDataSourceRegistry(
        workerSqlEgressPolicy,
        sqlPasswordProvider,
        new Jt400DataSourceFactory(),
        new H2SqlDataSourceFactory());
  }

  /**
   * 构建阻塞 JDBC 只读查询执行器。
   */
  @Bean
  SqlQueryExecutor sqlQueryExecutor(
      SqlDataSourceRegistry sqlDataSourceRegistry,
      SqlResultStore sqlResultStore,
      ObjectMapper objectMapper,
      Clock workerClock) {
    return new JdbcSqlQueryExecutor(sqlDataSourceRegistry, sqlResultStore, objectMapper, workerClock);
  }

  /**
   * 构建只读 DML 影响预览执行器。
   */
  @Bean
  SqlDmlImpactPreviewExecutor sqlDmlImpactPreviewExecutor(
      SqlDataSourceRegistry sqlDataSourceRegistry,
      ObjectMapper objectMapper) {
    return new JdbcSqlDmlImpactPreviewExecutor(sqlDataSourceRegistry, objectMapper);
  }

  @Bean
  SqlMetadataReader sqlMetadataReader(
      SqlDataSourceRegistry sqlDataSourceRegistry,
      Clock workerClock) {
    return new JdbcSqlMetadataReader(sqlDataSourceRegistry, workerClock);
  }

  /**
   * 构建 SQL 查询专用受限 Worker。
   */
  @Bean
  RestrictedSqlQueryExecutionWorker restrictedSqlQueryExecutionWorker(
      Clock workerClock,
      SqlQueryExecutor sqlQueryExecutor,
      SqlDmlImpactPreviewExecutor sqlDmlImpactPreviewExecutor,
      WorkerSqlDmlExecutionPolicy workerSqlDmlExecutionPolicy) {
    return new RestrictedSqlQueryExecutionWorker(
        new CalciteSqlReadOnlyGuard(),
        new CalciteSqlDmlGuard(),
        sqlQueryExecutor,
        sqlDmlImpactPreviewExecutor,
        workerSqlDmlExecutionPolicy,
        workerClock);
  }
}
