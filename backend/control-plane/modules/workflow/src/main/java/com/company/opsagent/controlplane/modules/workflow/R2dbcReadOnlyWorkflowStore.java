package com.company.opsagent.controlplane.modules.workflow;

import com.company.opsagent.contracts.events.SemanticEvent;
import com.company.opsagent.contracts.events.SemanticEventPayload;
import com.company.opsagent.contracts.events.SemanticEventType;
import com.company.opsagent.contracts.events.AgentRuntimeProgressPayload;
import com.company.opsagent.contracts.events.AgentRuntimeProgressPayloadKind;
import com.company.opsagent.contracts.events.AgentToolCallCompletedPayload;
import com.company.opsagent.contracts.events.AgentToolCallRejectedPayload;
import com.company.opsagent.contracts.events.AgentToolCallRequestedPayload;
import com.company.opsagent.contracts.events.SkillRoutedPayload;
import com.company.opsagent.contracts.workflow.ReadOnlyCommandEnvelope;
import com.company.opsagent.contracts.events.WorkerAcceptedPayload;
import com.company.opsagent.contracts.events.WorkflowCompletedPayload;
import com.company.opsagent.contracts.events.WorkflowFailedPayload;
import com.company.opsagent.contracts.events.WorkflowStartedPayload;
import com.company.opsagent.contracts.workflow.WorkerExecutionResult;
import com.company.opsagent.contracts.workflow.WorkerExecutionStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.RowsFetchSpec;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 基于 R2DBC 的只读工作流持久化实现。
 *
 * <p>该实现把工作流实例、执行尝试和语义事件统一落到关系型数据库，作为 M05 恢复语义的事实源。
 */
public class R2dbcReadOnlyWorkflowStore implements ReadOnlyWorkflowStore {

  private final DatabaseClient databaseClient;
  private final ObjectMapper objectMapper;

  public R2dbcReadOnlyWorkflowStore(DatabaseClient databaseClient, ObjectMapper objectMapper) {
    this.databaseClient = databaseClient;
    this.objectMapper = objectMapper.copy().findAndRegisterModules();
  }

  @Override
  public Mono<Void> createWorkflow(
      String workflowId,
      String idempotencyKey,
      String operatorId,
      String targetEnvironment,
      String skillId,
      String skillVersion,
      String parametersHash,
      String policyDecisionId,
      String policyVersion,
      String traceId,
      String requestId,
      String commandId,
      ReadOnlyCommandEnvelope command,
      OffsetDateTime createdAt) {
    final String commandJson;
    try {
      commandJson = objectMapper.writeValueAsString(command);
    } catch (JsonProcessingException exception) {
      return Mono.error(new IllegalStateException(exception));
    }
    return databaseClient.sql("""
            insert into workflow_instance (
              workflow_id,
              idempotency_key,
              operator_id,
              target_environment,
              skill_id,
              skill_version,
              parameters_hash,
              status,
              policy_decision_id,
              policy_version,
              trace_id,
              request_id,
              command_id,
              command_json,
              current_attempt_no,
              max_replay_count,
              replay_count,
              created_at,
              updated_at
            ) values (
              :workflowId,
              :idempotencyKey,
              :operatorId,
              :targetEnvironment,
              :skillId,
              :skillVersion,
              :parametersHash,
              :status,
              :policyDecisionId,
              :policyVersion,
              :traceId,
              :requestId,
              :commandId,
              :commandJson,
              :currentAttemptNo,
              :maxReplayCount,
              :replayCount,
              :createdAt,
              :updatedAt
            )
            """)
        .bind("workflowId", workflowId)
        .bind("idempotencyKey", idempotencyKey)
        .bind("operatorId", operatorId)
        .bind("targetEnvironment", targetEnvironment)
        .bind("skillId", skillId)
        .bind("skillVersion", skillVersion)
        .bind("parametersHash", parametersHash)
        .bind("status", StoredWorkflowStatus.PENDING.name())
        .bind("policyDecisionId", policyDecisionId)
        .bind("policyVersion", policyVersion)
        .bind("traceId", traceId)
        .bind("requestId", requestId)
        .bind("commandId", commandId)
        .bind("commandJson", commandJson)
        .bind("currentAttemptNo", 0)
        .bind("maxReplayCount", 1)
        .bind("replayCount", 0)
        .bind("createdAt", createdAt)
        .bind("updatedAt", createdAt)
        .fetch()
        .rowsUpdated()
        .then(databaseClient.sql("""
                insert into workflow_idempotency (
                  idempotency_key,
                  operator_id,
                  target_environment,
                  skill_id,
                  parameters_hash,
                  workflow_id
                ) values (
                  :idempotencyKey,
                  :operatorId,
                  :targetEnvironment,
                  :skillId,
                  :parametersHash,
                  :workflowId
                )
                """)
            .bind("idempotencyKey", idempotencyKey)
            .bind("operatorId", operatorId)
            .bind("targetEnvironment", targetEnvironment)
            .bind("skillId", skillId)
            .bind("parametersHash", parametersHash)
            .bind("workflowId", workflowId)
            .fetch()
            .rowsUpdated())
        .then();
  }

  @Override
  public Mono<PersistedReadOnlyWorkflowView> findByIdempotency(
      String idempotencyKey,
      String operatorId,
      String targetEnvironment,
      String skillId,
      String parametersHash) {
    return databaseClient.sql("""
            select wi.*
            from workflow_idempotency idx
            join workflow_instance wi on wi.workflow_id = idx.workflow_id
            where idx.idempotency_key = :idempotencyKey
              and idx.operator_id = :operatorId
              and idx.target_environment = :targetEnvironment
              and idx.skill_id = :skillId
              and idx.parameters_hash = :parametersHash
            """)
        .bind("idempotencyKey", idempotencyKey)
        .bind("operatorId", operatorId)
        .bind("targetEnvironment", targetEnvironment)
        .bind("skillId", skillId)
        .bind("parametersHash", parametersHash)
        .map((row, metadata) -> new StoredReadOnlyWorkflow(
            row.get("workflow_id", String.class),
            row.get("idempotency_key", String.class),
            row.get("operator_id", String.class),
            row.get("target_environment", String.class),
            row.get("skill_id", String.class),
            row.get("skill_version", String.class),
            row.get("parameters_hash", String.class),
            StoredWorkflowStatus.valueOf(row.get("status", String.class)),
            row.get("policy_decision_id", String.class),
            row.get("policy_version", String.class),
            row.get("trace_id", String.class),
            row.get("request_id", String.class),
            row.get("command_id", String.class),
            row.get("command_json", String.class),
            valueOrZero(row.get("current_attempt_no", Integer.class)),
            valueOrZero(row.get("max_replay_count", Integer.class)),
            valueOrZero(row.get("replay_count", Integer.class)),
            row.get("result_status", String.class),
            row.get("result_schema_id", String.class),
            row.get("result_payload_json", String.class),
            row.get("error_code", String.class),
            row.get("error_message", String.class),
            row.get("created_at", OffsetDateTime.class),
            row.get("updated_at", OffsetDateTime.class),
            row.get("completed_at", OffsetDateTime.class)))
        .one()
        .flatMap(this::loadWorkflowView);
  }

  @Override
  public Flux<SemanticEvent> loadEventsAfter(String workflowId, long afterSequence) {
    RowsFetchSpec<StoredWorkflowEvent> fetchSpec = databaseClient.sql("""
            select workflow_id, sequence, event_id, event_type, event_payload_json, created_at
            from workflow_event
            where workflow_id = :workflowId
              and sequence > :afterSequence
            order by sequence asc
            """)
        .bind("workflowId", workflowId)
        .bind("afterSequence", afterSequence)
        .map((row, metadata) -> new StoredWorkflowEvent(
            row.get("workflow_id", String.class),
            valueOrZero(row.get("sequence", Long.class)),
            row.get("event_id", String.class),
            row.get("event_type", String.class),
            row.get("event_payload_json", String.class),
            row.get("created_at", OffsetDateTime.class)));
    return fetchSpec.all().map(this::deserializeEvent);
  }

  @Override
  public Mono<Void> appendEvent(String workflowId, long sequence, SemanticEvent event) {
    return Mono.fromCallable(() -> serialize(event))
        .flatMap(payload -> databaseClient.sql("""
                insert into workflow_event (
                  workflow_id,
                  sequence,
                  event_id,
                  event_type,
                  event_payload_json,
                  created_at
                ) values (
                  :workflowId,
                  :sequence,
                  :eventId,
                  :eventType,
                  :eventPayloadJson,
                  :createdAt
                )
                """)
            .bind("workflowId", workflowId)
            .bind("sequence", sequence)
            .bind("eventId", event.eventId())
            .bind("eventType", event.type().name())
            .bind("eventPayloadJson", payload)
            .bind("createdAt", event.timestamp())
            .fetch()
            .rowsUpdated()
        .then())
        .onErrorMap(JsonProcessingException.class, IllegalStateException::new);
  }

  @Override
  public Mono<Void> startAttempt(
      String workflowId,
      int attemptNo,
      String executionRequestId,
      StoredWorkflowAttemptKind attemptKind,
      OffsetDateTime startedAt,
      OffsetDateTime expiresAt) {
    StoredWorkflowStatus runningStatus = attemptKind == StoredWorkflowAttemptKind.INITIAL
        ? StoredWorkflowStatus.RUNNING
        : StoredWorkflowStatus.REPLAYING;
    return databaseClient.sql("""
            insert into workflow_attempt (
              workflow_id,
              attempt_no,
              execution_request_id,
              attempt_kind,
              status,
              started_at,
              expires_at,
              retryable
            ) values (
              :workflowId,
              :attemptNo,
              :executionRequestId,
              :attemptKind,
              :status,
              :startedAt,
              :expiresAt,
              :retryable
            )
            """)
        .bind("workflowId", workflowId)
        .bind("attemptNo", attemptNo)
        .bind("executionRequestId", executionRequestId)
        .bind("attemptKind", attemptKind.name())
        .bind("status", runningStatus.name())
        .bind("startedAt", startedAt)
        .bind("expiresAt", expiresAt)
        .bind("retryable", false)
        .fetch()
        .rowsUpdated()
        .then(updateWorkflowForAttempt(workflowId, attemptNo, runningStatus, startedAt, null));
  }

  @Override
  public Mono<Void> markWorkflowCompleted(
      String workflowId,
      StoredWorkflowStatus status,
      WorkerExecutionResult result,
      int attemptNo,
      boolean retryable,
      OffsetDateTime completedAt) {
    String resultPayload;
    try {
      resultPayload = result.output() == null ? null : objectMapper.writeValueAsString(result.output());
    } catch (JsonProcessingException exception) {
      return Mono.error(new IllegalStateException(exception));
    }
    GenericExecuteSpec spec = databaseClient.sql("""
            update workflow_instance
            set status = :status,
                current_attempt_no = :currentAttemptNo,
                result_status = :resultStatus,
                result_schema_id = :resultSchemaId,
                result_payload_json = :resultPayloadJson,
                error_code = :errorCode,
                error_message = :errorMessage,
                updated_at = :updatedAt,
                completed_at = :completedAt
            where workflow_id = :workflowId
            """)
        .bind("status", status.name())
        .bind("resultStatus", result.status().name())
        .bind("resultSchemaId", result.outputSchemaId())
        .bind("currentAttemptNo", attemptNo)
        .bind("updatedAt", completedAt)
        .bind("completedAt", completedAt)
        .bind("workflowId", workflowId);
    spec = bindNullable(spec, "resultPayloadJson", resultPayload, String.class);
    spec = bindNullable(spec, "errorCode", result.errorCode(), String.class);
    spec = bindNullable(spec, "errorMessage", result.errorMessage(), String.class);
    GenericExecuteSpec updateAttempt = databaseClient.sql("""
            update workflow_attempt
            set status = :status,
                completed_at = :completedAt,
                worker_error_code = :workerErrorCode,
                worker_error_message = :workerErrorMessage,
                retryable = :retryable
            where workflow_id = :workflowId
              and attempt_no = :attemptNo
            """)
        .bind("status", status.name())
        .bind("completedAt", completedAt)
        .bind("retryable", retryable)
        .bind("workflowId", workflowId)
        .bind("attemptNo", attemptNo);
    updateAttempt = bindNullable(updateAttempt, "workerErrorCode", result.errorCode(), String.class);
    updateAttempt = bindNullable(updateAttempt, "workerErrorMessage", result.errorMessage(), String.class);
    return spec.fetch()
        .rowsUpdated()
        .then(updateAttempt.fetch().rowsUpdated())
        .then();
  }

  @Override
  public Flux<PersistedReadOnlyWorkflowView> findReplayCandidates(OffsetDateTime updatedBefore) {
    return databaseClient.sql("""
            select wi.*
            from workflow_instance wi
            left join workflow_attempt wa
              on wa.workflow_id = wi.workflow_id
             and wa.attempt_no = wi.current_attempt_no
            where wi.replay_count < wi.max_replay_count
              and (
                (wi.status = :failedRetryableStatus and wi.updated_at <= :updatedBefore)
                or
                (wi.status = :runningStatus and wa.expires_at <= :updatedBefore)
                or
                (wi.status = :replayingStatus and wa.expires_at <= :updatedBefore)
              )
            order by wi.updated_at asc
            """)
        .bind("failedRetryableStatus", StoredWorkflowStatus.FAILED_RETRYABLE.name())
        .bind("runningStatus", StoredWorkflowStatus.RUNNING.name())
        .bind("replayingStatus", StoredWorkflowStatus.REPLAYING.name())
        .bind("updatedBefore", updatedBefore)
        .map((row, metadata) -> new StoredReadOnlyWorkflow(
            row.get("workflow_id", String.class),
            row.get("idempotency_key", String.class),
            row.get("operator_id", String.class),
            row.get("target_environment", String.class),
            row.get("skill_id", String.class),
            row.get("skill_version", String.class),
            row.get("parameters_hash", String.class),
            StoredWorkflowStatus.valueOf(row.get("status", String.class)),
            row.get("policy_decision_id", String.class),
            row.get("policy_version", String.class),
            row.get("trace_id", String.class),
            row.get("request_id", String.class),
            row.get("command_id", String.class),
            row.get("command_json", String.class),
            valueOrZero(row.get("current_attempt_no", Integer.class)),
            valueOrZero(row.get("max_replay_count", Integer.class)),
            valueOrZero(row.get("replay_count", Integer.class)),
            row.get("result_status", String.class),
            row.get("result_schema_id", String.class),
            row.get("result_payload_json", String.class),
            row.get("error_code", String.class),
            row.get("error_message", String.class),
            row.get("created_at", OffsetDateTime.class),
            row.get("updated_at", OffsetDateTime.class),
            row.get("completed_at", OffsetDateTime.class)))
        .all()
        .flatMap(this::loadWorkflowView);
  }

  @Override
  public Mono<Void> markReplayStarted(
      String workflowId,
      int attemptNo,
      int replayCount,
      String executionRequestId,
      OffsetDateTime startedAt,
      OffsetDateTime expiresAt) {
    return databaseClient.sql("""
            insert into workflow_attempt (
              workflow_id,
              attempt_no,
              execution_request_id,
              attempt_kind,
              status,
              started_at,
              expires_at,
              retryable
            ) values (
              :workflowId,
              :attemptNo,
              :executionRequestId,
              :attemptKind,
              :status,
              :startedAt,
              :expiresAt,
              :retryable
            )
            """)
        .bind("workflowId", workflowId)
        .bind("attemptNo", attemptNo)
        .bind("executionRequestId", executionRequestId)
        .bind("attemptKind", StoredWorkflowAttemptKind.REPLAY.name())
        .bind("status", StoredWorkflowStatus.REPLAYING.name())
        .bind("startedAt", startedAt)
        .bind("expiresAt", expiresAt)
        .bind("retryable", false)
        .fetch()
        .rowsUpdated()
        .then(databaseClient.sql("""
            update workflow_instance
            set status = :status,
                current_attempt_no = :attemptNo,
                replay_count = :replayCount,
                result_status = null,
                result_schema_id = null,
                result_payload_json = null,
                error_code = null,
                error_message = null,
                updated_at = :updatedAt,
                completed_at = null
            where workflow_id = :workflowId
            """)
        .bind("status", StoredWorkflowStatus.REPLAYING.name())
        .bind("attemptNo", attemptNo)
        .bind("replayCount", replayCount)
        .bind("updatedAt", startedAt)
        .bind("workflowId", workflowId)
        .fetch()
        .rowsUpdated()
        .then());
  }

  private String serialize(SemanticEvent event) throws JsonProcessingException {
    return objectMapper.writeValueAsString(event);
  }

  private Mono<PersistedReadOnlyWorkflowView> loadWorkflowView(StoredReadOnlyWorkflow workflow) {
    return Mono.zip(
            loadAttempts(workflow.workflowId()).collectList(),
            loadEvents(workflow.workflowId()).collectList())
        .map(tuple -> new PersistedReadOnlyWorkflowView(
            workflow,
            deserializeCommand(workflow),
            deserializeResult(workflow),
            tuple.getT1(),
            tuple.getT2()));
  }

  private Flux<StoredWorkflowAttempt> loadAttempts(String workflowId) {
    RowsFetchSpec<StoredWorkflowAttempt> fetchSpec = databaseClient.sql("""
            select workflow_id,
                   attempt_no,
                   execution_request_id,
                   attempt_kind,
                   status,
                   started_at,
                   completed_at,
                   expires_at,
                   worker_error_code,
                   worker_error_message,
                   retryable
            from workflow_attempt
            where workflow_id = :workflowId
            order by attempt_no asc
            """)
        .bind("workflowId", workflowId)
        .map((row, metadata) -> new StoredWorkflowAttempt(
            row.get("workflow_id", String.class),
            valueOrZero(row.get("attempt_no", Integer.class)),
            row.get("execution_request_id", String.class),
            StoredWorkflowAttemptKind.valueOf(row.get("attempt_kind", String.class)),
            StoredWorkflowStatus.valueOf(row.get("status", String.class)),
            row.get("started_at", OffsetDateTime.class),
            row.get("completed_at", OffsetDateTime.class),
            row.get("expires_at", OffsetDateTime.class),
            row.get("worker_error_code", String.class),
            row.get("worker_error_message", String.class),
            Boolean.TRUE.equals(row.get("retryable", Boolean.class))));
    return fetchSpec.all();
  }

  private reactor.core.publisher.Flux<com.company.opsagent.contracts.events.SemanticEvent> loadEvents(String workflowId) {
    RowsFetchSpec<StoredWorkflowEvent> fetchSpec = databaseClient.sql("""
            select workflow_id, sequence, event_id, event_type, event_payload_json, created_at
            from workflow_event
            where workflow_id = :workflowId
            order by sequence asc
            """)
        .bind("workflowId", workflowId)
        .map((row, metadata) -> new StoredWorkflowEvent(
            row.get("workflow_id", String.class),
            valueOrZero(row.get("sequence", Long.class)),
            row.get("event_id", String.class),
            row.get("event_type", String.class),
            row.get("event_payload_json", String.class),
            row.get("created_at", OffsetDateTime.class)));
    return fetchSpec.all().map(this::deserializeEvent);
  }

  private WorkerExecutionResult deserializeResult(StoredReadOnlyWorkflow workflow) {
    if (workflow.resultStatus() == null || workflow.completedAt() == null) {
      return null;
    }
    try {
      JsonNode output = workflow.resultPayloadJson() == null ? null : objectMapper.readTree(workflow.resultPayloadJson());
      return new WorkerExecutionResult(
          "1.0",
          workflow.commandId(),
          workflow.commandId(),
          workflow.workflowId(),
          WorkerExecutionStatus.valueOf(workflow.resultStatus()),
          workflow.resultSchemaId(),
          output,
          workflow.errorCode(),
          workflow.errorMessage(),
          workflow.completedAt());
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private ReadOnlyCommandEnvelope deserializeCommand(StoredReadOnlyWorkflow workflow) {
    try {
      return objectMapper.readValue(workflow.commandJson(), ReadOnlyCommandEnvelope.class);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private SemanticEvent deserializeEvent(StoredWorkflowEvent storedEvent) {
    SemanticEventType type = SemanticEventType.valueOf(storedEvent.eventType());
    try {
      JsonNode serializedEvent = objectMapper.readTree(storedEvent.eventPayloadJson());
      JsonNode payloadNode = serializedEvent.has("payload") ? serializedEvent.get("payload") : serializedEvent;
      SemanticEventPayload payload = switch (type) {
        case WORKFLOW_STARTED -> new WorkflowStartedPayload(
            type,
            payloadNode.get("commandId").asText(),
            payloadNode.get("operatorId").asText());
        case SKILL_ROUTED -> new SkillRoutedPayload(
            type,
            payloadNode.get("skillId").asText(),
            payloadNode.get("skillVersion").asText());
        case WORKER_ACCEPTED -> new WorkerAcceptedPayload(
            type,
            payloadNode.get("executionRequestId").asText());
        case AGENT_TOOL_CALL_REQUESTED -> new AgentToolCallRequestedPayload(
            type,
            payloadNode.get("toolCallId").asText(),
            payloadNode.get("stepSequence").asLong(),
            payloadNode.get("skillId").asText(),
            payloadNode.get("skillVersion").asText(),
            payloadNode.get("parameterSchemaId").asText(),
            payloadNode.get("targetEnvironment").asText(),
            payloadNode.get("parametersHash").asText());
        case AGENT_TOOL_CALL_COMPLETED -> new AgentToolCallCompletedPayload(
            type,
            payloadNode.get("toolCallId").asText(),
            payloadNode.get("stepSequence").asLong(),
            payloadNode.get("skillId").asText(),
            payloadNode.get("skillVersion").asText(),
            payloadNode.get("status").asText(),
            payloadNode.get("outputSchemaId").asText());
        case AGENT_TOOL_CALL_REJECTED -> new AgentToolCallRejectedPayload(
            type,
            payloadNode.get("toolCallId").asText(),
            payloadNode.get("stepSequence").asLong(),
            payloadNode.get("skillId").asText(),
            payloadNode.get("skillVersion").asText(),
            payloadNode.get("errorCode").asText(),
            payloadNode.get("message").asText(),
            payloadNode.get("policyDecisionId").asText());
        case AGENT_RUNTIME_PROGRESS -> new AgentRuntimeProgressPayload(
            type,
            AgentRuntimeProgressPayloadKind.valueOf(payloadNode.get("progressKind").asText()),
            payloadNode.get("message").asText(),
            nullableText(payloadNode, "replyId"),
            nullableText(payloadNode, "blockId"),
            nullableText(payloadNode, "toolCallId"),
            nullableText(payloadNode, "toolName"),
            nullableText(payloadNode, "agentId"),
            nullableText(payloadNode, "sessionId"),
            nullableText(payloadNode, "subagentId"),
            payloadNode.get("inputTokens").asInt(),
            payloadNode.get("outputTokens").asInt(),
            payloadNode.get("totalTokens").asInt(),
            payloadNode.get("modelTimeSeconds").asDouble(),
            payloadNode.get("sensitiveContentSuppressed").asBoolean());
        case WORKFLOW_COMPLETED -> new WorkflowCompletedPayload(
            type,
            payloadNode.get("outputSchemaId").asText(),
            payloadNode.get("output"));
        case WORKFLOW_FAILED -> new WorkflowFailedPayload(
            type,
            payloadNode.get("errorCode").asText(),
            payloadNode.get("message").asText());
      };
      return new SemanticEvent(
          "1.0",
          storedEvent.eventId(),
          storedEvent.workflowId(),
          storedEvent.sequence(),
          storedEvent.createdAt(),
          type,
          payload);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private String nullableText(JsonNode node, String fieldName) {
    JsonNode field = node.get(fieldName);
    if (field == null || field.isNull()) {
      return null;
    }
    return field.asText();
  }

  private GenericExecuteSpec bindNullable(
      GenericExecuteSpec spec,
      String name,
      Object value,
      Class<?> type) {
    return value == null ? spec.bindNull(name, type) : spec.bind(name, value);
  }

  /**
   * 在开始新尝试时，把工作流主记录切换为运行中并清空上一次终态结果。
   */
  private Mono<Void> updateWorkflowForAttempt(
      String workflowId,
      int attemptNo,
      StoredWorkflowStatus status,
      OffsetDateTime updatedAt,
      Integer replayCount) {
    GenericExecuteSpec spec = databaseClient.sql("""
            update workflow_instance
            set status = :status,
                current_attempt_no = :attemptNo,
                result_status = null,
                result_schema_id = null,
                result_payload_json = null,
                error_code = null,
                error_message = null,
                updated_at = :updatedAt,
                completed_at = null,
                replay_count = coalesce(:replayCount, replay_count)
            where workflow_id = :workflowId
            """)
        .bind("status", status.name())
        .bind("attemptNo", attemptNo)
        .bind("updatedAt", updatedAt)
        .bind("workflowId", workflowId);
    spec = replayCount == null
        ? spec.bindNull("replayCount", Integer.class)
        : spec.bind("replayCount", replayCount);
    return spec.fetch().rowsUpdated().then();
  }

  private int valueOrZero(Integer value) {
    return value == null ? 0 : value;
  }

  private long valueOrZero(Long value) {
    return value == null ? 0L : value;
  }
}
