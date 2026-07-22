package com.company.opsagent.controlplane.modules.agentruntime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class DefaultModelProviderManagementServiceTest {

  private static final String MASTER_KEY = ModelProviderTestSecretMaterial.value();
  private final Clock clock = Clock.fixed(Instant.parse("2026-06-28T00:00:00Z"), ZoneOffset.UTC);
  private final AesGcmModelProviderSecretCodec codec = new AesGcmModelProviderSecretCodec(
      MASTER_KEY);
  private final InMemoryModelProviderStore store = new InMemoryModelProviderStore();
  private final DefaultModelProviderManagementService service = new DefaultModelProviderManagementService(
      store,
      codec,
      provider -> new ModelProviderProbeResult("SUCCEEDED", "ok"),
      clock);

  @Test
  void createsProviderSummaryWithoutReturningSecretMaterial() {
    String apiKey = ModelProviderTestSecretMaterial.value();
    ModelProviderSummary created = service.create(createCommand(apiKey), "admin");

    assertTrue(created.apiKeyConfigured());
    assertTrue(created.apiKeyFingerprint().startsWith("fp_"));
    assertTrue(created.apiKeyCiphertext().isEmpty());
    assertEquals(1, created.configVersion());
    assertFalse(store.findById(created.providerId()).orElseThrow().apiKeyCiphertext()
        .contains(apiKey));
  }

  @Test
  void rotatesApiKeyAndIncrementsConfigVersionWithoutChangingModelMetadata() {
    ModelProviderSummary created = service.create(createCommand(ModelProviderTestSecretMaterial.value()), "admin");

    ModelProviderSummary rotated = service.rotateApiKey(
        created.providerId(),
        new ModelProviderApiKeyCommand(ModelProviderTestSecretMaterial.value()),
        "admin");

    assertEquals(created.displayName(), rotated.displayName());
    assertEquals(created.baseUrl(), rotated.baseUrl());
    assertEquals(created.modelName(), rotated.modelName());
    assertEquals(2, rotated.configVersion());
    assertFalse(created.apiKeyFingerprint().equals(rotated.apiKeyFingerprint()));
  }

  @Test
  void switchesDefaultProviderOnlyWhenApiKeyIsConfigured() {
    ModelProviderSummary first = service.create(createCommand(ModelProviderTestSecretMaterial.value()), "admin");
    ModelProviderSummary second = service.create(createCommand(ModelProviderTestSecretMaterial.value()), "admin");

    service.setDefault(first.providerId(), "admin");
    ModelProviderSummary active = service.setDefault(second.providerId(), "admin");

    assertTrue(active.defaultProvider());
    assertEquals(second.providerId(), service.defaultProvider().orElseThrow().providerId());
    assertFalse(service.get(first.providerId()).orElseThrow().defaultProvider());
  }

  @Test
  void failsWhenDefaultProviderIsDisabled() {
    ModelProviderSummary created = service.create(createCommand(ModelProviderTestSecretMaterial.value()), "admin");
    service.disable(created.providerId(), "admin");

    assertThrows(IllegalStateException.class, () -> service.setDefault(created.providerId(), "admin"));
  }

  private ModelProviderCreateCommand createCommand(String apiKey) {
    return new ModelProviderCreateCommand(
        "OpenAI",
        "https://api.openai.com/v1",
        "gpt-4.1-mini",
        apiKey,
        Duration.ofSeconds(30),
        5,
        5,
        Duration.ofSeconds(30));
  }
}
