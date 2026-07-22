$ErrorActionPreference = "Stop"

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
Set-Location $repositoryRoot

$schemaFiles = Get-ChildItem -Path "backend/contracts" -Recurse -File -Filter "*.schema.json"
if ($schemaFiles.Count -lt 6) {
    throw "Expected at least six versioned JSON schemas, found $($schemaFiles.Count)."
}

foreach ($schemaFile in $schemaFiles) {
    $schema = Get-Content -Raw -Encoding UTF8 $schemaFile.FullName | ConvertFrom-Json
    if (-not $schema.'$schema' -or -not $schema.'$id' -or -not $schema.title) {
        throw "Schema metadata is incomplete: $($schemaFile.FullName)"
    }
}

$commandSchema = Get-Content -Raw -Encoding UTF8 "backend/contracts/workflow/read-only-command-v1.schema.json" | ConvertFrom-Json
$commandRequired = @($commandSchema.required)
foreach ($requiredField in @("workflowId", "idempotencyKey", "operationClass", "operator", "policyDecision", "trace")) {
    if ($requiredField -notin $commandRequired) {
        throw "Read-only command schema does not require $requiredField."
    }
}

if ($commandSchema.properties.operationClass.const -ne "READ_ONLY") {
    throw "P1 command contract must only allow READ_ONLY operations."
}

$eventSchema = Get-Content -Raw -Encoding UTF8 "backend/contracts/events/semantic-event-v1.schema.json" | ConvertFrom-Json
$eventRequired = @($eventSchema.required)
foreach ($requiredField in @("eventId", "workflowId", "sequence", "timestamp", "type", "payload")) {
    if ($requiredField -notin $eventRequired) {
        throw "Semantic event schema does not require $requiredField."
    }
}

Get-Content -Raw -Encoding UTF8 "backend/contracts/workflow/examples/read-only-node-health-command.json" | ConvertFrom-Json | Out-Null
Get-Content -Raw -Encoding UTF8 "backend/contracts/events/examples/workflow-completed-event.json" | ConvertFrom-Json | Out-Null

$expectedAgentTaskStatuses = @(
    "SUCCEEDED",
    "FAILED_TERMINAL",
    "REJECTED",
    "AGENT_RUNTIME_DISABLED",
    "AGENT_RUNTIME_NOT_CONFIGURED",
    "AGENT_RUNTIME_FAILED"
)

$expectedSemanticEventTypes = @(
    "WORKFLOW_STARTED",
    "SKILL_ROUTED",
    "WORKER_ACCEPTED",
    "AGENT_TOOL_CALL_REQUESTED",
    "AGENT_TOOL_CALL_COMPLETED",
    "AGENT_TOOL_CALL_REJECTED",
    "AGENT_RUNTIME_PROGRESS",
    "WORKFLOW_COMPLETED",
    "WORKFLOW_FAILED"
)

function Assert-SameStringSet {
    param(
        [string] $Name,
        [string[]] $Actual,
        [string[]] $Expected
    )

    $missing = $Expected | Where-Object { $_ -notin $Actual }
    $unexpected = $Actual | Where-Object { $_ -notin $Expected }
    if ($missing.Count -gt 0 -or $unexpected.Count -gt 0) {
        throw "$Name mismatch. Missing: $($missing -join ', '); unexpected: $($unexpected -join ', ')."
    }
}

function Get-QuotedStrings {
    param([string] $Text)

    return [regex]::Matches($Text, '"([^"]+)"') | ForEach-Object { $_.Groups[1].Value }
}

Assert-SameStringSet `
    -Name "Semantic event schema types" `
    -Actual @($eventSchema.properties.type.enum) `
    -Expected $expectedSemanticEventTypes

$semanticEventTypeSource = Get-Content -Raw -Encoding UTF8 "backend/contracts/src/main/java/com/company/opsagent/contracts/events/SemanticEventType.java"
$semanticEventTypeBlockMatch = [regex]::Match(
    $semanticEventTypeSource,
    'enum\s+SemanticEventType\s*\{(?<types>.*?)\}',
    [System.Text.RegularExpressions.RegexOptions]::Singleline)
if (-not $semanticEventTypeBlockMatch.Success) {
    throw "SemanticEventType.java does not expose a parseable enum block."
}
$javaSemanticEventTypes = [regex]::Matches(
    $semanticEventTypeBlockMatch.Groups["types"].Value,
    '\b[A-Z][A-Z0-9_]+\b') | ForEach-Object { $_.Value }
Assert-SameStringSet `
    -Name "SemanticEventType Java values" `
    -Actual @($javaSemanticEventTypes) `
    -Expected $expectedSemanticEventTypes

$agentTaskResultSchema = Get-Content -Raw -Encoding UTF8 "backend/contracts/agent/agent-task-result-v1.schema.json" | ConvertFrom-Json
Assert-SameStringSet `
    -Name "Agent task result schema statuses" `
    -Actual @($agentTaskResultSchema.properties.status.enum) `
    -Expected $expectedAgentTaskStatuses

$agentTaskResultSource = Get-Content -Raw -Encoding UTF8 "backend/contracts/src/main/java/com/company/opsagent/contracts/agent/AgentTaskResult.java"
$javaStatusBlockMatch = [regex]::Match(
    $agentTaskResultSource,
    'ALLOWED_STATUSES\s*=\s*Set\.of\((?<statuses>.*?)\);',
    [System.Text.RegularExpressions.RegexOptions]::Singleline)
if (-not $javaStatusBlockMatch.Success) {
    throw "AgentTaskResult.java does not expose an ALLOWED_STATUSES Set.of block."
}
Assert-SameStringSet `
    -Name "AgentTaskResult Java statuses" `
    -Actual @(Get-QuotedStrings $javaStatusBlockMatch.Groups["statuses"].Value) `
    -Expected $expectedAgentTaskStatuses

$agentSchemasSource = Get-Content -Raw -Encoding UTF8 "frontend/operator-console/src/schemas/agent-schemas.js"
$frontendStatusBlockMatch = [regex]::Match(
    $agentSchemasSource,
    'agentTaskStatusValues\s*=\s*\[(?<statuses>.*?)\];',
    [System.Text.RegularExpressions.RegexOptions]::Singleline)
if (-not $frontendStatusBlockMatch.Success) {
    throw "agent-schemas.js does not expose agentTaskStatusValues."
}
Assert-SameStringSet `
    -Name "Frontend Agent task result statuses" `
    -Actual @(Get-QuotedStrings $frontendStatusBlockMatch.Groups["statuses"].Value) `
    -Expected $expectedAgentTaskStatuses

& "$repositoryRoot/tools/skills/skill-package-tool.ps1" validate-all
if ([string]::IsNullOrWhiteSpace($env:OPS_AGENT_SKILL_REGISTRY_SIGNING_SECRET)) {
    throw "OPS_AGENT_SKILL_REGISTRY_SIGNING_SECRET must be securely injected before verifying signed Skill contracts."
}
& "$repositoryRoot/tools/skills/skill-package-tool.ps1" generate-all --check

Write-Host "Contract baseline check passed."
