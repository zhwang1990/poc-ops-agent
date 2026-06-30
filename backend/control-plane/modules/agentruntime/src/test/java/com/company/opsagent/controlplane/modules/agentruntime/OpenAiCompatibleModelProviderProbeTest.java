package com.company.opsagent.controlplane.modules.agentruntime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleModelProviderProbeTest {

  private static final String MASTER_KEY = "0123456789abcdef0123456789abcdef";
  private static final String FAKE_API_KEY = "OPS_AGENT_FAKE_API_KEY_REPLACE_ME";
  private static final String PROBE_API_KEY = "PROBE_API_KEY_PLACEHOLDER";

  private final AesGcmModelProviderSecretCodec codec =
      new AesGcmModelProviderSecretCodec(MASTER_KEY);

  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void sendsOpenAiCompatibleProbeWithoutExposingSecretInResult() throws Exception {
    AtomicReference<String> authorization = new AtomicReference<>();
    AtomicReference<String> requestBody = new AtomicReference<>();
    startServer(exchange -> {
      authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
      requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      respond(exchange, 200, "{\"id\":\"probe\",\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");
    });

    ModelProviderProbeResult result = probe().test(provider(serverBaseUrl(), PROBE_API_KEY));

    assertEquals("SUCCEEDED", result.status());
    assertEquals("Bearer " + PROBE_API_KEY, authorization.get());
    assertTrue(requestBody.get().contains("\"model\":\"gpt-4.1-mini\""));
    assertFalse(result.message().contains(PROBE_API_KEY));
  }

  @Test
  void appendsOpenAiVersionPathWhenBaseUrlDoesNotIncludeVersion() throws Exception {
    startServer(exchange -> respond(exchange, 200, "{\"id\":\"probe\"}"));

    ModelProviderProbeResult result = probe().test(provider(serverRootUrl(), PROBE_API_KEY));

    assertEquals("SUCCEEDED", result.status());
  }

  @Test
  void mapsUnauthorizedProbeWithoutReturningProviderResponseBody() throws Exception {
    startServer(exchange -> respond(exchange, 401, "invalid api key " + PROBE_API_KEY));

    ModelProviderProbeResult result = probe().test(provider(serverBaseUrl(), PROBE_API_KEY));

    assertEquals("FAILED", result.status());
    assertTrue(result.message().contains("credentials"));
    assertFalse(result.message().contains(PROBE_API_KEY));
    assertFalse(result.message().contains("invalid api key"));
  }

  @Test
  void skipsLocalFakeApiKeyWithoutCallingProvider() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    startServer(exchange -> {
      calls.incrementAndGet();
      respond(exchange, 200, "{}");
    });

    ModelProviderProbeResult result = probe().test(provider(serverBaseUrl(), FAKE_API_KEY));

    assertEquals("SKIPPED_FAKE_API_KEY", result.status());
    assertEquals(0, calls.get());
  }

  private OpenAiCompatibleModelProviderProbe probe() {
    HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build();
    return new OpenAiCompatibleModelProviderProbe(codec, httpClient, new ObjectMapper());
  }

  private ModelProvider provider(String baseUrl, String apiKey) {
    ModelProviderSecretCodec.EncryptedSecret encrypted = codec.encrypt(apiKey);
    OffsetDateTime now = OffsetDateTime.parse("2026-06-28T00:00:00Z");
    return new ModelProvider(
        "provider-1",
        "OpenAI",
        ModelProviderType.OPENAI_COMPATIBLE,
        baseUrl,
        "gpt-4.1-mini",
        true,
        true,
        Duration.ofSeconds(5),
        5,
        5,
        Duration.ofSeconds(5),
        encrypted.ciphertext(),
        encrypted.nonce(),
        encrypted.algorithm(),
        encrypted.fingerprint(),
        now,
        1,
        "operator-1",
        now,
        "operator-1",
        now);
  }

  private void startServer(ExchangeHandler handler) throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/chat/completions", handler::handle);
    server.start();
  }

  private String serverBaseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
  }

  private String serverRootUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private void respond(HttpExchange exchange, int statusCode, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(statusCode, bytes.length);
    try (var output = exchange.getResponseBody()) {
      output.write(bytes);
    }
  }

  @FunctionalInterface
  private interface ExchangeHandler {

    void handle(HttpExchange exchange) throws IOException;
  }
}
