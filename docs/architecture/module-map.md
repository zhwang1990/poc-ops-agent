# 模块地图

## 架构决策

V1.0 使用单一仓库，包含模块化控制面、独立部署的执行 Worker、独立操作台，以及版本化 Skill 区域。  
以下模块编号描述职责归属和契约边界，不表示每个模块都必须成为独立部署服务。  
系统为公司内部自研自用、单组织部署。模块设计不包含租户标识、租户隔离、租户路由或租户管理。

## 模块清单

| 编号 | 模块 | 初始位置 | 主要职责 |
|---|---|---|---|
| M00 | 项目治理与工程基础 | `docs`、`tools`、根目录配置 | 范围、ADR、威胁模型、CI 和规范 |
| M01 | 接入网关与身份认证 | `backend/control-plane` | API 入口、OIDC / SSO、JWT 和可信身份上下文 |
| M02 | 策略授权与审计 | `backend/control-plane` | 策略决策、RBAC、风险控制和审计 |
| M03 | Skill 契约与注册中心 | `backend/control-plane`、`backend/contracts` | Skill Schema、版本、签名、注册和发布校验 |
| M04 | Agent 路由与模型交互 | `backend/control-plane` | 候选 Skill 选择、约束筛选和后续路由决策 |
| M05 | 工作流、审批与状态恢复 | `backend/control-plane` | 持久化工作流、审批、幂等和补偿 |
| M06 | DAG 编排与制品黑板 | `backend/control-plane` | DAG 校验、调度、制品和容错 |
| M07 | 执行器与安全隔离 | `backend/execution-worker` | 受限执行、网络、凭据和隔离 |
| M08 | 运维 Skill 与目标系统集成 | `backend/skills` | 运维能力和目标系统适配器 |
| M09 | 操作台与语义事件流 | `frontend/operator-console`、`backend/control-plane` | 强类型事件、操作界面、审批和人工接管 |
| M10 | 可观测性与平台运维 | `backend/deploy`、`docs/runbooks` | 数据、遥测、部署、高可用、灾备和手册 |
| M11 | 测试评测、安全验证与发布 | 所有交付单元 | 独立质量和发布门禁 |

## 强制执行链路

```text
操作员 API
  -> M01 身份认证
  -> M02 策略授权
  -> M03 / M04 Skill 契约与候选路由
  -> M05 持久化工作流与审批
  -> M06 DAG 调度
  -> M07 受限执行
  -> M08 目标系统操作
  -> M09 强类型状态和事件展示
  -> M02 / M10 审计与可观测
```

任何模块都不得为生产副作用操作创建绕过此链路的捷径。

## 初始部署边界

### 控制面

控制面包含 M01-M06 和 M09 的后端部分。V1.0 初期采用模块化 Spring Boot 应用。  
控制面可以规划和授权工作，但不得直接执行脚本，也不得持有目标系统长期凭据。

### 执行 Worker

执行 Worker 包含 M07，并加载已授权的 M08 执行适配器。它独立部署，使用不同身份、受限网络和短期凭据。  
Worker 可以执行已授权工作，但不得自行做授权决策。
SQL 工作台 Worker 侧适配代码位于 `backend/execution-worker-sqlworkbench`，作为同一执行 Worker 部署的运行时依赖加载，不新增部署服务或模块编号。
发布中心的 Liberty、Tomcat 和后续服务器适配器也必须作为同一执行 Worker 的受控适配能力加载，不新增部署服务或模块编号。

### 操作台

操作台包含 M09 的前端部分，用于展示强类型事件和审批状态。  
操作台不得根据展示文本推断授权状态，也不得直接调用目标系统。
当前首轮重写页面包括登录页、Agent 工作台、Skill 注册中心和 SQL 工作台。页面必须优先消费真实控制面接口；未开放的提交、安装、升级、卸载、AI SQL 助手和 DML 执行能力只能显示为禁用状态。

### Skill 区域

Skill 区域包含 M08 的定义和测试。生产 Skill 必须经过版本化、签名、评审和测试，并且支持回滚。
简单 HTTP/JSON 只读 Skill 优先通过 M07 配置型 Worker 适配器和 HTTP 出口 allowlist 接入；只有需要专用协议、SDK、复杂会话、特殊凭据或独立隔离策略时，才新增专用 Worker 适配器。

## 当前实现重点

- P1 只读诊断 MVP 已于 2026-07-01 完成验收，当前阶段进入 P2 受控变更试点。
- M03 当前已经实现：
  - Skill Manifest 契约
  - 发布侧签名文件契约
  - 启动期注册
  - 显式发布校验动作
- M04 当前已经实现：
  - 基于 SkillId、分类、风险、参数、标签、发布状态的规则筛选
  - 控制面候选查询接口
- M09 SQL 工作台当前已经实现：
  - 仅开发和测试环境的 AS/400 连接目录
  - 基于 Calcite AST 的单语句分类、只读校验和 DML 静态预检
  - `backend/execution-worker-sqlworkbench` 中 Worker 侧独立的只读 SQL 拒绝边界
  - React SQL 工作台界面与强类型校验报告
- M09 操作台首轮原型化重写当前已经实现：
  - 浏览器会话登录页与受保护路由；
  - Agent 工作台接入只读候选 Skill 路由接口，并通过 `/api/v1/agent/diagnostics` 提交主 Agent 只读诊断任务；
  - Skill 注册中心接入真实 Skill 目录，变更类操作保持禁用；
  - SQL 工作台接入连接目录、校验接口和开发/测试环境受控单条 `SELECT` 查询结果，DML 仍仅提供静态预检；
  - Playwright 已覆盖 `1280px`、`1440px`、`1920px` 三个桌面视口的浏览器验收。
- SQL 工作台 P1 切片已按开发/测试环境受控单条 `SELECT` 执行、DML 仅预检、Worker 出口 allowlist、凭据别名与结果治理边界完成验收；生产 SQL 连接始终不可见、不可调用。
- SQL 工作台目标态以主流数据库客户端交互为基线，并通过右侧 AI SQL 助手提供错误分析和性能优化。P2 在不新增部署服务或模块编号的前提下，复用 M02、M05、M07 和 M09 开放开发环境受控 CRUD；生产 SQL 连接始终不可见、不可调用。
- 发布中心已进入 P2 非生产受控变更启动范围，在不新增部署服务或模块编号的前提下复用 M02、M03、M05、M07、M08、M09、M10 和 M11，提供 `dev`、`sit`、`uat` 环境的 WAR 制品发布、Liberty 脚本 Profile 发布、启停、回滚和只读日志分析。P1 不交付该副作用能力；生产环境始终不可见、不可配置、不可调用。
- M09 的 Agent 工作区、RAG 问答和 SQL 工作台共享专注模式：展开时收起左侧主菜单和当前页面右侧辅助卡片，由中央工作区使用释放出的横向空间；恢复布局不改变会话或结果上下文。
- 长期记忆当前不是独立模块，也不属于 P2 启动交付范围。后续如建设，必须由 M02、M04、M05、M06、M09、M10 和 M11 协同约束：M04 只能消费受控上下文，M05/M06 承载任务事实源和制品，M09 展示引用和状态，M10/M02 负责审计与授权，M11 负责评测和安全门禁；不得由 AgentScope memory、ChatMemory、Redis、session state、chat history 或浏览器缓存替代工作流事实源。
- SQL 工作台的数据库对象浏览器可独立收起和恢复；进入专注模式时数据库对象浏览器也会收起。
- AgentScope Java 已被决策为 P1 只读诊断目标主链路，当前已完成 ReAct 工具回调到平台执行器的最小闭环：
  - AgentScope Java `2.0.0-RC4` 通过 `control-plane-agentruntime` 模块提供 ReAct 主运行循环；
  - `/api/v1/agent/diagnostics` 是 Agent 只读诊断目标主入口，`/internal/agent/diagnostics` 仅作为内部兼容入口保留；
  - 当前实现已覆盖禁用/未配置失败关闭、受保护入口、Agent workflow 基础事实源、最终摘要 POC、AgentScope 真实 `AgentTool` 注册，以及 workflow-backed Agent Tool 执行器；
  - 平台守护执行器落在 M05，调用 M02 策略决策、记录执行器级授权审计、写入 M05 Tool Step、发布 Agent Tool 语义事件，并通过 M07 WorkerGateway 提交已授权只读命令；M04 只保留 `AgentToolExecutor` 端口，避免反向依赖 M05/M07；
  - ReAct 模型发出的 ToolUse 会先被 M04 转为强类型 `AgentToolCall`，再由 M05 执行器重新校验目录、重新授权、记录审计、持久化 Tool Step、发布 requested/completed/rejected 语义事件并提交 Worker；
  - Agent Tool 请求、完成和拒绝三类语义事件契约骨架、M05 发布接线、执行器级审计和多 Tool 幂等恢复演练已补齐；正式集中审计存储仍归 T010 后续条件；
  - 确定性单 Skill 只读入口保留为联调、兼容和紧急回退路径；AgentScope 主链路的评测集和路由解释 API 在 P2/P3 继续增强；
  - Tool Catalog 必须由 M03 注册与发布校验结果生成，并受 P1 只读风险约束；
  - 每一次 Tool Call 都必须经过平台守护执行器、M05 工作流持久化和 M07 Worker 隔离执行；
  - AgentScope Java 不得替代身份、授权、审计、工作流事实源、Skill 发布治理或 Worker 隔离边界。
- Quick Links / 快捷连接入口当前短期禁用；后续如开放 Splunk 等外部系统跳转，必须先补齐后端契约、服务端策略授权和审计事件。
- M08 Skill 区域改为 AgentScope Skill 与平台契约分离：
  - `backend/skills/<skill>/SKILL.md` 是 AgentScope 文件系统 Skill 入口，用自然语言说明何时使用、如何调用平台 Tool 和只读安全边界；
  - `backend/contracts/skills/packages/<skill>/manifest.json` 是 M03 启动注册入口，schema 与测试样例用于发布评审、M11 契约测试和 AgentScope Tool Catalog 生成校验；
  - P1 Skill 只能表达只读诊断能力，不得包含生产写执行、密钥、生产数据或匿名脚本。
## 边界评审检查清单

- 调用者身份是否明确且可信？
- 请求是否已经通过 M02 授权？
- 契约版本是否明确？
- 操作属于只读还是副作用操作？
- 是否需要持久化工作流？
- 操作是否幂等、可补偿或可人工恢复？
- 执行是否需要强隔离？
- 审计、指标、日志和追踪是否已定义？
- 发布和回滚方式是否已记录？
