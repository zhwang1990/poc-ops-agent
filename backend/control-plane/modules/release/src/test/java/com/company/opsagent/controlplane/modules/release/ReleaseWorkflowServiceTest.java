package com.company.opsagent.controlplane.modules.release;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
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
}
