package com.company.opsagent.controlplane.modules.agentruntime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.company.opsagent.contracts.agent.AgentToolCall;
import com.company.opsagent.contracts.agent.AgentToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class AgentscopeReActAgentClientTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void runsReActAgentWithReadOnlyToolSchemasAndReturnsFinalText() {
    AtomicReference<List<ToolSchema>> toolSchemas = new AtomicReference<>();
    Model model = new Model() {
      @Override
      public Flux<ChatResponse> stream(
          List<Msg> messages,
          List<ToolSchema> tools,
          GenerateOptions options) {
        toolSchemas.set(tools);
        return Flux.just(ChatResponse.builder()
            .id("response-1")
            .content(List.of(TextBlock.builder().text("node-1 is healthy").build()))
            .finishReason("stop")
            .build());
      }

      @Override
      public String getModelName() {
        return "fake-model";
      }
    };
    var client = new AgentscopeReActAgentClient(model, 3, Duration.ofSeconds(5));

    StepVerifier.create(client.run(new AgentscopeAgentInvocation(
            runtimeRequest(),
            List.of(readOnlyTool()),
            unusedToolExecutor())))
        .assertNext(response -> {
          assertEquals("SUCCEEDED", response.status());
          assertEquals("node-1 is healthy", response.summary());
          assertEquals(0, response.toolCallCount());
        })
        .verifyComplete();

    assertEquals(List.of("node-health"), toolSchemas.get().stream()
        .map(ToolSchema::getName)
        .toList());
  }

  @Test
  void streamsSanitizedProgressEventsWhenSinkIsConfigured() {
    List<AgentRuntimeProgressEvent> receivedEvents = new CopyOnWriteArrayList<>();
    AgentRuntimeProgressSink progressSink = (runtimeRequest, event) -> {
      assertEquals("workflow-1", runtimeRequest.workflowId());
      receivedEvents.add(event);
      return Mono.empty();
    };
    Model model = new Model() {
      @Override
      public Flux<ChatResponse> stream(
          List<Msg> messages,
          List<ToolSchema> tools,
          GenerateOptions options) {
        return Flux.just(ChatResponse.builder()
            .id("response-1")
            .content(List.of(TextBlock.builder().text("node-1 is healthy").build()))
            .finishReason("stop")
            .build());
      }

      @Override
      public String getModelName() {
        return "fake-streaming-model";
      }
    };
    var client = new AgentscopeReActAgentClient(
        model,
        3,
        Duration.ofSeconds(5),
        Integer.MAX_VALUE,
        objectMapper,
        java.time.Clock.systemUTC(),
        progressSink,
        new AgentscopeRuntimeEventMapper());

    StepVerifier.create(client.run(new AgentscopeAgentInvocation(
            runtimeRequest(),
            List.of(readOnlyTool()),
            unusedToolExecutor())))
        .assertNext(response -> {
          assertEquals("SUCCEEDED", response.status());
          assertEquals("node-1 is healthy", response.summary());
        })
        .verifyComplete();

    assertFalse(receivedEvents.isEmpty());
    assertTrue(receivedEvents.stream()
        .anyMatch(event -> event.kind() == AgentRuntimeProgressKind.MODEL_CALL_STARTED));
    assertTrue(receivedEvents.stream()
        .anyMatch(event -> event.kind() == AgentRuntimeProgressKind.AGENT_RESULT_READY));
    assertTrue(receivedEvents.stream()
        .noneMatch(event -> event.sourceEventType().startsWith("THINKING")));
    assertTrue(receivedEvents.stream()
        .noneMatch(event -> event.message().contains("node-1 is healthy")));
  }

  @Test
  void streamsProgressEventsAroundPlatformToolExecutionWhenSinkIsConfigured() {
    List<AgentRuntimeProgressEvent> receivedEvents = new CopyOnWriteArrayList<>();
    AgentRuntimeProgressSink progressSink = (runtimeRequest, event) -> {
      receivedEvents.add(event);
      return Mono.empty();
    };
    AgentToolExecutor toolExecutor = (runtimeRequest, toolCall) -> Mono.just(new AgentToolResult(
        "1.0",
        toolCall.toolCallId(),
        toolCall.taskId(),
        toolCall.workflowId(),
        "SUCCEEDED",
        toolCall.skill().outputSchemaId(),
        objectMapper.createObjectNode()
            .put("nodeId", toolCall.parameters().get("nodeId"))
            .put("status", "UP"),
        null,
        null,
        OffsetDateTime.of(2026, 6, 23, 10, 0, 0, 0, ZoneOffset.UTC)));
    var client = new AgentscopeReActAgentClient(
        new TwoStepToolUseModel(),
        4,
        Duration.ofSeconds(5),
        Integer.MAX_VALUE,
        objectMapper,
        java.time.Clock.systemUTC(),
        progressSink,
        new AgentscopeRuntimeEventMapper());

    StepVerifier.create(client.run(new AgentscopeAgentInvocation(
            runtimeRequest(),
            List.of(readOnlyTool()),
            toolExecutor)))
        .assertNext(response -> {
          assertEquals("SUCCEEDED", response.status());
          assertEquals("node-1 is healthy", response.summary());
          assertEquals(1, response.toolCallCount());
          assertEquals(1, response.toolResults().size());
        })
        .verifyComplete();

    assertTrue(receivedEvents.stream()
        .anyMatch(event -> event.kind() == AgentRuntimeProgressKind.TOOL_CALL_STARTED));
    assertTrue(receivedEvents.stream()
        .anyMatch(event -> event.kind() == AgentRuntimeProgressKind.TOOL_RESULT_COMPLETED));
    assertTrue(receivedEvents.stream()
        .anyMatch(event -> event.kind() == AgentRuntimeProgressKind.AGENT_RESULT_READY));
  }

  @Test
  void executesModelToolUseThroughPlatformToolExecutorAndFeedsResultBackToReAct() {
    var executedCall = new AtomicReference<AgentToolCall>();
    AgentToolExecutor toolExecutor = (runtimeRequest, toolCall) -> {
      executedCall.set(toolCall);
      return Mono.just(new AgentToolResult(
          "1.0",
          toolCall.toolCallId(),
          toolCall.taskId(),
          toolCall.workflowId(),
          "SUCCEEDED",
          toolCall.skill().outputSchemaId(),
          objectMapper.createObjectNode()
              .put("nodeId", toolCall.parameters().get("nodeId"))
              .put("status", "UP"),
          null,
          null,
          OffsetDateTime.of(2026, 6, 23, 10, 0, 0, 0, ZoneOffset.UTC)));
    };
    var model = new TwoStepToolUseModel();
    var client = new AgentscopeReActAgentClient(model, 4, Duration.ofSeconds(5));

    StepVerifier.create(client.run(new AgentscopeAgentInvocation(
            runtimeRequest(),
            List.of(readOnlyTool()),
            toolExecutor)))
        .assertNext(response -> {
          assertEquals("SUCCEEDED", response.status());
          assertEquals("node-1 is healthy", response.summary());
          assertEquals(1, response.toolCallCount());
          assertEquals(1, response.toolResults().size());
          assertEquals("SUCCEEDED", response.toolResults().getFirst().status());
          assertEquals("UP", response.toolResults().getFirst().output().path("status").asText());
        })
        .verifyComplete();

    AgentToolCall call = executedCall.get();
    assertNotNull(call);
    assertEquals("tool-call-1", call.toolCallId());
    assertEquals("task-1", call.taskId());
    assertEquals("workflow-1", call.workflowId());
    assertEquals(1, call.stepSequence());
    assertEquals("node-health", call.skill().skillId());
    assertEquals("1.0.0", call.skill().version());
    assertEquals("development", call.targetEnvironment());
    assertEquals(Map.of("nodeId", "node-1"), call.parameters());
    assertEquals("trace-1", call.trace().traceId());
    assertEquals("request-1", call.trace().requestId());
  }

  @Test
  void returnsRecordedToolResultsWhenModelFailsAfterToolExecution() {
    AgentToolExecutor toolExecutor = (runtimeRequest, toolCall) -> Mono.just(new AgentToolResult(
        "1.0",
        toolCall.toolCallId(),
        toolCall.taskId(),
        toolCall.workflowId(),
        "REJECTED",
        toolCall.skill().outputSchemaId(),
        objectMapper.createObjectNode(),
        "HTTP_SKILL_SOURCE_NOT_CONFIGURED",
        "configured HTTP skill endpoint is not configured",
        OffsetDateTime.of(2026, 6, 23, 10, 0, 0, 0, ZoneOffset.UTC)));
    var client = new AgentscopeReActAgentClient(
        new FailingAfterToolUseModel(),
        4,
        Duration.ofSeconds(5));

    StepVerifier.create(client.run(new AgentscopeAgentInvocation(
            runtimeRequest(),
            List.of(readOnlyTool()),
            toolExecutor)))
        .assertNext(response -> {
          assertEquals("AGENT_RUNTIME_FAILED", response.status());
          assertTrue(response.summary().contains("after 1 platform tool call"), response.summary());
          assertTrue(response.summary().contains("HTTP_SKILL_SOURCE_NOT_CONFIGURED"), response.summary());
          assertEquals(1, response.toolCallCount());
          assertEquals(1, response.toolResults().size());
          assertEquals("REJECTED", response.toolResults().getFirst().status());
          assertEquals("HTTP_SKILL_SOURCE_NOT_CONFIGURED", response.toolResults().getFirst().errorCode());
        })
        .verifyComplete();
  }

  @Test
  void rejectsToolCallsAfterConfiguredLimitWithoutCallingWorker() {
    AtomicInteger workerCalls = new AtomicInteger();
    AgentToolExecutor toolExecutor = (runtimeRequest, toolCall) -> {
      workerCalls.incrementAndGet();
      return Mono.just(new AgentToolResult(
          "1.0",
          toolCall.toolCallId(),
          toolCall.taskId(),
          toolCall.workflowId(),
          "SUCCEEDED",
          toolCall.skill().outputSchemaId(),
          objectMapper.createObjectNode()
              .put("nodeId", toolCall.parameters().get("nodeId"))
              .put("status", "UP"),
          null,
          null,
          OffsetDateTime.of(2026, 6, 23, 10, 0, 0, 0, ZoneOffset.UTC)));
    };
    var client = new AgentscopeReActAgentClient(
        new RepeatedToolUseModel(),
        5,
        Duration.ofSeconds(5),
        1);

    StepVerifier.create(client.run(new AgentscopeAgentInvocation(
            runtimeRequest(),
            List.of(readOnlyTool()),
            toolExecutor)))
        .assertNext(response -> {
          assertEquals("SUCCEEDED", response.status());
          assertEquals("tool limit enforced", response.summary());
          assertEquals(2, response.toolCallCount());
          assertEquals(2, response.toolResults().size());
          assertEquals("SUCCEEDED", response.toolResults().getFirst().status());
          AgentToolResult limitedResult = response.toolResults().get(1);
          assertEquals("REJECTED", limitedResult.status());
          assertEquals("AGENT_TOOL_CALL_LIMIT_EXCEEDED", limitedResult.errorCode());
        })
        .verifyComplete();

    assertEquals(1, workerCalls.get());
  }

  private AgentRuntimeRequest runtimeRequest() {
    return new AgentRuntimeRequest(
        "task-1",
        "workflow-1",
        "workspace-default",
        "operator-1",
        List.of("ROLE_ops-reader"),
        "development",
        "check node health",
        Map.of("nodeId", "node-1"),
        "trace-1",
        "request-1");
  }

  private AgentToolDescriptor readOnlyTool() {
    return new AgentToolDescriptor(
        "node-health",
        "1.0.0",
        "Read-only node health check",
        "node-health:1.0.0:input",
        "node-health:1.0.0:output",
        List.of("nodeId"),
        "READ_ONLY");
  }

  private AgentToolExecutor unusedToolExecutor() {
    return (runtimeRequest, toolCall) -> Mono.error(
        new AssertionError("tool executor must not be called without a model tool use"));
  }

  private static final class TwoStepToolUseModel implements Model {

    private int streamCalls;

    @Override
    public Flux<ChatResponse> stream(
        List<Msg> messages,
        List<ToolSchema> tools,
        GenerateOptions options) {
      streamCalls++;
      if (streamCalls == 1) {
        assertEquals(List.of("node-health"), tools.stream()
            .map(ToolSchema::getName)
            .toList());
        return Flux.just(ChatResponse.builder()
            .id("response-tool-use")
            .content(List.of(ToolUseBlock.builder()
                .id("tool-call-1")
                .name("node-health")
                .input(Map.of("nodeId", "node-1"))
                .content("{\"nodeId\":\"node-1\"}")
                .build()))
            .finishReason("tool_calls")
            .build());
      }

      List<String> toolResultText = messages.stream()
          .flatMap(message -> message.getContentBlocks(ToolResultBlock.class).stream())
          .flatMap(block -> block.getOutput().stream())
          .filter(TextBlock.class::isInstance)
          .map(TextBlock.class::cast)
          .map(TextBlock::getText)
          .toList();
      assertEquals(1, toolResultText.size());
      String result = toolResultText.getFirst();
      assertTrue(result.contains("\"status\":\"SUCCEEDED\""), result);
      assertTrue(result.contains("\"UP\""), result);
      return Flux.just(ChatResponse.builder()
          .id("response-final")
          .content(List.of(TextBlock.builder().text("node-1 is healthy").build()))
          .finishReason("stop")
          .build());
    }

    @Override
    public String getModelName() {
      return "fake-tool-model";
    }
  }

  private static final class FailingAfterToolUseModel implements Model {

    private int streamCalls;

    @Override
    public Flux<ChatResponse> stream(
        List<Msg> messages,
        List<ToolSchema> tools,
        GenerateOptions options) {
      streamCalls++;
      if (streamCalls == 1) {
        return Flux.just(ChatResponse.builder()
            .id("response-tool-use")
            .content(List.of(ToolUseBlock.builder()
                .id("tool-call-1")
                .name("node-health")
                .input(Map.of("nodeId", "node-1"))
                .content("{\"nodeId\":\"node-1\"}")
                .build()))
            .finishReason("tool_calls")
            .build());
      }
      return Flux.error(new RuntimeException("model failed after tool execution"));
    }

    @Override
    public String getModelName() {
      return "fake-failing-after-tool-model";
    }
  }

  private static final class RepeatedToolUseModel implements Model {

    private int streamCalls;

    @Override
    public Flux<ChatResponse> stream(
        List<Msg> messages,
        List<ToolSchema> tools,
        GenerateOptions options) {
      streamCalls++;
      if (streamCalls <= 2) {
        return Flux.just(ChatResponse.builder()
            .id("response-tool-use-" + streamCalls)
            .content(List.of(ToolUseBlock.builder()
                .id("tool-call-" + streamCalls)
                .name("node-health")
                .input(Map.of("nodeId", "node-1"))
                .content("{\"nodeId\":\"node-1\"}")
                .build()))
            .finishReason("tool_calls")
            .build());
      }
      return Flux.just(ChatResponse.builder()
          .id("response-final")
          .content(List.of(TextBlock.builder().text("tool limit enforced").build()))
          .finishReason("stop")
          .build());
    }

    @Override
    public String getModelName() {
      return "fake-repeated-tool-model";
    }
  }
}
