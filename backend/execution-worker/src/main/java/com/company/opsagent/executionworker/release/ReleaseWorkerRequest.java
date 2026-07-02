package com.company.opsagent.executionworker.release;

import com.company.opsagent.contracts.workflow.OperatorContext;
import com.company.opsagent.contracts.workflow.PolicyDecisionReference;
import com.company.opsagent.contracts.workflow.TraceContext;
import java.time.OffsetDateTime;
import java.util.List;

public record ReleaseWorkerRequest(
    String contractVersion,
    String executionRequestId,
    OffsetDateTime authorizedAt,
    OffsetDateTime expiresAt,
    ReleaseCommand command) {

  public record ReleaseCommand(
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
}
