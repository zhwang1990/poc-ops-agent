package com.company.opsagent.controlplane.modules.agentruntime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.SubagentExposedEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.model.ChatUsage;
import org.junit.jupiter.api.Test;

class AgentscopeRuntimeEventMapperTest {

  private final AgentscopeRuntimeEventMapper mapper = new AgentscopeRuntimeEventMapper();

  @Test
  void dropsThinkingEventsWithoutLeakingReasoningText() {
    var event = new ThinkingBlockDeltaEvent(
        "reply-1",
        "block-1",
        "private chain of thought that must never be published");

    assertTrue(mapper.map(event).isEmpty());
  }

  @Test
  void mapsToolCallDeltaWithoutArgumentText() {
    var event = new ToolCallDeltaEvent(
        "reply-1",
        "tool-call-1",
        "node-health",
        "{\"nodeId\":\"node-1\",\"secret\":\"must-not-leak\"}");

    AgentRuntimeProgressEvent progress = mapper.map(event).orElseThrow();

    assertEquals(AgentRuntimeProgressKind.TOOL_CALL_DELTA_AVAILABLE, progress.kind());
    assertEquals("TOOL_CALL_DELTA", progress.sourceEventType());
    assertEquals("reply-1", progress.replyId());
    assertEquals("tool-call-1", progress.toolCallId());
    assertEquals("node-health", progress.toolName());
    assertTrue(progress.sensitiveContentSuppressed());
    assertFalse(progress.message().contains("must-not-leak"), progress.message());
  }

  @Test
  void mapsModelUsageFromModelCallEnd() {
    var event = new ModelCallEndEvent("reply-1", new ChatUsage(12, 7, 0.25));

    AgentRuntimeProgressEvent progress = mapper.map(event).orElseThrow();

    assertEquals(AgentRuntimeProgressKind.MODEL_CALL_COMPLETED, progress.kind());
    assertEquals("MODEL_CALL_END", progress.sourceEventType());
    assertEquals("reply-1", progress.replyId());
    assertEquals(12, progress.inputTokens());
    assertEquals(7, progress.outputTokens());
    assertEquals(19, progress.totalTokens());
    assertEquals(0.25, progress.modelTimeSeconds());
  }

  @Test
  void mapsSubagentExposureForFutureSupervisorUi() {
    var event = new SubagentExposedEvent(
        "subagent-1",
        "agent-1",
        "workflow-1",
        "sql-readonly-analyst");

    AgentRuntimeProgressEvent progress = mapper.map(event).orElseThrow();

    assertEquals(AgentRuntimeProgressKind.SUBAGENT_EXPOSED, progress.kind());
    assertEquals("SUBAGENT_EXPOSED", progress.sourceEventType());
    assertEquals("subagent-1", progress.subagentId());
    assertEquals("agent-1", progress.agentId());
    assertEquals("workflow-1", progress.sessionId());
    assertEquals("sql-readonly-analyst", progress.message());
  }
}
