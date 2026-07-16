package com.company.opsagent.controlplane.modules.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.company.opsagent.contracts.sqlworkbench.SqlStatementType;
import com.company.opsagent.controlplane.modules.audit.AuditEvent;
import com.company.opsagent.controlplane.modules.audit.AuditTrail;
import com.company.opsagent.controlplane.modules.audit.InMemoryAuditTrail;
import io.r2dbc.spi.ConnectionFactories;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.r2dbc.connection.init.ConnectionFactoryInitializer;
import org.springframework.r2dbc.connection.init.ResourceDatabasePopulator;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.test.StepVerifier;

class R2dbcControlledSqlDmlWorkflowStoreTest {

  private static final String BINDING_HASH = "a".repeat(64);
  private static final String SQL_HASH = "b".repeat(64);
  private static final String PARAMETERS_HASH = "c".repeat(64);
  private static final String PREFLIGHT_HASH = "d".repeat(64);
  private static final String CONFIRMATION_HASH = "e".repeat(64);

  private DatabaseClient databaseClient;

  @Test
  void rejectsRawLookingValuesForEveryPersistedHashBeforePersistenceOrAudit() {
    OffsetDateTime createdAt = OffsetDateTime.parse("2026-07-17T10:00:00Z");
    List<ControlledSqlDmlWorkflow> invalidWorkflows = List.of(
        createdWorkflowWithHashes(
            "workflow-binding", "key-binding", "binding-a", SQL_HASH,
            PARAMETERS_HASH, PREFLIGHT_HASH, CONFIRMATION_HASH, createdAt),
        createdWorkflowWithHashes(
            "workflow-sql", "key-sql", BINDING_HASH,
            "UPDATE OPS.ACCOUNT SET PASSWORD='sample-value'", PARAMETERS_HASH,
            PREFLIGHT_HASH, CONFIRMATION_HASH, createdAt),
        createdWorkflowWithHashes(
            "workflow-parameters", "key-parameters", BINDING_HASH, SQL_HASH,
            "{\"password\":\"sample-value\"}", PREFLIGHT_HASH,
            CONFIRMATION_HASH, createdAt),
        createdWorkflowWithHashes(
            "workflow-preflight", "key-preflight", BINDING_HASH, SQL_HASH,
            PARAMETERS_HASH, "sample rows: secret", CONFIRMATION_HASH, createdAt),
        createdWorkflowWithHashes(
            "workflow-confirmation", "key-confirmation", BINDING_HASH, SQL_HASH,
            PARAMETERS_HASH, PREFLIGHT_HASH, "CONFIRM sample-value", createdAt));

    for (ControlledSqlDmlWorkflow invalidWorkflow : invalidWorkflows) {
      var auditTrail = new InMemoryAuditTrail();
      var store = testStore(auditTrail);

      StepVerifier.create(store.create(invalidWorkflow))
          .expectError(IllegalArgumentException.class)
          .verify();
      assertTrue(auditTrail.snapshot().isEmpty());
      assertTrue(store.findByIdempotency(
          invalidWorkflow.idempotencyKey(),
          invalidWorkflow.operatorId(),
          invalidWorkflow.targetEnvironment()).blockOptional().isEmpty());
    }
  }

  @Test
  void excludesIndividualHashesFromAuditReasons() {
    var auditTrail = new InMemoryAuditTrail();
    var store = testStore(auditTrail);
    OffsetDateTime createdAt = OffsetDateTime.parse("2026-07-17T10:00:00Z");

    store.create(createdWorkflow("workflow-safe-audit", "key-safe-audit", createdAt)).block();
    store.markConfirmed("workflow-safe-audit", createdAt.plusSeconds(1)).block();
    store.markSubmitted("workflow-safe-audit", createdAt.plusSeconds(2)).block();
    store.markSucceeded("workflow-safe-audit", 1, createdAt.plusSeconds(3)).block();

    List<String> individualHashes = List.of(
        BINDING_HASH, SQL_HASH, PARAMETERS_HASH, PREFLIGHT_HASH, CONFIRMATION_HASH);
    assertTrue(auditTrail.snapshot().stream()
        .map(event -> event.reason())
        .noneMatch(reason -> individualHashes.stream().anyMatch(reason::contains)));
  }

  @Test
  void rollsBackSubmissionWhenRequiredAuditFails() {
    var auditTrail = new FailingActionAuditTrail("SQL_DML_SUBMITTED");
    var store = testStore(auditTrail);
    OffsetDateTime createdAt = OffsetDateTime.parse("2026-07-17T10:00:00Z");

    store.create(createdWorkflow("workflow-audit-failure", "key-audit-failure", createdAt)).block();
    store.markConfirmed("workflow-audit-failure", createdAt.plusSeconds(1)).block();

    StepVerifier.create(store.markSubmitted("workflow-audit-failure", createdAt.plusSeconds(2)))
        .expectErrorMessage("forced audit failure")
        .verify();

    ControlledSqlDmlWorkflow persisted = store.findByIdempotency(
        "key-audit-failure", "operator-1", "sit").block();
    assertEquals(ControlledSqlDmlWorkflow.Status.CREATED, persisted.status());
    assertEquals(0, persisted.attemptCount());
    assertEquals(createdAt.plusSeconds(1), persisted.confirmedAt());
    assertEquals(
        List.of("SQL_DML_CREATED", "SQL_DML_CONFIRMED"),
        auditTrail.snapshot().stream().map(AuditEvent::action).toList());
  }

  @Test
  void requiresDurableConfirmationBeforeSubmission() {
    var auditTrail = new InMemoryAuditTrail();
    var store = testStore(auditTrail);
    OffsetDateTime createdAt = OffsetDateTime.parse("2026-07-17T10:00:00Z");
    OffsetDateTime confirmedAt = createdAt.plusSeconds(1);

    store.create(createdWorkflow("workflow-confirmed", "key-confirmed", createdAt)).block();

    StepVerifier.create(store.markSubmitted("workflow-confirmed", createdAt.plusSeconds(2)))
        .expectError(IllegalStateException.class)
        .verify();
    assertEquals(
        ControlledSqlDmlWorkflow.Status.CREATED,
        store.findByIdempotency("key-confirmed", "operator-1", "sit").block().status());

    store.markConfirmed("workflow-confirmed", confirmedAt).block();
    assertEquals(
        confirmedAt,
        databaseClient.sql("""
                select confirmed_at
                from controlled_sql_dml_workflow
                where workflow_id = :workflowId
                """)
            .bind("workflowId", "workflow-confirmed")
            .map((row, metadata) -> row.get("confirmed_at", OffsetDateTime.class))
            .one()
            .block());

    StepVerifier.create(store.markSubmitted("workflow-confirmed", createdAt.plusSeconds(2)))
        .assertNext(workflow -> assertEquals(
            ControlledSqlDmlWorkflow.Status.RUNNING, workflow.status()))
        .verifyComplete();
  }

  @Test
  void persistsControlledDmlLifecycleAndAuditsOnlySafeFacts() {
    var auditTrail = new InMemoryAuditTrail();
    var store = testStore(auditTrail);
    OffsetDateTime createdAt = OffsetDateTime.parse("2026-07-17T10:00:00Z");

    StepVerifier.create(store.create(createdWorkflow("workflow-1", "key-1", createdAt))
        .then(store.markConfirmed("workflow-1", createdAt.plusSeconds(1)))
        .then(store.markSubmitted("workflow-1", createdAt.plusSeconds(2)))
        .then(store.markSucceeded("workflow-1", 3, createdAt.plusSeconds(3))))
        .assertNext(workflow -> {
          assertEquals(ControlledSqlDmlWorkflow.Status.SUCCEEDED, workflow.status());
          assertEquals(1, workflow.attemptCount());
          assertEquals(3, workflow.affectedRowCount());
        })
        .verifyComplete();

    assertEquals(
        List.of("SQL_DML_CREATED", "SQL_DML_CONFIRMED", "SQL_DML_SUBMITTED", "SQL_DML_SUCCEEDED"),
        auditTrail.snapshot().stream().map(event -> event.action()).toList());
    assertTrue(auditTrail.snapshot().stream()
        .allMatch(event -> event.resource().equals("sql-workbench:as400-sit:OPS:UPDATE")));
    assertTrue(auditTrail.snapshot().stream()
        .noneMatch(event -> event.reason().contains("sample-value")));
  }

  @Test
  void recordsFailedAndUnknownResultsAsTerminalWithoutReplayCandidates() {
    var auditTrail = new InMemoryAuditTrail();
    var store = testStore(auditTrail);
    OffsetDateTime createdAt = OffsetDateTime.parse("2026-07-17T10:00:00Z");

    StepVerifier.create(store.create(createdWorkflow("workflow-failed", "key-failed", createdAt))
        .then(store.markConfirmed("workflow-failed", createdAt.plusSeconds(1)))
        .then(store.markSubmitted("workflow-failed", createdAt.plusSeconds(2)))
        .then(store.markFailed(
            "workflow-failed", "SQL_DML_WORKER_FAILED", createdAt.plusSeconds(3)))
        .then(store.create(createdWorkflow("workflow-handoff", "key-handoff", createdAt)))
        .then(store.markConfirmed("workflow-handoff", createdAt.plusSeconds(1)))
        .then(store.markSubmitted("workflow-handoff", createdAt.plusSeconds(2)))
        .then(store.markHandoffRequired(
            "workflow-handoff", "SQL_DML_RESULT_UNKNOWN", createdAt.plusSeconds(3))))
        .assertNext(workflow -> {
          assertEquals(ControlledSqlDmlWorkflow.Status.UNKNOWN_REQUIRES_HANDOFF, workflow.status());
          assertTrue(workflow.status().isTerminal());
          assertFalse(workflow.status().isReplayable());
        })
        .verifyComplete();

    assertEquals(
        List.of(
            "SQL_DML_CREATED",
            "SQL_DML_CONFIRMED",
            "SQL_DML_SUBMITTED",
            "SQL_DML_FAILED",
            "SQL_DML_CREATED",
            "SQL_DML_CONFIRMED",
            "SQL_DML_SUBMITTED",
            "SQL_DML_HANDOFF_REQUIRED"),
        auditTrail.snapshot().stream().map(event -> event.action()).toList());
    assertEquals(
        ControlledSqlDmlWorkflow.Status.FAILED,
        store.findByIdempotency("key-failed", "operator-1", "sit").block().status());
  }

  @Test
  void rejectsFreeFormFailureDetailsBeforePersistenceOrAudit() {
    var auditTrail = new InMemoryAuditTrail();
    var store = testStore(auditTrail);
    OffsetDateTime createdAt = OffsetDateTime.parse("2026-07-17T10:00:00Z");

    StepVerifier.create(store.create(createdWorkflow("workflow-unsafe", "key-unsafe", createdAt))
        .then(store.markConfirmed("workflow-unsafe", createdAt.plusSeconds(1)))
        .then(store.markSubmitted("workflow-unsafe", createdAt.plusSeconds(2)))
        .then(store.markFailed(
            "workflow-unsafe", "sample-value select * from secret", createdAt.plusSeconds(3))))
        .expectError(IllegalArgumentException.class)
        .verify();

    assertEquals(
        ControlledSqlDmlWorkflow.Status.RUNNING,
        store.findByIdempotency("key-unsafe", "operator-1", "sit").block().status());
    assertEquals(
        List.of("SQL_DML_CREATED", "SQL_DML_CONFIRMED", "SQL_DML_SUBMITTED"),
        auditTrail.snapshot().stream().map(event -> event.action()).toList());
  }

  private R2dbcControlledSqlDmlWorkflowStore testStore(AuditTrail auditTrail) {
    var connectionFactory = ConnectionFactories.get(
        "r2dbc:h2:mem:///controlled-sql-dml-r2dbc-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
    var initializer = new ConnectionFactoryInitializer();
    initializer.setConnectionFactory(connectionFactory);
    initializer.setDatabasePopulator(new ResourceDatabasePopulator(
        new ClassPathResource("sql/migrations/V004__controlled_sql_dml_workflow.sql")));
    initializer.afterPropertiesSet();
    databaseClient = DatabaseClient.create(connectionFactory);
    return new R2dbcControlledSqlDmlWorkflowStore(databaseClient, auditTrail);
  }

  private ControlledSqlDmlWorkflow createdWorkflow(
      String workflowId,
      String idempotencyKey,
      OffsetDateTime createdAt) {
    return createdWorkflowWithHashes(
        workflowId,
        idempotencyKey,
        BINDING_HASH,
        SQL_HASH,
        PARAMETERS_HASH,
        PREFLIGHT_HASH,
        CONFIRMATION_HASH,
        createdAt);
  }

  private ControlledSqlDmlWorkflow createdWorkflowWithHashes(
      String workflowId,
      String idempotencyKey,
      String bindingHash,
      String sqlHash,
      String parametersHash,
      String preflightHash,
      String confirmationHash,
      OffsetDateTime createdAt) {
    return new ControlledSqlDmlWorkflow(
        workflowId,
        idempotencyKey,
        "operator-1",
        "sit",
        bindingHash,
        "as400-sit",
        "OPS",
        SqlStatementType.UPDATE,
        sqlHash,
        parametersHash,
        preflightHash,
        confirmationHash,
        "decision-1",
        "policy-v1",
        "trace-1",
        "request-1",
        ControlledSqlDmlWorkflow.Status.CREATED,
        0,
        null,
        null,
        null,
        createdAt,
        createdAt,
        null);
  }

  private static final class FailingActionAuditTrail extends InMemoryAuditTrail {

    private final String failingAction;

    private FailingActionAuditTrail(String failingAction) {
      this.failingAction = failingAction;
    }

    @Override
    public void record(AuditEvent event) {
      if (failingAction.equals(event.action())) {
        throw new IllegalStateException("forced audit failure");
      }
      super.record(event);
    }
  }
}
