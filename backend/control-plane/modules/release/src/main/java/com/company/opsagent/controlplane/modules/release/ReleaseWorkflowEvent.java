package com.company.opsagent.controlplane.modules.release;

import java.time.Instant;

public record ReleaseWorkflowEvent(
    String contractVersion,
    String eventId,
    String workflowId,
    String releaseId,
    long sequence,
    Instant timestamp,
    ReleaseEventType type,
    ReleaseEventPayload payload,
    ReleaseAuditContext audit) {

  public ReleaseWorkflowEvent {
    if (!"1.0".equals(contractVersion)) {
      throw new IllegalArgumentException("unsupported release event contract version");
    }
    eventId = ReleaseValues.requiredText(eventId, "eventId");
    workflowId = ReleaseValues.requiredText(workflowId, "workflowId");
    releaseId = ReleaseValues.requiredText(releaseId, "releaseId");
    if (sequence < 1) {
      throw new IllegalArgumentException("sequence must be positive");
    }
    if (timestamp == null) {
      throw new IllegalArgumentException("timestamp is required");
    }
    type = ReleaseValues.required(type, "type");
    payload = ReleaseValues.required(payload, "payload");
    audit = ReleaseValues.required(audit, "audit");
    if (payload.payloadType() != type) {
      throw new IllegalArgumentException("release event type and payload type must match");
    }
  }
}
