package com.company.opsagent.controlplane.modules.release;

import java.time.OffsetDateTime;

public record ReleaseCredentialSummary(
    String credentialAlias,
    String fingerprint,
    OffsetDateTime updatedAt) {

  public ReleaseCredentialSummary {
    credentialAlias = ReleaseValues.requiredText(credentialAlias, "credentialAlias");
    fingerprint = ReleaseValues.requiredText(fingerprint, "fingerprint");
    updatedAt = ReleaseValues.requiredTime(updatedAt, "updatedAt");
  }
}
