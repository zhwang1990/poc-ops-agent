package com.company.opsagent.executionworker.sqlworkbench;

import com.company.opsagent.contracts.sqlworkbench.SqlControlledDmlExecutionRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlDmlPreflightExecutionRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryExecutionRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlConnectionSummary;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryAction;
import java.sql.SQLException;
import java.util.Arrays;
import javax.sql.DataSource;

/**
 * Resolves configured SQL connections after the Worker-local egress policy passes.
 */
public final class ConfiguredSqlDataSourceRegistry
    implements SqlDataSourceRegistry, SqlDmlWriteCapabilityValidator {

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
    if (request.query().action() == SqlQueryAction.COMMIT_DML) {
      return createWriteDataSource(descriptor);
    }
    return createDataSource(descriptor, descriptor.credentialAlias(), descriptor.username());
  }

  @Override
  public DataSource resolve(SqlConnectionSummary connection) {
    WorkerSqlConnectionDescriptor descriptor = egressPolicy.validate(connection);
    return createDataSource(descriptor, descriptor.credentialAlias(), descriptor.username());
  }

  @Override
  public void assertPreflightAllowed(SqlDmlPreflightExecutionRequest request) {
    validateWriteCapability(request.query().connectionId(), request.query().targetEnvironment());
  }

  @Override
  public void assertCommitAllowed(SqlControlledDmlExecutionRequest request) {
    validateWriteCapability(
        request.commitRequest().query().connectionId(),
        request.commitRequest().query().targetEnvironment());
  }

  private DataSource createWriteDataSource(WorkerSqlConnectionDescriptor descriptor) {
    if (!descriptor.dmlEnabled()
        || isBlank(descriptor.dmlCredentialAlias())
        || isBlank(descriptor.dmlUsername())) {
      throw new WorkerSqlEgressException(
          "SQL_DML_WORKER_DISABLED",
          "SQL DML is not enabled with a write credential for this worker connection");
    }
    char[] password = requiredWritePassword(descriptor.dmlCredentialAlias());
    try {
      return switch (descriptor.platformType()) {
        case "H2" -> h2DataSourceFactory.createWrite(descriptor, password);
        case "DB2_FOR_I" -> jt400DataSourceFactory.create(
            descriptor.host(),
            descriptor.dmlUsername(),
            password);
        default -> throw new WorkerSqlEgressException(
            "SQL_PLATFORM_NOT_SUPPORTED",
            "SQL platform is not supported by this worker");
      };
    } catch (WorkerSqlEgressException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw dmlDisabled();
    } finally {
      Arrays.fill(password, '\0');
    }
  }

  private DataSource createDataSource(
      WorkerSqlConnectionDescriptor descriptor,
      String credentialAlias,
      String username) {
    return switch (descriptor.platformType()) {
      case "H2" -> h2DataSourceFactory.create(descriptor);
      case "DB2_FOR_I" -> createJt400DataSource(descriptor, credentialAlias, username);
      default -> throw new WorkerSqlEgressException(
          "SQL_PLATFORM_NOT_SUPPORTED",
          "SQL platform is not supported by this worker");
    };
  }

  private DataSource createJt400DataSource(
      WorkerSqlConnectionDescriptor descriptor,
      String credentialAlias,
      String username) {
    char[] password = passwordProvider.password(credentialAlias);
    try {
      return jt400DataSourceFactory.create(descriptor.host(), username, password);
    } finally {
      Arrays.fill(password, '\0');
    }
  }

  private void validateWriteCapability(String connectionId, String targetEnvironment) {
    WorkerSqlConnectionDescriptor descriptor =
        egressPolicy.validateDmlConnection(connectionId, targetEnvironment);
    DataSource writeDataSource = createWriteDataSource(descriptor);
    try (var connection = writeDataSource.getConnection()) {
      // 打开并关闭隔离的写数据源，以验证其配置的连接边界。
    } catch (SQLException | RuntimeException exception) {
      throw dmlDisabled();
    }
  }

  private char[] requiredWritePassword(String credentialAlias) {
    try {
      char[] password = passwordProvider.password(credentialAlias);
      if (password == null || password.length == 0) {
        throw dmlDisabled();
      }
      return password;
    } catch (WorkerSqlEgressException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw dmlDisabled();
    }
  }

  private WorkerSqlEgressException dmlDisabled() {
    return new WorkerSqlEgressException(
        "SQL_DML_WORKER_DISABLED",
        "SQL DML is not enabled with a usable dedicated write credential");
  }

  private boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
