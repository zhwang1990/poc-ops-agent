package com.company.opsagent.controlplane.bootstrap;

import static org.springframework.http.MediaType.APPLICATION_JSON;

import com.company.opsagent.controlplane.bootstrap.support.TestOidcIdentityProvider;
import com.company.opsagent.controlplane.modules.audit.AuditTrail;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * OIDC 集成测试。
 *
 * <p>通过本地模拟 IdP 验证 issuer 发现、JWK 拉取、Token 校验和策略拒绝等主流程。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@TestPropertySource(properties = {
    "ops-agent.security.auth-mode=oidc",
    "ops-agent.security.browser-login-enabled=false",
    "ops-agent.security.audience=ops-agent-internal",
    "ops-agent.security.username-claim=preferred_username",
    "ops-agent.security.role-claim=groups",
    "ops-agent.policy.version=rbac-v1",
    "ops-agent.policy.required-roles-by-action.internal.health.read[0]=ROLE_ops-reader",
    "ops-agent.policy.required-roles-by-action.internal.health.read[1]=ROLE_ops-admin",
    "ops-agent.policy.required-roles-by-action.internal.modules.read[0]=ROLE_ops-reader",
    "ops-agent.policy.required-roles-by-action.internal.modules.read[1]=ROLE_ops-admin",
    "ops-agent.policy.required-roles-by-action.internal.echo.read[0]=ROLE_ops-reader",
    "ops-agent.policy.required-roles-by-action.internal.echo.read[1]=ROLE_ops-admin",
    "ops-agent.policy.required-roles-by-action.internal.failures.read[0]=ROLE_ops-admin",
    "ops-agent.policy.required-roles-by-action.internal.audit.read[0]=ROLE_ops-admin",
    "ops-agent.policy.required-roles-by-action.internal.audit.read[1]=ROLE_ops-auditor",
    "spring.r2dbc.url=r2dbc:h2:mem:///control-plane-oidc-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "ops-agent.workflow.startup-recovery-enabled=false",
    "ops-agent.audit.storage-path=target/test-audit-oidc/control-plane-audit.jsonl"
})
class ControlPlaneOidcIntegrationTest extends BootstrapSkillRegistryTestSupport {

  private static final String AUDIENCE = "ops-agent-internal";
  private static final TestOidcIdentityProvider TEST_IDP = TestOidcIdentityProvider.start();

  @Autowired
  private WebTestClient webTestClient;

  @Autowired
  private AuditTrail auditTrail;

  /**
   * 为 Spring 测试上下文动态注入本地模拟 IdP 的 issuer 参数。
   */
  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    registry.add("ops-agent.security.issuer", TEST_IDP::issuer);
    registry.add("ops-agent.security.issuer-uri", TEST_IDP::issuer);
  }

  /**
   * 关闭测试期间启动的本地模拟 IdP。
   */
  @AfterAll
  static void stopProvider() {
    TEST_IDP.close();
  }

  @Test
  void acceptsValidTokenFromMockOidcProvider() {
    // 验证在 OIDC 模式下，合法令牌可正常访问受保护接口。
    auditTrail.clear();

    webTestClient.get()
        .uri("/internal/healthz")
        .headers(headers -> headers.setBearerAuth(TEST_IDP.issueToken(
            "alice-id",
            "preferred_username",
            "alice",
            "groups",
            java.util.List.of("ops-reader"),
            AUDIENCE)))
        .exchange()
        .expectStatus().isOk()
        .expectHeader().contentTypeCompatibleWith(APPLICATION_JSON)
        .expectBody()
        .jsonPath("$.status").isEqualTo("UP");
  }

  @Test
  void supportsDelimitedRoleClaimsInOidcMode() {
    // 验证角色 claim 为字符串时也能被拆分并正确授权。
    auditTrail.clear();

    webTestClient.get()
        .uri("/internal/modules")
        .headers(headers -> headers.setBearerAuth(TEST_IDP.issueToken(
            "bob-id",
            "preferred_username",
            "bob",
            "groups",
            "ops-reader ops-admin",
            AUDIENCE)))
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.moduleIds.length()").isEqualTo(8);
  }

  @Test
  void rejectsTokenWithUnexpectedIssuerInOidcMode() {
    // issuer 不匹配时必须被视为未认证。
    auditTrail.clear();

    webTestClient.get()
        .uri("/internal/healthz")
        .headers(headers -> headers.setBearerAuth(TEST_IDP.issueToken(
            "mallory-id",
            "preferred_username",
            "mallory",
            "groups",
            java.util.List.of("ops-reader"),
            AUDIENCE,
            TEST_IDP.issuer() + "/unexpected")))
        .exchange()
        .expectStatus().isUnauthorized()
        .expectBody()
        .jsonPath("$.code").isEqualTo("UNAUTHENTICATED");
  }

  @Test
  void rejectsInsufficientRoleAgainstAdminEndpoint() {
    // 即便认证成功，只要角色不足，也应被服务端策略拒绝。
    auditTrail.clear();

    webTestClient.get()
        .uri("/internal/failures/illegal-argument")
        .headers(headers -> headers.setBearerAuth(TEST_IDP.issueToken(
            "charlie-id",
            "preferred_username",
            "charlie",
            "groups",
            java.util.List.of("ops-reader"),
            AUDIENCE)))
        .exchange()
        .expectStatus().isForbidden()
        .expectBody()
        .jsonPath("$.code").isEqualTo("POLICY_DENIED");
  }
}
