package com.company.opsagent.controlplane.modules.release;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

public class R2dbcReleaseEventSink implements ReleaseEventSink {

  private final DatabaseClient databaseClient;
  private final ObjectMapper objectMapper;
  private final Sinks.Many<ReleaseWorkflowEvent> liveEvents = Sinks.many().multicast().directBestEffort();

  public R2dbcReleaseEventSink(DatabaseClient databaseClient) {
    this(databaseClient, new ObjectMapper());
  }

  public R2dbcReleaseEventSink(DatabaseClient databaseClient, ObjectMapper objectMapper) {
    this.databaseClient = ReleaseValues.required(databaseClient, "databaseClient");
    this.objectMapper = ReleaseValues.required(objectMapper, "objectMapper")
        .copy()
        .findAndRegisterModules()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
  }

  @Override
  public Mono<Void> publish(ReleaseWorkflowEvent event) {
    ReleaseWorkflowEvent releaseEvent = ReleaseValues.required(event, "event");
    return databaseClient.sql("""
            insert into release_workflow_event (
              release_id,
              event_sequence,
              event_id,
              workflow_id,
              contract_version,
              event_type,
              payload_json,
              audit_json,
              occurred_at
            ) values (
              :releaseId,
              :eventSequence,
              :eventId,
              :workflowId,
              :contractVersion,
              :eventType,
              :payloadJson,
              :auditJson,
              :occurredAt
            )
            """)
        .bind("releaseId", releaseEvent.releaseId())
        .bind("eventSequence", releaseEvent.sequence())
        .bind("eventId", releaseEvent.eventId())
        .bind("workflowId", releaseEvent.workflowId())
        .bind("contractVersion", releaseEvent.contractVersion())
        .bind("eventType", releaseEvent.type().name())
        .bind("payloadJson", json(releaseEvent.payload(), "release event payload"))
        .bind("auditJson", json(releaseEvent.audit(), "release event audit"))
        .bind("occurredAt", at(releaseEvent.timestamp()))
        .fetch()
        .rowsUpdated()
        .doOnSuccess(ignored -> liveEvents.tryEmitNext(releaseEvent))
        .then();
  }

  @Override
  public Mono<Long> nextSequence(String releaseId) {
    String id = ReleaseValues.requiredText(releaseId, "releaseId");
    return databaseClient.sql("""
            select coalesce(max(event_sequence), 0) + 1 as next_sequence
            from release_workflow_event
            where release_id = :releaseId
            """)
        .bind("releaseId", id)
        .map((row, metadata) -> number(row.get("next_sequence")).longValue())
        .one();
  }

  @Override
  public Flux<ReleaseWorkflowEvent> events(String releaseId, long afterSequence) {
    String id = ReleaseValues.requiredText(releaseId, "releaseId");
    Flux<ReleaseWorkflowEvent> storedEvents = databaseClient.sql("""
            select *
            from release_workflow_event
            where release_id = :releaseId
              and event_sequence > :afterSequence
            order by event_sequence asc
            """)
        .bind("releaseId", id)
        .bind("afterSequence", afterSequence)
        .map((row, metadata) -> event(
            row.get("contract_version", String.class),
            row.get("event_id", String.class),
            row.get("workflow_id", String.class),
            row.get("release_id", String.class),
            number(row.get("event_sequence")).longValue(),
            instant(row.get("occurred_at", OffsetDateTime.class)),
            ReleaseEventType.valueOf(row.get("event_type", String.class)),
            row.get("payload_json", String.class),
            row.get("audit_json", String.class)))
        .all();
    Flux<ReleaseWorkflowEvent> liveStream = liveEvents.asFlux()
        .filter(event -> id.equals(event.releaseId()) && event.sequence() > afterSequence);
    return storedEvents.concatWith(liveStream);
  }

  private ReleaseWorkflowEvent event(
      String contractVersion,
      String eventId,
      String workflowId,
      String releaseId,
      long sequence,
      Instant timestamp,
      ReleaseEventType type,
      String payloadJson,
      String auditJson) {
    return new ReleaseWorkflowEvent(
        contractVersion,
        eventId,
        workflowId,
        releaseId,
        sequence,
        timestamp,
        type,
        payload(type, payloadJson),
        audit(auditJson));
  }

  private ReleaseEventPayload payload(ReleaseEventType type, String payloadJson) {
    try {
      return switch (type) {
        case RELEASE_CREATED -> objectMapper.readValue(payloadJson, ReleaseEventPayload.Created.class);
        case RELEASE_CONFIRMED -> objectMapper.readValue(payloadJson, ReleaseEventPayload.Confirmed.class);
        case RELEASE_NODE_STARTED -> objectMapper.readValue(payloadJson, ReleaseEventPayload.NodeStarted.class);
        case RELEASE_NODE_LOG -> objectMapper.readValue(payloadJson, ReleaseEventPayload.NodeLog.class);
        case RELEASE_NODE_COMPLETED -> objectMapper.readValue(payloadJson, ReleaseEventPayload.NodeCompleted.class);
        case RELEASE_NODE_FAILED -> objectMapper.readValue(payloadJson, ReleaseEventPayload.NodeFailed.class);
        case RELEASE_PARTIAL_FAILED -> objectMapper.readValue(payloadJson, ReleaseEventPayload.PartialFailed.class);
        case RELEASE_MANUAL_INTERVENTION_REQUIRED -> objectMapper.readValue(
            payloadJson,
            ReleaseEventPayload.ManualInterventionRequired.class);
        default -> throw new IllegalStateException("unsupported release event payload type: " + type);
      };
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("release event payload is invalid", exception);
    }
  }

  private ReleaseAuditContext audit(String auditJson) {
    try {
      return objectMapper.readValue(auditJson, ReleaseAuditContext.class);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("release event audit is invalid", exception);
    }
  }

  private String json(Object value, String fieldName) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException(fieldName + " cannot be serialized", exception);
    }
  }

  private OffsetDateTime at(Instant instant) {
    return OffsetDateTime.ofInstant(ReleaseValues.required(instant, "instant"), ZoneOffset.UTC);
  }

  private Instant instant(OffsetDateTime value) {
    return ReleaseValues.required(value, "timestamp").toInstant();
  }

  private Number number(Object value) {
    if (value instanceof Number number) {
      return number;
    }
    return Mono.justOrEmpty(value)
        .map(Object::toString)
        .map(Long::parseLong)
        .blockOptional()
        .orElseThrow(() -> new IllegalArgumentException("numeric value is missing"));
  }
}
