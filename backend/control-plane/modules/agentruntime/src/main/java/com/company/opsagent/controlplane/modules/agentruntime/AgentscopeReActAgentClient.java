package com.company.opsagent.controlplane.modules.agentruntime;

import com.company.opsagent.contracts.agent.AgentToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.publisher.Mono;

/**
 * 基于 AgentScope ReActAgent 的主 Agent Runtime 循环。
 *
 * <p>它负责 Prompt、模型和 SDK 工具注册；真实工具执行必须经 AgentToolExecutor
 * 回到平台服务端边界，不能使用 AgentScope 的 SchemaOnlyTool 外部挂起路径绕过策略、
 * workflow、审计或 Worker 隔离。
 */
public final class AgentscopeReActAgentClient implements AgentscopeAgentClient {

  private static final String AGENT_NAME = "ops-agent-primary";
  private static final String SCHEMA_VERSION = "1.0";
  private static final String SYSTEM_PROMPT = """
      You are the primary enterprise operations diagnostic agent.
      Use only the read-only tools exposed by the platform.
      Do not reveal hidden reasoning. Return a concise auditable diagnostic summary.
      """;

  private final Model model;
  private final int maxIters;
  private final Duration timeout;
  private final int maxToolCalls;
  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final AgentRuntimeProgressSink progressSink;
  private final AgentscopeRuntimeEventMapper progressEventMapper;

  public AgentscopeReActAgentClient(
      Model model,
      int maxIters,
      Duration timeout) {
    this(model, maxIters, timeout, Integer.MAX_VALUE, new ObjectMapper(), Clock.systemUTC());
  }

  public AgentscopeReActAgentClient(
      Model model,
      int maxIters,
      Duration timeout,
      int maxToolCalls) {
    this(model, maxIters, timeout, maxToolCalls, new ObjectMapper(), Clock.systemUTC());
  }

  AgentscopeReActAgentClient(
      Model model,
      int maxIters,
      Duration timeout,
      ObjectMapper objectMapper,
      Clock clock) {
    this(model, maxIters, timeout, Integer.MAX_VALUE, objectMapper, clock);
  }

  AgentscopeReActAgentClient(
      Model model,
      int maxIters,
      Duration timeout,
      int maxToolCalls,
      ObjectMapper objectMapper,
      Clock clock) {
    this(
        model,
        maxIters,
        timeout,
        maxToolCalls,
        objectMapper,
        clock,
        AgentRuntimeProgressSink.noop(),
        new AgentscopeRuntimeEventMapper());
  }

  AgentscopeReActAgentClient(
      Model model,
      int maxIters,
      Duration timeout,
      int maxToolCalls,
      ObjectMapper objectMapper,
      Clock clock,
      AgentRuntimeProgressSink progressSink,
      AgentscopeRuntimeEventMapper progressEventMapper) {
    this.model = model;
    this.maxIters = maxIters;
    this.timeout = timeout;
    this.maxToolCalls = maxToolCalls <= 0 ? 1 : maxToolCalls;
    this.objectMapper = objectMapper;
    this.clock = clock;
    this.progressSink = progressSink == null ? AgentRuntimeProgressSink.noop() : progressSink;
    this.progressEventMapper = progressEventMapper == null ? new AgentscopeRuntimeEventMapper() : progressEventMapper;
  }

  @Override
  public Mono<AgentscopeAgentResponse> run(AgentscopeAgentInvocation invocation) {
    AtomicLong stepSequence = new AtomicLong();
    List<AgentToolResult> toolResults = new CopyOnWriteArrayList<>();
    AgentToolExecutor recordingToolExecutor = (runtimeRequest, toolCall) -> {
      Mono<AgentToolResult> result = toolCall.stepSequence() > maxToolCalls
          ? Mono.just(toolCallLimitExceededResult(toolCall))
          : invocation.toolExecutor().execute(runtimeRequest, toolCall);
      return result.doOnNext(toolResults::add);
    };
    Toolkit toolkit = new Toolkit();
    /*
     * 必须注册真实 AgentTool，而不是 registerSchemas。registerSchemas 会创建
     * SchemaOnlyTool 并把执行挂起给外部调用方；这里要让 ReAct 立刻回调平台
     * AgentToolExecutor，由服务端重新授权、记录事实并提交 Worker。
     */
    invocation.tools().stream()
        .filter(AgentToolDescriptor::isReadOnly)
        .map(tool -> new AgentscopePlatformAgentTool(
            invocation.request(),
            tool,
            recordingToolExecutor,
            stepSequence,
            objectMapper,
            clock))
        .forEach(toolkit::registerAgentTool);
    ReActAgent agent = ReActAgent.builder()
        .name(AGENT_NAME)
        .sysPrompt(SYSTEM_PROMPT)
        .model(model)
        .toolkit(toolkit)
        .maxIters(maxIters)
        .build();
    Msg userMessage = toUserMessage(invocation);
    if (progressSink != AgentRuntimeProgressSink.noop()) {
      return runWithProgressEvents(invocation, agent, userMessage, stepSequence, toolResults);
    }
    return agent.call(userMessage)
        .timeout(timeout)
        .map(message -> new AgentscopeAgentResponse(
            "SUCCEEDED",
            summary(message),
            Math.toIntExact(stepSequence.get()),
            List.copyOf(toolResults)))
        .onErrorResume(error -> Mono.just(failedResponse(toolResults, stepSequence)));
  }

  private Mono<AgentscopeAgentResponse> runWithProgressEvents(
      AgentscopeAgentInvocation invocation,
      ReActAgent agent,
      Msg userMessage,
      AtomicLong stepSequence,
      List<AgentToolResult> toolResults) {
    AtomicReference<Msg> resultMessage = new AtomicReference<>();
    return agent.streamEvents(userMessage)
        .timeout(timeout)
        .concatMap(event -> {
          if (event instanceof AgentResultEvent resultEvent) {
            resultMessage.set(resultEvent.getResult());
          }
          return emitProgressEvent(invocation.request(), event);
        })
        .then(Mono.fromSupplier(() -> {
          Msg message = resultMessage.get();
          if (message == null) {
            return failedResponse(toolResults, stepSequence);
          }
          return new AgentscopeAgentResponse(
              "SUCCEEDED",
              summary(message),
              Math.toIntExact(stepSequence.get()),
              List.copyOf(toolResults));
        }))
        .onErrorResume(error -> Mono.just(failedResponse(toolResults, stepSequence)));
  }

  private Mono<Void> emitProgressEvent(AgentRuntimeRequest runtimeRequest, AgentEvent event) {
    return progressEventMapper.map(event)
        .map(progressEvent -> progressSink.emit(runtimeRequest, progressEvent))
        .orElse(Mono.empty());
  }

  private Msg toUserMessage(AgentscopeAgentInvocation invocation) {
    AgentRuntimeRequest request = invocation.request();
    return Msg.builder()
        .name("operator")
        .role(MsgRole.USER)
        .textContent("""
            workflowId: %s
            targetEnvironment: %s
            userIntent: %s
            inputParameters: %s
            """.formatted(
                request.workflowId(),
                request.targetEnvironment(),
                request.userIntent(),
                request.inputParameters()))
        .build();
  }

  private String summary(Msg message) {
    String text = message.getTextContent();
    if (text == null || text.isBlank()) {
      return "AgentScope runtime completed without a textual summary.";
    }
    return sanitizedSummary(text.strip());
  }

  private String sanitizedSummary(String text) {
    String finalAnswer = textAfterLastMarker(text, List.of("Final Answer:", "Final:", "Answer:"));
    if (finalAnswer != null && !containsReasoningMarker(finalAnswer)) {
      return finalAnswer;
    }
    if (containsReasoningMarker(text)) {
      return "AgentScope runtime produced a summary that was withheld because it included internal reasoning markers.";
    }
    return text;
  }

  private String textAfterLastMarker(String text, List<String> markers) {
    String lowerText = text.toLowerCase(Locale.ROOT);
    int markerIndex = -1;
    String matchedMarker = null;
    for (String marker : markers) {
      int index = lowerText.lastIndexOf(marker.toLowerCase(Locale.ROOT));
      if (index > markerIndex) {
        markerIndex = index;
        matchedMarker = marker;
      }
    }
    if (markerIndex < 0) {
      return null;
    }
    String candidate = text.substring(markerIndex + matchedMarker.length()).strip();
    return candidate.isBlank() ? null : candidate;
  }

  private boolean containsReasoningMarker(String text) {
    return text.lines()
        .map(String::strip)
        .map(line -> line.toLowerCase(Locale.ROOT))
        .anyMatch(line -> line.startsWith("thought:")
            || line.startsWith("reasoning:")
            || line.startsWith("chain of thought")
            || line.startsWith("action:")
            || line.startsWith("observation:"));
  }

  private AgentToolResult toolCallLimitExceededResult(com.company.opsagent.contracts.agent.AgentToolCall toolCall) {
    return new AgentToolResult(
        SCHEMA_VERSION,
        toolCall.toolCallId(),
        toolCall.taskId(),
        toolCall.workflowId(),
        "REJECTED",
        toolCall.skill().outputSchemaId(),
        objectMapper.createObjectNode(),
        "AGENT_TOOL_CALL_LIMIT_EXCEEDED",
        "agent tool call limit exceeded",
        OffsetDateTime.now(clock));
  }

  private AgentscopeAgentResponse failedResponse(
      List<AgentToolResult> toolResults,
      AtomicLong stepSequence) {
    if (toolResults.isEmpty()) {
      return new AgentscopeAgentResponse(
          "AGENT_RUNTIME_FAILED",
          "AgentScope runtime failed before producing a valid result.",
          Math.toIntExact(stepSequence.get()));
    }
    AgentToolResult lastResult = toolResults.getLast();
    return new AgentscopeAgentResponse(
        "AGENT_RUNTIME_FAILED",
        failedAfterToolSummary(toolResults.size(), lastResult),
        Math.toIntExact(stepSequence.get()),
        List.copyOf(toolResults));
  }

  private String failedAfterToolSummary(int toolResultCount, AgentToolResult lastResult) {
    String toolCallLabel = toolResultCount == 1 ? "platform tool call" : "platform tool calls";
    StringBuilder summary = new StringBuilder()
        .append("AgentScope runtime failed after ")
        .append(toolResultCount)
        .append(' ')
        .append(toolCallLabel)
        .append("; returning recorded tool result. Last tool status=")
        .append(lastResult.status());
    if (lastResult.errorCode() != null && !lastResult.errorCode().isBlank()) {
      summary.append(", errorCode=").append(lastResult.errorCode());
    }
    if (lastResult.errorMessage() != null && !lastResult.errorMessage().isBlank()) {
      summary.append(", errorMessage=").append(lastResult.errorMessage());
    }
    return summary.append('.').toString();
  }
}
