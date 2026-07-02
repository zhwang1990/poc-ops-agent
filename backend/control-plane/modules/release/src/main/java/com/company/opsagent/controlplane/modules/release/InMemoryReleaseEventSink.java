package com.company.opsagent.controlplane.modules.release;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

public class InMemoryReleaseEventSink implements ReleaseEventSink {

  private final List<ReleaseWorkflowEvent> events = new CopyOnWriteArrayList<>();
  private final Sinks.Many<ReleaseWorkflowEvent> eventStream = Sinks.many().replay().limit(1000);

  @Override
  public Mono<Void> publish(ReleaseWorkflowEvent event) {
    events.add(event);
    eventStream.tryEmitNext(event);
    return Mono.empty();
  }

  @Override
  public Flux<ReleaseWorkflowEvent> events(String releaseId, long afterSequence) {
    String id = ReleaseValues.requiredText(releaseId, "releaseId");
    return eventStream.asFlux()
        .filter(event -> id.equals(event.releaseId()) && event.sequence() > afterSequence);
  }

  public List<ReleaseWorkflowEvent> events() {
    return new ArrayList<>(events);
  }
}
