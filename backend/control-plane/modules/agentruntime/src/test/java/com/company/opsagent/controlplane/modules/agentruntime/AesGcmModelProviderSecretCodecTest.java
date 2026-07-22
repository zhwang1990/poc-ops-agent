package com.company.opsagent.controlplane.modules.agentruntime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AesGcmModelProviderSecretCodecTest {

  private static final String MASTER_KEY = ModelProviderTestSecretMaterial.value();

  @Test
  void encryptsAndDecryptsApiKeyWithoutPlaintextInCiphertext() {
    AesGcmModelProviderSecretCodec codec = new AesGcmModelProviderSecretCodec(
        MASTER_KEY);

    String apiKey = ModelProviderTestSecretMaterial.value();
    ModelProviderSecretCodec.EncryptedSecret encrypted = codec.encrypt(apiKey);

    assertEquals(apiKey, codec.decrypt(encrypted));
    assertFalse(encrypted.ciphertext().contains(apiKey));
    assertFalse(encrypted.nonce().isBlank());
    assertEquals("AES_GCM_V1", encrypted.algorithm());
    assertTrue(encrypted.fingerprint().startsWith("fp_"));
  }

  @Test
  void usesDifferentNonceForEachEncryption() {
    AesGcmModelProviderSecretCodec codec = new AesGcmModelProviderSecretCodec(
        MASTER_KEY);

    String apiKey = ModelProviderTestSecretMaterial.value();
    ModelProviderSecretCodec.EncryptedSecret first = codec.encrypt(apiKey);
    ModelProviderSecretCodec.EncryptedSecret second = codec.encrypt(apiKey);

    assertNotEquals(first.nonce(), second.nonce());
    assertNotEquals(first.ciphertext(), second.ciphertext());
    assertEquals(first.fingerprint(), second.fingerprint());
  }

  @Test
  void rejectsBlankMasterKeyAndBlankSecret() {
    assertThrows(IllegalArgumentException.class, () -> new AesGcmModelProviderSecretCodec(" "));

    AesGcmModelProviderSecretCodec codec = new AesGcmModelProviderSecretCodec(
        MASTER_KEY);

    assertThrows(IllegalArgumentException.class, () -> codec.encrypt(" "));
  }
}
