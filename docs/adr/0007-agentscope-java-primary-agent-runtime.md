# ADR 0007：AgentScope Java 作为 P1 只读诊断目标主链路

- 状态：Accepted
- 日期：2026-06-13
- 更新日期：2026-07-01
- 负责人：架构负责人
- 相关模块：M01、M02、M03、M04、M05、M07、M08、M09、M10、M11
- 相关任务：AgentScope Java 主运行时接入

## 背景

P1 只读诊断 MVP 已具备身份认证、服务端策略、Skill 注册、只读工作流、受限 Worker 和语义事件的基础闭环。早期方案将 AgentScope Java 作为 M04 主 Agent Runtime 的候选实现接入，用于验证意图理解、计划生成、只读 Tool 调用循环、多步诊断编排和最终诊断摘要。

现在项目需要收敛目标主链路：P1 只读诊断的产品主路径应由 AgentScope Java 主导 Agent 循环，而不是继续以确定性单 Skill 路由作为主要诊断体验。确定性单 Skill 只读入口保留为兼容、联调和回退路径。

该调整不改变产品边界：系统仍是公司内部自研自用、单组织部署；P1 仍只允许只读诊断；生产写执行、任意脚本执行和审批绕过仍禁止。

## 决策

将 AgentScope Java 定义为 P1 只读诊断目标主链路中的 M04 主 Agent Runtime。

P1 主链路为：

```text
操作员诊断请求
  -> M01 身份认证和可信身份上下文
  -> M02 服务端策略授权和审计预记录
  -> M05 创建持久化 Agent workflow
  -> M04 AgentScope Java 主运行时理解意图并生成可审计计划摘要
  -> M03 生成已发布、已签名、只读、工作空间可见的 Tool Catalog
  -> M04 AgentScope Java 选择下一次只读 Tool Call
  -> M05 持久化 Tool Step、幂等键、参数哈希、策略引用和事件序列
  -> M07 受限 Worker 执行已授权请求
  -> M08 目标系统只读适配器返回结构化结果
  -> M04 AgentScope Java 读取 Tool Result 并决定继续或结束
  -> M09 输出强类型语义事件和最终摘要
  -> M10 记录指标、日志、追踪和审计证据
```

## 当前实现状态

截至 2026-06-23，当前代码已完成 Agent Runtime 模块边界、禁用/未配置状态、受保护入口、Agent workflow 基础事实源、最终摘要 POC、workflow-backed Agent Tool 执行器，以及 AgentScope ReAct 真实 `AgentTool` 回调接线。平台守护执行器已在服务端重新校验 Tool Catalog、重做 M02 策略决策、记录执行器级授权审计、重算参数哈希、写入 M05 Tool Step、发布 Agent Tool 语义事件，并通过 M07 WorkerGateway 提交已授权只读命令。

AgentScope ReAct 现在注册真实 `AgentTool`，模型 ToolUse 会先在 M04 转成强类型 `AgentToolCall`，再交给 M05 的平台守护执行器。M04 生成的 policy 引用只用于满足当前信封契约，M05 会忽略该引用并以服务端重新授权结果为准。Agent Tool 请求、完成和拒绝三类语义事件契约骨架、M05 发布接线、执行器级审计和多 Tool 幂等恢复演练已经补齐；终态 Agent workflow 会复用持久化的 `AgentTaskResult` 状态、摘要和 toolCallCount，避免幂等重试时把 Runtime 失败误报成通用终态失败。评测集和路由解释 API 仍需后续补齐。确定性单 Skill 只读入口继续作为联调、兼容和紧急回退路径。

截至 2026-06-28，M04 已新增动态模型供应方设置：管理员可通过 M09 操作台维护 OpenAI-compatible `baseUrl`、模型名、运行限制和 API Key，并切换当前默认供应方。API Key 只允许在受保护请求中直接提交一次，控制面使用 `OPS_AGENT_MODEL_SECRET_MASTER_KEY` 派生的本地密钥进行 AES-GCM 加密后持久化；摘要 API 只返回指纹和配置版本，不返回明文或密文。测试配置通过 OpenAI-compatible `/chat/completions` 最小请求执行受控连通性探测，本地占位 Key 不出网，失败时只返回稳定摘要，不回显供应方响应体或 Key。Agent Runtime 每次运行前读取当前默认供应方并解密调用所需 Key，未设置默认供应方时才回退到旧的环境变量配置。

截至 2026-07-01，M04 直接依赖的 AgentScope Java 已升级到 `io.agentscope:agentscope:2.0.0-RC4`。Maven Central 当前 2.0 线尚未发布最终版 `2.0.0`，因此 P1 先固定到该 2.0 release candidate，并继续把依赖限制在 `control-plane-agentruntime` 模块内。

AgentScope Java 负责：

1. 理解操作员的只读诊断意图。
2. 基于平台提供的 Tool Catalog 生成可审计计划摘要。
3. 在一次 Agent workflow 中选择一个或多个只读 Tool。
4. 读取 Tool Result 并决定是否继续诊断。
5. 输出最终诊断摘要和结构化结果。
6. 解析当前默认模型供应方配置，但不得把模型配置选择视为授权决策。

平台继续负责：

1. M01 身份认证和可信身份上下文。
2. M02 策略授权、拒绝和审计。
3. M03 Skill 契约、目录式 Skill 包、版本、签名、发布状态和工作空间可见性。
4. M05 workflow 事实源、Tool Step、幂等、状态恢复和事件序列。
5. M07 Worker 隔离执行和 M08 目标系统适配器。
6. M09 强类型语义事件展示。
7. M10 结构化日志、指标、追踪和审计留存。
8. M02 对模型供应方读取、写入、Key 轮换、测试和默认切换进行 RBAC 授权与请求级审计。

## AgentScope Skill 与平台契约分离

已有内置 Skill 分为两个目录层次，分别服务不同消费者。

`backend/skills` 面向 AgentScope Java 文件系统 Skill Repository。每个 Skill 目录必须以 `SKILL.md` 作为入口：

```text
backend/skills/<skill-slug>/
|-- SKILL.md
|-- references/   # 可选
|-- examples/     # 可选
`-- scripts/      # 可选，P1 不使用
```

`SKILL.md` 必须包含 AgentScope 可解析的 YAML frontmatter，至少提供 `name` 和 `description`。正文必须用自然语言说明：

1. 什么时候使用该 Skill。
2. 需要哪些输入。
3. 应调用哪个平台 Tool。
4. 如何解释平台 Tool 输出。
5. 必须遵守哪些只读、安全和审计边界。

平台治理 JSON 不放在 `backend/skills`。M03 注册中心、发布签名、输入输出 Schema 和 M11 测试样例统一放在：

```text
backend/contracts/skills/packages/<skill-slug>/
|-- manifest.json
|-- manifest.signature.json
|-- input.schema.json
|-- output.schema.json
`-- tests/
    |-- happy-path.json
    |-- invalid-parameters.json
    `-- policy-denied.json
```

平台契约目录的职责如下：

- `manifest.json`：作为 M03 注册入口，声明责任人、版本、分类、风险、执行器、权限、超时、参数和治理拦截器。
- `manifest.signature.json`：保存 manifest 摘要和发布签名。
- `input.schema.json`：定义 AgentScope Tool Call 和平台命令边界可接受的输入。
- `output.schema.json`：定义 Worker / 适配器返回给 AgentScope 和 M09 的结构化输出。
- `tests/*.json`：保存正常路径、参数拒绝和授权拒绝样例，供 M11 后续接入契约测试与评测。

首批按 AgentScope Skill + 平台契约分离方式重新定义的已有 Skill：

1. `node-health`
2. `application-log-summary`
3. `certificate-expiry`
4. `platform-alert-summary`
5. `service-dependency-health`

## 强约束

- AgentScope Java 不能授予权限。
- AgentScope Java 不能直接访问目标系统。
- AgentScope Java 不能直接执行脚本或本地命令。
- AgentScope Java 不能绕过 M05 workflow 持久化。
- AgentScope Java 不能把 memory、session、plan 或 chat history 作为执行事实源。
- AgentScope Java Tool Catalog 只能来自 M03 已发布 Skill，并经过工作空间、风险等级和策略过滤。
- P1 阶段只允许 `READ_ONLY` Skill。
- 每一次 Tool Call 都必须形成强类型 `AgentToolCall` 或等价平台命令记录，并带有 Skill 版本、参数哈希、策略引用、工作空间、操作员和 trace 上下文。
- 模型内部推理过程不得写入日志、事件、审计或制品。
- 目录式 Skill 包中的 schema、样例和 README 不得包含密钥、生产数据或可执行脚本。
- 模型供应方 API Key 不得出现在响应、日志、审计原因、前端状态持久化、测试数据或文档样例中；仅允许保存加密后的密文、随机 nonce、算法标识和不可逆指纹。
- 模型供应方测试配置不得暴露第三方响应体、异常细节或 API Key；本地占位 Key 必须在服务端短路，不得向外部供应方发起请求。
- 默认模型切换只能改变 M04 运行时选择，不能绕过 M01/M02/M03/M05/M07 的身份、策略、工作流、Skill 和 Worker 边界。

## 考虑过的备选方案

### 继续使用确定性单 Skill 路由作为主链路

优点是安全边界简单，且与早期 P1 实现一致。缺点是无法满足多步诊断、跨 Skill 归纳和基于 Tool Result 继续推理的目标，因此不再作为产品主链路。

### 将 AgentScope Java 作为辅助路由建议器

该方案可以降低初始风险，但会让平台仍以确定性路由为中心，Agent 运行时价值较小，不能满足“AgentScope 做主链路”的目标。

### 让 AgentScope Java 直接执行工具

拒绝采用。直接执行会绕过 M02、M03、M05 和 M07，违反本项目不可妥协的安全规则。

## 影响

正面影响：

- P1 诊断能力从单 Skill 调用扩展为受控多步 Agent 诊断。
- Agent Runtime 与平台安全边界分离，后续可替换模型或运行时。
- 语义事件可以展示计划、工具调用、拒绝和最终摘要。
- Skill 包从注册清单升级为可评审、可测试、可追溯的目录式资产。

负面影响：

- M05 必须把 Agent workflow 和 Tool Step 作为 P1 主路径维护。
- M11 必须把目录式 Skill 包、Tool Catalog、模型行为、安全拒绝和恢复评测纳入门禁。
- 当前接入版本为 AgentScope Java `2.0.0-RC4`，后续升级到 2.0 正式版仍需版本稳定性、许可证和传递依赖审查。
- 确定性单 Skill 入口从主路径降级为兼容和回退路径后，操作台与运行手册需要明确入口差异。

## 验证方式

- 契约测试覆盖 Agent Task、Agent Tool Call、Agent Tool Result 和新增语义事件。
- 单元测试覆盖只读 Tool Catalog、未发布 Skill 拒绝、非只读 Skill 拒绝、跨工作空间拒绝。
- Skill 检查覆盖两类目录：AgentScope 目录必须包含可解析的 `SKILL.md`；平台契约目录必须包含 `manifest.json`、`manifest.signature.json`、`input.schema.json`、`output.schema.json` 和三类测试样例。
- 工作流测试覆盖 Agent workflow 幂等、Tool Step 顺序、Agent Runtime 失败和恢复事件。
- Agent Runtime 测试覆盖 ReAct ToolUse 通过真实 AgentScope `AgentTool` 回调平台 `AgentToolExecutor`，并将结构化 Tool Result 回送给下一轮 ReAct。
- 模型供应方测试覆盖 URL 校验、API Key AES-GCM 加密、R2DBC 持久化、受保护管理 API、受控 OpenAI-compatible 连通性探测、占位 Key 不出网、错误响应不回显敏感信息、RBAC 拒绝和运行时默认供应方动态解析。
- 集成测试覆盖 `/api/v1/agent/diagnostics` 的认证、授权和受控只读诊断路径。
- 评测覆盖 Prompt 注入、Tool 输出注入、写操作请求、模型超时和输出格式错误。

## 发布与回滚

AgentScope Java 是 P1 只读诊断的目标产品主链路，但环境启用仍必须受配置控制。未配置模型提供方、API Key 或评测环境时，控制面必须明确返回不可用状态，不得静默改走未审计路径。

确定性单 Skill 只读入口保留为兼容、联调和紧急回退路径。若 AgentScope 主链路出现异常，可以通过配置关闭 Agent Runtime，并临时回到现有 `/internal/diagnostics/read-only` 单 Skill 只读闭环。历史 Agent workflow、Tool Step、语义事件和审计记录仍需可查询，不得删除或篡改。
