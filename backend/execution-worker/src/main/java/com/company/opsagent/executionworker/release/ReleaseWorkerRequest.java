package com.company.opsagent.executionworker.release;

import com.company.opsagent.contracts.workflow.OperatorContext;
import com.company.opsagent.contracts.workflow.PolicyDecisionReference;
import com.company.opsagent.contracts.workflow.TraceContext;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record ReleaseWorkerRequest(
    String contractVersion,
    @JsonDeserialize(using = ReleaseWorkerRequest.StrictUuidDeserializer.class)
    UUID executionRequestId,
    OffsetDateTime authorizedAt,
    OffsetDateTime expiresAt,
    ReleaseCommand command) {

  public ReleaseWorkerRequest {
    if (executionRequestId == null) {
      throw new IllegalArgumentException("executionRequestId is required");
    }
  }

  static final class StrictUuidDeserializer extends JsonDeserializer<UUID> {

    @Override
    public UUID deserialize(JsonParser parser, DeserializationContext context) throws IOException {
      String value = parser.getValueAsString();
      if (value == null || value.isBlank()) {
        throw context.weirdStringException(value, UUID.class, "executionRequestId must be a UUID");
      }
      try {
        return UUID.fromString(value);
      } catch (IllegalArgumentException exception) {
        throw context.weirdStringException(value, UUID.class, "executionRequestId must be a UUID");
      }
    }
  }

  public record ReleaseCommand(
      String contractVersion,
      String releaseId,
      String workflowId,
      String idempotencyKey,
      String operation,
      String targetEnvironment,
      String applicationId,
      ReleaseArtifactReference artifact,
      List<ReleaseNodeTarget> nodes,
      OperatorContext operator,
      PolicyDecisionReference policyDecision,
      TraceContext trace,
      OffsetDateTime requestedAt) {

    public ReleaseCommand {
      idempotencyKey = requiredText(idempotencyKey, "idempotencyKey");
      if (operator == null) {
        throw new IllegalArgumentException("operator is required");
      }
      if (policyDecision == null) {
        throw new IllegalArgumentException("policyDecision is required");
      }
      if (trace == null) {
        throw new IllegalArgumentException("trace is required");
      }
    }
  }

  public record ReleaseArtifactReference(
      String artifactId,
      String type,
      String checksum,
      String storageKey) {

    public ReleaseArtifactReference(String artifactId, String type, String checksum) {
      this(artifactId, type, checksum, null);
    }
  }

  public record ReleaseNodeTarget(
      String nodeId,
      String serverType,
      String managementMode,
      String managementEndpoint,
      String applicationPath,
      String credentialAlias,
      ReleaseScriptProfile scriptProfile) {

    public ReleaseNodeTarget(String nodeId, String serverType, String managementMode) {
      this(nodeId, serverType, managementMode, null, null, null, null);
    }

    public ReleaseNodeTarget(
        String nodeId,
        String serverType,
        String managementMode,
        String managementEndpoint,
        String applicationPath,
        String credentialAlias) {
      this(nodeId, serverType, managementMode, managementEndpoint, applicationPath, credentialAlias, null);
    }
  }

  public record ReleaseScriptProfile(
      String profileId,
      List<ReleaseScriptParameter> parameters,
      ReleaseScriptProfileDefinition definition) {

    public ReleaseScriptProfile(String profileId, List<ReleaseScriptParameter> parameters) {
      this(profileId, parameters, null);
    }
  }

  public record ReleaseScriptProfileDefinition(
      String executablePath,
      List<String> arguments,
      List<Integer> successExitCodes,
      int timeoutSeconds,
      String workingDirectory,
      boolean approved,
      boolean enabled) {
  }

  public record ReleaseScriptParameter(
      String name,
      String value) {
  }

  private static String requiredText(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " is required");
    }
    return value.trim();
  }
}
