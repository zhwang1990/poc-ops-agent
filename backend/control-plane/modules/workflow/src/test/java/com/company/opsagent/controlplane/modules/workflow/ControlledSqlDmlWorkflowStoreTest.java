package com.company.opsagent.controlplane.modules.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.company.opsagent.contracts.sqlworkbench.SqlStatementType;
import com.company.opsagent.controlplane.modules.audit.InMemoryAuditTrail;
import io.r2dbc.spi.ConnectionFactories;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.r2dbc.connection.init.ConnectionFactoryInitializer;
import org.springframework.r2dbc.connection.init.ResourceDatabasePopulator;
import org.springframework.r2dbc.core.DatabaseClient;

class ControlledSqlDmlWorkflowStoreTest {

  @Test
  void reusesIdenticalIdempotencyBinding() {
    var store = testStore();
    store.create(createdWorkflow("workflow-1", "binding-a")).block();

    assertEquals(
        "workflow-1",
        store.findByIdempotency("key-1", "operator-1", "sit").block().workflowId());
    assertEquals(
        "workflow-1",
        store.create(createdWorkflow("workflow-2", "binding-a")).block().workflowId());
  }

  @Test
  void rejectsChangedBindingForSameKeyWithoutCreatingAnotherWorkflow() {
    var store = testStore();
    store.create(createdWorkflow("workflow-1", "binding-a")).block();

    var exception = assertThrows(
        ControlledSqlDmlWorkflowStore.IdempotencyConflictException.class,
        () -> store.create(createdWorkflow("workflow-2", "binding-b")).block());

    assertEquals("SQL_DML_IDEMPOTENCY_CONFLICT", exception.code());
    assertEquals(
        "workflow-1",
        store.findByIdempotency("key-1", "operator-1", "sit").block().workflowId());
  }

  @Test
  void rejectsChangedBindingWhenCompatibilityIsCheckedDirectly() {
    var store = testStore();
    store.create(createdWorkflow("workflow-1", "binding-a")).block();

    var exception = assertThrows(
        ControlledSqlDmlWorkflowStore.IdempotencyConflictException.class,
        () -> store.assertCompatible(
            "key-1", "operator-1", "sit", "binding-b").block());

    assertEquals("SQL_DML_IDEMPOTENCY_CONFLICT", exception.code());
  }

  @Test
  void rejectsPrepopulatedTerminalFactsAtCreation() {
    var store = testStore();

    assertThrows(
        IllegalArgumentException.class,
        () -> store.create(createdWorkflow(
            "workflow-1", "binding-a", "sample-value select * from secret")).block());

    assertNull(store.findByIdempotency("key-1", "operator-1", "sit").block());
  }

  private R2dbcControlledSqlDmlWorkflowStore testStore() {
    var connectionFactory = ConnectionFactories.get(
        "r2dbc:h2:mem:///controlled-sql-dml-store-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
    var initializer = new ConnectionFactoryInitializer();
    initializer.setConnectionFactory(connectionFactory);
    initializer.setDatabasePopulator(new ResourceDatabasePopulator(
        new ClassPathResource("sql/migrations/V004__controlled_sql_dml_workflow.sql")));
    initializer.afterPropertiesSet();
    return new R2dbcControlledSqlDmlWorkflowStore(
        DatabaseClient.create(connectionFactory), new InMemoryAuditTrail());
  }

  private ControlledSqlDmlWorkflow createdWorkflow(String workflowId, String bindingHash) {
    return createdWorkflow(workflowId, bindingHash, null);
  }

  private ControlledSqlDmlWorkflow createdWorkflow(
      String workflowId,
      String bindingHash,
      String failureCode) {
    OffsetDateTime createdAt = OffsetDateTime.parse("2026-07-17T10:00:00Z");
    return new ControlledSqlDmlWorkflow(
        workflowId,
        "key-1",
        "operator-1",
        "sit",
        bindingHash,
        "as400-sit",
        "OPS",
        SqlStatementType.UPDATE,
        "sql-hash-a",
        "parameters-hash-a",
        "preflight-hash-a",
        "confirmation-hash-a",
        "decision-1",
        "policy-v1",
        "trace-1",
        "request-1",
        ControlledSqlDmlWorkflow.Status.CREATED,
        0,
        null,
        failureCode,
        createdAt,
        createdAt,
        null);
  }
}
