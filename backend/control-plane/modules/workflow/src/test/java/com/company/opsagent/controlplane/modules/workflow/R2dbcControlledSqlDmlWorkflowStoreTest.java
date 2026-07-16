package com.company.opsagent.controlplane.modules.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.company.opsagent.contracts.sqlworkbench.SqlStatementType;
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
        .then(store.markSubmitted("workflow-failed", createdAt.plusSeconds(1)))
        .then(store.markFailed(
            "workflow-failed", "SQL_DML_WORKER_FAILED", createdAt.plusSeconds(2)))
        .then(store.create(createdWorkflow("workflow-handoff", "key-handoff", createdAt)))
        .then(store.markSubmitted("workflow-handoff", createdAt.plusSeconds(1)))
        .then(store.markHandoffRequired(
            "workflow-handoff", "SQL_DML_RESULT_UNKNOWN", createdAt.plusSeconds(2))))
        .assertNext(workflow -> {
          assertEquals(ControlledSqlDmlWorkflow.Status.UNKNOWN_REQUIRES_HANDOFF, workflow.status());
          assertTrue(workflow.status().isTerminal());
          assertFalse(workflow.status().isReplayable());
        })
        .verifyComplete();

    assertEquals(
        List.of(
            "SQL_DML_CREATED",
            "SQL_DML_SUBMITTED",
            "SQL_DML_FAILED",
            "SQL_DML_CREATED",
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
        .then(store.markSubmitted("workflow-unsafe", createdAt.plusSeconds(1)))
        .then(store.markFailed(
            "workflow-unsafe", "sample-value select * from secret", createdAt.plusSeconds(2))))
        .expectError(IllegalArgumentException.class)
        .verify();

    assertEquals(
        ControlledSqlDmlWorkflow.Status.RUNNING,
        store.findByIdempotency("key-unsafe", "operator-1", "sit").block().status());
    assertEquals(
        List.of("SQL_DML_CREATED", "SQL_DML_SUBMITTED"),
        auditTrail.snapshot().stream().map(event -> event.action()).toList());
  }

  private R2dbcControlledSqlDmlWorkflowStore testStore(InMemoryAuditTrail auditTrail) {
    var connectionFactory = ConnectionFactories.get(
        "r2dbc:h2:mem:///controlled-sql-dml-r2dbc-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
    var initializer = new ConnectionFactoryInitializer();
    initializer.setConnectionFactory(connectionFactory);
    initializer.setDatabasePopulator(new ResourceDatabasePopulator(
        new ClassPathResource("sql/migrations/V004__controlled_sql_dml_workflow.sql")));
    initializer.afterPropertiesSet();
    return new R2dbcControlledSqlDmlWorkflowStore(DatabaseClient.create(connectionFactory), auditTrail);
  }

  private ControlledSqlDmlWorkflow createdWorkflow(
      String workflowId,
      String idempotencyKey,
      OffsetDateTime createdAt) {
    return new ControlledSqlDmlWorkflow(
        workflowId,
        idempotencyKey,
        "operator-1",
        "sit",
        "binding-hash-" + idempotencyKey,
        "as400-sit",
        "OPS",
        SqlStatementType.UPDATE,
        "sql-hash-" + idempotencyKey,
        "parameters-hash-" + idempotencyKey,
        "preflight-hash-" + idempotencyKey,
        "confirmation-hash-" + idempotencyKey,
        "decision-1",
        "policy-v1",
        "trace-1",
        "request-1",
        ControlledSqlDmlWorkflow.Status.CREATED,
        0,
        null,
        null,
        createdAt,
        createdAt,
        null);
  }
}
