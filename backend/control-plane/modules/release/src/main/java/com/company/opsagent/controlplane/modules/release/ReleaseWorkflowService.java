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
import java.util.function.Function;
import java.util.stream.IntStream;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class ReleaseWorkflowService {

  private final ReleaseWorkerGateway workerGateway;
  private final Clock clock;
  private final ReleaseEventSink eventSink;
  private final Function<ReleasePlan, Mono<ReleasePlan>> planStateSink;
  private final ConcurrentMap<String, AtomicLong> eventSequences = new ConcurrentHashMap<>();

  public ReleaseWorkflowService(ReleaseWorkerGateway workerGateway, Clock clock) {
    this(workerGateway, clock, ReleaseEventSink.noop());
  }

  public ReleaseWorkflowService(ReleaseWorkerGateway workerGateway, Clock clock, ReleaseEventSink eventSink) {
    this(workerGateway, clock, eventSink, Mono::just);
  }

  public ReleaseWorkflowService(
      ReleaseWorkerGateway workerGateway,
      Clock clock,
      ReleaseEventSink eventSink,
      Function<ReleasePlan, Mono<ReleasePlan>> planStateSink) {
    this.workerGateway = ReleaseValues.required(workerGateway, "workerGateway");
    this.clock = ReleaseValues.required(clock, "clock");
    this.eventSink = ReleaseValues.required(eventSink, "eventSink");
    this.planStateSink = ReleaseValues.required(planStateSink, "planStateSink");
  }

  public Mono<ReleasePlan> createPlan(
      String releaseId,
      String applicationId,
      String targetEnvironment,
      String artifactId,
      List<ReleaseServer> servers,
      ReleaseEnvironmentPolicy policy,
      String parametersHash) {
    return createPlan(
        releaseId,
        applicationId,
        targetEnvironment,
        artifactId,
        servers,
        policy,
        parametersHash,
        ReleaseRequestContext.system(releaseId));
  }

  public Mono<ReleasePlan> createPlan(
      String releaseId,
      String applicationId,
      String targetEnvironment,
      String artifactId,
      List<ReleaseServer> servers,
      ReleaseEnvironmentPolicy policy,
      String parametersHash,
      ReleaseRequestContext context) {
    return Mono.fromSupplier(() -> {
      ReleaseValues.required(context, "context");
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
    }).flatMap(plan -> publishCreated(plan, context));
  }

  public Mono<ReleasePlan> confirm(ReleasePlan plan, ReleaseConfirmation confirmation) {
    ReleasePlan releasePlan = ReleaseValues.required(plan, "plan");
    return confirm(releasePlan, confirmation, ReleaseRequestContext.system(releasePlan.releaseId()));
  }

  public Mono<ReleasePlan> confirm(
      ReleasePlan plan,
      ReleaseConfirmation confirmation,
      ReleaseRequestContext context) {
    return Mono.fromSupplier(() -> {
      ReleasePlan releasePlan = ReleaseValues.required(plan, "plan");
      ReleaseConfirmation releaseConfirmation = ReleaseValues.required(confirmation, "confirmation");
      ReleaseValues.required(context, "context");
      if (releasePlan.status() != ReleaseStatus.WAIT_CONFIRM) {
        throw new ReleaseWorkflowException("RELEASE_CONFIRMATION_NOT_REQUIRED", "release is not waiting for confirmation");
      }
      if (!Objects.equals(releasePlan.parametersHash(), releaseConfirmation.parametersHash())) {
        throw new ReleaseWorkflowException(
            "RELEASE_CONFIRMATION_HASH_MISMATCH",
            "release confirmation parameters hash does not match");
      }
      return releasePlan.withConfirmation(releaseConfirmation, ReleaseStatus.READY, Instant.now(clock));
    }).flatMap(confirmed -> persist(confirmed)
        .flatMap(saved -> publish(
            saved,
            ReleaseEventType.RELEASE_CONFIRMED,
            new ReleaseEventPayload.Confirmed(
                confirmation.confirmationId(),
                confirmation.confirmedBy(),
            confirmation.confirmedAt(),
            confirmation.parametersHash()),
            "SUCCEEDED",
            "release confirmed",
            context).thenReturn(saved)));
  }

  public Mono<ReleasePlan> execute(ReleasePlan plan) {
    ReleasePlan releasePlan = ReleaseValues.required(plan, "plan");
    return execute(releasePlan, ReleaseRequestContext.system(releasePlan.releaseId()));
  }

  public Mono<ReleasePlan> execute(ReleasePlan plan, ReleaseRequestContext context) {
    return Mono.defer(() -> {
      ReleasePlan releasePlan = ReleaseValues.required(plan, "plan");
      ReleaseValues.required(context, "context");
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
      return persist(releasePlan.withStatus(ReleaseStatus.RUNNING, Instant.now(clock)))
          .flatMap(saved -> executeNode(saved, 0, context));
    });
  }

  private Mono<ReleasePlan> executeNode(ReleasePlan plan, int index, ReleaseRequestContext context) {
    if (index >= plan.nodes().size()) {
      ReleaseStatus terminalStatus = plan.hasFailedNodes() ? ReleaseStatus.PARTIAL_FAILED : ReleaseStatus.SUCCEEDED;
      return persist(plan.withStatus(terminalStatus, Instant.now(clock)));
    }

    ReleaseNodeStep runningNode = plan.nodes().get(index).markRunning(Instant.now(clock));
    ReleasePlan runningPlan = plan.withNode(index, runningNode, ReleaseStatus.RUNNING, Instant.now(clock));
    return persist(runningPlan)
        .flatMap(savedPlan -> publish(
            savedPlan,
            ReleaseEventType.RELEASE_NODE_STARTED,
            new ReleaseEventPayload.NodeStarted(
                runningNode.nodeId(),
                runningNode.serverType(),
                runningNode.managementMode(),
                runningNode.startedAt()),
            "STARTED",
            "release node started",
            context)
            .thenMany(workerGateway.executeWithEvents(savedPlan, runningNode, context)
                .concatMap(event -> handleExecutionEvent(savedPlan, runningNode, event, context)))
            .last(ReleaseNodeExecutionResult.failed("RELEASE_WORKER_EMPTY_RESULT"))
            .onErrorResume(error -> Mono.just(ReleaseNodeExecutionResult.failed("RELEASE_WORKER_ERROR")))
            .flatMap(result -> applyNodeResult(savedPlan, runningNode, result, index, context)));
  }

  private Flux<ReleaseNodeExecutionResult> handleExecutionEvent(
      ReleasePlan runningPlan,
      ReleaseNodeStep runningNode,
      ReleaseNodeExecutionEvent event,
      ReleaseRequestContext context) {
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
        "release node script output",
        context)
        .thenMany(Flux.empty());
  }

  private Mono<ReleasePlan> applyNodeResult(
      ReleasePlan runningPlan,
      ReleaseNodeStep runningNode,
      ReleaseNodeExecutionResult result,
      int index,
      ReleaseRequestContext context) {
    Instant finishedAt = Instant.now(clock);
    if (result.successful()) {
      ReleasePlan succeededPlan = runningPlan.withNode(
          index,
          runningNode.markSucceeded(finishedAt),
          ReleaseStatus.RUNNING,
          finishedAt);
      return persist(succeededPlan)
          .flatMap(savedPlan -> publish(
              savedPlan,
              ReleaseEventType.RELEASE_NODE_COMPLETED,
              new ReleaseEventPayload.NodeCompleted(runningNode.nodeId(), "SUCCEEDED", finishedAt),
              "SUCCEEDED",
              "release node completed",
              context)
              .then(executeNode(savedPlan, index + 1, context)));
    }

    ReleasePlan failedPlan = runningPlan.withNode(
        index,
        runningNode.markFailed(result.reason(), finishedAt),
        ReleaseStatus.PARTIAL_FAILED,
        finishedAt);
    Mono<Void> failedEvent = persist(failedPlan)
        .flatMap(savedPlan -> publish(
            savedPlan,
            ReleaseEventType.RELEASE_NODE_FAILED,
            new ReleaseEventPayload.NodeFailed(runningNode.nodeId(), result.reason(), result.reason(), finishedAt),
            "FAILED",
            result.reason(),
            context));
    if (failedPlan.stopOnNodeFailure()) {
      ReleasePlan stoppedPlan = failedPlan.skipNodesAfter(index, finishedAt);
      return failedEvent
          .then(persist(stoppedPlan))
          .flatMap(savedPlan -> publish(
              savedPlan,
              ReleaseEventType.RELEASE_PARTIAL_FAILED,
              new ReleaseEventPayload.PartialFailed(
                  runningNode.nodeId(),
                  completedNodeIds(savedPlan),
              "release stopped after node failure"),
              "FAILED",
              "release stopped after node failure",
              context)
          .then(publish(
              savedPlan,
              ReleaseEventType.RELEASE_MANUAL_INTERVENTION_REQUIRED,
              new ReleaseEventPayload.ManualInterventionRequired(
                  "release requires manual intervention after node failure",
                  lastCompletedNodeId(savedPlan),
                  runningNode.nodeId(),
                  "review deterministic checks and decide rollback or retry"),
              "MANUAL_INTERVENTION_REQUIRED",
              "release requires manual intervention",
              context))
          .thenReturn(savedPlan));
    }
    return failedEvent
        .then(persist(failedPlan.withStatus(ReleaseStatus.RUNNING, finishedAt)))
        .flatMap(savedPlan -> executeNode(savedPlan, index + 1, context));
  }

  private Mono<ReleasePlan> publishCreated(ReleasePlan plan, ReleaseRequestContext context) {
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
            context.operatorId(),
            context.policyDecisionId()),
        "CREATED",
        "release plan created",
        context)
        .thenReturn(plan);
  }

  private Mono<Void> publish(
      ReleasePlan plan,
      ReleaseEventType type,
      ReleaseEventPayload payload,
      String result,
      String reason,
      ReleaseRequestContext context) {
    return nextSequence(plan.releaseId())
        .flatMap(sequence -> {
          ReleaseWorkflowEvent event = new ReleaseWorkflowEvent(
              "1.0",
              UUID.randomUUID().toString(),
              workflowId(plan.releaseId()),
              plan.releaseId(),
              sequence,
              Instant.now(clock),
              type,
              payload,
              audit(plan, type, result, reason, context));
          return eventSink.publish(event);
        });
  }

  private Mono<ReleasePlan> persist(ReleasePlan plan) {
    Mono<ReleasePlan> savedPlan = ReleaseValues.required(planStateSink.apply(plan), "planStateSink result");
    return savedPlan.thenReturn(plan);
  }

  private ReleaseAuditContext audit(
      ReleasePlan plan,
      ReleaseEventType type,
      String result,
      String reason,
      ReleaseRequestContext context) {
    return new ReleaseAuditContext(
        type.name(),
        "release:" + plan.releaseId(),
        context.policyVersion(),
        result,
        reason,
        context.traceId(),
        context.requestId());
  }

  private Mono<Long> nextSequence(String releaseId) {
    return eventSink.nextSequence(releaseId)
        .switchIfEmpty(Mono.fromSupplier(
            () -> eventSequences.computeIfAbsent(releaseId, ignored -> new AtomicLong(1)).getAndIncrement()));
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
