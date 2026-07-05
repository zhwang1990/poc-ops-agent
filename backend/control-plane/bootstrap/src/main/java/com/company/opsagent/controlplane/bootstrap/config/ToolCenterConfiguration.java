package com.company.opsagent.controlplane.bootstrap.config;

import com.company.opsagent.controlplane.bootstrap.service.JsonRepairAssistantClient;
import com.company.opsagent.controlplane.bootstrap.service.ModelProviderJsonRepairAssistantClient;
import com.company.opsagent.controlplane.modules.agentruntime.ModelProviderSecretCodec;
import com.company.opsagent.controlplane.modules.agentruntime.ModelProviderStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ToolCenterConfiguration {

  @Bean
  JsonRepairAssistantClient jsonRepairAssistantClient(
      ModelProviderStore modelProviderStore,
      ModelProviderSecretCodec modelProviderSecretCodec,
      ObjectMapper objectMapper) {
    HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();
    return new ModelProviderJsonRepairAssistantClient(
        modelProviderStore,
        modelProviderSecretCodec,
        httpClient,
        objectMapper);
  }
}
