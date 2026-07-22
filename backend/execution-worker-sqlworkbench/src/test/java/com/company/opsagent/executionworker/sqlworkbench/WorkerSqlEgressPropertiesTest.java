package com.company.opsagent.executionworker.sqlworkbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.company.opsagent.contracts.sqlworkbench.SqlQueryAction;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryExecutionRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryLimits;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryRequest;
import com.company.opsagent.contracts.workflow.OperatorContext;
import com.company.opsagent.contracts.workflow.PolicyDecisionReference;
import com.company.opsagent.contracts.workflow.TraceContext;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证 Worker SQL 出口配置只生成本地连接目录和 allowlist 策略。
 */
class WorkerSqlEgressPropertiesTest {

  /**
   * 验证显式配置的开发连接可以转换为可校验的 Worker 出口策略。
   */
  @Test
  void convertsLocalPropertiesToPolicyInputs() {
    WorkerSqlEgressProperties properties = new WorkerSqlEgressProperties();
    properties.setAllowedTargets(List.of(target("as400-dev.internal", 446)));
    WorkerSqlEgressProperties.Connection connection =
        connection("as400-dev-readonly", "development", "as400-dev.internal", 446);
    connection.setDmlEnabled(true);
    connection.setDmlCredentialAlias("as400-dev-writer-password");
    connection.setDmlUsername("as400-dev-writer");
    properties.setConnections(List.of(connection));

    WorkerSqlEgressPolicy policy = properties.toPolicy();

    assertEquals(
        "as400-dev-readonly",
        policy.validate(request("as400-dev-readonly", "development")).connectionId());
    assertEquals(
        "as400-dev-writer",
        policy.validate(request("as400-dev-readonly", "development")).dmlUsername());
  }

  /**
   * 验证默认配置没有任何可用出口目标。
   */
  @Test
  void defaultsToEmptyLists() {
    WorkerSqlEgressProperties properties = new WorkerSqlEgressProperties();

    assertFalse(properties.getAllowedTargets().iterator().hasNext());
    assertFalse(properties.getConnections().iterator().hasNext());
  }

  private WorkerSqlEgressProperties.Target target(String host, int port) {
    WorkerSqlEgressProperties.Target target = new WorkerSqlEgressProperties.Target();
    target.setHost(host);
    target.setPort(port);
    return target;
  }

  private WorkerSqlEgressProperties.Connection connection(String id, String environment, String host, int port) {
    WorkerSqlEgressProperties.Connection connection = new WorkerSqlEgressProperties.Connection();
    connection.setConnectionId(id);
    connection.setTargetEnvironment(environment);
    connection.setHost(host);
    connection.setPort(port);
    connection.setCredentialAlias(id);
    connection.setEnabled(true);
    return connection;
  }

  private SqlQueryExecutionRequest request(String connectionId, String environment) {
    var query = new SqlQueryRequest(
        "1.0",
        connectionId,
        environment,
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
}
