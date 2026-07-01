package com.company.opsagent.controlplane.modules.release;

import java.util.ArrayList;
import java.util.List;
import reactor.core.publisher.Mono;

public class InMemoryReleaseEventSink implements ReleaseEventSink {

  private final List<ReleaseWorkflowEvent> events = new ArrayList<>();

  @Override
  public Mono<Void> publish(ReleaseWorkflowEvent event) {
    events.add(event);
    return Mono.empty();
  }

  public List<ReleaseWorkflowEvent> events() {
    return List.copyOf(events);
  }
}
