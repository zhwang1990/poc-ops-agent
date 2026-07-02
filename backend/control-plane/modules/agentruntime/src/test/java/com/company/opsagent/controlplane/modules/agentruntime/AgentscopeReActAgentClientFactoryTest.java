package com.company.opsagent.controlplane.modules.agentruntime;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.model.OpenAIClient;
import io.agentscope.core.model.transport.HttpTransportConfig;
import io.agentscope.core.model.transport.JdkHttpTransport;
import java.lang.reflect.Field;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class AgentscopeReActAgentClientFactoryTest {

  @Test
  void createsOpenAiCompatibleReActClientWithoutExposingAgentscopeModelConstruction() {
    AgentscopeAgentClient client = AgentscopeReActAgentClientFactory.openAiCompatible(
        "test-api-key",
        "test-model",
        "https://model-provider.example/v1",
        3,
        2,
        Duration.ofSeconds(5));

    assertInstanceOf(AgentscopeReActAgentClient.class, client);
  }

  @Test
  void appliesProviderTimeoutToOpenAiCompatibleHttpTransport() throws Exception {
    Duration timeout = Duration.ofSeconds(7);

    AgentscopeAgentClient client = AgentscopeReActAgentClientFactory.openAiCompatible(
        "test-api-key",
        "test-model",
        "https://model-provider.example/v1",
        3,
        2,
        timeout);

    AgentscopeReActAgentClient reActClient = assertInstanceOf(AgentscopeReActAgentClient.class, client);
    OpenAIChatModel model = assertInstanceOf(OpenAIChatModel.class, fieldValue(reActClient, "model"));
    OpenAIClient openAiClient = assertInstanceOf(OpenAIClient.class, fieldValue(model, "client"));
    JdkHttpTransport transport = assertInstanceOf(JdkHttpTransport.class, openAiClient.getTransport());
    HttpTransportConfig config = assertInstanceOf(HttpTransportConfig.class, fieldValue(transport, "config"));

    assertEquals(timeout, config.getConnectTimeout());
    assertEquals(timeout, config.getReadTimeout());
    assertEquals(timeout, config.getWriteTimeout());
  }

  @Test
  void passesConfiguredProgressSinkToOpenAiCompatibleClient() throws Exception {
    AgentRuntimeProgressSink progressSink = (runtimeRequest, event) -> reactor.core.publisher.Mono.empty();

    AgentscopeAgentClient client = AgentscopeReActAgentClientFactory.openAiCompatible(
        "test-api-key",
        "test-model",
        "https://model-provider.example/v1",
        3,
        2,
        Duration.ofSeconds(5),
        progressSink);

    AgentscopeReActAgentClient reActClient = assertInstanceOf(AgentscopeReActAgentClient.class, client);

    assertEquals(progressSink, fieldValue(reActClient, "progressSink"));
  }

  private Object fieldValue(Object target, String fieldName) throws Exception {
    Field field = target.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    return field.get(target);
  }
}
