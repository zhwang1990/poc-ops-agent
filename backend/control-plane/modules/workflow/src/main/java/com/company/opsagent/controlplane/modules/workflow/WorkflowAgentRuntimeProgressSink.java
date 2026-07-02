package com.company.opsagent.controlplane.modules.workflow;

import com.company.opsagent.contracts.events.AgentRuntimeProgressPayload;
import com.company.opsagent.contracts.events.AgentRuntimeProgressPayloadKind;
import com.company.opsagent.contracts.events.SemanticEvent;
import com.company.opsagent.contracts.events.SemanticEventType;
import com.company.opsagent.controlplane.modules.agentruntime.AgentRuntimeProgressEvent;
import com.company.opsagent.controlplane.modules.agentruntime.AgentRuntimeProgressSink;
import com.company.opsagent.controlplane.modules.agentruntime.AgentRuntimeRequest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import reactor.core.publisher.Mono;

/**
 * 将 M04 已脱敏运行时进度转换为 M09 可消费的语义事件。
 *
 * <p>运行时进度事件使用保留序号段，避免和现有 Agent Tool requested/completed/rejected 事件序号冲突。
 */
public final class WorkflowAgentRuntimeProgressSink implements AgentRuntimeProgressSink {

  static final long RUNTIME_PROGRESS_SEQUENCE_BASE = 10_000L;

  private final ReadOnlyWorkflowStore eventStore;
  private final Clock clock;
  private final ConcurrentMap<String, AtomicLong> sequencesByWorkflowId = new ConcurrentHashMap<>();

  public WorkflowAgentRuntimeProgressSink(ReadOnlyWorkflowStore eventStore, Clock clock) {
    this.eventStore = eventStore;
    this.clock = clock;
  }

  @Override
  public Mono<Void> emit(AgentRuntimeRequest runtimeRequest, AgentRuntimeProgressEvent event) {
    long sequence = sequencesByWorkflowId
        .computeIfAbsent(runtimeRequest.workflowId(), ignored -> new AtomicLong(RUNTIME_PROGRESS_SEQUENCE_BASE))
        .incrementAndGet();
    return eventStore.appendEvent(
        runtimeRequest.workflowId(),
        sequence,
        new SemanticEvent(
            "1.0",
            UUID.randomUUID().toString(),
            runtimeRequest.workflowId(),
            sequence,
            OffsetDateTime.now(clock),
            SemanticEventType.AGENT_RUNTIME_PROGRESS,
            new AgentRuntimeProgressPayload(
                SemanticEventType.AGENT_RUNTIME_PROGRESS,
                AgentRuntimeProgressPayloadKind.valueOf(event.kind().name()),
                event.message(),
                event.replyId(),
                event.blockId(),
                event.toolCallId(),
                event.toolName(),
                event.agentId(),
                event.sessionId(),
                event.subagentId(),
                event.inputTokens(),
                event.outputTokens(),
                event.totalTokens(),
                event.modelTimeSeconds(),
                event.sensitiveContentSuppressed())));
  }
}
