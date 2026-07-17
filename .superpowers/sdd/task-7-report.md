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

## 6. Review 阻塞项修复记录

本节追加于原始 Task 7 提交后的 review 修复，并以本节结果覆盖第 4.3 节关于既有 demo 身份种子口令未修改的历史说明。

### 6.1 RED

1. 基础 Worker DML 默认关闭测试：

```powershell
.\mvnw.cmd -am -pl control-plane/bootstrap "-Dtest=ControlledSqlDmlEndToEndTest#baseConfigurationsKeepDmlDisabledAndDemoProfilesActivateOnlyTheSitH2Slice" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

结果：1 项测试执行，0 错误、1 失败；`dml-enabled` 实际为 `true`，断言期望 `false`。失败定位到 Worker 基础 `application.yaml`。

2. 必需 demo identity seed 注入测试：

```powershell
.\mvnw.cmd -am -pl control-plane/bootstrap "-Dtest=DemoIdentityBootstrapConfigurationTest#demoProfileKeepsLocalBuiltInIdentityAndLoopbackWorker" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

结果：1 项测试执行，0 错误、1 失败；配置实际为固定字符串，断言期望无默认值的 `${OPS_AGENT_DEMO_ADMIN_PASSWORD}`。

3. demo 启动器自检：`cmd /c tools\demo\test-demo-scripts.cmd` 在新增的签名密钥要求下失败，并输出 `start-demo.cmd must require the DML receipt signing secret`。

### 6.2 GREEN

- Worker 基础 `application.yaml` 显式 `dml-enabled: false`，不含 DML 写凭据别名或用户名。
- 新增 Worker `application-demo.yaml`，仅为 `sit` / `h2-local-test` 配置 `dml-enabled: true`、独立 `h2-local-dml-writer` 别名和最小权限用户名。H2 E2E 显式加载该 profile；两个 demo 启动器也仅在 demo 路径激活该 profile。
- 控制面 `application-demo.yaml` 使用无默认值的 `${OPS_AGENT_DEMO_ADMIN_PASSWORD}`。`DemoIdentityBootstrapConfigurationTest` 的所有测试口令改由 `SecureRandom` 在运行时生成。
- `start-demo.cmd` 与 `start-backend-jars.cmd` 都要求 `OPS_AGENT_DEMO_ADMIN_PASSWORD` 和 `OPS_AGENT_SQL_DML_RECEIPT_HMAC_SECRET` 注入，且不显示口令值。
- 中文 SQL DML、demo 启动器和 backend-only 打包文档已同步要求一次性环境注入和 Worker demo profile。

聚焦 GREEN 命令：

```powershell
.\mvnw.cmd -am -pl control-plane/bootstrap,execution-worker-sqlworkbench "-Dtest=ControlledSqlDmlEndToEndTest,DemoIdentityBootstrapConfigurationTest,ConfiguredSqlDataSourceRegistryTest,WorkerSqlEgressPropertiesTest,DefaultApplicationConfigurationTest,SqlWorkbenchConfigurationTest,SecurityConfigurationAuditStorageTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
cmd /c tools\demo\test-demo-scripts.cmd
```

结果：Maven 24 项测试通过，0 失败、0 错误、0 跳过；demo 启动器自检通过。

### 6.3 最终验证

- 后端：`backend` 工作目录执行 `.\mvnw.cmd test`，17 个 reactor 模块全部 SUCCESS；Surefire 129 个报告、578 项测试通过，0 失败、0 错误、0 跳过；耗时 1 分 49 秒。
- 前端：`npm --prefix frontend/operator-console run check` 通过；`npm --prefix frontend/operator-console test -- --run` 为 29 个测试文件、358 项测试通过，0 失败，耗时 21.42 秒。
- `git diff --check` 通过。
- `tools/ci/scan-secrets.ps1` 通过；对 Task 7 修改的配置、测试、运行手册和 demo 启动器检索固定 demo 口令无命中。新增敏感词引用仅为环境变量名、凭据别名或运行时生成变量，没有提交 HMAC 密钥、数据库凭据或口令值。

## 7. 签名密钥与 Demo 密钥复审修复记录

### 7.1 RED

在移除默认值前，从 `backend` 执行以下聚焦命令：

```powershell
.\mvnw.cmd -pl control-plane/bootstrap -am "-Dtest=ControlledSqlDmlEndToEndTest,SkillRegistryBootstrapConfigurationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

结果：8 项测试中 3 项按预期失败。失败覆盖两个活动控制面配置仍接受固定 Skill 注册表签名材料、`SkillRegistryProperties` 仍提供签名密钥默认值，以及 `demo` 配置仍允许 DML 回执密钥缺失时解析为空值。随后为 demo 启动器新增 Skill 注册表密钥断言，`cmd /c tools\demo\test-demo-scripts.cmd` 按预期失败，指出启动器尚未要求该注入变量。

### 7.2 GREEN

- 控制面基础和 OIDC 示例配置均改为必需的 `OPS_AGENT_SKILL_REGISTRY_SIGNING_SECRET` 占位符，Java 属性不再提供默认签名密钥。
- `application-demo.yaml` 的 `OPS_AGENT_SQL_DML_RECEIPT_HMAC_SECRET` 移除了空默认值；新增配置测试证明缺失时无法解析。
- 所有控制面 `@SpringBootTest` 上下文从进程内生成随机签名材料，并对临时复制的 Skill fixture 重新签名；测试不再包含固定值。
- Skill 打包、合同检查和两个 demo 启动器都要求安全注入的签名密钥；打包工具测试先验证缺失注入被拒绝，再使用运行时生成材料生成制品。
- 历史 M03 计划删除固定签名材料；运行手册、打包和 demo 文档明确三个 demo 注入变量均无默认值。

聚焦 GREEN 命令与结果：

```powershell
.\mvnw.cmd -pl control-plane/bootstrap -am "-Dtest=ControlledSqlDmlEndToEndTest,SkillRegistryBootstrapConfigurationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
cmd /c tools\demo\test-demo-scripts.cmd
powershell -NoProfile -ExecutionPolicy Bypass -File tools\skills\test-skill-package-tool.ps1
```

结果：Maven 8 项测试全部通过；demo 启动器自检通过；Skill 打包工具测试通过。

### 7.3 最终验证

- `backend`：`.\mvnw.cmd test` 成功，17 个 reactor 项目全部成功，130 份 Surefire 报告共 581 项测试全部通过，耗时 1 分 52 秒。
- 前端：`npm --prefix frontend/operator-console run check` 成功；`npm --prefix frontend/operator-console test -- --run` 成功，29 个测试文件、358 项测试全部通过。
- 变更文件聚焦密钥扫描全部通过：旧 Skill 签名材料、固定 demo 密码形态、DML 回执空默认值和非占位符 Skill YAML 签名值均无命中。
- `git diff --check` 通过；仅有 Git 对既有 PowerShell/CMD 行尾归一化的提示，无空白错误。
