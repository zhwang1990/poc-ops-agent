package com.company.opsagent.controlplane.modules.release;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class AesGcmReleaseCredentialSecretCodec implements ReleaseCredentialSecretCodec {

  private static final String ALGORITHM = "AES_GCM_V1";
  private static final String CIPHER = "AES/GCM/NoPadding";
  private static final int GCM_TAG_BITS = 128;
  private static final int NONCE_BYTES = 12;
  private static final int FINGERPRINT_BYTES = 8;

  private final SecretKeySpec keySpec;
  private final SecureRandom secureRandom;

  public AesGcmReleaseCredentialSecretCodec(String masterKey) {
    this(masterKey, new SecureRandom());
  }

  AesGcmReleaseCredentialSecretCodec(String masterKey, SecureRandom secureRandom) {
    byte[] keyBytes = normalizeKey(ReleaseValues.requiredText(masterKey, "masterKey"));
    this.keySpec = new SecretKeySpec(keyBytes, "AES");
    this.secureRandom = secureRandom;
  }

  @Override
  public EncryptedCredential encrypt(String plaintext) {
    String secret = ReleaseValues.requiredText(plaintext, "plaintext");
    byte[] nonce = new byte[NONCE_BYTES];
    secureRandom.nextBytes(nonce);
    try {
      Cipher cipher = Cipher.getInstance(CIPHER);
      cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_BITS, nonce));
      byte[] encrypted = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
      return new EncryptedCredential(
          Base64.getEncoder().encodeToString(encrypted),
          Base64.getEncoder().encodeToString(nonce),
          ALGORITHM,
          fingerprint(secret));
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("failed to encrypt release credential", exception);
    }
  }

  @Override
  public String decrypt(EncryptedCredential encryptedCredential) {
    if (!ALGORITHM.equals(encryptedCredential.algorithm())) {
      throw new IllegalArgumentException("unsupported release credential algorithm");
    }
    try {
      Cipher cipher = Cipher.getInstance(CIPHER);
      byte[] nonce = Base64.getDecoder().decode(encryptedCredential.nonce());
      cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_BITS, nonce));
      byte[] decrypted = cipher.doFinal(Base64.getDecoder().decode(encryptedCredential.ciphertext()));
      return new String(decrypted, StandardCharsets.UTF_8);
    } catch (IllegalArgumentException | GeneralSecurityException exception) {
      throw new IllegalStateException("failed to decrypt release credential", exception);
    }
  }

  private static byte[] normalizeKey(String masterKey) {
    byte[] raw = masterKey.getBytes(StandardCharsets.UTF_8);
    if (raw.length == 16 || raw.length == 24 || raw.length == 32) {
      return raw;
    }
    try {
      return MessageDigest.getInstance("SHA-256").digest(raw);
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("failed to derive release credential master key", exception);
    }
  }

  private static String fingerprint(String plaintext) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(plaintext.getBytes(StandardCharsets.UTF_8));
      byte[] prefix = Arrays.copyOf(digest, FINGERPRINT_BYTES);
      return "fp_" + Base64.getUrlEncoder().withoutPadding().encodeToString(prefix);
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("failed to fingerprint release credential", exception);
    }
  }
}
