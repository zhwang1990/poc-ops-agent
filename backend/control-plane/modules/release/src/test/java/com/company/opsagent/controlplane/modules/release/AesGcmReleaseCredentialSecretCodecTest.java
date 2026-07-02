package com.company.opsagent.controlplane.modules.release;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AesGcmReleaseCredentialSecretCodecTest {

  @Test
  void encryptsCredentialAndReturnsStableFingerprintPrefix() {
    var codec = new AesGcmReleaseCredentialSecretCodec("dev-master-key");
    var encrypted = codec.encrypt("secret-password");
    var encryptedAgain = codec.encrypt("secret-password");

    assertNotEquals("secret-password", encrypted.ciphertext());
    assertFalse(encrypted.ciphertext().contains("secret-password"));
    assertEquals("AES_GCM_V1", encrypted.algorithm());
    assertTrue(encrypted.fingerprint().startsWith("fp_"));
    assertEquals(encrypted.fingerprint(), encryptedAgain.fingerprint());
    assertNotEquals(encrypted.nonce(), encryptedAgain.nonce());
    assertEquals("secret-password", codec.decrypt(encrypted));
  }

  @Test
  void rejectsBlankMasterKeyAndPlaintext() {
    assertThrows(IllegalArgumentException.class, () -> new AesGcmReleaseCredentialSecretCodec(" "));

    var codec = new AesGcmReleaseCredentialSecretCodec("dev-master-key");

    assertThrows(IllegalArgumentException.class, () -> codec.encrypt(" "));
  }
}
