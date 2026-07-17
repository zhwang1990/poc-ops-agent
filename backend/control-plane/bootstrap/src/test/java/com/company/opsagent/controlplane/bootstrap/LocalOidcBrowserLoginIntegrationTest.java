package com.company.opsagent.controlplane.bootstrap;

import java.net.URI;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.http.HttpCookie;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.reactive.server.EntityExchangeResult;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("local-oidc")
@TestPropertySource(properties = {
    "server.port=18080",
    "ops-agent.security.issuer=http://127.0.0.1:18080/mock-oidc",
    "ops-agent.security.issuer-uri=http://127.0.0.1:18080/mock-oidc",
    "ops-agent.security.jwk-set-uri=http://127.0.0.1:18080/mock-oidc/oauth2/jwks",
    "spring.r2dbc.url=r2dbc:h2:mem:///local-oidc-browser-login-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "ops-agent.workflow.startup-recovery-enabled=false",
    "ops-agent.local-oidc-provider.enabled=true",
    "ops-agent.local-oidc-provider.issuer=http://127.0.0.1:18080/mock-oidc",
    "ops-agent.local-oidc-provider.client-id=ops-agent-local-client",
    "ops-agent.local-oidc-provider.client-secret=ops-agent-local-secret",
    "spring.security.oauth2.client.provider.ops-agent.authorization-uri=http://127.0.0.1:18080/mock-oidc/oauth2/authorize",
    "spring.security.oauth2.client.provider.ops-agent.token-uri=http://127.0.0.1:18080/mock-oidc/oauth2/token",
    "spring.security.oauth2.client.provider.ops-agent.jwk-set-uri=http://127.0.0.1:18080/mock-oidc/oauth2/jwks",
    "spring.security.oauth2.client.provider.ops-agent.user-name-attribute=sub",
    "spring.security.oauth2.client.registration.ops-agent.provider=ops-agent",
    "spring.security.oauth2.client.registration.ops-agent.client-id=ops-agent-local-client",
    "spring.security.oauth2.client.registration.ops-agent.client-secret=ops-agent-local-secret",
    "spring.security.oauth2.client.registration.ops-agent.authorization-grant-type=authorization_code",
    "spring.security.oauth2.client.registration.ops-agent.client-authentication-method=client_secret_basic",
    "spring.security.oauth2.client.registration.ops-agent.redirect-uri={baseUrl}/login/oauth2/code/{registrationId}",
    "spring.security.oauth2.client.registration.ops-agent.scope[0]=openid",
    "spring.security.oauth2.client.registration.ops-agent.scope[1]=profile"
})
class LocalOidcBrowserLoginIntegrationTest extends BootstrapSkillRegistryTestSupport {

  @Autowired
  private WebTestClient webTestClient;

  @Test
  void establishesBrowserSessionViaLocalOidcProvider() {
    EntityExchangeResult<byte[]> loginResult = webTestClient.get()
        .uri("/auth/login")
        .exchange()
        .expectStatus().isFound()
        .expectHeader().valueEquals("Location", "/oauth2/authorization/ops-agent")
        .expectBody()
        .returnResult();

    EntityExchangeResult<byte[]> authorizationRequestResult = webTestClient.get()
        .uri(loginResult.getResponseHeaders().getLocation().toString())
        .exchange()
        .expectStatus().isFound()
        .expectHeader().valueMatches("Location", "http://127.0.0.1:18080/mock-oidc/oauth2/authorize.*")
        .expectBody()
        .returnResult();

    HttpCookie sessionCookie = requiredCookie(authorizationRequestResult, "SESSION");
    URI authorizeLocation = authorizationRequestResult.getResponseHeaders().getLocation();
    Assertions.assertThat(authorizeLocation).isNotNull();

    EntityExchangeResult<byte[]> authorizeResult = webTestClient.get()
        .uri(authorizeLocation)
        .exchange()
        .expectStatus().isFound()
        .expectHeader().valueMatches("Location", "/login/oauth2/code/ops-agent.*")
        .expectBody()
        .returnResult();

    URI callbackLocation = authorizeResult.getResponseHeaders().getLocation();
    Assertions.assertThat(callbackLocation).isNotNull();

    EntityExchangeResult<byte[]> callbackResult = webTestClient.get()
        .uri(callbackLocation)
        .cookie(sessionCookie.getName(), sessionCookie.getValue())
        .exchange()
        .expectStatus().isFound()
        .expectHeader().valueEquals("Location", "/")
        .expectBody()
        .returnResult();

    HttpCookie authenticatedSessionCookie = responseCookieOrDefault(callbackResult, "SESSION", sessionCookie);

    webTestClient.get()
        .uri("/auth/session")
        .cookie(authenticatedSessionCookie.getName(), authenticatedSessionCookie.getValue())
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.authenticated").isEqualTo(true)
        .jsonPath("$.subject").isEqualTo("local-reader-id")
        .jsonPath("$.username").isEqualTo("local.reader")
        .jsonPath("$.roles[0]").isEqualTo("ROLE_ops-reader");

    webTestClient.get()
        .uri("/internal/healthz")
        .cookie(authenticatedSessionCookie.getName(), authenticatedSessionCookie.getValue())
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.status").isEqualTo("UP")
        .jsonPath("$.service").isEqualTo("control-plane");

    webTestClient.post()
        .uri("/logout")
        .cookie(authenticatedSessionCookie.getName(), authenticatedSessionCookie.getValue())
        .exchange()
        .expectStatus().isFound()
        .expectHeader().valueEquals("Location", "/");

    webTestClient.get()
        .uri("/auth/session")
        .cookie(authenticatedSessionCookie.getName(), authenticatedSessionCookie.getValue())
        .exchange()
        .expectStatus().isUnauthorized()
        .expectBody()
        .jsonPath("$.authenticated").isEqualTo(false);
  }

  private HttpCookie requiredCookie(EntityExchangeResult<byte[]> response, String name) {
    HttpCookie cookie = response.getResponseCookies().getFirst(name);
    Assertions.assertThat(cookie)
        .as("response cookie %s should exist", name)
        .isNotNull();
    return cookie;
  }

  private HttpCookie responseCookieOrDefault(
      EntityExchangeResult<byte[]> response,
      String name,
      HttpCookie fallback) {
    HttpCookie cookie = response.getResponseCookies().getFirst(name);
    return cookie != null ? cookie : fallback;
  }
}
