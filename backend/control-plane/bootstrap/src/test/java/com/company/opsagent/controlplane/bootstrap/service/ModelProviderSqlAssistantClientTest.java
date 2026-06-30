package com.company.opsagent.controlplane.bootstrap.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.company.opsagent.contracts.sqlworkbench.SqlAssistantAction;
import com.company.opsagent.contracts.sqlworkbench.SqlAssistantResponse;
import com.company.opsagent.contracts.sqlworkbench.SqlAssistantStatus;
import com.company.opsagent.contracts.sqlworkbench.SqlStatementType;
import com.company.opsagent.contracts.sqlworkbench.SqlValidationLevel;
import com.company.opsagent.contracts.sqlworkbench.SqlValidationReport;
import com.company.opsagent.controlplane.modules.agentruntime.AesGcmModelProviderSecretCodec;
import com.company.opsagent.controlplane.modules.agentruntime.InMemoryModelProviderStore;
import com.company.opsagent.controlplane.modules.agentruntime.ModelProvider;
import com.company.opsagent.controlplane.modules.agentruntime.ModelProviderSecretCodec;
import com.company.opsagent.controlplane.modules.agentruntime.ModelProviderType;
import com.company.opsagent.controlplane.modules.sqlworkbench.SqlAssistantPrompt;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ModelProviderSqlAssistantClientTest {

  private static final String MASTER_KEY = "0123456789abcdef0123456789abcdef";
  private static final String API_KEY = "SQL_ASSISTANT_API_KEY_PLACEHOLDER";

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
    SqlAssistantResponse response = client(new InMemoryModelProviderStore()).ask(prompt());

    assertEquals(SqlAssistantStatus.MODEL_NOT_CONFIGURED, response.status());
    assertTrue(response.suggestions().isEmpty());
    assertTrue(response.validationRequired());
  }

  @Test
  void sendsPromptToDefaultProviderAndParsesAdvisoryJson() throws Exception {
    AtomicReference<String> authorization = new AtomicReference<>();
    AtomicReference<String> requestBody = new AtomicReference<>();
    startServer(exchange -> {
      authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
      requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      respond(exchange, 200, """
          {
            "choices": [{
              "message": {
                "content": "{\\"summary\\":\\"Use explicit columns.\\",\\"suggestions\\":[{\\"title\\":\\"Limit columns\\",\\"rationale\\":\\"The current projection fetches every column.\\",\\"suggestedSql\\":\\"select order_id, status from ORDERS.ORDERS\\"}],\\"safetyNotes\\":[\\"Validate before execution.\\"]}"
              }
            }]
          }
          """);
    });
    InMemoryModelProviderStore store = new InMemoryModelProviderStore();
    store.save(provider(serverBaseUrl(), API_KEY));

    SqlAssistantResponse response = client(store).ask(prompt());

    assertEquals(SqlAssistantStatus.SUCCEEDED, response.status());
    assertEquals("Use explicit columns.", response.summary());
    assertEquals("select order_id, status from ORDERS.ORDERS", response.suggestions().getFirst().suggestedSql());
    assertEquals("Bearer " + API_KEY, authorization.get());
    assertTrue(requestBody.get().contains("\"model\":\"gpt-4.1-mini\""));
    assertTrue(requestBody.get().contains("必须使用中文输出"));
    assertTrue(requestBody.get().contains("ORDERS.ORDERS"));
    assertFalse(requestBody.get().contains(API_KEY));
  }

  @Test
  void appendsOpenAiVersionPathWhenProviderBaseUrlDoesNotIncludeVersion() throws Exception {
    startServer(exchange -> respond(exchange, 200, """
        {
          "choices": [{
            "message": {
              "content": "{\\"summary\\":\\"Connected.\\",\\"suggestions\\":[],\\"safetyNotes\\":[\\"Validate before execution.\\"]}"
            }
          }]
        }
        """));
    InMemoryModelProviderStore store = new InMemoryModelProviderStore();
    store.save(provider(serverRootUrl(), API_KEY));

    SqlAssistantResponse response = client(store).ask(prompt());

    assertEquals(SqlAssistantStatus.SUCCEEDED, response.status());
    assertEquals("Connected.", response.summary());
  }

  @Test
  void mapsProviderFailureWithoutLeakingSecretOrResponseBody() throws Exception {
    startServer(exchange -> respond(exchange, 401, "invalid key " + API_KEY));
    InMemoryModelProviderStore store = new InMemoryModelProviderStore();
    store.save(provider(serverBaseUrl(), API_KEY));

    SqlAssistantResponse response = client(store).ask(prompt());

    assertEquals(SqlAssistantStatus.FAILED, response.status());
    assertTrue(response.summary().contains("provider rejected credentials"));
    assertFalse(response.summary().contains(API_KEY));
    assertTrue(response.suggestions().isEmpty());
  }

  private ModelProviderSqlAssistantClient client(InMemoryModelProviderStore store) {
    HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build();
    return new ModelProviderSqlAssistantClient(store, codec, httpClient, new ObjectMapper());
  }

  private SqlAssistantPrompt prompt() {
    return new SqlAssistantPrompt(
        SqlAssistantAction.OPTIMIZE_SQL,
        "as400-development",
        "development",
        "ORDERS",
        "DB2_FOR_I",
        "select * from ORDERS.ORDERS",
        new SqlValidationReport(
            "1.0",
            SqlStatementType.SELECT,
            SqlValidationLevel.VALIDATED,
            "sha256:readonly",
            List.of("ORDERS.ORDERS"),
            List.of(),
            List.of(),
            List.of()),
        null);
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
