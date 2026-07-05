package com.company.opsagent.controlplane.bootstrap.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.company.opsagent.contracts.toolcenter.JsonRepairAssistantAction;
import com.company.opsagent.contracts.toolcenter.JsonRepairAssistantRequest;
import com.company.opsagent.contracts.toolcenter.JsonRepairAssistantResponse;
import com.company.opsagent.contracts.toolcenter.JsonRepairAssistantStatus;
import com.company.opsagent.controlplane.modules.agentruntime.AesGcmModelProviderSecretCodec;
import com.company.opsagent.controlplane.modules.agentruntime.InMemoryModelProviderStore;
import com.company.opsagent.controlplane.modules.agentruntime.ModelProvider;
import com.company.opsagent.controlplane.modules.agentruntime.ModelProviderSecretCodec;
import com.company.opsagent.controlplane.modules.agentruntime.ModelProviderType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ModelProviderJsonRepairAssistantClientTest {

  private static final String MASTER_KEY = "0123456789abcdef0123456789abcdef";
  private static final String API_KEY = "JSON_REPAIR_ASSISTANT_API_KEY_PLACEHOLDER";

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
  void returnsModelNotConfiguredWhenNoDefaultProviderExists() {
    JsonRepairAssistantResponse response = client(new InMemoryModelProviderStore()).repair(request());

    assertEquals(JsonRepairAssistantStatus.MODEL_NOT_CONFIGURED, response.status());
    assertEquals("json-repair-assistant-read", response.skillId());
    assertTrue(response.validationRequired());
  }

  @Test
  void sendsPromptToDefaultProviderAndParsesRepairedJson() throws Exception {
    AtomicReference<String> authorization = new AtomicReference<>();
    AtomicReference<String> requestBody = new AtomicReference<>();
    startServer(exchange -> {
      authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
      requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      respond(exchange, 200, """
          {
            "choices": [{
              "message": {
                "content": "{\\"summary\\":\\"已移除尾随逗号。\\",\\"repairable\\":true,\\"repairedJson\\":\\"{\\\\\\"service\\\\\\":\\\\\\"queFork\\\\\\"}\\",\\"failureReason\\":null,\\"safetyNotes\\":[\\"重新本地校验。\\"]}"
              }
            }]
          }
          """);
    });
    InMemoryModelProviderStore store = new InMemoryModelProviderStore();
    store.save(provider(serverBaseUrl(), API_KEY));

    JsonRepairAssistantResponse response = client(store).repair(request());

    assertEquals(JsonRepairAssistantStatus.SUCCEEDED, response.status());
    assertEquals("{\"service\":\"queFork\"}", response.repairedJson());
    assertEquals("已移除尾随逗号。", response.summary());
    assertEquals("Bearer " + API_KEY, authorization.get());
    assertTrue(requestBody.get().contains("\"model\":\"gpt-4.1-mini\""));
    assertTrue(requestBody.get().contains("json-repair-assistant-read"));
    assertTrue(requestBody.get().contains("Broken JSON source"));
    assertFalse(requestBody.get().contains(API_KEY));
  }

  @Test
  void appendsOpenAiVersionPathWhenProviderBaseUrlDoesNotIncludeVersion() throws Exception {
    startServer(exchange -> respond(exchange, 200, """
        {
          "choices": [{
            "message": {
              "content": "{\\"summary\\":\\"无法可靠修复。\\",\\"repairable\\":false,\\"repairedJson\\":null,\\"failureReason\\":\\"缺少闭合结构。\\",\\"safetyNotes\\":[\\"不要编造字段。\\"]}"
            }
          }]
        }
        """));
    InMemoryModelProviderStore store = new InMemoryModelProviderStore();
    store.save(provider(serverRootUrl(), API_KEY));

    JsonRepairAssistantResponse response = client(store).repair(request());

    assertEquals(JsonRepairAssistantStatus.NOT_REPAIRABLE, response.status());
    assertEquals("缺少闭合结构。", response.failureReason());
  }

  @Test
  void mapsProviderFailureWithoutLeakingSecretOrResponseBody() throws Exception {
    startServer(exchange -> respond(exchange, 401, "invalid key " + API_KEY));
    InMemoryModelProviderStore store = new InMemoryModelProviderStore();
    store.save(provider(serverBaseUrl(), API_KEY));

    JsonRepairAssistantResponse response = client(store).repair(request());

    assertEquals(JsonRepairAssistantStatus.FAILED, response.status());
    assertTrue(response.summary().contains("provider rejected credentials"));
    assertFalse(response.summary().contains(API_KEY));
    assertFalse(response.failureReason().contains(API_KEY));
  }

  private ModelProviderJsonRepairAssistantClient client(InMemoryModelProviderStore store) {
    HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build();
    return new ModelProviderJsonRepairAssistantClient(store, codec, httpClient, new ObjectMapper());
  }

  private JsonRepairAssistantRequest request() {
    return new JsonRepairAssistantRequest(
        "1.0",
        JsonRepairAssistantAction.REPAIR_JSON,
        "{\"service\":\"queFork\",}",
        "Unexpected token }",
        "json-repair-1");
  }

  private ModelProvider provider(String baseUrl, String apiKey) {
    ModelProviderSecretCodec.EncryptedSecret encrypted = codec.encrypt(apiKey);
    OffsetDateTime now = OffsetDateTime.parse("2026-07-05T00:00:00Z");
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
