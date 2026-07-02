package com.company.opsagent.controlplane.bootstrap.service;

import com.company.opsagent.contracts.workflow.OperatorContext;
import com.company.opsagent.contracts.workflow.PolicyDecisionReference;
import com.company.opsagent.contracts.workflow.TraceContext;
import com.company.opsagent.contracts.workflow.WorkerRequestSignature;
import com.company.opsagent.contracts.workflow.WorkerTransportHeaders;
import com.company.opsagent.controlplane.bootstrap.config.WorkerProperties;
import com.company.opsagent.controlplane.modules.release.ReleaseArtifact;
import com.company.opsagent.controlplane.modules.release.ReleaseCatalogStore;
import com.company.opsagent.controlplane.modules.release.ReleaseNodeExecutionResult;
import com.company.opsagent.controlplane.modules.release.ReleaseNodeStep;
import com.company.opsagent.controlplane.modules.release.ReleasePlan;
import com.company.opsagent.controlplane.modules.release.ReleaseScriptProfileDefinition;
import com.company.opsagent.controlplane.modules.release.ReleaseServer;
import com.company.opsagent.controlplane.modules.release.ReleaseWorkerGateway;
import com.company.opsagent.controlplane.modules.release.ManagementMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * 发布中心调用独立 Worker 的网关。
 *
 * <p>控制面只提交已经形成发布工作流的单节点标准动作；Worker 继续负责适配器边界和目标系统执行。
 */
public class WebClientReleaseWorkerGateway implements ReleaseWorkerGateway {

  private final WebClient webClient;
  private final WorkerProperties workerProperties;
  private final ReleaseCatalogStore releaseCatalogStore;
  private final Clock clock;

  public WebClientReleaseWorkerGateway(
      WebClient webClient,
      WorkerProperties workerProperties,
      ReleaseCatalogStore releaseCatalogStore,
      Clock clock) {
    this.webClient = webClient;
    this.workerProperties = workerProperties;
    this.releaseCatalogStore = releaseCatalogStore;
    this.clock = clock;
  }

  @Override
  public Mono<ReleaseNodeExecutionResult> execute(ReleasePlan plan, ReleaseNodeStep node) {
    OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    return request(plan, node, now)
        .flatMap(request -> webClient.post()
            .uri("/internal/release/execute")
            .contentType(MediaType.APPLICATION_JSON)
            .headers(headers -> sign(headers, request))
            .bodyValue(request)
            .retrieve()
            .bodyToMono(ReleaseWorkerResult.class))
        .map(this::result);
  }

  private Mono<ReleaseWorkerRequest> request(ReleasePlan plan, ReleaseNodeStep node, OffsetDateTime now) {
    Mono<Optional<ReleaseArtifact>> artifact = plan.artifactId() == null
        ? Mono.just(Optional.empty())
        : releaseCatalogStore.findArtifact(plan.artifactId())
            .switchIfEmpty(Mono.error(new IllegalStateException("release artifact not found")))
            .map(Optional::of);
    return Mono.zip(
            artifact,
            releaseCatalogStore.findServer(node.nodeId())
                .switchIfEmpty(Mono.error(new IllegalStateException("release server not found"))))
        .flatMap(tuple -> {
          ReleaseServer server = tuple.getT2();
          if (server.managementMode() != ManagementMode.LIBERTY_SCRIPT_PROFILE) {
            return Mono.just(request(plan, node, tuple.getT1().orElse(null), server, null, now));
          }
          return releaseCatalogStore
              .findScriptProfileDefinition(server.targetEnvironment().value(), server.scriptProfile().profileId())
              .switchIfEmpty(Mono.error(new IllegalStateException("release script profile not found")))
              .filter(ReleaseScriptProfileDefinition::executable)
              .switchIfEmpty(Mono.error(new IllegalStateException("release script profile is not executable")))
              .map(definition -> request(plan, node, tuple.getT1().orElse(null), server, definition, now));
        });
  }

  private ReleaseWorkerRequest request(
      ReleasePlan plan,
      ReleaseNodeStep node,
      ReleaseArtifact artifact,
      ReleaseServer server,
      ReleaseScriptProfileDefinition scriptProfileDefinition,
      OffsetDateTime now) {
    ReleaseWorkerCommand command = new ReleaseWorkerCommand(
        "1.0",
        plan.releaseId(),
        workflowId(plan.releaseId()),
        "DEPLOY",
        plan.targetEnvironment().value(),
        plan.applicationId(),
        artifact == null
            ? null
            : new ReleaseArtifactReference(
                artifact.artifactId(),
                artifact.artifactType().name(),
                artifact.checksum(),
                artifact.storageKey()),
        List.of(new ReleaseNodeTarget(
            node.nodeId(),
            node.serverType().name(),
            node.managementMode().name(),
            server.managementEndpoint(),
            server.applicationPath(),
            server.credentialAlias(),
            scriptProfile(server.scriptProfile(), scriptProfileDefinition))),
        new OperatorContext("release-center", List.of("ROLE_ops-admin")),
        new PolicyDecisionReference(
            "release-policy:" + plan.releaseId(),
            "release-center-policy-v1",
            "ALLOW"),
        new TraceContext("trace:" + plan.releaseId(), "request:" + plan.releaseId()),
        now);
    return new ReleaseWorkerRequest(
        "1.0",
        "release-worker-" + plan.releaseId() + "-" + node.sequence(),
        now,
        now.plusSeconds(60),
        command);
  }

  private ReleaseNodeExecutionResult result(ReleaseWorkerResult result) {
    if ("SUCCEEDED".equals(result.status())) {
      return ReleaseNodeExecutionResult.succeeded();
    }
    String reason = result.errorCode() == null || result.errorCode().isBlank()
        ? result.status()
        : result.errorCode();
    return ReleaseNodeExecutionResult.failed(reason);
  }

  private void sign(HttpHeaders headers, ReleaseWorkerRequest request) {
    WorkerProperties.TransportAuth transportAuth = workerProperties.getTransportAuth();
    if (!transportAuth.isEnabled()) {
      return;
    }
    String timestamp = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).toString();
    String keyId = requireText(transportAuth.getKeyId(), "worker transport key id");
    String sharedSecret = requireText(transportAuth.getSharedSecret(), "worker transport shared secret");
    String payload = canonicalPayload(keyId, timestamp, request);
    headers.set(WorkerTransportHeaders.KEY_ID, keyId);
    headers.set(WorkerTransportHeaders.TIMESTAMP, timestamp);
    headers.set(WorkerTransportHeaders.SIGNATURE, WorkerRequestSignature.sign(sharedSecret, payload));
  }

  private String canonicalPayload(String keyId, String timestamp, ReleaseWorkerRequest request) {
    ReleaseWorkerCommand command = request.command();
    ReleaseArtifactReference artifact = command.artifact();
    return String.join("\n",
        "ops-agent-worker-signature-v1",
        "release-worker-execution-v1",
        keyId,
        timestamp,
        value(request.contractVersion()),
        value(request.executionRequestId()),
        value(request.authorizedAt()),
        value(request.expiresAt()),
        value(command.contractVersion()),
        value(command.releaseId()),
        value(command.workflowId()),
        value(command.operation()),
        value(command.targetEnvironment()),
        value(command.applicationId()),
        value(artifact == null ? null : artifact.artifactId()),
        value(artifact == null ? null : artifact.type()),
        value(artifact == null ? null : artifact.checksum()),
        value(artifact == null ? null : artifact.storageKey()),
        sha256Hex(command.nodes().toString()),
        value(command.operator().operatorId()),
        String.join(",", command.operator().roles()),
        value(command.policyDecision().decisionId()),
        value(command.policyDecision().policyVersion()),
        value(command.policyDecision().decision()),
        value(command.trace().traceId()),
        value(command.trace().requestId()));
  }

  private String workflowId(String releaseId) {
    return java.util.UUID.nameUUIDFromBytes(releaseId.getBytes(StandardCharsets.UTF_8)).toString();
  }

  private ReleaseScriptProfile scriptProfile(
      com.company.opsagent.controlplane.modules.release.ReleaseScriptProfile scriptProfile,
      ReleaseScriptProfileDefinition scriptProfileDefinition) {
    if (scriptProfile == null) {
      return null;
    }
    return new ReleaseScriptProfile(
        scriptProfile.profileId(),
        scriptProfile.parameters().stream()
            .map(this::scriptParameter)
            .toList(),
        scriptProfileDefinition(scriptProfileDefinition));
  }

  private ReleaseScriptProfileDefinitionPayload scriptProfileDefinition(
      ReleaseScriptProfileDefinition definition) {
    if (definition == null) {
      return null;
    }
    return new ReleaseScriptProfileDefinitionPayload(
        definition.executablePath(),
        definition.arguments(),
        definition.requiredParameters(),
        definition.allowedParameters(),
        definition.successExitCodes(),
        definition.timeoutSeconds(),
        definition.workingDirectory(),
        List.of(definition.targetEnvironment().value()),
        definition.approved(),
        definition.enabled());
  }

  private ReleaseScriptParameter scriptParameter(
      com.company.opsagent.controlplane.modules.release.ReleaseScriptParameter parameter) {
    return new ReleaseScriptParameter(parameter.name(), parameter.value());
  }

  private String sha256Hex(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder builder = new StringBuilder(bytes.length * 2);
      for (byte current : bytes) {
        builder.append(String.format("%02x", current));
      }
      return builder.toString();
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is not available", exception);
    }
  }

  private String requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + " is required when worker transport auth is enabled");
    }
    return value;
  }

  private String value(Object value) {
    return value == null ? "" : value.toString();
  }

  private record ReleaseWorkerRequest(
      String contractVersion,
      String executionRequestId,
      OffsetDateTime authorizedAt,
      OffsetDateTime expiresAt,
      ReleaseWorkerCommand command) {
  }

  private record ReleaseWorkerCommand(
      String contractVersion,
      String releaseId,
      String workflowId,
      String operation,
      String targetEnvironment,
      String applicationId,
      ReleaseArtifactReference artifact,
      List<ReleaseNodeTarget> nodes,
      OperatorContext operator,
      PolicyDecisionReference policyDecision,
      TraceContext trace,
      OffsetDateTime requestedAt) {
  }

  private record ReleaseArtifactReference(String artifactId, String type, String checksum, String storageKey) {
  }

  private record ReleaseNodeTarget(
      String nodeId,
      String serverType,
      String managementMode,
      String managementEndpoint,
      String applicationPath,
      String credentialAlias,
      ReleaseScriptProfile scriptProfile) {
  }

  private record ReleaseScriptProfile(
      String profileId,
      List<ReleaseScriptParameter> parameters,
      ReleaseScriptProfileDefinitionPayload definition) {
  }

  private record ReleaseScriptProfileDefinitionPayload(
      String executablePath,
      List<String> arguments,
      List<String> requiredParameters,
      List<String> allowedParameters,
      List<Integer> successExitCodes,
      int timeoutSeconds,
      String workingDirectory,
      List<String> targetEnvironments,
      boolean approved,
      boolean enabled) {
  }

  private record ReleaseScriptParameter(
      String name,
      String value) {
  }

  private record ReleaseWorkerResult(
      String contractVersion,
      String executionRequestId,
      String releaseId,
      String workflowId,
      String status,
      List<ReleaseNodeResult> nodeResults,
      String errorCode,
      String errorMessage,
      OffsetDateTime completedAt) {
  }

  private record ReleaseNodeResult(
      String nodeId,
      String status,
      String serverType,
      String managementMode,
      String errorCode,
      String errorMessage,
      OffsetDateTime startedAt,
      OffsetDateTime completedAt) {
  }
}
