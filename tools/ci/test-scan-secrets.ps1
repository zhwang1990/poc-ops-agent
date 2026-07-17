$ErrorActionPreference = "Stop"

$scanner = Join-Path $PSScriptRoot "scan-secrets.ps1"
$fixtureRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("ops-agent-secret-scan-" + [Guid]::NewGuid())

function New-RuntimeLiteral {
    $bytes = New-Object byte[] 24
    $random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $random.GetBytes($bytes)
    } finally {
        $random.Dispose()
    }
    return [Convert]::ToBase64String($bytes)
}

function Write-Fixture([string] $relativePath, [string] $content) {
    $path = Join-Path $fixtureRoot $relativePath
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $path) | Out-Null
    Set-Content -LiteralPath $path -Value $content -NoNewline
}

function Assert-ScannerFails([string] $relativePath, [string] $content) {
    Get-ChildItem -LiteralPath $fixtureRoot -Force | Remove-Item -Force -Recurse
    Write-Fixture $relativePath $content
    $rejected = $false
    try {
        & $scanner -RepositoryRoot $fixtureRoot *> $null
    } catch {
        $rejected = $true
    }
    if (-not $rejected) {
        throw "Expected secret scanner to reject $relativePath."
    }
}

try {
    $runtimeLiteral = New-RuntimeLiteral

    Write-Fixture "application.yaml" "client-secret: `${OPS_AGENT_LOCAL_OIDC_CLIENT_SECRET}"
    & $scanner -RepositoryRoot $fixtureRoot
    if (-not $?) {
        throw "Expected environment placeholder fixture to pass secret scanner."
    }

    Assert-ScannerFails "application.yaml" ("client-secret: " + $runtimeLiteral)
    Assert-ScannerFails "src/test/java/ExampleTest.java" (
        "char[] storePassword = `"" + $runtimeLiteral + "`".toCharArray();")
    Assert-ScannerFails "tools/example.mjs" (
        "const env = { OPS_AGENT_SQL_KEYSTORE_PASSWORD: `"" + $runtimeLiteral + "`" }; ")
    Assert-ScannerFails "scripts/start.cmd" (
        "set `"OPS_AGENT_LOCAL_OIDC_CLIENT_SECRET=" + $runtimeLiteral + "`"")
    Assert-ScannerFails "docs/runbook.md" ("password: " + $runtimeLiteral)

    Write-Host "Secret scanner targeted tests passed."
} finally {
    if (Test-Path -LiteralPath $fixtureRoot) {
        Remove-Item -LiteralPath $fixtureRoot -Force -Recurse
    }
}
