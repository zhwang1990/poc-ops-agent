package com.company.opsagent.controlplane.bootstrap;

import static org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.util.UriComponentsBuilder;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@AutoConfigureWebTestClient
@TestPropertySource(properties = {
    "server.port=18081",
    "spring.r2dbc.url=r2dbc:h2:mem:///local-oidc-provider-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "ops-agent.workflow.startup-recovery-enabled=false",
    "ops-agent.local-oidc-provider.enabled=true",
    "ops-agent.local-oidc-provider.issuer=http://127.0.0.1:18081/mock-oidc",
    "ops-agent.local-oidc-provider.client-id=ops-agent-local-client",
    "ops-agent.local-oidc-provider.client-secret=ops-agent-local-secret"
})
class LocalOidcProviderControllerTest extends BootstrapSkillRegistryTestSupport {

  @Autowired
  private WebTestClient webTestClient;

  @Test
  void exposesDiscoveryDocument() {
    webTestClient.get()
        .uri("/mock-oidc/.well-known/openid-configuration")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.issuer").isEqualTo("http://127.0.0.1:18081/mock-oidc")
        .jsonPath("$.authorization_endpoint").isEqualTo("http://127.0.0.1:18081/mock-oidc/oauth2/authorize")
        .jsonPath("$.token_endpoint").isEqualTo("http://127.0.0.1:18081/mock-oidc/oauth2/token")
        .jsonPath("$.jwks_uri").isEqualTo("http://127.0.0.1:18081/mock-oidc/oauth2/jwks");
  }

  @Test
  void exchangesAuthorizationCodeForTokens() {
    String redirectUri = "/login/oauth2/code/ops-agent";
    String state = "state=001";

    String callback = webTestClient.get()
        .uri(uriBuilder -> uriBuilder
            .path("/mock-oidc/oauth2/authorize")
            .queryParam("response_type", "code")
            .queryParam("client_id", "ops-agent-local-client")
            .queryParam("redirect_uri", redirectUri)
            .queryParam("scope", "openid profile")
            .queryParam("state", state)
            .queryParam("nonce", "nonce-001")
            .build())
        .exchange()
        .expectStatus().isFound()
        .expectHeader().valueMatches("Location", "/login/oauth2/code/ops-agent\\?code=.*&state=state%3D001")
        .returnResult(Void.class)
        .getResponseHeaders()
        .getLocation()
        .toString();

    String code = UriComponentsBuilder.fromUriString(callback)
        .build()
        .getQueryParams()
        .getFirst("code");

    webTestClient.post()
        .uri("/mock-oidc/oauth2/token")
        .contentType(APPLICATION_FORM_URLENCODED)
        .bodyValue(
            "grant_type=authorization_code"
                + "&code=" + code
                + "&redirect_uri=" + redirectUri
                + "&client_id=ops-agent-local-client"
                + "&client_secret=ops-agent-local-secret")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.access_token").exists()
        .jsonPath("$.id_token").exists()
        .jsonPath("$.token_type").isEqualTo("Bearer");
  }
}
