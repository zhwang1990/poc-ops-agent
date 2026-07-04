package com.company.opsagent.controlplane.bootstrap.security;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.company.opsagent.controlplane.bootstrap.api.ApiErrorResponseWriter;
import com.company.opsagent.controlplane.bootstrap.config.SecurityProperties;
import com.company.opsagent.controlplane.modules.audit.AuditEvent;
import com.company.opsagent.controlplane.modules.audit.AuditTrail;
import com.company.opsagent.controlplane.modules.identity.IdentityClaimsMapper;
import com.company.opsagent.controlplane.modules.identity.OperatorIdentity;
import com.company.opsagent.controlplane.modules.policy.PolicyDecision;
import com.company.opsagent.controlplane.modules.policy.PolicyDecisionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * 验证策略过滤器的安全关键行为。
 *
 * <p>本测试聚焦审计写入线程切换：文件审计是阻塞 I/O，不能运行在 WebFlux 调用线程或事件循环线程上。
 */
class PolicyEnforcementWebFilterTest {

  @Test
  void recordsAuditTrailOffTheCallingThread() {
    // 记录测试线程名，后续和审计落盘线程对比，证明审计写入已经被调度到 boundedElastic。
    String callerThreadName = Thread.currentThread().getName();
    var auditTrail = new ThreadRecordingAuditTrail();
    var filter = new PolicyEnforcementWebFilter(
        allowAllPolicy(),
        auditTrail,
        new ApiErrorResponseWriter(new ObjectMapper()),
        token -> Mono.just(new OperatorIdentity("operator-1", "ops.reader", List.of("ROLE_ops-reader"))),
        emptyPrincipalResolver(),
        null,
        "OPS_AGENT_SESSION");
    var exchange = MockServerWebExchange.from(MockServerHttpRequest
        .get("/internal/healthz")
        .header("Authorization", "Bearer valid-token"));

    StepVerifier.create(filter.filter(exchange, chainExchange -> Mono.empty()))
        .verifyComplete();

    // 审计线程必须不同于调用线程，并带有 Reactor boundedElastic 标识，避免阻塞调用线程。
    String auditThreadName = auditTrail.recordingThreadName.get();
    assertNotEquals(callerThreadName, auditThreadName);
    assertTrue(auditThreadName.contains("boundedElastic"));
  }

  @Test
  void mapsSqlWorkbenchMutatingAndResultEndpointsToExplicitActions() {
    assertEquals(
        "internal.sql-workbench.connections.create",
        resolvedAction(MockServerHttpRequest.post("/internal/sql-workbench/connections")));
    assertEquals(
        "internal.sql-workbench.connections.update",
        resolvedAction(MockServerHttpRequest.put("/internal/sql-workbench/connections/as400-development")));
    assertEquals(
        "internal.sql-workbench.connections.delete",
        resolvedAction(MockServerHttpRequest.delete("/internal/sql-workbench/connections/as400-development")));
    assertEquals(
        "internal.sql-workbench.connections.probe",
        resolvedAction(MockServerHttpRequest.post("/internal/sql-workbench/connections/as400-development/probe")));
    assertEquals(
        "internal.sql-workbench.metadata.read",
        resolvedAction(MockServerHttpRequest.get("/internal/sql-workbench/connections/as400-development/metadata")));
    assertEquals(
        "internal.sql-workbench.queries.run",
        resolvedAction(MockServerHttpRequest.post("/internal/sql-workbench/queries/run")));
    assertEquals(
        "internal.sql-workbench.dml.commit",
        resolvedAction(MockServerHttpRequest.post("/internal/sql-workbench/queries/commit")));
    assertEquals(
        "internal.sql-workbench.assistant.use",
        resolvedAction(MockServerHttpRequest.post("/internal/sql-workbench/assistant")));
    assertEquals(
        "internal.sql-workbench.results.read",
        resolvedAction(MockServerHttpRequest.get("/internal/sql-workbench/results/result-1")));
  }

  private String resolvedAction(MockServerHttpRequest.BaseBuilder<?> requestBuilder) {
    AtomicReference<String> action = new AtomicReference<>();
    var filter = new PolicyEnforcementWebFilter(
        capturingPolicy(action),
        new ThreadRecordingAuditTrail(),
        new ApiErrorResponseWriter(new ObjectMapper()),
        token -> Mono.just(new OperatorIdentity("operator-1", "ops.reader", List.of("ROLE_ops-reader"))),
        emptyPrincipalResolver(),
        null,
        "OPS_AGENT_SESSION");
    var exchange = MockServerWebExchange.from(requestBuilder.header("Authorization", "Bearer valid-token"));

    StepVerifier.create(filter.filter(exchange, chainExchange -> Mono.empty()))
        .verifyComplete();

    return action.get();
  }

  /**
   * 构造允许所有动作的策略服务，让测试只验证审计线程行为，不混入 RBAC 拒绝路径。
   */
  private PolicyDecisionService allowAllPolicy() {
    return new PolicyDecisionService() {
      @Override
      public PolicyDecision decide(OperatorIdentity identity, String action, String resource) {
        return new PolicyDecision(action, resource, "policy-v1", true, "allowed");
      }

      @Override
      public String policyVersion() {
        return "policy-v1";
      }
    };
  }

  private PolicyDecisionService capturingPolicy(AtomicReference<String> actionReference) {
    return new PolicyDecisionService() {
      @Override
      public PolicyDecision decide(OperatorIdentity identity, String action, String resource) {
        actionReference.set(action);
        return new PolicyDecision(action, resource, "policy-v1", true, "allowed");
      }

      @Override
      public String policyVersion() {
        return "policy-v1";
      }
    };
  }

  /**
   * 构造空的 Spring Security 主体解析器。
   *
   * <p>本测试使用 Bearer Token 认证路径，空解析器用于避免过滤器继续读取外部安全上下文。
   */
  private AuthenticatedPrincipalOperatorIdentityResolver emptyPrincipalResolver() {
    return new AuthenticatedPrincipalOperatorIdentityResolver(
        new SecurityProperties(
            "dev-hs256",
            "ops-agent-dev",
            "ops-agent-internal",
            "secret",
            null,
            null,
            "preferred_username",
            "roles",
            "ROLE_",
            false,
            "ops-agent",
            "/auth/session",
            "/"),
        new IdentityClaimsMapper()) {
      @Override
      public Mono<OperatorIdentity> resolve(ServerWebExchange exchange) {
        return Mono.empty();
      }
    };
  }

  /**
   * 只记录审计调用线程名的测试替身。
   *
   * <p>它不写文件，避免测试本身引入 I/O；线程名就是本用例的唯一验证证据。
   */
  private static class ThreadRecordingAuditTrail implements AuditTrail {
    private final AtomicReference<String> recordingThreadName = new AtomicReference<>();

    @Override
    public void record(AuditEvent event) {
      recordingThreadName.set(Thread.currentThread().getName());
    }

    @Override
    public List<AuditEvent> snapshot() {
      return List.of();
    }

    @Override
    public Optional<AuditEvent> latest() {
      return Optional.empty();
    }

    @Override
    public void clear() {
      recordingThreadName.set(null);
    }
  }
}
