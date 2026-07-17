# M04 Dynamic Model Provider Settings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an administrator-managed model provider registry that supports direct API Key entry, encrypted storage, default provider switching, and runtime use by the P1 AgentScope diagnostic path.

**Architecture:** M04 owns model provider domain, encryption, repository, management service, and runtime default-provider resolution. The bootstrap app exposes protected management APIs and initializes the M04 schema. M09 adds an admin model settings page backed by typed API/Zod contracts. Existing M01/M02 request filtering remains the authorization boundary, and audit records must never contain API Key plaintext or ciphertext.

**Tech Stack:** Java 21, Spring Boot WebFlux, R2DBC, H2 tests, Reactor, JDK AES-GCM, React/JSX, JSDoc `checkJs`, Zod, Vitest, Testing Library.

---

## File Structure

Backend files:

- Create: `backend/control-plane/modules/agentruntime/src/main/java/com/company/opsagent/controlplane/modules/agentruntime/ModelProviderType.java`
- Create: `backend/control-plane/modules/agentruntime/src/main/java/com/company/opsagent/controlplane/modules/agentruntime/ModelProvider.java`
- Create: `backend/control-plane/modules/agentruntime/src/main/java/com/company/opsagent/controlplane/modules/agentruntime/ModelProviderStatus.java`
- Create: `backend/control-plane/modules/agentruntime/src/main/java/com/company/opsagent/controlplane/modules/agentruntime/ModelProviderSummary.java`
- Create: `backend/control-plane/modules/agentruntime/src/main/java/com/company/opsagent/controlplane/modules/agentruntime/ModelProviderCreateCommand.java`
- Create: `backend/control-plane/modules/agentruntime/src/main/java/com/company/opsagent/controlplane/modules/agentruntime/ModelProviderUpdateCommand.java`
- Create: `backend/control-plane/modules/agentruntime/src/main/java/com/company/opsagent/controlplane/modules/agentruntime/ModelProviderApiKeyCommand.java`
- Create: `backend/control-plane/modules/agentruntime/src/main/java/com/company/opsagent/controlplane/modules/agentruntime/ModelProviderStore.java`
- Create: `backend/control-plane/modules/agentruntime/src/main/java/com/company/opsagent/controlplane/modules/agentruntime/InMemoryModelProviderStore.java`
- Create: `backend/control-plane/modules/agentruntime/src/main/java/com/company/opsagent/controlplane/modules/agentruntime/R2dbcModelProviderStore.java`
- Create: `backend/control-plane/modules/agentruntime/src/main/java/com/company/opsagent/controlplane/modules/agentruntime/ModelProviderSecretCodec.java`
- Create: `backend/control-plane/modules/agentruntime/src/main/java/com/company/opsagent/controlplane/modules/agentruntime/AesGcmModelProviderSecretCodec.java`
- Create: `backend/control-plane/modules/agentruntime/src/main/java/com/company/opsagent/controlplane/modules/agentruntime/ModelProviderManagementService.java`
- Create: `backend/control-plane/modules/agentruntime/src/main/java/com/company/opsagent/controlplane/modules/agentruntime/DefaultModelProviderManagementService.java`
- Create: `backend/control-plane/modules/agentruntime/src/main/java/com/company/opsagent/controlplane/modules/agentruntime/ModelProviderProbe.java`
- Create: `backend/control-plane/modules/agentruntime/src/main/java/com/company/opsagent/controlplane/modules/agentruntime/ModelProviderProbeResult.java`
- Create: `backend/control-plane/modules/agentruntime/src/main/java/com/company/opsagent/controlplane/modules/agentruntime/ProviderResolvingAgentscopeAgentClient.java`
- Create: `backend/control-plane/modules/agentruntime/src/main/resources/sql/migrations/V001__model_provider_schema.sql`
- Modify: `backend/control-plane/modules/agentruntime/pom.xml`
- Modify: `backend/control-plane/bootstrap/src/main/java/com/company/opsagent/controlplane/bootstrap/config/AgentRuntimeProperties.java`
- Modify: `backend/control-plane/bootstrap/src/main/java/com/company/opsagent/controlplane/bootstrap/config/AgentRuntimeConfiguration.java`
- Modify: `backend/control-plane/bootstrap/src/main/java/com/company/opsagent/controlplane/bootstrap/config/WorkflowConfiguration.java`
- Modify: `backend/control-plane/bootstrap/src/main/java/com/company/opsagent/controlplane/bootstrap/security/PolicyEnforcementWebFilter.java`
- Modify: `backend/control-plane/bootstrap/src/main/resources/application.yaml`
- Create: `backend/control-plane/bootstrap/src/main/java/com/company/opsagent/controlplane/bootstrap/api/ModelProviderController.java`
- Create tests in matching `src/test/java/.../agentruntime/` and bootstrap tests.

Frontend files:

- Create: `frontend/operator-console/src/schemas/model-provider-schemas.js`
- Create: `frontend/operator-console/src/api/model-provider-api.js`
- Create: `frontend/operator-console/src/features/model-settings/ModelSettingsPage.jsx`
- Create: `frontend/operator-console/src/features/model-settings/ModelSettingsPage.module.css`
- Create: `frontend/operator-console/src/features/model-settings/ModelSettingsPage.test.jsx`
- Modify: `frontend/operator-console/src/app/router.jsx`
- Modify: `frontend/operator-console/src/components/layout/AppShell.jsx`
- Modify: `frontend/operator-console/src/api/client.test.js`
- Modify: `frontend/operator-console/src/schemas/schemas.test.js`

Docs:

- Modify: `docs/adr/0007-agentscope-java-primary-agent-runtime.md`
- Modify: `docs/runbooks/agentscope-java-primary-runtime-poc.md`
- Modify: `docs/planning/project-plan.md`
- Modify: `docs/planning/design-traceability.md`
- Modify: `docs/standards/p1-threat-model.md`

---

### Task 1: Backend Domain and Secret Codec

**Files:**
- Create domain and command records under `backend/control-plane/modules/agentruntime/src/main/java/com/company/opsagent/controlplane/modules/agentruntime/`
- Test: `backend/control-plane/modules/agentruntime/src/test/java/com/company/opsagent/controlplane/modules/agentruntime/ModelProviderTest.java`
- Test: `backend/control-plane/modules/agentruntime/src/test/java/com/company/opsagent/controlplane/modules/agentruntime/AesGcmModelProviderSecretCodecTest.java`

- [ ] **Step 1: Write failing domain tests**

Create tests that prove:

```java
assertThrows(IllegalArgumentException.class, () -> validCreateCommand().withBaseUrl("http://api.example.com/v1"));
assertThrows(IllegalArgumentException.class, () -> validCreateCommand().withModelName(" "));
assertEquals(ModelProviderType.OPENAI_COMPATIBLE, created.providerType());
assertFalse(created.apiKeyCiphertext().contains("${OPS_AGENT_AGENT_RUNTIME_API_KEY}"));
```

- [ ] **Step 2: Run domain tests and verify RED**

Run:

```powershell
cd backend
.\mvnw.cmd -pl control-plane/modules/agentruntime -am '-Dtest=ModelProviderTest,AesGcmModelProviderSecretCodecTest' test
```

Expected: compile failure because the new domain and codec classes do not exist.

- [ ] **Step 3: Implement minimal domain and codec**

Implement immutable records with constructor validation:

- `providerId`, `displayName`, `baseUrl`, `modelName`, and `apiKeyFingerprint` are nonblank.
- `providerType` defaults through command construction to `OPENAI_COMPATIBLE`.
- `baseUrl` accepts `https://...` and explicit local `http://127.0.0.1` / `http://localhost`.
- API Key plaintext is never stored in `ModelProvider`.
- `AesGcmModelProviderSecretCodec` uses `AES/GCM/NoPadding`, random 12-byte nonce, Base64 ciphertext/nonce, and SHA-256 fingerprint prefix.

- [ ] **Step 4: Run domain tests and verify GREEN**

Run the same Maven command. Expected: tests pass.

### Task 2: Backend Store and Management Service

**Files:**
- Create: `ModelProviderStore`, `InMemoryModelProviderStore`, `DefaultModelProviderManagementService`
- Tests: `DefaultModelProviderManagementServiceTest.java`, `InMemoryModelProviderStoreTest.java`

- [ ] **Step 1: Write failing service tests**

Cover:

```java
service.create(command, "admin").apiKeyConfigured();
assertFalse(service.list().getFirst().apiKeyCiphertext().isPresent());
service.setDefault(providerId, "admin");
assertTrue(service.getDefault().orElseThrow().defaultProvider());
assertThrows(IllegalStateException.class, () -> service.setDefault(providerWithoutKey, "admin"));
```

Also verify API Key update increments `configVersion`, updates fingerprint, and does not change non-sensitive fields.

- [ ] **Step 2: Run service tests and verify RED**

Run:

```powershell
cd backend
.\mvnw.cmd -pl control-plane/modules/agentruntime -am '-Dtest=DefaultModelProviderManagementServiceTest,InMemoryModelProviderStoreTest' test
```

Expected: compile failure or missing implementation failure.

- [ ] **Step 3: Implement store and service**

Implement synchronous service methods returning domain summaries:

- `list()`
- `create(ModelProviderCreateCommand, String operatorId)`
- `update(String providerId, ModelProviderUpdateCommand, String operatorId)`
- `rotateApiKey(String providerId, ModelProviderApiKeyCommand, String operatorId)`
- `test(String providerId)`
- `setDefault(String providerId, String operatorId)`
- `disable(String providerId, String operatorId)`
- `defaultProvider()`

Keep default switching atomic inside the store abstraction.

- [ ] **Step 4: Run service tests and verify GREEN**

Run the same Maven command. Expected: tests pass.

### Task 3: R2DBC Persistence and Bootstrap Schema

**Files:**
- Modify: `backend/control-plane/modules/agentruntime/pom.xml`
- Create: `R2dbcModelProviderStore.java`
- Create: `src/main/resources/sql/migrations/V001__model_provider_schema.sql`
- Modify: `AgentRuntimeConfiguration.java`
- Tests: `R2dbcModelProviderStoreTest.java`, bootstrap integration test.

- [ ] **Step 1: Write failing persistence tests**

Test that:

```java
store.save(createdProvider).block();
assertEquals(createdProvider.providerId(), store.findById(providerId).block().providerId());
store.setDefault(providerId).block();
assertEquals(providerId, store.findDefault().block().providerId());
```

Also test that a second default clears the first default.

- [ ] **Step 2: Run persistence tests and verify RED**

Run:

```powershell
cd backend
.\mvnw.cmd -pl control-plane/modules/agentruntime -am '-Dtest=R2dbcModelProviderStoreTest' test
```

Expected: missing `R2dbcModelProviderStore` or migration failure.

- [ ] **Step 3: Implement persistence**

Add `spring-r2dbc` dependency and test `r2dbc-h2` dependency to the M04 module. Implement table columns for provider metadata, encrypted secret fields, fingerprint, version, timestamps, and default flag. Add a bootstrap `ConnectionFactoryInitializer` or extend existing initialization to run the M04 migration.

- [ ] **Step 4: Run persistence tests and verify GREEN**

Run the same Maven command. Expected: tests pass.

### Task 4: Protected Backend API and Policy Actions

**Files:**
- Create: `ModelProviderController.java`
- Modify: `PolicyEnforcementWebFilter.java`
- Modify: `application.yaml`
- Tests: `ModelProviderControllerTest.java` or existing `ControlPlaneApplicationTest.java`

- [ ] **Step 1: Write failing API tests**

Test:

```java
webTestClient.get().uri("/internal/model-providers").exchange().expectStatus().isOk();
webTestClient.post().uri("/internal/model-providers").bodyValue(validJsonWithApiKey()).exchange().expectStatus().isOk();
webTestClient.get().uri("/internal/model-providers").exchange().expectBody().jsonPath("$[0].apiKey").doesNotExist();
```

Add a reader-token request for write API and expect `403`.

- [ ] **Step 2: Run API tests and verify RED**

Run:

```powershell
cd backend
.\mvnw.cmd -pl control-plane/bootstrap -am '-Dtest=ControlPlaneApplicationTest' test
```

Expected: `404` or missing policy mapping.

- [ ] **Step 3: Implement API and actions**

Add actions:

- `internal.model-providers.read`
- `internal.model-providers.write`
- `internal.model-providers.api-key.rotate`
- `internal.model-providers.test`
- `internal.model-providers.switch`

Map API paths in `PolicyEnforcementWebFilter`. Configure all actions for `ROLE_ops-admin` only. Controller must parse JSON with field allowlists and never return API Key plaintext/ciphertext.

- [ ] **Step 4: Run API tests and verify GREEN**

Run the same Maven command. Expected: tests pass.

### Task 5: Dynamic Agent Runtime Resolution

**Files:**
- Create: `ProviderResolvingAgentscopeAgentClient.java`
- Modify: `AgentRuntimeConfiguration.java`
- Modify: `AgentTaskResult.java` if a new stable status is needed
- Tests: `ProviderResolvingAgentscopeAgentClientTest.java`, update existing runtime tests.

- [ ] **Step 1: Write failing runtime tests**

Test:

```java
client.run(invocation).block().status().equals("AGENT_RUNTIME_NOT_CONFIGURED");
```

when no default provider exists. Test that when a default provider exists, the factory receives decrypted API Key, model name, baseUrl, max iterations, and timeout.

- [ ] **Step 2: Run runtime tests and verify RED**

Run:

```powershell
cd backend
.\mvnw.cmd -pl control-plane/modules/agentruntime -am '-Dtest=ProviderResolvingAgentscopeAgentClientTest,AgentscopePrimaryAgentRuntimeServiceTest' test
```

Expected: missing class or static property-based client still used.

- [ ] **Step 3: Implement runtime resolver**

Replace the single startup `AgentscopeAgentClient` with a resolving client that reads the default provider per invocation, decrypts the key, builds an OpenAI-compatible delegate, and returns stable not-configured/fake-key/decryption-failed statuses without calling tools.

- [ ] **Step 4: Run runtime tests and verify GREEN**

Run the same Maven command. Expected: tests pass.

### Task 6: Frontend Schema and API

**Files:**
- Create: `model-provider-schemas.js`
- Create: `model-provider-api.js`
- Modify: `schemas.test.js`
- Modify: `client.test.js`

- [ ] **Step 1: Write failing frontend contract tests**

Test that model provider list responses parse, API Key is absent from response schema, and create/update requests reject unexpected fields.

- [ ] **Step 2: Run frontend tests and verify RED**

Run:

```powershell
cd frontend/operator-console
npm test -- src/schemas/schemas.test.js src/api/client.test.js
```

Expected: missing schema/API failures.

- [ ] **Step 3: Implement schema and API wrappers**

Add Zod schemas for provider summary, create, update, API key rotate, test result, and default switch. Add API functions wrapping `/internal/model-providers`.

- [ ] **Step 4: Run frontend tests and verify GREEN**

Run the same npm command. Expected: tests pass.

### Task 7: Frontend Model Settings Page

**Files:**
- Create: `ModelSettingsPage.jsx`
- Create: `ModelSettingsPage.module.css`
- Create: `ModelSettingsPage.test.jsx`
- Modify: `router.jsx`
- Modify: `AppShell.jsx`

- [ ] **Step 1: Write failing component tests**

Test:

```jsx
expect(screen.getByText("模型设置")).toBeInTheDocument();
await user.type(screen.getByLabelText("API Key"), "${OPS_AGENT_AGENT_RUNTIME_API_KEY}");
await user.click(screen.getByRole("button", { name: "保存供应方" }));
expect(screen.getByLabelText("API Key")).toHaveValue("");
expect(screen.queryByText("${OPS_AGENT_AGENT_RUNTIME_API_KEY}")).not.toBeInTheDocument();
```

Also test `设为默认` and `测试连接` call the API layer.

- [ ] **Step 2: Run component tests and verify RED**

Run:

```powershell
cd frontend/operator-console
npm test -- src/features/model-settings/ModelSettingsPage.test.jsx
```

Expected: missing page failure.

- [ ] **Step 3: Implement page and routing**

Add an admin-oriented page with table/list, create form, separate API Key input, test action, set-default action, and disable action. Add navigation item with a lucide settings icon. Do not use localStorage/sessionStorage for key material.

- [ ] **Step 4: Run component tests and verify GREEN**

Run the same npm command. Expected: tests pass.

### Task 8: Documentation and Final Verification

**Files:**
- Modify docs listed in the File Structure section.

- [ ] **Step 1: Update docs**

Update ADR 0007, runbook, project plan, traceability, and threat model with dynamic provider registry, direct API Key input, encrypted storage, authorization, audit, and rollback.

- [ ] **Step 2: Run backend verification**

Run:

```powershell
cd backend
.\mvnw.cmd -pl control-plane/modules/agentruntime,control-plane/bootstrap -am test
```

Expected: build success.

- [ ] **Step 3: Run frontend verification**

Run:

```powershell
cd frontend/operator-console
npm test -- src/schemas/schemas.test.js src/api/client.test.js src/features/model-settings/ModelSettingsPage.test.jsx
npm run check
```

Expected: all selected tests and check pass.

- [ ] **Step 4: Run repository safety checks**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\ci\scan-secrets.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\ci\check-repository.ps1
```

Expected: both checks pass and no API Key pattern is detected.

---

## Self-Review Notes

- Spec coverage: model provider CRUD, direct API Key input, encrypted storage, default switching, runtime resolution, M09 page, M02 policy, audit-safe responses, docs, and verification are all represented.
- Scope intentionally excludes key-ring rotation, per-request model selection, model marketplace, multi-tenant routing, cost optimization, and approval workflow.
- The plan keeps production code behind failing tests first and avoids modifying the unrelated existing Skill Registry files in the worktree.
