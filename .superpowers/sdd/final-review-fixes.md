# 最终全分支评审修复报告

## 1. 交付信息

- 日期：2026-07-17
- 分支：`codex/p2-audit-storage`
- 基线提交：`89ae767ee3e0`
- 交付提交标题：`Harden controlled DML final review paths`
- 范围：仅涉及 M05、M07、M09、操作台、受控 DML 运行手册及聚焦测试。
- 环境约束：受控 DML 仍仅限非生产环境；默认配置保持关闭；未知执行结果禁止自动重试。
- 提交哈希说明：本报告与实现位于同一交付提交中，提交哈希会在提交完成后由最终交付结果给出，避免在提交内容中自引用其尚未生成的哈希。

## 2. 六项发现与修复结果

### 2.1 JDBC 提交结果不确定性

完成。`JdbcSqlQueryExecutor` 将 `commit()` 抛出的异常转换为稳定的
`SqlDmlCommitOutcomeUnknownException`，且不会在该路径执行假设“尚未提交”的补偿回滚。
Worker 将该异常映射为 `UNKNOWN_REQUIRES_HANDOFF`，M05 持久化并审计未知状态，且相同执行请求不会重放。
外部响应只包含稳定代码和人工接管所需信息，不泄漏 JDBC 内部异常或执行结果。

### 2.2 Worker 信封重放防护

完成。新增基于持久目录和原子 `CREATE_NEW` 标记的重放防护，以
`executionRequestId` 的 SHA-256 摘要作为标记名。标记在任何数据库访问之前消费；无法建立或写入重放状态时关闭失败。
启动时验证目录可写，运行时写入失败也关闭失败。并发请求以及使用同一持久目录的不同 Guard 实例只能有一个请求进入执行路径。
签名和传输认证仍在 Controller 边界先行校验，未认证请求不会消费执行 ID。

能力检查顺序已调整为：静态策略校验、无需数据库访问的写能力配置校验、重放消费、需要连接的能力探测、数据库执行。
因此写能力关闭时稳定返回 `SQL_DML_WORKER_DISABLED`，不会被重放状态不可用覆盖。

### 2.3 浏览器幂等性

完成。操作台按一次未变化的用户提交上下文缓存幂等键。预检、提交和网络丢失或未知响应后的人工重试复用同一键；
SQL、连接、环境、Schema 或限制等提交上下文发生实质变化时生成新键；`SUCCEEDED`、`FAILED`、`REJECTED` 等终态完成后清除旧键。
`UNKNOWN_REQUIRES_HANDOFF` 保留原键并阻止对同一上下文再次提交。

### 2.4 人工接管结果贯通

完成。M05 在未知提交结果下返回强类型 `UNKNOWN_REQUIRES_HANDOFF` 执行响应，并携带 `workflowId` 经过 M09 Controller 到达操作台。
响应使用稳定代码 `SQL_DML_RESULT_UNKNOWN`，不暴露内部错误、SQL 结果或受影响行数。
重复请求返回已持久化的人工接管结果，不重新调用 Worker。现有前端仅人工接管视图可以由该响应到达。

### 2.5 DML 写身份隔离

完成。`WorkerSqlConnectionDescriptor` 在保留非空校验的基础上，拒绝 DML 写连接别名与对应读连接别名相同，
也拒绝 DML 写用户名与读用户名相同。比较在去除首尾空白后不区分大小写。

### 2.6 中文受控 DML 运行手册

完成。运行手册补充 Worker 传输认证的控制面和 Worker 双侧配置、启用方式、环境变量注入、已签名成功验证、未签名拒绝验证，
并明确即使使用回环地址也不得关闭认证。手册同时记录持久重放目录要求、多副本共享原子命名空间约束、默认关闭配置和非生产限制。

## 3. TDD 红灯证据

1. JDBC 未知提交结果：聚焦执行器测试初次运行共 27 项，2 项失败。提交异常仍被映射为
   `controlled JDBC DML failed`，Worker 仍返回 `FAILED`，证明旧实现无法表达不确定结果。
2. Worker 重放：初次运行共 22 项，2 项失败；同一请求第二次执行仍为 `SUCCEEDED`，并发测试中两个请求均成功。
   能力顺序测试还证明数据库在重放防护前被访问。后续顺序回归测试共 38 项，1 项失败：关闭写能力时错误返回
   `SQL_DML_REPLAY_STATE_UNAVAILABLE`，而不是要求的 `SQL_DML_WORKER_DISABLED`。
3. 浏览器幂等性：`SqlWorkbenchPage.test.jsx` 初次运行共 40 项，2 项失败；网络重试生成了不同键，未知结果后提交按钮仍可用。
4. 人工接管贯通：M05 聚焦测试初次运行共 18 项，6 项错误；服务抛出 `WorkflowException`，未返回强类型人工接管响应。
5. 身份隔离：描述符聚焦测试初次运行共 5 项，2 项失败；相同读写别名和用户名均未抛出异常。
6. 运行手册：评审检查确认原文缺少 Worker 传输认证启用和验证步骤；该项为文档缺口，不存在可执行红灯测试。

## 4. 绿灯与验证证据

### 4.1 Worker 聚焦套件

```powershell
.\mvnw.cmd -am -pl execution-worker-sqlworkbench "-Dtest=JdbcSqlQueryExecutorTest,RestrictedSqlQueryExecutionWorkerTest,FileSqlDmlExecutionReplayGuardTest,SqlQueryExecutionControllerTest,SqlWorkbenchWorkerConfigurationTest,WorkerSqlConnectionDescriptorTest,ConfiguredSqlDataSourceRegistryTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

结果：66 项测试通过，0 失败，0 错误，0 跳过。

### 4.2 M05/M09 聚焦套件

```powershell
.\mvnw.cmd -am -pl control-plane/bootstrap "-Dtest=ControlledSqlDmlWorkflowServiceTest,DefaultSqlWorkbenchServiceTest,SqlWorkbenchControllerTest,ControlledSqlDmlEndToEndTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

结果：65 项测试通过，0 失败，0 错误，0 跳过。其中工作流 18 项、SQL 服务 31 项、Controller 与端到端边界 16 项。

### 4.3 操作台聚焦套件

```powershell
npm test -- SqlWorkbenchPage.test.jsx
```

结果：1 个测试文件、40 项测试全部通过。

### 4.4 最终定向回归

```powershell
.\mvnw.cmd -am -pl control-plane/bootstrap "-Dtest=FileSqlDmlExecutionReplayGuardTest,ControlledSqlDmlEndToEndTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

结果：9 项测试通过，0 失败，0 错误，0 跳过。其中重放 Guard 3 项、受控 DML 端到端 6 项。

### 4.5 后端全量干净 Reactor

```powershell
.\mvnw.cmd clean verify
```

结果：17 个 Maven 模块全部构建成功；聚合 Surefire XML 为 123 个报告、577 项测试，0 失败，0 错误，0 跳过。

### 4.6 前端完整检查

```powershell
npm run build
```

首次运行在 ESLint 阶段发现 `SqlWorkbenchPage.jsx` 的未使用导入 `validateSqlQuery`，已删除后重跑。
最终结果：`check` 通过、ESLint 通过、29 个测试文件共 361 项测试全部通过、Vite 生产构建通过。

### 4.7 差异检查

```powershell
git diff --check
git diff --cached --check
```

工作区差异检查已通过；暂存差异检查在提交前执行并要求零输出、退出码 0。

## 5. 变更文件

### M05/M09 控制面

- `backend/control-plane/modules/workflow/src/main/java/com/company/opsagent/controlplane/modules/workflow/ControlledSqlDmlWorkflowService.java`
- `backend/control-plane/modules/workflow/src/test/java/com/company/opsagent/controlplane/modules/workflow/ControlledSqlDmlWorkflowServiceTest.java`
- `backend/control-plane/modules/sqlworkbench/src/test/java/com/company/opsagent/controlplane/modules/sqlworkbench/DefaultSqlWorkbenchServiceTest.java`
- `backend/control-plane/bootstrap/src/test/java/com/company/opsagent/controlplane/bootstrap/ControlledSqlDmlEndToEndTest.java`
- `backend/control-plane/bootstrap/src/test/java/com/company/opsagent/controlplane/bootstrap/api/SqlWorkbenchControllerTest.java`

### M07 Worker

- `backend/execution-worker-sqlworkbench/src/main/java/com/company/opsagent/executionworker/sqlworkbench/ConfiguredSqlDataSourceRegistry.java`
- `backend/execution-worker-sqlworkbench/src/main/java/com/company/opsagent/executionworker/sqlworkbench/FileSqlDmlExecutionReplayGuard.java`
- `backend/execution-worker-sqlworkbench/src/main/java/com/company/opsagent/executionworker/sqlworkbench/JdbcSqlQueryExecutor.java`
- `backend/execution-worker-sqlworkbench/src/main/java/com/company/opsagent/executionworker/sqlworkbench/RestrictedSqlQueryExecutionWorker.java`
- `backend/execution-worker-sqlworkbench/src/main/java/com/company/opsagent/executionworker/sqlworkbench/SqlDmlCommitOutcomeUnknownException.java`
- `backend/execution-worker-sqlworkbench/src/main/java/com/company/opsagent/executionworker/sqlworkbench/SqlDmlExecutionReplayGuard.java`
- `backend/execution-worker-sqlworkbench/src/main/java/com/company/opsagent/executionworker/sqlworkbench/SqlDmlReplayStateException.java`
- `backend/execution-worker-sqlworkbench/src/main/java/com/company/opsagent/executionworker/sqlworkbench/SqlDmlWriteCapabilityValidator.java`
- `backend/execution-worker-sqlworkbench/src/main/java/com/company/opsagent/executionworker/sqlworkbench/SqlWorkbenchWorkerConfiguration.java`
- `backend/execution-worker-sqlworkbench/src/main/java/com/company/opsagent/executionworker/sqlworkbench/WorkerSqlConnectionDescriptor.java`
- `backend/execution-worker-sqlworkbench/src/main/java/com/company/opsagent/executionworker/sqlworkbench/WorkerSqlDmlReplayProperties.java`
- `backend/execution-worker-sqlworkbench/src/test/java/com/company/opsagent/executionworker/sqlworkbench/FileSqlDmlExecutionReplayGuardTest.java`
- `backend/execution-worker-sqlworkbench/src/test/java/com/company/opsagent/executionworker/sqlworkbench/JdbcSqlQueryExecutorTest.java`
- `backend/execution-worker-sqlworkbench/src/test/java/com/company/opsagent/executionworker/sqlworkbench/RestrictedSqlQueryExecutionWorkerTest.java`
- `backend/execution-worker-sqlworkbench/src/test/java/com/company/opsagent/executionworker/sqlworkbench/SqlQueryExecutionControllerTest.java`
- `backend/execution-worker-sqlworkbench/src/test/java/com/company/opsagent/executionworker/sqlworkbench/SqlWorkbenchWorkerConfigurationTest.java`
- `backend/execution-worker-sqlworkbench/src/test/java/com/company/opsagent/executionworker/sqlworkbench/WorkerSqlConnectionDescriptorTest.java`
- `backend/execution-worker/src/main/resources/application-demo.yaml`

### 操作台与运行手册

- `frontend/operator-console/src/features/sql-workbench/SqlWorkbenchPage.jsx`
- `frontend/operator-console/src/features/sql-workbench/SqlWorkbenchPage.test.jsx`
- `docs/runbooks/sql-workbench-controlled-dml.md`
- `.superpowers/sdd/final-review-fixes.md`

## 6. 差异评审结论

- 未发现会绕过策略、签名、传输认证、审批、审计、幂等或隔离控制的新增路径。
- 未发现未知执行结果的自动重试路径；未知结果只能进入人工接管。
- 未发现生产写能力被默认启用；基础配置和 demo 配置继续保持关闭。
- 敏感信息模式检查仅命中正常的密码提供器调用，未发现密钥或凭据被提交。
- 变更聚焦于指定模块及测试，没有回退或覆盖无关工作。

## 7. 关注事项

1. 文件型重放状态要求部署提供持久、可写目录。多 Worker 副本必须共享一个支持原子 `CREATE_NEW` 语义的命名空间；否则应保持单副本。运行手册已将其列为启用前置条件。
2. `UNKNOWN_REQUIRES_HANDOFF` 是不可自动恢复终态。值班人员必须按 `workflowId` 核实目标数据库事实并完成人工接管，不能重新提交同一执行请求。
3. 前端完整构建仍输出既有的 Node `--localstorage-file` 路径警告和 Vite 大于 500 kB 的 chunk 警告；两者均不导致测试、静态检查或构建失败，也不属于本次安全修复范围。
4. 后端测试会输出 Mockito 动态 Agent 提示；测试通过，提示不影响本次结果，但后续 Java 工具链升级时应统一处理测试 Agent 配置。

## 8. 提交信封重试补充修复

### 8.1 修复内容

补齐操作台丢失提交响应后的 DML 重试语义。每个未变化的提交上下文现在在首次 `COMMIT_DML`
请求发出前缓存完整、强类型的提交信封：查询和幂等键、服务端签发的不可解释预检回执、以及确认中的
SQL 哈希与风险列表。网络响应丢失后的同一上下文重试直接提交该缓存信封，不会再次调用预检接口，
也不会使用可能已变化的数据库影响预览、回执、SQL 哈希或风险重新构造请求。

缓存只在提交上下文发生实质变化时被替换，或收到已确认的 `SUCCEEDED`、`FAILED`、`REJECTED`、
`EXPIRED` 终态结果后清除。`QUEUED`、`RUNNING` 和 `UNKNOWN_REQUIRES_HANDOFF` 不会清除该信封；
其中未知结果仍由操作台禁用同一 SQL 的再次提交，不会形成自动重试路径。

### 8.2 TDD 红灯证据

1. 新增“丢失提交响应”页面测试后运行 `npm test -- SqlWorkbenchPage.test.jsx`：40 项中 1 项失败。
   第二次点击因再次调用预检而被测试 Worker 拒绝，断言期望第二个 `COMMIT_DML` 请求但实际只有 1 个。
   这证明旧实现仅复用幂等键，未缓存提交信封。
2. 新增“非终态结果保留信封”页面测试后运行同一命令：41 项中 1 项失败。
   `RUNNING` 响应错误地清除了缓存，第二次点击再次进入被拒绝的预检，仍只有 1 个提交请求。

### 8.3 绿灯与检查证据

```powershell
npm test -- SqlWorkbenchPage.test.jsx
npm run check
git diff --check
git diff --cached --check
```

结果：页面测试 1 个文件、41 项全部通过；`checkJs` 静态检查通过。首个新测试捕获两次 HTTP 请求的原始
字节串，验证第二次 `POST /commit` 与首次字节完全一致、预检仅调用一次，并渲染原始
`UNKNOWN_REQUIRES_HANDOFF` 的 `workflowId`。第二个测试验证非终态响应保留同一信封。
工作区与暂存差异检查均要求零输出、退出码 0。

### 8.4 变更范围与提交

- `frontend/operator-console/src/features/sql-workbench/SqlWorkbenchPage.jsx`
- `frontend/operator-console/src/features/sql-workbench/SqlWorkbenchPage.test.jsx`
- `.superpowers/sdd/final-review-fixes.md`

基线提交：`e76caeef5a1c9037bd33b6eec995a30968908c8d`。
本补充修复的提交标题为 `Cache controlled DML retry envelopes`；提交哈希在提交完成后的交付结果中记录，
以避免提交内容自引用尚未生成的哈希。
