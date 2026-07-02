# AgentScope 2.0 运行时激进化落地设计

## 设计结论

M04 将把 AgentScope Java 2.0 从“ReAct 调用 SDK”升级为“可观测、可恢复、可扩展的 Agent 运行时内核”。第一阶段先落地 `streamEvents()` 事件流适配、运行时事件脱敏和结构化最终输出准备；第二阶段再引入 Harness / session state；第三阶段引入子 Agent 并行诊断、异步 Tool 唤醒和模型编队。

该设计不改变项目产品边界：系统仍为公司内部自研自用、单组织部署；P1 仍只允许只读诊断；AgentScope 不能授予权限、不能直接执行目标系统操作、不能绕过 M02/M03/M05/M07/M10。

## 目标

1. 让 M09 能看到 AgentScope 2.0 的实时运行进度，而不是只等最终摘要。
2. 让 M04 具备接入 Harness、子 Agent、模型 fallback 和结构化输出的清晰扩展点。
3. 所有 AgentScope 事件在进入平台事件流前必须被脱敏、分类和降噪。
4. 模型内部推理、ThinkingBlock、未过滤工具参数增量和第三方原始响应不得进入语义事件、审计、日志或前端状态。

## 非目标

1. 不在 P1 开放生产写执行。
2. 不让 AgentScope 直接连接数据库、文件系统、浏览器、MCP Server 或目标系统。
3. 不用 AgentScope memory、workspace、session 或 Redis store 取代 M05 workflow 事实源。
4. 不引入多租户、计费、外部客户接入或模型市场能力。

## 分阶段方案

### 阶段 A：事件流与脱敏适配

新增 M04 内部适配层，将 AgentScope 2.0 `AgentEvent` 转为平台内部 `AgentRuntimeProgressEvent`。

映射原则：

1. `THINKING_BLOCK_*` 全部丢弃。
2. `TOOL_CALL_DELTA` 不保留参数增量正文，只保留工具名、调用 ID 和阶段。
3. `TEXT_BLOCK_DELTA` 第一阶段只表示“摘要文本增量可用”，不直接落入 M09 语义事件正文。
4. `MODEL_CALL_END` 可以保留 token 数和耗时。
5. `REQUIRE_EXTERNAL_EXECUTION`、`REQUIRE_USER_CONFIRM` 必须标记为平台拒绝或待平台接管，不能被视为授权。

### 阶段 B：`streamEvents()` 接入主运行时

在 `AgentscopeReActAgentClient` 中增加可选事件 sink。主运行时仍返回 `AgentscopeAgentResponse`，但运行过程中将已脱敏的 `AgentRuntimeProgressEvent` 交给 M05。M05 再决定是否持久化为新增语义事件契约。

阶段 B 不改变 `/api/v1/agent/diagnostics` 的响应契约，避免前后端一次性大改。

### 阶段 C：强类型最终诊断输出

定义最终诊断 JSON 契约，字段包括：

1. `symptoms`
2. `evidence`
3. `likelyCauses`
4. `recommendedReadOnlyChecks`
5. `riskFlags`
6. `confidence`

AgentScope 2.0 结构化输出只负责生成候选结构；服务端必须继续做 schema 校验、长度限制和敏感信息过滤。

### 阶段 D：Harness 与会话状态

用 Harness / session state 管理 AgentScope 运行时上下文，并把 `workflowId` 绑定为 session 标识。M05 workflow 仍是唯一事实源；AgentScope state 只是可丢弃运行时上下文。

恢复策略：

1. M05 已有终态结果时直接复用，不重新跑 Agent。
2. M05 有已完成 Tool Step 时，把结构化结果作为上下文喂回 AgentScope。
3. AgentScope state 不完整时可以从 M05 重建，不允许反向覆盖 M05。

### 阶段 E：子 Agent、异步 Tool 和模型编队

引入 supervisor agent 和只读诊断子 Agent：

1. `log-diagnostician`
2. `sql-readonly-analyst`
3. `certificate-auditor`
4. `dependency-health-analyst`
5. `incident-summarizer`

子 Agent 可以并行推理，但任何 Tool Call 必须继续走 M05 的平台守护执行器和 M07 Worker。异步 Tool 完成后由 M05 唤醒 AgentScope，而不是让 AgentScope 直接轮询目标系统。

模型编队由 M04 动态模型供应方配置扩展而来。主模型、轻量路由模型和 fallback 模型都必须来自受保护模型供应方表，API Key 继续加密保存并由 M02 授权管理。

## 安全约束

1. AgentScope 事件不是审计事实源，必须经过平台适配层。
2. ThinkingBlock 永不输出。
3. Tool 输入、Tool 输出和模型文本都是不可信数据。
4. M04 只能提出工具意图，M05/M02/M07 才能完成执行链路。
5. AgentScope permission、confirm、external execution 只能提高约束，不能降低平台安全基线。
6. Redis、AgentScope memory 和 session state 不能成为执行事实源。

## 第一阶段验收标准

1. M04 有独立单元测试覆盖 AgentScope 2.0 事件到内部进度事件的映射。
2. ThinkingBlock 事件被丢弃，测试必须证明原始推理文本不会出现在映射结果中。
3. Tool 参数增量不进入映射结果。
4. 模型 token 使用量可以被安全保留。
5. 现有 AgentRuntime 单元测试继续通过。
