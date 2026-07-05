package com.company.opsagent.executionworker.release;

import java.time.OffsetDateTime;

public record ReleaseWorkerExecutionEvent(
    EventType eventType,
    String executionRequestId,
    String releaseId,
    String workflowId,
    String nodeId,
    String stream,
    String message,
    OffsetDateTime timestamp,
    ReleaseWorkerResult result) {

  public enum EventType {
    LOG,
    RESULT
  }

  public ReleaseWorkerExecutionEvent {
    if (eventType == null) {
      throw new IllegalArgumentException("eventType is required");
    }
    if (eventType == EventType.LOG) {
      executionRequestId = requireText(executionRequestId, "executionRequestId");
      releaseId = requireText(releaseId, "releaseId");
      workflowId = requireText(workflowId, "workflowId");
      nodeId = requireText(nodeId, "nodeId");
      stream = requireText(stream, "stream");
      message = requireText(message, "message");
      if (timestamp == null) {
        throw new IllegalArgumentException("timestamp is required");
      }
      result = null;
    } else if (result == null) {
      throw new IllegalArgumentException("result is required");
    }
  }

  public static ReleaseWorkerExecutionEvent log(
      ReleaseWorkerRequest request,
      String nodeId,
      String stream,
      String message,
      OffsetDateTime timestamp) {
    ReleaseWorkerRequest.ReleaseCommand command = request.command();
    return new ReleaseWorkerExecutionEvent(
        EventType.LOG,
        request.executionRequestId().toString(),
        command.releaseId(),
        command.workflowId(),
        nodeId,
        stream,
        message,
        timestamp,
        null);
  }

  public static ReleaseWorkerExecutionEvent result(ReleaseWorkerResult result) {
    return new ReleaseWorkerExecutionEvent(
        EventType.RESULT,
        result.executionRequestId(),
        result.releaseId(),
        result.workflowId(),
        null,
        null,
        null,
        result.completedAt(),
        result);
  }

  private static String requireText(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " is required");
    }
    return value.trim();
  }
}
