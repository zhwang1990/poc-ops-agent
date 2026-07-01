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
      String checksum) {
  }

  public record ReleaseNodeTarget(
      String nodeId,
      String serverType,
      String managementMode) {
  }
}
