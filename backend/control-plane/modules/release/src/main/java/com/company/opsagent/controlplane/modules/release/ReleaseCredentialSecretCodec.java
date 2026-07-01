package com.company.opsagent.controlplane.modules.release;

public interface ReleaseCredentialSecretCodec {

  EncryptedCredential encrypt(String plaintext);

  String decrypt(EncryptedCredential encryptedCredential);

  record EncryptedCredential(
      String ciphertext,
      String nonce,
      String algorithm,
      String fingerprint) {

    public EncryptedCredential {
      ciphertext = ReleaseValues.requiredText(ciphertext, "ciphertext");
      nonce = ReleaseValues.requiredText(nonce, "nonce");
      algorithm = ReleaseValues.requiredText(algorithm, "algorithm");
      fingerprint = ReleaseValues.requiredText(fingerprint, "fingerprint");
    }
  }
}
