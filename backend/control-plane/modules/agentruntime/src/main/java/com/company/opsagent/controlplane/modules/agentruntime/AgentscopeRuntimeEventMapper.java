package com.company.opsagent.controlplane.modules.agentruntime;

import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.RequireExternalExecutionEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.SubagentExposedEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.model.ChatUsage;
import java.util.Optional;

/**
 * 将 AgentScope 2.0 原始事件映射为平台内部运行时进度事件。
 */
public final class AgentscopeRuntimeEventMapper {

  public Optional<AgentRuntimeProgressEvent> map(AgentEvent event) {
    if (event == null || event.getType() == null || isThinking(event.getType())) {
      return Optional.empty();
    }
    return Optional.of(toProgressEvent(event));
  }

  private AgentRuntimeProgressEvent toProgressEvent(AgentEvent event) {
    return switch (event.getType()) {
      case AGENT_START -> agentStart((AgentStartEvent) event);
      case AGENT_END -> agentEnd((AgentEndEvent) event);
      case AGENT_RESULT -> agentResult((AgentResultEvent) event);
      case MODEL_CALL_START -> modelCallStart((ModelCallStartEvent) event);
      case MODEL_CALL_END -> modelCallEnd((ModelCallEndEvent) event);
      case TEXT_BLOCK_DELTA -> textDelta((TextBlockDeltaEvent) event);
      case TOOL_CALL_START -> toolCallStart((ToolCallStartEvent) event);
      case TOOL_CALL_DELTA -> toolCallDelta((ToolCallDeltaEvent) event);
      case TOOL_CALL_END -> toolCallEnd((ToolCallEndEvent) event);
      case TOOL_RESULT_START -> toolResultStart((ToolResultStartEvent) event);
      case TOOL_RESULT_TEXT_DELTA -> toolResultTextDelta((ToolResultTextDeltaEvent) event);
      case TOOL_RESULT_END -> toolResultEnd((ToolResultEndEvent) event);
      case SUBAGENT_EXPOSED -> subagentExposed((SubagentExposedEvent) event);
      case EXCEED_MAX_ITERS -> exceedMaxIters((ExceedMaxItersEvent) event);
      case REQUIRE_USER_CONFIRM -> requireUserConfirm((RequireUserConfirmEvent) event);
      case REQUIRE_EXTERNAL_EXECUTION -> requireExternalExecution((RequireExternalExecutionEvent) event);
      case CUSTOM -> custom((CustomEvent) event);
      default -> AgentRuntimeProgressEvent.of(event.getType().name(), AgentRuntimeProgressKind.UNKNOWN, "agent runtime event received");
    };
  }

  private boolean isThinking(AgentEventType type) {
    return type == AgentEventType.THINKING_BLOCK_START
        || type == AgentEventType.THINKING_BLOCK_DELTA
        || type == AgentEventType.THINKING_BLOCK_END;
  }

  private AgentRuntimeProgressEvent agentStart(AgentStartEvent event) {
    return new AgentRuntimeProgressEvent(
        event.getType().name(),
        AgentRuntimeProgressKind.AGENT_STARTED,
        safeMessage(event.getName(), "agent started"),
        event.getReplyId(),
        null,
        null,
        null,
        null,
        event.getSessionId(),
        null,
        0,
        0,
        0,
        0,
        false);
  }

  private AgentRuntimeProgressEvent agentEnd(AgentEndEvent event) {
    return withReply(event, AgentRuntimeProgressKind.AGENT_ENDED, "agent ended", event.getReplyId());
  }

  private AgentRuntimeProgressEvent agentResult(AgentResultEvent event) {
    return withReply(event, AgentRuntimeProgressKind.AGENT_RESULT_READY, "agent result ready", null);
  }

  private AgentRuntimeProgressEvent modelCallStart(ModelCallStartEvent event) {
    return withReply(event, AgentRuntimeProgressKind.MODEL_CALL_STARTED, "model call started", event.getReplyId());
  }

  private AgentRuntimeProgressEvent modelCallEnd(ModelCallEndEvent event) {
    ChatUsage usage = event.getUsage();
    int inputTokens = usage == null ? 0 : usage.getInputTokens();
    int outputTokens = usage == null ? 0 : usage.getOutputTokens();
    int totalTokens = usage == null ? inputTokens + outputTokens : usage.getTotalTokens();
    double modelTime = usage == null ? 0 : usage.getTime();
    return new AgentRuntimeProgressEvent(
        event.getType().name(),
        AgentRuntimeProgressKind.MODEL_CALL_COMPLETED,
        "model call completed",
        event.getReplyId(),
        null,
        null,
        null,
        null,
        null,
        null,
        inputTokens,
        outputTokens,
        totalTokens,
        modelTime,
        false);
  }

  private AgentRuntimeProgressEvent textDelta(TextBlockDeltaEvent event) {
    return new AgentRuntimeProgressEvent(
        event.getType().name(),
        AgentRuntimeProgressKind.TEXT_DELTA_AVAILABLE,
        "assistant text delta available",
        event.getReplyId(),
        event.getBlockId(),
        null,
        null,
        null,
        null,
        null,
        0,
        0,
        0,
        0,
        true);
  }

  private AgentRuntimeProgressEvent toolCallStart(ToolCallStartEvent event) {
    return toolEvent(event, AgentRuntimeProgressKind.TOOL_CALL_STARTED, "tool call started", event.getReplyId(),
        event.getToolCallId(), event.getToolCallName(), false);
  }

  private AgentRuntimeProgressEvent toolCallDelta(ToolCallDeltaEvent event) {
    return toolEvent(event, AgentRuntimeProgressKind.TOOL_CALL_DELTA_AVAILABLE, "tool call arguments delta available",
        event.getReplyId(), event.getToolCallId(), event.getToolCallName(), true);
  }

  private AgentRuntimeProgressEvent toolCallEnd(ToolCallEndEvent event) {
    return toolEvent(event, AgentRuntimeProgressKind.TOOL_CALL_COMPLETED, "tool call completed", event.getReplyId(),
        event.getToolCallId(), event.getToolCallName(), false);
  }

  private AgentRuntimeProgressEvent toolResultStart(ToolResultStartEvent event) {
    return toolEvent(event, AgentRuntimeProgressKind.TOOL_RESULT_STARTED, "tool result started", event.getReplyId(),
        event.getToolCallId(), event.getToolCallName(), false);
  }

  private AgentRuntimeProgressEvent toolResultTextDelta(ToolResultTextDeltaEvent event) {
    return toolEvent(event, AgentRuntimeProgressKind.TOOL_RESULT_DELTA_AVAILABLE, "tool result text delta available",
        event.getReplyId(), event.getToolCallId(), event.getToolCallName(), true);
  }

  private AgentRuntimeProgressEvent toolResultEnd(ToolResultEndEvent event) {
    return toolEvent(event, AgentRuntimeProgressKind.TOOL_RESULT_COMPLETED, "tool result completed", event.getReplyId(),
        event.getToolCallId(), event.getToolCallName(), false);
  }

  private AgentRuntimeProgressEvent subagentExposed(SubagentExposedEvent event) {
    return new AgentRuntimeProgressEvent(
        event.getType().name(),
        AgentRuntimeProgressKind.SUBAGENT_EXPOSED,
        safeMessage(event.getLabel(), "subagent exposed"),
        null,
        null,
        null,
        null,
        event.getAgentId(),
        event.getSessionId(),
        event.getSubagentId(),
        0,
        0,
        0,
        0,
        false);
  }

  private AgentRuntimeProgressEvent exceedMaxIters(ExceedMaxItersEvent event) {
    return withReply(event, AgentRuntimeProgressKind.ITERATION_LIMIT_EXCEEDED, "agent iteration limit exceeded", event.getReplyId());
  }

  private AgentRuntimeProgressEvent requireUserConfirm(RequireUserConfirmEvent event) {
    return withReply(event, AgentRuntimeProgressKind.USER_CONFIRMATION_REQUIRED, "agent requested user confirmation", event.getReplyId());
  }

  private AgentRuntimeProgressEvent requireExternalExecution(RequireExternalExecutionEvent event) {
    return withReply(event, AgentRuntimeProgressKind.EXTERNAL_EXECUTION_REQUIRED, "agent requested external execution", event.getReplyId());
  }

  private AgentRuntimeProgressEvent custom(CustomEvent event) {
    return AgentRuntimeProgressEvent.of(event.getType().name(), AgentRuntimeProgressKind.CUSTOM, safeMessage(event.getName(), "custom agent event"));
  }

  private AgentRuntimeProgressEvent withReply(
      AgentEvent event,
      AgentRuntimeProgressKind kind,
      String message,
      String replyId) {
    return new AgentRuntimeProgressEvent(
        event.getType().name(),
        kind,
        message,
        replyId,
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

  private AgentRuntimeProgressEvent toolEvent(
      AgentEvent event,
      AgentRuntimeProgressKind kind,
      String message,
      String replyId,
      String toolCallId,
      String toolName,
      boolean sensitiveContentSuppressed) {
    return new AgentRuntimeProgressEvent(
        event.getType().name(),
        kind,
        message,
        replyId,
        null,
        toolCallId,
        toolName,
        null,
        null,
        null,
        0,
        0,
        0,
        0,
        sensitiveContentSuppressed);
  }

  private String safeMessage(String value, String fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    return value.strip();
  }
}
