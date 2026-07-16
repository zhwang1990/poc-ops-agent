# Task 5 实施报告

## 结果

已将 M05 受控 DML 持久化工作流接入 M09 SQL 工作台，并在 bootstrap 完成签名 Worker 适配与 API 装配。实现保持生产写禁用，仅允许服务端策略明确放行的非生产受控 DML。

提交：

- `7e4ebe0a Execute controlled SQL DML workflows`
- `ad99c7ea Harden controlled SQL DML result handling`
- `23eb489a Test controlled SQL DML persistence failures`

## 实现范围

- M05 新增受控 DML 工作流请求、Worker 端口和编排服务。
- 提交前创建或复用持久化工作流，并以事务审计记录创建、确认、提交和终态。
- 相同幂等范围和绑定复用终态；绑定变化返回 `SQL_DML_IDEMPOTENCY_CONFLICT`。
- 正在执行的重复请求返回 `SQL_DML_WORKFLOW_IN_PROGRESS`，不修改原工作流状态。
- Worker 超时、HTTP 408、5xx、无效响应或不确定传输结果标记 `UNKNOWN_REQUIRES_HANDOFF`，不重试。
- Worker 的确定性 `REJECTED`/`FAILED` 响应持久化为稳定失败结果。
- M09 预检重新执行静态校验和服务端策略，使用服务端 `SqlDmlPreviewSelection` 构造独立签名请求。
- M09 提交重新校验环境、连接状态、策略、确认事实和绑定，不信任浏览器能力。
- DML 能力仅在事务审计、环境开关、连接策略、连接 READY 状态和 Worker HMAC 配置同时满足时暴露。
- 默认文件审计不支持事务参与，受控 DML 因而保持 fail-closed；只读 SQL 路径未改变。
- 工作流和审计只保存标识、状态、策略引用及 SHA-256 绑定，不保存原始 SQL、参数值、凭据或预览样本。

## TDD 证据

RED：首次聚焦测试在 M05 编译阶段因缺少 `ControlledSqlDmlWorkerGateway`、`ControlledSqlDmlWorkflowRequest` 和 `ControlledSqlDmlWorkflowService` 失败。补充边界测试后还观察到以下预期失败：预检路由未映射、Worker HMAC 条件未参与能力门禁、Worker 4xx 未稳定映射，以及 Worker `REJECTED` 被错误转为未知结果。

GREEN 聚焦命令：

```powershell
.\backend\mvnw.cmd -f backend\pom.xml -pl 'control-plane/modules/workflow,control-plane/modules/sqlworkbench,control-plane/bootstrap' -am '-Dtest=ControlledSqlDmlWorkflowServiceTest,DefaultSqlWorkbenchServiceTest,SqlWorkbenchControllerTest,PolicyEnforcementWebFilterTest,WebClientSqlWorkbenchWorkerClientTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

结果：`BUILD SUCCESS`。最终 M05 聚焦 8 个、M09 25 个、bootstrap 聚焦 23 个测试通过。

最终全量命令：

```powershell
.\backend\mvnw.cmd -f backend\pom.xml -pl 'control-plane/modules/workflow,control-plane/modules/sqlworkbench,control-plane/bootstrap' -am test
```

结果：`BUILD SUCCESS`，15 个 reactor 模块全部成功；bootstrap 120 个测试通过，无失败或错误。

## 安全与边界检查

- 未修改 Worker、contracts、frontend 或 docs。
- `git diff --check` 通过。
- 独立预检和提交请求分别使用 `canonicalSqlDmlPreflightPayload` 与 `canonicalControlledSqlDmlPayload` 签名。
- HTTP 408、5xx 和客户端超时不会转换成可自动重试的确定性失败。
- M05 仅向 M09 暴露稳定 `WorkflowException`；M09 映射为 `SqlWorkbenchException`，不存在 M05 反向依赖 M09。

## 说明

任务指明的 `C:\Users\Lenovo\Documents\ops-agent` 事实源目录在本机不存在，因此读取并遵循了当前工作树内对应的 `AGENTS.md`、模块图、项目计划、设计追溯和相关 ADR。首次并发 Maven 增量编译出现过缓存不一致，执行一次 reactor `clean -DskipTests test` 后恢复；随后聚焦和全量测试均重复通过。

## 提交后清理验证

在 `7e4ebe0a` 之后检查到三个未暂存的 Task 5 实现文件：Worker HTTP 客户端、其聚焦测试，以及 M05 工作流服务。

- Worker 提交端点对 HTTP `408 Request Timeout` 不再转换为确定性 `FAILED` 结果，而是保留为传输异常。M05 因而将其记录为未知交接并禁止自动重试，避免把可能已到达 Worker 的写操作误判为安全失败。
- M05 对 `assertCompatible(...)` 的事务审计缺失和基础设施异常转换为稳定错误码：`SQL_DML_TRANSACTIONAL_AUDIT_REQUIRED` 或 `SQL_DML_WORKFLOW_PERSISTENCE_FAILED`。两个新增的 M05 回归测试都断言 Worker 未被调用。

验证命令（从 `backend` 目录执行）：

```powershell
.\mvnw.cmd -am -pl control-plane/modules/workflow,control-plane/bootstrap '-Dtest=ControlledSqlDmlWorkflowServiceTest,WebClientSqlWorkbenchWorkerClientTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

结果：退出码 `0`，`BUILD SUCCESS`，总耗时 `8.435 s`，完成时间 `2026-07-17T04:56:22+08:00`。

- `ControlledSqlDmlWorkflowServiceTest`：`8` tests，`0` failures，`0` errors，`0` skipped。
- `WebClientSqlWorkbenchWorkerClientTest`：`13` tests，`0` failures，`0` errors，`0` skipped。

后续提交：`ad99c7ea Harden controlled SQL DML result handling`（三个原始实现/测试文件）；`23eb489a Test controlled SQL DML persistence failures`（M05 聚焦回归测试）。

## 2026-07-17 审查整改

### 整改范围

- 新增 `SqlDmlPreflightReceipt` v1.0 合约，并将 DML 预检结果和提交请求扩展为兼容的 `1.1` 版本。回执只携带标识、时间、操作人、目标元数据和 SHA-256 摘要；不携带 SQL 原文、参数值、影响预览样本或凭据。
- 服务端仅在 Worker 返回实际影响预览后签发 HMAC 回执。回执绑定操作人、幂等键与限制、连接、环境、schema、SQL 与参数摘要、策略版本与服务端选择、以及实际 Worker 影响预览摘要；提交前在 M09 和 M05 两处验证签名、过期时间和全部绑定内容。
- M05 通过 `ControlledSqlDmlPreflightReceiptVerifier` 端口验证回执，未引入 M05 到 M09 的依赖。默认未接线构造器拒绝提交，返回 `SQL_DML_PREFLIGHT_RECEIPT_REQUIRED`。
- 幂等绑定改用 canonical 摘要，不再包含每次请求唯一的策略决定编号；策略决定编号仍持久化在工作流和审计引用中。新的同语义策略重试测试确认复用终态结果。
- 新增 `execution_expires_at` 持久化字段和 V005 迁移。过期或历史无过期时间的 `RUNNING` 工作流会先以事务审计转换为 `UNKNOWN_REQUIRES_HANDOFF`，随后才返回未知结果，且绝不再次调用 Worker。
- 任意不确定 Worker 结果或终态持久化异常都要求写入交接状态；交接持久化失败返回 `SQL_DML_HANDOFF_PERSISTENCE_FAILED`，并保留原 `RUNNING` 工作流以供人工对账。
- M09 只映射稳定的 `WorkflowException`，不再捕获 M05 存储实现异常。DML 能力还要求事务审计、Worker 受控传输、环境策略和回执签名器同时可用。
- Controller 允许并强类型解析 `receipt` 字段；Worker DML 提交签名 payload 已包含回执安全字段。补充 HTTP 408、5xx 和成功响应解码失败均保留为不确定传输结果的测试。

### TDD 红灯证据

1. 在 `backend` 目录执行：

```powershell
.\mvnw.cmd -pl contracts '-Dtest=SqlDmlPreflightResultTest,WorkerRequestSignatureTest' test
```

首次退出码为 `1`，`15` 个测试编译错误：缺少 `SqlDmlPreflightReceipt`、四参数 `SqlDmlPreflightResult`、`receipt()` 访问器，以及预检回执 canonical/影响预览摘要方法。

2. 在 `backend` 目录执行：

```powershell
.\mvnw.cmd -pl control-plane/modules/workflow -am '-Dtest=ControlledSqlDmlWorkflowServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

首次退出码为 `1`，`6` 个测试编译错误：缺少四参数 M05 服务构造器、`executionExpiresAt` 工作流字段和三参数 `markSubmitted` API。

3. 在 `backend` 目录执行：

```powershell
.\mvnw.cmd -pl control-plane/modules/sqlworkbench -am '-Dtest=DefaultSqlWorkbenchServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

首次退出码为 `1`，`4` 个测试编译错误：缺少 `SqlDmlPreflightReceiptService` 和 `SqlDmlPreflightReceiptProperties`。

### TDD 绿灯与聚焦验证

```powershell
.\mvnw.cmd -pl contracts '-Dtest=SqlDmlPreflightResultTest,WorkerRequestSignatureTest' test
```

退出码 `0`，`24` 个测试通过。

```powershell
.\mvnw.cmd -pl control-plane/modules/sqlworkbench -am '-Dtest=DefaultSqlWorkbenchServiceTest,ControlledSqlDmlWorkflowServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

退出码 `0`；`DefaultSqlWorkbenchServiceTest` 的 `28` 个测试和 `ControlledSqlDmlWorkflowServiceTest` 的 `12` 个测试全部通过。

```powershell
.\mvnw.cmd -pl control-plane/bootstrap -am '-Dtest=WebClientSqlWorkbenchWorkerClientTest,SqlWorkbenchControllerTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

退出码 `0`；`WebClientSqlWorkbenchWorkerClientTest` 的 `15` 个测试和 `SqlWorkbenchControllerTest` 的 `9` 个测试全部通过。

### 最终清洁 Reactor 验证

在 `backend` 目录执行：

```powershell
.\mvnw.cmd -pl contracts,control-plane/modules/workflow,control-plane/modules/sqlworkbench,control-plane/bootstrap -am clean test
```

最终一次退出码 `0`，总耗时 `01:46 min`，完成时间 `2026-07-17T05:45:07+08:00`。15 个 reactor 模块均为 `SUCCESS`；关键模块结果如下：

- contracts：`64` tests，`0` failures，`0` errors，`0` skipped。
- workflow：`52` tests，`0` failures，`0` errors，`0` skipped。
- sqlworkbench：`51` tests，`0` failures，`0` errors，`0` skipped。
- bootstrap：`123` tests，`0` failures，`0` errors，`0` skipped。

`git diff --check` 通过。

### 本轮提交

- `Add signed SQL DML preflight receipts`
- `Document Task 5 security remediation`

### 风险与后续事项

- 运行环境必须通过受控密钥管理配置 `ops-agent.controlled-sql-dml.preflight-receipt.key-id` 与 `hmac-secret`；未配置时 DML 能力保持隐藏并失败关闭。
- 本整改不启用生产 DML、不增加 Worker 自动重试，也不改变生产只读限制。正式上线前仍需完成 ADR 0009 所列安全评审、密钥管理联调和人工交接运行演练。

## 2026-07-17 剩余审查问题恢复

### 修复范围

- M05 将回执真实性与绑定校验和提交时效校验拆开：所有请求均校验真实性与绑定，只有即将产生新的 Worker 提交时才校验过期时间。
- M05 在过期拒绝前读取已有幂等工作流。终态直接复用；已过执行时限的 `RUNNING` 以事务审计转为 `UNKNOWN_REQUIRES_HANDOFF`，不重放 Worker。
- `CREATED` 明确作为尚未提交 Worker 的状态处理：有效未过期回执恢复为一次 Worker 调用；过期或无效回执以事务审计转为 `UNKNOWN_REQUIRES_HANDOFF`，不保留永久进行中状态。
- R2DBC 工作流存储允许仅由人工交接转换把 `CREATED` 或 `RUNNING` 置为 `UNKNOWN_REQUIRES_HANDOFF`；成功和确定性失败仍只允许从 `RUNNING` 转换。
- M09 的 DML 能力展示和服务端准入均显式要求回执签名器可用；未注入签名器的兼容构造器隐藏 DML 能力，并在预检或提交前以 `SQL_DML_PREFLIGHT_RECEIPT_UNAVAILABLE` 失败关闭。

### TDD 红灯

在 `backend` 目录执行：

```powershell
.\mvnw.cmd -pl control-plane/modules/workflow -am '-Dtest=ControlledSqlDmlWorkflowServiceTest#movesCreatedWorkflowToAuditedHandoffWhenReceiptIsInvalid' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

首次退出码为 `1`，`1` 个测试运行、`1` 个失败、`0` 个错误、`0` 个跳过。失败符合预期：期望 `SQL_DML_RESULT_UNKNOWN`，实际为 `SQL_DML_PREFLIGHT_RECEIPT_INVALID`，证明无效回执在幂等查找前被拒绝，已有 `CREATED` 工作流未进入持久化人工交接。

### TDD 绿灯

完成 M05 顺序修复后执行同一命令，退出码为 `0`，`1` 个测试通过、`0` 个失败、`0` 个错误、`0` 个跳过；总耗时 `5.862 s`，完成时间 `2026-07-17T06:26:20+08:00`。

### 聚焦验证

在 `backend` 目录执行：

```powershell
.\mvnw.cmd -pl 'contracts,control-plane/modules/workflow,control-plane/modules/sqlworkbench,control-plane/bootstrap' -am '-Dtest=SqlDmlPreflightResultTest,WorkerRequestSignatureTest,R2dbcControlledSqlDmlWorkflowStoreTest,ControlledSqlDmlWorkflowServiceTest,DefaultSqlWorkbenchServiceTest,SqlWorkbenchControllerTest,WebClientSqlWorkbenchWorkerClientTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

退出码为 `0`，`BUILD SUCCESS`，共 `103` 个聚焦测试通过、`0` 个失败、`0` 个错误、`0` 个跳过；总耗时 `9.471 s`，完成时间 `2026-07-17T06:26:44+08:00`。

- contracts：`24` tests。
- workflow：`25` tests，其中工作流服务 `17`、R2DBC 工作流存储 `8`。
- sqlworkbench：`30` tests。
- bootstrap：`24` tests，其中控制器 `9`、Worker 客户端 `15`。

### 清洁 Reactor 验证

在 `backend` 目录执行：

```powershell
.\mvnw.cmd -pl 'contracts,control-plane/modules/workflow,control-plane/modules/sqlworkbench,control-plane/bootstrap' -am clean test
```

退出码为 `0`，`BUILD SUCCESS`，总耗时 `01:46 min`，完成时间 `2026-07-17T06:29:02+08:00`。`15` 个 reactor 模块全部为 `SUCCESS`；关键模块结果如下：

- contracts：`64` tests，`0` failures，`0` errors，`0` skipped。
- workflow：`57` tests，`0` failures，`0` errors，`0` skipped。
- sqlworkbench：`53` tests，`0` failures，`0` errors，`0` skipped。
- bootstrap：`123` tests，`0` failures，`0` errors，`0` skipped。

`git diff --check` 退出码为 `0`。任务指定的 `C:\Users\Lenovo\Documents\ops-agent` 事实源目录仍不存在，本轮继续读取并遵循当前工作树内对应的 `AGENTS.md`、模块图、项目计划、设计追溯和 ADR 0009。
