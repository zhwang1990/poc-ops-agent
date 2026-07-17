# Windows Demo Environment Launcher Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a double-click Windows demo launcher that starts the local P1 stack, seeds a demo-only `admin` account, and provides richer read-only H2 SQL Workbench data.

**Architecture:** Use pure Batch files under `tools/demo` for start/stop orchestration. Add a Spring `demo` profile plus a profile-gated bootstrap component that creates the local demo account through the M01 repositories and password hasher. Extend the Worker-local H2 data source factory with deterministic in-memory demo tables while preserving the existing SQL Workbench control-plane and Worker enforcement chain.

**Tech Stack:** Windows Batch, Maven Wrapper, Spring Boot profiles, Java 21, R2DBC identity repositories, H2 JDBC, React/Vite dev server.

---

## File Structure

- Create `tools/demo/start-demo.cmd`: double-click startup script, dependency checks, port checks, process launch, logs, PID capture, browser open.
- Create `tools/demo/stop-demo.cmd`: stops only PIDs recorded by `start-demo.cmd`.
- Create `tools/demo/README.md`: Chinese demo instructions, fixed demo account, H2 sample queries, troubleshooting.
- Create `backend/control-plane/bootstrap/src/main/resources/application-demo.yaml`: demo-only profile overrides.
- Create `backend/control-plane/bootstrap/src/main/java/com/company/opsagent/controlplane/bootstrap/config/DemoIdentityBootstrapConfiguration.java`: profile-gated admin account seeding.
- Create `backend/control-plane/bootstrap/src/test/java/com/company/opsagent/controlplane/bootstrap/config/DemoIdentityBootstrapConfigurationTest.java`: verifies demo seed creates and does not overwrite the `admin` account.
- Modify `backend/execution-worker-sqlworkbench/src/main/java/com/company/opsagent/executionworker/sqlworkbench/H2SqlDataSourceFactory.java`: add deterministic demo tables and rows.
- Modify `backend/execution-worker-sqlworkbench/src/test/java/com/company/opsagent/executionworker/sqlworkbench/SqlWorkbenchWorkerConfigurationTest.java`: verify new H2 demo joins/health queries execute.
- Modify `.gitignore`: ignore `.demo/`.
- Modify docs/runbooks or add links only if implementation shows existing runbook needs a demo pointer; prefer `tools/demo/README.md` to keep scope narrow.

### Task 1: Demo Identity Seed

**Files:**
- Create: `backend/control-plane/bootstrap/src/main/resources/application-demo.yaml`
- Create: `backend/control-plane/bootstrap/src/main/java/com/company/opsagent/controlplane/bootstrap/config/DemoIdentityBootstrapConfiguration.java`
- Create: `backend/control-plane/bootstrap/src/test/java/com/company/opsagent/controlplane/bootstrap/config/DemoIdentityBootstrapConfigurationTest.java`

- [ ] **Step 1: Write failing test for demo profile properties**

Add a test method to `DemoIdentityBootstrapConfigurationTest` that loads `application-demo.yaml` and asserts:

```java
assertEquals("built-in", demo.getProperty("ops-agent.security.auth-mode"));
assertEquals(true, demo.getProperty("ops-agent.security.browser-login-enabled"));
assertEquals(false, demo.getProperty("ops-agent.local-oidc-provider.enabled"));
assertEquals("http://127.0.0.1:8091", demo.getProperty("ops-agent.worker.base-url"));
```

- [ ] **Step 2: Run test and verify RED**

Run:

```cmd
backend\mvnw.cmd -f backend\pom.xml -pl control-plane/bootstrap -Dtest=DemoIdentityBootstrapConfigurationTest "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Expected: FAIL because `DemoIdentityBootstrapConfigurationTest` or `application-demo.yaml` does not exist.

- [ ] **Step 3: Add `application-demo.yaml`**

Create:

```yaml
ops-agent:
  security:
    auth-mode: built-in
    browser-login-enabled: true
  local-oidc-provider:
    enabled: false
  worker:
    base-url: http://127.0.0.1:8091
    transport-auth:
      enabled: false
  demo:
    identity-seed:
      enabled: true
      username: admin
      password: ${OPS_AGENT_DEMO_ADMIN_PASSWORD}
      roles:
        - ROLE_ops-admin
        - ROLE_ops-reader
```

- [ ] **Step 4: Run property test and verify GREEN**

Run the same Maven command. Expected: PASS for the property test.

- [ ] **Step 5: Write failing test for account seed**

In `DemoIdentityBootstrapConfigurationTest`, use `ApplicationContextRunner` or a small Spring Boot test context with an in-memory R2DBC H2 database, `BuiltInIdentityConfiguration`, and the new demo bootstrap configuration. Assert that after context startup:

```java
Account account = accountRepository.findByUsername("admin").orElseThrow();
assertEquals("admin", account.username());
assertTrue(account.roleCodes().contains("ROLE_ops-admin"));
assertTrue(account.roleCodes().contains("ROLE_ops-reader"));
PasswordCredential credential = passwordCredentialRepository.findActiveByAccountId(account.accountId()).orElseThrow();
assertFalse(credential.mustChangeOnNextLogin());
assertTrue(((PasswordVerifier) passwordHasher).matches(System.getenv("OPS_AGENT_DEMO_ADMIN_PASSWORD"), credential));
```

- [ ] **Step 6: Run seed test and verify RED**

Run:

```cmd
backend\mvnw.cmd -f backend\pom.xml -pl control-plane/bootstrap -Dtest=DemoIdentityBootstrapConfigurationTest "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Expected: FAIL because `DemoIdentityBootstrapConfiguration` does not exist or no account is seeded.

- [ ] **Step 7: Implement minimal demo seed**

Create `DemoIdentityBootstrapConfiguration.java` with a `@Profile("demo")` and `@ConditionalOnProperty(prefix = "ops-agent.demo.identity-seed", name = "enabled", havingValue = "true")` configuration. Bind properties for username, password, and roles. Register an `ApplicationRunner` that:

```java
if (accountRepository.findByUsername(properties.username()).isPresent()) {
  return;
}
String accountId = "demo-admin";
accountRepository.save(new Account(
    accountId,
    properties.username(),
    AccountStatus.ACTIVE,
    PasswordState.ACTIVE,
    MfaRequirement.NOT_REQUIRED,
    properties.roles(),
    0,
    null));
passwordCredentialRepository.save(passwordHasher.hash(accountId, properties.password(), 1L, false));
```

If role grants are not persisted by `AccountRepository.save`, add a focused repository helper inside bootstrap that inserts role grants through `DatabaseClient` only for this demo seeding path.

- [ ] **Step 8: Run seed test and verify GREEN**

Run the same Maven command. Expected: PASS.

### Task 2: H2 Demo Data

**Files:**
- Modify: `backend/execution-worker-sqlworkbench/src/main/java/com/company/opsagent/executionworker/sqlworkbench/H2SqlDataSourceFactory.java`
- Modify: `backend/execution-worker-sqlworkbench/src/test/java/com/company/opsagent/executionworker/sqlworkbench/SqlWorkbenchWorkerConfigurationTest.java`

- [ ] **Step 1: Write failing H2 join query test**

Add a test in `SqlWorkbenchWorkerConfigurationTest` that executes:

```sql
select c.REGION, count(*) as ORDER_COUNT, sum(o.AMOUNT) as TOTAL_AMOUNT
from PUBLIC.ORDERS o
join PUBLIC.CUSTOMERS c on c.CUSTOMER_ID = o.CUSTOMER_ID
group by c.REGION
order by TOTAL_AMOUNT desc
```

Assert the Worker result succeeds and returns at least two regions.

- [ ] **Step 2: Run H2 test and verify RED**

Run:

```cmd
backend\mvnw.cmd -f backend\pom.xml -pl execution-worker-sqlworkbench -Dtest=SqlWorkbenchWorkerConfigurationTest "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Expected: FAIL because `PUBLIC.CUSTOMERS` does not exist.

- [ ] **Step 3: Add H2 demo tables and rows**

Extend `H2SqlDataSourceFactory.initialize` with `CUSTOMERS`, `INCIDENTS`, and `SERVICE_HEALTH`. Use `CREATE TABLE IF NOT EXISTS` and `MERGE INTO ... KEY(...)` so repeated starts are idempotent. Preserve the existing `ORDERS` rows and add additional `ORDERS` rows referencing `CUSTOMERS`.

- [ ] **Step 4: Run H2 test and verify GREEN**

Run the same Maven command. Expected: PASS.

- [ ] **Step 5: Add service health query test**

Add a test that executes:

```sql
select SERVICE_NAME, ENVIRONMENT, HEALTH_STATUS, ERROR_RATE_PERCENT, P95_LATENCY_MS
from PUBLIC.SERVICE_HEALTH
where ENVIRONMENT = 'test'
order by P95_LATENCY_MS desc
```

Assert it succeeds and returns rows with `ENVIRONMENT = test`.

- [ ] **Step 6: Run H2 tests and verify GREEN**

Run the same Maven command. Expected: PASS.

### Task 3: Batch Launcher

**Files:**
- Create: `tools/demo/start-demo.cmd`
- Create: `tools/demo/stop-demo.cmd`
- Modify: `.gitignore`

- [ ] **Step 1: Write static script safety test**

If there is no existing script test harness, create a lightweight test under `tools/demo/test-demo-scripts.cmd` that verifies:

```cmd
findstr /I /C:"powershell" tools\demo\start-demo.cmd && exit /b 1
findstr /I /C:"powershell" tools\demo\stop-demo.cmd && exit /b 1
findstr /I /C:"spring-boot.run.profiles=demo" tools\demo\start-demo.cmd >nul || exit /b 1
findstr /I /C:"OPS_AGENT_DEMO_ADMIN_PASSWORD" tools\demo\start-demo.cmd >nul || exit /b 1
```

- [ ] **Step 2: Run script test and verify RED**

Run:

```cmd
tools\demo\test-demo-scripts.cmd
```

Expected: FAIL because scripts do not exist yet.

- [ ] **Step 3: Create `start-demo.cmd`**

Implement pure Batch:

- `@echo off` and `setlocal EnableExtensions EnableDelayedExpansion`
- Resolve repo root from `%~dp0..\..`
- Create `.demo\logs` and `.demo\pids`
- Check `java.exe`, `npm.cmd`, and `backend\mvnw.cmd`
- Check ports using `netstat -ano | findstr ":8091 "` etc.
- Start three windows with stable titles:
  - `OpsAgent Demo Worker`
  - `OpsAgent Demo Control Plane`
  - `OpsAgent Demo Console`
- Redirect logs to `.demo\logs\*.log`
- Write launched PIDs by querying `wmic process where "CommandLine like ..."` if available, with a fallback to window-title stop guidance.
- Open browser with `start "" http://127.0.0.1:5173`
- Print the `OPS_AGENT_DEMO_ADMIN_PASSWORD` injection requirement and sample SQL.

- [ ] **Step 4: Create `stop-demo.cmd`**

Implement pure Batch:

- Read `.demo\pids\*.pid`
- Run `taskkill /PID <pid> /T /F`
- Report already-stopped PIDs
- Do not kill processes discovered only by port.

- [ ] **Step 5: Add `.demo/` to `.gitignore`**

Append `.demo/` if not already ignored.

- [ ] **Step 6: Run script test and verify GREEN**

Run:

```cmd
tools\demo\test-demo-scripts.cmd
```

Expected: PASS.

### Task 4: Demo Documentation

**Files:**
- Create: `tools/demo/README.md`
- Optionally modify: `docs/runbooks/README.md`

- [ ] **Step 1: Write README**

Document in Chinese:

- Prerequisites: Java 21, Node.js 20+, npm, no PowerShell required.
- Start: double-click `start-demo.cmd`.
- Stop: double-click `stop-demo.cmd`.
- Login: `admin` with the value injected through `OPS_AGENT_DEMO_ADMIN_PASSWORD`.
- SQL connection: `h2-local-test`.
- Three sample SELECT queries from the spec.
- DML remains disabled.
- Logs live under `.demo\logs`.

- [ ] **Step 2: Review README for production confusion**

Search:

```cmd
findstr /I /C:"生产部署" tools\demo\README.md
```

Expected: README states this is not production deployment.

### Task 5: End-to-End Verification

**Files:**
- No new files unless verification reveals a focused fix is needed.

- [ ] **Step 1: Run focused backend tests**

Run:

```cmd
backend\mvnw.cmd -f backend\pom.xml -pl contracts,control-plane/modules/identity,control-plane/bootstrap,execution-worker-sqlworkbench,execution-worker -am -Dtest=DemoIdentityBootstrapConfigurationTest,SqlWorkbenchWorkerConfigurationTest "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Expected: PASS.

- [ ] **Step 2: Run frontend build**

Run:

```cmd
cd frontend\operator-console
npm run build
```

Expected: PASS.

- [ ] **Step 3: Manual smoke if time allows**

Run `tools\demo\start-demo.cmd`, log in with `admin` and the value injected through `OPS_AGENT_DEMO_ADMIN_PASSWORD`, execute the three H2 sample SELECT queries, then run `tools\demo\stop-demo.cmd`.

- [ ] **Step 4: Final diff review**

Run:

```cmd
git diff --stat
git diff --check
```

Expected: no whitespace errors; changed files match this plan.

## Self-Review

- Spec coverage: The plan covers pure Batch start/stop, demo profile, fixed `admin` login, H2 fake data, docs, and verification.
- Placeholder scan: No TBD/TODO placeholders are present.
- Type consistency: Account seed uses existing `Account`, `PasswordCredential`, `PasswordHasher`, and identity repository names. H2 tests use existing `SqlWorkbenchWorkerConfigurationTest` patterns.
