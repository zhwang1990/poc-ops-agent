$ErrorActionPreference = "Stop"

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
Set-Location $repositoryRoot

$requiredPaths = @(
    "AGENTS.md",
    "README.md",
    ".editorconfig",
    ".gitattributes",
    ".gitignore",
    "backend/pom.xml",
    "backend/contracts/pom.xml",
    "backend/mvnw",
    "backend/mvnw.cmd",
    "backend/.mvn/wrapper/maven-wrapper.properties",
    "backend/control-plane/pom.xml",
    "backend/control-plane/bootstrap/pom.xml",
    "backend/control-plane/modules/identity/pom.xml",
    "backend/control-plane/modules/policy/pom.xml",
    "backend/control-plane/modules/audit/pom.xml",
    "backend/control-plane/modules/skillregistry/pom.xml",
    "backend/control-plane/modules/agentrouting/pom.xml",
    "backend/control-plane/modules/agentruntime/pom.xml",
    "backend/control-plane/modules/workflow/pom.xml",
    "backend/control-plane/modules/orchestration/pom.xml",
    "backend/control-plane/modules/events/pom.xml",
    "backend/control-plane/modules/sqlworkbench/pom.xml",
    "backend/execution-worker/pom.xml",
    "frontend/operator-console/package.json",
    "frontend/operator-console/package-lock.json",
    "tools/ci/check-contracts.ps1",
    "tools/release/package-release.mjs",
    "tools/release/release-packaging.mjs",
    "tools/release/test-release-packaging.mjs",
    ".github/workflows/ci.yml",
    ".github/pull_request_template.md",
    "docs/architecture/module-map.md",
    "docs/planning/design-traceability.md",
    "docs/standards/development.md",
    "docs/standards/git-workflow.md",
    "docs/runbooks/repository-bootstrap.md"
)

$missingPaths = $requiredPaths | Where-Object { -not (Test-Path -LiteralPath $_) }
if ($missingPaths.Count -gt 0) {
    throw "Missing required repository files: $($missingPaths -join ', ')"
}

if ($env:GITHUB_HEAD_REF -and $env:GITHUB_HEAD_REF -notmatch "^(codex|feature|fix|docs|chore)/[a-z0-9][a-z0-9._-]*$") {
    throw "Invalid branch name: $($env:GITHUB_HEAD_REF)"
}

$forbiddenPatterns = @("*.pem", "*.key", "*.p12", "*.pfx", ".env", ".env.*")
$forbiddenFiles = foreach ($pattern in $forbiddenPatterns) {
    Get-ChildItem -Path . -Recurse -Force -File -Filter $pattern |
        Where-Object {
            $_.FullName -notmatch "[\\/]\.git[\\/]" -and
            $_.Name -ne ".env.example"
        }
}

if ($forbiddenFiles.Count -gt 0) {
    $relativePaths = $forbiddenFiles | ForEach-Object {
        $_.FullName.Substring($repositoryRoot.Length).TrimStart("\", "/")
    }
    throw "Forbidden sensitive files found: $($relativePaths -join ', ')"
}

Write-Host "Repository baseline check passed."
