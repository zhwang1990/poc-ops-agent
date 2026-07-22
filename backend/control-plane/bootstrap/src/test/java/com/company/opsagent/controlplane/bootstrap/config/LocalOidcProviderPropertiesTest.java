package com.company.opsagent.controlplane.bootstrap.config;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class LocalOidcProviderPropertiesTest {

  @Test
  void rejectsMissingClientSecret() {
    assertThrows(IllegalArgumentException.class, () -> properties(null));
  }

  @Test
  void rejectsBlankClientSecret() {
    assertThrows(IllegalArgumentException.class, () -> properties(" "));
  }

  private LocalOidcProviderProperties properties(String clientSecret) {
    return new LocalOidcProviderProperties(
        true,
        "http://127.0.0.1:8080/mock-oidc",
        "ops-agent-internal",
        "ops-agent-local-client",
        clientSecret,
        "local-reader-id",
        "local.reader",
        List.of("ops-reader"),
        Duration.ofMinutes(5),
        Duration.ofMinutes(10));
  }
}
