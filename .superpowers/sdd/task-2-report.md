# Task 2：默认拒绝的服务端 DML 策略与静态分析报告

## 状态

DONE。实现提交为 `47f212ca`（`Add controlled SQL DML policy gate`）。

## 实现范围

- 新增 `ControlledSqlDmlProperties`：默认 `enabledEnvironments` 和规则均为空；启用环境额外受 `dev`、`sit`、`uat` 硬限制，生产环境即使误配也不会启用。
- 新增 `CalciteSqlDmlAnalysis`：仅分析单条 `INSERT`、`UPDATE`、`DELETE`；拒绝多语句、DDL、`INSERT ... SELECT`、目标或源子查询、未知谓词运算符、复杂谓词列和非静态赋值表达式。`UPDATE`、`DELETE` 无 `WHERE` 仍由既有验证报告标记为风险。
- 新增 `ControlledSqlDmlPolicy`：在重新静态分析、环境限制和连接、Schema、表、语句类型、变更列、谓词列、运算符完全匹配后，才返回 Task 1 的 `SqlDmlPreviewSelection`。预览列和掩码列完全由规则配置解析；掩码子集由契约在启动时校验。
- 更新 Calcite 验证服务，使不在受控静态子集内的 DML 返回拒绝报告；新增 bootstrap 配置绑定和默认空配置。
- 没有改动 contracts、Worker、workflow 或前端代码；实现中不记录、返回或审计原始 SQL。

## 文件变更

- `backend/control-plane/modules/sqlworkbench/src/main/java/com/company/opsagent/controlplane/modules/sqlworkbench/ControlledSqlDmlPolicy.java`
- `backend/control-plane/modules/sqlworkbench/src/main/java/com/company/opsagent/controlplane/modules/sqlworkbench/ControlledSqlDmlProperties.java`
- `backend/control-plane/modules/sqlworkbench/src/main/java/com/company/opsagent/controlplane/modules/sqlworkbench/CalciteSqlDmlAnalysis.java`
- `backend/control-plane/modules/sqlworkbench/src/main/java/com/company/opsagent/controlplane/modules/sqlworkbench/CalciteSqlValidationService.java`
- `backend/control-plane/bootstrap/src/main/java/com/company/opsagent/controlplane/bootstrap/config/SqlWorkbenchConfiguration.java`
- `backend/control-plane/bootstrap/src/main/resources/application.yaml`
- `backend/control-plane/modules/sqlworkbench/src/test/java/com/company/opsagent/controlplane/modules/sqlworkbench/ControlledSqlDmlPolicyTest.java`
- `backend/control-plane/modules/sqlworkbench/src/test/java/com/company/opsagent/controlplane/modules/sqlworkbench/CalciteSqlValidationServiceTest.java`

## TDD 证据

基线：

```powershell
cd backend
.\mvnw.cmd -pl control-plane/modules/sqlworkbench test
```

结果：通过，31 项测试，0 失败。

RED：

```powershell
cd backend
.\mvnw.cmd -pl control-plane/modules/sqlworkbench -am '-Dtest=ControlledSqlDmlPolicyTest,CalciteSqlValidationServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

结果：失败；reactor 已构建 Task 1 contracts，SQL workbench 测试编译明确报告缺少 `ControlledSqlDmlPolicy`、`ControlledSqlDmlProperties` 和 `CalciteSqlDmlAnalysis`。

GREEN：

```powershell
cd backend
.\mvnw.cmd -pl control-plane/modules/sqlworkbench -am '-Dtest=ControlledSqlDmlPolicyTest,CalciteSqlValidationServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

结果：通过，13 项目标测试，0 失败。调试中确认 Calcite 1.42 会在 AST 生成前拒绝 `UPDATE (SELECT ...)`，稳定理由为 `SQL syntax is not supported`；测试据此验证 fail-closed 行为，而不是要求不可达的内部 AST 拒绝消息。

## 完整验证

```powershell
cd backend
.\mvnw.cmd -pl control-plane/modules/sqlworkbench -am test
```

结果：通过。contracts 61 项测试、SQL workbench 41 项测试，均为 0 失败、0 错误、0 跳过。

```powershell
cd backend
.\mvnw.cmd -pl control-plane/bootstrap -am '-Dtest=ControlPlaneApplicationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

结果：通过。Spring Boot 默认配置上下文成功启动，33 项测试，0 失败、0 错误、0 跳过。

## 自查

- `git diff --check` 和 `git diff --cached --check` 均无输出。
- 默认配置为 `enabled-environments: []`、`rules: []`；显式测试覆盖空配置拒绝和生产误配拒绝。
- 规则匹配比较连接、Schema、表、语句类型、变更列、谓词列和运算符；任何不匹配均返回 `SQL_DML_POLICY_DENIED`。
- 策略在返回预览选择前重新解析请求 SQL，拒绝已拒绝的验证报告和静态分析不一致的语句；没有从展示文本推断安全状态。
- 新增代码仅在请求处理时将 `request.sql()` 交给 Calcite 分析，不将其写入日志、策略输出或审计载荷。

## 关注项

无阻塞关注项。当前任务按指定文件边界提供并装配了独立的 M02 策略 Bean；既有 `DefaultSqlWorkbenchService` 尚未存在 Task 1 的 DML preflight 工作流入口，因此其消费应由后续 M05/Worker 预检编排任务接入，不能在本任务中绕过持久化工作流或 Worker 隔离。
