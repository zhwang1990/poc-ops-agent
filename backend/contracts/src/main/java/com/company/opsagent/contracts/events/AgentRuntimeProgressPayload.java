package com.company.opsagent.contracts.events;

import static com.company.opsagent.contracts.ContractValues.required;
import static com.company.opsagent.contracts.ContractValues.requiredText;

/**
 * Agent 运行时进度事件载荷。
 *
 * <p>该载荷只包含脱敏后的平台字段，不包含 AgentScope SDK 原始事件名、模型推理正文、
 * 工具参数增量或工具结果正文增量。
 */
public record AgentRuntimeProgressPayload(
    SemanticEventType payloadType,
    AgentRuntimeProgressPayloadKind progressKind,
    String message,
    String replyId,
    String blockId,
    String toolCallId,
    String toolName,
    String agentId,
    String sessionId,
    String subagentId,
    int inputTokens,
    int outputTokens,
    int totalTokens,
    double modelTimeSeconds,
    boolean sensitiveContentSuppressed) implements SemanticEventPayload {

  public AgentRuntimeProgressPayload {
    if (payloadType != SemanticEventType.AGENT_RUNTIME_PROGRESS) {
      throw new IllegalArgumentException("payload type must be AGENT_RUNTIME_PROGRESS");
    }
    progressKind = required(progressKind, "progressKind");
    message = requiredText(message, "message");
    replyId = optionalText(replyId);
    blockId = optionalText(blockId);
    toolCallId = optionalText(toolCallId);
    toolName = optionalText(toolName);
    agentId = optionalText(agentId);
    sessionId = optionalText(sessionId);
    subagentId = optionalText(subagentId);
    if (inputTokens < 0) {
      throw new IllegalArgumentException("inputTokens must not be negative");
    }
    if (outputTokens < 0) {
      throw new IllegalArgumentException("outputTokens must not be negative");
    }
    if (totalTokens < 0) {
      throw new IllegalArgumentException("totalTokens must not be negative");
    }
    if (modelTimeSeconds < 0) {
      throw new IllegalArgumentException("modelTimeSeconds must not be negative");
    }
  }

  private static String optionalText(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.strip();
  }
}
