package com.company.opsagent.controlplane.modules.release;

import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

@FunctionalInterface
public interface ReleaseWorkerGateway {

  Mono<ReleaseNodeExecutionResult> execute(ReleasePlan plan, ReleaseNodeStep node);

  default Flux<ReleaseNodeExecutionEvent> executeWithEvents(ReleasePlan plan, ReleaseNodeStep node) {
    return execute(plan, node).map(ReleaseNodeExecutionEvent::result).flux();
  }
}
