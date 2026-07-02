package com.company.opsagent.controlplane.modules.release;

import java.time.Instant;

public record ReleaseConfirmation(
    String confirmationId,
    String parametersHash,
    String confirmedBy,
    Instant confirmedAt) {

  public ReleaseConfirmation {
    confirmationId = ReleaseValues.requiredText(confirmationId, "confirmationId");
    parametersHash = ReleaseValues.sha256Checksum(parametersHash);
    confirmedBy = ReleaseValues.requiredText(confirmedBy, "confirmedBy");
    if (confirmedAt == null) {
      throw new IllegalArgumentException("confirmedAt is required");
    }
  }
}
