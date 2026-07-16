# Controlled SQL DML Review Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 Task 5 的预检授权绑定、幂等、未知交接恢复和 M05/M09 错误边界缺陷，使非生产受控 DML 在缺少可验证预检回执时始终失败关闭。

**Architecture:** 在 `backend/contracts` 增加向后兼容的 DML 预检回执字段和 HMAC canonical payload。M09 在 Worker 返回实际影响预览后签发回执，M05 通过仅依赖 contracts 的回执验证端口在创建工作流前重新验证；持久化工作流保存回执语义摘要而不保存回执、SQL、参数或预览值。运行中工作流保存执行过期时间，重复请求先持久化人工交接再返回稳定错误，且绝不重派 Worker。

**Tech Stack:** Java 21、Spring Boot WebFlux、R2DBC、H2 测试数据库、Maven、JUnit 5、版本化 Java records、HmacSHA256。

## Global Constraints

- P2 仅允许 `dev`、`sit`、`uat` 非生产受控 DML；不得新增生产写执行或自动重试。
- M05 不得依赖 M09；跨模块边界仅通过明确端口和 `backend/contracts` 强类型契约。
- HMAC 密钥只能由运行环境注入；回执、审计、日志和持久化工作流不得保存 SQL、参数值或预览样本。
- 任一回执、审计、持久化或未知交接失败必须返回稳定失败码，且不得调用或重派 Worker。

---

### Task 1: Versioned Preflight Receipt Contract

**Files:**
- Create: `backend/contracts/src/main/java/com/company/opsagent/contracts/sqlworkbench/SqlDmlPreflightReceipt.java`
- Modify: `backend/contracts/src/main/java/com/company/opsagent/contracts/sqlworkbench/SqlDmlCommitRequest.java`
- Modify: `backend/contracts/src/main/java/com/company/opsagent/contracts/sqlworkbench/SqlDmlPreflightResult.java`
- Modify: `backend/contracts/src/main/java/com/company/opsagent/contracts/workflow/WorkerRequestSignature.java`
- Test: `backend/contracts/src/test/java/com/company/opsagent/contracts/sqlworkbench/SqlDmlPreflightResultTest.java`
- Test: `backend/contracts/src/test/java/com/company/opsagent/contracts/workflow/WorkerRequestSignatureTest.java`

**Interfaces:**
- Produces `SqlDmlPreflightReceipt` with key id, signed binding digest, issue/expiry instants, operator/request identifiers, target fields, SQL/parameter hashes, policy version/selection digest, Worker-preview digest and HMAC signature.
- Preserves existing three-field constructors for `SqlDmlCommitRequest` and `SqlDmlPreflightResult`; only a validated receipt authorizes DML submit.
- Adds canonical receipt-binding, receipt-signature and impact-preview digest methods to `WorkerRequestSignature`.

- [ ] **Step 1: Write failing contract tests**

Add tests that require a `1.1` preflight result to contain a receipt, require a `1.1` commit to carry it, and prove signature payload changes when the Worker impact preview, operator, request, SQL hash, parameter hash, policy version or selection changes.

- [ ] **Step 2: Run contract tests to verify RED**

Run: `./mvnw -pl contracts -Dtest=SqlDmlPreflightResultTest,WorkerRequestSignatureTest test`

Expected: compilation failures for the missing receipt type and canonical methods.

- [ ] **Step 3: Implement minimal versioned records and canonicalization**

Use `WorkerRequestSignature.canonicalFields(...)` internally for length-prefixed fields; HMAC verification must use `WorkerRequestSignature.matches(...)`. The persisted semantic receipt digest excludes request id, issue time, expiry and signature so a fresh authorized request can reuse an existing terminal workflow.

- [ ] **Step 4: Run contract tests to verify GREEN**

Run the Step 2 command and require `0` failures and `0` errors.

### Task 2: Server Receipt Issue and Verification Boundary

**Files:**
- Create: `backend/control-plane/modules/sqlworkbench/src/main/java/com/company/opsagent/controlplane/modules/sqlworkbench/SqlDmlPreflightReceiptService.java`
- Create: `backend/control-plane/modules/sqlworkbench/src/main/java/com/company/opsagent/controlplane/modules/sqlworkbench/SqlDmlPreflightReceiptProperties.java`
- Create: `backend/control-plane/modules/workflow/src/main/java/com/company/opsagent/controlplane/modules/workflow/ControlledSqlDmlPreflightReceiptVerifier.java`
- Modify: `backend/control-plane/modules/sqlworkbench/src/main/java/com/company/opsagent/controlplane/modules/sqlworkbench/DefaultSqlWorkbenchService.java`
- Modify: `backend/control-plane/modules/workflow/src/main/java/com/company/opsagent/controlplane/modules/workflow/ControlledSqlDmlWorkflowRequest.java`
- Modify: `backend/control-plane/modules/workflow/src/main/java/com/company/opsagent/controlplane/modules/workflow/ControlledSqlDmlWorkflowService.java`
- Modify: `backend/control-plane/bootstrap/src/main/java/com/company/opsagent/controlplane/bootstrap/config/SqlWorkbenchConfiguration.java`
- Modify: `backend/control-plane/bootstrap/src/main/java/com/company/opsagent/controlplane/bootstrap/config/WorkflowConfiguration.java`
- Modify: `backend/control-plane/bootstrap/src/main/java/com/company/opsagent/controlplane/bootstrap/api/SqlWorkbenchController.java`
- Test: `backend/control-plane/modules/sqlworkbench/src/test/java/com/company/opsagent/controlplane/modules/sqlworkbench/DefaultSqlWorkbenchServiceTest.java`
- Test: `backend/control-plane/modules/workflow/src/test/java/com/company/opsagent/controlplane/modules/workflow/ControlledSqlDmlWorkflowServiceTest.java`
- Test: `backend/control-plane/bootstrap/src/test/java/com/company/opsagent/controlplane/bootstrap/api/SqlWorkbenchControllerTest.java`

**Interfaces:**
- `SqlDmlPreflightReceiptService.issue(...)` runs only after Worker preflight and returns the signed receipt included in `SqlDmlPreflightResult`.
- `ControlledSqlDmlPreflightReceiptVerifier.verify(ControlledSqlDmlWorkflowRequest)` is an M05 port that throws `WorkflowException`-compatible stable receipt errors.
- M09 calls no Worker for a direct/missing, expired, tampered, wrong operator/request/target/SQL/parameters/policy receipt.

- [ ] **Step 1: Write failing service and controller tests**

Cover missing receipt, tampered receipt, expired receipt, receipt issued after actual preview, and controller parsing of the receipt. Assert no M05 executor and no Worker call for rejected commits.

- [ ] **Step 2: Run focused tests to verify RED**

Run: `./mvnw -pl control-plane/modules/sqlworkbench,control-plane/modules/workflow,control-plane/bootstrap -am -Dtest=DefaultSqlWorkbenchServiceTest,ControlledSqlDmlWorkflowServiceTest,SqlWorkbenchControllerTest test`

Expected: receipt behavior fails because the current commit accepts only static/recomputed preflight hashes.

- [ ] **Step 3: Implement receipt issue, verify, parse and wire behavior**

The signer derives `impactPreviewDigest` from the actual Worker response and signs the full receipt with environment-injected receipt HMAC credentials. The verifier recomputes only request-bound hashes, validates expiry and constant-time signature, then exposes only the signed semantic receipt digest to M05 persistence.

- [ ] **Step 4: Run focused tests to verify GREEN**

Run the Step 2 command and require `0` failures and `0` errors.

### Task 3: Idempotency and Durable Unknown-Handoff Recovery

**Files:**
- Modify: `backend/control-plane/modules/workflow/src/main/java/com/company/opsagent/controlplane/modules/workflow/ControlledSqlDmlWorkflow.java`
- Modify: `backend/control-plane/modules/workflow/src/main/java/com/company/opsagent/controlplane/modules/workflow/ControlledSqlDmlWorkflowStore.java`
- Modify: `backend/control-plane/modules/workflow/src/main/java/com/company/opsagent/controlplane/modules/workflow/R2dbcControlledSqlDmlWorkflowStore.java`
- Create: `backend/control-plane/modules/workflow/src/main/resources/sql/migrations/V005__controlled_sql_dml_execution_expiry.sql`
- Modify: `backend/control-plane/modules/sqlworkbench/src/main/java/com/company/opsagent/controlplane/modules/sqlworkbench/DefaultSqlWorkbenchService.java`
- Test: `backend/control-plane/modules/workflow/src/test/java/com/company/opsagent/controlplane/modules/workflow/ControlledSqlDmlWorkflowServiceTest.java`
- Test: `backend/control-plane/modules/workflow/src/test/java/com/company/opsagent/controlplane/modules/workflow/R2dbcControlledSqlDmlWorkflowStoreTest.java`
- Test: `backend/control-plane/bootstrap/src/test/java/com/company/opsagent/controlplane/bootstrap/service/WebClientSqlWorkbenchWorkerClientTest.java`

**Interfaces:**
- `markSubmitted(workflowId, submittedAt, expiresAt)` persists the deterministic dispatch expiry with the `RUNNING` transition.
- `RUNNING` duplicate handling transitions an expired/null-expiry workflow to `UNKNOWN_REQUIRES_HANDOFF` with audit before returning; a handoff persistence failure returns `SQL_DML_HANDOFF_PERSISTENCE_FAILED` and dispatch count remains zero.
- `SqlDmlExecutionBinding.bindingHash` excludes `policyDecision.decisionId()` but retains policy version and selection/receipt semantic digest.

- [ ] **Step 1: Write failing workflow and Worker-client tests**

Add tests for fresh-request terminal reuse with a different policy decision id, stale-running handoff audit, failed handoff persistence, no Worker replay, and 5xx/decode/timeout Worker results being uncertain.

- [ ] **Step 2: Run focused tests to verify RED**

Run: `./mvnw -pl control-plane/modules/workflow,control-plane/bootstrap -am -Dtest=ControlledSqlDmlWorkflowServiceTest,R2dbcControlledSqlDmlWorkflowStoreTest,WebClientSqlWorkbenchWorkerClientTest test`

Expected: stale workflows remain `RUNNING`, a handoff write can be swallowed, and fresh policy-decision retries conflict.

- [ ] **Step 3: Implement expiry persistence and fail-closed recovery**

Persist expiry without SQL/parameter/preview values; make every uncertain result call a non-swallowing handoff transition. A successful handoff returns `SQL_DML_RESULT_UNKNOWN`; a failed handoff returns `SQL_DML_HANDOFF_PERSISTENCE_FAILED`.

- [ ] **Step 4: Run focused tests to verify GREEN**

Run the Step 2 command and require `0` failures and `0` errors.

### Task 4: M09 Error Boundary, Evidence and Release Gate

**Files:**
- Modify: `backend/control-plane/modules/sqlworkbench/src/main/java/com/company/opsagent/controlplane/modules/sqlworkbench/DefaultSqlWorkbenchService.java`
- Modify: `.superpowers/sdd/task-5-report.md`
- Modify: `docs/planning/project-plan.md`

- [ ] **Step 1: Remove M09 catches for M05 store-specific exceptions**

Leave only `ControlledSqlDmlWorkflowService.WorkflowException` mapping at the M09 boundary.

- [ ] **Step 2: Run focused tests and static checks**

Run: `git diff --check` and the Task 2/Task 3 focused Maven commands.

- [ ] **Step 3: Run the clean relevant reactor**

Run: `./mvnw -pl control-plane/modules/workflow,control-plane/modules/sqlworkbench,control-plane/bootstrap -am clean test`

Expected: all selected reactor modules succeed with `0` failures and `0` errors.

- [ ] **Step 4: Update evidence and commit fixes**

Record exact RED/GREEN commands, outcomes, contract compatibility, migration, rollback effect and remaining security-review requirement in the existing Task 5 report. Commit the focused security remediation with an imperative English message.

## Coverage Review

- Receipt authenticity, expiry, request/target/operator/policy/preview binding: Tasks 1 and 2.
- No static-hash substitute and direct-commit fail-closed: Task 2.
- Fresh-request semantic idempotency and no decision-id binding: Task 3.
- Stale workflow recovery, durable handoff, failure behavior and zero replay: Task 3.
- M05-to-M09 stable exception mapping only: Task 4.
- Contract, controller, Worker transport, persistence, migration and reactor coverage: Tasks 1 through 4.
