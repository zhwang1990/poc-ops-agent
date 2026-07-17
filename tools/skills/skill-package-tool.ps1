$ErrorActionPreference = "Stop"

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$defaultSkillRoot = Join-Path $repositoryRoot "backend/skills"
$defaultContractRoot = Join-Path $repositoryRoot "backend/contracts/skills/packages"

$allowedCategories = @(
    "INFRASTRUCTURE_DIAGNOSTICS",
    "APPLICATION_DIAGNOSTICS",
    "PLATFORM_OBSERVABILITY"
)
$allowedRiskLevels = @("READ_ONLY", "LOW", "MEDIUM", "HIGH")
$allowedExecutors = @("SHELL", "HTTP", "WORKFLOW")
$allowedOutputTypes = @("JSON", "TEXT", "TABLE", "MARKDOWN")
$allowedParameterTypes = @("STRING", "INTEGER", "BOOLEAN", "ENUM")
$allowedInterceptors = @("AUTHORIZATION", "AUDIT", "SENSITIVE_DATA_MASKING", "RATE_LIMIT")
$forbiddenSkillFrontmatterKeys = @(
    "platform_tool",
    "platform_contract",
    "executor",
    "requiredRoles",
    "credential",
    "endpoint",
    "allowlist"
)
$forbiddenPackageFields = @(
    "endpointUrl",
    "apiKey",
    "credentialRef",
    "allowlist",
    "script",
    "command",
    "shell"
)

function Fail {
    param([string] $Message)
    throw $Message
}

function Get-RequiredSigningSecret {
    $secret = $env:OPS_AGENT_SKILL_REGISTRY_SIGNING_SECRET
    if ([string]::IsNullOrWhiteSpace($secret)) {
        Fail "OPS_AGENT_SKILL_REGISTRY_SIGNING_SECRET must be injected for Skill contract signing."
    }
    return $secret
}

function Write-Utf8NoBom {
    param(
        [string] $Path,
        [string] $Content
    )
    $parent = Split-Path -Parent $Path
    if ($parent -and -not (Test-Path -LiteralPath $parent)) {
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
    }
    $encoding = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, $Content, $encoding)
}

function Copy-JsonFile {
    param(
        [string] $Source,
        [string] $Destination
    )
    Get-Content -Raw -Encoding UTF8 -LiteralPath $Source | ConvertFrom-Json | Out-Null
    $parent = Split-Path -Parent $Destination
    if ($parent -and -not (Test-Path -LiteralPath $parent)) {
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
    }
    Copy-Item -Force -LiteralPath $Source -Destination $Destination
}

function Convert-JsonString {
    param([string] $Value)
    return ($Value | ConvertTo-Json -Compress)
}

function ConvertTo-CanonicalJson {
    param(
        [object] $Value,
        [int] $Indent = 0
    )
    $padding = " " * $Indent
    $childPadding = " " * ($Indent + 2)

    if ($null -eq $Value) {
        return "null"
    }
    if ($Value -is [bool]) {
        if ($Value) { return "true" }
        return "false"
    }
    if ($Value -is [int] -or $Value -is [long] -or $Value -is [double] -or $Value -is [decimal]) {
        return [string] $Value
    }
    if ($Value -is [string]) {
        return Convert-JsonString $Value
    }
    if ($Value -is [System.Collections.IDictionary]) {
        $keys = @($Value.Keys)
        if ($keys.Count -eq 0) {
            return "{}"
        }
        $lines = New-Object System.Collections.Generic.List[string]
        $lines.Add("{")
        for ($i = 0; $i -lt $keys.Count; $i += 1) {
            $key = [string] $keys[$i]
            $entry = $childPadding + (Convert-JsonString $key) + ": " + (ConvertTo-CanonicalJson -Value $Value[$key] -Indent ($Indent + 2))
            if ($i -lt ($keys.Count - 1)) {
                $entry += ","
            }
            $lines.Add($entry)
        }
        $lines.Add($padding + "}")
        return ($lines -join "`n")
    }
    if ($Value -is [System.Collections.IEnumerable]) {
        $items = @($Value)
        if ($items.Count -eq 0) {
            return "[]"
        }
        $lines = New-Object System.Collections.Generic.List[string]
        $lines.Add("[")
        for ($i = 0; $i -lt $items.Count; $i += 1) {
            $entry = $childPadding + (ConvertTo-CanonicalJson -Value $items[$i] -Indent ($Indent + 2))
            if ($i -lt ($items.Count - 1)) {
                $entry += ","
            }
            $lines.Add($entry)
        }
        $lines.Add($padding + "]")
        return ($lines -join "`n")
    }
    return Convert-JsonString ([string] $Value)
}

function Convert-ToJsonFile {
    param(
        [string] $Path,
        [object] $Value
    )
    $json = ConvertTo-CanonicalJson -Value $Value
    Write-Utf8NoBom -Path $Path -Content ($json + "`n")
}

function Parse-Scalar {
    param([string] $Raw)
    $value = $Raw.Trim()
    if ($value -eq "true") { return $true }
    if ($value -eq "false") { return $false }
    if ($value -eq "null") { return $null }
    if ($value -eq "[]") { return @() }
    if ($value -match '^\[(.*)\]$') {
        $inner = $Matches[1].Trim()
        if ($inner.Length -eq 0) { return @() }
        return @($inner -split "," | ForEach-Object { $_.Trim().Trim([char]34).Trim([char]39) })
    }
    if ($value -match '^\d+$') { return [int] $value }
    return $value.Trim([char]34).Trim([char]39)
}

function Read-ConstrainedYaml {
    param([string] $Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        Fail "Missing skill.package.yaml: $Path"
    }
    $lines = Get-Content -Encoding UTF8 -LiteralPath $Path
    $data = [ordered]@{}
    $index = 0
    while ($index -lt $lines.Count) {
        $line = $lines[$index]
        $index += 1
        if ($line.Trim().Length -eq 0 -or $line.TrimStart().StartsWith("#")) {
            continue
        }
        if ($line -notmatch '^([A-Za-z][A-Za-z0-9]*)\s*:\s*(.*)$') {
            Fail "Unsupported YAML line in ${Path}: $line"
        }

        $key = $Matches[1]
        $rawValue = $Matches[2]
        if ($rawValue.Trim().Length -gt 0) {
            $data[$key] = Parse-Scalar $rawValue
            continue
        }

        if ($key -eq "parameters") {
            $parameters = @()
            while ($index -lt $lines.Count -and $lines[$index] -match '^\s+') {
                $itemLine = $lines[$index]
                if ($itemLine -match '^\s{2}-\s+name\s*:\s*(.*)$') {
                    $parameter = [ordered]@{
                        name = Parse-Scalar $Matches[1]
                    }
                    $index += 1
                    while ($index -lt $lines.Count -and $lines[$index] -match '^\s{4}([A-Za-z][A-Za-z0-9]*)\s*:\s*(.*)$') {
                        $parameter[$Matches[1]] = Parse-Scalar $Matches[2]
                        $index += 1
                    }
                    $parameters += ,$parameter
                    continue
                }
                Fail "Unsupported parameters YAML line in ${Path}: $itemLine"
            }
            $data[$key] = $parameters
            continue
        }

        $items = @()
        while ($index -lt $lines.Count -and $lines[$index] -match '^\s{2}-\s+(.*)$') {
            $items += ,(Parse-Scalar $Matches[1])
            $index += 1
        }
        $data[$key] = $items
    }
    return $data
}

function Read-SkillFrontmatter {
    param([string] $SkillMarkdownPath)
    if (-not (Test-Path -LiteralPath $SkillMarkdownPath)) {
        Fail "Missing SKILL.md: $SkillMarkdownPath"
    }
    $text = Get-Content -Raw -Encoding UTF8 -LiteralPath $SkillMarkdownPath
    $match = [regex]::Match($text, '^\s*---\r?\n(?<frontmatter>.*?)\r?\n---', [System.Text.RegularExpressions.RegexOptions]::Singleline)
    if (-not $match.Success) {
        Fail "SKILL.md must start with YAML frontmatter: $SkillMarkdownPath"
    }

    $frontmatter = [ordered]@{}
    foreach ($line in ($match.Groups["frontmatter"].Value -split "\r?\n")) {
        if ($line.Trim().Length -eq 0 -or $line.TrimStart().StartsWith("#")) {
            continue
        }
        if ($line -notmatch '^([A-Za-z_][A-Za-z0-9_-]*)\s*:\s*(.*)$') {
            Fail "Unsupported SKILL.md frontmatter line: $line"
        }
        $frontmatter[$Matches[1]] = Parse-Scalar $Matches[2]
    }
    return $frontmatter
}

function Assert-OneOf {
    param(
        [string] $Name,
        [object] $Value,
        [string[]] $Allowed
    )
    if ($Value -notin $Allowed) {
        Fail "$Name has unsupported value '$Value'. Allowed values: $($Allowed -join ', ')"
    }
}

function Validate-Package {
    param([string] $PackagePath)
    $resolvedPackage = (Resolve-Path -LiteralPath $PackagePath).Path
    $skillMarkdown = Join-Path $resolvedPackage "SKILL.md"
    $packageYaml = Join-Path $resolvedPackage "skill.package.yaml"
    $frontmatter = Read-SkillFrontmatter $skillMarkdown
    $package = Read-ConstrainedYaml $packageYaml

    foreach ($key in @("name", "description")) {
        if (-not $frontmatter.Contains($key) -or [string]::IsNullOrWhiteSpace([string]$frontmatter[$key])) {
            Fail "SKILL.md frontmatter must include $key"
        }
    }
    foreach ($forbiddenKey in $forbiddenSkillFrontmatterKeys) {
        if ($frontmatter.Contains($forbiddenKey)) {
            Fail "SKILL.md frontmatter must not include platform-specific key: $forbiddenKey"
        }
    }

    $rawPackageText = Get-Content -Raw -Encoding UTF8 -LiteralPath $packageYaml
    foreach ($forbiddenField in $forbiddenPackageFields) {
        if ($rawPackageText -match "(?m)^\s*$([regex]::Escape($forbiddenField))\s*:") {
            Fail "skill.package.yaml must not include forbidden field: $forbiddenField"
        }
    }

    $requiredFields = @(
        "schemaVersion",
        "skillId",
        "version",
        "displayName",
        "description",
        "category",
        "riskLevel",
        "readOnly",
        "executor",
        "outputType",
        "owner",
        "timeoutSeconds",
        "requiredRoles",
        "interceptors",
        "tags",
        "parameters"
    )
    $allowedPackageFields = @($requiredFields + "publishedAt")
    foreach ($field in $package.Keys) {
        if ($field -notin $allowedPackageFields) {
            Fail "skill.package.yaml contains unsupported field: $field"
        }
    }
    foreach ($field in $requiredFields) {
        if (-not $package.Contains($field)) {
            Fail "skill.package.yaml missing required field: $field"
        }
    }
    if ($package.schemaVersion -ne "ops-agent.skill/v1") {
        Fail "schemaVersion must be ops-agent.skill/v1"
    }
    if ($frontmatter.name -ne $package.skillId) {
        Fail "SKILL.md name '$($frontmatter.name)' must equal skill.package.yaml skillId '$($package.skillId)'"
    }
    if ($package.readOnly -ne $true) {
        Fail "P1 stage only allows readOnly=true"
    }
    if ($package.riskLevel -ne "READ_ONLY") {
        Fail "P1 stage only allows riskLevel=READ_ONLY"
    }
    Assert-OneOf "category" $package.category $allowedCategories
    Assert-OneOf "riskLevel" $package.riskLevel $allowedRiskLevels
    Assert-OneOf "executor" $package.executor $allowedExecutors
    Assert-OneOf "outputType" $package.outputType $allowedOutputTypes

    foreach ($role in @($package.requiredRoles)) {
        if ([string]::IsNullOrWhiteSpace([string]$role)) {
            Fail "requiredRoles must not contain blank values"
        }
    }
    foreach ($interceptor in @($package.interceptors)) {
        Assert-OneOf "interceptor" $interceptor $allowedInterceptors
    }
    foreach ($parameter in @($package.parameters)) {
        $allowedParameterFields = @("name", "displayName", "description", "type", "required", "allowedValues", "defaultValue")
        foreach ($field in $parameter.Keys) {
            if ($field -notin $allowedParameterFields) {
                Fail "parameter '$($parameter.name)' contains unsupported field: $field"
            }
        }
        foreach ($field in @("name", "displayName", "description", "type", "required", "allowedValues", "defaultValue")) {
            if (-not $parameter.Contains($field)) {
                Fail "parameter missing required field '$field'"
            }
        }
        Assert-OneOf "parameter.type" $parameter.type $allowedParameterTypes
        if ($parameter.type -eq "ENUM" -and @($parameter.allowedValues).Count -eq 0) {
            Fail "ENUM parameter '$($parameter.name)' must define allowedValues"
        }
    }

    $scriptsPath = Join-Path $resolvedPackage "scripts"
    if (Test-Path -LiteralPath $scriptsPath) {
        Fail "P1 source packages must not contain scripts/: $scriptsPath"
    }

    foreach ($schemaFile in @("schemas/input.schema.json", "schemas/output.schema.json")) {
        $schemaPath = Join-Path $resolvedPackage $schemaFile
        if (Test-Path -LiteralPath $schemaPath) {
            Get-Content -Raw -Encoding UTF8 -LiteralPath $schemaPath | ConvertFrom-Json | Out-Null
        }
    }
    $examplesPath = Join-Path $resolvedPackage "examples"
    if (Test-Path -LiteralPath $examplesPath) {
        Get-ChildItem -LiteralPath $examplesPath -Filter "*.json" -File | ForEach-Object {
            Get-Content -Raw -Encoding UTF8 -LiteralPath $_.FullName | ConvertFrom-Json | Out-Null
        }
    }

    return [pscustomobject]@{
        PackagePath = $resolvedPackage
        PackageName = Split-Path -Leaf $resolvedPackage
        Frontmatter = $frontmatter
        Package = $package
    }
}

function Build-Manifest {
    param([hashtable] $Package)
    $parameters = @()
    foreach ($parameter in @($Package.parameters)) {
        $parameters += ,[ordered]@{
            name = $parameter.name
            displayName = $parameter.displayName
            description = $parameter.description
            type = $parameter.type
            required = [bool] $parameter.required
            allowedValues = @($parameter.allowedValues)
            defaultValue = $parameter.defaultValue
        }
    }

    return [ordered]@{
        skillId = $Package.skillId
        version = $Package.version
        displayName = $Package.displayName
        description = $Package.description
        category = $Package.category
        riskLevel = $Package.riskLevel
        executor = $Package.executor
        outputType = $Package.outputType
        readOnly = [bool] $Package.readOnly
        timeoutSeconds = [int] $Package.timeoutSeconds
        owner = $Package.owner
        requiredRoles = @($Package.requiredRoles)
        tags = @($Package.tags)
        interceptors = @($Package.interceptors)
        parameters = $parameters
    }
}

function Build-GeneratedInputSchema {
    param([hashtable] $Package)
    $required = @()
    $properties = [ordered]@{}
    foreach ($parameter in @($Package.parameters)) {
        if ($parameter.required -eq $true) {
            $required += ,$parameter.name
        }
        $jsonType = switch ($parameter.type) {
            "INTEGER" { "integer" }
            "BOOLEAN" { "boolean" }
            default { "string" }
        }
        $property = [ordered]@{
            type = $jsonType
            description = $parameter.description
        }
        if ($parameter.type -eq "STRING") {
            $property.minLength = 1
        }
        if ($parameter.type -eq "ENUM") {
            $property.enum = @($parameter.allowedValues)
        }
        $properties[$parameter.name] = $property
    }
    $jsonSchema = [ordered]@{}
    $jsonSchema['$schema'] = "https://json-schema.org/draft/2020-12/schema"
    $jsonSchema['$id'] = ("https://company.example/ops-agent/skills/{0}/{1}/input.schema.json" -f $Package.skillId, $Package.version)
    $jsonSchema['title'] = ("{0} input" -f $Package.skillId)
    $jsonSchema['description'] = ("Input parameters for {0}." -f $Package.skillId)
    $jsonSchema['type'] = "object"
    $jsonSchema['additionalProperties'] = $false
    $jsonSchema['required'] = $required
    $jsonSchema['properties'] = $properties
    return $jsonSchema
}

function Build-GeneratedOutputSchema {
    param([hashtable] $Package)
    $jsonSchema = [ordered]@{}
    $jsonSchema['$schema'] = "https://json-schema.org/draft/2020-12/schema"
    $jsonSchema['$id'] = ("https://company.example/ops-agent/skills/{0}/{1}/output.schema.json" -f $Package.skillId, $Package.version)
    $jsonSchema['title'] = ("{0} output" -f $Package.skillId)
    $jsonSchema['description'] = ("Structured output for {0}." -f $Package.skillId)
    $jsonSchema['type'] = "object"
    $jsonSchema['additionalProperties'] = $true
    $jsonSchema['properties'] = [ordered]@{}
    return $jsonSchema
}

function Get-Sha256Hex {
    param([string] $Path)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.IO.File]::ReadAllBytes($Path)
        return (($sha.ComputeHash($bytes) | ForEach-Object { $_.ToString("x2") }) -join "")
    } finally {
        $sha.Dispose()
    }
}

function Get-HmacSha256Hex {
    param(
        [string] $Secret,
        [string] $Payload
    )
    $hmac = New-Object System.Security.Cryptography.HMACSHA256
    try {
        $hmac.Key = [System.Text.Encoding]::UTF8.GetBytes($Secret)
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($Payload)
        return (($hmac.ComputeHash($bytes) | ForEach-Object { $_.ToString("x2") }) -join "")
    } finally {
        $hmac.Dispose()
    }
}

function Build-MinimalTestCase {
    param(
        [hashtable] $Package,
        [string] $Kind
    )
    $decision = if ($Kind -eq "policy-denied") { "DENY" } elseif ($Kind -eq "invalid-parameters") { "REJECT" } else { "ALLOW" }
    $status = if ($Kind -eq "policy-denied") { "POLICY_DENIED" } elseif ($Kind -eq "invalid-parameters") { "INVALID_PARAMETERS" } else { "SUCCEEDED" }
    return [ordered]@{
        caseId = "$($Package.skillId)-$Kind"
        skillId = $Package.skillId
        version = $Package.version
        description = "$($Package.displayName) $Kind"
        operator = [ordered]@{
            subject = if ($Kind -eq "policy-denied") { "guest-1" } else { "ops-reader-1" }
            roles = if ($Kind -eq "policy-denied") { @("ROLE_guest") } else { @("ROLE_ops-reader") }
        }
        targetEnvironment = "development"
        input = [ordered]@{}
        expected = [ordered]@{
            decision = $decision
            status = $status
        }
    }
}

function Generate-Package {
    param(
        [string] $PackagePath,
        [string] $OutputRoot
    )
    $validated = Validate-Package $PackagePath
    $package = $validated.Package
    $targetRoot = Join-Path $OutputRoot $validated.PackageName
    if (Test-Path -LiteralPath $targetRoot) {
        Remove-Item -Recurse -Force -LiteralPath $targetRoot
    }
    New-Item -ItemType Directory -Force -Path $targetRoot | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $targetRoot "tests") | Out-Null

    $manifestPath = Join-Path $targetRoot "manifest.json"
    Convert-ToJsonFile -Path $manifestPath -Value (Build-Manifest $package)

    $sourceInputSchema = Join-Path $validated.PackagePath "schemas/input.schema.json"
    $sourceOutputSchema = Join-Path $validated.PackagePath "schemas/output.schema.json"
    if (Test-Path -LiteralPath $sourceInputSchema) {
        Copy-JsonFile -Source $sourceInputSchema -Destination (Join-Path $targetRoot "input.schema.json")
    } else {
        Convert-ToJsonFile -Path (Join-Path $targetRoot "input.schema.json") -Value (Build-GeneratedInputSchema $package)
    }
    if (Test-Path -LiteralPath $sourceOutputSchema) {
        Copy-JsonFile -Source $sourceOutputSchema -Destination (Join-Path $targetRoot "output.schema.json")
    } else {
        Convert-ToJsonFile -Path (Join-Path $targetRoot "output.schema.json") -Value (Build-GeneratedOutputSchema $package)
    }

    foreach ($kind in @("happy-path", "invalid-parameters", "policy-denied")) {
        $sourceExample = Join-Path $validated.PackagePath "examples/$kind.json"
        $targetExample = Join-Path $targetRoot "tests/$kind.json"
        if (Test-Path -LiteralPath $sourceExample) {
            Copy-JsonFile -Source $sourceExample -Destination $targetExample
        } else {
            Convert-ToJsonFile -Path $targetExample -Value (Build-MinimalTestCase -Package $package -Kind $kind)
        }
    }

    $checksum = Get-Sha256Hex $manifestPath
    $publication = [ordered]@{
        publishedBy = $package.owner
        publishedAt = if ($package.Contains("publishedAt")) { $package.publishedAt } else { "2026-06-27T00:00:00+08:00" }
        checksumSha256 = $checksum
        signatureAlgorithm = "HmacSHA256"
        signature = Get-HmacSha256Hex -Secret (Get-RequiredSigningSecret) -Payload $checksum
    }
    Convert-ToJsonFile -Path (Join-Path $targetRoot "manifest.signature.json") -Value $publication
}

function Get-SourcePackages {
    if (-not (Test-Path -LiteralPath $defaultSkillRoot)) {
        return @()
    }
    return @(Get-ChildItem -LiteralPath $defaultSkillRoot -Directory | Where-Object {
        Test-Path -LiteralPath (Join-Path $_.FullName "skill.package.yaml")
    } | Sort-Object Name)
}

function Compare-Directories {
    param(
        [string] $ExpectedRoot,
        [string] $ActualRoot
    )
    $expectedFiles = @{}
    if (Test-Path -LiteralPath $ExpectedRoot) {
        Get-ChildItem -Recurse -File -LiteralPath $ExpectedRoot | ForEach-Object {
            $relative = $_.FullName.Substring((Resolve-Path -LiteralPath $ExpectedRoot).Path.Length).TrimStart('\', '/').Replace('\', '/')
            $expectedFiles[$relative] = $_.FullName
        }
    }
    $actualFiles = @{}
    if (Test-Path -LiteralPath $ActualRoot) {
        Get-ChildItem -Recurse -File -LiteralPath $ActualRoot | ForEach-Object {
            $relative = $_.FullName.Substring((Resolve-Path -LiteralPath $ActualRoot).Path.Length).TrimStart('\', '/').Replace('\', '/')
            $actualFiles[$relative] = $_.FullName
        }
    }

    $allKeys = @($expectedFiles.Keys + $actualFiles.Keys | Sort-Object -Unique)
    $drift = @()
    foreach ($key in $allKeys) {
        if (-not $expectedFiles.Contains($key)) {
            $drift += "Unexpected generated file: $key"
            continue
        }
        if (-not $actualFiles.Contains($key)) {
            $drift += "Missing generated file: $key"
            continue
        }
        $expectedBytes = [System.IO.File]::ReadAllBytes($expectedFiles[$key])
        $actualBytes = [System.IO.File]::ReadAllBytes($actualFiles[$key])
        if ($expectedBytes.Length -ne $actualBytes.Length) {
            $drift += "Generated file differs: $key"
            continue
        }
        for ($i = 0; $i -lt $expectedBytes.Length; $i += 1) {
            if ($expectedBytes[$i] -ne $actualBytes[$i]) {
                $drift += "Generated file differs: $key"
                break
            }
        }
    }
    return $drift
}

function Validate-All {
    $packages = Get-SourcePackages
    if ($packages.Count -eq 0) {
        Fail "No Skill source packages found under $defaultSkillRoot"
    }
    foreach ($package in $packages) {
        Validate-Package $package.FullName | Out-Null
    }
}

function Generate-All {
    param(
        [string] $OutputRoot,
        [bool] $Check
    )
    if ($Check) {
        $tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("ops-agent-skill-generate-check-" + [Guid]::NewGuid())
        try {
            New-Item -ItemType Directory -Force -Path $tempRoot | Out-Null
            $packages = Get-SourcePackages
            if ($packages.Count -eq 0) {
                Fail "No Skill source packages found under $defaultSkillRoot"
            }
            foreach ($package in $packages) {
                Generate-Package -PackagePath $package.FullName -OutputRoot $tempRoot
            }
            $drift = Compare-Directories -ExpectedRoot $defaultContractRoot -ActualRoot $tempRoot
            if ($drift.Count -gt 0) {
                Fail ("Skill contract generation drift detected:`n" + ($drift -join "`n"))
            }
        } finally {
            if (Test-Path -LiteralPath $tempRoot) {
                Remove-Item -Recurse -Force -LiteralPath $tempRoot
            }
        }
        return
    }

    $packages = Get-SourcePackages
    if ($packages.Count -eq 0) {
        Fail "No Skill source packages found under $defaultSkillRoot"
    }
    foreach ($package in $packages) {
        Generate-Package -PackagePath $package.FullName -OutputRoot $OutputRoot
    }
}

function Parse-Option {
    param(
        [string[]] $Values,
        [string] $Name
    )
    for ($i = 0; $i -lt $Values.Count; $i += 1) {
        if ($Values[$i] -eq $Name -and ($i + 1) -lt $Values.Count) {
            return $Values[$i + 1]
        }
    }
    return $null
}

if ($args.Count -lt 1) {
    Fail "Usage: skill-package-tool.ps1 <validate|generate|validate-all|generate-all> [packagePath] [--output-root path] [--check]"
}

$command = $args[0]
$remaining = @($args | Select-Object -Skip 1)
switch ($command) {
    "validate" {
        if ($remaining.Count -lt 1) { Fail "validate requires packagePath" }
        Validate-Package $remaining[0] | Out-Null
        Write-Host "Skill source package is valid: $($remaining[0])"
    }
    "generate" {
        if ($remaining.Count -lt 1) { Fail "generate requires packagePath" }
        $outputRoot = Parse-Option -Values $remaining -Name "--output-root"
        if ([string]::IsNullOrWhiteSpace($outputRoot)) {
            $outputRoot = $defaultContractRoot
        }
        Generate-Package -PackagePath $remaining[0] -OutputRoot $outputRoot
        Write-Host "Generated skill contract package: $($remaining[0])"
    }
    "validate-all" {
        Validate-All
        Write-Host "All Skill source packages are valid."
    }
    "generate-all" {
        $check = $remaining -contains "--check"
        $outputRoot = Parse-Option -Values $remaining -Name "--output-root"
        if ([string]::IsNullOrWhiteSpace($outputRoot)) {
            $outputRoot = $defaultContractRoot
        }
        Generate-All -OutputRoot $outputRoot -Check $check
        if ($check) {
            Write-Host "Skill contract generated outputs are up to date."
        } else {
            Write-Host "Generated all Skill contract packages."
        }
    }
    default {
        Fail "Unsupported command: $command"
    }
}
