package com.company.opsagent.controlplane.bootstrap.api;

import com.company.opsagent.contracts.toolcenter.JsonRepairAssistantRequest;
import com.company.opsagent.contracts.toolcenter.JsonRepairAssistantResponse;
import com.company.opsagent.controlplane.bootstrap.service.JsonRepairAssistantClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Iterator;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/internal/tool-center/json-assistant")
public class ToolCenterJsonAssistantController {

  private static final Set<String> REPAIR_FIELDS = Set.of(
      "contractVersion",
      "assistantAction",
      "source",
      "parseError",
      "idempotencyKey");

  private final JsonRepairAssistantClient jsonRepairAssistantClient;
  private final ObjectMapper objectMapper;

  public ToolCenterJsonAssistantController(
      JsonRepairAssistantClient jsonRepairAssistantClient,
      ObjectMapper objectMapper) {
    this.jsonRepairAssistantClient = jsonRepairAssistantClient;
    this.objectMapper = objectMapper;
  }

  @PostMapping("/repair")
  public Mono<JsonRepairAssistantResponse> repair(@RequestBody JsonNode request) {
    return blocking(() -> jsonRepairAssistantClient.repair(parseRepairRequest(request)));
  }

  private static <T> Mono<T> blocking(Supplier<T> supplier) {
    return Mono.fromSupplier(supplier).subscribeOn(Schedulers.boundedElastic());
  }

  private JsonRepairAssistantRequest parseRepairRequest(JsonNode request) {
    if (request == null || !request.isObject()) {
      throw new IllegalArgumentException("JSON repair assistant request must be a JSON object");
    }
    Iterator<String> fieldNames = request.fieldNames();
    while (fieldNames.hasNext()) {
      String fieldName = fieldNames.next();
      if (!REPAIR_FIELDS.contains(fieldName)) {
        throw new IllegalArgumentException("unsupported JSON repair assistant field: " + fieldName);
      }
    }
    try {
      return objectMapper.treeToValue(request, JsonRepairAssistantRequest.class);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("JSON repair assistant request is invalid", exception);
    }
  }
}
