package com.company.opsagent.controlplane.modules.release;

import java.time.OffsetDateTime;

public record ReleaseCredential(
    String credentialAlias,
    ServerType serverType,
    String ciphertext,
    String nonce,
    String algorithm,
    String fingerprint,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt) {

  public ReleaseCredential {
    credentialAlias = ReleaseValues.requiredText(credentialAlias, "credentialAlias");
    serverType = ReleaseValues.required(serverType, "serverType");
    ciphertext = ReleaseValues.requiredText(ciphertext, "ciphertext");
    nonce = ReleaseValues.requiredText(nonce, "nonce");
    algorithm = ReleaseValues.requiredText(algorithm, "algorithm");
    fingerprint = ReleaseValues.requiredText(fingerprint, "fingerprint");
    createdAt = ReleaseValues.requiredTime(createdAt, "createdAt");
    updatedAt = ReleaseValues.requiredTime(updatedAt, "updatedAt");
  }

  public ReleaseCredentialSecretCodec.EncryptedCredential encryptedSecret() {
    return new ReleaseCredentialSecretCodec.EncryptedCredential(ciphertext, nonce, algorithm, fingerprint);
  }

  public ReleaseCredentialSummary summary() {
    return new ReleaseCredentialSummary(credentialAlias, fingerprint, updatedAt);
  }
}
