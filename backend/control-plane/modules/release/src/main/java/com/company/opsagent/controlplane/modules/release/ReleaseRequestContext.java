package com.company.opsagent.controlplane.modules.release;

import java.util.List;

public record ReleaseRequestContext(
    String operatorId,
    List<String> roles,
    String policyDecisionId,
    String policyVersion,
    String traceId,
    String requestId) {

  public ReleaseRequestContext {
    operatorId = ReleaseValues.requiredText(operatorId, "operatorId");
    roles = List.copyOf(ReleaseValues.required(roles, "roles"));
    if (roles.isEmpty()) {
      throw new IllegalArgumentException("roles must not be empty");
    }
    policyDecisionId = ReleaseValues.requiredText(policyDecisionId, "policyDecisionId");
    policyVersion = ReleaseValues.requiredText(policyVersion, "policyVersion");
    traceId = ReleaseValues.requiredText(traceId, "traceId");
    requestId = ReleaseValues.requiredText(requestId, "requestId");
  }

  public static ReleaseRequestContext system(String releaseId) {
    String id = ReleaseValues.requiredText(releaseId, "releaseId");
    return new ReleaseRequestContext(
        "release-center",
        List.of("ROLE_ops-admin"),
        "release-policy:" + id,
        "release-center-policy-v1",
        "trace:" + id,
        "request:" + id);
  }
}
