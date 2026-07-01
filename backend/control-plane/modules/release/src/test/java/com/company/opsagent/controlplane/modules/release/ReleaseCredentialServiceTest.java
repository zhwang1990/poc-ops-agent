package com.company.opsagent.controlplane.modules.release;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ReleaseCredentialServiceTest {

  private final Clock clock = Clock.fixed(Instant.parse("2026-07-01T00:00:00Z"), ZoneOffset.UTC);
  private final InMemoryReleaseCatalogStore store = new InMemoryReleaseCatalogStore();
  private final AesGcmReleaseCredentialSecretCodec codec = new AesGcmReleaseCredentialSecretCodec(
      "0123456789abcdef0123456789abcdef");
  private final ReleaseCredentialService service = new ReleaseCredentialService(store, codec, clock);

  @Test
  void createsCredentialSummaryWithoutReturningPlaintext() {
    ReleaseCredentialSummary summary = service
        .createOrRotate("sit-tomcat", ServerType.TOMCAT, "secret-password", "admin")
        .block();

    assertEquals("sit-tomcat", summary.credentialAlias());
    assertTrue(summary.fingerprint().startsWith("fp_"));
    assertEquals(Instant.parse("2026-07-01T00:00:00Z"), summary.updatedAt().toInstant());
    assertFalse(summary.toString().contains("secret-password"));

    ReleaseCredential stored = store.findCredential("sit-tomcat").block();
    assertFalse(stored.ciphertext().contains("secret-password"));
    assertEquals("secret-password", codec.decrypt(stored.encryptedSecret()));
  }

  @Test
  void rotatesCredentialFingerprintWithoutReturningCiphertext() {
    ReleaseCredentialSummary first = service
        .createOrRotate("sit-tomcat", ServerType.TOMCAT, "first-secret", "admin")
        .block();
    ReleaseCredentialSummary second = service
        .createOrRotate("sit-tomcat", ServerType.TOMCAT, "second-secret", "admin")
        .block();

    assertEquals(first.credentialAlias(), second.credentialAlias());
    assertNotEquals(first.fingerprint(), second.fingerprint());
    assertFalse(second.toString().contains("second-secret"));
  }
}
