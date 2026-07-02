package com.company.opsagent.controlplane.modules.agentruntime;

import reactor.core.publisher.Mono;

/**
 * M04 内部运行时进度出口。
 *
 * <p>该端口只接收已经由 AgentscopeRuntimeEventMapper 脱敏后的固定字段事件；公开语义事件契约需要由
 * M05/M09 后续任务单独版本化。
 */
@FunctionalInterface
public interface AgentRuntimeProgressSink {

  AgentRuntimeProgressSink NOOP = (runtimeRequest, event) -> Mono.empty();

  Mono<Void> emit(AgentRuntimeRequest runtimeRequest, AgentRuntimeProgressEvent event);

  static AgentRuntimeProgressSink noop() {
    return NOOP;
  }
}
