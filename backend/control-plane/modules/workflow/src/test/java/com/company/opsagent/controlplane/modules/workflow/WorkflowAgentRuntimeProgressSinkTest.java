package com.company.opsagent.controlplane.modules.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.company.opsagent.contracts.events.AgentRuntimeProgressPayload;
import com.company.opsagent.contracts.events.AgentRuntimeProgressPayloadKind;
import com.company.opsagent.contracts.events.SemanticEventType;
import com.company.opsagent.controlplane.modules.agentruntime.AgentRuntimeProgressEvent;
import com.company.opsagent.controlplane.modules.agentruntime.AgentRuntimeProgressKind;
import com.company.opsagent.controlplane.modules.agentruntime.AgentRuntimeRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class WorkflowAgentRuntimeProgressSinkTest {

  private final Clock clock = Clock.fixed(Instant.parse("2026-07-01T00:00:00Z"), ZoneOffset.UTC);

  @Test
  void appendsSanitizedRuntimeProgressEventsToReservedSemanticEventRange() {
    var store = new InMemoryReadOnlyWorkflowStoreFixture();
    var sink = new WorkflowAgentRuntimeProgressSink(store, clock);
    String workflowId = "11111111-1111-4111-8111-111111111111";

    StepVerifier.create(sink.emit(
            runtimeRequest(workflowId),
            new AgentRuntimeProgressEvent(
                "MODEL_CALL_END",
                AgentRuntimeProgressKind.MODEL_CALL_COMPLETED,
                "model call completed",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                12,
                5,
                17,
                0.42,
                false)))
        .verifyComplete();

    StepVerifier.create(store.loadEventsAfter(workflowId, 10_000))
        .assertNext(event -> {
          UUID.fromString(event.eventId());
          assertEquals(10_001, event.sequence());
          assertEquals(SemanticEventType.AGENT_RUNTIME_PROGRESS, event.type());
          var payload = assertInstanceOf(AgentRuntimeProgressPayload.class, event.payload());
          assertEquals(AgentRuntimeProgressPayloadKind.MODEL_CALL_COMPLETED, payload.progressKind());
          assertEquals("model call completed", payload.message());
          assertEquals(12, payload.inputTokens());
          assertEquals(5, payload.outputTokens());
          assertEquals(17, payload.totalTokens());
          assertEquals(0.42, payload.modelTimeSeconds());
        })
        .verifyComplete();
  }

  private AgentRuntimeRequest runtimeRequest(String workflowId) {
    return new AgentRuntimeRequest(
        "task-1",
        workflowId,
        "workspace-default",
        "operator-1",
        List.of("ROLE_ops-reader"),
        "development",
        "check node health",
        Map.of("nodeId", "node-1"),
        "trace-1",
        "request-1");
  }
}
