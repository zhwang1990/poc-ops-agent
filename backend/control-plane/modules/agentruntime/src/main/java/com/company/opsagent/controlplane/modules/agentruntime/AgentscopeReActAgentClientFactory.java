package com.company.opsagent.controlplane.modules.agentruntime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.model.transport.HttpTransportConfig;
import io.agentscope.core.model.transport.JdkHttpTransport;
import java.time.Clock;
import java.time.Duration;

/**
 * AgentScope SDK 客户端工厂。
 *
 * <p>模型供应方、baseUrl 和 OpenAI-compatible 适配细节都收敛在 M04 运行时模块内，
 * 避免其他模块直接依赖 AgentScope SDK 类型或模型构造方式。
 */
public final class AgentscopeReActAgentClientFactory {

  private AgentscopeReActAgentClientFactory() {
  }

  public static AgentscopeAgentClient openAiCompatible(
      String apiKey,
      String modelName,
      String baseUrl,
      int maxIters,
      int maxToolCalls,
      Duration timeout) {
    return openAiCompatible(
        apiKey,
        modelName,
        baseUrl,
        maxIters,
        maxToolCalls,
        timeout,
        AgentRuntimeProgressSink.noop());
  }

  public static AgentscopeAgentClient openAiCompatible(
      String apiKey,
      String modelName,
      String baseUrl,
      int maxIters,
      int maxToolCalls,
      Duration timeout,
      AgentRuntimeProgressSink progressSink) {
    var httpTransport = JdkHttpTransport.builder()
        .config(HttpTransportConfig.builder()
            .connectTimeout(timeout)
            .readTimeout(timeout)
            .writeTimeout(timeout)
            .build())
        .build();
    var builder = OpenAIChatModel.builder()
        .apiKey(apiKey)
        .modelName(modelName)
        .stream(false)
        .httpTransport(httpTransport);
    if (baseUrl != null && !baseUrl.isBlank()) {
      builder.baseUrl(baseUrl);
    }
    return new AgentscopeReActAgentClient(
        builder.build(),
        maxIters,
        timeout,
        maxToolCalls,
        new ObjectMapper(),
        Clock.systemUTC(),
        progressSink,
        new AgentscopeRuntimeEventMapper());
  }
}
