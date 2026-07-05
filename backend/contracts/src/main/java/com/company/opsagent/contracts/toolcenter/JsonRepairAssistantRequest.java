package com.company.opsagent.contracts.toolcenter;

import static com.company.opsagent.contracts.ContractValues.required;
import static com.company.opsagent.contracts.ContractValues.requiredText;

public record JsonRepairAssistantRequest(
    String contractVersion,
    JsonRepairAssistantAction assistantAction,
    String source,
    String parseError,
    String idempotencyKey) {

  private static final int MAX_SOURCE_LENGTH = 30_000;
  private static final int MAX_PARSE_ERROR_LENGTH = 1_000;

  public JsonRepairAssistantRequest {
    if (!"1.0".equals(contractVersion)) {
      throw new IllegalArgumentException("contractVersion must be 1.0");
    }
    assistantAction = required(assistantAction, "assistantAction");
    source = boundedRequiredText(source, "source", MAX_SOURCE_LENGTH);
    parseError = optionalText(parseError, "parseError", MAX_PARSE_ERROR_LENGTH);
    idempotencyKey = boundedRequiredText(idempotencyKey, "idempotencyKey", 200);
  }

  private static String boundedRequiredText(String value, String fieldName, int maxLength) {
    String normalized = requiredText(value, fieldName).trim();
    if (normalized.length() > maxLength) {
      throw new IllegalArgumentException(fieldName + " is too long");
    }
    return normalized;
  }

  private static String optionalText(String value, String fieldName, int maxLength) {
    if (value == null) {
      return null;
    }
    String normalized = value.trim();
    if (normalized.isBlank()) {
      return null;
    }
    if (normalized.length() > maxLength) {
      throw new IllegalArgumentException(fieldName + " is too long");
    }
    return normalized;
  }
}
