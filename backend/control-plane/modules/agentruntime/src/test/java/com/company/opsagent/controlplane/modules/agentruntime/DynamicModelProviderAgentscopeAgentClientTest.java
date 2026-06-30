package com.company.opsagent.controlplane.modules.agentruntime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class DynamicModelProviderAgentscopeAgentClientTest {

  @Test
  void resolvesActiveClientFromCurrentDefaultProvider() {
    var codec = new AesGcmModelProviderSecretCodec("0123456789abcdef0123456789abcdef");
    var store = new InMemoryModelProviderStore();
    var provider = new DefaultModelProviderManagementService(
        store,
        codec,
        ignored -> new ModelProviderProbeResult("SUCCEEDED", "ok"),
        java.time.Clock.systemUTC())
        .create(new ModelProviderCreateCommand(
            "OpenAI",
            "https://api.openai.com/v1",
            "gpt-4.1-mini",
            "CONSOLE_API_KEY_PLACEHOLDER",
            Duration.ofSeconds(17),
            7,
            4,
            Duration.ofSeconds(11)),
            "admin");
    store.setDefault(provider.providerId());

    AtomicReference<CapturedFactoryInput> captured = new AtomicReference<>();
    AgentscopeAgentClient client = new DynamicModelProviderAgentscopeAgentClient(
        store,
        codec,
        (apiKey, modelName, baseUrl, maxIters, maxToolCalls, timeout) -> {
          captured.set(new CapturedFactoryInput(apiKey, modelName, baseUrl, maxIters, maxToolCalls, timeout));
          return invocation -> Mono.just(new AgentscopeAgentResponse("SUCCEEDED", "ok", 0));
        },
        invocation -> Mono.just(new AgentscopeAgentResponse("LEGACY", "legacy", 0)));

    StepVerifier.create(client.run(invocation()))
        .assertNext(response -> assertEquals("SUCCEEDED", response.status()))
        .verifyComplete();

    assertEquals("CONSOLE_API_KEY_PLACEHOLDER", captured.get().apiKey());
    assertEquals("gpt-4.1-mini", captured.get().modelName());
    assertEquals("https://api.openai.com/v1", captured.get().baseUrl());
    assertEquals(7, captured.get().maxIters());
    assertEquals(4, captured.get().maxToolCalls());
    assertEquals(Duration.ofSeconds(17), captured.get().timeout());
  }

  @Test
  void normalizesSpringAiStyleBaseUrlBeforeCreatingClient() {
    var codec = new AesGcmModelProviderSecretCodec("0123456789abcdef0123456789abcdef");
    var store = new InMemoryModelProviderStore();
    var provider = new DefaultModelProviderManagementService(
        store,
        codec,
        ignored -> new ModelProviderProbeResult("SUCCEEDED", "ok"),
        java.time.Clock.systemUTC())
        .create(new ModelProviderCreateCommand(
            "OpenAI",
            "https://model-provider.example/base",
            "gpt-4.1-mini",
            "CONSOLE_API_KEY_PLACEHOLDER",
            Duration.ofSeconds(17),
            7,
            4,
            Duration.ofSeconds(11)),
            "admin");
    store.setDefault(provider.providerId());

    AtomicReference<CapturedFactoryInput> captured = new AtomicReference<>();
    AgentscopeAgentClient client = new DynamicModelProviderAgentscopeAgentClient(
        store,
        codec,
        (apiKey, modelName, baseUrl, maxIters, maxToolCalls, timeout) -> {
          captured.set(new CapturedFactoryInput(apiKey, modelName, baseUrl, maxIters, maxToolCalls, timeout));
          return invocation -> Mono.just(new AgentscopeAgentResponse("SUCCEEDED", "ok", 0));
        },
        invocation -> Mono.just(new AgentscopeAgentResponse("LEGACY", "legacy", 0)));

    StepVerifier.create(client.run(invocation()))
        .assertNext(response -> assertEquals("SUCCEEDED", response.status()))
        .verifyComplete();

    assertEquals("https://model-provider.example/base/v1", captured.get().baseUrl());
  }

  @Test
  void fallsBackWhenNoDefaultProviderExists() {
    AgentscopeAgentClient client = new DynamicModelProviderAgentscopeAgentClient(
        new InMemoryModelProviderStore(),
        new AesGcmModelProviderSecretCodec("0123456789abcdef0123456789abcdef"),
        (apiKey, modelName, baseUrl, maxIters, maxToolCalls, timeout) -> invocation -> Mono.just(
            new AgentscopeAgentResponse("UNEXPECTED", "unexpected", 0)),
        invocation -> Mono.just(new AgentscopeAgentResponse("LEGACY", "legacy", 0)));

    StepVerifier.create(client.run(invocation()))
        .assertNext(response -> assertEquals("LEGACY", response.status()))
        .verifyComplete();
  }

  private AgentscopeAgentInvocation invocation() {
    return new AgentscopeAgentInvocation(
        new AgentRuntimeRequest(
            "task-1",
            "workflow-1",
            "workspace-default",
            "operator-1",
            List.of("ROLE_ops-reader"),
            "development",
            "check node health",
            Map.of(),
            "trace-1",
            "request-1"),
        List.of(),
        (runtimeRequest, toolCall) -> Mono.empty());
  }

  private record CapturedFactoryInput(
      String apiKey,
      String modelName,
      String baseUrl,
      int maxIters,
      int maxToolCalls,
      Duration timeout) {
  }
}
