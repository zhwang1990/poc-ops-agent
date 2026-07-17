# Task 7 TDD 与验收报告

- 日期：2026-07-17
- 模块：M02、M05、M07、M09、M10、M11
- 范围：受控 SQL DML 配置、中文运行手册与规划追溯、`sit` / `h2-local-test` H2 端到端验收

## 1. 交付内容

- 控制面 demo 配置仅为 `sit` 启用受控 DML，仅允许 `h2-local-test` 的 `PUBLIC.ORDERS` 表执行明确列和谓词约束下的 `INSERT`、`UPDATE`、`DELETE`。
- 控制面使用数据库审计主存储，配置显式回执 key ID，并仅通过 `OPS_AGENT_SQL_DML_RECEIPT_HMAC_SECRET` 环境占位注入签名密钥。
- Worker 仅为 `h2-local-test` 启用 DML，使用与只读别名分离的 `h2-local-dml-writer` 写凭据别名和独立数据库用户名；其他默认配置保持关闭。
- 新增 H2 E2E，直接装配控制面策略、v1.1 服务端预检回执、R2DBC 工作流和数据库审计，以及真实 Worker SQL 注册表、预览与写执行路径。
- 新增中文运行手册，覆盖启用、验证、停用、撤销 Worker 写凭据、生产拒绝、回执签名前提、`UNKNOWN_REQUIRES_HANDOFF` 和回滚。
- 更新项目计划和设计追溯，关闭受控 SQL DML 实施计划任务 5 的规划追溯，并将交付状态限定为 `sit` / H2 试点切片；`dev` / `uat` 仍待推广，生产仍只读。

## 2. TDD 记录

### 2.1 RED

首次从仓库根目录调用 `.\mvnw.cmd` 失败，因为 Maven Wrapper 位于 `backend`；该失败属于执行路径问题，没有进入编译或测试。随后从 `backend` 仅使用 `-pl` 时命中了本地仓库中的旧 SNAPSHOT，出现 v1.1 API 缺失编译错误；改用 reactor 的 `-am` 后消除了该环境问题。

有效 RED 命令：

```powershell
.\mvnw.cmd -am -pl control-plane/bootstrap "-Dtest=ControlledSqlDmlEndToEndTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

有效 RED 执行了 5 项 E2E 测试并使构建失败。失败证据为 demo 配置仍返回空的 `enabled-environments`，且 v1.1 回执签名器未配置；因此成功路径无法签发服务端回执。这证明测试在配置实现前能够识别缺失能力，而不是先有实现后补断言。

### 2.2 GREEN

完成配置后使用同一 reactor 命令重跑：

- `ControlledSqlDmlEndToEndTest`：5 项通过，0 失败，0 错误，0 跳过。
- `INSERT`、`UPDATE`、`DELETE` 每类均使用同一幂等键提交两次，目标写入和 Worker 调度均只发生一次。
- 生产环境、控制面能力关闭、控制面环境配置关闭、Worker DML 关闭、篡改或过期回执、不匹配确认均在写入前拒绝，Worker 调度保持为零。
- 未知结果在 Worker 已写入后模拟响应丢失；再次提交保持 `UNKNOWN_REQUIRES_HANDOFF`，Worker 只调度一次且数据库只保留一次写入。
- HMAC 回执密钥和测试 Writer KeyStore 材料均在测试进程中用 `SecureRandom` 运行时生成，未写入文件。

## 3. 聚焦验证

命令：

```powershell
.\mvnw.cmd -am -pl control-plane/bootstrap,execution-worker-sqlworkbench "-Dtest=ControlledSqlDmlEndToEndTest,ConfiguredSqlDataSourceRegistryTest,WorkerSqlEgressPropertiesTest,DefaultApplicationConfigurationTest,SqlWorkbenchConfigurationTest,SecurityConfigurationAuditStorageTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

结果：BUILD SUCCESS，共 21 项测试通过，0 失败，0 错误，0 跳过。其中 Worker 数据源和 DML 配置测试 11 项，控制面配置与 H2 E2E 测试 10 项。

## 4. 全量验证

### 4.1 后端

工作目录：`backend`

```powershell
.\mvnw.cmd test
```

结果：17 个 reactor 模块全部 SUCCESS；Surefire 129 个测试报告，共 578 项测试通过，0 失败，0 错误，0 跳过；总耗时 1 分 49 秒。

### 4.2 前端

```powershell
npm --prefix frontend/operator-console run check
npm --prefix frontend/operator-console test -- --run
```

结果：TypeScript `checkJs` 检查通过；Vitest 29 个测试文件、358 项测试全部通过，0 失败，总耗时 22.75 秒。

### 4.3 差异与敏感信息

```powershell
git diff --check
```

结果：通过，无空白错误。

对本次变更的配置、文档和 E2E 测试执行了聚焦扫描：私钥头、AWS Key、GitHub Token 和常见 API Key 高熵模式均无命中。新增敏感词引用仅包括环境变量占位、凭据别名和运行时生成变量；没有新增 HMAC 密钥、数据库凭据或口令值。`application-demo.yaml` 中存在任务开始前已有的固定 demo 身份种子口令，并由既有 demo 脚本和测试约束；本任务未新增、复制或修改该值，以避免破坏无关的既有演示契约。

## 5. 已知事项

- Maven 输出包含既有的 SLF4J provider、Mockito 动态 agent 和 Commons Logging classpath 警告，未造成编译或测试失败；Mockito 动态加载需要在未来 JDK 升级前统一处理。
- 前端测试输出包含既有的无效 `--localstorage-file` 路径警告，未造成测试失败。
- 用户指定的 `C:\Users\Lenovo\Documents\ops-agent` 事实源路径在当前环境不存在；实现使用当前工作树内同名 `AGENTS.md`、模块图和规划文档，并遵循用户消息中提供的全局规则。
- 本次只交付 `sit` / `h2-local-test` 切片。`dev` / `uat` 真实数据库、外部 KeyStore/密钥系统联调、安全评审和环境演练仍是后续工作；生产写执行保持禁止。
