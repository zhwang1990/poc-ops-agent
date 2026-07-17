package com.company.opsagent.executionworker.sqlworkbench;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.company.opsagent.contracts.sqlworkbench.SqlQueryAction;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryExecutionRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryLimits;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryRequest;
import com.company.opsagent.contracts.workflow.OperatorContext;
import com.company.opsagent.contracts.workflow.PolicyDecisionReference;
import com.company.opsagent.contracts.workflow.TraceContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 验证 SQL 工作台 Worker 适配模块独立装配 SQL 出口边界，并保持空配置默认拒绝。
 */
class SqlWorkbenchWorkerConfigurationTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withUserConfiguration(SqlWorkbenchWorkerConfiguration.class)
      .withBean(Clock.class, Clock::systemUTC)
      .withBean(ObjectMapper.class, ObjectMapper::new);

  @Test
  void registersSqlEgressPolicyWithEmptyDefaultDeny() {
    contextRunner.run(context -> {
      assertNotNull(context.getBean(WorkerSqlEgressProperties.class));
      assertNotNull(context.getBean(WorkerSqlEgressPolicy.class));

      var worker = context.getBean(RestrictedSqlQueryExecutionWorker.class);
      var result = worker.execute(request());

      assertEquals("REJECTED", result.status());
      assertEquals("SQL_CONNECTION_NOT_FOUND", result.errorCode());
    });
  }

  @Test
  void registersKeyStorePasswordProviderWhenCredentialStoreIsConfigured() throws Exception {
    char[] storePassword = SqlTestSecretMaterial.password();
    String databasePassword = SqlTestSecretMaterial.value();
    Path keyStorePath = keyStore("as400-dev-readonly", databasePassword, storePassword);

    contextRunner
        .withPropertyValues(
            "ops-agent.worker.sql-credentials.key-store-path=" + keyStorePath,
            "ops-agent.worker.sql-credentials.store-password=" + new String(storePassword))
        .run(context -> assertArrayEquals(
            databasePassword.toCharArray(),
            context.getBean(SqlPasswordProvider.class).password("as400-dev-readonly")));
  }

  @Test
  void executesConfiguredH2ReadOnlyQuery() {
    h2ContextRunner()
        .run(context -> {
          var worker = context.getBean(RestrictedSqlQueryExecutionWorker.class);
          var result = worker.execute(h2Request("select ORDER_ID, STATUS from PUBLIC.ORDERS order by ORDER_ID"));

          assertEquals("SUCCEEDED", result.status());
          var page = context.getBean(SqlResultStore.class).find(result.resultId()).orElseThrow();
          assertEquals("ORDER_ID", page.columns().getFirst().name());
          assertEquals("STATUS", page.columns().get(1).name());
          assertTrue(page.rows().size() >= 2);
          assertEquals("READY", page.rows().getFirst().get(1).asText());
          assertEquals("PENDING", page.rows().get(1).get(1).asText());
        });
  }

  @Test
  void executesConfiguredH2CustomerOrderDemoQuery() {
    h2ContextRunner()
        .run(context -> {
          var worker = context.getBean(RestrictedSqlQueryExecutionWorker.class);
          var result = worker.execute(h2Request("""
              select c.REGION, count(*) as ORDER_COUNT, sum(o.AMOUNT) as TOTAL_AMOUNT
              from PUBLIC.ORDERS o
              join PUBLIC.CUSTOMERS c on c.CUSTOMER_ID = o.CUSTOMER_ID
              group by c.REGION
              order by TOTAL_AMOUNT desc
              """));

          assertEquals("SUCCEEDED", result.status());
          var page = context.getBean(SqlResultStore.class).find(result.resultId()).orElseThrow();
          assertEquals("REGION", page.columns().getFirst().name());
          assertEquals("ORDER_COUNT", page.columns().get(1).name());
          assertTrue(page.rows().size() >= 2);
        });
  }

  @Test
  void executesConfiguredH2ServiceHealthDemoQuery() {
    h2ContextRunner()
        .run(context -> {
          var worker = context.getBean(RestrictedSqlQueryExecutionWorker.class);
          var result = worker.execute(h2Request("""
              select SERVICE_NAME, ENVIRONMENT, HEALTH_STATUS, ERROR_RATE_PERCENT, P95_LATENCY_MS
              from PUBLIC.SERVICE_HEALTH
              where ENVIRONMENT = 'test'
              order by P95_LATENCY_MS desc
              """));

          assertEquals("SUCCEEDED", result.status());
          var page = context.getBean(SqlResultStore.class).find(result.resultId()).orElseThrow();
          assertEquals("SERVICE_NAME", page.columns().getFirst().name());
          assertTrue(page.rows().size() >= 2);
          assertTrue(page.rows().stream().allMatch(row -> "test".equals(row.get(1).asText())));
        });
  }

  private ApplicationContextRunner h2ContextRunner() {
    return contextRunner.withPropertyValues(
        "ops-agent.worker.sql-egress.allowed-targets[0].host=localhost",
        "ops-agent.worker.sql-egress.allowed-targets[0].port=9092",
        "ops-agent.worker.sql-egress.connections[0].connection-id=h2-local-test",
        "ops-agent.worker.sql-egress.connections[0].target-environment=sit",
        "ops-agent.worker.sql-egress.connections[0].platform-type=H2",
        "ops-agent.worker.sql-egress.connections[0].host=localhost",
        "ops-agent.worker.sql-egress.connections[0].port=9092",
        "ops-agent.worker.sql-egress.connections[0].credential-alias=h2-local-readonly",
        "ops-agent.worker.sql-egress.connections[0].enabled=true");
  }

  private Path keyStore(String alias, String secret, char[] storePassword) throws Exception {
    Path path = Files.createTempFile("ops-agent-sql", ".jceks");
    KeyStore keyStore = KeyStore.getInstance("JCEKS");
    keyStore.load(null, storePassword);
    keyStore.setEntry(
        alias,
        new KeyStore.SecretKeyEntry(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "AES")),
        new KeyStore.PasswordProtection(storePassword));
    try (var output = Files.newOutputStream(path)) {
      keyStore.store(output, storePassword);
    }
    return path;
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

  private SqlQueryExecutionRequest h2Request(String sql) {
    var query = new SqlQueryRequest(
        "1.0",
        "h2-local-test",
        "sit",
        "PUBLIC",
        SqlQueryAction.RUN_READ_ONLY,
        sql,
        List.of(),
        new SqlQueryLimits(500, 5_000_000, 30),
        "h2-key");
    return new SqlQueryExecutionRequest(
        "1.0",
        "h2-execution-1",
        "h2-workflow-1",
        query,
        "sha256:h2-test",
        new OperatorContext("operator-1", List.of("ROLE_ops-reader")),
        new PolicyDecisionReference("decision-1", "policy-v1", "ALLOW"),
        new TraceContext("trace-1", "request-1"),
        OffsetDateTime.now().plusSeconds(30));
  }
}
