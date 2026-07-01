package com.company.opsagent.controlplane.modules.release;

import reactor.core.publisher.Mono;

@FunctionalInterface
public interface ReleaseWorkerGateway {

  Mono<ReleaseNodeExecutionResult> execute(ReleasePlan plan, ReleaseNodeStep node);
}
