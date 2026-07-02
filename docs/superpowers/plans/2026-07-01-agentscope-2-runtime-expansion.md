# AgentScope 2.0 运行时扩展实施计划

> 给 Agent 执行者：实施本计划时按任务逐项执行，并使用复选框跟踪状态。执行代码任务前必须使用测试先行方式，先看到失败，再补实现。

**目标：** 先落地 AgentScope 2.0 扩展的安全切片：把 AgentScope 2.0 `AgentEvent` 事件流接入 M04 内部运行时进度端口，为后续 M05/M09 语义事件流做准备。

**架构：** M04 继续收敛所有 AgentScope SDK 直接依赖。新增适配器把 AgentScope 2.0 `AgentEvent` 转成平台内部 `AgentRuntimeProgressEvent`，并在进入任何持久化或前端渲染前丢弃 ThinkingBlock、工具参数增量和工具结果正文增量。

**技术栈：** Java 21、Maven、JUnit 5、Reactor、AgentScope Java `2.0.0-RC4`。

---

## 文件结构

- 新增：`backend/control-plane/modules/agentruntime/src/main/java/com/company/opsagent/controlplane/modules/agentruntime/AgentRuntimeProgressKind.java`
  - 定义 M04 内部稳定进度分类。
- 新增：`backend/control-plane/modules/agentruntime/src/main/java/com/company/opsagent/controlplane/modules/agentruntime/AgentRuntimeProgressEvent.java`
  - 保存固定字段的已脱敏运行时事件摘要。
- 新增：`backend/control-plane/modules/agentruntime/src/main/java/com/company/opsagent/controlplane/modules/agentruntime/AgentscopeRuntimeEventMapper.java`
  - 映射 AgentScope 2.0 SDK 事件，并丢弃不安全事件。
- 新增：`backend/control-plane/modules/agentruntime/src/main/java/com/company/opsagent/controlplane/modules/agentruntime/AgentRuntimeProgressSink.java`
  - 定义 M04 内部运行时进度出口。
- 修改：`backend/control-plane/modules/agentruntime/src/main/java/com/company/opsagent/controlplane/modules/agentruntime/AgentscopeReActAgentClient.java`
  - 在显式配置进度 Sink 时使用 AgentScope `streamEvents(...)`，经 mapper 脱敏后传给 Sink。
- 新增：`backend/control-plane/modules/agentruntime/src/test/java/com/company/opsagent/controlplane/modules/agentruntime/AgentscopeRuntimeEventMapperTest.java`
  - 证明适配器保留安全元数据，并过滤推理内容与工具增量。
- 修改：`backend/control-plane/modules/agentruntime/src/test/java/com/company/opsagent/controlplane/modules/agentruntime/AgentscopeReActAgentClientTest.java`
  - 覆盖显式配置 Sink 后的事件流路径和平台工具回调路径。
- 修改：`backend/control-plane/modules/agentruntime/src/main/java/com/company/opsagent/controlplane/modules/agentruntime/AgentscopeReActAgentClientFactory.java`
  - 将模型供应方超时显式传入 AgentScope 2.0 OpenAI HTTP transport。
- 修改：`backend/control-plane/modules/agentruntime/src/test/java/com/company/opsagent/controlplane/modules/agentruntime/AgentscopeReActAgentClientFactoryTest.java`
  - 覆盖 HTTP transport 超时配置。

## 任务 1：运行时事件适配层

**状态：** 已完成。

**文件：**

- 新增：`backend/control-plane/modules/agentruntime/src/test/java/com/company/opsagent/controlplane/modules/agentruntime/AgentscopeRuntimeEventMapperTest.java`
- 新增：`backend/control-plane/modules/agentruntime/src/main/java/com/company/opsagent/controlplane/modules/agentruntime/AgentRuntimeProgressKind.java`
- 新增：`backend/control-plane/modules/agentruntime/src/main/java/com/company/opsagent/controlplane/modules/agentruntime/AgentRuntimeProgressEvent.java`
- 新增：`backend/control-plane/modules/agentruntime/src/main/java/com/company/opsagent/controlplane/modules/agentruntime/AgentscopeRuntimeEventMapper.java`

- [x] **步骤 1：写失败测试**

测试覆盖：

```java
void dropsThinkingEventsWithoutLeakingReasoningText()
void mapsToolCallDeltaWithoutArgumentText()
void mapsModelUsageFromModelCallEnd()
void mapsSubagentExposureForFutureSupervisorUi()
```

- [x] **步骤 2：运行测试确认失败**

运行：

```powershell
.\mvnw.cmd -pl control-plane/modules/agentruntime -am "-Dtest=AgentscopeRuntimeEventMapperTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

实际结果：编译失败，缺少 `AgentscopeRuntimeEventMapper`、`AgentRuntimeProgressEvent` 和 `AgentRuntimeProgressKind`，符合预期。

- [x] **步骤 3：实现进度分类枚举**

已新增 `AgentRuntimeProgressKind`，包含：

```java
AGENT_STARTED,
AGENT_ENDED,
AGENT_RESULT_READY,
MODEL_CALL_STARTED,
MODEL_CALL_COMPLETED,
TEXT_DELTA_AVAILABLE,
TOOL_CALL_STARTED,
TOOL_CALL_DELTA_AVAILABLE,
TOOL_CALL_COMPLETED,
TOOL_RESULT_STARTED,
TOOL_RESULT_DELTA_AVAILABLE,
TOOL_RESULT_COMPLETED,
SUBAGENT_EXPOSED,
ITERATION_LIMIT_EXCEEDED,
USER_CONFIRMATION_REQUIRED,
EXTERNAL_EXECUTION_REQUIRED,
CUSTOM,
UNKNOWN
```

- [x] **步骤 4：实现已脱敏事件记录**

已新增 `AgentRuntimeProgressEvent`，固定字段包括：

```java
String sourceEventType
AgentRuntimeProgressKind kind
String message
String replyId
String blockId
String toolCallId
String toolName
String agentId
String sessionId
String subagentId
int inputTokens
int outputTokens
int totalTokens
double modelTimeSeconds
boolean sensitiveContentSuppressed
```

实现约束：必填文本非空，枚举非空，token 和耗时不能为负数，空白可选文本归一化为 `null`。

- [x] **步骤 5：实现 mapper**

已新增 `AgentscopeRuntimeEventMapper`：

```java
Optional<AgentRuntimeProgressEvent> map(AgentEvent event)
```

规则：

- `THINKING_BLOCK_*` 返回 `Optional.empty()`。
- `ToolCallDeltaEvent.getDelta()` 不进入结果。
- `ToolResultTextDeltaEvent.getDelta()` 不进入结果。
- 工具事件保留 tool call ID 和工具名。
- `ModelCallEndEvent` 保留 token 数和模型耗时。
- `SubagentExposedEvent` 保留 subagent ID、agent ID、session ID 和 label。

- [x] **步骤 6：运行聚焦测试**

运行：

```powershell
.\mvnw.cmd -pl control-plane/modules/agentruntime -am "-Dtest=AgentscopeRuntimeEventMapperTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

实际结果：`AgentscopeRuntimeEventMapperTest` 4 个测试全部通过。

## 任务 2：主运行时事件流接线

**状态：** 已完成。

**文件：**

- 新增：`backend/control-plane/modules/agentruntime/src/main/java/com/company/opsagent/controlplane/modules/agentruntime/AgentRuntimeProgressSink.java`
- 修改：`backend/control-plane/modules/agentruntime/src/main/java/com/company/opsagent/controlplane/modules/agentruntime/AgentscopeReActAgentClient.java`
- 修改：`backend/control-plane/modules/agentruntime/src/test/java/com/company/opsagent/controlplane/modules/agentruntime/AgentscopeReActAgentClientTest.java`

- [x] **步骤 1：写失败测试**

新增测试：

```java
void streamsSanitizedProgressEventsWhenSinkIsConfigured()
```

运行：

```powershell
.\mvnw.cmd -pl control-plane/modules/agentruntime -am "-Dtest=AgentscopeReActAgentClientTest#streamsSanitizedProgressEventsWhenSinkIsConfigured" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

实际结果：编译失败，缺少 `AgentRuntimeProgressSink`，符合预期。

- [x] **步骤 2：实现内部进度端口**

已新增 `AgentRuntimeProgressSink`：

```java
Mono<Void> emit(AgentRuntimeRequest runtimeRequest, AgentRuntimeProgressEvent event)
```

约束：该端口只接收已脱敏固定字段事件；M05/M09 公开语义事件契约必须后续单独版本化。

- [x] **步骤 3：接入 `streamEvents(...)`**

实现方式：

- 默认构造器继续使用 `AgentRuntimeProgressSink.noop()`，保留现有 `agent.call(...)` 路径。
- 显式配置非 no-op Sink 时，使用 `agent.streamEvents(userMessage)`。
- 每个 `AgentEvent` 先经过 `AgentscopeRuntimeEventMapper`。
- mapper 返回空时丢弃事件；返回事件时调用 Sink。
- `AgentResultEvent` 仅用于记录最终 `Msg`，最终响应仍使用既有 `AgentscopeAgentResponse`。
- 出错时沿用既有失败响应策略，返回已记录的工具结果。

- [x] **步骤 4：覆盖工具回调路径**

新增测试：

```java
void streamsProgressEventsAroundPlatformToolExecutionWhenSinkIsConfigured()
```

验证点：

- `streamEvents(...)` 路径不会绕过 `AgentToolExecutor`。
- 工具结果仍会进入 `AgentscopeAgentResponse.toolResults()`。
- Sink 可以收到 `TOOL_CALL_STARTED`、`TOOL_RESULT_COMPLETED` 和 `AGENT_RESULT_READY`。

- [x] **步骤 5：运行客户端聚焦测试**

运行：

```powershell
.\mvnw.cmd -pl control-plane/modules/agentruntime -am "-Dtest=AgentscopeReActAgentClientTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

实际结果：`AgentscopeReActAgentClientTest` 6 个测试全部通过。测试运行结束后有非阻塞 SLF4J no-provider 警告。

## 后续任务

- [x] **任务 3：M05/M09 语义事件契约版本化**
  - 已在 `backend/contracts/events/semantic-event-v1.schema.json` 增量新增 `AGENT_RUNTIME_PROGRESS`。
  - 已新增 `AgentRuntimeProgressPayload` 和平台稳定 `AgentRuntimeProgressPayloadKind`，不暴露 AgentScope SDK 原始事件名。
  - 已更新 M09 前端 Zod `semanticEventSchema`，严格拒绝未声明字段，例如 `sourceEventType`。
  - 已更新 M09 工作流事件流展示，`AGENT_RUNTIME_PROGRESS` 只显示平台稳定 `progressKind` 与脱敏 `message`。
  - 已新增 M05 `WorkflowAgentRuntimeProgressSink`，把 M04 内部 `AgentRuntimeProgressEvent` 转为公开语义事件并写入 workflow 事件事实源。
  - 已把 Sink 注入动态模型 provider 与 legacy AgentScope 客户端工厂。
- [ ] **任务 4：结构化最终诊断输出**
  - 使用 AgentScope 2.0 structured output。
  - 输出必须映射到平台诊断结论契约。
  - 原始模型 JSON 不得作为执行事实源。
- [ ] **任务 5：Harness/session 状态实验**
  - 只能作为可恢复会话的辅助快照。
  - M05 持久化工作流仍是执行事实源。
- [ ] **任务 6：子 Agent 与模型供应方矩阵**
  - 只允许只读诊断子 Agent。
  - 模型供应方选择必须经受控 provider registry，不能由 prompt 或客户端输入自由切换。

## 最终验证

- [x] **第二阶段全量模块测试**

运行：

```powershell
.\mvnw.cmd -pl control-plane/modules/agentruntime -am test
```

实际结果：反应堆构建成功；`contracts` 33 个测试、`identity` 22 个测试、`policy` 4 个测试、`skillregistry` 7 个测试、`agentruntime` 41 个测试全部通过，合计 107 个测试、失败 0 个。测试运行结束后有非阻塞 SLF4J no-provider 警告。

- [x] **第三阶段后端聚合测试**

运行：

```powershell
.\mvnw.cmd -pl control-plane/bootstrap -am test
```

实际结果：反应堆构建成功；`control-plane/bootstrap` 及其依赖模块全部通过，bootstrap 模块 83 个测试通过、失败 0 个。测试运行结束后有非阻塞 SLF4J no-provider、Mockito/ByteBuddy 动态 agent 警告。

- [x] **第三阶段前端契约与事件流测试**

运行：

```powershell
npm run test -- src/schemas/schemas.test.js src/features/workflow-events/WorkflowEventsPage.test.jsx
```

实际结果：2 个测试文件、32 个测试全部通过，失败 0 个。测试运行结束后有非阻塞 Vitest `--localstorage-file` 路径警告。
