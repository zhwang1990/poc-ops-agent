package com.company.opsagent.controlplane.bootstrap.api;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.APPLICATION_OCTET_STREAM;
import static org.springframework.http.MediaType.MULTIPART_FORM_DATA;

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
import org.springframework.core.io.ByteArrayResource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.http.client.MultipartBodyBuilder;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@TestPropertySource(properties = {
    "ops-agent.security.auth-mode=dev-hs256",
    "ops-agent.security.issuer=ops-agent-dev",
    "ops-agent.security.audience=ops-agent-internal",
    "ops-agent.security.username-claim=preferred_username",
    "ops-agent.security.role-claim=roles",
    "ops-agent.policy.version=rbac-v1",
    "ops-agent.worker.base-url=http://127.0.0.1:1",
    "ops-agent.skill-registry.root-path=target/test-classes/skills",
    "ops-agent.skill-registry.signature-required=true",
    "ops-agent.audit.storage-path=target/test-audit/release-center-api-audit.jsonl",
    "spring.r2dbc.url=r2dbc:h2:mem:///release-center-api-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "ops-agent.workflow.startup-recovery-enabled=false",
    "ops-agent.agent-runtime.enabled=false",
    "ops-agent.release-center.enabled=false"
})
class ReleaseCenterControllerTest {

  @Autowired
  private WebTestClient webTestClient;

  @Value("${ops-agent.security.shared-secret}")
  private String sharedSecret;

  @Test
  void managesApplicationsThroughPolicyProtectedApi() {
    webTestClient.post()
        .uri("/internal/release-center/applications")
        .headers(headers -> headers.setBearerAuth(token("admin", List.of("ops-admin"))))
        .contentType(APPLICATION_JSON)
        .bodyValue("""
            {
              "applicationId": "orders",
              "displayName": "订单服务",
              "artifactType": "WAR",
              "healthCheckPath": "/health",
              "enabled": true
            }
            """)
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.applicationId").isEqualTo("orders")
        .jsonPath("$.artifactType").isEqualTo("WAR");

    webTestClient.get()
        .uri("/internal/release-center/applications")
        .headers(headers -> headers.setBearerAuth(token("alice", List.of("ops-reader"))))
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$[0].applicationId").isEqualTo("orders")
        .jsonPath("$[0].displayName").isEqualTo("订单服务");
  }

  @Test
  void rotatesCredentialWithoutReturningSensitiveMaterial() {
    webTestClient.post()
        .uri("/internal/release-center/credentials")
        .headers(headers -> headers.setBearerAuth(token("admin", List.of("ops-admin"))))
        .contentType(APPLICATION_JSON)
        .bodyValue("""
            {
              "credentialAlias": "sit-tomcat",
              "serverType": "TOMCAT",
              "secret": "test-release-credential"
            }
            """)
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.credentialAlias").isEqualTo("sit-tomcat")
        .jsonPath("$.fingerprint").isNotEmpty()
        .jsonPath("$.updatedAt").isNotEmpty()
        .jsonPath("$.secret").doesNotExist()
        .jsonPath("$.password").doesNotExist()
        .jsonPath("$.ciphertext").doesNotExist();
  }

  @Test
  void uploadsTomcatWarArtifactWithoutReturningLocalPath() {
    webTestClient.post()
        .uri("/internal/release-center/artifacts/tomcat-war")
        .headers(headers -> headers.setBearerAuth(token("admin", List.of("ops-admin"))))
        .contentType(MULTIPART_FORM_DATA)
        .body(BodyInserters.fromMultipartData(warUploadBody()))
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.artifactId").isNotEmpty()
        .jsonPath("$.applicationId").isEqualTo("orders")
        .jsonPath("$.artifactType").isEqualTo("WAR")
        .jsonPath("$.checksum").value(value -> value.toString().startsWith("sha256:"))
        .jsonPath("$.originalFilename").isEqualTo("orders.war")
        .jsonPath("$.storagePath").doesNotExist()
        .jsonPath("$.absolutePath").doesNotExist();
  }

  @Test
  void exposesServerConnectionTestPlaceholder() {
    webTestClient.post()
        .uri("/internal/release-center/servers/sit-tomcat-1/test")
        .headers(headers -> headers.setBearerAuth(token("admin", List.of("ops-admin"))))
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.nodeId").isEqualTo("sit-tomcat-1")
        .jsonPath("$.status").isEqualTo("SKIPPED");
  }

  @Test
  void rejectsReaderFromReleaseCatalogWriteEndpoint() {
    webTestClient.post()
        .uri("/internal/release-center/applications")
        .headers(headers -> headers.setBearerAuth(token("alice", List.of("ops-reader"))))
        .contentType(APPLICATION_JSON)
        .bodyValue("""
            {
              "applicationId": "orders",
              "displayName": "订单服务",
              "artifactType": "WAR",
              "healthCheckPath": "/health",
              "enabled": true
            }
            """)
        .exchange()
        .expectStatus().isForbidden()
        .expectBody()
        .jsonPath("$.code").isEqualTo("POLICY_DENIED");
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

  private MultiValueMap<String, org.springframework.http.HttpEntity<?>> warUploadBody() {
    MultipartBodyBuilder builder = new MultipartBodyBuilder();
    builder.part("applicationId", "orders");
    builder.part("targetEnvironment", "dev");
    builder.part("file", new ByteArrayResource("war".getBytes(StandardCharsets.UTF_8)) {
          @Override
          public String getFilename() {
            return "orders.war";
          }
        })
        .filename("orders.war")
        .contentType(APPLICATION_OCTET_STREAM);
    return builder.build();
  }
}
