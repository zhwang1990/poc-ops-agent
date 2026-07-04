# 项目术语解释

## 1. 文档目的

本文面向本仓库的研发同学，用于解释当前仓库已经出现或近期会高频出现的术语。

本文不追求百科式定义，而是强调三个问题：

- 这个词在本项目里具体指什么。
- 研发同学会在什么场景下遇到它。
- 使用它时最容易出现哪些理解偏差。

当前阶段为 P2“受控变更试点”。P1“只读诊断 MVP”已于 2026-07-01 完成验收；不要把 P2/P3 尚未落地的能力写成既成事实。

## 2. 阅读方式

每个术语统一按以下结构解释：

- 定义：在本项目中的含义。
- 场景：研发同学通常在哪类需求、设计或排障中遇到它。
- 常见边界或误区：哪些理解是错的，或者当前阶段不能这样使用。
- 相关位置：可以继续深读的模块或文档入口。

## 3. 项目治理与架构类术语

### ADR

- 定义：`ADR` 是 `Architecture Decision Record` 的缩写，表示架构决策记录。它用于记录那些会影响架构边界、安全形态、运维成本、契约选择或长期可维护性的关键决策。
- 场景：当你要确定“控制面为什么先做成模块化 Spring Boot”“前端为什么选当前工具链”“高风险执行为什么必须强隔离”这类问题时，需要查已有 ADR；如果你准备引入新的基础设施、改变模块边界或突破现有限制，也需要新增 ADR。
- 常见边界或误区：ADR 不是普通开发笔记，也不是会议纪要。只有足以影响长期工程形态或安全边界的决策才应该进入 ADR。没有经过评审接受的 ADR，不能当作最终事实源。
- 相关位置：`docs/adr/README.md`、`docs/adr/`

### 模块编号

- 定义：模块编号是本项目对职责边界的统一编号方式，例如 `M01` 表示接入网关与身份认证，`M07` 表示执行器与安全隔离。
- 场景：写任务、PR、设计说明、测试范围或跨模块接口时，经常需要明确“这次改动属于哪个模块”以及“是否跨模块”。
- 常见边界或误区：模块编号首先是职责边界，不等于必须独立部署的微服务。不要把“模块”自动理解成“单独服务”。
- 相关位置：`AGENTS.md`、`docs/architecture/module-map.md`

### P1

- 定义：`P1` 是已经完成验收的项目阶段，目标是交付“只读诊断 MVP”。
- 场景：回看只读 Skill、身份认证、策略授权、审计、语义事件和 CI 基础为何这样设计时，需要按 P1 的验收边界理解。
- 常见边界或误区：P1 明确禁止生产写执行、任意脚本执行和绕过策略或审计的路径。不要把“P1 已验收”理解为可以降低这些安全边界。
- 相关位置：`AGENTS.md`、`docs/planning/project-plan.md`

### P2

- 定义：`P2` 是当前项目阶段，目标是交付“受控变更试点”。
- 场景：判断某个需求是否应该现在做时，先看它是否属于非生产、低风险、可回滚的受控变更，例如 SQL 工作台 `dev`、`sit`、`uat` 受控 CRUD 或发布中心非生产 WAR 发布、启停、回滚和只读日志分析。
- 常见边界或误区：P2 不是“开放写操作”。所有副作用动作仍必须经过服务端策略、审批或二次确认、持久化工作流、幂等、审计、Worker 隔离、失败恢复和回滚设计；生产写执行和任意脚本执行仍禁止。
- 相关位置：`AGENTS.md`、`docs/planning/project-plan.md`、`docs/adr/0009-sql-workbench-controlled-development-crud.md`、`docs/adr/0010-release-center-non-production-controlled-change.md`

### MVP

- 定义：`MVP` 是 `Minimum Viable Product` 的缩写，在本项目里指“满足当前阶段目标、可被验收、但不追求一次性做全的最小可行版本”。
- 场景：P1 的 MVP 不是全功能运维平台，而是“可审计、可授权、可回放、无生产写入”的只读诊断闭环；P2 的 MVP 则是非生产、低风险、可回滚的受控变更闭环。
- 常见边界或误区：MVP 不是“随便做个 Demo”。它仍然必须满足安全边界、事实源、契约、测试和可观测性要求。
- 相关位置：`AGENTS.md`、`docs/planning/project-plan.md`

## 4. 身份与授权类术语

### IdP / IDP

- 定义：`IdP` 是 `Identity Provider` 的缩写，表示身份提供方。团队口头上有时会写成 `IDP`，本质上说的是同一个概念。
- 场景：当控制面接入企业统一登录、浏览器会话登录或本地模拟登录时，都会涉及 IdP。当前仓库既有真实 OIDC 配置模式，也有本地模拟 IdP 用于开发和测试。
- 常见边界或误区：这里的 IdP 指身份提供方，不是“内部开发平台”。在本仓库上下文里看到 `IdP`，优先按身份认证理解。
- 相关位置：`docs/planning/project-plan.md`、`docs/runbooks/local-oidc-mock-testing.md`、`backend/control-plane`

### SSO

- 定义：`SSO` 是 `Single Sign-On` 的缩写，表示单点登录。用户在统一身份体系下完成一次登录后，可以访问被纳入信任体系的多个应用。
- 场景：操作员通过企业登录进入控制面时，通常就是 SSO 场景。研发同学在联调浏览器登录、回调地址、退出流程和会话查询接口时会接触到它。
- 常见边界或误区：SSO 解决的是“怎么登录进来”，不等于“登录后就自动有权限”。认证通过后，仍然要走服务端授权和审计链。
- 相关位置：`AGENTS.md`、`docs/planning/project-plan.md`

### OIDC

- 定义：`OIDC` 是 `OpenID Connect` 的缩写，是建立在 OAuth 2.0 之上的身份层协议。本项目用它承载标准浏览器登录、用户身份声明获取和会话建立。
- 场景：如果你在看 `issuer-uri`、`jwk-set-uri`、登录入口、回调地址、用户 Claim 映射和浏览器会话接口，基本就在处理 OIDC 接入。
- 常见边界或误区：OIDC 在本项目里主要解决身份认证和身份声明来源，不负责替代服务端策略授权。不要把 OIDC Token 解析成功理解成“业务动作一定允许”。
- 相关位置：`docs/planning/project-plan.md`、`docs/runbooks/local-oidc-mock-testing.md`、`backend/control-plane`

### JWT

- 定义：`JWT` 是 `JSON Web Token` 的缩写，是一种带签名的令牌格式。本项目用它承载认证后的身份声明，并作为服务端校验请求身份的输入之一。
- 场景：本地开发态 Bearer Token、OIDC 返回的令牌校验、角色 Claim 映射、接口鉴权测试，都会用到 JWT。
- 常见边界或误区：JWT 只是身份和声明载体，不是授权结果本身。令牌合法，不代表可以执行某个诊断动作；还需要进入 M02 的策略决策。
- 相关位置：`AGENTS.md`、`docs/planning/project-plan.md`

### RBAC

- 定义：`RBAC` 是 `Role-Based Access Control` 的缩写，表示基于角色的访问控制。本项目先以角色到动作的映射实现基础授权。
- 场景：当你看到“某个动作需要哪些角色”“角色不足返回结构化 403”“动作到角色的规则外置配置”时，这就是 RBAC 的落点。
- 常见边界或误区：RBAC 是基础授权能力，不等于最终完整策略体系。它也不是前端判断逻辑，浏览器不能自己根据按钮文案或页面状态决定授权结果。
- 相关位置：`docs/planning/project-plan.md`、`docs/runbooks/identity-policy-audit.md`、`backend/control-plane`

## 5. 契约与能力类术语

### Skill

- 定义：`Skill` 是本项目中的版本化运维能力单元。它不是随意挂接的一段脚本，而是带有 Owner、版本、分类、风险、执行器、输入输出契约、权限和测试要求的受控能力定义。AgentScope 看到的是 `backend/skills/<skill>/SKILL.md`；平台注册和治理读取的是 `backend/contracts/skills/packages/<skill>/`。
- 场景：当 AgentScope 需要理解“何时使用某项能力”时，读取 `SKILL.md`。当控制面要决定“这次诊断请求能否调用某个 Tool”“某个能力是否已经发布可用”“Worker 允许加载哪些适配器”时，读取平台契约包。
- 常见边界或误区：Skill 不等于自由脚本执行入口。P1 已围绕显式注册、已校验、只读的 Skill 完成闭环；P2 若新增有副作用能力，必须补齐版本化契约、策略、审批或确认、工作流、审计、Worker 隔离和回滚。
- 相关位置：`AGENTS.md`、`backend/contracts/skills/README.md`、`backend/skills`

### Schema

- 定义：`Schema` 是结构约束定义，用于规定输入、输出、事件或命令必须长成什么样。在本项目里主要体现为 JSON Schema 和 OpenAPI。
- 场景：定义 Skill 输入输出、只读命令信封、Worker 请求结果、语义事件载荷时，都会先定义 Schema，再由代码和测试去实现。
- 常见边界或误区：Schema 不是可有可无的注释。仓库明确要求先有版本化契约，再让实现依赖合入；也禁止在完整执行链路里用无约束的 `Map<String, Object>` 逃避建模。
- 相关位置：`AGENTS.md`、`backend/contracts/`、`backend/contracts/workflow/README.md`

### Manifest

- 定义：`Manifest` 在本项目里通常指 Skill 的清单文件，用来声明该 Skill 的元数据、契约和发布相关信息。
- 场景：新 Skill 注册、启动期扫描、显式发布校验、签名校验时，都会读取 `backend/contracts/skills/packages/<skill>/manifest.json` 及其相关签名侧文件。
- 常见边界或误区：Manifest 不是给人看的自由文本，而是注册中心和发布校验链路依赖的正式输入。字段变化要受契约和版本管理约束。
- 相关位置：`backend/contracts/skills/README.md`、`backend/contracts/skills/skill-manifest.schema.json`

### 只读 Skill

- 定义：只读 Skill 指不会对生产目标系统产生副作用的诊断能力。
- 场景：P1 的核心交付就是只读 Skill 路由和执行闭环，例如节点健康检查、信息采集和状态查询。
- 常见边界或误区：名字写成“diagnostic”不代表它天然只读，是否只读要看真实行为、契约和 Worker 适配器约束。即使进入 P2，也不允许把写操作伪装成诊断。
- 相关位置：`AGENTS.md`、`docs/standards/p1-threat-model.md`、`backend/skills`

### Control Plane

- 定义：`Control Plane` 即控制面，是本项目中负责认证、授权、Skill 路由、工作流、DAG、审计和对外控制 API 的中心后端区域。
- 场景：研发同学在实现 API、策略决策、工作流持久化、事件输出或 Skill 查询时，多数都在控制面工作。
- 常见边界或误区：控制面可以规划和授权，但不能直接执行脚本，也不能直接持有目标系统长期凭据。它必须把已授权工作提交给独立 Worker。
- 相关位置：`AGENTS.md`、`README.md`、`backend/control-plane`

### Execution Worker / Worker

- 定义：`Execution Worker` 是独立部署的受限执行单元，用于承接控制面发来的已授权请求，并在受限环境中执行目标能力。
- 场景：当一个只读请求通过身份、策略和工作流链路后，最终会由 Worker 执行具体适配器，例如当前已存在的 `node-health-read` 适配器。
- 常见边界或误区：Worker 不是第二个控制面。它不能自行决定授权，也不能绕开版本化请求格式接收任意执行内容。
- 相关位置：`AGENTS.md`、`docs/architecture/module-map.md`、`backend/execution-worker`

## 6. 工作流与执行链路类术语

### Workflow

- 定义：`Workflow` 是围绕一次请求建立的持久化执行流程，负责承接状态推进、结果回读、失败恢复和审计关联。
- 场景：只要请求进入正式执行链路，就需要有工作流实例、事件序列、结果和恢复语义；P2 的受控变更更必须依赖持久化工作流。
- 常见边界或误区：工作流不是“以后写操作阶段再补”的能力。仓库已经明确，生产副作用操作必须走持久化工作流，P1 也已经为只读切片建立了受控工作流基础。
- 相关位置：`AGENTS.md`、`docs/planning/project-plan.md`、`backend/contracts/workflow/`

### DAG

- 定义：`DAG` 是 `Directed Acyclic Graph` 的缩写，表示有向无环图。在本项目中它用于表达多步骤执行编排、依赖顺序和并行关系。
- 场景：当一个诊断或后续受控变更需要拆成多个步骤，并且这些步骤存在先后依赖或并行机会时，就会进入 DAG 编排语境。
- 常见边界或误区：DAG 不是所有请求都必须上。P1 重点是只读闭环；P2/P3 的复杂编排仍必须受 M06 边界、契约、恢复语义和审计约束。
- 相关位置：`AGENTS.md`、`docs/architecture/module-map.md`、`docs/planning/design-traceability.md`

### Idempotency Key

- 定义：`Idempotency Key` 是幂等键，用于标识“这是不是同一次业务请求的重复提交”。
- 场景：接口重试、网络抖动、前端重复提交、恢复重放时，需要用幂等键判断是否应该复用已有执行结果，而不是重复执行一遍。
- 常见边界或误区：幂等不是“尽量别重复”，而是需要有明确键值、持久化语义和恢复策略支撑。它尤其不能只依赖前端按钮状态来实现。
- 相关位置：`AGENTS.md`、`backend/contracts/workflow/read-only-command-v1.schema.json`

### 审批

- 定义：审批是对高风险或有副作用动作的人工或流程化放行机制，用于把授权、目标、参数和时间窗口绑定起来。
- 场景：P2 开始引入非生产受控变更后，审批或二次确认必须绑定 Skill 版本、目标、参数哈希、策略版本、审批人范围和过期时间。
- 常见边界或误区：审批不是“同意一下就行”。一旦关键内容变化，原审批必须自动失效，不能沿用旧批准结果。
- 相关位置：`AGENTS.md`、`docs/architecture/module-map.md`

### Audit / 审计事件

- 定义：`Audit` 指可追溯的审计记录，用于记录授权决策、执行请求、结果、补偿、人工接管、配置变更和发布等关键动作。
- 场景：研发同学在排查“谁在什么时间、以什么身份、对什么资源、基于什么策略发起了什么请求，以及结果是什么”时，需要依赖审计事件。
- 常见边界或误区：审计不是普通业务日志的别名。审计关注的是可追责和不可抵赖，不能依赖聊天记忆、Redis 临时态或前端展示文本充当执行事实源。
- 相关位置：`AGENTS.md`、`docs/runbooks/identity-policy-audit.md`、`docs/planning/project-plan.md`

### TraceId

- 定义：`TraceId` 是一次调用链路的追踪标识，用于把 API、策略、工作流、Worker、事件和审计关联起来。
- 场景：一次请求跨越控制面、工作流持久化、Worker 调用和事件回传时，研发同学通常会用 TraceId 做问题串联和排障。
- 常见边界或误区：TraceId 只是关联标识，不是权限凭据，也不能代替工作流 ID、请求 ID 或审计主键。
- 相关位置：`AGENTS.md`、`docs/planning/project-plan.md`

## 7. 前端与事件流类术语

### 语义事件

- 定义：语义事件是带明确类型和结构化载荷的状态事件，用来表达工作流在执行链路中的真实状态变化。
- 场景：前端操作台不会通过展示文本猜测状态，而是根据诸如 `SKILL_ROUTED`、`WORKER_ACCEPTED`、`WORKFLOW_COMPLETED` 这类强类型事件进行渲染。
- 常见边界或误区：语义事件不是日志文本换个名字。它必须有稳定字段、事件类型和载荷结构，供系统和前端可靠消费。
- 相关位置：`AGENTS.md`、`backend/contracts/events/`、`frontend/operator-console`

### SSE

- 定义：`SSE` 是 `Server-Sent Events` 的缩写，是服务器持续向浏览器推送事件流的一种方式。
- 场景：当前只读操作台通过 SSE 获取工作流的语义事件序列，用于展示请求经过身份、策略、路由、Worker 和审计链的状态变化。
- 常见边界或误区：SSE 只解决事件流传输问题，不负责授权判定，也不自动保证断线恢复语义。断点恢复、重连和去重需要额外设计与实现。
- 相关位置：`docs/planning/project-plan.md`、`frontend/operator-console/README.md`、`frontend/operator-console/src/app/router.jsx`

### Operator Console

- 定义：`Operator Console` 即操作台，是面向操作员的前端界面，用于展示语义事件、审批状态和人工接管相关交互。
- 场景：当前仓库里的最小只读操作台已经可以消费语义事件流，帮助研发同学联调只读诊断链路。
- 常见边界或误区：操作台是状态展示和交互入口，不是安全决策点。它不能绕过服务端策略、审批、幂等、审计或隔离控制。
- 相关位置：`AGENTS.md`、`README.md`、`frontend/operator-console`

## 8. 研发同学最容易混淆的几组概念

### 认证、授权、审批不是一回事

- 认证回答“你是谁”，典型术语是 `IdP`、`OIDC`、`SSO`、`JWT`。
- 授权回答“你能不能做”，典型术语是 `RBAC`、策略决策。
- 审批回答“这次高风险动作是否在当前上下文下被额外放行”，它绑定 Skill 版本、目标、参数和时效。

### Skill、脚本、Worker 不是一回事

- Skill 是受版本和契约约束的能力定义。
- Worker 是受限执行环境。
- 脚本只是某种可能的执行载体；P1 和当前 P2 都明确禁止任意脚本执行。

### 审计事件、语义事件、普通日志不是一回事

- 审计事件用于追责和合规。
- 语义事件用于表达工作流状态并驱动前端展示。
- 普通日志用于排障和运行观测。

### 模块、服务、部署单元不是一回事

- 模块用于定义职责边界。
- 服务表示运行时进程或应用。
- 部署单元表示实际发布和运维对象。

本项目当前是单仓库、多交付单元，并未要求每个模块都拆成独立服务。

## 9. 建议阅读顺序

如果你是新加入项目的研发同学，建议按以下顺序继续阅读：

1. `AGENTS.md`：先理解全局规则和禁止项。
2. `docs/architecture/module-map.md`：理解模块边界和执行链路。
3. `docs/planning/project-plan.md`：理解当前阶段、范围和优先级。
4. `docs/planning/design-traceability.md`：理解什么是设计内能力，什么不能擅自扩边界。
5. `backend/contracts/`：理解契约先行的落地方式。
