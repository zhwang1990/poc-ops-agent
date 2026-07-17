package com.company.opsagent.controlplane.bootstrap.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.company.opsagent.controlplane.modules.agentruntime.AgentRuntimeRequest;
import com.company.opsagent.controlplane.modules.agentruntime.AgentRuntimeProgressSink;
import com.company.opsagent.controlplane.modules.agentruntime.AesGcmModelProviderSecretCodec;
import com.company.opsagent.controlplane.bootstrap.BootstrapTestSecretMaterial;
import com.company.opsagent.controlplane.modules.agentruntime.AgentscopeAgentClient;
import com.company.opsagent.controlplane.modules.agentruntime.AgentscopeAgentInvocation;
import com.company.opsagent.controlplane.modules.agentruntime.AgentscopeAgentResponse;
import com.company.opsagent.controlplane.modules.agentruntime.DynamicModelProviderAgentscopeAgentClient;
import com.company.opsagent.controlplane.modules.agentruntime.InMemoryModelProviderStore;
import com.company.opsagent.controlplane.modules.agentruntime.LocalWeatherSmokeAgentClient;
import com.company.opsagent.controlplane.modules.agentruntime.ModelProvider;
import com.company.opsagent.controlplane.modules.agentruntime.R2dbcModelProviderStore;
import io.r2dbc.spi.ConnectionFactories;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;

class AgentRuntimeConfigurationTest {

  @Test
  void createsLocalWeatherSmokeClientOnlyWhenExplicitlyConfigured() {
    AgentRuntimeProperties properties = new AgentRuntimeProperties();
    properties.setProvider("local-weather-smoke");

    AgentscopeAgentClient client = new AgentRuntimeConfiguration().agentscopeAgentClient(
        properties,
        new InMemoryModelProviderStore(),
        new AesGcmModelProviderSecretCodec(BootstrapTestSecretMaterial.value()),
        AgentRuntimeProgressSink.noop());

    assertInstanceOf(LocalWeatherSmokeAgentClient.class, client);
  }

  @Test
  void failsClosedWhenNoApiKeyIsConfigured() {
    AgentRuntimeProperties properties = new AgentRuntimeProperties();
    properties.setModelName("gpt-4.1-mini");
    properties.setBaseUrl("https://api.openai.com/v1");
    AgentscopeAgentClient client = new AgentRuntimeConfiguration().agentscopeAgentClient(
        properties,
        new InMemoryModelProviderStore(),
        new AesGcmModelProviderSecretCodec(BootstrapTestSecretMaterial.value()),
        AgentRuntimeProgressSink.noop());

    AgentscopeAgentResponse response = client.run(invocation()).block();
    assertEquals("AGENT_RUNTIME_NOT_CONFIGURED", response.status());
  }

  @Test
  void createsDynamicClientWithLegacyFallbackWhenRealApiKeyIsConfigured() {
    AgentRuntimeProperties properties = new AgentRuntimeProperties();
    properties.setModelName("gpt-4.1-mini");
    properties.setBaseUrl("https://api.openai.com/v1");
    properties.setApiKey(BootstrapTestSecretMaterial.value());

    AgentscopeAgentClient client = new AgentRuntimeConfiguration().agentscopeAgentClient(
        properties,
        new InMemoryModelProviderStore(),
        new AesGcmModelProviderSecretCodec(BootstrapTestSecretMaterial.value()),
        AgentRuntimeProgressSink.noop());

    assertInstanceOf(DynamicModelProviderAgentscopeAgentClient.class, client);
  }

  @Test
  void modelProviderInitializerSeedsLocalDeepseekDefaultProvider() {
    var connectionFactory = ConnectionFactories.get(
        "r2dbc:h2:mem:///bootstrap-model-provider-seed-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");

    new AgentRuntimeConfiguration().modelProviderSchemaInitializer(connectionFactory).afterPropertiesSet();

    R2dbcModelProviderStore store = new R2dbcModelProviderStore(DatabaseClient.create(connectionFactory));
    ModelProvider provider = store.findById("local-deepseek-default").orElseThrow();
    assertEquals("deepseek", provider.displayName());
    assertEquals("https://api.deepseek.com", provider.baseUrl());
    assertEquals("deepseek-v4-pro", provider.modelName());
    assertEquals("fp_XMKCRAGIOQU", provider.apiKeyFingerprint());
  }

  private AgentscopeAgentInvocation invocation() {
    return new AgentscopeAgentInvocation(
        new AgentRuntimeRequest(
            "task-1",
            "workflow-1",
            "workspace-default",
            "operator-1",
            List.of("ROLE_ops-reader"),
            "dev",
            "Check node health",
            Map.of(),
            "trace-1",
            "request-1"),
        List.of(),
        (runtimeRequest, toolCall) -> Mono.empty());
  }
}
