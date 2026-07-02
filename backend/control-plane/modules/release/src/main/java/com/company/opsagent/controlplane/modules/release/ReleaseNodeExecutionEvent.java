package com.company.opsagent.controlplane.modules.release;

import java.time.Instant;

public record ReleaseNodeExecutionEvent(
    EventType eventType,
    String nodeId,
    String stream,
    String message,
    Instant emittedAt,
    ReleaseNodeExecutionResult result) {

  public enum EventType {
    LOG,
    RESULT
  }

  public ReleaseNodeExecutionEvent {
    eventType = ReleaseValues.required(eventType, "eventType");
    if (eventType == EventType.LOG) {
      nodeId = ReleaseValues.requiredText(nodeId, "nodeId");
      stream = ReleaseValues.requiredText(stream, "stream");
      message = ReleaseValues.requiredText(message, "message");
      emittedAt = ReleaseValues.required(emittedAt, "emittedAt");
      result = null;
    } else {
      result = ReleaseValues.required(result, "result");
      nodeId = ReleaseValues.optionalText(nodeId);
      stream = ReleaseValues.optionalText(stream);
      message = ReleaseValues.optionalText(message);
    }
  }

  public static ReleaseNodeExecutionEvent log(String nodeId, String stream, String message, Instant emittedAt) {
    return new ReleaseNodeExecutionEvent(EventType.LOG, nodeId, stream, message, emittedAt, null);
  }

  public static ReleaseNodeExecutionEvent result(ReleaseNodeExecutionResult result) {
    return new ReleaseNodeExecutionEvent(EventType.RESULT, null, null, null, null, result);
  }
}
