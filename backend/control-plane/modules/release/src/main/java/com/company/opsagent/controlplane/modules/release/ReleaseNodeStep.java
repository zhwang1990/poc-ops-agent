package com.company.opsagent.controlplane.modules.release;

import java.time.Instant;

public record ReleaseNodeStep(
    String nodeId,
    ServerType serverType,
    ManagementMode managementMode,
    int sequence,
    ReleaseNodeStatus status,
    String statusReason,
    Instant startedAt,
    Instant completedAt) {

  public ReleaseNodeStep {
    nodeId = ReleaseValues.requiredText(nodeId, "nodeId");
    serverType = ReleaseValues.required(serverType, "serverType");
    managementMode = ReleaseValues.required(managementMode, "managementMode");
    if (sequence < 1) {
      throw new IllegalArgumentException("sequence must be positive");
    }
    status = ReleaseValues.required(status, "status");
    statusReason = ReleaseValues.optionalText(statusReason);
  }

  public static ReleaseNodeStep fromServer(int sequence, ReleaseServer server) {
    ReleaseValues.required(server, "server");
    return new ReleaseNodeStep(
        server.nodeId(),
        server.serverType(),
        server.managementMode(),
        sequence,
        ReleaseNodeStatus.PENDING,
        null,
        null,
        null);
  }

  public ReleaseNodeStep markRunning(Instant startedAt) {
    return new ReleaseNodeStep(
        nodeId,
        serverType,
        managementMode,
        sequence,
        ReleaseNodeStatus.RUNNING,
        null,
        requireInstant(startedAt, "startedAt"),
        null);
  }

  public ReleaseNodeStep markSucceeded(Instant completedAt) {
    Instant finishedAt = requireInstant(completedAt, "completedAt");
    return new ReleaseNodeStep(
        nodeId,
        serverType,
        managementMode,
        sequence,
        ReleaseNodeStatus.SUCCEEDED,
        null,
        startedAt,
        finishedAt);
  }

  public ReleaseNodeStep markFailed(String reason, Instant completedAt) {
    Instant finishedAt = requireInstant(completedAt, "completedAt");
    return new ReleaseNodeStep(
        nodeId,
        serverType,
        managementMode,
        sequence,
        ReleaseNodeStatus.FAILED,
        ReleaseValues.requiredText(reason, "reason"),
        startedAt,
        finishedAt);
  }

  public ReleaseNodeStep markSkipped(String reason, Instant completedAt) {
    Instant finishedAt = requireInstant(completedAt, "completedAt");
    return new ReleaseNodeStep(
        nodeId,
        serverType,
        managementMode,
        sequence,
        ReleaseNodeStatus.SKIPPED,
        ReleaseValues.requiredText(reason, "reason"),
        startedAt,
        finishedAt);
  }

  private static Instant requireInstant(Instant value, String fieldName) {
    if (value == null) {
      throw new IllegalArgumentException(fieldName + " is required");
    }
    return value;
  }
}
