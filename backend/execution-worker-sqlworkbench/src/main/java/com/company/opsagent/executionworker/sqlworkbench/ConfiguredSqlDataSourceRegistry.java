package com.company.opsagent.executionworker.sqlworkbench;

import com.company.opsagent.contracts.sqlworkbench.SqlQueryExecutionRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlConnectionSummary;
import java.util.Arrays;
import javax.sql.DataSource;

/**
 * Resolves configured SQL connections after the Worker-local egress policy passes.
 */
public final class ConfiguredSqlDataSourceRegistry implements SqlDataSourceRegistry {

  private final WorkerSqlEgressPolicy egressPolicy;
  private final SqlPasswordProvider passwordProvider;
  private final Jt400DataSourceFactory jt400DataSourceFactory;
  private final H2SqlDataSourceFactory h2DataSourceFactory;

  public ConfiguredSqlDataSourceRegistry(
      WorkerSqlEgressPolicy egressPolicy,
      SqlPasswordProvider passwordProvider,
      Jt400DataSourceFactory jt400DataSourceFactory,
      H2SqlDataSourceFactory h2DataSourceFactory) {
    this.egressPolicy = egressPolicy;
    this.passwordProvider = passwordProvider;
    this.jt400DataSourceFactory = jt400DataSourceFactory;
    this.h2DataSourceFactory = h2DataSourceFactory;
  }

  @Override
  public DataSource resolve(SqlQueryExecutionRequest request) {
    WorkerSqlConnectionDescriptor descriptor = egressPolicy.validate(request);
    return createDataSource(descriptor);
  }

  @Override
  public DataSource resolve(SqlConnectionSummary connection) {
    WorkerSqlConnectionDescriptor descriptor = egressPolicy.validate(connection);
    return createDataSource(descriptor);
  }

  private DataSource createDataSource(WorkerSqlConnectionDescriptor descriptor) {
    return switch (descriptor.platformType()) {
      case "H2" -> h2DataSourceFactory.create(descriptor);
      case "DB2_FOR_I" -> createJt400DataSource(descriptor);
      default -> throw new WorkerSqlEgressException(
          "SQL_PLATFORM_NOT_SUPPORTED",
          "SQL platform is not supported by this worker");
    };
  }

  private DataSource createJt400DataSource(WorkerSqlConnectionDescriptor descriptor) {
    char[] password = passwordProvider.password(descriptor.credentialAlias());
    try {
      return jt400DataSourceFactory.create(descriptor.host(), descriptor.username(), password);
    } finally {
      Arrays.fill(password, '\0');
    }
  }
}
