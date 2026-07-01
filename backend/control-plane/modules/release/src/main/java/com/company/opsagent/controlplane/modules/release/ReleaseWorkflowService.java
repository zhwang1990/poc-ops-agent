package com.company.opsagent.controlplane.modules.release;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;
import reactor.core.publisher.Mono;

public class ReleaseWorkflowService {

  private final ReleaseWorkerGateway workerGateway;
  private final Clock clock;

  public ReleaseWorkflowService(ReleaseWorkerGateway workerGateway, Clock clock) {
    this.workerGateway = ReleaseValues.required(workerGateway, "workerGateway");
    this.clock = ReleaseValues.required(clock, "clock");
  }

  public Mono<ReleasePlan> createPlan(
      String releaseId,
      String applicationId,
      String targetEnvironment,
      String artifactId,
      List<ReleaseServer> servers,
      ReleaseEnvironmentPolicy policy,
      String parametersHash) {
    return Mono.fromSupplier(() -> {
      TargetEnvironment environment = TargetEnvironment.from(targetEnvironment);
      ReleaseEnvironmentPolicy releasePolicy = ReleaseValues.required(policy, "policy");
      if (releasePolicy.targetEnvironment() != environment) {
        throw new ReleaseWorkflowException("RELEASE_POLICY_ENVIRONMENT_MISMATCH", "policy environment does not match target");
      }
      if (!releasePolicy.enabled()) {
        throw new ReleaseWorkflowException("RELEASE_ENVIRONMENT_DISABLED", "release environment is disabled");
      }
      List<ReleaseNodeStep> nodes = enabledNodes(environment, servers);
      ReleaseStatus status = releasePolicy.confirmationRequired() ? ReleaseStatus.WAIT_CONFIRM : ReleaseStatus.DRAFT;
      Instant now = Instant.now(clock);
      return new ReleasePlan(
          releaseId,
          applicationId,
          environment,
          artifactId,
          status,
          nodes,
          parametersHash,
          null,
          releasePolicy.stopOnNodeFailure(),
          now,
          now);
    });
  }

  public Mono<ReleasePlan> confirm(ReleasePlan plan, ReleaseConfirmation confirmation) {
    return Mono.fromSupplier(() -> {
      ReleasePlan releasePlan = ReleaseValues.required(plan, "plan");
      ReleaseConfirmation releaseConfirmation = ReleaseValues.required(confirmation, "confirmation");
      if (releasePlan.status() != ReleaseStatus.WAIT_CONFIRM) {
        throw new ReleaseWorkflowException("RELEASE_CONFIRMATION_NOT_REQUIRED", "release is not waiting for confirmation");
      }
      if (!Objects.equals(releasePlan.parametersHash(), releaseConfirmation.parametersHash())) {
        throw new ReleaseWorkflowException(
            "RELEASE_CONFIRMATION_HASH_MISMATCH",
            "release confirmation parameters hash does not match");
      }
      return releasePlan.withConfirmation(releaseConfirmation, ReleaseStatus.READY, Instant.now(clock));
    });
  }

  public Mono<ReleasePlan> execute(ReleasePlan plan) {
    return Mono.defer(() -> {
      ReleasePlan releasePlan = ReleaseValues.required(plan, "plan");
      if (releasePlan.status() == ReleaseStatus.WAIT_CONFIRM) {
        return Mono.error(new ReleaseWorkflowException(
            "RELEASE_CONFIRMATION_REQUIRED",
            "release is waiting for confirmation"));
      }
      if (releasePlan.status() != ReleaseStatus.DRAFT && releasePlan.status() != ReleaseStatus.READY) {
        return Mono.error(new ReleaseWorkflowException(
            "RELEASE_STATUS_NOT_EXECUTABLE",
            "release status is not executable"));
      }
      return executeNode(releasePlan.withStatus(ReleaseStatus.RUNNING, Instant.now(clock)), 0);
    });
  }

  private Mono<ReleasePlan> executeNode(ReleasePlan plan, int index) {
    if (index >= plan.nodes().size()) {
      ReleaseStatus terminalStatus = plan.hasFailedNodes() ? ReleaseStatus.PARTIAL_FAILED : ReleaseStatus.SUCCEEDED;
      return Mono.just(plan.withStatus(terminalStatus, Instant.now(clock)));
    }

    ReleaseNodeStep runningNode = plan.nodes().get(index).markRunning(Instant.now(clock));
    ReleasePlan runningPlan = plan.withNode(index, runningNode, ReleaseStatus.RUNNING, Instant.now(clock));
    return workerGateway.execute(runningPlan, runningNode)
        .switchIfEmpty(Mono.just(ReleaseNodeExecutionResult.failed("RELEASE_WORKER_EMPTY_RESULT")))
        .onErrorResume(error -> Mono.just(ReleaseNodeExecutionResult.failed("RELEASE_WORKER_ERROR")))
        .flatMap(result -> applyNodeResult(runningPlan, runningNode, result, index));
  }

  private Mono<ReleasePlan> applyNodeResult(
      ReleasePlan runningPlan,
      ReleaseNodeStep runningNode,
      ReleaseNodeExecutionResult result,
      int index) {
    Instant finishedAt = Instant.now(clock);
    if (result.successful()) {
      ReleasePlan succeededPlan = runningPlan.withNode(
          index,
          runningNode.markSucceeded(finishedAt),
          ReleaseStatus.RUNNING,
          finishedAt);
      return executeNode(succeededPlan, index + 1);
    }

    ReleasePlan failedPlan = runningPlan.withNode(
        index,
        runningNode.markFailed(result.reason(), finishedAt),
        ReleaseStatus.PARTIAL_FAILED,
        finishedAt);
    if (failedPlan.stopOnNodeFailure()) {
      return Mono.just(failedPlan.skipNodesAfter(index, finishedAt));
    }
    return executeNode(failedPlan.withStatus(ReleaseStatus.RUNNING, finishedAt), index + 1);
  }

  private static List<ReleaseNodeStep> enabledNodes(TargetEnvironment environment, List<ReleaseServer> servers) {
    List<ReleaseServer> releaseServers = List.copyOf(ReleaseValues.required(servers, "servers"));
    if (releaseServers.isEmpty()) {
      throw new ReleaseWorkflowException("RELEASE_NO_NODES", "release plan requires at least one node");
    }
    for (ReleaseServer server : releaseServers) {
      if (server.targetEnvironment() != environment) {
        throw new ReleaseWorkflowException("RELEASE_NODE_ENVIRONMENT_MISMATCH", "node environment does not match target");
      }
    }
    List<ReleaseServer> enabledServers = releaseServers.stream()
        .filter(ReleaseServer::enabled)
        .toList();
    if (enabledServers.isEmpty()) {
      throw new ReleaseWorkflowException("RELEASE_NO_ENABLED_NODES", "release plan requires at least one enabled node");
    }
    return IntStream.range(0, enabledServers.size())
        .mapToObj(index -> ReleaseNodeStep.fromServer(index + 1, enabledServers.get(index)))
        .toList();
  }
}
