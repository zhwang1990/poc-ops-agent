package com.company.opsagent.executionworker.sqlworkbench;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.company.opsagent.contracts.sqlworkbench.SqlQueryAction;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryExecutionRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryLimits;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryRequest;
import com.company.opsagent.contracts.workflow.OperatorContext;
import com.company.opsagent.contracts.workflow.PolicyDecisionReference;
import com.company.opsagent.contracts.workflow.TraceContext;
import java.time.OffsetDateTime;
import java.util.List;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

/**
 * 验证 Db2 for i 数据源注册表只用 Worker 本地目录和凭据别名创建 JDBC 数据源。
 */
class Jt400SqlDataSourceRegistryTest {

  @Test
  void createsDataSourceFromWorkerDescriptorAndCredentialAlias() {
    char[] databasePassword = SqlTestSecretMaterial.password();
    CapturingDataSourceFactory factory = new CapturingDataSourceFactory();
    DataSource expected = new JdbcDataSource();
    factory.dataSource = expected;
    var registry = new Jt400SqlDataSourceRegistry(
        policy(),
        alias -> {
          assertEquals("as400-dev-readonly", alias);
          return databasePassword.clone();
        },
        factory);

    DataSource actual = registry.resolve(request());

    assertSame(expected, actual);
    assertEquals("as400-dev.internal", factory.systemName);
    assertEquals("readonly_user", factory.username);
    assertArrayEquals(databasePassword, factory.password);
  }

  private WorkerSqlEgressPolicy policy() {
    return new WorkerSqlEgressPolicy(
        List.of(new WorkerSqlConnectionDescriptor(
            "as400-development",
            "development",
            "as400-dev.internal",
            446,
            "as400-dev-readonly",
            "readonly_user",
            true)),
        List.of(new WorkerSqlEgressTarget("as400-dev.internal", 446)));
  }

  private SqlQueryExecutionRequest request() {
    var query = new SqlQueryRequest(
        "1.0",
        "as400-development",
        "development",
        "ORDERS",
        SqlQueryAction.RUN_READ_ONLY,
        "select * from ORDERS.ORDERS",
        List.of(),
        new SqlQueryLimits(500, 5_000_000, 30),
        "key");
    return new SqlQueryExecutionRequest(
        "1.0",
        "execution-1",
        "workflow-1",
        query,
        "sha256:test",
        new OperatorContext("operator-1", List.of("ROLE_ops-reader")),
        new PolicyDecisionReference("decision-1", "policy-v1", "ALLOW"),
        new TraceContext("trace-1", "request-1"),
        OffsetDateTime.now().plusSeconds(30));
  }

  private static final class CapturingDataSourceFactory extends Jt400DataSourceFactory {

    private DataSource dataSource;
    private String systemName;
    private String username;
    private char[] password;

    @Override
    public DataSource create(String systemName, String username, char[] password) {
      this.systemName = systemName;
      this.username = username;
      this.password = password.clone();
      return dataSource;
    }
  }
}
