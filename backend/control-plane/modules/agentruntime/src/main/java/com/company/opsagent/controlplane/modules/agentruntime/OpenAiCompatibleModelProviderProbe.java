package com.company.opsagent.controlplane.modules.agentruntime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * OpenAI-compatible model provider connectivity probe.
 */
public final class OpenAiCompatibleModelProviderProbe implements ModelProviderProbe {

  private static final String LOCAL_FAKE_API_KEY = "OPS_AGENT_FAKE_API_KEY_REPLACE_ME";

  private final ModelProviderSecretCodec secretCodec;
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;

  public OpenAiCompatibleModelProviderProbe(ModelProviderSecretCodec secretCodec) {
    this(secretCodec, HttpClient.newHttpClient(), new ObjectMapper());
  }

  OpenAiCompatibleModelProviderProbe(
      ModelProviderSecretCodec secretCodec,
      HttpClient httpClient,
      ObjectMapper objectMapper) {
    this.secretCodec = secretCodec;
    this.httpClient = httpClient;
    this.objectMapper = objectMapper;
  }

  @Override
  public ModelProviderProbeResult test(ModelProvider provider) {
    if (!provider.enabled()) {
      return new ModelProviderProbeResult("FAILED", "Model provider is disabled.");
    }
    String apiKey = secretCodec.decrypt(new ModelProviderSecretCodec.EncryptedSecret(
        provider.apiKeyCiphertext(),
        provider.apiKeyNonce(),
        provider.apiKeyAlgorithm(),
        provider.apiKeyFingerprint()));
    if (LOCAL_FAKE_API_KEY.equals(apiKey)) {
      return new ModelProviderProbeResult(
          "SKIPPED_FAKE_API_KEY",
          "Local fake API key placeholder was not sent to the provider.");
    }
    HttpRequest request = HttpRequest.newBuilder(OpenAiCompatibleEndpoint.chatCompletionsUri(provider.baseUrl()))
        .timeout(probeTimeout(provider.timeout()))
        .header("Authorization", "Bearer " + apiKey)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(probePayload(provider)))
        .build();
    try {
      HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
      return resultForStatus(response.statusCode());
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return new ModelProviderProbeResult(
          "FAILED",
          "Model provider probe was interrupted before completion.");
    } catch (IOException | RuntimeException exception) {
      return new ModelProviderProbeResult(
          "FAILED",
          "Model provider probe failed before receiving a valid response.");
    }
  }

  private String probePayload(ModelProvider provider) {
    try {
      return objectMapper.writeValueAsString(Map.of(
          "model", provider.modelName(),
          "messages", List.of(Map.of(
              "role", "user",
              "content", "Return ok.")),
          "max_tokens", 1,
          "stream", false));
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("failed to create model provider probe payload", exception);
    }
  }

  private Duration probeTimeout(Duration configuredTimeout) {
    Duration maxProbeTimeout = Duration.ofSeconds(10);
    return configuredTimeout.compareTo(maxProbeTimeout) > 0 ? maxProbeTimeout : configuredTimeout;
  }

  private ModelProviderProbeResult resultForStatus(int statusCode) {
    if (statusCode >= 200 && statusCode < 300) {
      return new ModelProviderProbeResult(
          "SUCCEEDED",
          "Model provider accepted the connectivity probe request.");
    }
    if (statusCode == 401 || statusCode == 403) {
      return new ModelProviderProbeResult(
          "FAILED",
          "Model provider rejected credentials or access.");
    }
    return new ModelProviderProbeResult(
        "FAILED",
        "Model provider probe returned HTTP status " + statusCode + ".");
  }
}
