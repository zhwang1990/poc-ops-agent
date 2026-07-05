package com.company.opsagent.controlplane.modules.release;

import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

@FunctionalInterface
public interface ReleaseEventSink {

  Mono<Void> publish(ReleaseWorkflowEvent event);

  default Mono<Long> nextSequence(String releaseId) {
    return Mono.empty();
  }

  default Flux<ReleaseWorkflowEvent> events(String releaseId, long afterSequence) {
    return Flux.empty();
  }

  static ReleaseEventSink noop() {
    return event -> Mono.empty();
  }
}
