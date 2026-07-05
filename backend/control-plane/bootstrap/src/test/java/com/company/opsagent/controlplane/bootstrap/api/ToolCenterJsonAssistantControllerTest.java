package com.company.opsagent.controlplane.bootstrap.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.company.opsagent.contracts.toolcenter.JsonRepairAssistantAction;
import com.company.opsagent.contracts.toolcenter.JsonRepairAssistantRequest;
import com.company.opsagent.contracts.toolcenter.JsonRepairAssistantResponse;
import com.company.opsagent.contracts.toolcenter.JsonRepairAssistantStatus;
import com.company.opsagent.controlplane.bootstrap.service.JsonRepairAssistantClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class ToolCenterJsonAssistantControllerTest {

  private final RecordingJsonRepairAssistantClient client = new RecordingJsonRepairAssistantClient();
  private final ToolCenterJsonAssistantController controller =
      new ToolCenterJsonAssistantController(client, new ObjectMapper());
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void passesJsonRepairRequestThroughTypedClientBoundary() throws Exception {
    var request = objectMapper.readTree("""
        {
          "contractVersion": "1.0",
          "assistantAction": "REPAIR_JSON",
          "source": "{\\"service\\":\\"queFork\\",}",
          "parseError": "Unexpected token }",
          "idempotencyKey": "json-repair-1"
        }
        """);

    StepVerifier.create(controller.repair(request))
        .assertNext(response -> {
          assertEquals(JsonRepairAssistantStatus.SUCCEEDED, response.status());
          assertEquals(JsonRepairAssistantAction.REPAIR_JSON, response.assistantAction());
          assertEquals(true, response.validationRequired());
          assertEquals("json-repair-assistant-read", response.skillId());
        })
        .verifyComplete();

    assertEquals(1, client.repairCount.get());
    assertEquals(JsonRepairAssistantAction.REPAIR_JSON, client.lastRequest.assistantAction());
    assertEquals("Unexpected token }", client.lastRequest.parseError());
  }

  @Test
  void rejectsUnknownJsonRepairFieldsBeforeClientBoundary() throws Exception {
    var request = objectMapper.readTree("""
        {
          "contractVersion": "1.0",
          "assistantAction": "REPAIR_JSON",
          "source": "{\\"service\\":\\"queFork\\",}",
          "idempotencyKey": "json-repair-1",
          "apiKey": "must-not-be-accepted"
        }
        """);

    StepVerifier.create(controller.repair(request))
        .expectErrorSatisfies(error -> {
          assertInstanceOf(IllegalArgumentException.class, error);
          assertEquals("unsupported JSON repair assistant field: apiKey", error.getMessage());
        })
        .verify();

    assertEquals(0, client.repairCount.get());
  }

  private static final class RecordingJsonRepairAssistantClient implements JsonRepairAssistantClient {

    private final AtomicInteger repairCount = new AtomicInteger();
    private JsonRepairAssistantRequest lastRequest;

    @Override
    public JsonRepairAssistantResponse repair(JsonRepairAssistantRequest request) {
      repairCount.incrementAndGet();
      lastRequest = request;
      return new JsonRepairAssistantResponse(
          "1.0",
          JsonRepairAssistantStatus.SUCCEEDED,
          request.assistantAction(),
          "已移除尾随逗号。",
          "{\"service\":\"queFork\"}",
          null,
          List.of("修复结果必须重新经过本地 JSON 校验。"),
          true,
          "json-repair-assistant-read",
          "provider:fingerprint");
    }
  }
}
