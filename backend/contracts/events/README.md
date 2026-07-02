# 事件契约

本目录保存语义事件和审计事件 Schema。

每个语义事件必须包含：

- `eventId`
- `workflowId`
- `sequence`
- `timestamp`
- `type`
- 强类型 `payload`

禁止发布模型内部推理过程。

`semantic-event-v1.schema.json` 定义操作台消费的版本化语义事件及强类型载荷。

Agent Tool 事件当前包含：

- `AGENT_TOOL_CALL_REQUESTED`：模型提出只读 Tool 意图，只携带 Skill、目标环境、step 和参数哈希，不携带原始参数。
- `AGENT_TOOL_CALL_COMPLETED`：Tool 执行完成，只携带执行状态和输出契约标识。
- `AGENT_TOOL_CALL_REJECTED`：Tool 意图被平台边界拒绝，携带错误码、展示消息和策略决策引用。

Agent 运行时进度事件当前包含：

- `AGENT_RUNTIME_PROGRESS`：M04 将 AgentScope 2.0 运行时事件脱敏后交给 M05 发布的进度事件。该事件只暴露平台稳定的 `progressKind`，不暴露 AgentScope SDK 原始事件名、模型推理正文、工具参数增量或工具结果正文增量。
