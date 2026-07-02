package com.company.opsagent.controlplane.bootstrap.config;

import com.company.opsagent.controlplane.modules.agentruntime.AgentRuntimeService;
import com.company.opsagent.controlplane.modules.agentruntime.AgentRuntimeProgressSink;
import com.company.opsagent.controlplane.modules.agentruntime.AgentToolCatalogProvider;
import com.company.opsagent.controlplane.modules.agentruntime.AgentToolDescriptor;
import com.company.opsagent.controlplane.modules.agentruntime.AgentToolExecutor;
import com.company.opsagent.controlplane.modules.agentruntime.AgentscopeAgentClient;
import com.company.opsagent.controlplane.modules.agentruntime.AgentscopeAgentResponse;
import com.company.opsagent.controlplane.modules.agentruntime.AgentscopePrimaryAgentRuntimeService;
import com.company.opsagent.controlplane.modules.agentruntime.AgentscopeReActAgentClientFactory;
import com.company.opsagent.controlplane.modules.agentruntime.AesGcmModelProviderSecretCodec;
import com.company.opsagent.controlplane.modules.agentruntime.DefaultModelProviderManagementService;
import com.company.opsagent.controlplane.modules.agentruntime.DynamicModelProviderAgentscopeAgentClient;
import com.company.opsagent.controlplane.modules.agentruntime.LocalWeatherSmokeAgentClient;
import com.company.opsagent.controlplane.modules.agentruntime.ModelProviderProbe;
import com.company.opsagent.controlplane.modules.agentruntime.ModelProviderSecretCodec;
import com.company.opsagent.controlplane.modules.agentruntime.ModelProviderStore;
import com.company.opsagent.controlplane.modules.agentruntime.OpenAiCompatibleModelProviderProbe;
import com.company.opsagent.controlplane.modules.agentruntime.R2dbcModelProviderStore;
import com.company.opsagent.controlplane.modules.skillregistry.RegisteredSkill;
import com.company.opsagent.controlplane.modules.skillregistry.SkillPublicationStatus;
import com.company.opsagent.controlplane.modules.skillregistry.SkillRegistryService;
import io.r2dbc.spi.ConnectionFactory;
import java.time.Clock;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.r2dbc.connection.init.ConnectionFactoryInitializer;
import org.springframework.r2dbc.connection.init.ResourceDatabasePopulator;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;

/**
 * P1 只读诊断 AgentScope 主运行时装配。
 *
 * <p>这里把 M03 已验证的只读 Skill 转成 M04 Tool Catalog，并把 M05 提供的
 * AgentToolExecutor 注入运行时服务。这样 AgentScope 只能提出工具意图，真实
 * 授权、工作流持久化和 Worker 调用仍由服务端平台边界完成。
 */
@Configuration
@EnableConfigurationProperties(AgentRuntimeProperties.class)
public class AgentRuntimeConfiguration {

  private static final String LOCAL_FAKE_API_KEY = "OPS_AGENT_FAKE_API_KEY_REPLACE_ME";
  private static final String LOCAL_MODEL_PROVIDER_MASTER_KEY =
      "OPS_AGENT_MODEL_SECRET_MASTER_KEY_REPLACE_ME";

  /**
   * 从注册中心生成模型可见的只读 Tool Catalog。
   */
  @Bean
  AgentToolCatalogProvider agentToolCatalogProvider(SkillRegistryService skillRegistryService) {
    return () -> skillRegistryService.listSkills().stream()
        .filter(skill -> skill.publicationStatus() == SkillPublicationStatus.VALIDATED)
        .filter(skill -> skill.descriptor().readOnly())
        .map(this::toToolDescriptor)
        .toList();
  }

  @Bean
  AgentscopeAgentClient agentscopeAgentClient(
      AgentRuntimeProperties properties,
      ModelProviderStore modelProviderStore,
      ModelProviderSecretCodec modelProviderSecretCodec,
      AgentRuntimeProgressSink progressSink) {
    if ("local-weather-smoke".equals(properties.getProvider())) {
      return new LocalWeatherSmokeAgentClient();
    }
    return new DynamicModelProviderAgentscopeAgentClient(
        modelProviderStore,
        modelProviderSecretCodec,
        AgentscopeReActAgentClientFactory::openAiCompatible,
        legacyPropertiesClient(properties, progressSink),
        progressSink);
  }

  private AgentscopeAgentClient legacyPropertiesClient(
      AgentRuntimeProperties properties,
      AgentRuntimeProgressSink progressSink) {
    String apiKey = resolvedApiKey(properties);
    if (isBlank(properties.getModelName()) || isBlank(apiKey)) {
      return notConfiguredClient();
    }
    if (isLocalFakeApiKey(apiKey)) {
      return fakeApiKeyClient();
    }
    return AgentscopeReActAgentClientFactory.openAiCompatible(
        apiKey,
        properties.getModelName(),
        properties.getBaseUrl(),
        properties.getMaxIterations(),
        properties.getMaxToolCalls(),
        properties.getTimeout(),
        progressSink);
  }

  @Bean
  ModelProviderStore modelProviderStore(DatabaseClient databaseClient) {
    return new R2dbcModelProviderStore(databaseClient);
  }

  @Bean
  ModelProviderSecretCodec modelProviderSecretCodec(AgentRuntimeProperties properties) {
    String masterKey = isBlank(properties.getModelProviderSecretMasterKey())
        ? LOCAL_MODEL_PROVIDER_MASTER_KEY
        : properties.getModelProviderSecretMasterKey();
    return new AesGcmModelProviderSecretCodec(masterKey);
  }

  @Bean
  ModelProviderProbe modelProviderProbe(ModelProviderSecretCodec modelProviderSecretCodec) {
    return new OpenAiCompatibleModelProviderProbe(modelProviderSecretCodec);
  }

  @Bean
  DefaultModelProviderManagementService modelProviderManagementService(
      ModelProviderStore modelProviderStore,
      ModelProviderSecretCodec modelProviderSecretCodec,
      ModelProviderProbe modelProviderProbe) {
    return new DefaultModelProviderManagementService(
        modelProviderStore,
        modelProviderSecretCodec,
        modelProviderProbe,
        Clock.systemUTC());
  }

  @Bean
  ConnectionFactoryInitializer modelProviderSchemaInitializer(ConnectionFactory connectionFactory) {
    var initializer = new ConnectionFactoryInitializer();
    initializer.setConnectionFactory(connectionFactory);
    initializer.setDatabasePopulator(new ResourceDatabasePopulator(
        new ClassPathResource("sql/migrations/V001__model_provider_schema.sql"),
        new ClassPathResource("sql/migrations/V002__local_deepseek_model_provider_seed.sql")));
    return initializer;
  }

  /**
   * 未配置模型供应方或 API Key 时必须失败关闭，不能静默切换到未审计路径。
   */
  private AgentscopeAgentClient notConfiguredClient() {
    return invocation -> Mono.just(new AgentscopeAgentResponse(
        "AGENT_RUNTIME_NOT_CONFIGURED",
        "AgentScope model provider is not configured for this environment.",
        0));
  }

  /**
   * 本地占位 Key 只用于打通配置链路，不能触发真实模型调用。
   */
  private AgentscopeAgentClient fakeApiKeyClient() {
    return invocation -> Mono.just(new AgentscopeAgentResponse(
        "AGENT_RUNTIME_FAKE_API_KEY",
        "AgentScope model provider is using a local fake API key placeholder.",
        0));
  }

  /**
   * 装配主运行时服务，并显式注入平台 Tool 执行端口。
   */
  @Bean
  AgentRuntimeService agentRuntimeService(
      AgentToolCatalogProvider agentToolCatalogProvider,
      AgentscopeAgentClient agentscopeAgentClient,
      AgentToolExecutor agentToolExecutor) {
    return new AgentscopePrimaryAgentRuntimeService(
        agentToolCatalogProvider,
        agentscopeAgentClient,
        agentToolExecutor);
  }

  /**
   * 将已发布 Skill 的平台描述转换为 AgentScope 可见的脱敏 Tool 描述。
   */
  private AgentToolDescriptor toToolDescriptor(RegisteredSkill skill) {
    List<String> parameterNames = skill.descriptor().parameters().stream()
        .map(parameter -> parameter.name())
        .toList();
    return new AgentToolDescriptor(
        skill.descriptor().skillId(),
        skill.descriptor().version(),
        skill.descriptor().description(),
        skill.descriptor().skillId() + ":" + skill.descriptor().version() + ":input",
        skill.descriptor().skillId() + ":" + skill.descriptor().version() + ":output",
        parameterNames,
        skill.descriptor().riskLevel().name());
  }

  private String resolvedApiKey(AgentRuntimeProperties properties) {
    if (!isBlank(properties.getApiKey())) {
      return properties.getApiKey();
    }
    if (isBlank(properties.getApiKeyEnv())) {
      return "";
    }
    return System.getenv(properties.getApiKeyEnv());
  }

  private boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private boolean isLocalFakeApiKey(String value) {
    return LOCAL_FAKE_API_KEY.equals(value);
  }
}
