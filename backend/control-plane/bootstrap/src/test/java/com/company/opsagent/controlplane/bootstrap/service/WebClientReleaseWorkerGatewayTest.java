package com.company.opsagent.controlplane.bootstrap.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.company.opsagent.controlplane.bootstrap.config.WorkerProperties;
import com.company.opsagent.controlplane.modules.release.ArtifactType;
import com.company.opsagent.controlplane.modules.release.ManagementMode;
import com.company.opsagent.controlplane.modules.release.ReleaseApplication;
import com.company.opsagent.controlplane.modules.release.ReleaseArtifact;
import com.company.opsagent.controlplane.modules.release.ReleaseCatalogStore;
import com.company.opsagent.controlplane.modules.release.ReleaseCredential;
import com.company.opsagent.controlplane.modules.release.ReleaseEnvironmentPolicy;
import com.company.opsagent.controlplane.modules.release.ReleaseNodeStatus;
import com.company.opsagent.controlplane.modules.release.ReleaseNodeStep;
import com.company.opsagent.controlplane.modules.release.ReleasePlan;
import com.company.opsagent.controlplane.modules.release.ReleaseRequestContext;
import com.company.opsagent.controlplane.modules.release.ReleaseScriptProfileDefinition;
import com.company.opsagent.controlplane.modules.release.ReleaseServer;
import com.company.opsagent.controlplane.modules.release.ReleaseStatus;
import com.company.opsagent.controlplane.modules.release.ServerType;
import com.company.opsagent.controlplane.modules.release.TargetEnvironment;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class WebClientReleaseWorkerGatewayTest {

  private static final Instant SIGNED_AT = Instant.parse("2026-07-05T10:15:30Z");

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void sendsUuidExecutionRequestIdToReleaseWorker() throws Exception {
    AtomicReference<ClientRequest> captured = new AtomicReference<>();
    WebClientReleaseWorkerGateway gateway = new WebClientReleaseWorkerGateway(
        webClient(captured),
        workerProperties(),
        releaseCatalogStore(),
        Clock.fixed(SIGNED_AT, ZoneOffset.UTC));

    gateway.executeWithEvents(plan(), node()).collectList().block();

    ClientRequest sent = captured.get();
    assertNotNull(sent);
    assertEquals("/internal/release/execute/events", sent.url().getPath());
    String body = body(sent);
    String executionRequestId = objectMapper.readTree(body).path("executionRequestId").asText();
    assertDoesNotThrow(() -> UUID.fromString(executionRequestId));
  }

  @Test
  void sendsRealContextAndIdempotencyKeyToReleaseWorker() throws Exception {
    AtomicReference<ClientRequest> captured = new AtomicReference<>();
    WebClientReleaseWorkerGateway gateway = new WebClientReleaseWorkerGateway(
        webClient(captured),
        workerProperties(),
        releaseCatalogStore(),
        Clock.fixed(SIGNED_AT, ZoneOffset.UTC));
    ReleaseRequestContext context = new ReleaseRequestContext(
        "alice",
        List.of("ROLE_ops-release"),
        "request-123:release.plan.execute",
        "rbac-v1",
        "trace-123",
        "request-123");

    gateway.executeWithEvents(plan(), node(), context).collectList().block();

    var command = objectMapper.readTree(body(captured.get())).path("command");
    assertEquals("release:rel-1:node:1", command.path("idempotencyKey").asText());
    assertEquals("alice", command.path("operator").path("operatorId").asText());
    assertEquals(List.of("ROLE_ops-release"), objectMapper.convertValue(
        command.path("operator").path("roles"),
        objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)));
    assertEquals("request-123:release.plan.execute", command.path("policyDecision").path("decisionId").asText());
    assertEquals("rbac-v1", command.path("policyDecision").path("policyVersion").asText());
    assertEquals("trace-123", command.path("trace").path("traceId").asText());
    assertEquals("request-123", command.path("trace").path("requestId").asText());
  }

  private WebClient webClient(AtomicReference<ClientRequest> captured) {
    return WebClient.builder()
        .exchangeFunction(request -> {
          captured.set(request);
          return Mono.just(ClientResponse.create(HttpStatus.OK)
              .header("Content-Type", "text/event-stream")
              .body("""
                  event: RESULT
                  data: {"eventType":"RESULT","executionRequestId":"550e8400-e29b-41d4-a716-446655440001","releaseId":"rel-1","workflowId":"550e8400-e29b-41d4-a716-446655440000","timestamp":"2026-07-05T10:15:31Z","result":{"contractVersion":"1.0","executionRequestId":"550e8400-e29b-41d4-a716-446655440001","releaseId":"rel-1","workflowId":"550e8400-e29b-41d4-a716-446655440000","status":"SUCCEEDED","nodeResults":[],"completedAt":"2026-07-05T10:15:31Z"}}

                  """)
              .build());
        })
        .build();
  }

  private String body(ClientRequest request) {
    MockClientHttpRequest mockRequest = new MockClientHttpRequest(request.method(), request.url());
    request.writeTo(mockRequest, ExchangeStrategies.withDefaults()).block();
    return mockRequest.getBodyAsString().block();
  }

  private WorkerProperties workerProperties() {
    WorkerProperties properties = new WorkerProperties();
    properties.getTransportAuth().setEnabled(false);
    return properties;
  }

  private ReleaseCatalogStore releaseCatalogStore() {
    return new ReleaseCatalogStore() {
      @Override
      public Mono<ReleaseApplication> saveApplication(ReleaseApplication application) {
        return Mono.error(new UnsupportedOperationException());
      }

      @Override
      public Flux<ReleaseApplication> listApplications() {
        return Flux.error(new UnsupportedOperationException());
      }

      @Override
      public Mono<ReleaseEnvironmentPolicy> saveEnvironmentPolicy(ReleaseEnvironmentPolicy policy) {
        return Mono.error(new UnsupportedOperationException());
      }

      @Override
      public Mono<ReleaseEnvironmentPolicy> findEnvironmentPolicy(TargetEnvironment targetEnvironment) {
        return Mono.error(new UnsupportedOperationException());
      }

      @Override
      public Mono<ReleaseServer> saveServer(ReleaseServer server) {
        return Mono.error(new UnsupportedOperationException());
      }

      @Override
      public Mono<ReleaseServer> findServer(String nodeId) {
        return Mono.just(server());
      }

      @Override
      public Flux<ReleaseServer> listServers(String targetEnvironment) {
        return Flux.error(new UnsupportedOperationException());
      }

      @Override
      public Mono<Void> deleteServer(String nodeId) {
        return Mono.error(new UnsupportedOperationException());
      }

      @Override
      public Mono<ReleaseScriptProfileDefinition> saveScriptProfileDefinition(ReleaseScriptProfileDefinition profile) {
        return Mono.error(new UnsupportedOperationException());
      }

      @Override
      public Mono<ReleaseScriptProfileDefinition> findScriptProfileDefinition(String profileId) {
        return Mono.error(new UnsupportedOperationException());
      }

      @Override
      public Flux<ReleaseScriptProfileDefinition> listScriptProfileDefinitions() {
        return Flux.error(new UnsupportedOperationException());
      }

      @Override
      public Mono<Void> deleteScriptProfileDefinition(String profileId) {
        return Mono.error(new UnsupportedOperationException());
      }

      @Override
      public Mono<ReleaseArtifact> saveArtifact(ReleaseArtifact artifact) {
        return Mono.error(new UnsupportedOperationException());
      }

      @Override
      public Flux<ReleaseArtifact> listArtifacts(String targetEnvironment) {
        return Flux.error(new UnsupportedOperationException());
      }

      @Override
      public Mono<ReleaseArtifact> findArtifact(String artifactId) {
        return Mono.just(artifact());
      }

      @Override
      public Mono<ReleaseCredential> saveCredential(ReleaseCredential credential) {
        return Mono.error(new UnsupportedOperationException());
      }

      @Override
      public Mono<ReleaseCredential> findCredential(String credentialAlias) {
        return Mono.error(new UnsupportedOperationException());
      }

      @Override
      public Mono<ReleasePlan> savePlan(ReleasePlan plan) {
        return Mono.error(new UnsupportedOperationException());
      }

      @Override
      public Mono<ReleasePlan> findPlan(String releaseId) {
        return Mono.error(new UnsupportedOperationException());
      }

      @Override
      public Flux<ReleasePlan> listPlans() {
        return Flux.error(new UnsupportedOperationException());
      }
    };
  }

  private ReleasePlan plan() {
    return new ReleasePlan(
        "rel-1",
        "orders",
        TargetEnvironment.DEV,
        "artifact-1",
        ReleaseStatus.RUNNING,
        List.of(node()),
        "sha256:abc123",
        null,
        true,
        SIGNED_AT,
        SIGNED_AT);
  }

  private ReleaseNodeStep node() {
    return new ReleaseNodeStep(
        "node-1",
        ServerType.TOMCAT,
        ManagementMode.TOMCAT_WAR_UPLOAD,
        1,
        ReleaseNodeStatus.RUNNING,
        null,
        SIGNED_AT,
        null);
  }

  private ReleaseArtifact artifact() {
    return ReleaseArtifact.create(
        "artifact-1",
        "orders",
        "dev",
        ArtifactType.WAR,
        "sha256:abc123",
        "orders.war",
        "artifacts/orders.war",
        1234,
        "operator-1",
        "UPLOAD",
        true);
  }

  private ReleaseServer server() {
    return ReleaseServer.create(
        "node-1",
        "dev",
        ServerType.TOMCAT,
        ManagementMode.TOMCAT_WAR_UPLOAD,
        "https://tomcat.example.local:8443",
        "/orders",
        "tomcat-dev",
        true);
  }
}
