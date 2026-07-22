# SQL 工作台非生产受控 CRUD 实施计划

> **供 Agent 执行：** 必须使用 `superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans` 逐任务执行。步骤使用复选框追踪。

**目标：** 在 `dev`、`sit`、`uat` 为 SQL 工作台提供带影响预览、策略、持久化工作流、审计和 Worker 短事务的受控 `INSERT`、`UPDATE`、`DELETE`。

**架构：** M09 负责预检、确认和结果展示；M05 先持久化 DML 工作流、幂等绑定和语义事件，再经 M07 端口提交 Worker。Worker 使用独立的 DML 功能开关和写凭据，再次校验受控 DML 子集并在单次 JDBC 事务中执行。M02 的 `AuditTrail` 记录整个链路，原始参数和 SQL 不写入审计记录。

**技术栈：** Java 21、Spring Boot WebFlux、R2DBC/H2、JDBC、Apache Calcite、React/JSX、Zod、Vitest、JUnit 5。

## 全局约束

- 仅允许 `dev`、`sit`、`uat` 写执行；生产环境不得暴露 DML 预检或提交能力。
- 控制面不直接使用目标数据库长期凭据，只能向已授权 Worker 发送受签名、带过期时间的请求。
- 所有 DML 必须先持久化工作流和幂等绑定；结果未知时不得自动重放，必须进入人工接管状态。
- 无 `WHERE` 的 `UPDATE`、`DELETE` 必须使用与 SQL 哈希绑定的二次确认。
- 新增跨模块数据采用 `backend/contracts` 的版本化强类型契约；不得使用无约束 `Map<String, Object>`。
- 审计、日志和事件只能保存连接、环境、表、哈希、风险、工作流标识、操作者和影响行数，不能保存原始参数、凭据或敏感结果。
- 继续使用现有模块和部署单元，不新增服务或模块编号。

---

## 文件结构

| 路径 | 责任 |
|---|---|
| `backend/contracts/.../SqlDmlImpactPreview.java` | 版本化、脱敏的 DML 影响预览结果。 |
| `backend/contracts/.../SqlDmlPreflightResult.java` | 静态校验报告与 Worker 影响预览的强类型响应。 |
| `backend/contracts/.../SqlDmlPreflightExecutionRequest.java` | 控制面到 Worker 的受签名只读预览请求。 |
| `backend/contracts/.../SqlControlledDmlExecutionRequest.java` | 控制面到 Worker 的受签名 DML 提交请求，不改变既有只读 v1 请求。 |
| `backend/contracts/.../SqlDmlExecutionBinding.java` | SQL、参数、策略、确认和预检的哈希绑定。 |
| `backend/control-plane/modules/sqlworkbench/.../ControlledSqlDmlPolicy.java` | 按环境、连接、Schema、表、字段与运算符执行服务端 DML 策略。 |
| `backend/control-plane/modules/workflow/.../ControlledSqlDmlWorkflowService.java` | 创建、去重、审计、提交和终结 DML 工作流。 |
| `backend/control-plane/modules/workflow/.../R2dbcControlledSqlDmlWorkflowStore.java` | DML 工作流和尝试记录的关系型事实源。 |
| `backend/execution-worker-sqlworkbench/.../JdbcSqlDmlImpactPreviewExecutor.java` | 用只读语句执行影响计数和受限样本预览。 |
| `frontend/operator-console/src/features/sql-workbench/SqlWorkbenchPage.jsx` | 消费真实预检结果、开关后的连接能力和执行状态。 |

## Task 1：定义预检、绑定和 Worker 传输契约

**文件：**

- 新建：`backend/contracts/src/main/java/com/company/opsagent/contracts/sqlworkbench/SqlDmlImpactPreview.java`
- 新建：`backend/contracts/src/main/java/com/company/opsagent/contracts/sqlworkbench/SqlDmlPreflightResult.java`
- 新建：`backend/contracts/src/main/java/com/company/opsagent/contracts/sqlworkbench/SqlDmlPreviewSelection.java`
- 新建：`backend/contracts/src/main/java/com/company/opsagent/contracts/sqlworkbench/SqlDmlPreflightExecutionRequest.java`
- 新建：`backend/contracts/src/main/java/com/company/opsagent/contracts/sqlworkbench/SqlDmlExecutionBinding.java`
- 新建：`backend/contracts/src/main/java/com/company/opsagent/contracts/sqlworkbench/SqlControlledDmlExecutionRequest.java`
- 新建：`backend/contracts/sqlworkbench/sql-dml-preflight-result-v1.schema.json`
- 修改：`backend/contracts/src/main/java/com/company/opsagent/contracts/workflow/WorkerRequestSignature.java`
- 测试：`backend/contracts/src/test/java/com/company/opsagent/contracts/sqlworkbench/SqlDmlPreflightResultTest.java`
- 测试：`backend/contracts/src/test/java/com/company/opsagent/contracts/workflow/WorkerRequestSignatureTest.java`

**接口：**

- `SqlDmlPreflightResult(String contractVersion, SqlValidationReport validation, SqlDmlImpactPreview impactPreview)`。
- `SqlDmlPreflightExecutionRequest` 只接受 `PREFLIGHT_DML`，携带查询、校验哈希、由策略解析出的 `SqlDmlPreviewSelection`、操作者、策略、trace 和过期时间。
- `SqlDmlExecutionBinding(String bindingHash, String parametersHash, String preflightHash, String confirmationHash)`。
- `SqlControlledDmlExecutionRequest` 只接受 `COMMIT_DML`，携带 `SqlDmlExecutionBinding`；既有只读 `SqlQueryExecutionRequest` v1 保持不变。

- [ ] **步骤 1：写失败测试**

~~~java
@Test
void controlledDmlExecutionRequiresBinding() {
  assertThrows(IllegalArgumentException.class, () -> new SqlControlledDmlExecutionRequest(
      "1.0", "execution-1", "workflow-1", commitRequest(), null,
      operator(), policy(), trace(), expiresAt()));
}

@Test
void updatePreflightRequiresImpactPreview() {
  assertThrows(IllegalArgumentException.class, () ->
      new SqlDmlPreflightResult("1.0", updateValidation(), null));
}
~~~

- [ ] **步骤 2：确认失败**

运行：`./mvnw -pl backend/contracts -Dtest=SqlDmlPreflightResultTest,WorkerRequestSignatureTest test`

预期：失败，提示预览或绑定类型不存在。

- [ ] **步骤 3：实现最小契约和签名绑定**

~~~java
public record SqlDmlExecutionBinding(
    String bindingHash, String parametersHash, String preflightHash, String confirmationHash) {
  public SqlDmlExecutionBinding {
    bindingHash = requireHash(bindingHash, "bindingHash");
    parametersHash = requireHash(parametersHash, "parametersHash");
    preflightHash = requireHash(preflightHash, "preflightHash");
    confirmationHash = requireHash(confirmationHash, "confirmationHash");
  }
}
~~~

保持 `canonicalSqlPayload` 的既有只读语义不变；新增 `canonicalControlledSqlDmlPayload`，签名 DML 提交请求及四个绑定哈希；新增 `canonicalSqlDmlPreflightPayload`，签名预检请求的查询、校验哈希、预览选列与掩码规则、操作者、策略、trace 和过期时间。

- [ ] **步骤 4：验证与提交**

运行：`./mvnw -pl backend/contracts test`

预期：通过；任一 SQL、参数、确认或策略字段变化后 binding 与 HMAC 均变化。

~~~powershell
git add backend/contracts
git commit -m "Add controlled SQL DML contracts"
~~~

## Task 2：实现默认拒绝的服务端 DML 策略和静态分析

**文件：**

- 新建：`backend/control-plane/modules/sqlworkbench/src/main/java/com/company/opsagent/controlplane/modules/sqlworkbench/ControlledSqlDmlPolicy.java`
- 新建：`backend/control-plane/modules/sqlworkbench/src/main/java/com/company/opsagent/controlplane/modules/sqlworkbench/ControlledSqlDmlProperties.java`
- 新建：`backend/control-plane/modules/sqlworkbench/src/main/java/com/company/opsagent/controlplane/modules/sqlworkbench/CalciteSqlDmlAnalysis.java`
- 修改：`backend/control-plane/modules/sqlworkbench/src/main/java/com/company/opsagent/controlplane/modules/sqlworkbench/CalciteSqlValidationService.java`
- 修改：`backend/control-plane/bootstrap/src/main/java/com/company/opsagent/controlplane/bootstrap/config/SqlWorkbenchConfiguration.java`
- 修改：`backend/control-plane/bootstrap/src/main/resources/application.yaml`
- 测试：`backend/control-plane/modules/sqlworkbench/src/test/java/com/company/opsagent/controlplane/modules/sqlworkbench/ControlledSqlDmlPolicyTest.java`
- 测试：`backend/control-plane/modules/sqlworkbench/src/test/java/com/company/opsagent/controlplane/modules/sqlworkbench/CalciteSqlValidationServiceTest.java`

**接口：**

- `ControlledSqlDmlPolicy.authorize(SqlQueryRequest request, SqlValidationReport report): SqlDmlPreviewSelection`。
- 配置前缀 `ops-agent.controlled-sql-dml`，包括 `enabled-environments` 及规则列表。
- 每条规则精确匹配 `connectionId`、`schema`、`table`、语句类型、更新字段、谓词字段和运算符。规则还必须显式配置 `previewSampleColumns` 与 `maskedPreviewColumns`；无匹配规则即拒绝，未配置预览列时不返回样本数据，掩码列必须是预览列的子集。

- [ ] **步骤 1：写失败测试**

~~~java
@Test
void rejectsPredicateColumnOutsideAllowlist() {
  var policy = policyFor("ORDERS", Set.of("STATUS"), Set.of("ORDER_ID"), Set.of("EQUALS"));
  assertThrows(SqlWorkbenchException.class, () -> policy.authorize(
      updateRequest("update ORDERS set STATUS = 'READY' where OWNER = 'ops'"), updateReport()));
}

@Test
void rejectsInsertSelectFromControlledSubset() {
  assertTrue(validation.validate(commitRequest("insert into ORDERS select * from ARCHIVE"))
      .rejectionReasons().contains("controlled INSERT requires a VALUES source"));
}
~~~

- [ ] **步骤 2：确认失败**

运行：`./mvnw -pl backend/control-plane/modules/sqlworkbench -Dtest=ControlledSqlDmlPolicyTest,CalciteSqlValidationServiceTest test`

预期：失败，提示策略和字段/运算符提取尚不存在。

- [ ] **步骤 3：实现门禁**

~~~java
public SqlDmlPreviewSelection authorize(SqlQueryRequest request, SqlValidationReport report) {
  if (!enabledEnvironments.contains(request.targetEnvironment())) {
    throw new SqlWorkbenchException("SQL_DML_DISABLED", "DML execution is disabled for the target environment");
  }
  Rule rule = findRule(request, report)
      .orElseThrow(() -> new SqlWorkbenchException("SQL_DML_POLICY_DENIED", "No matching DML policy rule"));
  analysis.inspect(request.sql()).verifyAllowedBy(rule);
  return rule.previewSelection();
}
~~~

Calcite 分析必须拒绝多语句、DDL、`INSERT ... SELECT`、子查询目标和不可识别字段或运算符。无 `WHERE` 更新或删除仍标为风险。默认配置的 `enabled-environments` 必须为空，规则默认不得返回任何样本列。

- [ ] **步骤 4：验证与提交**

运行：`./mvnw -pl backend/control-plane/modules/sqlworkbench test`

预期：通过；显式规则之外、生产环境和关闭开关均拒绝。

~~~powershell
git add backend/control-plane/modules/sqlworkbench backend/control-plane/bootstrap/src/main
git commit -m "Add controlled SQL DML policy gate"
~~~

## Task 3：建立 M05 DML 工作流、幂等和审计事实源

**文件：**

- 新建：`backend/control-plane/modules/workflow/src/main/java/com/company/opsagent/controlplane/modules/workflow/ControlledSqlDmlWorkflow.java`
- 新建：`backend/control-plane/modules/workflow/src/main/java/com/company/opsagent/controlplane/modules/workflow/ControlledSqlDmlWorkflowStore.java`
- 新建：`backend/control-plane/modules/workflow/src/main/java/com/company/opsagent/controlplane/modules/workflow/R2dbcControlledSqlDmlWorkflowStore.java`
- 新建：`backend/control-plane/modules/workflow/src/main/resources/sql/migrations/V004__controlled_sql_dml_workflow.sql`
- 修改：`backend/control-plane/bootstrap/src/main/java/com/company/opsagent/controlplane/bootstrap/config/WorkflowConfiguration.java`
- 测试：`backend/control-plane/modules/workflow/src/test/java/com/company/opsagent/controlplane/modules/workflow/R2dbcControlledSqlDmlWorkflowStoreTest.java`
- 测试：`backend/control-plane/modules/workflow/src/test/java/com/company/opsagent/controlplane/modules/workflow/ControlledSqlDmlWorkflowStoreTest.java`

**接口：**

- 状态为 `CREATED`、`RUNNING`、`SUCCEEDED`、`FAILED`、`UNKNOWN_REQUIRES_HANDOFF`，不得加入只读 replay 队列。
- `findByIdempotency(idempotencyKey, operatorId, targetEnvironment)` 返回已有工作流；binding 不同时由 M05 的 `ControlledSqlDmlWorkflowStore.IdempotencyConflictException` 抛出稳定错误码 `SQL_DML_IDEMPOTENCY_CONFLICT`。M05 不依赖 M09 的 `SqlWorkbenchException`。
- 迁移仅保存连接、Schema、语句类型、SQL/参数/预检/确认/binding 哈希、策略、trace、尝试、影响行数和安全失败码。

- [ ] **步骤 1：写失败测试**

~~~java
@Test
void reusesIdenticalIdempotencyBinding() {
  store.create(createdWorkflow("binding-a")).block();
  assertEquals("workflow-1", store.findByIdempotency("key-1", "operator-1", "sit").block().workflowId());
}

@Test
void rejectsChangedBindingForSameKey() {
  store.create(createdWorkflow("binding-a")).block();
  assertThrows(ControlledSqlDmlWorkflowStore.IdempotencyConflictException.class,
      () -> store.assertCompatible("key-1", "operator-1", "sit", "binding-b").block());
}
~~~

- [ ] **步骤 2：确认失败**

运行：`./mvnw -pl backend/control-plane/modules/workflow -Dtest=ControlledSqlDmlWorkflowStoreTest,R2dbcControlledSqlDmlWorkflowStoreTest test`

预期：失败，提示 DML store 与迁移不存在。

- [ ] **步骤 3：实现专用事实源和审计**

~~~sql
create table if not exists controlled_sql_dml_workflow (
  workflow_id varchar(64) primary key,
  idempotency_key varchar(128) not null,
  operator_id varchar(128) not null,
  target_environment varchar(64) not null,
  binding_hash varchar(128) not null,
  connection_id varchar(128) not null,
  schema_name varchar(128) not null,
  statement_type varchar(16) not null,
  sql_hash varchar(128) not null,
  parameters_hash varchar(128) not null,
  preflight_hash varchar(128) not null,
  confirmation_hash varchar(128) not null,
  status varchar(32) not null,
  created_at timestamp with time zone not null,
  updated_at timestamp with time zone not null,
  completed_at timestamp with time zone
);
create unique index if not exists ux_controlled_sql_dml_idempotency
  on controlled_sql_dml_workflow (idempotency_key, operator_id, target_environment);
~~~

通过既有 `AuditTrail` 记录 `SQL_DML_CREATED`、`SQL_DML_CONFIRMED`、`SQL_DML_SUBMITTED`、`SQL_DML_SUCCEEDED`、`SQL_DML_FAILED`、`SQL_DML_HANDOFF_REQUIRED`。资源格式固定为 `sql-workbench:{connectionId}:{schema}:{statementType}`。

- [ ] **步骤 4：验证与提交**

运行：`./mvnw -pl backend/control-plane/modules/workflow,backend/control-plane/modules/audit test`

预期：通过；重复提交复用，冲突不执行，持久化和审计不含 SQL 或参数。

~~~powershell
git add backend/control-plane/modules/workflow backend/control-plane/bootstrap/src/main
git commit -m "Persist controlled SQL DML workflows"
~~~

## Task 4：实现 Worker 影响预览、写凭据和短事务门禁

**文件：**

- 新建：`backend/execution-worker-sqlworkbench/src/main/java/com/company/opsagent/executionworker/sqlworkbench/SqlDmlImpactPreviewExecutor.java`
- 新建：`backend/execution-worker-sqlworkbench/src/main/java/com/company/opsagent/executionworker/sqlworkbench/JdbcSqlDmlImpactPreviewExecutor.java`
- 新建：`backend/execution-worker-sqlworkbench/src/main/java/com/company/opsagent/executionworker/sqlworkbench/WorkerSqlDmlExecutionPolicy.java`
- 修改：`backend/execution-worker-sqlworkbench/src/main/java/com/company/opsagent/executionworker/sqlworkbench/WorkerSqlEgressProperties.java`
- 修改：`backend/execution-worker-sqlworkbench/src/main/java/com/company/opsagent/executionworker/sqlworkbench/WorkerSqlConnectionDescriptor.java`
- 修改：`backend/execution-worker-sqlworkbench/src/main/java/com/company/opsagent/executionworker/sqlworkbench/ConfiguredSqlDataSourceRegistry.java`
- 修改：`backend/execution-worker-sqlworkbench/src/main/java/com/company/opsagent/executionworker/sqlworkbench/RestrictedSqlQueryExecutionWorker.java`
- 修改：`backend/execution-worker-sqlworkbench/src/main/java/com/company/opsagent/executionworker/sqlworkbench/SqlQueryExecutionController.java`
- 修改：`backend/execution-worker-sqlworkbench/src/main/java/com/company/opsagent/executionworker/sqlworkbench/SqlWorkerTransportAuthenticator.java`
- 修改：`backend/execution-worker-sqlworkbench/src/main/java/com/company/opsagent/executionworker/sqlworkbench/SqlWorkbenchWorkerConfiguration.java`
- 测试：`backend/execution-worker-sqlworkbench/src/test/java/com/company/opsagent/executionworker/sqlworkbench/JdbcSqlDmlImpactPreviewExecutorTest.java`
- 测试：`backend/execution-worker-sqlworkbench/src/test/java/com/company/opsagent/executionworker/sqlworkbench/RestrictedSqlQueryExecutionWorkerTest.java`
- 测试：`backend/execution-worker-sqlworkbench/src/test/java/com/company/opsagent/executionworker/sqlworkbench/SqlQueryExecutionControllerTest.java`

**接口：**

- 新增受签名端点 `POST /internal/executions/sql-query/dml-preflight`，请求类型为 `SqlDmlPreflightExecutionRequest`，只接受 `PREFLIGHT_DML`；新增 `POST /internal/executions/sql-query/dml-commit`，请求类型为 `SqlControlledDmlExecutionRequest`，只接受 `COMMIT_DML`。既有只读执行端点和 `SqlQueryExecutionRequest` 不变。
- 既有 `POST /internal/executions/sql-query` 只接受 `RUN_READ_ONLY`；即使传输签名有效，也必须在访问数据库前拒绝旧 `SqlQueryExecutionRequest` 中的 `COMMIT_DML`，错误码为 `SQL_DML_LEGACY_ENVELOPE_REJECTED`。
- `UPDATE`、`DELETE` 返回只读 `COUNT(*)` 和最多 20 条按 `SqlDmlPreviewSelection` 选列和掩码处理的样本；未选列时样本为空；值列表 `INSERT` 返回预估影响行数 `1` 和不可验证项。
- Worker 连接新增 `dml-enabled` 与 `dml-credential-alias`。任一未配置时提交返回 `SQL_DML_WORKER_DISABLED`，读取仍使用只读凭据。

- [ ] **步骤 1：写失败测试**

~~~java
@Test
void previewsUpdateUsingReadOnlyCountAndMaskedSamples() {
  SqlDmlImpactPreview preview = executor.preview(updatePreflightRequest()).block();
  assertEquals(3, preview.estimatedAffectedRows());
  assertEquals(2, preview.samples().size());
  assertTrue(preview.samples().getFirst().values().containsValue("***"));
}

@Test
void returnsNoSamplesWhenPolicyDoesNotSelectPreviewColumns() {
  assertTrue(executor.preview(preflightWithoutPreviewColumns()).block().samples().isEmpty());
}

@Test
void rejectsCommitWhenWriteCapabilityIsDisabled() {
  assertEquals("SQL_DML_WORKER_DISABLED", worker.executeControlledDml(commitRequest()).errorCode());
}

@Test
void rejectsCommitDmlAtLegacyReadEndpointBeforeDatabaseAccess() {
  assertEquals("SQL_DML_LEGACY_ENVELOPE_REJECTED", worker.execute(legacyCommitRequest()).errorCode());
}
~~~

- [ ] **步骤 2：确认失败**

运行：`./mvnw -pl backend/execution-worker-sqlworkbench -Dtest=JdbcSqlDmlImpactPreviewExecutorTest,RestrictedSqlQueryExecutionWorkerTest test`

预期：失败，提示预览执行器与写门禁不存在。

- [ ] **步骤 3：实现只读预览和 Worker 二次校验**

~~~java
private SqlQueryExecutionResult executeControlledDml(SqlControlledDmlExecutionRequest request) {
  dmlExecutionPolicy.assertEnabled(request);
  if (!SqlTargetEnvironments.allowsCrud(request.query().targetEnvironment())) {
    return rejected(request, "SQL_DML_ENVIRONMENT_NOT_ALLOWED", "SQL DML is allowed only in dev, sit, or uat");
  }
  if (!dmlGuard.isControlledDml(request.query().sql())) {
    return rejected(request, "SQL_NOT_CONTROLLED_DML", "Worker accepts exactly one controlled DML statement");
  }
  return succeeded(request, executor.executeDml(request));
}
~~~

预览只能从 AST 生成 `SELECT COUNT(*)` 和样本查询，必须设置 `setReadOnly(true)`、`setAutoCommit(false)` 并始终回滚；样本查询只选择 `SqlDmlPreviewSelection` 指定的列，并在序列化前对掩码列写入 `***`。不能拼接 DML 原文、记录原始参数或绕过列掩码。DML 保持现有的一次事务、成功 commit、异常 rollback。

- [ ] **步骤 4：验证与提交**

运行：`./mvnw -pl backend/execution-worker-sqlworkbench test`

预期：通过；预览不写数据，写开关或写凭据关闭时拒绝，事务错误时回滚。

~~~powershell
git add backend/execution-worker-sqlworkbench
git commit -m "Add controlled SQL DML worker guards"
~~~

## Task 5：将 M05 工作流接入 M09 控制面

**文件：**

- 新建：`backend/control-plane/modules/workflow/src/main/java/com/company/opsagent/controlplane/modules/workflow/ControlledSqlDmlWorkerGateway.java`
- 新建：`backend/control-plane/modules/workflow/src/main/java/com/company/opsagent/controlplane/modules/workflow/ControlledSqlDmlWorkflowRequest.java`
- 新建：`backend/control-plane/modules/workflow/src/main/java/com/company/opsagent/controlplane/modules/workflow/ControlledSqlDmlWorkflowService.java`
- 修改：`backend/control-plane/modules/sqlworkbench/src/main/java/com/company/opsagent/controlplane/modules/sqlworkbench/DefaultSqlWorkbenchService.java`
- 修改：`backend/control-plane/modules/sqlworkbench/src/main/java/com/company/opsagent/controlplane/modules/sqlworkbench/SqlWorkbenchService.java`
- 修改：`backend/control-plane/modules/sqlworkbench/src/main/java/com/company/opsagent/controlplane/modules/sqlworkbench/SqlWorkbenchWorkerClient.java`
- 修改：`backend/control-plane/bootstrap/src/main/java/com/company/opsagent/controlplane/bootstrap/config/SqlWorkbenchConfiguration.java`
- 修改：`backend/control-plane/bootstrap/src/main/java/com/company/opsagent/controlplane/bootstrap/config/WorkflowConfiguration.java`
- 修改：`backend/control-plane/bootstrap/src/main/java/com/company/opsagent/controlplane/bootstrap/api/SqlWorkbenchController.java`
- 修改：`backend/control-plane/bootstrap/src/main/java/com/company/opsagent/controlplane/bootstrap/service/WebClientSqlWorkbenchWorkerClient.java`
- 测试：`backend/control-plane/modules/workflow/src/test/java/com/company/opsagent/controlplane/modules/workflow/ControlledSqlDmlWorkflowServiceTest.java`
- 测试：`backend/control-plane/modules/sqlworkbench/src/test/java/com/company/opsagent/controlplane/modules/sqlworkbench/DefaultSqlWorkbenchServiceTest.java`
- 测试：`backend/control-plane/bootstrap/src/test/java/com/company/opsagent/controlplane/bootstrap/api/SqlWorkbenchControllerTest.java`

**接口：**

- `ControlledSqlDmlWorkerGateway.execute(SqlControlledDmlExecutionRequest request): SqlQueryExecutionResult` 是 M05 到 M07 的端口；配置层以 `SqlWorkbenchWorkerClient::executeControlledDml` 适配。
- `ControlledSqlDmlWorkflowService.execute(ControlledSqlDmlWorkflowRequest request): SqlQueryExecutionResult` 先落工作流和审计，再调用 gateway。
- `SqlWorkbenchWorkerClient.preflightDml(SqlDmlPreflightExecutionRequest request): SqlDmlImpactPreview` 调用 Worker 只读预览端点。
- `DefaultSqlWorkbenchService.preflightControlledDml(...)` 返回 `SqlDmlPreflightResult`；`commitControlledDml(...)` 不再固定抛出 `SQL_DML_WORKFLOW_REQUIRED`，并在 M09 边界将 M05 的稳定工作流错误码映射为 `SqlWorkbenchException`，不使 M05 依赖 M09。

- [ ] **步骤 1：写失败的端到端服务测试**

~~~java
@Test
void persistsAuditsAndSubmitsDmlOnlyAfterConfirmation() {
  SqlQueryExecutionResult result = service.commitControlledDml(confirmedUpdate(), operator(), policy(), trace());
  assertEquals("SUCCEEDED", result.status());
  assertEquals(1, gateway.requests().size());
  assertEquals(1, workflowStore.created().size());
  assertEquals("SQL_DML_SUCCEEDED", auditTrail.latest().orElseThrow().action());
}

@Test
void leavesUnknownResultForHumanHandoffWhenWorkerTimesOut() {
  gateway.timeoutOnExecute();
  assertThrows(SqlWorkbenchException.class, () ->
      service.commitControlledDml(confirmedUpdate(), operator(), policy(), trace()));
  assertEquals(UNKNOWN_REQUIRES_HANDOFF, workflowStore.latest().status());
}
~~~

- [ ] **步骤 2：确认失败**

运行：`./mvnw -pl backend/control-plane/modules/workflow,backend/control-plane/modules/sqlworkbench,backend/control-plane/bootstrap -Dtest=ControlledSqlDmlWorkflowServiceTest,DefaultSqlWorkbenchServiceTest,SqlWorkbenchControllerTest test`

预期：失败，因为当前提交仍固定拒绝。

- [ ] **步骤 3：实现编排和开关后的能力返回**

~~~java
public SqlQueryExecutionResult execute(ControlledSqlDmlWorkflowRequest request) {
  ControlledSqlDmlWorkflow existing = store.findByIdempotency(request.idempotencyScope()).block();
  if (existing != null) {
    return existing.reuseOrReject(request.binding().bindingHash());
  }
  ControlledSqlDmlWorkflow workflow = store.create(request, now()).block();
  audit.record(auditEvent(workflow, "SQL_DML_CREATED", "ALLOW"));
  SqlControlledDmlExecutionRequest workerRequest = request.toWorkerRequest(workflow.workflowId(), now());
  store.markRunning(workflow.workflowId(), workerRequest.executionRequestId(), workerRequest.expiresAt()).block();
  try {
    SqlQueryExecutionResult result = workerGateway.execute(workerRequest);
    return store.completeFromWorkerResult(workflow.workflowId(), result, now()).block();
  } catch (RuntimeException failure) {
    store.markUnknownRequiresHandoff(workflow.workflowId(), safeFailureCode(failure), now()).block();
    throw new SqlWorkbenchException("SQL_DML_RESULT_UNKNOWN", "DML result requires human handoff");
  }
}
~~~

`/queries/preflight` 从 `ExecutionContext` 和策略解析出的 `SqlDmlPreviewSelection` 构造 `SqlDmlPreflightExecutionRequest`，由 `SqlWorkbenchWorkerClient.preflightDml(...)` 使用 `canonicalSqlDmlPreflightPayload` 签名后提交 Worker。提交 DML 时以 `canonicalControlledSqlDmlPayload` 签名 `SqlControlledDmlExecutionRequest` 并调用独立的 Worker 提交端点。连接列表在控制面开关关闭时移除 `PREFLIGHT_DML`、`COMMIT_DML` 能力，浏览器由此禁用入口；提交时仍重新校验，不能信任浏览器能力。

- [ ] **步骤 4：验证与提交**

运行：`./mvnw -pl backend/control-plane/modules/workflow,backend/control-plane/modules/sqlworkbench,backend/control-plane/bootstrap test`

预期：通过；成功仅提交一次 Worker，策略、环境、确认与幂等冲突在提交前拒绝，未知结果不重试。

~~~powershell
git add backend/control-plane/modules/workflow backend/control-plane/modules/sqlworkbench backend/control-plane/bootstrap
git commit -m "Execute controlled SQL DML workflows"
~~~

## Task 6：接入操作台真实预检、能力与执行状态

**文件：**

- 修改：`frontend/operator-console/src/api/sql-api.js`
- 修改：`frontend/operator-console/src/schemas/sql-schemas.js`
- 修改：`frontend/operator-console/src/features/sql-workbench/SqlWorkbenchPage.jsx`
- 修改：`frontend/operator-console/src/features/sql-workbench/SqlEditorPanel.jsx`
- 测试：`frontend/operator-console/src/features/sql-workbench/SqlWorkbenchPage.test.jsx`

**接口：**

- `preflightControlledSqlDml(input)` 调用 `/internal/sql-workbench/queries/preflight` 并用 Zod 解析 `SqlDmlPreflightResult`。
- DML 入口只消费服务端返回的连接能力，不在浏览器复制策略。
- 结果区显式显示 `SUCCEEDED`、`FAILED`、`REJECTED`、`UNKNOWN_REQUIRES_HANDOFF`；未知状态只显示工作流标识和人工接管说明。
- Zod 执行结果状态枚举必须包含 `UNKNOWN_REQUIRES_HANDOFF`，预览样本只渲染服务端返回的列和值，不推断或补全敏感列。

- [ ] **步骤 1：写失败界面测试**

~~~jsx
test("uses server preflight before showing DML confirmation", async () => {
  server.use(http.post("/internal/sql-workbench/queries/preflight", () => HttpResponse.json(preflightWithRisk())));
  await user.click(screen.getByRole("button", { name: "提交当前受控 DML" }));
  expect(await screen.findByText("预计影响 4 行")).toBeInTheDocument();
  expect(await screen.findByRole("dialog", { name: "确认 DML 风险" })).toBeInTheDocument();
});

test("keeps DML submit disabled when server omits COMMIT_DML capability", async () => {
  renderWorkbench(connectionWithoutDmlCapability());
  expect(screen.getByRole("button", { name: "提交当前受控 DML" })).toBeDisabled();
});
~~~

- [ ] **步骤 2：确认失败**

运行：`npm --prefix frontend/operator-console test -- SqlWorkbenchPage.test.jsx`

预期：失败，提示预检 API、Zod schema 或预览渲染不存在。

- [ ] **步骤 3：实现 API 边界和最小页面变更**

~~~javascript
export function preflightControlledSqlDml(input) {
  const request = sqlQueryRequestSchema.parse(input);
  return requestJson("/internal/sql-workbench/queries/preflight", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(request),
    schema: sqlDmlPreflightResultSchema,
  });
}
~~~

用 `validation` 建立确认载荷，用 `impactPreview` 渲染计数、受限样本和不可验证项。预览计数不能作为授权依据。保留当前无 `WHERE` 风险对话框与 `affectedRows` 成功提示。

- [ ] **步骤 4：验证与提交**

运行：`npm --prefix frontend/operator-console run check && npm --prefix frontend/operator-console test -- SqlWorkbenchPage.test.jsx`

预期：通过；生产或关闭开关连接不可提交，确认仍携带当前 SQL 哈希与风险集合。

~~~powershell
git add frontend/operator-console
git commit -m "Show controlled SQL DML preflight state"
~~~

## Task 7：配置、运行手册、计划状态和端到端验收

**文件：**

- 修改：`backend/control-plane/bootstrap/src/main/resources/application-demo.yaml`
- 修改：`backend/execution-worker/src/main/resources/application.yaml`
- 新建：`backend/control-plane/bootstrap/src/test/java/com/company/opsagent/controlplane/bootstrap/ControlledSqlDmlEndToEndTest.java`
- 修改：`docs/planning/project-plan.md`
- 修改：`docs/planning/design-traceability.md`
- 新建：`docs/runbooks/sql-workbench-controlled-dml.md`

**接口：**

- 控制面和 Worker 的默认 DML 开关均关闭；演示配置只为 `sit` 的 `h2-local-test` 显式打开，并配置独立 `dml-credential-alias`。
- 运行手册提供启用、验证、关闭、撤销 Worker 写凭据、处理 `UNKNOWN_REQUIRES_HANDOFF` 和生产拒绝验证步骤。

- [ ] **步骤 1：写失败 H2 端到端测试**

~~~java
@Test
void executesInsertUpdateDeleteAndLeavesProductionReadOnly() {
  assertEquals(1, commit("insert into PUBLIC.ORDERS (ID, STATUS) values (901, 'NEW')").affectedRows());
  assertEquals(1, commit("update PUBLIC.ORDERS set STATUS = 'READY' where ID = 901").affectedRows());
  assertEquals(1, commit("delete from PUBLIC.ORDERS where ID = 901").affectedRows());
  assertThrows(IllegalArgumentException.class,
      () -> commitInEnvironment("production", "delete from PUBLIC.ORDERS where ID = 1"));
}
~~~

- [ ] **步骤 2：确认失败**

运行：`./mvnw -pl backend/control-plane/bootstrap -Dtest=ControlledSqlDmlEndToEndTest test`

预期：失败，因为尚未提供完整 DML 开关、写凭据和工作流链路。

- [ ] **步骤 3：补充最小演示配置和中文运行手册**

~~~yaml
ops-agent:
  controlled-sql-dml:
    enabled-environments: [sit]
    rules:
      - connection-id: h2-local-test
        schema: PUBLIC
        table: ORDERS
        statements: [INSERT, UPDATE, DELETE]
        update-columns: [STATUS]
        predicate-columns: [ID]
        operators: [EQUALS]
        preview-sample-columns: [ID, STATUS]
        masked-preview-columns: []
~~~

Worker 演示配置同时设置 `dml-enabled: true` 与非空 `dml-credential-alias`；凭据仅由已配置 KeyStore 提供，不能加入 YAML。运行手册必须记录关闭控制面开关和 Worker 开关、撤销 KeyStore 别名并验证只读查询仍正常的顺序。

- [ ] **步骤 4：全量验证与提交**

~~~powershell
./mvnw test
npm --prefix frontend/operator-console run check
npm --prefix frontend/operator-console test -- --run
git diff --check
~~~

预期：全部通过；端到端证明三类 DML 在 `sit` 受控成功，生产、关闭开关、错误确认和结果未知均不能重复写入。

~~~powershell
git add backend/control-plane/bootstrap/src/main/resources backend/execution-worker/src/main/resources docs backend/control-plane/bootstrap/src/test
git commit -m "Document controlled SQL DML operations"
~~~

## 实施前复核

- 规格覆盖：任务 1 覆盖版本化契约和绑定；任务 2 覆盖服务端策略；任务 3、5 覆盖持久化工作流、幂等、审计、人工接管；任务 4 覆盖 Worker 预览、写凭据和短事务；任务 6 覆盖操作台；任务 7 覆盖运行手册、计划状态和端到端验收。
- 占位检查：每个任务包含精确路径、失败测试、最小实现、验证命令和提交命令。
- 类型一致性：M09 通过 `SqlDmlPreflightExecutionRequest` 和 `SqlDmlPreflightResult` 完成只读预览，并通过 `ControlledSqlDmlWorkflowService` 提交；M05 经 `ControlledSqlDmlWorkerGateway` 使用 `SqlControlledDmlExecutionRequest`；M07 返回 `SqlDmlImpactPreview` 或 `SqlQueryExecutionResult`。既有 `SqlQueryExecutionRequest` 仅保留只读执行链路。
