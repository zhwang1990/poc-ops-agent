# 发布中心 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交付 `发布中心` 的首个非生产受控发布切片，支持 dev/sit/uat、Tomcat WAR 上传、Liberty HTTPS 制品源、串行发布工作流、二次确认、审计事件和只读日志分析边界。

**Architecture:** 新增控制面 `control-plane-release` 业务模块承载发布目录、制品目录、凭据指纹、发布单和发布工作流；新增版本化契约和 Worker 发布执行请求，不复用 P1 只读命令信封。M09 提供 `发布中心` 页面，M07 Worker 只执行已授权标准动作，Liberty 和 Tomcat 差异通过适配器隔离。

**Tech Stack:** Java 21、Spring Boot WebFlux、R2DBC、Maven 多模块、JSON Schema、React/JSX、JSDoc、Zod、Vitest、Playwright。

---

## 范围说明

本计划覆盖首个可验收垂直切片。它不接生产环境，不触发 CI 构建，不做多应用编排，不允许任意脚本执行。Tomcat 首版只允许 WAR，Liberty 首版只通过已配置 HTTPS 服务读取远程制品目录。

## 文件结构

### 后端契约

- Create: `backend/contracts/release/release-command-v1.schema.json`
- Create: `backend/contracts/release/release-worker-request-v1.schema.json`
- Create: `backend/contracts/release/release-worker-result-v1.schema.json`
- Create: `backend/contracts/release/release-events-v1.schema.json`
- Modify: `backend/contracts/README.md`
- Test: `backend/contracts/src/test/java/com/company/opsagent/contracts/ContractsTest.java`

### 控制面发布模块

- Create: `backend/control-plane/modules/release/pom.xml`
- Modify: `backend/control-plane/pom.xml`
- Modify: `backend/control-plane/bootstrap/pom.xml`
- Create: `backend/control-plane/modules/release/src/main/java/com/company/opsagent/controlplane/modules/release/ReleaseApplication.java`
- Create: `backend/control-plane/modules/release/src/main/java/com/company/opsagent/controlplane/modules/release/ReleaseEnvironmentPolicy.java`
- Create: `backend/control-plane/modules/release/src/main/java/com/company/opsagent/controlplane/modules/release/ReleaseServer.java`
- Create: `backend/control-plane/modules/release/src/main/java/com/company/opsagent/controlplane/modules/release/ReleaseArtifact.java`
- Create: `backend/control-plane/modules/release/src/main/java/com/company/opsagent/controlplane/modules/release/ReleaseCredential.java`
- Create: `backend/control-plane/modules/release/src/main/java/com/company/opsagent/controlplane/modules/release/ReleasePlan.java`
- Create: `backend/control-plane/modules/release/src/main/java/com/company/opsagent/controlplane/modules/release/ReleaseNodeStep.java`
- Create: `backend/control-plane/modules/release/src/main/java/com/company/opsagent/controlplane/modules/release/ReleaseCatalogStore.java`
- Create: `backend/control-plane/modules/release/src/main/java/com/company/opsagent/controlplane/modules/release/InMemoryReleaseCatalogStore.java`
- Create: `backend/control-plane/modules/release/src/main/java/com/company/opsagent/controlplane/modules/release/R2dbcReleaseCatalogStore.java`
- Create: `backend/control-plane/modules/release/src/main/java/com/company/opsagent/controlplane/modules/release/ReleaseWorkflowService.java`
- Create: `backend/control-plane/modules/release/src/main/java/com/company/opsagent/controlplane/modules/release/ReleaseWorkerGateway.java`
- Create: `backend/control-plane/modules/release/src/main/resources/sql/migrations/V001__release_center_schema.sql`
- Test: `backend/control-plane/modules/release/src/test/java/com/company/opsagent/controlplane/modules/release/ReleaseWorkflowServiceTest.java`
- Test: `backend/control-plane/modules/release/src/test/java/com/company/opsagent/controlplane/modules/release/R2dbcReleaseCatalogStoreTest.java`

### 控制面装配和 API

- Create: `backend/control-plane/bootstrap/src/main/java/com/company/opsagent/controlplane/bootstrap/config/ReleaseCenterProperties.java`
- Create: `backend/control-plane/bootstrap/src/main/java/com/company/opsagent/controlplane/bootstrap/config/ReleaseCenterConfiguration.java`
- Create: `backend/control-plane/bootstrap/src/main/java/com/company/opsagent/controlplane/bootstrap/api/ReleaseCenterController.java`
- Modify: `backend/control-plane/bootstrap/src/main/java/com/company/opsagent/controlplane/bootstrap/config/WorkflowConfiguration.java`
- Modify: `backend/control-plane/bootstrap/src/main/java/com/company/opsagent/controlplane/bootstrap/config/PolicyProperties.java`
- Test: `backend/control-plane/bootstrap/src/test/java/com/company/opsagent/controlplane/bootstrap/ControlPlaneApplicationTest.java`
- Test: `backend/control-plane/bootstrap/src/test/java/com/company/opsagent/controlplane/bootstrap/api/ReleaseCenterControllerTest.java`

### Worker 发布适配器

- Create: `backend/execution-worker/src/main/java/com/company/opsagent/executionworker/release/ReleaseWorkerController.java`
- Create: `backend/execution-worker/src/main/java/com/company/opsagent/executionworker/release/ReleaseAdapter.java`
- Create: `backend/execution-worker/src/main/java/com/company/opsagent/executionworker/release/LibertyHttpsReleaseAdapter.java`
- Create: `backend/execution-worker/src/main/java/com/company/opsagent/executionworker/release/TomcatWarUploadReleaseAdapter.java`
- Create: `backend/execution-worker/src/main/java/com/company/opsagent/executionworker/release/ReleaseAdapterRegistry.java`
- Test: `backend/execution-worker/src/test/java/com/company/opsagent/executionworker/release/ReleaseWorkerControllerTest.java`
- Test: `backend/execution-worker/src/test/java/com/company/opsagent/executionworker/release/LibertyHttpsReleaseAdapterTest.java`
- Test: `backend/execution-worker/src/test/java/com/company/opsagent/executionworker/release/TomcatWarUploadReleaseAdapterTest.java`

### 前端发布中心

- Modify: `frontend/operator-console/src/components/layout/AppShell.jsx`
- Modify: `frontend/operator-console/src/app/router.jsx`
- Create: `frontend/operator-console/src/api/release-center-api.js`
- Create: `frontend/operator-console/src/schemas/release-center-schemas.js`
- Create: `frontend/operator-console/src/features/release-center/use-release-center.js`
- Create: `frontend/operator-console/src/features/release-center/ReleaseCenterPage.jsx`
- Create: `frontend/operator-console/src/features/release-center/ReleaseCenterPage.module.css`
- Test: `frontend/operator-console/src/features/release-center/ReleaseCenterPage.test.jsx`
- Modify: `frontend/operator-console/src/test/handlers.js`

### 文档和 ADR

- Create: `docs/adr/0010-release-center-non-production-controlled-change.md`
- Modify: `docs/architecture/module-map.md`
- Modify: `docs/planning/project-plan.md`
- Create: `docs/runbooks/release-center.md`

---

### Task 1: ADR 和范围门禁

**Files:**
- Create: `docs/adr/0010-release-center-non-production-controlled-change.md`
- Modify: `docs/architecture/module-map.md`
- Modify: `docs/planning/project-plan.md`
- Create: `docs/runbooks/release-center.md`

- [ ] **Step 1: 写 ADR**

新增 ADR，内容必须包含：

```markdown
# ADR 0010：发布中心非生产受控变更边界

- 状态：Accepted
- 日期：2026-07-01
- 相关模块：M02、M03、M05、M07、M08、M09、M10、M11

## 决策

发布中心首版只覆盖 dev、sit、uat。生产环境不出现在 API、页面、测试样例或默认配置中。发布、启停、回滚属于 P2 受控变更能力，必须经过服务端策略、二次确认、持久化工作流、Worker 隔离、审计事件和回滚或人工接管路径。

Tomcat 首版只支持 WAR 上传入平台受控制品记录。Liberty 首版只通过已配置 HTTPS 服务读取远程制品目录。大模型只分析脱敏日志并输出诊断建议，不能决定授权或最终状态。
```

- [ ] **Step 2: 更新模块地图**

在 `docs/architecture/module-map.md` 当前实现重点中增加发布中心条目，明确不新增独立部署服务，只新增控制面业务模块和 Worker 适配器。

- [ ] **Step 3: 更新项目计划**

在 `docs/planning/project-plan.md` 增加 P2 发布中心受控变更切片，说明 P1 不交付该能力。

- [ ] **Step 4: 写运行手册骨架**

新增 `docs/runbooks/release-center.md`，记录功能开关、环境启用顺序、凭据轮换、失败停止、回滚和人工接管流程。

- [ ] **Step 5: 验证文档格式**

Run: `git diff --check -- docs/adr/0010-release-center-non-production-controlled-change.md docs/architecture/module-map.md docs/planning/project-plan.md docs/runbooks/release-center.md`

Expected: exit code 0。

- [ ] **Step 6: Commit**

```powershell
git add docs/adr/0010-release-center-non-production-controlled-change.md docs/architecture/module-map.md docs/planning/project-plan.md docs/runbooks/release-center.md
git commit -m "Document release center controlled change boundary"
```

---

### Task 2: 发布契约 Schema

**Files:**
- Create: `backend/contracts/release/release-command-v1.schema.json`
- Create: `backend/contracts/release/release-worker-request-v1.schema.json`
- Create: `backend/contracts/release/release-worker-result-v1.schema.json`
- Create: `backend/contracts/release/release-events-v1.schema.json`
- Modify: `backend/contracts/README.md`
- Test: `backend/contracts/src/test/java/com/company/opsagent/contracts/ContractsTest.java`

- [ ] **Step 1: 写失败契约测试**

在 `ContractsTest` 增加测试，验证生产环境被拒绝、Tomcat 只允许 WAR、事件 payload 不允许凭据字段。

```java
@Test
void releaseCommandSchemaRejectsProductionEnvironment() throws Exception {
  JsonNode schema = schema("release/release-command-v1.schema.json");
  JsonNode command = objectMapper.readTree("""
      {
        "contractVersion": "1.0",
        "releaseId": "rel-001",
        "workflowId": "550e8400-e29b-41d4-a716-446655440000",
        "operation": "DEPLOY",
        "targetEnvironment": "prod",
        "applicationId": "orders",
        "artifact": {"artifactId": "art-1", "type": "WAR", "checksum": "sha256:abc"},
        "nodes": [{"nodeId": "node-1", "serverType": "TOMCAT", "managementMode": "TOMCAT_WAR_UPLOAD"}],
        "operator": {"operatorId": "alice", "roles": ["ROLE_ops-release"]},
        "policyDecision": {"decisionId": "pd-1", "policyVersion": "v1", "decision": "ALLOW"},
        "confirmation": {"confirmationId": "cf-1", "confirmedAt": "2026-07-01T00:00:00Z", "parametersHash": "sha256:abc"},
        "trace": {"traceId": "trace-1", "requestId": "req-1"},
        "requestedAt": "2026-07-01T00:00:00Z"
      }
      """);

  assertFalse(validate(schema, command).isEmpty());
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend; .\mvnw.cmd -pl contracts -Dtest=ContractsTest#releaseCommandSchemaRejectsProductionEnvironment test`

Expected: FAIL because `release/release-command-v1.schema.json` does not exist.

- [ ] **Step 3: 新增 release command schema**

创建 `backend/contracts/release/release-command-v1.schema.json`，要求：

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://company.example/ops-agent/contracts/release/release-command-v1.schema.json",
  "title": "Release command v1",
  "type": "object",
  "additionalProperties": false,
  "required": ["contractVersion", "releaseId", "workflowId", "operation", "targetEnvironment", "applicationId", "artifact", "nodes", "operator", "policyDecision", "trace", "requestedAt"],
  "properties": {
    "contractVersion": {"const": "1.0"},
    "releaseId": {"type": "string", "minLength": 1},
    "workflowId": {"type": "string", "format": "uuid"},
    "operation": {"enum": ["DEPLOY", "START", "STOP", "RESTART", "ROLLBACK"]},
    "targetEnvironment": {"enum": ["dev", "sit", "uat"]},
    "applicationId": {"type": "string", "minLength": 1},
    "artifact": {
      "type": "object",
      "additionalProperties": false,
      "required": ["artifactId", "type", "checksum"],
      "properties": {
        "artifactId": {"type": "string", "minLength": 1},
        "type": {"const": "WAR"},
        "checksum": {"type": "string", "pattern": "^sha256:[a-fA-F0-9]{3,}$"}
      }
    },
    "nodes": {
      "type": "array",
      "minItems": 1,
      "items": {
        "type": "object",
        "additionalProperties": false,
        "required": ["nodeId", "serverType", "managementMode"],
        "properties": {
          "nodeId": {"type": "string", "minLength": 1},
          "serverType": {"enum": ["TOMCAT", "LIBERTY"]},
          "managementMode": {"enum": ["LIBERTY_HTTPS", "TOMCAT_WAR_UPLOAD", "TOMCAT_MANAGER_API", "NODE_AGENT_HTTPS", "CONTROLLED_SSH_TEMPLATE"]}
        }
      }
    },
    "operator": {"type": "object"},
    "policyDecision": {"type": "object"},
    "confirmation": {"type": "object"},
    "trace": {"type": "object"},
    "requestedAt": {"type": "string", "format": "date-time"}
  }
}
```

- [ ] **Step 4: 新增 Worker 请求和结果 Schema**

创建 `release-worker-request-v1.schema.json`，字段包含 `contractVersion`、`executionRequestId`、`authorizedAt`、`expiresAt`、`command`。创建 `release-worker-result-v1.schema.json`，状态枚举为 `SUCCEEDED`、`FAILED`、`REJECTED`、`MANUAL_INTERVENTION_REQUIRED`。

- [ ] **Step 5: 新增发布事件 Schema**

创建 `release-events-v1.schema.json`，事件类型包括 `RELEASE_CREATED`、`RELEASE_CONFIRMED`、`RELEASE_NODE_STARTED`、`RELEASE_NODE_COMPLETED`、`RELEASE_NODE_FAILED`、`RELEASE_PARTIAL_FAILED`、`RELEASE_ROLLBACK_STARTED`、`RELEASE_ROLLBACK_FAILED`、`RELEASE_MANUAL_INTERVENTION_REQUIRED`。payload 禁止 credential、secret、password 字段。

- [ ] **Step 6: 更新 contracts README**

在 `backend/contracts/README.md` 增加 `release` 目录说明：发布类契约只用于非生产受控变更，不得用于生产写操作。

- [ ] **Step 7: 运行契约测试**

Run: `cd backend; .\mvnw.cmd -pl contracts test`

Expected: PASS。

- [ ] **Step 8: Commit**

```powershell
git add backend/contracts/release backend/contracts/README.md backend/contracts/src/test/java/com/company/opsagent/contracts/ContractsTest.java
git commit -m "Add release center contracts"
```

---

### Task 3: 控制面 release 模块骨架

**Files:**
- Create: `backend/control-plane/modules/release/pom.xml`
- Modify: `backend/control-plane/pom.xml`
- Modify: `backend/control-plane/bootstrap/pom.xml`
- Create: `backend/control-plane/modules/release/src/main/java/com/company/opsagent/controlplane/modules/release/ReleaseApplication.java`
- Create: `backend/control-plane/modules/release/src/main/java/com/company/opsagent/controlplane/modules/release/ReleaseEnvironmentPolicy.java`
- Create: `backend/control-plane/modules/release/src/main/java/com/company/opsagent/controlplane/modules/release/ReleaseServer.java`
- Create: `backend/control-plane/modules/release/src/main/java/com/company/opsagent/controlplane/modules/release/ReleaseArtifact.java`
- Test: `backend/control-plane/modules/release/src/test/java/com/company/opsagent/controlplane/modules/release/ReleaseCatalogModelTest.java`

- [ ] **Step 1: 写模型测试**

```java
@Test
void releaseServerRejectsProductionEnvironment() {
  IllegalArgumentException error = assertThrows(
      IllegalArgumentException.class,
      () -> ReleaseServer.create("node-1", "prod", ServerType.TOMCAT, ManagementMode.TOMCAT_WAR_UPLOAD, "https://tomcat.example", true));

  assertEquals("targetEnvironment must be dev, sit or uat", error.getMessage());
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend; .\mvnw.cmd -pl control-plane/modules/release test`

Expected: FAIL because module does not exist.

- [ ] **Step 3: 新建 Maven 模块**

`backend/control-plane/modules/release/pom.xml` 使用 `control-plane-parent`，依赖 `ops-agent-contracts`、`control-plane-policy`、`control-plane-audit`、`reactor-core`、`spring-r2dbc`、`jackson-databind`、`junit-jupiter`、`reactor-test`、`r2dbc-h2`。

- [ ] **Step 4: 注册 Maven 模块**

在 `backend/control-plane/pom.xml` `<modules>` 中加入：

```xml
<module>modules/release</module>
```

在 `backend/control-plane/bootstrap/pom.xml` 加入：

```xml
<dependency>
  <groupId>com.company.opsagent</groupId>
  <artifactId>control-plane-release</artifactId>
  <version>${project.version}</version>
</dependency>
```

- [ ] **Step 5: 实现领域枚举和值对象**

创建枚举：

```java
public enum TargetEnvironment {
  DEV("dev"), SIT("sit"), UAT("uat");
}

public enum ServerType {
  TOMCAT, LIBERTY
}

public enum ManagementMode {
  LIBERTY_HTTPS, TOMCAT_WAR_UPLOAD, TOMCAT_MANAGER_API, NODE_AGENT_HTTPS, CONTROLLED_SSH_TEMPLATE, DISABLED
}

public enum ArtifactType {
  WAR
}
```

实现 `ReleaseServer.create(String nodeId, String targetEnvironment, ServerType serverType, ManagementMode managementMode, String managementEndpoint, boolean enabled)` 时拒绝 `prod` 和空字段。

- [ ] **Step 6: 运行 release 模块测试**

Run: `cd backend; .\mvnw.cmd -pl control-plane/modules/release test`

Expected: PASS。

- [ ] **Step 7: Commit**

```powershell
git add backend/control-plane/pom.xml backend/control-plane/bootstrap/pom.xml backend/control-plane/modules/release
git commit -m "Add release center control-plane module"
```

---

### Task 4: 发布目录持久化

**Files:**
- Create: `backend/control-plane/modules/release/src/main/resources/sql/migrations/V001__release_center_schema.sql`
- Create: `backend/control-plane/modules/release/src/main/java/com/company/opsagent/controlplane/modules/release/ReleaseCatalogStore.java`
- Create: `backend/control-plane/modules/release/src/main/java/com/company/opsagent/controlplane/modules/release/R2dbcReleaseCatalogStore.java`
- Create: `backend/control-plane/modules/release/src/main/java/com/company/opsagent/controlplane/modules/release/InMemoryReleaseCatalogStore.java`
- Create: `backend/control-plane/bootstrap/src/main/java/com/company/opsagent/controlplane/bootstrap/config/ReleaseCenterConfiguration.java`
- Test: `backend/control-plane/modules/release/src/test/java/com/company/opsagent/controlplane/modules/release/R2dbcReleaseCatalogStoreTest.java`

- [ ] **Step 1: 写 R2DBC 仓储测试**

测试保存应用、策略、服务器和制品记录，读取时不返回任何凭据明文。

```java
@Test
void storesCatalogRecordsWithoutCredentialPlaintext() {
  ReleaseCatalogStore store = store();
  store.saveApplication(ReleaseApplication.create("orders", "订单服务", ArtifactType.WAR, "/health", true)).block();
  store.saveEnvironmentPolicy(ReleaseEnvironmentPolicy.defaultFor(TargetEnvironment.SIT).requireConfirmation(true)).block();
  store.saveServer(ReleaseServer.create("sit-tomcat-1", "sit", ServerType.TOMCAT, ManagementMode.TOMCAT_WAR_UPLOAD, "https://tomcat-sit.example", true)).block();

  List<ReleaseServer> servers = store.listServers("sit").collectList().block();

  assertEquals(1, servers.size());
  assertEquals("sit-tomcat-1", servers.get(0).nodeId());
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend; .\mvnw.cmd -pl control-plane/modules/release -Dtest=R2dbcReleaseCatalogStoreTest test`

Expected: FAIL because store and migration do not exist.

- [ ] **Step 3: 新增迁移脚本**

`V001__release_center_schema.sql` 创建表：

```sql
CREATE TABLE IF NOT EXISTS release_application (
  application_id VARCHAR(120) PRIMARY KEY,
  display_name VARCHAR(200) NOT NULL,
  artifact_type VARCHAR(40) NOT NULL,
  health_path VARCHAR(400) NOT NULL,
  enabled BOOLEAN NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS release_environment_policy (
  target_environment VARCHAR(20) PRIMARY KEY,
  allow_deploy BOOLEAN NOT NULL,
  allow_start BOOLEAN NOT NULL,
  allow_stop BOOLEAN NOT NULL,
  allow_rollback BOOLEAN NOT NULL,
  require_confirmation BOOLEAN NOT NULL,
  timeout_seconds INTEGER NOT NULL,
  stop_on_node_failure BOOLEAN NOT NULL,
  log_analysis_enabled BOOLEAN NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS release_server (
  node_id VARCHAR(120) PRIMARY KEY,
  target_environment VARCHAR(20) NOT NULL,
  server_type VARCHAR(40) NOT NULL,
  management_mode VARCHAR(80) NOT NULL,
  management_endpoint VARCHAR(500) NOT NULL,
  application_path VARCHAR(500),
  credential_alias VARCHAR(160),
  enabled BOOLEAN NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS release_credential (
  credential_alias VARCHAR(160) PRIMARY KEY,
  server_type VARCHAR(40) NOT NULL,
  ciphertext CLOB NOT NULL,
  nonce VARCHAR(120) NOT NULL,
  algorithm VARCHAR(80) NOT NULL,
  fingerprint VARCHAR(120) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS release_artifact (
  artifact_id VARCHAR(120) PRIMARY KEY,
  application_id VARCHAR(120) NOT NULL,
  target_environment VARCHAR(20) NOT NULL,
  artifact_type VARCHAR(40) NOT NULL,
  checksum VARCHAR(160) NOT NULL,
  original_filename VARCHAR(255) NOT NULL,
  storage_key VARCHAR(255) NOT NULL,
  byte_size BIGINT NOT NULL,
  uploaded_by VARCHAR(160) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS release_plan (
  release_id VARCHAR(120) PRIMARY KEY,
  workflow_id VARCHAR(120) NOT NULL,
  application_id VARCHAR(120) NOT NULL,
  target_environment VARCHAR(20) NOT NULL,
  artifact_id VARCHAR(120) NOT NULL,
  operation VARCHAR(40) NOT NULL,
  status VARCHAR(80) NOT NULL,
  parameters_hash VARCHAR(160) NOT NULL,
  policy_version VARCHAR(120) NOT NULL,
  confirmed_by VARCHAR(160),
  confirmed_at TIMESTAMP WITH TIME ZONE,
  created_by VARCHAR(160) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS release_node_step (
  release_id VARCHAR(120) NOT NULL,
  step_sequence INTEGER NOT NULL,
  node_id VARCHAR(120) NOT NULL,
  action VARCHAR(40) NOT NULL,
  status VARCHAR(80) NOT NULL,
  error_code VARCHAR(160),
  error_message VARCHAR(1000),
  started_at TIMESTAMP WITH TIME ZONE,
  completed_at TIMESTAMP WITH TIME ZONE,
  PRIMARY KEY (release_id, step_sequence)
);
```

所有表包含 `created_at`、`updated_at`。`release_credential` 只保存 `ciphertext`、`nonce`、`algorithm`、`fingerprint`，不得有 `plaintext` 字段。

- [ ] **Step 4: 实现 Store 接口和 R2DBC 实现**

接口返回 `Mono<T>` 和 `Flux<T>`，避免阻塞 WebFlux 线程。

- [ ] **Step 5: 装配 release schema initializer**

在 `ReleaseCenterConfiguration` 中新增 `ConnectionFactoryInitializer` 或合并到现有初始化链。确保启动时加载 release migration。

- [ ] **Step 6: 运行模块测试**

Run: `cd backend; .\mvnw.cmd -pl control-plane/modules/release test`

Expected: PASS。

- [ ] **Step 7: Commit**

```powershell
git add backend/control-plane/modules/release/src/main/resources backend/control-plane/modules/release/src/main/java backend/control-plane/modules/release/src/test/java backend/control-plane/bootstrap/src/main/java/com/company/opsagent/controlplane/bootstrap/config/ReleaseCenterConfiguration.java
git commit -m "Persist release center catalog"
```

---

### Task 5: 凭据加密和指纹

**Files:**
- Create: `backend/control-plane/modules/release/src/main/java/com/company/opsagent/controlplane/modules/release/ReleaseCredentialSecretCodec.java`
- Create: `backend/control-plane/modules/release/src/main/java/com/company/opsagent/controlplane/modules/release/AesGcmReleaseCredentialSecretCodec.java`
- Create: `backend/control-plane/modules/release/src/main/java/com/company/opsagent/controlplane/modules/release/ReleaseCredentialService.java`
- Create: `backend/control-plane/bootstrap/src/main/java/com/company/opsagent/controlplane/bootstrap/config/ReleaseCenterProperties.java`
- Test: `backend/control-plane/modules/release/src/test/java/com/company/opsagent/controlplane/modules/release/AesGcmReleaseCredentialSecretCodecTest.java`
- Test: `backend/control-plane/modules/release/src/test/java/com/company/opsagent/controlplane/modules/release/ReleaseCredentialServiceTest.java`

- [ ] **Step 1: 写加密测试**

```java
@Test
void encryptsCredentialAndReturnsStableFingerprintPrefix() {
  var codec = new AesGcmReleaseCredentialSecretCodec(System.getenv("OPS_AGENT_RELEASE_CREDENTIAL_MASTER_KEY"));
  var encrypted = codec.encrypt(System.getenv("OPS_AGENT_RELEASE_CREDENTIAL_VALUE"));

  assertNotEquals(System.getenv("OPS_AGENT_RELEASE_CREDENTIAL_VALUE"), encrypted.ciphertext());
  assertTrue(encrypted.fingerprint().startsWith("fp_"));
  assertEquals(System.getenv("OPS_AGENT_RELEASE_CREDENTIAL_VALUE"), codec.decrypt(encrypted));
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend; .\mvnw.cmd -pl control-plane/modules/release -Dtest=AesGcmReleaseCredentialSecretCodecTest test`

Expected: FAIL because codec does not exist.

- [ ] **Step 3: 实现 AES-GCM codec**

复用模型设置中的 AES-GCM 思路，但放在 release 模块内，避免 release 模块依赖 agentruntime。

- [ ] **Step 4: 新增配置属性**

`ReleaseCenterProperties` 包含：

```java
private boolean enabled;
private String credentialMasterKey;
private Path artifactStoragePath;
```

生产运行时未配置 `credentialMasterKey` 且启用发布中心时启动失败。

- [ ] **Step 5: 实现凭据服务**

`ReleaseCredentialService.createOrRotate(alias, serverType, plaintext, operatorId)` 只返回 `credentialAlias`、`fingerprint`、`updatedAt`，不返回明文。

- [ ] **Step 6: 运行测试**

Run: `cd backend; .\mvnw.cmd -pl control-plane/modules/release test`

Expected: PASS。

- [ ] **Step 7: Commit**

```powershell
git add backend/control-plane/modules/release backend/control-plane/bootstrap/src/main/java/com/company/opsagent/controlplane/bootstrap/config/ReleaseCenterProperties.java
git commit -m "Encrypt release center credentials"
```

---

### Task 6: 发布中心管理 API

**Files:**
- Create: `backend/control-plane/bootstrap/src/main/java/com/company/opsagent/controlplane/bootstrap/api/ReleaseCenterController.java`
- Modify: `backend/control-plane/bootstrap/src/main/java/com/company/opsagent/controlplane/bootstrap/config/PolicyProperties.java`
- Test: `backend/control-plane/bootstrap/src/test/java/com/company/opsagent/controlplane/bootstrap/api/ReleaseCenterControllerTest.java`

- [ ] **Step 1: 写 Controller 测试**

覆盖：

- `GET /internal/release-center/applications`
- `POST /internal/release-center/applications`
- `POST /internal/release-center/credentials`
- `POST /internal/release-center/servers/{nodeId}/test`
- 未授权角色返回 403
- 凭据响应不包含 `secret`、`password`、`ciphertext`

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend; .\mvnw.cmd -pl control-plane/bootstrap -Dtest=ReleaseCenterControllerTest test`

Expected: FAIL because controller does not exist.

- [ ] **Step 3: 实现 Controller**

控制器使用 `/internal/release-center` 前缀。请求使用 `JsonNode` 加字段白名单校验，参考 `ModelProviderController`。

- [ ] **Step 4: 增加策略动作映射**

新增动作：

```text
release.catalog.read
release.catalog.write
release.credential.rotate
release.connection.test
release.plan.create
release.plan.confirm
release.plan.execute
release.rollback.execute
```

默认只给 `ROLE_ops-admin` 写权限，`ROLE_ops-reader` 只读。

- [ ] **Step 5: 运行 controller 测试**

Run: `cd backend; .\mvnw.cmd -pl control-plane/bootstrap -Dtest=ReleaseCenterControllerTest test`

Expected: PASS。

- [ ] **Step 6: Commit**

```powershell
git add backend/control-plane/bootstrap/src/main/java/com/company/opsagent/controlplane/bootstrap/api/ReleaseCenterController.java backend/control-plane/bootstrap/src/main/java/com/company/opsagent/controlplane/bootstrap/config/PolicyProperties.java backend/control-plane/bootstrap/src/test/java/com/company/opsagent/controlplane/bootstrap/api/ReleaseCenterControllerTest.java
git commit -m "Add release center management API"
```

---

### Task 7: Tomcat WAR 上传入库

**Files:**
- Create: `backend/control-plane/modules/release/src/main/java/com/company/opsagent/controlplane/modules/release/ReleaseArtifactStore.java`
- Create: `backend/control-plane/modules/release/src/main/java/com/company/opsagent/controlplane/modules/release/FileSystemReleaseArtifactStore.java`
- Modify: `backend/control-plane/bootstrap/src/main/java/com/company/opsagent/controlplane/bootstrap/api/ReleaseCenterController.java`
- Test: `backend/control-plane/modules/release/src/test/java/com/company/opsagent/controlplane/modules/release/FileSystemReleaseArtifactStoreTest.java`
- Test: `backend/control-plane/bootstrap/src/test/java/com/company/opsagent/controlplane/bootstrap/api/ReleaseCenterControllerTest.java`

- [ ] **Step 1: 写 artifact store 测试**

```java
@Test
void storesWarAndComputesSha256Checksum() throws IOException {
  Path storage = tempDir.resolve("artifacts");
  ReleaseArtifactStore store = new FileSystemReleaseArtifactStore(storage);

  ReleaseArtifact artifact = store.storeWar("orders", "dev", "orders-1.0.0.war", new ByteArrayInputStream("war".getBytes(UTF_8))).block();

  assertEquals(ArtifactType.WAR, artifact.type());
  assertTrue(artifact.checksum().startsWith("sha256:"));
  assertTrue(Files.exists(storage.resolve(artifact.artifactId() + ".war")));
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend; .\mvnw.cmd -pl control-plane/modules/release -Dtest=FileSystemReleaseArtifactStoreTest test`

Expected: FAIL because artifact store does not exist.

- [ ] **Step 3: 实现文件系统制品暂存**

只接受 `.war` 文件名，限制最大大小由 `ReleaseCenterProperties.maxArtifactBytes` 控制。保存路径使用生成的 `artifactId`，不使用用户上传文件名作为真实路径。

- [ ] **Step 4: 实现上传 API**

新增 `POST /internal/release-center/artifacts/tomcat-war`，接收 multipart 文件和应用/环境字段，返回 artifact summary。响应包含 checksum，不包含本地绝对路径。

- [ ] **Step 5: 运行上传 API 测试**

Run: `cd backend; .\mvnw.cmd -pl control-plane/bootstrap -Dtest=ReleaseCenterControllerTest test`

Expected: PASS。

- [ ] **Step 6: Commit**

```powershell
git add backend/control-plane/modules/release backend/control-plane/bootstrap/src/main/java/com/company/opsagent/controlplane/bootstrap/api/ReleaseCenterController.java backend/control-plane/bootstrap/src/test/java/com/company/opsagent/controlplane/bootstrap/api/ReleaseCenterControllerTest.java
git commit -m "Store uploaded Tomcat WAR artifacts"
```

---

### Task 8: 发布工作流状态机

**Files:**
- Create: `backend/control-plane/modules/release/src/main/java/com/company/opsagent/controlplane/modules/release/ReleaseWorkflowService.java`
- Create: `backend/control-plane/modules/release/src/main/java/com/company/opsagent/controlplane/modules/release/ReleaseStatus.java`
- Create: `backend/control-plane/modules/release/src/main/java/com/company/opsagent/controlplane/modules/release/ReleaseNodeStatus.java`
- Create: `backend/control-plane/modules/release/src/main/java/com/company/opsagent/controlplane/modules/release/ReleaseConfirmation.java`
- Test: `backend/control-plane/modules/release/src/test/java/com/company/opsagent/controlplane/modules/release/ReleaseWorkflowServiceTest.java`

- [ ] **Step 1: 写状态机测试**

测试 sit 发布创建后进入 `WAIT_CONFIRM`，确认参数哈希不匹配时拒绝，节点失败后进入 `PARTIAL_FAILED` 并停止后续节点。

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend; .\mvnw.cmd -pl control-plane/modules/release -Dtest=ReleaseWorkflowServiceTest test`

Expected: FAIL because workflow service does not exist.

- [ ] **Step 3: 实现状态枚举**

```java
public enum ReleaseStatus {
  DRAFT, WAIT_CONFIRM, READY, RUNNING, SUCCEEDED, SUCCEEDED_WITH_WARNINGS, PARTIAL_FAILED, FAILED,
  ROLLING_BACK, ROLLED_BACK, ROLLBACK_FAILED, MANUAL_INTERVENTION
}
```

- [ ] **Step 4: 实现 create 和 confirm**

`createPlan` 根据环境策略决定 `DRAFT` 或 `WAIT_CONFIRM`。`confirm` 绑定 `parametersHash`，不匹配时抛出稳定错误 `RELEASE_CONFIRMATION_HASH_MISMATCH`。

- [ ] **Step 5: 实现串行 execute**

`execute` 按节点顺序调用 `ReleaseWorkerGateway`。任一节点失败时停止后续节点并标记 `PARTIAL_FAILED`。

- [ ] **Step 6: 运行 workflow 测试**

Run: `cd backend; .\mvnw.cmd -pl control-plane/modules/release test`

Expected: PASS。

- [ ] **Step 7: Commit**

```powershell
git add backend/control-plane/modules/release
git commit -m "Add release workflow state machine"
```

---

### Task 9: Worker 发布执行边界

**Files:**
- Create: `backend/execution-worker/src/main/java/com/company/opsagent/executionworker/release/ReleaseWorkerController.java`
- Create: `backend/execution-worker/src/main/java/com/company/opsagent/executionworker/release/ReleaseAdapter.java`
- Create: `backend/execution-worker/src/main/java/com/company/opsagent/executionworker/release/ReleaseAdapterRegistry.java`
- Test: `backend/execution-worker/src/test/java/com/company/opsagent/executionworker/release/ReleaseWorkerControllerTest.java`

- [ ] **Step 1: 写 Worker 边界测试**

覆盖：

- `targetEnvironment=prod` 被拒绝。
- `managementMode=DISABLED` 被拒绝。
- 未注册适配器返回 `SERVER_MANAGEMENT_MODE_NOT_CONFIGURED`。
- 请求过期返回 `RELEASE_REQUEST_EXPIRED`。

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend; .\mvnw.cmd -pl execution-worker -Dtest=ReleaseWorkerControllerTest test`

Expected: FAIL because release controller does not exist.

- [ ] **Step 3: 实现 ReleaseAdapter 接口**

```java
public interface ReleaseAdapter {
  Mono<ReleaseWorkerResult> precheck(ReleaseWorkerRequest request);
  Mono<ReleaseWorkerResult> deploy(ReleaseWorkerRequest request);
  Mono<ReleaseWorkerResult> start(ReleaseWorkerRequest request);
  Mono<ReleaseWorkerResult> stop(ReleaseWorkerRequest request);
  Mono<ReleaseWorkerResult> rollback(ReleaseWorkerRequest request);
  Mono<ReleaseWorkerResult> healthcheck(ReleaseWorkerRequest request);
  Mono<ReleaseWorkerResult> collectLogs(ReleaseWorkerRequest request);
}
```

- [ ] **Step 4: 实现 Controller**

新增 `POST /internal/release/execute`。控制器先验证传输认证、过期时间、环境、管理模式和适配器注册，再执行单个标准动作。

- [ ] **Step 5: 运行 Worker 测试**

Run: `cd backend; .\mvnw.cmd -pl execution-worker -Dtest=ReleaseWorkerControllerTest test`

Expected: PASS。

- [ ] **Step 6: Commit**

```powershell
git add backend/execution-worker/src/main/java/com/company/opsagent/executionworker/release backend/execution-worker/src/test/java/com/company/opsagent/executionworker/release
git commit -m "Add release worker execution boundary"
```

---

### Task 10: Liberty 和 Tomcat 适配器

**Files:**
- Create: `backend/execution-worker/src/main/java/com/company/opsagent/executionworker/release/LibertyHttpsReleaseAdapter.java`
- Create: `backend/execution-worker/src/main/java/com/company/opsagent/executionworker/release/TomcatWarUploadReleaseAdapter.java`
- Test: `backend/execution-worker/src/test/java/com/company/opsagent/executionworker/release/LibertyHttpsReleaseAdapterTest.java`
- Test: `backend/execution-worker/src/test/java/com/company/opsagent/executionworker/release/TomcatWarUploadReleaseAdapterTest.java`

- [ ] **Step 1: 写 Liberty 适配器测试**

使用 mock WebClient 或 test HTTP server，验证只调用已配置 Liberty HTTPS base URL，拒绝请求参数中夹带的任意 URL。

- [ ] **Step 2: 写 Tomcat 适配器测试**

验证只接受 `ArtifactType.WAR` 和已登记 artifactId，拒绝任意本地路径、ZIP、JAR 和空 checksum。

- [ ] **Step 3: 运行测试确认失败**

Run: `cd backend; .\mvnw.cmd -pl execution-worker -Dtest=LibertyHttpsReleaseAdapterTest,TomcatWarUploadReleaseAdapterTest test`

Expected: FAIL because adapters do not exist.

- [ ] **Step 4: 实现 Liberty 适配器**

`LibertyHttpsReleaseAdapter` 从 Worker 配置读取 base URL 和 credential alias，动作映射为固定 endpoint 名称，例如 `/deploy`、`/start`、`/stop`、`/health`。禁止从请求参数读取完整 URL。

- [ ] **Step 5: 实现 Tomcat 适配器骨架**

首版可以只实现受控 WAR 交付、健康检查和明确失败关闭。未配置 Tomcat 管理方式时返回 `SERVER_MANAGEMENT_MODE_NOT_CONFIGURED`，不尝试 SSH 或自由命令。

- [ ] **Step 6: 运行适配器测试**

Run: `cd backend; .\mvnw.cmd -pl execution-worker -Dtest=LibertyHttpsReleaseAdapterTest,TomcatWarUploadReleaseAdapterTest test`

Expected: PASS。

- [ ] **Step 7: Commit**

```powershell
git add backend/execution-worker/src/main/java/com/company/opsagent/executionworker/release backend/execution-worker/src/test/java/com/company/opsagent/executionworker/release
git commit -m "Add release worker adapters"
```

---

### Task 11: 发布语义事件和审计

**Files:**
- Modify: `backend/contracts/events/semantic-event-v1.schema.json`
- Modify: `backend/control-plane/modules/events/src/main/java/com/company/opsagent/controlplane/modules/events/EventsModule.java`
- Modify: `backend/control-plane/modules/release/src/main/java/com/company/opsagent/controlplane/modules/release/ReleaseWorkflowService.java`
- Test: `backend/control-plane/modules/release/src/test/java/com/company/opsagent/controlplane/modules/release/ReleaseWorkflowServiceTest.java`

- [ ] **Step 1: 写事件测试**

验证创建、确认、节点开始、节点失败和人工接管都会追加事件，payload 不含 credential、secret、password。

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend; .\mvnw.cmd -pl control-plane/modules/release -Dtest=ReleaseWorkflowServiceTest test`

Expected: FAIL because release workflow does not publish release events.

- [ ] **Step 3: 扩展事件契约**

在 `semantic-event-v1.schema.json` 或新增 release 专用事件契约中加入发布类事件。若修改通用契约会影响 P1 事件测试，同步更新 contracts 测试。

- [ ] **Step 4: 在 workflow 中发布事件**

每个状态转换都调用事件存储，审计事件记录 action、resource、policyVersion、result、reason、traceId、requestId。

- [ ] **Step 5: 运行测试**

Run: `cd backend; .\mvnw.cmd -pl contracts,control-plane/modules/release test`

Expected: PASS。

- [ ] **Step 6: Commit**

```powershell
git add backend/contracts/events backend/control-plane/modules/events backend/control-plane/modules/release
git commit -m "Publish release workflow events"
```

---

### Task 12: 发布中心前端数据边界

**Files:**
- Create: `frontend/operator-console/src/schemas/release-center-schemas.js`
- Create: `frontend/operator-console/src/api/release-center-api.js`
- Create: `frontend/operator-console/src/features/release-center/use-release-center.js`
- Test: `frontend/operator-console/src/schemas/schemas.test.js`
- Test: `frontend/operator-console/src/api/client.test.js`

- [ ] **Step 1: 写 Zod schema 测试**

验证生产环境、非 WAR Tomcat 制品和带明文凭据的响应会 parse 失败。

```js
expect(() =>
  releasePlanSchema.parse({
    releaseId: "rel-1",
    targetEnvironment: "prod",
  }),
).toThrow();
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd frontend/operator-console; npm test -- schemas.test.js`

Expected: FAIL because release schemas do not exist.

- [ ] **Step 3: 实现 release schemas**

导出 `releaseApplicationSchema`、`releaseServerSchema`、`releaseArtifactSchema`、`releasePlanSchema`、`releaseCredentialSummarySchema`。`releaseCredentialSummarySchema` 只允许 `credentialAlias`、`fingerprint`、`updatedAt`。

- [ ] **Step 4: 实现 API client**

API 包含 `listReleasePlans`、`createReleasePlan`、`confirmReleasePlan`、`executeReleasePlan`、`uploadTomcatWar`、`listReleaseApplications`、`saveReleaseServer`、`rotateReleaseCredential`。

- [ ] **Step 5: 运行前端单测**

Run: `cd frontend/operator-console; npm test -- schemas.test.js client.test.js`

Expected: PASS。

- [ ] **Step 6: Commit**

```powershell
git add frontend/operator-console/src/schemas/release-center-schemas.js frontend/operator-console/src/api/release-center-api.js frontend/operator-console/src/features/release-center/use-release-center.js frontend/operator-console/src/schemas/schemas.test.js frontend/operator-console/src/api/client.test.js
git commit -m "Add release center frontend data boundary"
```

---

### Task 13: 发布中心页面和导航

**Files:**
- Modify: `frontend/operator-console/src/components/layout/AppShell.jsx`
- Modify: `frontend/operator-console/src/app/router.jsx`
- Create: `frontend/operator-console/src/features/release-center/ReleaseCenterPage.jsx`
- Create: `frontend/operator-console/src/features/release-center/ReleaseCenterPage.module.css`
- Test: `frontend/operator-console/src/features/release-center/ReleaseCenterPage.test.jsx`
- Modify: `frontend/operator-console/src/test/handlers.js`

- [ ] **Step 1: 写页面测试**

验证侧边栏出现 `发布中心`，不会显示“应用发布与运行控制”；页面包含 `发布单`、`制品`、`应用`、`服务器`、`策略`、`凭据` 标签。

- [ ] **Step 2: 运行测试确认失败**

Run: `cd frontend/operator-console; npm test -- ReleaseCenterPage.test.jsx App.test.jsx`

Expected: FAIL because route and page do not exist.

- [ ] **Step 3: 新增导航项**

在 `AppShell.jsx` 导入 `Rocket` 或 `UploadCloud` 图标，添加：

```js
{ icon: Rocket, label: "发布中心", tone: "release", to: "/release" }
```

CSS 增加 `.navTonerelease`，使用克制的非单一蓝紫色调，保持文字不换行。

- [ ] **Step 4: 新增路由**

在 `router.jsx` 增加 `/release` 受保护路由，渲染 `ReleaseCenterPage`。

- [ ] **Step 5: 实现页面骨架**

页面使用 `WorkspacePageFrame`，主内容为标签式布局。首版可先展示真实 API 数据和禁用态按钮，执行按钮只有后端契约准备好后启用。

- [ ] **Step 6: 运行前端测试**

Run: `cd frontend/operator-console; npm test -- ReleaseCenterPage.test.jsx App.test.jsx`

Expected: PASS。

- [ ] **Step 7: Commit**

```powershell
git add frontend/operator-console/src/components/layout/AppShell.jsx frontend/operator-console/src/app/router.jsx frontend/operator-console/src/features/release-center frontend/operator-console/src/test/handlers.js
git commit -m "Add release center workspace"
```

---

### Task 14: 日志分析只读 Skill

**Files:**
- Create: `backend/skills/release-log-analysis/SKILL.md`
- Create: `backend/skills/release-log-analysis/skill.package.yaml`
- Create: `backend/skills/release-log-analysis/schemas/input.schema.json`
- Create: `backend/skills/release-log-analysis/schemas/output.schema.json`
- Create: `backend/skills/release-log-analysis/examples/happy-path.json`
- Create: `backend/skills/release-log-analysis/examples/policy-denied.json`
- Create: `backend/skills/release-log-analysis/examples/invalid-parameters.json`
- Generated: `backend/contracts/skills/packages/release-log-analysis-read/*`

- [ ] **Step 1: 写 Skill 源包**

`SKILL.md` 明确：该 Skill 只分析脱敏日志摘要，不判断最终成功失败，不授予执行权限，不调用目标系统。

- [ ] **Step 2: 写 skill.package.yaml**

使用 `riskLevel: READ_ONLY`、`readOnly: true`、`executor: WORKFLOW`、`outputType: JSON`。参数包含 `releaseId`、`nodeId`、`sanitizedLogExcerpt`、`deterministicStatus`。

- [ ] **Step 3: 运行源包校验**

Run: `tools/skills/skill-package-tool.ps1 validate backend/skills/release-log-analysis`

Expected: PASS。

- [ ] **Step 4: 生成契约包**

Run: `tools/skills/skill-package-tool.ps1 generate backend/skills/release-log-analysis`

Expected: creates `backend/contracts/skills/packages/release-log-analysis-read`.

- [ ] **Step 5: 检查生成产物无漂移**

Run: `tools/skills/skill-package-tool.ps1 generate-all --check`

Expected: PASS。

- [ ] **Step 6: Commit**

```powershell
git add backend/skills/release-log-analysis backend/contracts/skills/packages/release-log-analysis-read
git commit -m "Add release log analysis skill"
```

---

### Task 15: 端到端验证和发布证据

**Files:**
- Modify: `docs/runbooks/release-center.md`
- Modify: `docs/planning/p1-read-only-vertical-slice-evidence.md` only if this task documents why release center is excluded from P1 evidence
- Test: `frontend/operator-console/tests/e2e/operator-console.spec.js`

- [ ] **Step 1: 增加浏览器验收**

Playwright 覆盖登录后进入 `/release`，确认菜单显示 `发布中心`，页面可看到发布单、制品、应用、服务器、策略、凭据。

- [ ] **Step 2: 运行后端验证**

Run: `cd backend; .\mvnw.cmd verify`

Expected: PASS。

- [ ] **Step 3: 运行前端验证**

Run: `cd frontend/operator-console; npm run check && npm run lint && npm run test`

Expected: PASS。

- [ ] **Step 4: 运行仓库检查**

Run: `tools/ci/check-repository.ps1; tools/ci/check-contracts.ps1; tools/ci/scan-secrets.ps1`

Expected: all exit code 0。

- [ ] **Step 5: 记录验收证据**

在 `docs/runbooks/release-center.md` 增加本地验证命令、失败注入方法和回滚演练记录格式。

- [ ] **Step 6: Commit**

```powershell
git add docs/runbooks/release-center.md frontend/operator-console/tests/e2e/operator-console.spec.js
git commit -m "Verify release center vertical slice"
```

---

## Self-Review

- Spec coverage: 本计划覆盖 ADR、契约、目录、凭据、Tomcat WAR 上传、Liberty/Tomcat 适配器、串行工作流、二次确认、语义事件、发布中心页面、日志分析 Skill、验证和运行手册。
- Scope check: 本计划不实现生产发布、多应用编排、自由脚本或 CI 构建，符合 spec 非目标。
- Type consistency: 统一使用 `ReleasePlan`、`ReleaseArtifact`、`ReleaseServer`、`ReleaseWorkflowService`、`ReleaseWorkerRequest`、`ReleaseWorkerResult`、`TargetEnvironment`、`ManagementMode`。
- Execution risk: Task 10 的 Tomcat 真实部署路径允许先失败关闭，避免未决 Tomcat 管理方式阻塞安全主链路。
