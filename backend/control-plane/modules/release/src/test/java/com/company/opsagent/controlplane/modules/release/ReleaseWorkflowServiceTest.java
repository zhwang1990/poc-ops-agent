package com.company.opsagent.controlplane.modules.release;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ReleaseWorkflowServiceTest {

  private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-01T00:00:00Z"), ZoneOffset.UTC);

  @Test
  void createSitPlanWaitsForSecondConfirmation() {
    ReleaseWorkflowService service = service((plan, node) -> Mono.just(ReleaseNodeExecutionResult.succeeded()));

    ReleasePlan plan = service.createPlan(
        "rel-1",
        "orders",
        "sit",
        "artifact-1",
        List.of(server("node-1", "sit")),
        ReleaseEnvironmentPolicy.defaultFor(TargetEnvironment.SIT),
        "sha256:abc123")
        .block();

    assertEquals(ReleaseStatus.WAIT_CONFIRM, plan.status());
    assertEquals(ReleaseNodeStatus.PENDING, plan.nodes().get(0).status());
  }

  @Test
  void createLibertyScriptPlanDoesNotRequireArtifact() {
    InMemoryReleaseEventSink eventSink = new InMemoryReleaseEventSink();
    ReleaseWorkflowService service = new ReleaseWorkflowService(
        (plan, node) -> Mono.just(ReleaseNodeExecutionResult.succeeded()),
        CLOCK,
        eventSink);

    ReleasePlan plan = service.createPlan(
        "rel-1",
        "orders",
        "sit",
        null,
        List.of(libertyScriptServer("node-1", "sit")),
        ReleaseEnvironmentPolicy.defaultFor(TargetEnvironment.SIT),
        "sha256:abc123")
        .block();

    assertEquals(null, plan.artifactId());
    assertFalse("sha256:abc123".equals(plan.parametersHash()));
    assertEquals(ReleaseStatus.WAIT_CONFIRM, plan.status());
    assertEquals(ManagementMode.LIBERTY_SCRIPT_PROFILE, plan.nodes().get(0).managementMode());
    ReleaseEventPayload.Created payload = assertInstanceOf(
        ReleaseEventPayload.Created.class,
        eventSink.events().getFirst().payload());
    assertEquals("SCRIPT_PROFILE", payload.artifactType());
    assertEquals(plan.parametersHash(), payload.artifactChecksum());
  }

  @Test
  void publishesReleaseCreatedEventWithRequestContext() {
    InMemoryReleaseEventSink eventSink = new InMemoryReleaseEventSink();
    ReleaseWorkflowService service = new ReleaseWorkflowService(
        (plan, node) -> Mono.just(ReleaseNodeExecutionResult.succeeded()),
        CLOCK,
        eventSink);
    ReleaseRequestContext context = new ReleaseRequestContext(
        "alice",
        List.of("ROLE_ops-release"),
        "request-123:release.plan.create",
        "rbac-v1",
        "trace-123",
        "request-123");

    service.createPlan(
        "rel-1",
        "orders",
        "dev",
        "artifact-1",
        List.of(server("node-1", "dev")),
        ReleaseEnvironmentPolicy.defaultFor(TargetEnvironment.DEV).requireConfirmation(false),
        "sha256:abc123",
        context)
        .block();

    ReleaseWorkflowEvent event = eventSink.events().getFirst();
    ReleaseEventPayload.Created payload = assertInstanceOf(ReleaseEventPayload.Created.class, event.payload());
    assertEquals("alice", payload.operatorId());
    assertEquals("request-123:release.plan.create", payload.policyDecisionId());
    assertEquals("rbac-v1", event.audit().policyVersion());
    assertEquals("trace-123", event.audit().traceId());
    assertEquals("request-123", event.audit().requestId());
  }

  @Test
  void createLibertyScriptPlanRequiresSharedArtifactPathParameter() {
    ReleaseWorkflowService service = service((plan, node) -> Mono.just(ReleaseNodeExecutionResult.succeeded()));

    StepVerifier.create(service.createPlan(
            "rel-1",
            "orders",
            "sit",
            null,
            List.of(libertyScriptServerWithoutArtifactPath("node-1", "sit")),
            ReleaseEnvironmentPolicy.defaultFor(TargetEnvironment.SIT),
            "sha256:abc123"))
        .expectErrorSatisfies(error -> {
          ReleaseWorkflowException exception = assertInstanceOf(ReleaseWorkflowException.class, error);
          assertEquals("RELEASE_SCRIPT_ARTIFACT_PATH_REQUIRED", exception.code());
        })
        .verify();
  }

  @Test
  void createTomcatPlanStillRequiresArtifact() {
    ReleaseWorkflowService service = service((plan, node) -> Mono.just(ReleaseNodeExecutionResult.succeeded()));

    StepVerifier.create(service.createPlan(
            "rel-1",
            "orders",
            "dev",
            null,
            List.of(server("node-1", "dev")),
            ReleaseEnvironmentPolicy.defaultFor(TargetEnvironment.DEV),
            "sha256:abc123"))
        .expectErrorSatisfies(error -> {
          ReleaseWorkflowException exception = assertInstanceOf(ReleaseWorkflowException.class, error);
          assertEquals("RELEASE_ARTIFACT_REQUIRED", exception.code());
        })
        .verify();
  }

  @Test
  void confirmRejectsMismatchedParametersHashWithStableErrorCode() {
    ReleaseWorkflowService service = service((plan, node) -> Mono.just(ReleaseNodeExecutionResult.succeeded()));
    ReleasePlan plan = service.createPlan(
        "rel-1",
        "orders",
        "sit",
        "artifact-1",
        List.of(server("node-1", "sit")),
        ReleaseEnvironmentPolicy.defaultFor(TargetEnvironment.SIT),
        "sha256:abc123")
        .block();

    StepVerifier.create(service.confirm(
            plan,
            new ReleaseConfirmation("confirm-1", "sha256:def456", "alice", Instant.now(CLOCK))))
        .expectErrorSatisfies(error -> {
          ReleaseWorkflowException exception = assertInstanceOf(ReleaseWorkflowException.class, error);
          assertEquals("RELEASE_CONFIRMATION_HASH_MISMATCH", exception.code());
        })
        .verify();
  }

  @Test
  void executeStopsRemainingNodesAfterNodeFailure() {
    AtomicInteger calls = new AtomicInteger();
    ReleaseWorkflowService service = service((plan, node) -> {
      int call = calls.incrementAndGet();
      if (call == 2) {
        return Mono.just(ReleaseNodeExecutionResult.failed("NODE_HEALTHCHECK_FAILED"));
      }
      return Mono.just(ReleaseNodeExecutionResult.succeeded());
    });
    ReleaseEnvironmentPolicy policy = ReleaseEnvironmentPolicy.defaultFor(TargetEnvironment.DEV)
        .requireConfirmation(false);
    ReleasePlan plan = service.createPlan(
        "rel-1",
        "orders",
        "dev",
        "artifact-1",
        List.of(server("node-1", "dev"), server("node-2", "dev"), server("node-3", "dev")),
        policy,
        "sha256:abc123")
        .block();

    ReleasePlan result = service.execute(plan).block();

    assertEquals(ReleaseStatus.PARTIAL_FAILED, result.status());
    assertEquals(ReleaseNodeStatus.SUCCEEDED, result.nodes().get(0).status());
    assertEquals(ReleaseNodeStatus.FAILED, result.nodes().get(1).status());
    assertEquals(ReleaseNodeStatus.SKIPPED, result.nodes().get(2).status());
    assertEquals(2, calls.get());
  }

  @Test
  void persistsRunningNodeAndTerminalStateDuringExecution() {
    List<ReleasePlan> savedPlans = new ArrayList<>();
    ReleaseWorkflowService service = new ReleaseWorkflowService(
        (plan, node) -> Mono.just(ReleaseNodeExecutionResult.succeeded()),
        CLOCK,
        new InMemoryReleaseEventSink(),
        plan -> {
          savedPlans.add(plan);
          return Mono.just(plan);
        });
    ReleaseEnvironmentPolicy policy = ReleaseEnvironmentPolicy.defaultFor(TargetEnvironment.DEV)
        .requireConfirmation(false);
    ReleasePlan plan = service.createPlan(
        "rel-1",
        "orders",
        "dev",
        "artifact-1",
        List.of(server("node-1", "dev")),
        policy,
        "sha256:abc123")
        .block();

    service.execute(plan).block();

    assertTrue(savedPlans.stream().anyMatch(saved ->
        saved.status() == ReleaseStatus.RUNNING
            && saved.nodes().get(0).status() == ReleaseNodeStatus.RUNNING));
    assertTrue(savedPlans.stream().anyMatch(saved ->
        saved.status() == ReleaseStatus.RUNNING
            && saved.nodes().get(0).status() == ReleaseNodeStatus.SUCCEEDED));
    assertEquals(ReleaseStatus.SUCCEEDED, savedPlans.getLast().status());
    assertEquals(ReleaseNodeStatus.SUCCEEDED, savedPlans.getLast().nodes().get(0).status());
  }

  @Test
  void publishesReleaseWorkflowEventsWithoutCredentialMaterial() {
    InMemoryReleaseEventSink eventSink = new InMemoryReleaseEventSink();
    AtomicInteger calls = new AtomicInteger();
    ReleaseWorkflowService service = new ReleaseWorkflowService((plan, node) -> {
      int call = calls.incrementAndGet();
      if (call == 2) {
        return Mono.just(ReleaseNodeExecutionResult.failed("NODE_HEALTHCHECK_FAILED"));
      }
      return Mono.just(ReleaseNodeExecutionResult.succeeded());
    }, CLOCK, eventSink);
    ReleasePlan created = service.createPlan(
        "rel-1",
        "orders",
        "sit",
        "artifact-1",
        List.of(server("node-1", "sit"), server("node-2", "sit")),
        ReleaseEnvironmentPolicy.defaultFor(TargetEnvironment.SIT),
        "sha256:abc123")
        .block();
    ReleasePlan confirmed = service.confirm(
        created,
        new ReleaseConfirmation("confirm-1", "sha256:abc123", "alice", Instant.now(CLOCK)))
        .block();

    service.execute(confirmed).block();

    assertEquals(List.of(
        ReleaseEventType.RELEASE_CREATED,
        ReleaseEventType.RELEASE_CONFIRMED,
        ReleaseEventType.RELEASE_NODE_STARTED,
        ReleaseEventType.RELEASE_NODE_COMPLETED,
        ReleaseEventType.RELEASE_NODE_STARTED,
        ReleaseEventType.RELEASE_NODE_FAILED,
        ReleaseEventType.RELEASE_PARTIAL_FAILED,
        ReleaseEventType.RELEASE_MANUAL_INTERVENTION_REQUIRED), eventSink.events().stream()
            .map(ReleaseWorkflowEvent::type)
            .toList());
    for (ReleaseWorkflowEvent event : eventSink.events()) {
      assertNotNull(event.audit());
      assertFalse(event.toString().toLowerCase().contains("credential"));
      assertFalse(event.toString().toLowerCase().contains("secret"));
      assertFalse(event.toString().toLowerCase().contains("password"));
    }
  }

  @Test
  void publishesWorkerScriptLogEventsInReleaseSequence() {
    InMemoryReleaseEventSink eventSink = new InMemoryReleaseEventSink();
    ReleaseWorkflowService service = new ReleaseWorkflowService(new ReleaseWorkerGateway() {
      @Override
      public Mono<ReleaseNodeExecutionResult> execute(ReleasePlan plan, ReleaseNodeStep node) {
        return Mono.just(ReleaseNodeExecutionResult.succeeded());
      }

      @Override
      public Flux<ReleaseNodeExecutionEvent> executeWithEvents(ReleasePlan plan, ReleaseNodeStep node) {
        return Flux.just(
            ReleaseNodeExecutionEvent.log(node.nodeId(), "STDOUT", "deploy started", Instant.now(CLOCK)),
            ReleaseNodeExecutionEvent.result(ReleaseNodeExecutionResult.succeeded()));
      }
    }, CLOCK, eventSink);
    ReleaseEnvironmentPolicy policy = ReleaseEnvironmentPolicy.defaultFor(TargetEnvironment.DEV)
        .requireConfirmation(false);
    ReleasePlan plan = service.createPlan(
        "rel-1",
        "orders",
        "dev",
        "artifact-1",
        List.of(server("node-1", "dev")),
        policy,
        "sha256:abc123")
        .block();

    service.execute(plan).block();

    assertEquals(List.of(
        ReleaseEventType.RELEASE_CREATED,
        ReleaseEventType.RELEASE_NODE_STARTED,
        ReleaseEventType.RELEASE_NODE_LOG,
        ReleaseEventType.RELEASE_NODE_COMPLETED), eventSink.events().stream()
            .map(ReleaseWorkflowEvent::type)
            .toList());
    ReleaseEventPayload.NodeLog payload = assertInstanceOf(
        ReleaseEventPayload.NodeLog.class,
        eventSink.events().get(2).payload());
    assertEquals("node-1", payload.nodeId());
    assertEquals("STDOUT", payload.stream());
    assertEquals("deploy started", payload.message());
    assertEquals(List.of(1L, 2L, 3L, 4L), eventSink.events().stream()
        .map(ReleaseWorkflowEvent::sequence)
        .toList());
  }

  private static ReleaseWorkflowService service(ReleaseWorkerGateway gateway) {
    return new ReleaseWorkflowService(gateway, CLOCK);
  }

  private static ReleaseServer server(String nodeId, String targetEnvironment) {
    return ReleaseServer.create(
        nodeId,
        targetEnvironment,
        ServerType.TOMCAT,
        ManagementMode.TOMCAT_WAR_UPLOAD,
        "https://" + nodeId + ".example",
        true);
  }

  private static ReleaseServer libertyScriptServer(String nodeId, String targetEnvironment) {
    return ReleaseServer.create(
        nodeId,
        targetEnvironment,
        ServerType.LIBERTY,
        ManagementMode.LIBERTY_SCRIPT_PROFILE,
        "https://" + nodeId + ".example",
        "/orders",
        null,
        new ReleaseScriptProfile(
            "liberty-war-deploy",
            List.of(
                new ReleaseScriptParameter("serverName", "defaultServer"),
                new ReleaseScriptParameter("applicationName", "orders"),
                new ReleaseScriptParameter("artifactPath", "\\\\jenkins\\share\\orders\\latest\\orders.war"))),
        true);
  }

  private static ReleaseServer libertyScriptServerWithoutArtifactPath(String nodeId, String targetEnvironment) {
    return ReleaseServer.create(
        nodeId,
        targetEnvironment,
        ServerType.LIBERTY,
        ManagementMode.LIBERTY_SCRIPT_PROFILE,
        "https://" + nodeId + ".example",
        "/orders",
        null,
        new ReleaseScriptProfile(
            "liberty-war-deploy",
            List.of(
                new ReleaseScriptParameter("serverName", "defaultServer"),
                new ReleaseScriptParameter("applicationName", "orders"))),
        true);
  }
}
