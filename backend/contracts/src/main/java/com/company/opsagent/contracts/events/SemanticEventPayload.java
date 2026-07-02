package com.company.opsagent.contracts.events;

/**
 * 语义事件载荷标记接口，仅允许明确类型的载荷实现。
 */
public sealed interface SemanticEventPayload permits
    WorkflowStartedPayload,
    SkillRoutedPayload,
    WorkerAcceptedPayload,
    AgentToolCallRequestedPayload,
    AgentToolCallCompletedPayload,
    AgentToolCallRejectedPayload,
    AgentRuntimeProgressPayload,
    WorkflowCompletedPayload,
    WorkflowFailedPayload {

  /**
   * 返回与事件类型一致的载荷类型。
   */
  SemanticEventType payloadType();
}
