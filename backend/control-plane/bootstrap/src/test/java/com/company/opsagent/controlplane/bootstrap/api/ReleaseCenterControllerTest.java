package com.company.opsagent.controlplane.bootstrap.api;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.APPLICATION_OCTET_STREAM;
import static org.springframework.http.MediaType.MULTIPART_FORM_DATA;

import com.company.opsagent.controlplane.modules.release.ManagementMode;
import com.company.opsagent.controlplane.modules.release.ArtifactType;
import com.company.opsagent.controlplane.modules.release.ReleaseArtifact;
import com.company.opsagent.controlplane.modules.release.ReleaseAuditContext;
import com.company.opsagent.controlplane.modules.release.ReleaseCatalogStore;
import com.company.opsagent.controlplane.modules.release.ReleaseEnvironmentPolicy;
import com.company.opsagent.controlplane.modules.release.ReleaseEventPayload;
import com.company.opsagent.controlplane.modules.release.ReleaseEventSink;
import com.company.opsagent.controlplane.modules.release.ReleaseEventType;
import com.company.opsagent.controlplane.modules.release.ReleaseServer;
import com.company.opsagent.controlplane.modules.release.ReleaseWorkflowEvent;
import com.company.opsagent.controlplane.modules.release.ServerType;
import com.company.opsagent.controlplane.modules.release.TargetEnvironment;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
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

  @Autowired
  private ReleaseCatalogStore releaseCatalogStore;

  @Autowired
  private ReleaseEventSink releaseEventSink;

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
  void listsReleaseServersThroughPolicyProtectedApi() {
    releaseCatalogStore.saveServer(ReleaseServer.create(
            "dev-tomcat-1",
            "dev",
            ServerType.TOMCAT,
            ManagementMode.TOMCAT_MANAGER_API,
            "https://dev-tomcat-1.example.internal/manager",
            "/orders",
            "dev-tomcat",
            true))
        .block();

    webTestClient.get()
        .uri("/internal/release-center/servers?targetEnvironment=dev")
        .headers(headers -> headers.setBearerAuth(token("alice", List.of("ops-reader"))))
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$[0].nodeId").isEqualTo("dev-tomcat-1")
        .jsonPath("$[0].targetEnvironment").isEqualTo("DEV")
        .jsonPath("$[0].credentialAlias").isEqualTo("dev-tomcat");
  }

  @Test
  void createsLibertyScriptProfileServerThroughPolicyProtectedApi() {
    seedApprovedScriptProfile();

    webTestClient.post()
        .uri("/internal/release-center/servers")
        .headers(headers -> headers.setBearerAuth(token("admin", List.of("ops-admin"))))
        .contentType(APPLICATION_JSON)
        .bodyValue("""
            {
              "nodeId": "dev-liberty-1",
              "targetEnvironment": "dev",
              "serverType": "LIBERTY",
              "managementMode": "LIBERTY_SCRIPT_PROFILE",
              "managementEndpoint": "https://liberty-dev.example",
              "applicationPath": "/orders",
              "credentialAlias": "liberty-dev",
              "scriptProfile": {
                "profileId": "liberty-war-deploy",
                "parameters": [
                  {"name": "serverName", "value": "defaultServer"},
                  {"name": "applicationName", "value": "orders"},
                  {"name": "artifactPath", "value": "\\\\\\\\jenkins\\\\share\\\\orders\\\\latest\\\\orders.war"}
                ]
              },
              "enabled": true
            }
            """)
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.nodeId").isEqualTo("dev-liberty-1")
        .jsonPath("$.managementMode").isEqualTo("LIBERTY_SCRIPT_PROFILE")
        .jsonPath("$.scriptProfile.profileId").isEqualTo("liberty-war-deploy")
        .jsonPath("$.scriptProfile.parameters[0].name").isEqualTo("serverName")
        .jsonPath("$.scriptProfile.parameters[0].value").isEqualTo("defaultServer")
        .jsonPath("$.scriptProfile.parameters[2].name").isEqualTo("artifactPath")
        .jsonPath("$.scriptProfile.parameters[2].value").isEqualTo("\\\\jenkins\\share\\orders\\latest\\orders.war");
  }

  @Test
  void managesScriptProfilesThroughPolicyProtectedApi() {
    webTestClient.post()
        .uri("/internal/release-center/script-profiles")
        .headers(headers -> headers.setBearerAuth(token("release-operator", List.of("ops-release"))))
        .contentType(APPLICATION_JSON)
        .bodyValue("""
            {
              "profileId": "liberty-war-deploy",
              "displayName": "Liberty WAR deploy",
              "executablePath": "C:\\\\ops\\\\scripts\\\\liberty-war-deploy.cmd",
              "workingDirectory": "C:\\\\ops-agent\\\\work\\\\release",
              "arguments": [],
              "successExitCodes": [0],
              "timeoutSeconds": 600,
              "approved": true,
              "enabled": true
            }
            """)
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.profileId").isEqualTo("liberty-war-deploy")
        .jsonPath("$.targetEnvironment").doesNotExist()
        .jsonPath("$.executablePath").isEqualTo("C:\\ops\\scripts\\liberty-war-deploy.cmd")
        .jsonPath("$.arguments.length()").isEqualTo(0)
        .jsonPath("$.approved").isEqualTo(true)
        .jsonPath("$.enabled").isEqualTo(true);

    webTestClient.get()
        .uri("/internal/release-center/script-profiles")
        .headers(headers -> headers.setBearerAuth(token("release-operator", List.of("ops-release"))))
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$[0].profileId").isEqualTo("liberty-war-deploy")
        .jsonPath("$[0].requiredParameters").doesNotExist()
        .jsonPath("$[0].allowedParameters").doesNotExist();
  }

  @Test
  void streamsReleasePlanEventsThroughPolicyProtectedSse() {
    seedReleaseCatalog("dev");
    webTestClient.post()
        .uri("/internal/release-center/plans")
        .headers(headers -> headers.setBearerAuth(token("admin", List.of("ops-admin"))))
        .contentType(APPLICATION_JSON)
        .bodyValue("""
            {
              "applicationId": "orders",
              "targetEnvironment": "dev",
              "artifactId": "artifact-dev-1",
              "nodeIds": ["dev-tomcat-1"],
              "parametersHash": "sha256:abc123"
            }
            """)
        .exchange()
        .expectStatus().isOk();
    releaseEventSink.publish(new ReleaseWorkflowEvent(
        "1.0",
        UUID.randomUUID().toString(),
        UUID.nameUUIDFromBytes("rel-orders-dev".getBytes(StandardCharsets.UTF_8)).toString(),
        "rel-orders-dev",
        999,
        Instant.parse("2026-07-02T00:00:00Z"),
        ReleaseEventType.RELEASE_NODE_LOG,
        new ReleaseEventPayload.NodeLog("dev-tomcat-1", "STDOUT", "deploy started", Instant.parse("2026-07-02T00:00:00Z")),
        new ReleaseAuditContext(
            "RELEASE_NODE_LOG",
            "release:rel-orders-dev",
            "release-center-policy-v1",
            "LOG",
            "release node script output",
            "trace:rel-orders-dev",
            "request:rel-orders-dev")))
        .block();

    webTestClient.get()
        .uri("/internal/release-center/plans/rel-orders-dev/events?afterSequence=998")
        .headers(headers -> headers.setBearerAuth(token("alice", List.of("ops-reader"))))
        .accept(org.springframework.http.MediaType.TEXT_EVENT_STREAM)
        .exchange()
        .expectStatus().isOk()
        .expectHeader().contentTypeCompatibleWith(org.springframework.http.MediaType.TEXT_EVENT_STREAM)
        .returnResult(String.class)
        .getResponseBody()
        .as(responseBody -> {
          String body = responseBody.blockFirst(Duration.ofSeconds(5));
          org.junit.jupiter.api.Assertions.assertNotNull(body);
          org.junit.jupiter.api.Assertions.assertTrue(body.contains("RELEASE_NODE_LOG"), body);
          org.junit.jupiter.api.Assertions.assertTrue(body.contains("\"payloadType\":\"RELEASE_NODE_LOG\""), body);
          org.junit.jupiter.api.Assertions.assertTrue(body.contains("deploy started"), body);
          return responseBody;
        });
  }

  @Test
  void rejectsLibertyServerWhenReferencedScriptProfileIsNotApproved() {
    webTestClient.post()
        .uri("/internal/release-center/script-profiles")
        .headers(headers -> headers.setBearerAuth(token("admin", List.of("ops-admin"))))
        .contentType(APPLICATION_JSON)
        .bodyValue("""
            {
              "profileId": "liberty-war-deploy",
              "displayName": "Liberty WAR deploy",
              "executablePath": "C:\\\\ops\\\\scripts\\\\liberty-war-deploy.cmd",
              "workingDirectory": "C:\\\\ops-agent\\\\work\\\\release",
              "arguments": ["{{param.serverName}}"],
              "successExitCodes": [0],
              "timeoutSeconds": 600,
              "approved": false,
              "enabled": true
            }
            """)
        .exchange()
        .expectStatus().isOk();

    webTestClient.post()
        .uri("/internal/release-center/servers")
        .headers(headers -> headers.setBearerAuth(token("admin", List.of("ops-admin"))))
        .contentType(APPLICATION_JSON)
        .bodyValue("""
            {
              "nodeId": "dev-liberty-unapproved",
              "targetEnvironment": "dev",
              "serverType": "LIBERTY",
              "managementMode": "LIBERTY_SCRIPT_PROFILE",
              "managementEndpoint": "https://liberty-dev.example",
              "applicationPath": "/orders",
              "scriptProfile": {
                "profileId": "liberty-war-deploy",
                "parameters": [
                  {"name": "serverName", "value": "defaultServer"}
                ]
              },
              "enabled": true
            }
            """)
        .exchange()
        .expectStatus().isBadRequest();
  }

  @Test
  void deletesReleaseServerThroughPolicyProtectedApi() {
    releaseCatalogStore.saveServer(ReleaseServer.create(
            "dev-tomcat-delete",
            "dev",
            ServerType.TOMCAT,
            ManagementMode.TOMCAT_WAR_UPLOAD,
            "https://dev-tomcat-delete.example.internal/manager",
            "/orders",
            "dev-tomcat",
            true))
        .block();

    webTestClient.delete()
        .uri("/internal/release-center/servers/dev-tomcat-delete")
        .headers(headers -> headers.setBearerAuth(token("admin", List.of("ops-admin"))))
        .exchange()
        .expectStatus().isNoContent()
        .expectBody().isEmpty();

    webTestClient.get()
        .uri("/internal/release-center/servers?targetEnvironment=dev")
        .headers(headers -> headers.setBearerAuth(token("alice", List.of("ops-reader"))))
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$[?(@.nodeId == 'dev-tomcat-delete')]").isEmpty();
  }

  @Test
  void listsReleasePlansThroughPolicyProtectedApi() {
    webTestClient.get()
        .uri("/internal/release-center/plans")
        .headers(headers -> headers.setBearerAuth(token("alice", List.of("ops-reader"))))
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$").isArray();
  }

  @Test
  void createsAndExecutesDevReleasePlanThroughPolicyProtectedApi() {
    seedReleaseCatalog("dev");

    webTestClient.post()
        .uri("/internal/release-center/plans")
        .headers(headers -> headers.setBearerAuth(token("admin", List.of("ops-admin"))))
        .contentType(APPLICATION_JSON)
        .bodyValue("""
            {
              "applicationId": "orders",
              "targetEnvironment": "dev",
              "artifactId": "artifact-dev-1",
              "nodeIds": ["dev-tomcat-1"],
              "parametersHash": "sha256:abc123"
            }
            """)
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.releaseId").isNotEmpty()
        .jsonPath("$.applicationId").isEqualTo("orders")
        .jsonPath("$.targetEnvironment").isEqualTo("DEV")
        .jsonPath("$.status").isEqualTo("DRAFT")
        .jsonPath("$.nodes[0].nodeId").isEqualTo("dev-tomcat-1");

    webTestClient.get()
        .uri("/internal/release-center/plans")
        .headers(headers -> headers.setBearerAuth(token("alice", List.of("ops-reader"))))
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$[0].releaseId").isNotEmpty()
        .jsonPath("$[0].status").isEqualTo("DRAFT");

    webTestClient.post()
        .uri("/internal/release-center/plans/rel-orders-dev/execute")
        .headers(headers -> headers.setBearerAuth(token("admin", List.of("ops-admin"))))
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.releaseId").isEqualTo("rel-orders-dev")
        .jsonPath("$.status").isEqualTo("PARTIAL_FAILED")
        .jsonPath("$.nodes[0].status").isEqualTo("FAILED");
  }

  @Test
  void createsLibertyScriptReleasePlanWithoutUploadedArtifactThroughPolicyProtectedApi() {
    seedLibertyScriptCatalogWithoutUploadedArtifact("sit");

    webTestClient.post()
        .uri("/internal/release-center/plans")
        .headers(headers -> headers.setBearerAuth(token("admin", List.of("ops-admin"))))
        .contentType(APPLICATION_JSON)
        .bodyValue("""
            {
              "applicationId": "orders",
              "targetEnvironment": "sit",
              "nodeIds": ["sit-liberty-1"],
              "parametersHash": "sha256:abc123"
            }
            """)
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.releaseId").isEqualTo("rel-orders-sit")
        .jsonPath("$.applicationId").isEqualTo("orders")
        .jsonPath("$.targetEnvironment").isEqualTo("SIT")
        .jsonPath("$.artifactId").doesNotExist()
        .jsonPath("$.nodes[0].nodeId").isEqualTo("sit-liberty-1")
        .jsonPath("$.nodes[0].managementMode").isEqualTo("LIBERTY_SCRIPT_PROFILE");
  }

  @Test
  void sitReleasePlanRequiresMatchingConfirmationBeforeExecution() {
    seedReleaseCatalog("sit");

    webTestClient.post()
        .uri("/internal/release-center/plans")
        .headers(headers -> headers.setBearerAuth(token("admin", List.of("ops-admin"))))
        .contentType(APPLICATION_JSON)
        .bodyValue("""
            {
              "applicationId": "orders",
              "targetEnvironment": "sit",
              "artifactId": "artifact-sit-1",
              "nodeIds": ["sit-tomcat-1"],
              "parametersHash": "sha256:abc123"
            }
            """)
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.releaseId").isEqualTo("rel-orders-sit")
        .jsonPath("$.status").isEqualTo("WAIT_CONFIRM");

    webTestClient.post()
        .uri("/internal/release-center/plans/rel-orders-sit/confirm")
        .headers(headers -> headers.setBearerAuth(token("admin", List.of("ops-admin"))))
        .contentType(APPLICATION_JSON)
        .bodyValue("""
            {
              "confirmationId": "confirm-1",
              "parametersHash": "sha256:def456"
            }
            """)
        .exchange()
        .expectStatus().isBadRequest();

    webTestClient.post()
        .uri("/internal/release-center/plans/rel-orders-sit/execute")
        .headers(headers -> headers.setBearerAuth(token("admin", List.of("ops-admin"))))
        .exchange()
        .expectStatus().isBadRequest();
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

  private void seedReleaseCatalog(String targetEnvironment) {
    TargetEnvironment environment = TargetEnvironment.from(targetEnvironment);
    releaseCatalogStore.saveApplication(com.company.opsagent.controlplane.modules.release.ReleaseApplication.create(
            "orders",
            "订单服务",
            ArtifactType.WAR,
            "/health",
            true))
        .block();
    releaseCatalogStore.saveEnvironmentPolicy(ReleaseEnvironmentPolicy.defaultFor(environment))
        .block();
    releaseCatalogStore.saveServer(ReleaseServer.create(
            targetEnvironment + "-tomcat-1",
            targetEnvironment,
            ServerType.TOMCAT,
            ManagementMode.TOMCAT_WAR_UPLOAD,
            "https://" + targetEnvironment + "-tomcat-1.example.internal/manager",
            "/orders",
            targetEnvironment + "-tomcat",
            true))
        .block();
    releaseCatalogStore.saveArtifact(ReleaseArtifact.create(
            "artifact-" + targetEnvironment + "-1",
            "orders",
            targetEnvironment,
            ArtifactType.WAR,
            "sha256:abc123",
            "orders.war",
            "artifact-" + targetEnvironment + "-1.war",
            3,
            "admin",
            "OPERATOR_UPLOAD",
            true))
        .block();
  }

  private void seedLibertyScriptCatalogWithoutUploadedArtifact(String targetEnvironment) {
    seedApprovedScriptProfile();
    TargetEnvironment environment = TargetEnvironment.from(targetEnvironment);
    releaseCatalogStore.saveApplication(com.company.opsagent.controlplane.modules.release.ReleaseApplication.create(
            "orders",
            "璁㈠崟鏈嶅姟",
            ArtifactType.WAR,
            "/health",
            true))
        .block();
    releaseCatalogStore.saveEnvironmentPolicy(ReleaseEnvironmentPolicy.defaultFor(environment))
        .block();
    releaseCatalogStore.saveServer(ReleaseServer.create(
            targetEnvironment + "-liberty-1",
            targetEnvironment,
            ServerType.LIBERTY,
            ManagementMode.LIBERTY_SCRIPT_PROFILE,
            "https://" + targetEnvironment + "-liberty-1.example.internal",
            "/orders",
            null,
            new com.company.opsagent.controlplane.modules.release.ReleaseScriptProfile(
                "liberty-war-deploy",
                List.of(
                    new com.company.opsagent.controlplane.modules.release.ReleaseScriptParameter("serverName", "defaultServer"),
                    new com.company.opsagent.controlplane.modules.release.ReleaseScriptParameter("applicationName", "orders"),
                    new com.company.opsagent.controlplane.modules.release.ReleaseScriptParameter(
                        "artifactPath",
                        "\\\\jenkins\\share\\orders\\latest\\orders.war"))),
            true))
        .block();
  }

  private void seedApprovedScriptProfile() {
    releaseCatalogStore.saveScriptProfileDefinition(
            com.company.opsagent.controlplane.modules.release.ReleaseScriptProfileDefinition.create(
                "liberty-war-deploy",
                "Liberty WAR deploy",
                "C:\\ops\\scripts\\liberty-war-deploy.cmd",
                "C:\\ops-agent\\work\\release",
                List.of("{{param.serverName}}", "{{param.applicationName}}", "{{param.artifactPath}}"),
                List.of(0),
                600,
                true,
                true))
        .block();
  }
}
