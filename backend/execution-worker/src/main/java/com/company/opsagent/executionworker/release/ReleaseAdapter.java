package com.company.opsagent.executionworker.release;

import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

public interface ReleaseAdapter {

  String managementMode();

  Mono<ReleaseWorkerResult> precheck(ReleaseWorkerRequest request);

  default Flux<ReleaseWorkerExecutionEvent> precheckWithEvents(ReleaseWorkerRequest request) {
    return precheck(request).map(ReleaseWorkerExecutionEvent::result).flux();
  }

  Mono<ReleaseWorkerResult> deploy(ReleaseWorkerRequest request);

  default Flux<ReleaseWorkerExecutionEvent> deployWithEvents(ReleaseWorkerRequest request) {
    return deploy(request).map(ReleaseWorkerExecutionEvent::result).flux();
  }

  Mono<ReleaseWorkerResult> start(ReleaseWorkerRequest request);

  default Flux<ReleaseWorkerExecutionEvent> startWithEvents(ReleaseWorkerRequest request) {
    return start(request).map(ReleaseWorkerExecutionEvent::result).flux();
  }

  Mono<ReleaseWorkerResult> stop(ReleaseWorkerRequest request);

  default Flux<ReleaseWorkerExecutionEvent> stopWithEvents(ReleaseWorkerRequest request) {
    return stop(request).map(ReleaseWorkerExecutionEvent::result).flux();
  }

  Mono<ReleaseWorkerResult> rollback(ReleaseWorkerRequest request);

  default Flux<ReleaseWorkerExecutionEvent> rollbackWithEvents(ReleaseWorkerRequest request) {
    return rollback(request).map(ReleaseWorkerExecutionEvent::result).flux();
  }

  Mono<ReleaseWorkerResult> healthcheck(ReleaseWorkerRequest request);

  default Flux<ReleaseWorkerExecutionEvent> healthcheckWithEvents(ReleaseWorkerRequest request) {
    return healthcheck(request).map(ReleaseWorkerExecutionEvent::result).flux();
  }

  Mono<ReleaseWorkerResult> collectLogs(ReleaseWorkerRequest request);

  default Flux<ReleaseWorkerExecutionEvent> collectLogsWithEvents(ReleaseWorkerRequest request) {
    return collectLogs(request).map(ReleaseWorkerExecutionEvent::result).flux();
  }
}
