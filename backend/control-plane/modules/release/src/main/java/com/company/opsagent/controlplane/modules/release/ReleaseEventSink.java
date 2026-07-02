package com.company.opsagent.controlplane.modules.release;

import reactor.core.publisher.Mono;

@FunctionalInterface
public interface ReleaseEventSink {

  Mono<Void> publish(ReleaseWorkflowEvent event);

  static ReleaseEventSink noop() {
    return event -> Mono.empty();
  }
}
