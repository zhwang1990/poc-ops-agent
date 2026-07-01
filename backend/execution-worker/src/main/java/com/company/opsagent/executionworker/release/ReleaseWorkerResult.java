package com.company.opsagent.executionworker.release;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

public record ReleaseWorkerResult(
    String contractVersion,
    String executionRequestId,
    String releaseId,
    String workflowId,
    ReleaseWorkerStatus status,
    List<ReleaseNodeResult> nodeResults,
    String errorCode,
    String errorMessage,
    OffsetDateTime completedAt) {

  public static ReleaseWorkerResult rejected(
      ReleaseWorkerRequest request,
      String errorCode,
      String errorMessage,
      Clock clock) {
    ReleaseWorkerRequest.ReleaseCommand command = request == null ? null : request.command();
    ReleaseWorkerRequest.ReleaseNodeTarget node = firstNode(command);
    OffsetDateTime completedAt = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    return new ReleaseWorkerResult(
        "1.0",
        valueOrUnknown(request == null ? null : request.executionRequestId()),
        valueOrUnknown(command == null ? null : command.releaseId()),
        valueOrUnknown(command == null ? null : command.workflowId()),
        ReleaseWorkerStatus.REJECTED,
        List.of(ReleaseNodeResult.rejected(node, errorCode, errorMessage, completedAt)),
        errorCode,
        errorMessage,
        completedAt);
  }

  public record ReleaseNodeResult(
      String nodeId,
      ReleaseWorkerStatus status,
      String serverType,
      String managementMode,
      String errorCode,
      String errorMessage,
      OffsetDateTime startedAt,
      OffsetDateTime completedAt) {

    static ReleaseNodeResult rejected(
        ReleaseWorkerRequest.ReleaseNodeTarget node,
        String errorCode,
        String errorMessage,
        OffsetDateTime completedAt) {
      return new ReleaseNodeResult(
          valueOrUnknown(node == null ? null : node.nodeId()),
          ReleaseWorkerStatus.REJECTED,
          node == null ? null : node.serverType(),
          node == null ? null : node.managementMode(),
          errorCode,
          errorMessage,
          completedAt,
          completedAt);
    }
  }

  private static ReleaseWorkerRequest.ReleaseNodeTarget firstNode(ReleaseWorkerRequest.ReleaseCommand command) {
    if (command == null || command.nodes() == null || command.nodes().isEmpty()) {
      return null;
    }
    return command.nodes().get(0);
  }

  private static String valueOrUnknown(String value) {
    if (value == null || value.isBlank()) {
      return "unknown";
    }
    return value;
  }
}
