package com.company.opsagent.controlplane.modules.audit;

import io.r2dbc.spi.ConnectionFactory;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.NoTransactionException;
import org.springframework.transaction.reactive.TransactionSynchronization;
import org.springframework.transaction.reactive.TransactionSynchronizationManager;
import reactor.core.publisher.Mono;

/**
 * 基于 R2DBC 的审计链实现。
 *
 * <p>P2 阶段以关系型数据库作为审计主存储，同时保留内存快照用于最近记录查询。
 */
public class R2dbcAuditTrail implements AuditTrail {

  private final DatabaseClient databaseClient;
  private final ConnectionFactory connectionFactory;
  private final CopyOnWriteArrayList<AuditEvent> events = new CopyOnWriteArrayList<>();

  public R2dbcAuditTrail(DatabaseClient databaseClient) {
    this.databaseClient = databaseClient;
    this.connectionFactory = databaseClient.getConnectionFactory();
    loadExisting();
  }

  @Override
  public boolean supportsTransactionalParticipation(ConnectionFactory connectionFactory) {
    return this.connectionFactory == connectionFactory;
  }

  @Override
  public void record(AuditEvent event) {
    recordReactive(event).block();
  }

  @Override
  public Mono<Void> recordReactive(AuditEvent event) {
    Mono<Void> insert = databaseClient.sql("""
            insert into audit_event (
              event_id,
              request_id,
              trace_id,
              subject,
              action,
              resource,
              policy_version,
              result,
              reason,
              occurred_at
            ) values (
              :eventId,
              :requestId,
              :traceId,
              :subject,
              :action,
              :resource,
              :policyVersion,
              :result,
              :reason,
              :occurredAt
            )
            """)
        .bind("eventId", event.eventId())
        .bind("requestId", event.requestId())
        .bind("traceId", event.traceId())
        .bind("subject", event.subject())
        .bind("action", event.action())
        .bind("resource", event.resource())
        .bind("policyVersion", event.policyVersion())
        .bind("result", event.result())
        .bind("reason", nullToBlank(event.reason()))
        .bind("occurredAt", event.timestamp())
        .fetch()
        .rowsUpdated()
        .then();
    return TransactionSynchronizationManager.forCurrentTransaction()
        .flatMap(manager -> {
          manager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public Mono<Void> afterCommit() {
              return Mono.fromRunnable(() -> events.add(event));
            }
          });
          return insert;
        })
        .onErrorResume(NoTransactionException.class, ignored ->
            insert.then(Mono.fromRunnable(() -> events.add(event))));
  }

  @Override
  public List<AuditEvent> snapshot() {
    return List.copyOf(events);
  }

  @Override
  public Optional<AuditEvent> latest() {
    List<AuditEvent> events = snapshot();
    if (events.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(events.get(events.size() - 1));
  }

  @Override
  public void clear() {
    databaseClient.sql("delete from audit_event")
        .fetch()
        .rowsUpdated()
        .block();
    events.clear();
  }

  private void loadExisting() {
    List<AuditEvent> loaded = databaseClient.sql("""
            select event_id,
                   request_id,
                   trace_id,
                   subject,
                   action,
                   resource,
                   policy_version,
                   result,
                   reason,
                   occurred_at
            from audit_event
            order by occurred_at asc, event_id asc
            """)
        .map((row, metadata) -> new AuditEvent(
            row.get("event_id", String.class),
            row.get("request_id", String.class),
            row.get("trace_id", String.class),
            row.get("subject", String.class),
            row.get("action", String.class),
            row.get("resource", String.class),
            row.get("policy_version", String.class),
            row.get("result", String.class),
            row.get("reason", String.class),
            row.get("occurred_at", OffsetDateTime.class)))
        .all()
        .collectList()
        .block();
    if (loaded != null) {
      events.addAll(loaded);
    }
  }

  private String nullToBlank(String value) {
    return value == null ? "" : value;
  }
}
