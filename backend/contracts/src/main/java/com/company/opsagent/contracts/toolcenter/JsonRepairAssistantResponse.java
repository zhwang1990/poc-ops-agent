package com.company.opsagent.contracts.toolcenter;

import static com.company.opsagent.contracts.ContractValues.required;
import static com.company.opsagent.contracts.ContractValues.requiredText;

import java.util.List;

public record JsonRepairAssistantResponse(
    String contractVersion,
    JsonRepairAssistantStatus status,
    JsonRepairAssistantAction assistantAction,
    String summary,
    String repairedJson,
    String failureReason,
    List<String> safetyNotes,
    boolean validationRequired,
    String skillId,
    String modelProviderFingerprint) {

  public JsonRepairAssistantResponse {
    if (!"1.0".equals(contractVersion)) {
      throw new IllegalArgumentException("contractVersion must be 1.0");
    }
    status = required(status, "status");
    assistantAction = required(assistantAction, "assistantAction");
    summary = requiredText(summary, "summary").trim();
    repairedJson = optionalText(repairedJson);
    failureReason = optionalText(failureReason);
    safetyNotes = safetyNotes == null
        ? List.of()
        : safetyNotes.stream()
            .filter(note -> note != null && !note.isBlank())
            .map(String::trim)
            .toList();
    if (status == JsonRepairAssistantStatus.SUCCEEDED && !validationRequired) {
      throw new IllegalArgumentException("successful JSON repair assistant responses require validation");
    }
    if (status == JsonRepairAssistantStatus.SUCCEEDED && repairedJson == null) {
      throw new IllegalArgumentException("successful JSON repair assistant responses require repairedJson");
    }
    skillId = requiredText(skillId, "skillId").trim();
    modelProviderFingerprint = optionalText(modelProviderFingerprint);
  }

  private static String optionalText(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }
}
