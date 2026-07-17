package com.company.opsagent.controlplane.bootstrap;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockOidcLogin;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * 浏览器 OIDC 登录模式集成测试。
 *
 * <p>验证登录入口重定向、会话查询和基于浏览器会话访问内部接口的主流程。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@TestPropertySource(properties = {
    "ops-agent.security.auth-mode=oidc",
    "ops-agent.security.shared-secret=ops-agent-dev-secret-2026-06-06-0001",
    "ops-agent.security.issuer=ops-agent-dev",
    "ops-agent.security.audience=ops-agent-internal",
    "ops-agent.security.username-claim=preferred_username",
    "ops-agent.security.role-claim=roles",
    "ops-agent.security.browser-login-enabled=true",
    "ops-agent.security.browser-registration-id=ops-agent",
    "ops-agent.security.browser-login-success-uri=/auth/session",
    "ops-agent.security.browser-logout-success-uri={baseUrl}/",
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
    "spring.r2dbc.url=r2dbc:h2:mem:///browser-authentication-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "ops-agent.workflow.startup-recovery-enabled=false",
    "spring.security.oauth2.client.provider.ops-agent.authorization-uri=https://idp.example.com/oauth2/authorize",
    "spring.security.oauth2.client.provider.ops-agent.token-uri=https://idp.example.com/oauth2/token",
    "spring.security.oauth2.client.provider.ops-agent.jwk-set-uri=https://idp.example.com/oauth2/jwks",
    "spring.security.oauth2.client.provider.ops-agent.user-name-attribute=sub",
    "spring.security.oauth2.client.registration.ops-agent.provider=ops-agent",
    "spring.security.oauth2.client.registration.ops-agent.client-id=control-plane-client",
    "spring.security.oauth2.client.registration.ops-agent.client-secret=control-plane-secret",
    "spring.security.oauth2.client.registration.ops-agent.authorization-grant-type=authorization_code",
    "spring.security.oauth2.client.registration.ops-agent.redirect-uri={baseUrl}/login/oauth2/code/{registrationId}",
    "spring.security.oauth2.client.registration.ops-agent.scope[0]=openid",
    "spring.security.oauth2.client.registration.ops-agent.scope[1]=profile"
})
class BrowserAuthenticationControllerTest extends BootstrapSkillRegistryTestSupport {

  @Autowired
  private WebTestClient webTestClient;

  @Test
  void exposesBrowserLoginEntry() {
    // 浏览器登录入口应重定向到 Spring Security 的 OIDC 授权起点。
    webTestClient.get()
        .uri("/auth/login")
        .exchange()
        .expectStatus().isFound()
        .expectHeader().valueEquals("Location", "/oauth2/authorization/ops-agent");
  }

  @Test
  void returnsUnauthorizedWhenBrowserSessionMissing() {
    // 未登录浏览器访问会话查询接口时应得到 401。
    webTestClient.get()
        .uri("/auth/session")
        .exchange()
        .expectStatus().isUnauthorized()
        .expectHeader().contentTypeCompatibleWith(APPLICATION_JSON)
        .expectBody()
        .jsonPath("$.authenticated").isEqualTo(false);
  }

  @Test
  void exposesCurrentBrowserSession() {
    // 已登录浏览器应能读取标准化后的会话主体信息。
    webTestClient.mutateWith(mockOidcLogin()
            .idToken(token -> token
                .subject("alice-id")
                .claim("preferred_username", "alice")
                .claim("roles", List.of("ops-reader"))))
        .get()
        .uri("/auth/session")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.authenticated").isEqualTo(true)
        .jsonPath("$.subject").isEqualTo("alice-id")
        .jsonPath("$.username").isEqualTo("alice")
        .jsonPath("$.roles[0]").isEqualTo("ROLE_ops-reader");
  }

  @Test
  void allowsInternalEndpointWithBrowserSession() {
    // 浏览器登录后的会话主体应可复用现有内部接口鉴权链路。
    webTestClient.mutateWith(mockOidcLogin()
            .idToken(token -> token
                .subject("alice-id")
                .claim("preferred_username", "alice")
                .claim("roles", List.of("ops-reader"))))
        .get()
        .uri("/internal/healthz")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.status").isEqualTo("UP");
  }
}
