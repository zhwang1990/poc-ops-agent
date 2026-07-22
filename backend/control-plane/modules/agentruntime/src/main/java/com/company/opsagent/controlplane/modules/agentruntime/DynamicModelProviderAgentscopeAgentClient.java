package com.company.opsagent.controlplane.modules.agentruntime;

import java.time.Duration;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 每次调用前解析当前默认模型供应方的 AgentScope 客户端。
 */
public final class DynamicModelProviderAgentscopeAgentClient implements AgentscopeAgentClient {

  @FunctionalInterface
  public interface OpenAiCompatibleClientFactory {

    AgentscopeAgentClient openAiCompatible(
        String apiKey,
        String modelName,
        String baseUrl,
        int maxIters,
        int maxToolCalls,
        Duration timeout,
        AgentRuntimeProgressSink progressSink);
  }

  private static final String LOCAL_DEMO_PROVIDER_ID = "local-deepseek-default";

  private final ModelProviderStore store;
  private final ModelProviderSecretCodec secretCodec;
  private final OpenAiCompatibleClientFactory clientFactory;
  private final AgentscopeAgentClient fallbackClient;
  private final AgentRuntimeProgressSink progressSink;

  public DynamicModelProviderAgentscopeAgentClient(
      ModelProviderStore store,
      ModelProviderSecretCodec secretCodec,
      OpenAiCompatibleClientFactory clientFactory,
      AgentscopeAgentClient fallbackClient,
      AgentRuntimeProgressSink progressSink) {
    this.store = store;
    this.secretCodec = secretCodec;
    this.clientFactory = clientFactory;
    this.fallbackClient = fallbackClient;
    this.progressSink = progressSink == null ? AgentRuntimeProgressSink.noop() : progressSink;
  }

  @Override
  public Mono<AgentscopeAgentResponse> run(AgentscopeAgentInvocation invocation) {
    return Mono.fromCallable(this::resolveActiveClient)
        .subscribeOn(Schedulers.boundedElastic())
        .flatMap(client -> client.run(invocation));
  }

  private AgentscopeAgentClient resolveActiveClient() {
    return store.findDefault()
        .map(this::clientForProvider)
        .orElse(fallbackClient);
  }

  private AgentscopeAgentClient clientForProvider(ModelProvider provider) {
    if (LOCAL_DEMO_PROVIDER_ID.equals(provider.providerId())) {
      return invocation -> Mono.just(new AgentscopeAgentResponse(
          "AGENT_RUNTIME_FAKE_API_KEY",
          "AgentScope model provider is using a local demo provider.",
          0));
    }
    String apiKey = secretCodec.decrypt(new ModelProviderSecretCodec.EncryptedSecret(
        provider.apiKeyCiphertext(),
        provider.apiKeyNonce(),
        provider.apiKeyAlgorithm(),
        provider.apiKeyFingerprint()));
    return clientFactory.openAiCompatible(
        apiKey,
        provider.modelName(),
        OpenAiCompatibleEndpoint.apiBaseUrl(provider.baseUrl()),
        provider.maxIterations(),
        provider.maxToolCalls(),
        provider.timeout(),
        progressSink);
  }
}
