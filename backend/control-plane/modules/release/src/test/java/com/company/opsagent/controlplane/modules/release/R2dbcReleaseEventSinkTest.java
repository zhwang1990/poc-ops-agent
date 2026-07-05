package com.company.opsagent.controlplane.modules.release;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.r2dbc.connection.init.ConnectionFactoryInitializer;
import org.springframework.r2dbc.connection.init.ResourceDatabasePopulator;
import org.springframework.r2dbc.core.DatabaseClient;

class R2dbcReleaseEventSinkTest {

  @Test
  void persistsEventsAndContinuesSequenceFromStoredRows() {
    R2dbcReleaseEventSink sink = new R2dbcReleaseEventSink(
        databaseClient(),
        new ObjectMapper().findAndRegisterModules());
    Instant emittedAt = Instant.parse("2026-07-02T00:00:00Z");
    ReleaseWorkflowEvent event = new ReleaseWorkflowEvent(
        "1.0",
        UUID.randomUUID().toString(),
        UUID.nameUUIDFromBytes("rel-1".getBytes(StandardCharsets.UTF_8)).toString(),
        "rel-1",
        1,
        emittedAt,
        ReleaseEventType.RELEASE_NODE_LOG,
        new ReleaseEventPayload.NodeLog("node-1", "STDOUT", "deploy started", emittedAt),
        new ReleaseAuditContext(
            "RELEASE_NODE_LOG",
            "release:rel-1",
            "release-center-policy-v1",
            "LOG",
            "release node script output",
            "trace:rel-1",
            "request:rel-1"));

    sink.publish(event).block();

    List<ReleaseWorkflowEvent> loaded = sink.events("rel-1", 0).take(1).collectList().block();
    ReleaseWorkflowEvent loadedEvent = loaded.getFirst();
    ReleaseEventPayload.NodeLog payload = assertInstanceOf(
        ReleaseEventPayload.NodeLog.class,
        loadedEvent.payload());
    assertEquals(1L, loadedEvent.sequence());
    assertEquals(ReleaseEventType.RELEASE_NODE_LOG, loadedEvent.type());
    assertEquals("deploy started", payload.message());
    assertEquals(2L, sink.nextSequence("rel-1").block());
  }

  private DatabaseClient databaseClient() {
    var connectionFactory = connectionFactory("release-events");
    initialize(connectionFactory, new ClassPathResource("sql/migrations/V005__release_workflow_event.sql"));
    return DatabaseClient.create(connectionFactory);
  }

  private ConnectionFactory connectionFactory(String prefix) {
    return ConnectionFactories.get("r2dbc:h2:mem:///" + prefix + "-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
  }

  private void initialize(ConnectionFactory connectionFactory, ClassPathResource script) {
    var initializer = new ConnectionFactoryInitializer();
    initializer.setConnectionFactory(connectionFactory);
    initializer.setDatabasePopulator(new ResourceDatabasePopulator(script));
    initializer.afterPropertiesSet();
  }
}
