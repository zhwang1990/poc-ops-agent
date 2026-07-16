package com.company.opsagent.controlplane.bootstrap.config;

import com.company.opsagent.controlplane.bootstrap.service.WebClientSqlWorkbenchWorkerClient;
import com.company.opsagent.controlplane.bootstrap.service.ModelProviderSqlAssistantClient;
import com.company.opsagent.controlplane.modules.agentruntime.ModelProviderSecretCodec;
import com.company.opsagent.controlplane.modules.agentruntime.ModelProviderStore;
import com.company.opsagent.controlplane.modules.audit.AuditTrail;
import com.company.opsagent.controlplane.modules.sqlworkbench.CalciteSqlValidationService;
import com.company.opsagent.controlplane.modules.sqlworkbench.CalciteSqlDmlAnalysis;
import com.company.opsagent.controlplane.modules.sqlworkbench.ControlledSqlDmlPolicy;
import com.company.opsagent.controlplane.modules.sqlworkbench.ControlledSqlDmlProperties;
import com.company.opsagent.controlplane.modules.sqlworkbench.DefaultSqlWorkbenchService;
import com.company.opsagent.controlplane.modules.sqlworkbench.R2dbcSqlConnectionCatalog;
import com.company.opsagent.controlplane.modules.sqlworkbench.SqlAssistantClient;
import com.company.opsagent.controlplane.modules.sqlworkbench.SqlConnectionCatalog;
import com.company.opsagent.controlplane.modules.sqlworkbench.SqlValidationService;
import com.company.opsagent.controlplane.modules.sqlworkbench.SqlWorkbenchWorkerClient;
import com.company.opsagent.controlplane.modules.sqlworkbench.SqlWorkbenchService;
import com.company.opsagent.contracts.sqlworkbench.SqlTargetEnvironments;
import com.company.opsagent.controlplane.modules.workflow.ControlledSqlDmlWorkflowService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.spi.ConnectionFactory;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.r2dbc.connection.init.ConnectionFactoryInitializer;
import org.springframework.r2dbc.connection.init.ResourceDatabasePopulator;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * SQL 工作台 P1 连接目录与校验服务装配。
 */
@Configuration
public class SqlWorkbenchConfiguration {

  @Bean
  SqlConnectionCatalog sqlConnectionCatalog(DatabaseClient databaseClient, ObjectMapper objectMapper) {
    return new R2dbcSqlConnectionCatalog(databaseClient, objectMapper);
  }

  @Bean
  ConnectionFactoryInitializer sqlWorkbenchSchemaInitializer(ConnectionFactory connectionFactory) {
    var initializer = new ConnectionFactoryInitializer();
    initializer.setConnectionFactory(connectionFactory);
    initializer.setDatabasePopulator(new ResourceDatabasePopulator(
        new ClassPathResource("sql/migrations/V001__sql_connection_catalog_schema.sql"),
        new ClassPathResource("sql/migrations/V002__local_h2_sql_connection_seed.sql"),
        new ClassPathResource("sql/migrations/V003__local_h2_sql_connection_controlled_crud.sql")));
    return initializer;
  }

  @Bean
  SqlValidationService sqlValidationService() {
    return new CalciteSqlValidationService();
  }

  @Bean
  CalciteSqlDmlAnalysis calciteSqlDmlAnalysis() {
    return new CalciteSqlDmlAnalysis();
  }

  @Bean
  @ConfigurationProperties(prefix = "ops-agent.controlled-sql-dml")
  ControlledSqlDmlProperties controlledSqlDmlProperties() {
    return new ControlledSqlDmlProperties();
  }

  @Bean
  ControlledSqlDmlPolicy controlledSqlDmlPolicy(
      ControlledSqlDmlProperties controlledSqlDmlProperties,
      CalciteSqlDmlAnalysis calciteSqlDmlAnalysis) {
    return new ControlledSqlDmlPolicy(controlledSqlDmlProperties, calciteSqlDmlAnalysis);
  }

  @Bean
  SqlWorkbenchService sqlWorkbenchService(
      SqlConnectionCatalog sqlConnectionCatalog,
      SqlValidationService sqlValidationService,
      SqlWorkbenchWorkerClient sqlWorkbenchWorkerClient,
      SqlAssistantClient sqlAssistantClient,
      ControlledSqlDmlPolicy controlledSqlDmlPolicy,
      ControlledSqlDmlProperties controlledSqlDmlProperties,
      ControlledSqlDmlWorkflowService controlledSqlDmlWorkflowService,
      AuditTrail auditTrail,
      ConnectionFactory connectionFactory) {
    boolean transactionalAuditAvailable =
        auditTrail.supportsTransactionalParticipation(connectionFactory);
    return new DefaultSqlWorkbenchService(
        sqlConnectionCatalog,
        sqlValidationService,
        sqlWorkbenchWorkerClient,
        sqlAssistantClient,
        controlledSqlDmlPolicy,
        controlledSqlDmlWorkflowService::execute,
        environment -> controlledSqlDmlProperties.getEnabledEnvironments().stream()
            .map(SqlTargetEnvironments::normalize)
            .anyMatch(SqlTargetEnvironments.normalize(environment)::equals)
            && sqlWorkbenchWorkerClient.supportsControlledDml(environment),
        transactionalAuditAvailable,
        Clock.systemUTC());
  }

  @Bean
  SqlAssistantClient sqlAssistantClient(
      ModelProviderStore modelProviderStore,
      ModelProviderSecretCodec modelProviderSecretCodec,
      ObjectMapper objectMapper) {
    HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();
    return new ModelProviderSqlAssistantClient(
        modelProviderStore,
        modelProviderSecretCodec,
        httpClient,
        objectMapper);
  }

  @Bean
  SqlWorkbenchWorkerClient sqlWorkbenchWorkerClient(
      WebClient.Builder webClientBuilder,
      WorkerProperties workerProperties) {
    return new WebClientSqlWorkbenchWorkerClient(
        webClientBuilder.baseUrl(workerProperties.getBaseUrl()).build(),
        workerProperties,
        Clock.systemUTC());
  }

}
