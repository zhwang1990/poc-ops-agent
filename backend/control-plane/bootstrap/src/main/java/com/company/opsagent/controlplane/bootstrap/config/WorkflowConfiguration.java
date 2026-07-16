package com.company.opsagent.controlplane.bootstrap.config;

import com.company.opsagent.controlplane.bootstrap.service.WebClientWorkerGateway;
import com.company.opsagent.controlplane.modules.agentruntime.AgentRuntimeService;
import com.company.opsagent.controlplane.modules.agentruntime.AgentRuntimeProgressSink;
import com.company.opsagent.controlplane.modules.agentruntime.AgentToolCatalogProvider;
import com.company.opsagent.controlplane.modules.agentruntime.AgentToolExecutor;
import com.company.opsagent.controlplane.modules.audit.AuditTrail;
import com.company.opsagent.controlplane.modules.policy.PolicyDecisionService;
import com.company.opsagent.controlplane.modules.agentrouting.SkillRoutingService;
import com.company.opsagent.controlplane.modules.workflow.AgentDiagnosticWorkflowService;
import com.company.opsagent.controlplane.modules.workflow.AgentWorkflowStore;
import com.company.opsagent.controlplane.modules.workflow.ControlledSqlDmlWorkflowStore;
import com.company.opsagent.controlplane.modules.workflow.ControlledSqlDmlWorkerGateway;
import com.company.opsagent.controlplane.modules.workflow.ControlledSqlDmlWorkflowService;
import com.company.opsagent.controlplane.modules.sqlworkbench.SqlDmlPreflightReceiptService;
import com.company.opsagent.controlplane.modules.workflow.ReadOnlyDiagnosticWorkflowService;
import com.company.opsagent.controlplane.modules.workflow.ReadOnlyWorkflowRecoveryService;
import com.company.opsagent.controlplane.modules.workflow.ReadOnlyWorkflowStore;
import com.company.opsagent.controlplane.modules.workflow.R2dbcAgentWorkflowStore;
import com.company.opsagent.controlplane.modules.workflow.R2dbcControlledSqlDmlWorkflowStore;
import com.company.opsagent.controlplane.modules.workflow.R2dbcReadOnlyWorkflowStore;
import com.company.opsagent.controlplane.modules.workflow.RetryableFailureClassifier;
import com.company.opsagent.controlplane.modules.workflow.WorkerGateway;
import com.company.opsagent.controlplane.modules.workflow.WorkflowBackedAgentToolExecutor;
import com.company.opsagent.controlplane.modules.workflow.WorkflowAgentRuntimeProgressSink;
import com.company.opsagent.controlplane.modules.sqlworkbench.SqlWorkbenchWorkerClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.r2dbc.connection.init.ConnectionFactoryInitializer;
import org.springframework.r2dbc.connection.init.ResourceDatabasePopulator;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.web.reactive.function.client.WebClient;
import io.r2dbc.spi.ConnectionFactory;

/**
 * 只读诊断工作流和独立 Worker 客户端装配。
 */
@Configuration
@EnableConfigurationProperties({WorkerProperties.class, WorkflowPersistenceProperties.class})
public class WorkflowConfiguration {

  /**
   * 构建指向独立 Worker 的非阻塞网关。
   */
  @Bean
  WorkerGateway workerGateway(WebClient.Builder webClientBuilder, WorkerProperties properties) {
    return new WebClientWorkerGateway(
        webClientBuilder.baseUrl(properties.getBaseUrl()).build(),
        properties,
        Clock.systemUTC());
  }

  @Bean
  ReadOnlyWorkflowStore readOnlyWorkflowStore(DatabaseClient databaseClient, ObjectMapper objectMapper) {
    return new R2dbcReadOnlyWorkflowStore(databaseClient, objectMapper);
  }

  @Bean
  AgentWorkflowStore agentWorkflowStore(DatabaseClient databaseClient) {
    return new R2dbcAgentWorkflowStore(databaseClient);
  }

  @Bean
  ControlledSqlDmlWorkflowStore controlledSqlDmlWorkflowStore(
      DatabaseClient databaseClient,
      AuditTrail auditTrail) {
    return new R2dbcControlledSqlDmlWorkflowStore(databaseClient, auditTrail);
  }

  @Bean
  ControlledSqlDmlWorkerGateway controlledSqlDmlWorkerGateway(
      SqlWorkbenchWorkerClient sqlWorkbenchWorkerClient) {
    return sqlWorkbenchWorkerClient::executeControlledDml;
  }

  @Bean
  ControlledSqlDmlWorkflowService controlledSqlDmlWorkflowService(
      ControlledSqlDmlWorkflowStore controlledSqlDmlWorkflowStore,
      ControlledSqlDmlWorkerGateway controlledSqlDmlWorkerGateway,
      SqlDmlPreflightReceiptService sqlDmlPreflightReceiptService) {
    return new ControlledSqlDmlWorkflowService(
        controlledSqlDmlWorkflowStore,
        controlledSqlDmlWorkerGateway,
        sqlDmlPreflightReceiptService,
        Clock.systemUTC());
  }

  @Bean
  AgentRuntimeProgressSink agentRuntimeProgressSink(ReadOnlyWorkflowStore readOnlyWorkflowStore) {
    return new WorkflowAgentRuntimeProgressSink(readOnlyWorkflowStore, Clock.systemUTC());
  }

  /**
   * 装配 AgentScope ToolCall 的服务端执行边界。
   *
   * <p>这个 Bean 位于 workflow 配置中，是因为真实执行链需要 M05 的 Tool Step 事实源、
   * M02 的策略决策和 M07 WorkerGateway。M04 AgentRuntime 只持有端口，不能反向依赖
   * workflow，否则会形成模块依赖环并削弱“服务端策略是唯一权限决策点”的边界。
   */
  @Bean
  AgentToolExecutor agentToolExecutor(
      AgentToolCatalogProvider agentToolCatalogProvider,
      PolicyDecisionService policyDecisionService,
      AgentWorkflowStore agentWorkflowStore,
      ReadOnlyWorkflowStore readOnlyWorkflowStore,
      AuditTrail auditTrail,
      WorkerGateway workerGateway,
      ObjectMapper objectMapper) {
    return new WorkflowBackedAgentToolExecutor(
        agentToolCatalogProvider,
        policyDecisionService,
        agentWorkflowStore,
        readOnlyWorkflowStore,
        auditTrail,
        workerGateway,
        objectMapper,
        Clock.systemUTC());
  }

  @Bean
  ConnectionFactoryInitializer workflowSchemaInitializer(ConnectionFactory connectionFactory) {
    var initializer = new ConnectionFactoryInitializer();
    initializer.setConnectionFactory(connectionFactory);
    initializer.setDatabasePopulator(new ResourceDatabasePopulator(
        new ClassPathResource("sql/migrations/V001__workflow_schema.sql"),
        new ClassPathResource("sql/migrations/V002__agent_workflow_schema.sql"),
        new ClassPathResource("sql/migrations/V003__agent_workflow_result_columns.sql"),
        new ClassPathResource("sql/migrations/V004__controlled_sql_dml_workflow.sql"),
        new ClassPathResource("sql/migrations/V005__controlled_sql_dml_execution_expiry.sql")));
    return initializer;
  }

  @Bean
  RetryableFailureClassifier retryableFailureClassifier() {
    return new RetryableFailureClassifier();
  }

  @Bean
  ReadOnlyWorkflowRecoveryService readOnlyWorkflowRecoveryService(
      ReadOnlyWorkflowStore workflowStore,
      WorkerGateway workerGateway,
      RetryableFailureClassifier retryableFailureClassifier) {
    return new ReadOnlyWorkflowRecoveryService(
        workflowStore,
        workerGateway,
        Clock.systemUTC(),
        retryableFailureClassifier);
  }

  @Bean
  ApplicationRunner workflowRecoveryRunner(
      WorkflowPersistenceProperties properties,
      ReadOnlyWorkflowRecoveryService recoveryService) {
    return args -> {
      if (properties.isStartupRecoveryEnabled()) {
        recoveryService.recoverStaleWorkflows().block();
      }
    };
  }

  /**
   * 构建 P1 只读诊断工作流服务。
   */
  @Bean
  ReadOnlyDiagnosticWorkflowService readOnlyDiagnosticWorkflowService(
      SkillRoutingService skillRoutingService,
      WorkerGateway workerGateway,
      ReadOnlyWorkflowStore workflowStore,
      RetryableFailureClassifier retryableFailureClassifier) {
    return new ReadOnlyDiagnosticWorkflowService(
        skillRoutingService,
        workerGateway,
        Clock.systemUTC(),
        workflowStore,
        retryableFailureClassifier);
  }

  @Bean
  AgentDiagnosticWorkflowService agentDiagnosticWorkflowService(
      AgentRuntimeService agentRuntimeService,
      AgentWorkflowStore agentWorkflowStore) {
    return new AgentDiagnosticWorkflowService(
        agentRuntimeService,
        agentWorkflowStore,
        Clock.systemUTC());
  }
}
