package com.company.opsagent.controlplane.modules.release;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public record ReleasePlan(
    String releaseId,
    String applicationId,
    TargetEnvironment targetEnvironment,
    String artifactId,
    ReleaseStatus status,
    List<ReleaseNodeStep> nodes,
    String parametersHash,
    ReleaseConfirmation confirmation,
    boolean stopOnNodeFailure,
    Instant createdAt,
    Instant updatedAt) {

  public ReleasePlan {
    releaseId = ReleaseValues.requiredText(releaseId, "releaseId");
    applicationId = ReleaseValues.requiredText(applicationId, "applicationId");
    targetEnvironment = ReleaseValues.required(targetEnvironment, "targetEnvironment");
    artifactId = ReleaseValues.optionalText(artifactId);
    status = ReleaseValues.required(status, "status");
    nodes = List.copyOf(ReleaseValues.required(nodes, "nodes"));
    if (nodes.isEmpty()) {
      throw new IllegalArgumentException("nodes must not be empty");
    }
    parametersHash = ReleaseValues.sha256Checksum(parametersHash);
    createdAt = requireInstant(createdAt, "createdAt");
    updatedAt = requireInstant(updatedAt, "updatedAt");
  }

  public ReleasePlan withStatus(ReleaseStatus status, Instant updatedAt) {
    return new ReleasePlan(
        releaseId,
        applicationId,
        targetEnvironment,
        artifactId,
        status,
        nodes,
        parametersHash,
        confirmation,
        stopOnNodeFailure,
        createdAt,
        updatedAt);
  }

  public ReleasePlan withConfirmation(
      ReleaseConfirmation confirmation,
      ReleaseStatus status,
      Instant updatedAt) {
    return new ReleasePlan(
        releaseId,
        applicationId,
        targetEnvironment,
        artifactId,
        status,
        nodes,
        parametersHash,
        ReleaseValues.required(confirmation, "confirmation"),
        stopOnNodeFailure,
        createdAt,
        updatedAt);
  }

  public ReleasePlan withNode(int index, ReleaseNodeStep node, ReleaseStatus status, Instant updatedAt) {
    List<ReleaseNodeStep> updatedNodes = new ArrayList<>(nodes);
    updatedNodes.set(index, ReleaseValues.required(node, "node"));
    return new ReleasePlan(
        releaseId,
        applicationId,
        targetEnvironment,
        artifactId,
        status,
        updatedNodes,
        parametersHash,
        confirmation,
        stopOnNodeFailure,
        createdAt,
        updatedAt);
  }

  public ReleasePlan skipNodesAfter(int index, Instant updatedAt) {
    List<ReleaseNodeStep> updatedNodes = new ArrayList<>(nodes);
    for (int i = index + 1; i < updatedNodes.size(); i += 1) {
      ReleaseNodeStep node = updatedNodes.get(i);
      if (node.status() == ReleaseNodeStatus.PENDING) {
        updatedNodes.set(i, node.markSkipped("STOPPED_AFTER_NODE_FAILURE", updatedAt));
      }
    }
    return new ReleasePlan(
        releaseId,
        applicationId,
        targetEnvironment,
        artifactId,
        status,
        updatedNodes,
        parametersHash,
        confirmation,
        stopOnNodeFailure,
        createdAt,
        updatedAt);
  }

  public boolean hasFailedNodes() {
    return nodes.stream().anyMatch(node -> node.status() == ReleaseNodeStatus.FAILED);
  }

  private static Instant requireInstant(Instant value, String fieldName) {
    if (value == null) {
      throw new IllegalArgumentException(fieldName + " is required");
    }
    return value;
  }
}
