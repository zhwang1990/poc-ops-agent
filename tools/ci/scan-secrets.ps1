[CmdletBinding()]
param(
    [string] $RepositoryRoot
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RepositoryRoot)) {
    $RepositoryRoot = Join-Path $PSScriptRoot "../.."
}
$repositoryRoot = (Resolve-Path $RepositoryRoot).Path
$excludedDirectories = @(".git", "target", "node_modules", "artifacts", ".cache")
$textExtensions = @(
    ".java", ".kt", ".kts", ".xml", ".json", ".yaml", ".yml", ".properties",
    ".md", ".txt", ".ps1", ".sh", ".cmd", ".ts", ".tsx", ".js", ".jsx", ".mjs"
)
$configurationExtensions = @(".yaml", ".yml", ".properties", ".md", ".txt")
$sourceExtensions = @(".java", ".kt", ".kts", ".ts", ".tsx", ".js", ".jsx", ".mjs")
$scriptExtensions = @(".ps1", ".sh", ".cmd")
$secretPatterns = @(
    "-----BEGIN (RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----",
    "AKIA[0-9A-Z]{16}",
    "ghp_[A-Za-z0-9]{30,}",
    "github_pat_[A-Za-z0-9_]{30,}",
    "sk-[A-Za-z0-9]{32,}"
)
$configurationAssignment = [regex]::new(
    '(?i)(?<key>[A-Za-z][A-Za-z0-9_.-]*)\s*(?<assignment>[:=])\s*(?<value>.+?)\s*$')
$sourceAssignment = [regex]::new(
    '(?i)(?<key>[A-Za-z_][A-Za-z0-9_]*)\s*(?<assignment>=|:)\s*[''"](?<value>[^''"]+)[''"]')
$sourcePropertyAssignment = [regex]::new(
    '(?i)[''"](?<key>[A-Za-z][A-Za-z0-9_.-]*)=(?<value>[^''"]+)[''"]')
$environmentMapAssignment = [regex]::new(
    '(?i)(?:[''"](?<quotedKey>OPS_AGENT_[A-Z0-9_]+)[''"]|(?<bareKey>OPS_AGENT_[A-Z0-9_]+))\s*(?:,|:)\s*[''"](?<value>[^''"]+)[''"]')
$scriptEnvironmentAssignment = [regex]::new(
    '(?i)(?:\$env:|set\s+[''"]?)(?<key>[A-Za-z_][A-Za-z0-9_]*)\s*=\s*[''"]?(?<value>[^''"\r\n]+)')

function Test-AllowedInjection([string] $value) {
    $candidate = $value.Trim().Trim("'", '"')
    return $candidate -match '^\$\{[A-Z][A-Z0-9_]*\}$'
}

function Test-SensitiveCredentialIdentifier([string] $key) {
    $normalized = [regex]::Replace($key, '(?<=[a-z0-9])(?=[A-Z])', '_').ToLowerInvariant()
    $segments = @($normalized -split '[_.-]+' | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    if ($segments.Count -eq 0) {
        return $false
    }
    $lastSegment = $segments[-1]

    if ($lastSegment -in @("credential", "token", "secret", "password")) {
        return $true
    }
    if ($lastSegment -ne "key") {
        return $false
    }
    if ($segments.Count -eq 1 -or $key -cmatch '^[A-Z][A-Z0-9_]*$') {
        return $true
    }

    return $segments[-2] -in @(
        "api", "master", "secret", "private", "signing", "encryption", "hmac", "shared", "access"
    )
}

function Test-LiteralCredentialValue([string] $value, [bool] $isScriptAssignment) {
    $candidate = $value.Trim().Trim("'", '"')
    if (Test-AllowedInjection $candidate) {
        return $false
    }
    if ($isScriptAssignment -and
        $candidate -match '^(?:\$[A-Za-z_][A-Za-z0-9_]*|%[A-Za-z_][A-Za-z0-9_]*%|New-|Get-|Convert-|Join-Path|\[)') {
        return $false
    }
    return $true
}

function Test-NonSensitiveDocumentationExample([string] $key, [string] $assignment, [string] $value) {
    $candidate = $value.Trim().Trim("'", '"', ',', ';')
    if ($key -ceq "key" -and $candidate -match '^(?:\{|[A-Za-z0-9_-]+$)') {
        return $true
    }
    if ($assignment -eq ":" -and
        $candidate -match '^(?:string|number|boolean|unknown|any)(?:\s*[,;}\]])?$') {
        return $true
    }
    return $assignment -eq "=" -and
        $candidate -match '^[A-Za-z_][A-Za-z0-9_.()]*$' -and
        $candidate.Contains("(")
}

function New-Finding([string] $file, [int] $line, [string] $rule) {
    return [PSCustomObject]@{
        File = $file
        Line = $line
        Rule = $rule
    }
}

function Find-LiteralCredentialAssignments([System.IO.FileInfo] $file, [string] $relativePath) {
    $findings = @()
    $lines = [System.IO.File]::ReadAllLines($file.FullName)
    for ($index = 0; $index -lt $lines.Length; $index++) {
        $line = $lines[$index]
        $lineNumber = $index + 1
        $patterns = @()

        if ($configurationExtensions -contains $file.Extension) {
            $patterns += [PSCustomObject]@{ Regex = $configurationAssignment; Rule = "literal configuration credential assignment" }
        }
        if ($sourceExtensions -contains $file.Extension) {
            $patterns += [PSCustomObject]@{ Regex = $sourceAssignment; Rule = "literal source/test credential assignment" }
            $patterns += [PSCustomObject]@{ Regex = $sourcePropertyAssignment; Rule = "literal source/test property credential assignment" }
            $patterns += [PSCustomObject]@{ Regex = $environmentMapAssignment; Rule = "literal environment credential assignment" }
        }
        if ($scriptExtensions -contains $file.Extension) {
            $patterns += [PSCustomObject]@{ Regex = $scriptEnvironmentAssignment; Rule = "literal script credential assignment" }
        }

        foreach ($pattern in $patterns) {
            foreach ($match in $pattern.Regex.Matches($line)) {
                $key = $match.Groups["key"].Value
                if ([string]::IsNullOrWhiteSpace($key)) {
                    $key = $match.Groups["quotedKey"].Value
                }
                if ([string]::IsNullOrWhiteSpace($key)) {
                    $key = $match.Groups["bareKey"].Value
                }
                $value = $match.Groups["value"].Value
                $assignment = $match.Groups["assignment"].Value
                $isScriptAssignment = $pattern.Rule -eq "literal script credential assignment"
                $isNonSensitiveObjectKey = $pattern.Rule -eq "literal source/test credential assignment" -and
                    $assignment -eq ":" -and $key -ceq "key"
                $isNonSensitiveDocumentationExample = $file.Extension -eq ".md" -and
                    (Test-NonSensitiveDocumentationExample $key $assignment $value)
                if (-not $isNonSensitiveObjectKey -and
                    -not $isNonSensitiveDocumentationExample -and
                    (Test-SensitiveCredentialIdentifier $key) -and
                    (Test-LiteralCredentialValue $value $isScriptAssignment)) {
                    $findings += New-Finding $relativePath $lineNumber $pattern.Rule
                }
            }
        }
    }
    return $findings
}

$candidateFiles = Get-ChildItem -Path $repositoryRoot -Recurse -Force -File |
    Where-Object {
        $relativePath = $_.FullName.Substring($repositoryRoot.Length).TrimStart("\", "/")
        $segments = $relativePath -split "[\\/]"
        $isExcluded = $segments | Where-Object { $_ -in $excludedDirectories }
        -not $isExcluded -and
        $_.FullName -ne $PSCommandPath -and
        $_.Extension -in $textExtensions
    }

$findings = @(foreach ($file in $candidateFiles) {
    $relativePath = $file.FullName.Substring($repositoryRoot.Length).TrimStart("\", "/")
    foreach ($pattern in $secretPatterns) {
        $matches = Select-String -LiteralPath $file.FullName -Pattern $pattern -AllMatches
        foreach ($match in $matches) {
            New-Finding $relativePath $match.LineNumber "high-confidence secret signature"
        }
    }
    Find-LiteralCredentialAssignments $file $relativePath
})

if ($findings.Count -gt 0) {
    $findings | Sort-Object File, Line, Rule -Unique | ForEach-Object {
        Write-Output ("{0}:{1} [{2}]" -f $_.File, $_.Line, $_.Rule)
    }
    throw "Potential literal credentials found."
}

Write-Host "Secret scan passed."
