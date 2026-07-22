# M03 Skill Source Package Registration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the P1 Skill registration closed loop where developers maintain AgentScope-compatible source packages and a repository tool generates the M03 platform contract packages.

**Architecture:** Keep M03 runtime unchanged: it still scans `backend/contracts/skills/packages`. Add a repository-level PowerShell tool under `tools/skills` that validates `backend/skills/<skill>/SKILL.md + skill.package.yaml`, generates platform contract packages, signs generated manifests with the existing development HMAC secret, and provides a `generate-all --check` drift gate for CI.

**Tech Stack:** PowerShell 5+/pwsh, JSON Schema files, existing M03 JSON contracts, existing Maven backend tests, existing CI PowerShell checks.

---

### Task 1: Add Tool-Level Failing Tests

**Files:**
- Create: `tools/skills/test-skill-package-tool.ps1`
- Create: `tools/skills/fixtures/valid-node-health/SKILL.md`
- Create: `tools/skills/fixtures/valid-node-health/skill.package.yaml`
- Create: `tools/skills/fixtures/valid-node-health/schemas/input.schema.json`
- Create: `tools/skills/fixtures/valid-node-health/schemas/output.schema.json`
- Create: `tools/skills/fixtures/valid-node-health/examples/happy-path.json`
- Create: `tools/skills/fixtures/valid-node-health/examples/invalid-parameters.json`
- Create: `tools/skills/fixtures/valid-node-health/examples/policy-denied.json`
- Create: `tools/skills/fixtures/invalid-platform-frontmatter/SKILL.md`
- Create: `tools/skills/fixtures/invalid-platform-frontmatter/skill.package.yaml`

- [ ] **Step 1: Write the failing test script**

Create `tools/skills/test-skill-package-tool.ps1` with tests that call the tool and assert behavior:

```powershell
$ErrorActionPreference = "Stop"

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$tool = Join-Path $PSScriptRoot "skill-package-tool.ps1"
$fixtureRoot = Join-Path $PSScriptRoot "fixtures"
$validPackage = Join-Path $fixtureRoot "valid-node-health"
$invalidFrontmatterPackage = Join-Path $fixtureRoot "invalid-platform-frontmatter"
$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("ops-agent-skill-tool-test-" + [Guid]::NewGuid())

function Assert-True {
    param([bool] $Condition, [string] $Message)
    if (-not $Condition) { throw $Message }
}

function Assert-FileExists {
    param([string] $Path)
    Assert-True (Test-Path -LiteralPath $Path) "Expected file to exist: $Path"
}

try {
    New-Item -ItemType Directory -Force -Path $tempRoot | Out-Null
    $generatedRoot = Join-Path $tempRoot "contracts"

    & $tool validate $validPackage
    if ($LASTEXITCODE -ne 0) {
        throw "validate should pass for valid fixture"
    }

    & $tool generate $validPackage --output-root $generatedRoot
    if ($LASTEXITCODE -ne 0) {
        throw "generate should pass for valid fixture"
    }

    $packageRoot = Join-Path $generatedRoot "valid-node-health"
    Assert-FileExists (Join-Path $packageRoot "manifest.json")
    Assert-FileExists (Join-Path $packageRoot "manifest.signature.json")
    Assert-FileExists (Join-Path $packageRoot "input.schema.json")
    Assert-FileExists (Join-Path $packageRoot "output.schema.json")
    Assert-FileExists (Join-Path $packageRoot "tests/happy-path.json")

    $manifest = Get-Content -Raw -Encoding UTF8 (Join-Path $packageRoot "manifest.json") | ConvertFrom-Json
    Assert-True ($manifest.skillId -eq "valid-node-health-read") "Generated manifest has wrong skillId"
    Assert-True ($manifest.readOnly -eq $true) "Generated manifest must be readOnly"
    Assert-True ($manifest.riskLevel -eq "READ_ONLY") "Generated manifest must be READ_ONLY"

    $signature = Get-Content -Raw -Encoding UTF8 (Join-Path $packageRoot "manifest.signature.json") | ConvertFrom-Json
    Assert-True ($signature.checksumSha256 -match "^[A-Fa-f0-9]{64}$") "Signature checksum must be SHA-256 hex"
    Assert-True ($signature.signature -match "^[A-Fa-f0-9]{64}$") "Signature must be HMAC hex"

    $invalidOutput = & $tool validate $invalidFrontmatterPackage 2>&1
    if ($LASTEXITCODE -eq 0) {
        throw "validate should reject Ops Agent platform fields in SKILL.md frontmatter"
    }
    Assert-True (($invalidOutput | Out-String) -match "platform_tool") "Expected rejection to mention platform_tool"

    Write-Host "Skill package tool tests passed."
} finally {
    if (Test-Path -LiteralPath $tempRoot) {
        Remove-Item -Recurse -Force -LiteralPath $tempRoot
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/skills/test-skill-package-tool.ps1
```

Expected: FAIL because `tools/skills/skill-package-tool.ps1` does not exist.

### Task 2: Implement the Skill Package Tool

**Files:**
- Create: `tools/skills/skill-package-tool.ps1`
- Create: `tools/skills/skill-package.schema.json`
- Create: `tools/skills/README.md`

- [ ] **Step 1: Implement `skill-package-tool.ps1`**

Implement these commands:

```powershell
tools/skills/skill-package-tool.ps1 validate <packagePath>
tools/skills/skill-package-tool.ps1 generate <packagePath> [--output-root <path>]
tools/skills/skill-package-tool.ps1 validate-all
tools/skills/skill-package-tool.ps1 generate-all --check
```

Required behaviors:

- Parse `SKILL.md` frontmatter and require `name` and `description`.
- Reject forbidden frontmatter keys: `platform_tool`, `platform_contract`, `executor`, `requiredRoles`, `credential`, `endpoint`, `allowlist`.
- Parse the constrained `skill.package.yaml` structure used by P1 source packages.
- Require `skillId`, `version`, `displayName`, `description`, `category`, `riskLevel`, `readOnly`, `executor`, `outputType`, `owner`, `requiredRoles`, `timeoutSeconds`, `interceptors`, `tags`, and `parameters`.
- Require `SKILL.md` frontmatter `name` to equal `skill.package.yaml.skillId`.
- Require `readOnly=true` and `riskLevel=READ_ONLY`.
- Reject `scripts/` in the package directory.
- Reject banned package fields such as `endpointUrl`, `apiKey`, `credentialRef`, `allowlist`, `script`, `command`, and `shell`.
- Generate `manifest.json` from `skill.package.yaml`.
- Copy `schemas/input.schema.json` and `schemas/output.schema.json` when present; otherwise generate minimal schemas.
- Copy `examples/*.json` to `tests/*.json` when present; otherwise generate minimal test skeletons.
- 通过安全注入的 `OPS_AGENT_SKILL_REGISTRY_SIGNING_SECRET` 对 manifest checksum 生成 HMAC-SHA256 的 `manifest.signature.json`；该变量没有默认值。
- Implement `generate-all --check` by generating to a temp directory and comparing with `backend/contracts/skills/packages`.

- [ ] **Step 2: Add `skill-package.schema.json`**

Create a JSON Schema that documents the P1 source package metadata fields. It should use `additionalProperties=false`, require P1 read-only metadata, and define `parameters` with `name`, `displayName`, `description`, `type`, `required`, `allowedValues`, and `defaultValue`.

- [ ] **Step 3: Add `tools/skills/README.md`**

Document developer usage:

```powershell
tools/skills/skill-package-tool.ps1 validate backend/skills/weather-current
tools/skills/skill-package-tool.ps1 generate backend/skills/weather-current
tools/skills/skill-package-tool.ps1 validate-all
tools/skills/skill-package-tool.ps1 generate-all --check
```

State that P1 source packages must not include endpoints, credentials, allowlists, scripts, commands, production data, or write operations.

- [ ] **Step 4: Run the tool tests again**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/skills/test-skill-package-tool.ps1
```

Expected: PASS with `Skill package tool tests passed.`

### Task 3: Convert Existing P1 Skills to Source Packages

**Files:**
- Modify: `backend/skills/*/SKILL.md`
- Create: `backend/skills/*/skill.package.yaml`
- Create: `backend/skills/*/schemas/input.schema.json`
- Create: `backend/skills/*/schemas/output.schema.json`
- Create: `backend/skills/*/examples/happy-path.json`
- Create: `backend/skills/*/examples/invalid-parameters.json`
- Create: `backend/skills/*/examples/policy-denied.json`

- [ ] **Step 1: Clean SKILL.md frontmatter**

For each existing Skill, keep only AgentScope-portable fields:

```yaml
---
name: <skill-id>
description: <portable description>
---
```

Remove `version`, `risk`, `category`, `platform_tool`, and `platform_contract` from `SKILL.md`.

- [ ] **Step 2: Add `skill.package.yaml` for each existing Skill**

Use the current `backend/contracts/skills/packages/<skill>/manifest.json` content as the source of truth for each YAML file. Preserve `skillId`, `version`, `displayName`, `description`, `category`, `riskLevel`, `readOnly`, `executor`, `outputType`, `owner`, `requiredRoles`, `timeoutSeconds`, `interceptors`, `tags`, and `parameters`.

- [ ] **Step 3: Move current generated schemas into source package schema files**

Copy each current `input.schema.json` and `output.schema.json` into the matching source package:

```text
backend/skills/<skill>/schemas/input.schema.json
backend/skills/<skill>/schemas/output.schema.json
```

- [ ] **Step 4: Move current generated test examples into source package examples**

Copy each current generated test fixture into the matching source package:

```text
backend/skills/<skill>/examples/happy-path.json
backend/skills/<skill>/examples/invalid-parameters.json
backend/skills/<skill>/examples/policy-denied.json
```

- [ ] **Step 5: Validate all source packages**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/skills/skill-package-tool.ps1 validate-all
```

Expected: PASS.

### Task 4: Regenerate Contracts and Add Drift Gate

**Files:**
- Modify: `backend/contracts/skills/packages/*`
- Modify: `tools/ci/check-contracts.ps1`
- Modify: `docs/superpowers/specs/2026-06-27-m03-skill-source-package-registration-design.md`

- [ ] **Step 1: Regenerate all contract packages**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/skills/skill-package-tool.ps1 generate-all
```

Expected: Generated `manifest.json`, `manifest.signature.json`, schemas, and tests under `backend/contracts/skills/packages/*`.

- [ ] **Step 2: Verify drift check passes**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/skills/skill-package-tool.ps1 generate-all --check
```

Expected: PASS with no drift.

- [ ] **Step 3: Add CI gate**

Update `tools/ci/check-contracts.ps1` to run:

```powershell
& "$repositoryRoot/tools/skills/skill-package-tool.ps1" validate-all
& "$repositoryRoot/tools/skills/skill-package-tool.ps1" generate-all --check
```

This keeps the existing repository-check CI job as the drift gate.

- [ ] **Step 4: Update the design doc for optional source schema files**

Update the spec to mention optional `schemas/input.schema.json` and `schemas/output.schema.json` under the source package. State that they are source-package schema declarations, not generated M03 artifacts, and the tool copies them into the generated contract package.

### Task 5: Verification

**Files:**
- No new files expected unless verification finds a focused defect.

- [ ] **Step 1: Run skill tool tests**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/skills/test-skill-package-tool.ps1
```

Expected: PASS.

- [ ] **Step 2: Run contract checks**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/ci/check-contracts.ps1
```

Expected: `Contract baseline check passed.`

- [ ] **Step 3: Run targeted backend tests**

Run:

```powershell
backend\mvnw.cmd -f backend\pom.xml -pl contracts,control-plane/modules/skillregistry,control-plane/bootstrap -am "-Dtest=ContractsTest,FileSystemSkillManifestLoaderTest,InMemorySkillRegistryServiceTest,SkillRegistryModuleTest,ControlPlaneApplicationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Expected: PASS.

- [ ] **Step 4: Check worktree scope**

Run:

```powershell
git status --short
```

Expected: changes only in `tools/skills`, `tools/ci/check-contracts.ps1`, `backend/skills`, `backend/contracts/skills`, and the M03 design/plan docs, plus pre-existing unrelated frontend changes left unstaged.
