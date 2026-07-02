$ErrorActionPreference = "Stop"

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$tool = Join-Path $PSScriptRoot "skill-package-tool.ps1"
$fixtureRoot = Join-Path $PSScriptRoot "fixtures"
$validPackage = Join-Path $fixtureRoot "valid-node-health"
$invalidFrontmatterPackage = Join-Path $fixtureRoot "invalid-platform-frontmatter"
$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("ops-agent-skill-tool-test-" + [Guid]::NewGuid())

function Assert-True {
    param([bool] $Condition, [string] $Message)
    if (-not $Condition) {
        throw $Message
    }
}

function Assert-FileExists {
    param([string] $Path)
    Assert-True (Test-Path -LiteralPath $Path) "Expected file to exist: $Path"
}

function Get-Sha256Hex {
    param([string] $Path)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.IO.File]::ReadAllBytes($Path)
        return ([System.BitConverter]::ToString($sha.ComputeHash($bytes))).Replace("-", "").ToLowerInvariant()
    } finally {
        $sha.Dispose()
    }
}

try {
    New-Item -ItemType Directory -Force -Path $tempRoot | Out-Null
    $generatedRoot = Join-Path $tempRoot "contracts"

    & $tool validate $validPackage

    & $tool generate $validPackage --output-root $generatedRoot

    $packageRoot = Join-Path $generatedRoot "valid-node-health"
    Assert-FileExists (Join-Path $packageRoot "manifest.json")
    Assert-FileExists (Join-Path $packageRoot "manifest.signature.json")
    Assert-FileExists (Join-Path $packageRoot "input.schema.json")
    Assert-FileExists (Join-Path $packageRoot "output.schema.json")
    Assert-FileExists (Join-Path $packageRoot "tests/happy-path.json")

    $manifestPath = Join-Path $packageRoot "manifest.json"
    $manifestBytes = [System.IO.File]::ReadAllBytes($manifestPath)
    $manifestText = [System.Text.Encoding]::UTF8.GetString($manifestBytes)
    Assert-True (-not $manifestText.Contains("`r`n")) "Generated manifest must use LF line endings for stable checksums"

    $manifest = Get-Content -Raw -Encoding UTF8 $manifestPath | ConvertFrom-Json
    Assert-True ($manifest.skillId -eq "valid-node-health-read") "Generated manifest has wrong skillId"
    Assert-True ($manifest.readOnly -eq $true) "Generated manifest must be readOnly"
    Assert-True ($manifest.riskLevel -eq "READ_ONLY") "Generated manifest must be READ_ONLY"

    $signature = Get-Content -Raw -Encoding UTF8 (Join-Path $packageRoot "manifest.signature.json") | ConvertFrom-Json
    Assert-True ($signature.checksumSha256 -match "^[A-Fa-f0-9]{64}$") "Signature checksum must be SHA-256 hex"
    Assert-True ($signature.checksumSha256 -eq (Get-Sha256Hex $manifestPath)) "Signature checksum must match generated manifest bytes"
    Assert-True ($signature.signature -match "^[A-Fa-f0-9]{64}$") "Signature must be HMAC hex"

    $invalidOutput = ""
    try {
        & $tool validate $invalidFrontmatterPackage 2>&1
        throw "validate should reject Ops Agent platform fields in SKILL.md frontmatter"
    } catch {
        $invalidOutput = $_ | Out-String
    }
    Assert-True (($invalidOutput | Out-String) -match "platform_tool") "Expected rejection to mention platform_tool"

    Write-Host "Skill package tool tests passed."
} finally {
    if (Test-Path -LiteralPath $tempRoot) {
        Remove-Item -Recurse -Force -LiteralPath $tempRoot
    }
}
