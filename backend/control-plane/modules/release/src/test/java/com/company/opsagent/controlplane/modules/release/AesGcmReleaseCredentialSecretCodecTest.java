package com.company.opsagent.controlplane.modules.release;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AesGcmReleaseCredentialSecretCodecTest {

  private static final String MASTER_KEY = ReleaseTestSecretMaterial.value();

  @Test
  void encryptsCredentialAndReturnsStableFingerprintPrefix() {
    String credentialValue = ReleaseTestSecretMaterial.value();
    var codec = new AesGcmReleaseCredentialSecretCodec(MASTER_KEY);
    var encrypted = codec.encrypt(credentialValue);
    var encryptedAgain = codec.encrypt(credentialValue);

    assertNotEquals(credentialValue, encrypted.ciphertext());
    assertFalse(encrypted.ciphertext().contains(credentialValue));
    assertEquals("AES_GCM_V1", encrypted.algorithm());
    assertTrue(encrypted.fingerprint().startsWith("fp_"));
    assertEquals(encrypted.fingerprint(), encryptedAgain.fingerprint());
    assertNotEquals(encrypted.nonce(), encryptedAgain.nonce());
    assertEquals(credentialValue, codec.decrypt(encrypted));
  }

  @Test
  void rejectsBlankMasterKeyAndPlaintext() {
    assertThrows(IllegalArgumentException.class, () -> new AesGcmReleaseCredentialSecretCodec(" "));

    var codec = new AesGcmReleaseCredentialSecretCodec(MASTER_KEY);

    assertThrows(IllegalArgumentException.class, () -> codec.encrypt(" "));
  }
}
