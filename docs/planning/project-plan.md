# 项目计划

## 计划基线

- 启动日期：2026-06-08
- 计划发布 V1.0：2026-11-20
- 总周期：24 周
- P1 里程碑：2026-07-01 已完成只读诊断 MVP 验收
- 当前优先级：P2 受控变更试点
- 团队假设：8-10 名核心成员，领域评审人员兼职参与
- 产品边界：公司内部自研自用、单组织部署，不建设租户或多租户能力

## 当前执行状态

| 任务 | 状态 | 进度 | 已完成 | 剩余条件 |
|---|---|---:|---|---|
| T005 建立仓库、分支、编码规范与 CI 骨架 | P1 验收完成 | 100% | 已完成首个本地 Git 基线提交、后端标准 Maven 多模块骨架、Wrapper、仓库规范检查、契约检查、密钥扫描、制品收集、CI 工作流和远程仓库初始化手册，并已本地验证通过；P1 里程碑已接受该工程基线 | 无 P1 阻塞；远程 GitHub 仓库、真实评审团队、默认分支保护和远程 PR CI 作为 P2/P3 协作治理继续跟进 |
| T006 建立测试与评测基线 | 已完成 | 100% | 已新增 [T006 测试与评测基线](../standards/testing-and-evaluation-baseline.md)，明确 P1 的测试分层、最低门禁、评测集、CI 执行方式和验收产物 | 无 |
| T007 控制面 Spring Boot / WebFlux 服务骨架 | 已完成 | 100% | 已在 `backend/control-plane/bootstrap` 落地 Spring Boot WebFlux 骨架，提供健康检查、模块清单、OpenAPI 文档、配置文件、统一异常返回，并通过自动化测试和 `backend` 全量 `verify` | 无 |
| T008 实现 OIDC / SSO 接入与 JWT 校验 | P1 验收完成 | 100% | 已支持开发态 `HS256`、真实 OIDC 配置模式和标准 OIDC 浏览器登录模式，补齐 `issuer-uri`、`jwk-set-uri`、`username-claim`、`role-claim`、登录入口、回调地址、退出流程和会话查询接口；同时补充本地模拟 IdP、OIDC 单元测试、集成测试与浏览器登录测试，覆盖发现、签名校验、Claim 映射和会话读取主流程；P1 已接受其作为可信身份基础 | 无 P1 阻塞；真实企业身份提供方联调、环境专用参数校验与联调记录转入 P2/P3 环境落地 |
| T009 实现服务端策略决策接口与基础 RBAC | P1 验收完成 | 100% | 已抽象 `PolicyDecisionService` 接口，并将动作到角色的 RBAC 规则外置到 `ops-agent.policy.required-roles-by-action`，完成结构化 `403` 拒绝和自动化验证；当前已覆盖开发态 JWT、OIDC、本地模拟 IdP、浏览器会话登录与角色不足场景下的服务端策略回归；P1 已接受其作为服务端授权基线 | 无 P1 阻塞；独立策略源或策略引擎、动作目录扩展和更完整策略回归集转入 P2/P3 |
| T010 实现执行上下文与不可篡改审计事件 | P1 验收完成 | 100% | 已实现请求级执行上下文、追加式文件审计持久化、最新审计查询入口，并验证主体、动作、资源、策略版本、TraceId、RequestId 和结果可追溯；现有自动化测试已覆盖 Bearer Token、浏览器会话鉴权成功、拒绝与审计读取链路；已补充 P1 文件审计保留、归档、恢复和访问控制运行手册；P1 已接受文件审计作为当前审计事实源 | 无 P1 阻塞；正式集中审计存储、组织级备份系统和真实环境恢复演练转入 P2/P3 |
| T011 设计并实现生产可用的内建身份提供方与登录模块 | 已完成 | 100% | 已完成身份契约、`M01` 正式账号/密码/会话领域、R2DBC 仓储、浏览器 built-in 登录/改密/重置/登出闭环、真实数据库集成测试、运行模式隔离与中文运行手册；`local-oidc` 继续仅保留为本地联调能力 | 后续仅在 P2/P3 再扩展完整 MFA、自助找回密码与内建 OIDC 对外发行 |

## 模块进度

| 模块 | 状态 | 进度 | 已完成 | 剩余条件 |
|---|---|---:|---|---|
| M03 Skill 契约与注册中心 | P1 验收完成 | 100% | 已落地 Skill Manifest、发布签名、注册和显式校验，并将 P1 只读 Skill 补足到 6 个；平台 JSON 已迁入 `backend/contracts/skills/packages`，与 AgentScope `SKILL.md` 目录分离；P1 已接受该注册与发布校验闭环 | 无 P1 阻塞；发布流水编排、生产签名方案、契约包自动校验和更多 Worker 适配器转入 P2/P3 |
| M04 AgentScope 主运行链路 | P1 验收完成 | 100% | 已完成确定性候选筛选、发布态约束、Agent Runtime 模块边界、启用开关、未配置失败关闭、最终摘要 POC，并已将 AgentScope Java 决策为 P1 只读诊断目标主链路；M04 `AgentToolExecutor` 端口已携带 Runtime 身份、角色和 trace 上下文，AgentScope ReAct 已通过真实 `AgentTool` 回调平台执行器；Agent Tool 请求、完成和拒绝语义事件契约骨架、M05 事件发布接线、执行器级授权审计和多 Tool 幂等恢复演练已补齐；已新增动态模型供应方注册、API Key 加密持久化、默认供应方切换、受控连通性探测和运行时动态解析；P1 已接受该主链路作为只读诊断闭环 | 无 P1 阻塞；路由解释 API、生产密钥管理联调和更完整评测集转入 P2/P3 |
| M05 只读工作流切片 | 已完成 | 100% | 已生成强类型只读命令、短期 Worker 请求和顺序语义事件；同时已落地 H2/R2DBC 工作流实例、attempt 与事件持久化、幂等复用、结果与事件回读、启动恢复装配、版本化迁移脚本，以及针对 `FAILED_RETRYABLE` 和 attempt 已过期在途实例的单次受控重放；已新增 workflow-backed Agent Tool 执行器，服务端重算参数哈希、重做 M02 策略决策、写入 Tool Step、发布 Agent Tool requested/completed/rejected 语义事件、记录 Agent Tool 授权审计，并通过 WorkerGateway 提交只读命令；Agent workflow 终态幂等命中时不再重跑 Runtime，而是复用持久化的终态 `AgentTaskResult` 状态、摘要和 toolCallCount | 无；后续仅在 P2/P3 扩展正式生产数据库接入与更长期恢复演练 |
| M07 受限执行 Worker | P1 验收完成 | 100% | 已提供独立 WebFlux Worker、回环地址开发配置、显式允许列表和 `node-health-read` 适配器；已新增控制面到 Worker 的 HMAC 传输认证、Worker 入站验签、非回环绑定启动保护、ADR 和运行手册；已补充 SQL 出口 allowlist、默认拒绝配置、连接目录校验、Worker 拒绝映射，并将 SQL 工作台 Worker 侧适配抽取到 `execution-worker-sqlworkbench` 运行时模块；已新增配置型 HTTP/JSON 只读适配器、HTTP 出口 allowlist、响应字段白名单和 `weather-current-read` 配置化执行路径，并通过 M05 Agent Tool 执行器生成已授权只读命令信封；P1 已接受该受限执行边界 | 无 P1 阻塞；mTLS、网络层出口策略、短期目标系统凭据、Windows 隔离部署方案和生产演练转入 P2/P3 |
| M09 语义事件与只读操作台 | P1 验收完成 | 100% | 已定义强类型语义事件、SSE 接口、React/JSX/JSDoc `checkJs` 只读操作台、API/Zod 边界，并补齐 Agent Tool 请求、完成、拒绝三类事件契约和 M05 发布接线；已完成登录页、Agent 工作区、Skill 注册中心、SQL 工作台和模型设置等 P1 相关页面的真实接口优先接入与禁用态边界；P1 已接受其作为只读诊断操作台 | 无 P1 阻塞；P2 继续扩展审批、发布中心、SQL 写执行确认、断点恢复细化和更完整浏览器验收 |
| M09 SQL 工作台 P1 切片 | P1 验收完成 | 100% | 已完成 AS/400 开发测试连接目录、SQL AST 校验、DML 静态预检、Worker 双重拒绝边界、SQL 工作台界面、`execution-worker-sqlworkbench` Worker 侧 SQL 出口 allowlist、凭据别名与 KeyStore 解锁路径，以及开发/测试环境受控单条 `SELECT` 执行边界；P1 已接受该只读查询切片 | 无 P1 阻塞；更多平台适配、结果分页脱敏、短期留存增强、多会话体验和展开工作区优化转入 P2 |
| M09 SQL 工作台 P2 受控 CRUD | 启动中 | 5% | 已接受 ADR 0009，确认语义执行轨道、Notebook 式独立结果、开发环境受控 CRUD、DML 影响预览、短事务、服务端策略、持久化工作流、Worker 二次校验和审计边界 | 完成会话与单元契约、DML 影响预览、环境风险策略、受限写 Worker、短事务执行、审计事件、安全评审和 E2E 验收 |
| M09 发布中心 P2 受控变更 | 启动中 | 5% | 已接受 ADR 0010，确认“发布中心”菜单、`dev` / `sit` / `uat` 非生产范围、WAR 制品发布、Liberty 脚本 Profile 发布、单应用单环境多节点串行执行、Liberty 现有 HTTPS 服务接入、Tomcat 页面上传策略、`sit` / `uat` 二次确认、确定性成功失败判断和大模型只读日志分析边界 | 完成契约、配置持久化、凭据加密、工作流、Worker 适配器、语义事件、审计、前端页面、安全评审和 E2E 验收 |
| M01 接入网关与身份认证 | P1 验收完成 | 100% | 已完成开发态 JWT、真实 OIDC 配置模式、本地 Mock OIDC 联调，以及正式内建身份模式下的账号、密码、锁定、会话、管理员重置密码、首次改密、登出撤销与身份契约；P1 已接受该身份认证基础 | 无 P1 阻塞；内建 OIDC 对外发行、完整 MFA、自助找回密码与更完整的运维开户工具转入 P2/P3 |

## 阶段划分

| 阶段 | 周期 | 目标 | 退出里程碑 |
|---|---:|---|---|
| P1 只读诊断 MVP | 6 周 | 建立可审计且无生产写入的诊断闭环 | 只读 MVP 验收 |
| P2 受控变更试点 | 8 周 | 建立审批、工作流、幂等、补偿和受限执行 | 低风险可回滚试点验收 |
| P3 生产平台化 | 10 周 | 完成高可用、强隔离、安全、灾备和运维移交 | 发布 V1.0 |

P1 只读诊断 MVP 已于 2026-07-01 完成验收。当前启动 P2 受控变更试点；P2 不开放生产写执行，不启用任意脚本执行，不允许绕过策略、审批、幂等、审计或隔离控制。

## P1 范围（已验收）

交付内容：

- 仓库、CI、ADR、威胁模型和测试评测基础
- 控制面骨架和关系型数据基础
- OIDC / SSO 和可信身份上下文
- 无外部 IdP 场景下的正式内建身份提供方与登录模块
- 服务端授权和审计链
- Skill Schema、强类型命令信封、加载器和只读路由
- 5-10 个只读诊断 Skill
- 强类型语义事件协议、SSE 和只读操作台
- 评测集、回归检查和 MVP 验收证据

不交付内容：

- 生产写操作
- 任意生成脚本执行
- 审批绕过机制
- 高风险执行
- 由人格配置降低安全要求
- 发布、启停、重启、回滚、制品上传部署等会改变目标系统状态的发布中心能力；该能力属于 P2 非生产受控变更切片

P1 SQL 工作台已按开发和测试环境受控单条 `SELECT` 执行、DML 仅预检的边界完成验收。开发环境受控增删改查属于 P2 受控变更试点，必须在不开放生产连接的前提下，通过策略、持久化工作流、短事务、审计和 Worker 二次校验完成。

P2 发布中心以非生产 `dev`、`sit`、`uat` 环境为范围，提供 WAR 制品发布、Liberty 脚本 Profile 发布、启停、重启、回滚和只读日志分析。初始执行模型为单应用、单环境、多节点串行；`sit` 和 `uat` 默认需要二次确认或更严格审批；Liberty 发布不要求操作台上传制品，但必须由控制面页面维护并审核启用的脚本 Profile 调用成熟 JAR / HTTPS 发布能力，并通过脚本参数 `artifactPath` 引用以 `\\` 开头的共享目录制品路径；Tomcat 初始通过操作台上传 WAR 制品并使用可扩展策略执行。成功或失败必须由确定性状态检查判定，大模型只能基于脱敏日志提供诊断建议。

## P2 启动范围

首批 P2 工作聚焦两个低风险、可回滚、非生产受控变更切片：

- SQL 工作台开发环境受控 CRUD：以 ADR 0009 为边界，只开放开发环境受控 `INSERT`、`UPDATE`、`DELETE`，测试环境按风险策略确认、审批或拒绝，生产连接始终不可见、不可配置、不可调用。
- 发布中心非生产受控变更：以 ADR 0010 为边界，只覆盖 `dev`、`sit`、`uat`，首版覆盖 WAR 制品发布和 Liberty 脚本 Profile 发布，单应用、单环境、多节点串行执行，成功或失败由确定性状态检查判定。
- 共性 P2 门禁：所有副作用动作必须具备版本化契约、服务端策略、审批或二次确认、持久化工作流、幂等键、审计事件、Worker 隔离、短期或加密凭据、失败恢复、人工接管和回滚方式。
- 长期记忆不进入首批 P2 启动范围；后续如建设，必须作为独立设计议题评审，且不得把 ChatMemory、AgentScope memory、session state、chat history、Redis 或浏览器缓存作为执行事实源。

## 前两个迭代

### 迭代 0：工程基础

- 确认项目章程和决策机制
- 冻结 MVP 范围和默认禁止清单
- 接受初始架构 ADR 和威胁模型
- 建立仓库、CI、质量门禁和测试策略
- 定义身份、策略、Skill、命令和事件的初始契约

### 迭代 1：只读垂直切片

- 通过开发身份提供方认证一名操作员
- 形成并落地无外部 IdP 场景下的正式内建身份规格、ADR、实现计划与运行手册
- 授权一个只读 Skill
- 将一个请求路由到该 Skill
- 通过受限的开发 Worker 路径执行
- 输出强类型状态和结果事件
- 持久化审计证据并追踪完整请求
- 添加评测和授权拒绝测试

## 启动阶段必须决策的事项

以下事项必须在实现锁定前编写 ADR：

- 控制面模块和构建结构
- 持久化工作流引擎
- 策略引擎方案
- 对象和制品存储
- 前端工具链
- 部署目标和环境策略
- 高风险执行的强隔离方案

## 里程碑验收证据

每个里程碑必须包含：

- 已确认的范围和演示
- 测试与评测报告
- 安全评审证据
- 已知风险、负责人和完成期限
- 与当前阶段相匹配的运维和回滚证据

## 2026-06-07 M09 进展补充

- M09 已补齐“当前工作流内自动恢复”的第一批实现：
  - 控制面新增只读恢复 SSE 接口；
  - 工作流存储支持按 `workflowId + afterSequence` 查询后续事件；
  - 操作台新增连接中、恢复中、完成、失败状态展示；
  - 操作台在终态前断流时会自动尝试恢复，并对重复事件去重。
- M09 当前仍未完成的项保持不变：
  - 会话登录联调；
  - 浏览器端到端验收沉淀；
  - 如未来需要，再评估执行中的增量事件推送。

## 2026-06-13 AgentScope Java 主运行时接入计划

- 新增 P1 目标主链路方向：将 AgentScope Java 作为 M04 主 Agent Runtime 接入，而不是辅助路由建议器。
- 接入目标：
  - 由 AgentScope Java 主导只读诊断意图理解、计划生成、多步 Tool 调用和最终诊断摘要；
  - 由平台继续强制执行 M01 身份、M02 授权、M03 Skill 契约、M05 工作流事实源、M07 Worker 隔离、M09 强类型事件和 M10 审计观测；
  - P1 阶段仅允许已发布、已授权、工作空间可见的 `READ_ONLY` Skill。
- 验收证据必须覆盖：
  - 单 Tool 只读诊断成功；
  - 多 Tool 只读诊断成功；
  - 写操作、Prompt 注入、跨 Workspace Skill、未发布 Skill 和 Tool 输出注入被拒绝；
  - 每个 Tool Call 都有工作流 step、参数哈希、策略引用、语义事件和审计 trace；
  - 关闭 `ops-agent.agent-runtime.enabled` 后，现有单 Skill 只读诊断闭环仍可运行。

## 2026-06-14 AgentScope Java 主运行时接入进展

- 已将 AgentScope Java `2.0.0-RC4` 接入为 M04 主运行时实现，并限制直接依赖只出现在 `control-plane-agentruntime` 模块。
- 已新增 `AgentscopeReActAgentClient`，通过 AgentScope `ReActAgent` 和 OpenAI-compatible `OpenAIChatModel` 运行主 Agent 循环，并只返回最终可审计摘要，不输出模型内部推理。
- 已新增 `/api/v1/agent/diagnostics` 受保护入口；入口经过统一认证、策略授权和审计过滤器。未配置模型供应方或未启用的环境必须失败关闭。
- 已新增 R2DBC Agent 工作流事实源，覆盖 workflow 幂等、Tool Step 顺序和完成状态。
- 已于 2026-06-23 补齐 workflow-backed Agent Tool 执行器切片：ToolCall 会在服务端重新校验目录、重做策略决策、记录执行器级授权审计、写入 M05 Tool Step、发布 Agent Tool requested/completed/rejected 语义事件，并通过 M07 WorkerGateway 提交已授权只读命令；同日已完成 AgentScope ReAct 真实 `AgentTool` 到该执行器的最小回调接线。
- 已补充评测清单和 POC 运行手册，记录启用、回退和依赖验证方式。

## 2026-06-23 AgentScope 主链路与目录式 Skill 包补充

- AgentScope Java 从“主运行时候选”调整为 P1 只读诊断目标主链路：
  - `/api/v1/agent/diagnostics` 作为 Agent 只读诊断主入口；
  - 确定性单 Skill 只读入口保留为联调、兼容和紧急回退路径；
  - AgentScope 负责意图理解、计划摘要、多步只读 Tool 调用和最终摘要；
  - 平台继续强制执行 M01 身份、M02 授权、M03 Skill 契约、M05 工作流事实源、M07 Worker 隔离、M09 强类型事件和 M10 审计观测。
- 当前实现状态：
  - 已完成 Agent Runtime 模块边界、禁用/未配置状态、受保护入口、Agent workflow 基础事实源、最终摘要 POC、workflow-backed Agent Tool 执行器和 AgentScope ReAct 真实工具回调接线；
  - 平台守护执行器已在服务端忽略 ToolCall 夹带的授权引用，重新完成目录校验、M02 策略决策、执行器级授权审计、参数哈希、M05 Tool Step 持久化、M07 WorkerGateway 调用和结果映射；
  - 已补齐 Agent Tool 请求、完成和拒绝三类语义事件契约骨架，并由 M05 平台守护执行器发布到持久化语义事件流；
  - 确定性单 Skill 只读工作流继续作为联调、兼容和紧急回退路径；AgentScope 主链路的评测集和路由解释 API 在 P2/P3 继续增强。
- 当前 6 个 P1 只读 Skill 按 AgentScope Skill 与平台契约分离方式维护：
  - `backend/skills/<skill>/SKILL.md` 作为 AgentScope 文件系统 Skill 入口，说明何时使用、输入、平台 Tool 调用方式、输出解读和安全边界；
  - `backend/contracts/skills/packages/<skill>/input.schema.json` 和 `output.schema.json` 作为 AgentScope Tool Catalog 与 Worker 结果边界；
  - `backend/contracts/skills/packages/<skill>/tests/happy-path.json`、`invalid-parameters.json` 和 `policy-denied.json` 作为 M11 后续契约测试与评测样例。
- 2026-06-24 新增 `weather-current-read` 当前天气查询 Skill，并通过 M07 配置型 HTTP/JSON 只读适配器接入 Worker；默认 `endpoint-url` 和 HTTP 出口 allowlist 为空，未配置受控天气源时会失败关闭。简单第三方 HTTP Skill 后续应优先新增平台契约与配置，不默认新增专用 Java 适配器。

## 2026-06-28 动态模型供应方设置补充

- M04 已新增模型供应方配置领域、AES-GCM API Key 加密、R2DBC 持久化、受保护管理 API 和运行时默认供应方动态解析。
- M09 已新增“模型设置”操作台页面，管理员可以动态新增 OpenAI-compatible 供应方、直接输入 API Key、保存运行限制、轮换 Key、触发受控连通性探测、禁用供应方和切换默认模型。
- M02 已新增模型供应方读取、写入、Key 轮换、测试和默认切换的 RBAC 动作映射；请求级审计不得记录 API Key 明文。
- 生产环境必须通过 `OPS_AGENT_MODEL_SECRET_MASTER_KEY` 或等价部署密钥源提供模型密钥加密主密钥；源码、样例配置、日志、Prompt、制品和测试数据不得包含真实模型密钥。
- M04 测试配置已通过 OpenAI-compatible `/chat/completions` 最小请求执行受控连通性探测；本地占位 Key 不出网，401/403 和异常只返回稳定摘要，不回显供应方响应体或 Key。
- 后续仍需补齐生产密钥轮换、备份恢复和集中审计联调。

## 2026-07-01 长期记忆规划记录

- 当前系统只落地任务级上下文和工作流事实源，不交付通用长期聊天记忆。
- 长期记忆后续仅作为独立设计议题评估，不阻塞 P2 的 SQL 工作台开发环境受控 CRUD 和发布中心非生产受控变更。
- 候选方向拆分为知识记忆、任务记忆、偏好记忆和经验记忆；其中知识记忆必须可引用，任务记忆必须追溯到 M05/M06 事实源，偏好记忆不得影响授权或执行，经验记忆必须评审、版本化、测试和回滚。
- 后续实现必须继续强制执行 M01 身份、M02 授权、M05 工作流事实源、M07 Worker 隔离、M09 强类型事件、M10 审计观测和 M11 评测门禁。
