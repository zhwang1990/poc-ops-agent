package com.company.opsagent.executionworker.release;

import reactor.core.publisher.Mono;

public interface ReleaseAdapter {

  String managementMode();

  Mono<ReleaseWorkerResult> precheck(ReleaseWorkerRequest request);

  Mono<ReleaseWorkerResult> deploy(ReleaseWorkerRequest request);

  Mono<ReleaseWorkerResult> start(ReleaseWorkerRequest request);

  Mono<ReleaseWorkerResult> stop(ReleaseWorkerRequest request);

  Mono<ReleaseWorkerResult> rollback(ReleaseWorkerRequest request);

  Mono<ReleaseWorkerResult> healthcheck(ReleaseWorkerRequest request);

  Mono<ReleaseWorkerResult> collectLogs(ReleaseWorkerRequest request);
}
