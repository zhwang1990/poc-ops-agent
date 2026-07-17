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
    '(?i)(?<key>[A-Za-z0-9_.-]*(?:secret|password))\s*[:=]\s*(?<value>.+?)\s*$')
$sourceAssignment = [regex]::new(
    '(?i)(?<key>[A-Za-z_][A-Za-z0-9_]*(?:secret|password))\s*=\s*[''"](?<value>[^''"]+)[''"]')
$sourcePropertyAssignment = [regex]::new(
    '(?i)[''"](?<key>[A-Za-z0-9_.-]*(?:secret|password))=(?<value>[^''"]+)[''"]')
$environmentMapAssignment = [regex]::new(
    '(?i)(?:[''"](?<quotedKey>OPS_AGENT_[A-Z0-9_]*(?:SECRET|PASSWORD))[''"]|(?<bareKey>OPS_AGENT_[A-Z0-9_]*(?:SECRET|PASSWORD)))\s*(?:,|:)\s*[''"](?<value>[^''"]+)[''"]')
$scriptEnvironmentAssignment = [regex]::new(
    '(?i)(?:\$env:|set\s+[''"]?)(?<key>[A-Za-z0-9_]*(?:secret|password))\s*=\s*[''"]?(?<value>[^''"\r\n]+)')

function Test-AllowedInjection([string] $value) {
    $candidate = $value.Trim().Trim("'", '"')
    return $candidate -match '^\$\{[A-Z][A-Z0-9_]*\}$'
}

function Test-LiteralScriptValue([string] $value) {
    $candidate = $value.Trim().Trim("'", '"')
    return -not ($candidate -match '^(?:\$|New-|Get-|Convert-|Join-Path|\[)')
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
            $match = $pattern.Regex.Match($line)
            $value = $match.Groups["value"].Value
            $isLiteralScriptValue = $pattern.Rule -ne "literal script credential assignment" -or
                (Test-LiteralScriptValue $value)
            if ($match.Success -and $isLiteralScriptValue -and -not (Test-AllowedInjection $value)) {
                $findings += New-Finding $relativePath $lineNumber $pattern.Rule
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
