# P1 只读诊断垂直切片验收证据

## 验收结论

P1 只读诊断 MVP 已于 2026-07-01 完成里程碑验收。验收接受范围为：可审计、可授权、可回放、无生产写入的只读诊断闭环，以及开发/测试环境受控单条 `SELECT` 查询边界。

本文件保留 P1 验收证据、自动化验证、本地端到端验证和安全边界补充记录。原先列为里程碑待确认的远程治理、真实企业联调、集中审计和生产隔离事项不再阻塞 P1，已转入 P2/P3 后续治理和生产加固。

## 已验证能力

- 版本化只读命令、Worker 请求、Worker 结果和语义事件契约。
- 契约 Java 值对象拒绝非只读命令和不一致事件载荷。
- Worker 仅执行显式注册的只读适配器；`node-health-read:1.1.0` 使用专用开发适配器，简单 HTTP/JSON Skill 通过配置型适配器接入。
- Worker 拒绝过期请求和未知 Skill 版本。
- 控制面确定性路由到已校验的只读 Skill，并通过 `WorkerGateway` 调用独立 Worker。
- 控制面将只读工作流、幂等键、原始命令信封、执行结果和语义事件持久化到关系型事实源。
- 控制面可在启动后扫描 `FAILED_RETRYABLE` 工作流，以及当前 attempt 已过期的 `RUNNING` / `REPLAYING` 工作流，并执行一次受控重放。
- SSE 输出强类型语义事件。
- React/TypeScript 操作台按语义事件类型渲染，不进行浏览器授权决策。
- 内置只读 Skill 数量达到 6 个。
- SQL 工作台仅允许开发/测试环境受控单条 `SELECT` 执行，DML 保持静态预检，生产连接在控制面和 Worker 边界均不可见、不可调用。

## 自动化验证

- `Set-Location backend`
- `.\mvnw.cmd -f .\pom.xml -B -ntp verify`
- `tools/ci/check-repository.ps1`
- `tools/ci/check-contracts.ps1`
- `tools/ci/scan-secrets.ps1`
- `npm run build`，执行位置为 `frontend/operator-console`

## 本地端到端验证

2026-06-06 已启动独立 Worker 和控制面并调用 SSE 诊断接口：

- 未认证请求返回 `401`。
- 有效开发 JWT 请求返回 `200`。
- 返回事件顺序为：
  1. `WORKFLOW_STARTED`
  2. `SKILL_ROUTED`
  3. `WORKER_ACCEPTED`
  4. `WORKFLOW_COMPLETED`
- Worker 返回 `node-health-read:1.1.0` 的 `HEALTHY` 结构化结果。

2026-06-07 已补充工作流持久化与恢复验证：

- `control-plane-workflow` 模块测试覆盖：
  - 版本化迁移脚本 `sql/migrations/V001__workflow_schema.sql` 可直接初始化事实表
  - 幂等命中返回既有工作流结果
  - workflow attempt 持久化与回读
  - 成功结果与事件回读
  - `WORKER_TIMEOUT` 失败落入 `FAILED_RETRYABLE`
  - `FAILED_RETRYABLE` 工作流仅受控重放一次
  - `RUNNING` 工作流在当前 attempt 过期后触发一次受控恢复，未过期时不会误重放
  - `R2dbcReadOnlyWorkflowRecoveryIntegrationTest` 覆盖真实 R2DBC 持久化状态下的恢复闭环
- `ControlPlaneApplicationTest` 已验证：
  - `ReadOnlyWorkflowStore` 与 `ReadOnlyWorkflowRecoveryService` 已完成 Spring 装配
  - 启动期 schema 初始化后可完成最小工作流持久化查询

## 已移交 P2/P3 的后续事项

- 真实企业 IdP 联调、环境专用参数校验和联调记录。
- 远程 GitHub CI、分支保护和真实评审团队协作治理。
- mTLS、网络层出口策略、短期目标系统凭据、Windows 隔离部署方案和生产演练。
- 开发 HMAC/JWT 固定测试密钥迁移为运行时生成或安全注入。
- 更多生产级 Worker 适配器、受控第三方 HTTP 源和 Skill 发布流水线。

## 2026-06-24 天气查询 Skill 注册补充证据

- 已新增 `weather-current-read:1.0.0` 当前天气查询 Skill。
- AgentScope 入口位于 `backend/skills/weather-current/SKILL.md`，只允许通过平台 Tool 查询指定地点当前天气，不允许直接调用外部 API 或使用未托管凭据。
- 平台契约位于 `backend/contracts/skills/packages/weather-current/`，包含 manifest、HMAC 发布签名、输入输出 Schema，以及 happy-path、invalid-parameters、policy-denied 三类样例。
- 控制面测试资源同步新增该 Skill，用于验证启动注册、按 SkillId 查询和显式发布校验动作。
- Worker 已新增配置型 HTTP/JSON 只读适配器和 HTTP 出口 allowlist，`weather-current-read` 通过配置项注册到该通用适配器；默认 `endpoint-url` 为空且 HTTP allowlist 为空，因此未配置受控天气源时会稳定拒绝。
- 本次不新增天气专用 Java 适配器，后续简单第三方 HTTP Skill 应优先复用该通用适配器配置。
- SSE 当前在 Worker 返回后输出完整事件序列，不支持执行中的增量恢复。

## 2026-06-07 M09 事件流恢复补充证据

- `R2dbcReadOnlyWorkflowStoreTest` 已覆盖按 `workflowId + afterSequence` 读取后续语义事件。
- `ControlPlaneApplicationTest` 已覆盖恢复接口 `GET /internal/diagnostics/read-only/workflows/{workflowId}/events` 的策略保护与 SSE 输出。
- `frontend/operator-console` 已增加当前工作流内自动恢复、事件去重和连接状态展示，并通过 `npm run build`。
- 当前恢复能力仍以“已落盘事件续传”为边界，不宣称支持执行中的增量事件推送。
## 2026-06-23 M09 登录登出与本地门禁补充证据

- 登录页已接入现有 `POST /auth/login`，保留用户名与密码输入，登录成功后进入 `/overview`。
- 操作台登出入口已接入服务端登出流程，退出后回到 `/login`。
- 后端控制面以 `built-in` 登录模式启动后，`GET /actuator/health` 返回 `{"status":"UP","groups":["liveness","readiness"]}`。
- `backend` 执行 `.\mvnw.cmd verify` 通过，15 个 Maven reactor 模块均为 `SUCCESS`。
- `frontend/operator-console` 执行 `npm run build` 通过，包含 `checkJs`、ESLint、Vitest 和 Vite 生产构建；Vitest 结果为 10 个测试文件、88 个测试通过。
- `frontend/operator-console` 执行 `npm run test:e2e` 通过，Playwright 在 `1280px`、`1440px`、`1920px` 三个桌面视口共 9 个浏览器测试通过。
- `frontend/operator-console` 执行 `npm audit --audit-level=high` 通过，结果为 `found 0 vulnerabilities`。
- 仓库级 `tools/ci/check-repository.ps1`、`tools/ci/check-contracts.ps1` 和 `tools/ci/scan-secrets.ps1` 均通过。

本轮本地自动化门禁已满足 P1 只读诊断 MVP 的提交验收条件。2026-07-01 里程碑验收已接受该证据集；远程 CI、分支保护、评审签署和生产加固项转入 P2/P3 后续治理。

## 2026-06-23 T010 审计保留与恢复补充证据

- 已新增 `docs/runbooks/audit-retention-and-recovery.md`，明确 P1 文件审计的保留周期、归档步骤、恢复流程、访问控制和故障处理。
- 已在 `docs/runbooks/identity-policy-audit.md` 增加审计运行手册入口，避免身份、策略与审计联调说明承载过多运维细节。
- 已更新 `docs/runbooks/README.md` 和 `docs/planning/project-plan.md`，将 T010 进度调整为 96%，并保留集中审计存储和真实环境恢复演练作为剩余条件。

本补充不改变当前代码实现和部署边界；P1 仍采用追加式 JSONL 文件审计，正式集中审计存储或组织级备份系统接入仍属于后续环境落地事项。

## 2026-06-23 M07 Worker 传输认证补充证据

- 已新增控制面到 Worker 的应用层 HMAC 签名契约，签名绑定执行请求 ID、命令、工作流、Skill、策略、trace 和参数摘要。
- Worker HTTP 边界在认证启用时会拒绝未签名、错误签名和时间漂移过大的请求。
- Worker 非回环绑定且未启用传输认证时启动保护失败，避免把未认证 Worker 暴露到跨主机网络。
- 已新增 ADR `docs/adr/0008-m07-worker-transport-auth-and-deployment-isolation.md` 和运行手册 `docs/runbooks/m07-worker-transport-auth.md`。

本补充仍不宣称完成完整生产隔离。mTLS、受控网络出口、短期目标系统凭据、Windows 隔离部署方案和生产演练仍是 M07 后续条件。

## 2026-06-23 M07 Worker SQL 出口 allowlist 补充证据

- Worker 新增 SQL 连接目录和 host/port allowlist 校验；部署安全基线为空连接目录和空 allowlist，仓库内置 `h2-local-test` 仅用于本地 H2 smoke。
- SQL 连接目录只接受 `development` 和 `test` 环境，P1 生产 SQL 连接会在 Worker 边界被拒绝。
- 数据源解析前会先执行 Worker 本地出口策略；未知连接、禁用连接、环境不匹配和 host/port 不在 allowlist 时不会继续解析真实 `DataSource`。
- 出口策略拒绝会映射为 SQL 执行结果 `REJECTED`，并保留稳定错误码，避免误报为普通执行失败。
- 新增自动化测试覆盖 `WorkerSqlConnectionDescriptorTest`、`WorkerSqlEgressPolicyTest`、`PolicyEnforcedSqlDataSourceRegistryTest`、`WorkerSqlEgressPropertiesTest`、`ExecutionWorkerConfigurationTest`、`RestrictedSqlQueryExecutionWorkerTest` 和 `JdbcSqlQueryExecutorTest`。

本补充是应用层出口保护，不宣称替代防火墙、私有网络、mTLS、短期目标系统凭据、Windows 隔离或网络层出口策略。

## 2026-06-24 M07 Worker HTTP 出口与配置型 Skill 补充证据

- Worker 新增 HTTP 出口 `scheme + host + port` allowlist，默认空 allowlist 拒绝所有 HTTP 目标。
- Worker 新增 `ConfiguredHttpReadOnlySkillAdapter`，简单 HTTP/JSON 只读 Skill 通过配置声明 Skill ID、版本、基础 URL、输入参数名、query 参数名、响应字段白名单、可选 source 和 timeout。
- `weather-current-read:1.0.0` 已通过配置型适配器接入 Worker；默认天气源端点为空，因此未配置受控天气源时返回 `HTTP_SKILL_SOURCE_NOT_CONFIGURED`，不会访问外部网络。
- 配置型适配器会对 query 参数进行安全编码，只透传响应字段白名单，并补充 `generatedAt`；`source` 是可选非敏感标识，未配置时不输出该字段。
- 新增自动化测试覆盖 `WorkerHttpEgressPolicyTest`、`WorkerHttpEgressPropertiesTest`、`ConfiguredHttpReadOnlySkillAdapterTest` 和 `ExecutionWorkerConfigurationTest` 中的配置型 HTTP Skill 场景。

本补充是应用层出口保护，不宣称替代防火墙、私有网络、mTLS、短期目标系统凭据、内部受控网关、Windows 隔离或网络层出口策略。
