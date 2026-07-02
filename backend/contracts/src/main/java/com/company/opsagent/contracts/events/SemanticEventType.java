package com.company.opsagent.contracts.events;

/**
 * P1 只读工作流对操作台公开的语义事件类型。
 */
public enum SemanticEventType {
  WORKFLOW_STARTED,
  SKILL_ROUTED,
  WORKER_ACCEPTED,
  AGENT_TOOL_CALL_REQUESTED,
  AGENT_TOOL_CALL_COMPLETED,
  AGENT_TOOL_CALL_REJECTED,
  AGENT_RUNTIME_PROGRESS,
  WORKFLOW_COMPLETED,
  WORKFLOW_FAILED
}
