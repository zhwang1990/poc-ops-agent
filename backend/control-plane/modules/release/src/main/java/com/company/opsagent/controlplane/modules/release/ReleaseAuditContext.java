package com.company.opsagent.controlplane.modules.release;

public record ReleaseAuditContext(
    String action,
    String resource,
    String policyVersion,
    String result,
    String reason,
    String traceId,
    String requestId) {

  public ReleaseAuditContext {
    action = ReleaseValues.requiredText(action, "action");
    resource = ReleaseValues.requiredText(resource, "resource");
    policyVersion = ReleaseValues.requiredText(policyVersion, "policyVersion");
    result = ReleaseValues.requiredText(result, "result");
    reason = ReleaseValues.requiredText(reason, "reason");
    traceId = ReleaseValues.requiredText(traceId, "traceId");
    requestId = ReleaseValues.requiredText(requestId, "requestId");
  }
}
