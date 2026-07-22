package com.company.opsagent.controlplane.modules.agentruntime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class ModelProviderTest {

  private static final String API_KEY = ModelProviderTestSecretMaterial.value();

  @Test
  void createCommandRejectsNonHttpsExternalBaseUrl() {
    assertThrows(IllegalArgumentException.class, () -> createCommand("http://api.example.com/v1"));
  }

  @Test
  void createCommandAllowsLocalHttpBaseUrlForDevelopment() {
    ModelProviderCreateCommand command = createCommand("http://127.0.0.1:11434/v1");

    assertEquals("http://127.0.0.1:11434/v1", command.baseUrl());
  }

  @Test
  void createCommandRejectsBlankModelName() {
    assertThrows(IllegalArgumentException.class, () -> new ModelProviderCreateCommand(
        "OpenAI",
        "https://api.openai.com/v1",
        " ",
        API_KEY,
        Duration.ofSeconds(30),
        5,
        5,
        Duration.ofSeconds(30)));
  }

  @Test
  void providerUsesOpenAiCompatibleTypeAndNeverStoresPlaintextKey() {
    OffsetDateTime now = OffsetDateTime.parse("2026-06-28T00:00:00Z");

    ModelProvider provider = new ModelProvider(
        "provider-1",
        "OpenAI",
        ModelProviderType.OPENAI_COMPATIBLE,
        "https://api.openai.com/v1",
        "gpt-4.1-mini",
        true,
        false,
        Duration.ofSeconds(30),
        5,
        5,
        Duration.ofSeconds(30),
        "ciphertext-value",
        "nonce-value",
        "AES_GCM_V1",
        "fp_1234567890abcdef",
        now,
        1,
        "admin",
        now,
        "admin",
        now);

    assertEquals(ModelProviderType.OPENAI_COMPATIBLE, provider.providerType());
    assertFalse(provider.apiKeyCiphertext().contains(API_KEY));
    assertTrue(provider.apiKeyConfigured());
  }

  private ModelProviderCreateCommand createCommand(String baseUrl) {
    return new ModelProviderCreateCommand(
        "OpenAI",
        baseUrl,
        "gpt-4.1-mini",
        API_KEY,
        Duration.ofSeconds(30),
        5,
        5,
        Duration.ofSeconds(30));
  }
}
