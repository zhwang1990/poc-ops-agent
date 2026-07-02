package com.company.opsagent.controlplane.modules.agentruntime;

/**
 * 已脱敏的 Agent 运行时进度事件。
 *
 * <p>该记录只保留固定字段，避免把 AgentScope SDK 原始动态载荷直接带入平台事件流。
 */
public record AgentRuntimeProgressEvent(
    String sourceEventType,
    AgentRuntimeProgressKind kind,
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
    boolean sensitiveContentSuppressed) {

  public AgentRuntimeProgressEvent {
    sourceEventType = requiredText(sourceEventType, "sourceEventType");
    if (kind == null) {
      throw new IllegalArgumentException("kind must not be null");
    }
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

  public static AgentRuntimeProgressEvent of(
      String sourceEventType,
      AgentRuntimeProgressKind kind,
      String message) {
    return new AgentRuntimeProgressEvent(
        sourceEventType,
        kind,
        message,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        0,
        0,
        0,
        0,
        false);
  }

  private static String requiredText(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
    return value.strip();
  }

  private static String optionalText(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.strip();
  }
}
