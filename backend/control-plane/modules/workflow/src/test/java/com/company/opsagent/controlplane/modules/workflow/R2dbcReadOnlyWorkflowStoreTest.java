package com.company.opsagent.controlplane.modules.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.company.opsagent.contracts.events.AgentRuntimeProgressPayload;
import com.company.opsagent.contracts.events.AgentRuntimeProgressPayloadKind;
import com.company.opsagent.contracts.events.AgentToolCallRequestedPayload;
import com.company.opsagent.contracts.events.SemanticEvent;
import com.company.opsagent.contracts.events.SemanticEventType;
import com.company.opsagent.contracts.events.SkillRoutedPayload;
import com.company.opsagent.contracts.events.WorkerAcceptedPayload;
import com.company.opsagent.contracts.events.WorkflowCompletedPayload;
import com.company.opsagent.contracts.events.WorkflowStartedPayload;
import com.company.opsagent.contracts.workflow.OperatorContext;
import com.company.opsagent.contracts.workflow.PolicyDecisionReference;
import com.company.opsagent.contracts.workflow.ReadOnlyCommandEnvelope;
import com.company.opsagent.contracts.workflow.SkillReference;
import com.company.opsagent.contracts.workflow.TraceContext;
import com.company.opsagent.contracts.workflow.WorkerExecutionResult;
import com.company.opsagent.contracts.workflow.WorkerExecutionStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.r2dbc.connection.init.ConnectionFactoryInitializer;
import org.springframework.r2dbc.connection.init.ResourceDatabasePopulator;
import org.springframework.r2dbc.core.DatabaseClient;
import io.r2dbc.spi.ConnectionFactories;
import reactor.test.StepVerifier;

class R2dbcReadOnlyWorkflowStoreTest {

  @Test
  void createsWorkflowAndFindsItByIdempotencyTuple() {
    var store = testStore();
    var created = store.createWorkflow(
        "workflow-1",
        "idempotency-1",
        "operator-1",
        "development",
        "node-health-read",
        "1.1.0",
        "sha256:abc",
        "decision-1",
        "policy-v1",
        "trace-1",
        "request-1",
        "command-1",
        command("workflow-1", "command-1", "idempotency-1"),
        OffsetDateTime.parse("2026-06-07T01:00:00Z"));

    StepVerifier.create(created.then(store.findByIdempotency(
            "idempotency-1",
            "operator-1",
            "development",
            "node-health-read",
            "sha256:abc")))
        .assertNext(view -> {
          assertEquals("workflow-1", view.workflow().workflowId());
          assertEquals(StoredWorkflowStatus.PENDING, view.workflow().status());
        })
        .verifyComplete();
  }

  @Test
  void persistsRunningAttemptAndReturnsItInWorkflowView() {
    var store = testStore();
    OffsetDateTime now = OffsetDateTime.parse("2026-06-07T01:00:00Z");

    StepVerifier.create(store.createWorkflow(
            "workflow-3",
            "idempotency-3",
            "operator-1",
            "development",
            "node-health-read",
            "1.1.0",
            "sha256:ghi",
            "decision-1",
            "policy-v1",
            "trace-1",
            "request-1",
            "command-3",
            command("workflow-3", "command-3", "idempotency-3"),
            now)
        .then(store.startAttempt(
            "workflow-3",
            1,
            "execution-3",
            StoredWorkflowAttemptKind.INITIAL,
            now,
            now.plusSeconds(30)))
        .then(store.findByIdempotency(
            "idempotency-3",
            "operator-1",
            "development",
            "node-health-read",
            "sha256:ghi")))
        .assertNext(view -> {
          assertEquals(StoredWorkflowStatus.RUNNING, view.workflow().status());
          assertEquals(1, view.workflow().currentAttemptNo());
          assertEquals(1, view.attempts().size());
          assertEquals(StoredWorkflowAttemptKind.INITIAL, view.attempts().getFirst().attemptKind());
        })
        .verifyComplete();
  }

  @Test
  void reloadsPersistedExecutionResultAndEvents() {
    var store = testStore();
    OffsetDateTime now = OffsetDateTime.parse("2026-06-07T01:00:00Z");
    StepVerifier.create(store.createWorkflow(
            "workflow-2",
            "idempotency-2",
            "operator-1",
            "development",
            "node-health-read",
            "1.1.0",
            "sha256:def",
            "decision-1",
            "policy-v1",
            "trace-1",
            "request-1",
            "command-2",
            command("workflow-2", "command-2", "idempotency-2"),
            now)
        .then(store.startAttempt(
            "workflow-2",
            1,
            "execution-2",
            StoredWorkflowAttemptKind.INITIAL,
            now,
            now.plusSeconds(30)))
        .then(store.appendEvent("workflow-2", 1, new SemanticEvent(
            "1.0",
            "event-1",
            "workflow-2",
            1,
            now,
            SemanticEventType.WORKFLOW_STARTED,
            new WorkflowStartedPayload(SemanticEventType.WORKFLOW_STARTED, "command-2", "operator-1"))))
        .then(store.appendEvent("workflow-2", 2, new SemanticEvent(
            "1.0",
            "event-2",
            "workflow-2",
            2,
            now.plusSeconds(1),
            SemanticEventType.WORKFLOW_COMPLETED,
            new WorkflowCompletedPayload(
                SemanticEventType.WORKFLOW_COMPLETED,
                "node-health-read:1.1.0:output",
                new ObjectMapper().createObjectNode().put("status", "HEALTHY")))))
        .then(store.markWorkflowCompleted(
            "workflow-2",
            StoredWorkflowStatus.SUCCEEDED,
            new WorkerExecutionResult(
                "1.0",
                "execution-2",
                "command-2",
                "workflow-2",
                WorkerExecutionStatus.SUCCEEDED,
                "node-health-read:1.1.0:output",
                new ObjectMapper().createObjectNode().put("status", "HEALTHY"),
                null,
                null,
                now.plusSeconds(1)),
            1,
            false,
            now.plusSeconds(1)))
        .then(store.findByIdempotency(
            "idempotency-2",
            "operator-1",
            "development",
            "node-health-read",
            "sha256:def")))
        .assertNext(view -> {
          assertEquals(StoredWorkflowStatus.SUCCEEDED, view.workflow().status());
          assertNotNull(view.executionResult());
          assertEquals(WorkerExecutionStatus.SUCCEEDED, view.executionResult().status());
          assertEquals(2, view.events().size());
        })
        .verifyComplete();
  }

  @Test
  void loadsOnlyEventsAfterGivenSequence() {
    var store = testStore();
    OffsetDateTime now = OffsetDateTime.parse("2026-06-07T01:00:00Z");

    StepVerifier.create(store.createWorkflow(
            "workflow-4",
            "idempotency-4",
            "operator-1",
            "development",
            "node-health-read",
            "1.1.0",
            "sha256:jkl",
            "decision-1",
            "policy-v1",
            "trace-1",
            "request-1",
            "command-4",
            command("workflow-4", "command-4", "idempotency-4"),
            now)
        .then(store.appendEvent("workflow-4", 1, new SemanticEvent(
            "1.0",
            "event-4-1",
            "workflow-4",
            1,
            now,
            SemanticEventType.WORKFLOW_STARTED,
            new WorkflowStartedPayload(SemanticEventType.WORKFLOW_STARTED, "command-4", "operator-1"))))
        .then(store.appendEvent("workflow-4", 2, new SemanticEvent(
            "1.0",
            "event-4-2",
            "workflow-4",
            2,
            now.plusSeconds(1),
            SemanticEventType.SKILL_ROUTED,
            new SkillRoutedPayload(SemanticEventType.SKILL_ROUTED, "node-health-read", "1.1.0"))))
        .then(store.appendEvent("workflow-4", 3, new SemanticEvent(
            "1.0",
            "event-4-3",
            "workflow-4",
            3,
            now.plusSeconds(2),
            SemanticEventType.WORKER_ACCEPTED,
            new WorkerAcceptedPayload(SemanticEventType.WORKER_ACCEPTED, "execution-4"))))
        .then(store.appendEvent("workflow-4", 4, new SemanticEvent(
            "1.0",
            "event-4-4",
            "workflow-4",
            4,
            now.plusSeconds(3),
            SemanticEventType.AGENT_TOOL_CALL_REQUESTED,
            new AgentToolCallRequestedPayload(
                SemanticEventType.AGENT_TOOL_CALL_REQUESTED,
                "tool-call-1",
                1,
                "node-health-read",
                "1.1.0",
                "node-health-read:1.1.0:input",
                "development",
                "sha256:tool-params"))))
        .thenMany(store.loadEventsAfter("workflow-4", 1)))
        .assertNext(event -> assertEquals(2, event.sequence()))
        .assertNext(event -> assertEquals(3, event.sequence()))
        .assertNext(event -> {
          assertEquals(4, event.sequence());
          assertEquals(SemanticEventType.AGENT_TOOL_CALL_REQUESTED, event.type());
        })
        .verifyComplete();
  }

  @Test
  void reloadsAgentRuntimeProgressEventPayloads() {
    var store = testStore();
    OffsetDateTime now = OffsetDateTime.parse("2026-07-01T00:00:00Z");

    StepVerifier.create(store.appendEvent("workflow-runtime", 10_001, new SemanticEvent(
            "1.0",
            "event-runtime-progress-1",
            "workflow-runtime",
            10_001,
            now,
            SemanticEventType.AGENT_RUNTIME_PROGRESS,
            new AgentRuntimeProgressPayload(
                SemanticEventType.AGENT_RUNTIME_PROGRESS,
                AgentRuntimeProgressPayloadKind.MODEL_CALL_COMPLETED,
                "model call completed",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                12,
                5,
                17,
                0.42,
                false)))
        .thenMany(store.loadEventsAfter("workflow-runtime", 10_000)))
        .assertNext(event -> {
          assertEquals(SemanticEventType.AGENT_RUNTIME_PROGRESS, event.type());
          var payload = assertInstanceOf(AgentRuntimeProgressPayload.class, event.payload());
          assertEquals(AgentRuntimeProgressPayloadKind.MODEL_CALL_COMPLETED, payload.progressKind());
          assertEquals(17, payload.totalTokens());
        })
        .verifyComplete();
  }

  private R2dbcReadOnlyWorkflowStore testStore() {
    var connectionFactory = ConnectionFactories.get(
        "r2dbc:h2:mem:///workflow-store-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
    var initializer = new ConnectionFactoryInitializer();
    initializer.setConnectionFactory(connectionFactory);
    initializer.setDatabasePopulator(new ResourceDatabasePopulator(
        new ClassPathResource("sql/migrations/V001__workflow_schema.sql")));
    initializer.afterPropertiesSet();
    return new R2dbcReadOnlyWorkflowStore(DatabaseClient.create(connectionFactory), new ObjectMapper());
  }

  private ReadOnlyCommandEnvelope command(String workflowId, String commandId, String idempotencyKey) {
    return new ReadOnlyCommandEnvelope(
        "1.0",
        commandId,
        workflowId,
        idempotencyKey,
        "READ_ONLY",
        "development",
        new SkillReference(
            "node-health-read",
            "1.1.0",
            "node-health-read:1.1.0:input",
            "node-health-read:1.1.0:output"),
        new ObjectMapper().createObjectNode().put("nodeName", "node-a"),
        new OperatorContext("operator-1", java.util.List.of("ROLE_ops-reader")),
        new PolicyDecisionReference("decision-1", "policy-v1", "ALLOW"),
        new TraceContext("trace-1", "request-1"),
        OffsetDateTime.parse("2026-06-07T01:00:00Z"));
  }
}
