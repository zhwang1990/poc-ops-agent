package com.company.opsagent.controlplane.modules.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.r2dbc.connection.init.ConnectionFactoryInitializer;
import org.springframework.r2dbc.connection.init.ResourceDatabasePopulator;
import org.springframework.r2dbc.core.DatabaseClient;

class R2dbcAuditTrailTest {

  @Test
  void recordsEventsToDatabaseAndReloadsSnapshot() {
    ConnectionFactory connectionFactory = connectionFactory("audit-store-reload");
    initialize(connectionFactory);
    R2dbcAuditTrail auditTrail = new R2dbcAuditTrail(DatabaseClient.create(connectionFactory));
    AuditEvent event = event("event-1", "request-1", "trace-1", "alice", "ALLOW");

    auditTrail.record(event);

    R2dbcAuditTrail reloaded = new R2dbcAuditTrail(DatabaseClient.create(connectionFactory));
    assertEquals(1, reloaded.snapshot().size());
    assertEquals("event-1", reloaded.latest().orElseThrow().eventId());
    assertEquals("alice", reloaded.latest().orElseThrow().subject());
  }

  @Test
  void clearsDatabaseAndMemorySnapshot() {
    ConnectionFactory connectionFactory = connectionFactory("audit-store-clear");
    initialize(connectionFactory);
    R2dbcAuditTrail auditTrail = new R2dbcAuditTrail(DatabaseClient.create(connectionFactory));
    auditTrail.record(event("event-1", "request-1", "trace-1", "alice", "ALLOW"));

    auditTrail.clear();

    assertTrue(auditTrail.snapshot().isEmpty());
    R2dbcAuditTrail reloaded = new R2dbcAuditTrail(DatabaseClient.create(connectionFactory));
    assertTrue(reloaded.snapshot().isEmpty());
  }

  @Test
  void recordsNullReasonAsEmptyReason() {
    ConnectionFactory connectionFactory = connectionFactory("audit-store-null-reason");
    initialize(connectionFactory);
    R2dbcAuditTrail auditTrail = new R2dbcAuditTrail(DatabaseClient.create(connectionFactory));
    auditTrail.record(event("event-1", "request-1", "trace-1", "alice", "ALLOW", null));

    R2dbcAuditTrail reloaded = new R2dbcAuditTrail(DatabaseClient.create(connectionFactory));

    assertEquals("", reloaded.latest().orElseThrow().reason());
  }

  private AuditEvent event(
      String eventId,
      String requestId,
      String traceId,
      String subject,
      String result) {
    return new AuditEvent(
        eventId,
        requestId,
        traceId,
        subject,
        "internal.audit.test",
        "/internal/audit/test",
        "rbac-v1",
        result,
        "test audit event",
        OffsetDateTime.parse("2026-07-05T12:00:00Z"));
  }

  private AuditEvent event(
      String eventId,
      String requestId,
      String traceId,
      String subject,
      String result,
      String reason) {
    return new AuditEvent(
        eventId,
        requestId,
        traceId,
        subject,
        "internal.audit.test",
        "/internal/audit/test",
        "rbac-v1",
        result,
        reason,
        OffsetDateTime.parse("2026-07-05T12:00:00Z"));
  }

  private ConnectionFactory connectionFactory(String name) {
    return ConnectionFactories.get("r2dbc:h2:mem:///" + name + "-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
  }

  private void initialize(ConnectionFactory connectionFactory) {
    var initializer = new ConnectionFactoryInitializer();
    initializer.setConnectionFactory(connectionFactory);
    initializer.setDatabasePopulator(new ResourceDatabasePopulator(
        new ClassPathResource("sql/migrations/V001__audit_event_schema.sql")));
    initializer.afterPropertiesSet();
  }
}
