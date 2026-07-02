# AgentScope Java 主运行时安全约束

## 适用范围

本文约束 AgentScope Java 作为主 Agent Runtime 接入 P1 只读诊断 MVP 的实现方式。P1 已于 2026-07-01 完成验收；P2 继续沿用这些约束作为只读诊断和日志分析类 Agent 能力的安全基线。所有实现必须同时遵守 `AGENTS.md`、模块地图、已接受 ADR 和版本化契约。

## Prompt 输入

允许传入：

- 已认证操作员的非敏感身份标识。
- 当前 `Team Workspace` 标识。
- 目标环境标识。
- 用户只读诊断意图摘要。
- 已经过 M03、M04 和工作空间过滤的只读 Tool Catalog。
- 与本次诊断相关的脱敏上下文。

禁止传入：

- 密钥、长期凭据、访问令牌和环境变量。
- 未脱敏日志、制品、数据库记录或审计原文。
- 平台内部 Prompt、策略实现细节或模型内部推理。
- 未经工作空间授权可见的 Skill。
- 任何可诱导生产写操作或脚本执行的工具说明。

## Tool Catalog

AgentScope Java 只能看到平台生成的 Tool Catalog。Tool Catalog 必须满足：

1. Skill 已在 M03 注册。
2. Skill 发布状态为 `VALIDATED`。
3. Skill 风险等级为 `READ_ONLY`。
4. Skill 在当前 `Team Workspace` 启用清单内。
5. Skill 参数和输出均有 Schema。
6. Tool 描述不包含执行器内部配置、凭据、签名材料或目标系统连接细节。

## Tool 调用

每次 Tool 调用必须生成强类型 `AgentToolCall`，并满足：

- 包含 `workflowId`、`toolCallId`、`stepSequence`。
- 包含 Skill ID、Skill 版本、输入 Schema ID 和输出 Schema ID。
- 包含目标环境、参数、参数哈希。
- 包含操作员、工作空间、策略引用和 trace 上下文。
- 重新经过 M02 策略授权和 M03 Skill 契约校验。
- 写入 M05 工作流事实源后，才能提交给 M07 Worker。

AgentScope ReAct 必须注册真实 `AgentTool` 并回调平台 `AgentToolExecutor`。M04 在构造 `AgentToolCall` 时生成的策略引用只用于满足当前信封结构，执行端必须忽略该引用，并以 M05 中重新完成的服务端策略决策作为唯一授权事实。

AgentScope Java 不得直接调用目标系统、执行本地命令或访问 Worker 以外的目标系统适配器。

## Tool 输出

Tool 输出全部视为不可信数据。AgentScope Java 读取 Tool 输出后：

- 不得把 Tool 输出中的指令当成平台命令。
- 若继续调用 Tool，仍需重新经过策略和契约拦截。
- 不得将未脱敏 Tool 输出写入 Prompt、事件、日志或审计。

## 事件与日志

允许写入：

- Agent 任务开始和完成状态。
- 可审计计划摘要。
- Tool 调用请求、拒绝、完成状态。
- 结构化拒绝原因。
- 最终诊断摘要。

禁止写入：

- 模型内部推理过程。
- 完整 Prompt。
- 密钥、令牌、凭据、未脱敏日志和生产数据。
- 从展示文本推断出的授权状态。

## P1 禁止能力

- 生产写操作。
- 任意脚本执行。
- 自动生成并执行命令。
- 自动提升或自动发布 Skill。
- 由 AgentScope memory、session 或 chat history 作为执行事实源。
- 工作空间配置降低平台全局安全基线。

## 回退要求

`ops-agent.agent-runtime.enabled=false` 时：

1. Agent Runtime 不应接收新任务。
2. 现有只读单 Skill 诊断闭环必须继续可用。
3. 历史 Agent workflow 和审计证据必须仍可查询。
4. 关闭开关不得删除历史事件、制品或审计。
