package com.company.opsagent.controlplane.modules.release;

import java.time.Clock;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class ReleaseWorkflowService {

  private final ReleaseWorkerGateway workerGateway;
  private final Clock clock;
  private final ReleaseEventSink eventSink;
  private final ConcurrentMap<String, AtomicLong> eventSequences = new ConcurrentHashMap<>();

  public ReleaseWorkflowService(ReleaseWorkerGateway workerGateway, Clock clock) {
    this(workerGateway, clock, ReleaseEventSink.noop());
  }

  public ReleaseWorkflowService(ReleaseWorkerGateway workerGateway, Clock clock, ReleaseEventSink eventSink) {
    this.workerGateway = ReleaseValues.required(workerGateway, "workerGateway");
    this.clock = ReleaseValues.required(clock, "clock");
    this.eventSink = ReleaseValues.required(eventSink, "eventSink");
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
      String normalizedArtifactId = ReleaseValues.optionalText(artifactId);
      if (normalizedArtifactId == null) {
        if (!allNodesUseLibertyScriptProfile(nodes)) {
          throw new ReleaseWorkflowException("RELEASE_ARTIFACT_REQUIRED", "release artifact is required for this release mode");
        }
        requireLibertySharedArtifactPaths(servers);
      }
      String effectiveParametersHash = normalizedArtifactId == null
          ? scriptProfileParametersHash(servers)
          : requiredArtifactParametersHash(parametersHash);
      ReleaseStatus status = releasePolicy.confirmationRequired() ? ReleaseStatus.WAIT_CONFIRM : ReleaseStatus.DRAFT;
      Instant now = Instant.now(clock);
      return new ReleasePlan(
          releaseId,
          applicationId,
          environment,
          normalizedArtifactId,
          status,
          nodes,
          effectiveParametersHash,
          null,
          releasePolicy.stopOnNodeFailure(),
          now,
          now);
    }).flatMap(this::publishCreated);
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
    }).flatMap(confirmed -> publish(
        confirmed,
        ReleaseEventType.RELEASE_CONFIRMED,
        new ReleaseEventPayload.Confirmed(
            confirmation.confirmationId(),
            confirmation.confirmedBy(),
            confirmation.confirmedAt(),
            confirmation.parametersHash()),
        "SUCCEEDED",
        "release confirmed").thenReturn(confirmed));
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
    return publish(
        runningPlan,
        ReleaseEventType.RELEASE_NODE_STARTED,
        new ReleaseEventPayload.NodeStarted(
            runningNode.nodeId(),
            runningNode.serverType(),
            runningNode.managementMode(),
            runningNode.startedAt()),
        "STARTED",
        "release node started")
        .thenMany(workerGateway.executeWithEvents(runningPlan, runningNode)
            .concatMap(event -> handleExecutionEvent(runningPlan, runningNode, event)))
        .last(ReleaseNodeExecutionResult.failed("RELEASE_WORKER_EMPTY_RESULT"))
        .onErrorResume(error -> Mono.just(ReleaseNodeExecutionResult.failed("RELEASE_WORKER_ERROR")))
        .flatMap(result -> applyNodeResult(runningPlan, runningNode, result, index));
  }

  private Flux<ReleaseNodeExecutionResult> handleExecutionEvent(
      ReleasePlan runningPlan,
      ReleaseNodeStep runningNode,
      ReleaseNodeExecutionEvent event) {
    if (event.eventType() == ReleaseNodeExecutionEvent.EventType.RESULT) {
      return Flux.just(event.result());
    }
    return publish(
        runningPlan,
        ReleaseEventType.RELEASE_NODE_LOG,
        new ReleaseEventPayload.NodeLog(
            event.nodeId(),
            event.stream(),
            event.message(),
            event.emittedAt()),
        "LOG",
        "release node script output")
        .thenMany(Flux.empty());
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
      return publish(
          succeededPlan,
          ReleaseEventType.RELEASE_NODE_COMPLETED,
          new ReleaseEventPayload.NodeCompleted(runningNode.nodeId(), "SUCCEEDED", finishedAt),
          "SUCCEEDED",
          "release node completed")
          .then(executeNode(succeededPlan, index + 1));
    }

    ReleasePlan failedPlan = runningPlan.withNode(
        index,
        runningNode.markFailed(result.reason(), finishedAt),
        ReleaseStatus.PARTIAL_FAILED,
        finishedAt);
    Mono<Void> failedEvent = publish(
        failedPlan,
        ReleaseEventType.RELEASE_NODE_FAILED,
        new ReleaseEventPayload.NodeFailed(runningNode.nodeId(), result.reason(), result.reason(), finishedAt),
        "FAILED",
        result.reason());
    if (failedPlan.stopOnNodeFailure()) {
      ReleasePlan stoppedPlan = failedPlan.skipNodesAfter(index, finishedAt);
      return failedEvent
          .then(publish(
              stoppedPlan,
              ReleaseEventType.RELEASE_PARTIAL_FAILED,
              new ReleaseEventPayload.PartialFailed(
                  runningNode.nodeId(),
                  completedNodeIds(stoppedPlan),
                  "release stopped after node failure"),
              "FAILED",
              "release stopped after node failure"))
          .then(publish(
              stoppedPlan,
              ReleaseEventType.RELEASE_MANUAL_INTERVENTION_REQUIRED,
              new ReleaseEventPayload.ManualInterventionRequired(
                  "release requires manual intervention after node failure",
                  lastCompletedNodeId(stoppedPlan),
                  runningNode.nodeId(),
                  "review deterministic checks and decide rollback or retry"),
              "MANUAL_INTERVENTION_REQUIRED",
              "release requires manual intervention"))
          .thenReturn(stoppedPlan);
    }
    return failedEvent.then(executeNode(failedPlan.withStatus(ReleaseStatus.RUNNING, finishedAt), index + 1));
  }

  private Mono<ReleasePlan> publishCreated(ReleasePlan plan) {
    return publish(
        plan,
        ReleaseEventType.RELEASE_CREATED,
        new ReleaseEventPayload.Created(
            plan.applicationId(),
            plan.targetEnvironment(),
            "DEPLOY",
            plan.artifactId() == null ? "SCRIPT_PROFILE" : "WAR",
            plan.parametersHash(),
            plan.nodes().stream().map(ReleaseNodeStep::nodeId).toList(),
            "system",
            "release-policy:" + plan.targetEnvironment().value()),
        "CREATED",
        "release plan created")
        .thenReturn(plan);
  }

  private Mono<Void> publish(
      ReleasePlan plan,
      ReleaseEventType type,
      ReleaseEventPayload payload,
      String result,
      String reason) {
    ReleaseWorkflowEvent event = new ReleaseWorkflowEvent(
        "1.0",
        UUID.randomUUID().toString(),
        workflowId(plan.releaseId()),
        plan.releaseId(),
        nextSequence(plan.releaseId()),
        Instant.now(clock),
        type,
        payload,
        audit(plan, type, result, reason));
    return eventSink.publish(event);
  }

  private ReleaseAuditContext audit(ReleasePlan plan, ReleaseEventType type, String result, String reason) {
    return new ReleaseAuditContext(
        type.name(),
        "release:" + plan.releaseId(),
        "release-center-policy-v1",
        result,
        reason,
        "trace:" + plan.releaseId(),
        "request:" + plan.releaseId());
  }

  private long nextSequence(String releaseId) {
    return eventSequences.computeIfAbsent(releaseId, ignored -> new AtomicLong(1)).getAndIncrement();
  }

  public Flux<ReleaseWorkflowEvent> events(String releaseId, long afterSequence) {
    return eventSink.events(releaseId, afterSequence);
  }

  private String workflowId(String releaseId) {
    return UUID.nameUUIDFromBytes(releaseId.getBytes(StandardCharsets.UTF_8)).toString();
  }

  private List<String> completedNodeIds(ReleasePlan plan) {
    return plan.nodes().stream()
        .filter(node -> node.status() == ReleaseNodeStatus.SUCCEEDED)
        .map(ReleaseNodeStep::nodeId)
        .toList();
  }

  private String lastCompletedNodeId(ReleasePlan plan) {
    List<String> completedNodeIds = completedNodeIds(plan);
    if (completedNodeIds.isEmpty()) {
      return null;
    }
    return completedNodeIds.get(completedNodeIds.size() - 1);
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

  private static boolean allNodesUseLibertyScriptProfile(List<ReleaseNodeStep> nodes) {
    return nodes.stream()
        .allMatch(node -> node.serverType() == ServerType.LIBERTY
            && node.managementMode() == ManagementMode.LIBERTY_SCRIPT_PROFILE);
  }

  private static void requireLibertySharedArtifactPaths(List<ReleaseServer> servers) {
    List<ReleaseServer> enabledServers = servers.stream()
        .filter(ReleaseServer::enabled)
        .toList();
    for (ReleaseServer server : enabledServers) {
      String artifactPath = scriptProfileParameter(server.scriptProfile(), "artifactPath");
      if (artifactPath == null || !artifactPath.startsWith("\\\\")) {
        throw new ReleaseWorkflowException(
            "RELEASE_SCRIPT_ARTIFACT_PATH_REQUIRED",
            "Liberty script releases require artifactPath script parameter starting with \\\\");
      }
    }
  }

  private static String scriptProfileParameter(ReleaseScriptProfile scriptProfile, String name) {
    ReleaseScriptProfile profile = ReleaseValues.required(scriptProfile, "scriptProfile");
    return profile.parameters().stream()
        .filter(parameter -> parameter.name().equals(name))
        .map(ReleaseScriptParameter::value)
        .map(String::trim)
        .filter(value -> !value.isEmpty())
        .findFirst()
        .orElse(null);
  }

  private static String requiredArtifactParametersHash(String parametersHash) {
    try {
      return ReleaseValues.sha256Checksum(parametersHash);
    } catch (IllegalArgumentException exception) {
      throw new ReleaseWorkflowException(
          "RELEASE_PARAMETERS_HASH_REQUIRED",
          "release parameters hash is required for artifact releases");
    }
  }

  private static String scriptProfileParametersHash(List<ReleaseServer> servers) {
    List<ReleaseServer> enabledServers = servers.stream()
        .filter(ReleaseServer::enabled)
        .toList();
    String material = enabledServers.stream()
        .map(server -> server.nodeId() + "\n" + scriptProfileMaterial(server.scriptProfile()))
        .reduce("", (left, right) -> left + right + "\n---\n");
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return "sha256:" + HexFormat.of().formatHex(digest.digest(material.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is not available", exception);
    }
  }

  private static String scriptProfileMaterial(ReleaseScriptProfile scriptProfile) {
    ReleaseScriptProfile profile = ReleaseValues.required(scriptProfile, "scriptProfile");
    String parameters = profile.parameters().stream()
        .map(parameter -> parameter.name() + "=" + parameter.value())
        .reduce("", (left, right) -> left + right + "\n");
    return profile.profileId() + "\n" + parameters;
  }
}
