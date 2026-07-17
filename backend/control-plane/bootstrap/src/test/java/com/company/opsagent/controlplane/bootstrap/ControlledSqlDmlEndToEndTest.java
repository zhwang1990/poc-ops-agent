package com.company.opsagent.controlplane.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.company.opsagent.contracts.sqlworkbench.SqlConnectionCreateRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlConnectionProbeResult;
import com.company.opsagent.contracts.sqlworkbench.SqlConnectionSummary;
import com.company.opsagent.contracts.sqlworkbench.SqlConnectionUpdateRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlControlledDmlExecutionRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlDatabaseMetadata;
import com.company.opsagent.contracts.sqlworkbench.SqlDmlCommitRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlDmlConfirmation;
import com.company.opsagent.contracts.sqlworkbench.SqlDmlImpactPreview;
import com.company.opsagent.contracts.sqlworkbench.SqlDmlPreflightExecutionRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlDmlPreflightReceipt;
import com.company.opsagent.contracts.sqlworkbench.SqlDmlPreflightResult;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryAction;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryExecutionRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryExecutionResult;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryLimits;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlResultPage;
import com.company.opsagent.contracts.sqlworkbench.SqlTargetEnvironments;
import com.company.opsagent.contracts.workflow.OperatorContext;
import com.company.opsagent.contracts.workflow.PolicyDecisionReference;
import com.company.opsagent.contracts.workflow.TraceContext;
import com.company.opsagent.controlplane.modules.audit.R2dbcAuditTrail;
import com.company.opsagent.controlplane.modules.sqlworkbench.CalciteSqlDmlAnalysis;
import com.company.opsagent.controlplane.modules.sqlworkbench.CalciteSqlValidationService;
import com.company.opsagent.controlplane.modules.sqlworkbench.ControlledSqlDmlPolicy;
import com.company.opsagent.controlplane.modules.sqlworkbench.ControlledSqlDmlProperties;
import com.company.opsagent.controlplane.modules.sqlworkbench.DefaultSqlWorkbenchService;
import com.company.opsagent.controlplane.modules.sqlworkbench.SqlConnectionCatalog;
import com.company.opsagent.controlplane.modules.sqlworkbench.SqlDmlPreflightReceiptProperties;
import com.company.opsagent.controlplane.modules.sqlworkbench.SqlDmlPreflightReceiptService;
import com.company.opsagent.controlplane.modules.sqlworkbench.SqlWorkbenchException;
import com.company.opsagent.controlplane.modules.sqlworkbench.SqlWorkbenchService;
import com.company.opsagent.controlplane.modules.sqlworkbench.SqlWorkbenchWorkerClient;
import com.company.opsagent.controlplane.modules.workflow.ControlledSqlDmlWorkflowService;
import com.company.opsagent.controlplane.modules.workflow.R2dbcControlledSqlDmlWorkflowStore;
import com.company.opsagent.executionworker.sqlworkbench.CalciteSqlDmlGuard;
import com.company.opsagent.executionworker.sqlworkbench.CalciteSqlReadOnlyGuard;
import com.company.opsagent.executionworker.sqlworkbench.ConfiguredSqlDataSourceRegistry;
import com.company.opsagent.executionworker.sqlworkbench.H2SqlDataSourceFactory;
import com.company.opsagent.executionworker.sqlworkbench.InMemorySqlResultStore;
import com.company.opsagent.executionworker.sqlworkbench.JdbcSqlDmlImpactPreviewExecutor;
import com.company.opsagent.executionworker.sqlworkbench.JdbcSqlQueryExecutor;
import com.company.opsagent.executionworker.sqlworkbench.Jt400DataSourceFactory;
import com.company.opsagent.executionworker.sqlworkbench.RestrictedSqlQueryExecutionWorker;
import com.company.opsagent.executionworker.sqlworkbench.WorkerSqlConnectionDescriptor;
import com.company.opsagent.executionworker.sqlworkbench.WorkerSqlDmlExecutionPolicy;
import com.company.opsagent.executionworker.sqlworkbench.WorkerSqlEgressException;
import com.company.opsagent.executionworker.sqlworkbench.WorkerSqlEgressProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.spi.ConnectionFactories;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.r2dbc.connection.init.ConnectionFactoryInitializer;
import org.springframework.r2dbc.connection.init.ResourceDatabasePopulator;
import org.springframework.r2dbc.core.DatabaseClient;

class ControlledSqlDmlEndToEndTest {

  private static final Clock CLOCK = Clock.fixed(
      Instant.parse("2026-07-17T08:00:00Z"), ZoneOffset.UTC);
  private static final OperatorContext OPERATOR =
      new OperatorContext("task-7-operator", List.of("ROLE_ops-admin"));
  private static final PolicyDecisionReference POLICY =
      new PolicyDecisionReference("task-7-decision", "policy-v1", "ALLOW");
  private static final SqlQueryLimits LIMITS = new SqlQueryLimits(100, 1_000_000, 30);
  private static final char[] WRITER_KEY_MATERIAL = runtimeKeyMaterial().toCharArray();

  @Test
  void executesInsertUpdateDeleteExactlyOnceInConfiguredSitH2Slice() throws Exception {
    Harness harness = Harness.configured(CLOCK, CLOCK, DispatchMode.RETURN_RESULT);

    assertExactlyOnce(harness, "insert-901", """
        insert into PUBLIC.ORDERS
          (ORDER_ID, STATUS, AMOUNT, CUSTOMER_ID, CREATED_AT)
        values
          (901, 'NEW', 10.00, 'CUST-001', TIMESTAMP '2026-07-17 08:00:00')
        """);
    assertExactlyOnce(harness, "update-901", """
        update PUBLIC.ORDERS set STATUS = 'READY' where ORDER_ID = 901
        """);
    assertExactlyOnce(harness, "delete-901", """
        delete from PUBLIC.ORDERS where ORDER_ID = 901
        """);

    assertEquals(3, harness.workerClient.commitDispatchCount.get());
    assertEquals(0, harness.countRows("select count(*) from PUBLIC.ORDERS where ORDER_ID = 901"));
    assertEquals("database", harness.demoProperty("ops-agent.audit.storage-mode").toString());
    assertEquals("task7-sql-dml-receipt-v1", harness.receiptProperties.getKeyId());
    assertTrue(harness.auditTrail.supportsTransactionalParticipation(harness.connectionFactory));
  }

  @Test
  void productionDisabledCapabilityAndDisabledConfigNeverDispatchWrites() throws Exception {
    Harness configured = Harness.configured(CLOCK, CLOCK, DispatchMode.RETURN_RESULT);
    assertThrows(IllegalArgumentException.class, () -> configured.query(
        "production",
        SqlQueryAction.COMMIT_DML,
        "delete from PUBLIC.ORDERS where ORDER_ID = 1",
        "production-delete"));
    assertEquals(0, configured.workerClient.commitDispatchCount.get());

    configured.catalog.disableDmlCapabilities();
    assertThrows(IllegalArgumentException.class, () -> configured.preflight(
        "delete from PUBLIC.ORDERS where ORDER_ID = 1", "capability-disabled"));
    assertEquals(0, configured.workerClient.commitDispatchCount.get());

    Harness controlDisabled = Harness.controlDisabled();
    SqlWorkbenchException controlFailure = assertThrows(
        SqlWorkbenchException.class,
        () -> controlDisabled.preflight(
            "delete from PUBLIC.ORDERS where ORDER_ID = 1", "control-disabled"));
    assertEquals("SQL_DML_DISABLED", controlFailure.code());
    assertEquals(0, controlDisabled.workerClient.commitDispatchCount.get());

    Harness workerDisabled = Harness.workerDisabled();
    WorkerSqlEgressException workerFailure = assertThrows(
        WorkerSqlEgressException.class,
        () -> workerDisabled.preflight(
            "delete from PUBLIC.ORDERS where ORDER_ID = 1", "worker-disabled"));
    assertEquals("SQL_DML_WORKER_DISABLED", workerFailure.errorCode());
    assertEquals(0, workerDisabled.workerClient.commitDispatchCount.get());
  }

  @Test
  void invalidOrExpiredReceiptAndInvalidConfirmationNeverDispatchWrites() throws Exception {
    Harness harness = Harness.configured(CLOCK, CLOCK, DispatchMode.RETURN_RESULT);
    SqlDmlCommitRequest valid = harness.prepareCommit(insertSql(902), "invalid-receipt-902");

    SqlDmlPreflightReceipt receipt = valid.receipt();
    SqlDmlPreflightReceipt invalidReceipt = new SqlDmlPreflightReceipt(
        receipt.contractVersion(),
        receipt.receiptId(),
        receipt.keyId(),
        receipt.issuedAt(),
        receipt.expiresAt(),
        receipt.operatorId(),
        receipt.requestHash(),
        receipt.connectionId(),
        receipt.targetEnvironment(),
        receipt.schema(),
        receipt.sqlHash(),
        receipt.parametersHash(),
        receipt.policyVersion(),
        receipt.policySelectionHash(),
        receipt.impactPreviewHash(),
        receipt.preflightHash(),
        receipt.signature() + "invalid");
    SqlWorkbenchException invalidReceiptFailure = assertThrows(
        SqlWorkbenchException.class,
        () -> harness.commit(new SqlDmlCommitRequest(
            "1.1", valid.query(), valid.confirmation(), invalidReceipt)));
    assertEquals("SQL_DML_PREFLIGHT_RECEIPT_INVALID", invalidReceiptFailure.code());

    SqlDmlConfirmation invalidConfirmation = new SqlDmlConfirmation(
        "1.0",
        valid.confirmation().sqlHash(),
        valid.confirmation().confirmedRisks(),
        "INVALID_CONFIRMATION");
    assertThrows(IllegalArgumentException.class, () -> harness.commit(new SqlDmlCommitRequest(
        "1.1", valid.query(), invalidConfirmation, valid.receipt())));

    Harness expired = Harness.configured(
        CLOCK, Clock.offset(CLOCK, Duration.ofMinutes(10)), DispatchMode.RETURN_RESULT);
    SqlDmlCommitRequest expiredCommit = expired.prepareCommit(insertSql(903), "expired-receipt-903");
    SqlWorkbenchException expiredFailure = assertThrows(
        SqlWorkbenchException.class,
        () -> expired.commit(expiredCommit));
    assertEquals("SQL_DML_PREFLIGHT_RECEIPT_EXPIRED", expiredFailure.code());

    assertEquals(0, harness.workerClient.commitDispatchCount.get());
    assertEquals(0, expired.workerClient.commitDispatchCount.get());
    assertEquals(0, harness.countRows("select count(*) from PUBLIC.ORDERS where ORDER_ID = 902"));
    assertEquals(0, expired.countRows("select count(*) from PUBLIC.ORDERS where ORDER_ID = 903"));
  }

  @Test
  void unknownOutcomeRequiresHandoffAndNeverReplaysTheWrite() throws Exception {
    Harness harness = Harness.configured(CLOCK, CLOCK, DispatchMode.THROW_AFTER_WRITE);
    SqlDmlCommitRequest commit = harness.prepareCommit(insertSql(904), "unknown-904");

    SqlQueryExecutionResult first = harness.commit(commit);
    SqlQueryExecutionResult duplicate = harness.commit(commit);

    assertEquals("UNKNOWN_REQUIRES_HANDOFF", first.status());
    assertEquals("SQL_DML_RESULT_UNKNOWN", first.errorCode());
    assertEquals(first, duplicate);
    assertTrue(!first.workflowId().isBlank());
    assertEquals(first.workflowId() + "-dml", first.executionRequestId());
    assertNull(first.resultId());
    assertNull(first.errorMessage());
    assertNull(first.affectedRows());
    assertEquals(1, harness.workerClient.commitDispatchCount.get());
    assertEquals(1, harness.countRows("select count(*) from PUBLIC.ORDERS where ORDER_ID = 904"));
    assertEquals("UNKNOWN_REQUIRES_HANDOFF", harness.workflowStatus("unknown-904"));
  }

  @Test
  void baseConfigurationsKeepDmlDisabledAndDemoProfilesActivateOnlyTheSitH2Slice()
      throws Exception {
    ControlledSqlDmlProperties defaults = bind(
        List.of(new ClassPathResource("application.yaml")),
        Map.of(),
        "ops-agent.controlled-sql-dml",
        ControlledSqlDmlProperties.class);

    assertEquals(Set.of(), defaults.getEnabledEnvironments());
    assertTrue(defaults.getRules().isEmpty());

    WorkerSqlEgressProperties workerDefaults = bind(
        List.of(workerConfiguration("application.yaml")),
        Map.of(),
        "ops-agent.worker.sql-egress",
        WorkerSqlEgressProperties.class);
    WorkerSqlEgressProperties.Connection workerDefault = workerDefaults.getConnections().getFirst();
    assertFalse(workerDefault.isDmlEnabled());
    assertTrue(workerDefault.getDmlCredentialAlias() == null
        || workerDefault.getDmlCredentialAlias().isBlank());

    StandardEnvironment controlProfiles = environmentFor(
        List.of(
            new ClassPathResource("application-demo.yaml"),
            new ClassPathResource("application.yaml")),
        Map.of());
    StandardEnvironment workerProfiles = environmentFor(
        List.of(
            workerConfiguration("application-demo.yaml"),
            workerConfiguration("application.yaml")),
        Map.of());
    assertEquals("false", controlProfiles.getProperty(
        "ops-agent.worker.transport-auth.enabled"));
    assertEquals("false", workerProfiles.getProperty(
        "ops-agent.worker.transport-auth.enabled"));

    Path workerDemoProfile = workerConfigurationPath("application-demo.yaml");
    assertTrue(Files.isRegularFile(workerDemoProfile));

    ConfiguredProperties configured = configuredProperties();
    assertEquals(Set.of("sit"), configured.control().getEnabledEnvironments());
    assertEquals(3, configured.control().getRules().size());
    assertEquals(1, configured.worker().getConnections().size());
    WorkerSqlEgressProperties.Connection demo = configured.worker().getConnections().getFirst();
    assertEquals("h2-local-test", demo.getConnectionId());
    assertEquals("sit", demo.getTargetEnvironment());
    assertTrue(demo.isDmlEnabled());
    assertNotEquals(demo.getCredentialAlias(), demo.getDmlCredentialAlias());
    assertTrue(demo.getDmlCredentialAlias() != null && !demo.getDmlCredentialAlias().isBlank());
    assertEquals("task7-sql-dml-receipt-v1", configured.receipt().getKeyId());
  }

  @Test
  void demoProfileRejectsMissingDmlReceiptSecretInjection() throws Exception {
    StandardEnvironment environment = environmentFor(
        List.of(new ClassPathResource("application-demo.yaml")), Map.of());

    assertThrows(IllegalArgumentException.class, () -> environment.getProperty(
        "ops-agent.controlled-sql-dml.preflight-receipt.hmac-secret"));
  }

  private static void assertExactlyOnce(Harness harness, String idempotencyKey, String sql) {
    SqlDmlCommitRequest commit = harness.prepareCommit(sql, idempotencyKey);

    SqlQueryExecutionResult first = harness.commit(commit);
    SqlQueryExecutionResult duplicate = harness.commit(commit);

    assertEquals("SUCCEEDED", first.status());
    assertEquals(1, first.affectedRows());
    assertEquals(first, duplicate);
  }

  private static String insertSql(int orderId) {
    return """
        insert into PUBLIC.ORDERS
          (ORDER_ID, STATUS, AMOUNT, CUSTOMER_ID, CREATED_AT)
        values
          (%d, 'NEW', 10.00, 'CUST-001', TIMESTAMP '2026-07-17 08:00:00')
        """.formatted(orderId);
  }

  private static ConfiguredProperties configuredProperties() throws IOException {
    String receiptSecret = runtimeKeyMaterial();
    ControlledSqlDmlProperties control = bind(
        List.of(
            new ClassPathResource("application-demo.yaml"),
            new ClassPathResource("application.yaml")),
        Map.of("ops-agent.controlled-sql-dml.preflight-receipt.hmac-secret", receiptSecret),
        "ops-agent.controlled-sql-dml",
        ControlledSqlDmlProperties.class);
    SqlDmlPreflightReceiptProperties receipt = bind(
        List.of(
            new ClassPathResource("application-demo.yaml"),
            new ClassPathResource("application.yaml")),
        Map.of("ops-agent.controlled-sql-dml.preflight-receipt.hmac-secret", receiptSecret),
        "ops-agent.controlled-sql-dml.preflight-receipt",
        SqlDmlPreflightReceiptProperties.class);
    WorkerSqlEgressProperties worker = bind(
        List.of(
            workerConfiguration("application-demo.yaml"),
            workerConfiguration("application.yaml")),
        Map.of(),
        "ops-agent.worker.sql-egress",
        WorkerSqlEgressProperties.class);
    return new ConfiguredProperties(control, receipt, worker, receiptSecret);
  }

  private static <T> T bind(
      List<Resource> resources,
      Map<String, Object> runtimeValues,
      String prefix,
      Class<T> type) throws IOException {
    StandardEnvironment environment = environmentFor(resources, runtimeValues);
    return Binder.get(environment).bind(prefix, Bindable.of(type))
        .orElseThrow(() -> new IllegalStateException(prefix + " is not configured"));
  }

  private static StandardEnvironment environmentFor(
      List<Resource> resources,
      Map<String, Object> runtimeValues) throws IOException {
    StandardEnvironment environment = new StandardEnvironment();
    MutablePropertySources sources = environment.getPropertySources();
    sources.addFirst(new MapPropertySource("task-7-runtime", runtimeValues));
    YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
    for (int index = resources.size() - 1; index >= 0; index--) {
      Resource resource = resources.get(index);
      List<org.springframework.core.env.PropertySource<?>> loaded =
          loader.load("task-7-" + index, resource);
      for (int sourceIndex = loaded.size() - 1; sourceIndex >= 0; sourceIndex--) {
        sources.addAfter("task-7-runtime", loaded.get(sourceIndex));
      }
    }
    return environment;
  }

  private static FileSystemResource workerConfiguration(String fileName) {
    return new FileSystemResource(workerConfigurationPath(fileName));
  }

  private static Path workerConfigurationPath(String fileName) {
    return Path.of("..", "..", "execution-worker", "src", "main", "resources", fileName);
  }

  private static String runtimeKeyMaterial() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getEncoder().encodeToString(bytes);
  }

  private enum DispatchMode {
    RETURN_RESULT,
    THROW_AFTER_WRITE
  }

  private record ConfiguredProperties(
      ControlledSqlDmlProperties control,
      SqlDmlPreflightReceiptProperties receipt,
      WorkerSqlEgressProperties worker,
      String receiptSecret) {
  }

  private static final class Harness {

    private final io.r2dbc.spi.ConnectionFactory connectionFactory;
    private final R2dbcAuditTrail auditTrail;
    private final RecordingCatalog catalog;
    private final RecordingWorkerClient workerClient;
    private final SqlWorkbenchService service;
    private final DataSource verificationDataSource;
    private final SqlDmlPreflightReceiptProperties receiptProperties;
    private final DatabaseClient databaseClient;
    private final Map<String, Object> demoProperties;

    private Harness(
        ConfiguredProperties configured,
        Clock signingClock,
        Clock dispatchClock,
        DispatchMode dispatchMode,
        boolean controlEnabled,
        boolean workerEnabled) {
      ControlledSqlDmlProperties control = controlEnabled
          ? configured.control()
          : new ControlledSqlDmlProperties();
      WorkerSqlEgressProperties workerProperties = configured.worker();
      WorkerSqlEgressProperties.Connection workerConnection =
          workerProperties.getConnections().getFirst();
      if (!workerEnabled) {
        workerConnection.setDmlEnabled(false);
      }
      WorkerSqlConnectionDescriptor descriptor = descriptor(workerConnection);
      H2SqlDataSourceFactory h2Factory = new H2SqlDataSourceFactory();
      ConfiguredSqlDataSourceRegistry registry = new ConfiguredSqlDataSourceRegistry(
          workerProperties.toPolicy(),
          alias -> {
            if (!workerConnection.getDmlCredentialAlias().equals(alias)) {
              throw new IllegalArgumentException("unexpected credential alias");
            }
            return WRITER_KEY_MATERIAL.clone();
          },
          new Jt400DataSourceFactory(),
          h2Factory);
      ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
      var consumedExecutionRequestIds = ConcurrentHashMap.<String>newKeySet();
      RestrictedSqlQueryExecutionWorker worker = new RestrictedSqlQueryExecutionWorker(
          new CalciteSqlReadOnlyGuard(),
          new CalciteSqlDmlGuard(),
          new JdbcSqlQueryExecutor(
              registry, new InMemorySqlResultStore(signingClock), objectMapper, signingClock),
          new JdbcSqlDmlImpactPreviewExecutor(registry, objectMapper),
          new WorkerSqlDmlExecutionPolicy(List.of(descriptor)),
          registry,
          consumedExecutionRequestIds::add,
          signingClock);
      workerClient = new RecordingWorkerClient(worker, dispatchMode);
      verificationDataSource = h2Factory.create(descriptor);

      connectionFactory = ConnectionFactories.get(
          "r2dbc:h2:mem:///task-7-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
      initializeWorkflowDatabase(connectionFactory);
      databaseClient = DatabaseClient.create(connectionFactory);
      auditTrail = new R2dbcAuditTrail(databaseClient);
      var store = new R2dbcControlledSqlDmlWorkflowStore(databaseClient, auditTrail);
      receiptProperties = configured.receipt();
      SqlDmlPreflightReceiptService signer =
          new SqlDmlPreflightReceiptService(receiptProperties, signingClock);
      SqlDmlPreflightReceiptService verifier =
          new SqlDmlPreflightReceiptService(receiptProperties, dispatchClock);
      ControlledSqlDmlWorkflowService workflow = new ControlledSqlDmlWorkflowService(
          store, workerClient::executeControlledDml, verifier, dispatchClock);
      catalog = new RecordingCatalog(connectionSummary());
      ControlledSqlDmlPolicy policy =
          new ControlledSqlDmlPolicy(control, new CalciteSqlDmlAnalysis());
      service = new DefaultSqlWorkbenchService(
          catalog,
          new CalciteSqlValidationService(),
          workerClient,
          prompt -> {
            throw new UnsupportedOperationException("assistant is outside Task 7");
          },
          policy,
          signer,
          workflow::execute,
          environment -> control.getEnabledEnvironments().stream()
              .anyMatch(enabled -> SqlTargetEnvironments.same(enabled, environment))
              && workerClient.supportsControlledDml(environment),
          auditTrail.supportsTransactionalParticipation(connectionFactory),
          signingClock);
      demoProperties = loadDemoProperties();
    }

    static Harness configured(Clock signingClock, Clock dispatchClock, DispatchMode mode)
        throws IOException {
      return new Harness(configuredProperties(), signingClock, dispatchClock, mode, true, true);
    }

    static Harness controlDisabled() throws IOException {
      return new Harness(configuredProperties(), CLOCK, CLOCK, DispatchMode.RETURN_RESULT, false, true);
    }

    static Harness workerDisabled() throws IOException {
      return new Harness(configuredProperties(), CLOCK, CLOCK, DispatchMode.RETURN_RESULT, true, false);
    }

    SqlDmlPreflightResult preflight(String sql, String idempotencyKey) {
      return service.preflightControlledDml(
          query("sit", SqlQueryAction.PREFLIGHT_DML, sql, idempotencyKey),
          OPERATOR,
          POLICY,
          trace(idempotencyKey));
    }

    SqlDmlCommitRequest prepareCommit(String sql, String idempotencyKey) {
      SqlDmlPreflightResult preflight = preflight(sql, idempotencyKey);
      SqlQueryRequest commitQuery = query(
          "sit", SqlQueryAction.COMMIT_DML, sql, idempotencyKey);
      SqlDmlConfirmation confirmation = new SqlDmlConfirmation(
          "1.0",
          preflight.validation().sqlHash(),
          preflight.validation().risks().isEmpty()
              ? List.of("CONTROLLED_DML_CONFIRMED")
              : preflight.validation().risks(),
          SqlDmlConfirmation.RISK_CONFIRMATION_CODE);
      return new SqlDmlCommitRequest("1.1", commitQuery, confirmation, preflight.receipt());
    }

    SqlQueryExecutionResult commit(SqlDmlCommitRequest request) {
      return service.commitControlledDml(
          request, OPERATOR, POLICY, trace(request.query().idempotencyKey()));
    }

    SqlQueryRequest query(
        String environment,
        SqlQueryAction action,
        String sql,
        String idempotencyKey) {
      return new SqlQueryRequest(
          "1.0",
          "h2-local-test",
          environment,
          "PUBLIC",
          action,
          sql,
          List.of(),
          LIMITS,
          idempotencyKey);
    }

    int countRows(String sql) throws Exception {
      try (var connection = verificationDataSource.getConnection();
          var statement = connection.createStatement();
          var rows = statement.executeQuery(sql)) {
        assertTrue(rows.next());
        return rows.getInt(1);
      }
    }

    String workflowStatus(String idempotencyKey) {
      return databaseClient.sql("""
              select status from controlled_sql_dml_workflow
              where idempotency_key = :idempotencyKey
              """)
          .bind("idempotencyKey", idempotencyKey)
          .map((row, metadata) -> row.get("status", String.class))
          .one()
          .block();
    }

    Object demoProperty(String name) {
      return demoProperties.get(name);
    }

    private static TraceContext trace(String suffix) {
      return new TraceContext("trace-" + suffix, "request-" + suffix);
    }

    private static WorkerSqlConnectionDescriptor descriptor(
        WorkerSqlEgressProperties.Connection connection) {
      return new WorkerSqlConnectionDescriptor(
          connection.getConnectionId(),
          connection.getTargetEnvironment(),
          connection.getPlatformType(),
          connection.getHost(),
          connection.getPort(),
          connection.getCredentialAlias(),
          connection.getUsername() == null || connection.getUsername().isBlank()
              ? connection.getCredentialAlias()
              : connection.getUsername(),
          connection.isEnabled(),
          connection.isDmlEnabled(),
          connection.getDmlCredentialAlias(),
          connection.getDmlUsername());
    }

    private static SqlConnectionSummary connectionSummary() {
      return new SqlConnectionSummary(
          "1.0",
          "h2-local-test",
          "Local H2 Test",
          "sit",
          "H2",
          "localhost",
          9092,
          "PUBLIC",
          List.of("PUBLIC"),
          List.of(
              SqlQueryAction.VALIDATE,
              SqlQueryAction.RUN_READ_ONLY,
              SqlQueryAction.PREFLIGHT_DML,
              SqlQueryAction.COMMIT_DML),
          "h2-local-readonly",
          "READY",
          100,
          30);
    }

    private static void initializeWorkflowDatabase(
        io.r2dbc.spi.ConnectionFactory connectionFactory) {
      var initializer = new ConnectionFactoryInitializer();
      initializer.setConnectionFactory(connectionFactory);
      initializer.setDatabasePopulator(new ResourceDatabasePopulator(
          new ClassPathResource("sql/migrations/V001__audit_event_schema.sql"),
          new ClassPathResource("sql/migrations/V004__controlled_sql_dml_workflow.sql"),
          new ClassPathResource("sql/migrations/V005__controlled_sql_dml_execution_expiry.sql")));
      initializer.afterPropertiesSet();
    }

    private static Map<String, Object> loadDemoProperties() {
      try {
        List<org.springframework.core.env.PropertySource<?>> sources =
            new YamlPropertySourceLoader().load(
                "task-7-demo", new ClassPathResource("application-demo.yaml"));
        Map<String, Object> values = new java.util.LinkedHashMap<>();
        for (org.springframework.core.env.PropertySource<?> source : sources) {
          if (source.getSource() instanceof Map<?, ?> map) {
            map.forEach((key, value) -> values.put(String.valueOf(key), value));
          }
        }
        return Map.copyOf(values);
      } catch (IOException exception) {
        throw new IllegalStateException("application-demo.yaml could not be loaded", exception);
      }
    }
  }

  private static final class RecordingWorkerClient implements SqlWorkbenchWorkerClient {

    private final RestrictedSqlQueryExecutionWorker worker;
    private final DispatchMode dispatchMode;
    private final AtomicInteger commitDispatchCount = new AtomicInteger();

    private RecordingWorkerClient(
        RestrictedSqlQueryExecutionWorker worker,
        DispatchMode dispatchMode) {
      this.worker = worker;
      this.dispatchMode = dispatchMode;
    }

    @Override
    public SqlDmlImpactPreview preflightDml(SqlDmlPreflightExecutionRequest request) {
      return worker.preflightDml(request).block();
    }

    @Override
    public SqlQueryExecutionResult executeControlledDml(SqlControlledDmlExecutionRequest request) {
      commitDispatchCount.incrementAndGet();
      SqlQueryExecutionResult result = worker.executeControlledDml(request);
      if (dispatchMode == DispatchMode.THROW_AFTER_WRITE && "SUCCEEDED".equals(result.status())) {
        throw new IllegalStateException("simulated response loss after commit");
      }
      return result;
    }

    @Override
    public boolean supportsControlledDml(String targetEnvironment) {
      return SqlTargetEnvironments.same("sit", targetEnvironment);
    }

    @Override
    public SqlConnectionProbeResult probe(SqlConnectionSummary connection) {
      throw new UnsupportedOperationException();
    }

    @Override
    public SqlQueryExecutionResult execute(SqlQueryExecutionRequest request) {
      return worker.execute(request);
    }

    @Override
    public SqlResultPage readResultPage(String resultId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public SqlDatabaseMetadata readMetadata(SqlConnectionSummary connection, String schema) {
      throw new UnsupportedOperationException();
    }
  }

  private static final class RecordingCatalog implements SqlConnectionCatalog {

    private SqlConnectionSummary connection;

    private RecordingCatalog(SqlConnectionSummary connection) {
      this.connection = connection;
    }

    void disableDmlCapabilities() {
      connection = new SqlConnectionSummary(
          connection.contractVersion(),
          connection.connectionId(),
          connection.displayName(),
          connection.targetEnvironment(),
          connection.platformType(),
          connection.host(),
          connection.port(),
          connection.defaultSchema(),
          connection.allowedSchemas(),
          List.of(SqlQueryAction.VALIDATE, SqlQueryAction.RUN_READ_ONLY),
          connection.credentialAlias(),
          connection.status(),
          connection.maxRowsDefault(),
          connection.timeoutSecondsDefault());
    }

    @Override
    public List<SqlConnectionSummary> list() {
      return List.of(connection);
    }

    @Override
    public Optional<SqlConnectionSummary> find(String connectionId) {
      return connection.connectionId().equals(connectionId)
          ? Optional.of(connection)
          : Optional.empty();
    }

    @Override
    public SqlConnectionSummary create(SqlConnectionCreateRequest request) {
      throw new UnsupportedOperationException();
    }

    @Override
    public SqlConnectionSummary update(String connectionId, SqlConnectionUpdateRequest request) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void delete(String connectionId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public SqlConnectionSummary updateStatus(String connectionId, String status) {
      throw new UnsupportedOperationException();
    }
  }
}
