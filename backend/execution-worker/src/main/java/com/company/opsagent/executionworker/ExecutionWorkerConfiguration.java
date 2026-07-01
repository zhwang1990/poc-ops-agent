package com.company.opsagent.executionworker;

import com.company.opsagent.executionworker.release.ReleaseAdapter;
import com.company.opsagent.executionworker.release.ReleaseAdapterRegistry;
import com.company.opsagent.executionworker.release.ReleaseWorkerProperties;
import com.company.opsagent.executionworker.release.TomcatWarUploadReleaseAdapter;
import com.company.opsagent.executionworker.release.LibertyHttpsReleaseAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Worker 允许列表和执行核心装配。
 */
@Configuration
@EnableConfigurationProperties({
    WorkerTransportAuthProperties.class,
    WorkerHttpEgressProperties.class,
    ConfiguredHttpReadOnlySkillProperties.class,
    ReleaseWorkerProperties.class
})
public class ExecutionWorkerConfiguration {

  /**
   * 提供可替换的系统时钟。
   */
  @Bean
  Clock workerClock() {
    return Clock.systemUTC();
  }

  /**
   * 注册当前 P1 唯一允许执行的节点健康只读适配器。
   */
  @Bean
  NodeHealthReadAdapter nodeHealthReadAdapter(ObjectMapper objectMapper, Clock workerClock) {
    return new NodeHealthReadAdapter(objectMapper, workerClock);
  }

  /**
   * 构建 Worker 本地 HTTP 出口策略，默认空配置即拒绝所有 HTTP 目标。
   */
  @Bean
  WorkerHttpEgressPolicy workerHttpEgressPolicy(WorkerHttpEgressProperties properties) {
    return properties.toPolicy();
  }

  /**
   * 注册配置型 HTTP/JSON 只读适配器。简单第三方 HTTP Skill 应优先走该适配器配置。
   */
  @Bean
  ConfiguredHttpReadOnlySkillAdapter configuredHttpReadOnlySkillAdapter(
      ObjectMapper objectMapper,
      WorkerHttpEgressPolicy workerHttpEgressPolicy,
      Clock workerClock,
      ConfiguredHttpReadOnlySkillProperties properties) {
    return new ConfiguredHttpReadOnlySkillAdapter(
        HttpClient.newHttpClient(),
        objectMapper,
        workerClock,
        workerHttpEgressPolicy,
        properties);
  }

  /**
   * 构建受限 Worker 核心，明确传入允许列表。
   */
  @Bean
  RestrictedReadOnlyExecutionWorker restrictedReadOnlyExecutionWorker(
      NodeHealthReadAdapter nodeHealthReadAdapter,
      ConfiguredHttpReadOnlySkillAdapter configuredHttpReadOnlySkillAdapter,
      Clock workerClock) {
    return new RestrictedReadOnlyExecutionWorker(
        List.of(nodeHealthReadAdapter, configuredHttpReadOnlySkillAdapter),
        workerClock);
  }

  /**
   * 构建 Worker HTTP 边界传输认证器。
   */
  @Bean
  WorkerTransportAuthenticator workerTransportAuthenticator(
      WorkerTransportAuthProperties properties,
      Clock workerClock) {
    return new WorkerTransportAuthenticator(properties, workerClock);
  }

  @Bean
  ReleaseAdapterRegistry releaseAdapterRegistry(List<ReleaseAdapter> adapters) {
    return new ReleaseAdapterRegistry(adapters);
  }

  @Bean
  TomcatWarUploadReleaseAdapter tomcatWarUploadReleaseAdapter(Clock workerClock) {
    return new TomcatWarUploadReleaseAdapter(workerClock);
  }

  @Bean
  @ConditionalOnProperty(prefix = "ops-agent.worker.release.liberty", name = "enabled", havingValue = "true")
  LibertyHttpsReleaseAdapter libertyHttpsReleaseAdapter(
      ReleaseWorkerProperties properties,
      WorkerHttpEgressPolicy workerHttpEgressPolicy,
      Clock workerClock,
      ObjectMapper objectMapper) {
    return new LibertyHttpsReleaseAdapter(
        properties.getLiberty().getBaseUrl(),
        properties.getLiberty().getCredentialAlias(),
        HttpClient.newHttpClient(),
        objectMapper,
        workerHttpEgressPolicy,
        workerClock);
  }

  /**
   * 启动时阻止未认证 Worker 绑定到非回环地址。
   */
  @Bean
  ApplicationRunner workerBindingSafetyRunner(
      @Value("${server.address:}") String serverAddress,
      WorkerTransportAuthProperties properties) {
    return args -> new WorkerBindingSafetyGuard(serverAddress, properties).validate();
  }

}
