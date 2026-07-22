package com.company.opsagent.controlplane.bootstrap;

import static org.springframework.http.MediaType.APPLICATION_JSON;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@TestPropertySource(properties = {
    "ops-agent.security.auth-mode=dev-hs256",
    "ops-agent.security.issuer=ops-agent-dev",
    "ops-agent.security.audience=ops-agent-internal",
    "ops-agent.security.username-claim=preferred_username",
    "ops-agent.security.role-claim=roles",
    "ops-agent.policy.version=rbac-v1",
    "ops-agent.policy.required-roles-by-action.internal.agent.diagnostics.read[0]=ROLE_ops-reader",
    "ops-agent.policy.required-roles-by-action.internal.agent.diagnostics.read[1]=ROLE_ops-admin",
    "ops-agent.audit.storage-path=target/test-audit/agent-runtime-disabled-audit.jsonl",
    "spring.r2dbc.url=r2dbc:h2:mem:///agent-runtime-disabled-endpoint-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "ops-agent.workflow.startup-recovery-enabled=false",
    "ops-agent.agent-runtime.enabled=false"
})
class AgentRuntimeDisabledEndpointIntegrationTest extends BootstrapSkillRegistryTestSupport {

  @Autowired
  private WebTestClient webTestClient;

  @Value("${ops-agent.security.shared-secret}")
  private String sharedSecret;

  @Test
  void reportsAgentRuntimeDisabledWhenExplicitlyConfiguredOff() {
    webTestClient.post()
        .uri("/api/v1/agent/diagnostics")
        .headers(headers -> headers.setBearerAuth(token("alice", List.of("ops-reader"))))
        .contentType(APPLICATION_JSON)
        .bodyValue("""
            {
              "targetEnvironment": "development",
              "idempotencyKey": "agent-runtime-explicitly-disabled",
              "userIntent": "check node health",
              "inputParameters": {
                "nodeId": "node-1"
              }
            }
            """)
        .exchange()
        .expectStatus().isEqualTo(503)
        .expectBody()
        .jsonPath("$.code").isEqualTo("AGENT_RUNTIME_DISABLED");
  }

  private String token(String username, List<String> roles) {
    try {
      JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
          .subject(username)
          .issuer("ops-agent-dev")
          .audience("ops-agent-internal")
          .issueTime(Date.from(Instant.now()))
          .expirationTime(Date.from(Instant.now().plusSeconds(600)))
          .claim("preferred_username", username)
          .claim("roles", roles)
          .build();
      SignedJWT signedJwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claimsSet);
      signedJwt.sign(new MACSigner(sharedSecret.getBytes(StandardCharsets.UTF_8)));
      return signedJwt.serialize();
    } catch (JOSEException exception) {
      throw new IllegalStateException("failed to create test token", exception);
    }
  }
}
