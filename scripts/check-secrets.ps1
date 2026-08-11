[CmdletBinding()]
param(
    [switch]$Staged
)

$ErrorActionPreference = 'Stop'

function Test-SafePlaceholderValue {
    param(
        [string]$Value,
        [bool]$AllowBoolean = $false
    )

    if ($Value -eq '') {
        return $true
    }
    if ($Value -match '^\$\{[A-Za-z_][A-Za-z0-9_.-]*(?::)?\}$') {
        return $true
    }
    if ($Value -match '(?i)^(?:replace_[A-Za-z0-9._-]*|change_me[A-Za-z0-9._-]*|example|dummy|test|none|null|<[^>\r\n]+>|\{\{[^}\r\n]+\}\})$') {
        return $true
    }
    return $AllowBoolean -and $Value -match '(?i)^(?:true|false)$'
}

function Test-ReservedExampleValueList {
    param([string]$Value)

    $items = @($Value -split '\s*,\s*')
    if ($items.Count -eq 0) {
        return $false
    }
    foreach ($item in $items) {
        if ($item -notmatch '(?i)^(?:(?:jdbc:postgresql|https?|wss?)://)?(?:[^/@\s]+@)?(?:[a-z0-9-]+\.)*example\.(?:com|internal|invalid)(?::\d+)?(?:/[^,\s]*)?$') {
            return $false
        }
    }
    return $true
}

function Test-SafeInfrastructureValue {
    param(
        [string]$Value,
        [string]$ConfigKey,
        [string]$NormalizedPath
    )

    $isExplicitTestDatasourceUsername = (
        $NormalizedPath -match '(?i)^(?:.*?/)?src/test/' -and
        $ConfigKey -match '(?i)^(?:spring\.datasource\.username|SPRING_DATASOURCE_USERNAME)$' -and
        $Value -eq 'sa'
    )
    return (
        (Test-SafePlaceholderValue -Value $Value) -or
        (Test-ReservedExampleValueList -Value $Value) -or
        ($NormalizedPath -match '(?i)^(?:.*?/)?src/test/' -and
            $Value -match '(?i)^jdbc:h2:mem:[A-Za-z0-9._-]+(?:;[A-Za-z0-9._=-]+)*$') -or
        $isExplicitTestDatasourceUsername
    )
}

$repositoryRoot = (git rev-parse --show-toplevel).Trim()
if (-not $repositoryRoot) {
    throw 'Git repository root was not found.'
}

Push-Location $repositoryRoot
try {
    if ($Staged) {
        $files = @(git diff --cached --name-only --diff-filter=ACMR)
    } else {
        $files = @(git ls-files)
    }

    $files = @($files | Where-Object { $_ })
    $findings = [System.Collections.Generic.List[string]]::new()

    $blockedExtensions = @('.pem', '.key', '.p8', '.pk8', '.pkcs8', '.p12', '.pfx', '.jks', '.keystore')
    foreach ($file in $files) {
        $normalized = $file.Replace('\', '/')
        $leaf = [System.IO.Path]::GetFileName($normalized)
        $extension = [System.IO.Path]::GetExtension($leaf).ToLowerInvariant()

        $isAllowedExample = $normalized -eq '.env.example' -or $normalized.EndsWith('.example')
        $isBlockedPath = (
            (($leaf -eq '.env' -or $leaf.StartsWith('.env.')) -and -not $isAllowedExample) -or
            $leaf -match '(?i)^application-(?:local|secrets)\.' -or
            $leaf -in @('.pgpass', 'pg_service.conf') -or
            $blockedExtensions -contains $extension -or
            $normalized -match '(^|/)(\.gradle|build|bin|out|gradle-[0-9][^/]*|\.vscode)(/|$)' -or
            ($normalized -match '^config/.+\.(properties|ya?ml)$' -and -not $isAllowedExample)
        )

        if ($isBlockedPath) {
            $findings.Add("$normalized`: blocked secret or generated-file path")
            continue
        }

        $rawSize = (& git cat-file -s ":$normalized" 2>$null)
        if ($LASTEXITCODE -ne 0) {
            $findings.Add("$normalized`: Git index blob could not be read")
            continue
        }
        $fileSize = [long]$rawSize

        $textExtensions = @(
            '', '.properties', '.yml', '.yaml', '.env', '.json', '.xml', '.java', '.kt',
            '.groovy', '.gradle', '.html', '.js', '.ts', '.css', '.md', '.txt', '.ps1', '.sh',
            '.bat', '.cmd', '.conf', '.ini', '.toml', '.sql'
        )
        if ($textExtensions -notcontains $extension -and -not $isAllowedExample -and
                $leaf -notin @('Dockerfile', 'gradlew')) {
            continue
        }

        if ($fileSize -gt 2MB) {
            $findings.Add("$normalized`: text file exceeds the 2 MiB scan limit")
            continue
        }

        $content = @(& git cat-file blob ":$normalized")
        if ($LASTEXITCODE -ne 0) {
            $findings.Add("$normalized`: Git index blob could not be read")
            continue
        }

        $lineNumber = 0
        foreach ($line in $content) {
            $lineNumber++
            $category = $null
            $assignmentLine = $line.Trim().TrimEnd(',', ';').Trim()
            $inlineStructuredCategory = $null
            $dockerStructuredCategory = $null

            $inlineInfrastructurePattern = '(?i)["''](?<configKey>aspera\.node\.(?:url|username|allowed-origins)|spring\.datasource\.(?:url|username)|app\.bootstrap-admin\.(?:username|email)|ASPERA_NODE_(?:URL|USERNAME|ALLOWED_ORIGINS)|SPRING_DATASOURCE_(?:URL|USERNAME)|BOOTSTRAP_ADMIN_(?:USERNAME|EMAIL))["'']\s*:\s*["''](?<configValue>[^"'']*)["'']'
            foreach ($assignment in [regex]::Matches($assignmentLine, $inlineInfrastructurePattern)) {
                $configKey = $assignment.Groups['configKey'].Value
                $value = $assignment.Groups['configValue'].Value.Trim()
                if (-not (Test-SafeInfrastructureValue -Value $value -ConfigKey $configKey `
                        -NormalizedPath $normalized)) {
                    $inlineStructuredCategory = 'literal infrastructure endpoint or identity'
                    break
                }
            }

            if (-not $inlineStructuredCategory) {
                $inlineSecretPattern = '(?i)["''](?:aspera\.node\.password|spring\.datasource\.password|app\.bootstrap-admin\.password|ASPERA_NODE_PASSWORD|SPRING_DATASOURCE_PASSWORD|BOOTSTRAP_ADMIN_PASSWORD)["'']\s*:\s*["''](?<secretValue>[^"'']*)["'']'
                foreach ($assignment in [regex]::Matches($assignmentLine, $inlineSecretPattern)) {
                    $value = $assignment.Groups['secretValue'].Value.Trim()
                    if (-not (Test-SafePlaceholderValue -Value $value)) {
                        $inlineStructuredCategory = 'literal secret assignment'
                        break
                    }
                }
            }

            if ($leaf -eq 'Dockerfile' -and $assignmentLine -match '(?i)^(?:ENV|ARG)\s+') {
                $dockerInfrastructurePattern = '(?i)(?:^|\s)(?<configKey>aspera\.node\.(?:url|username|allowed-origins)|spring\.datasource\.(?:url|username)|app\.bootstrap-admin\.(?:username|email)|ASPERA_NODE_(?:URL|USERNAME|ALLOWED_ORIGINS)|SPRING_DATASOURCE_(?:URL|USERNAME)|BOOTSTRAP_ADMIN_(?:USERNAME|EMAIL))(?:\s*=\s*|\s+)(?<configValue>"(?:\\.|[^"])*"|''(?:\\.|[^''])*''|[^\s]+)'
                foreach ($assignment in [regex]::Matches($assignmentLine, $dockerInfrastructurePattern)) {
                    $configKey = $assignment.Groups['configKey'].Value
                    $value = $assignment.Groups['configValue'].Value.Trim().Trim('"', "'")
                    if (-not (Test-SafeInfrastructureValue -Value $value -ConfigKey $configKey `
                            -NormalizedPath $normalized)) {
                        $dockerStructuredCategory = 'literal infrastructure endpoint or identity'
                        break
                    }
                }

                if (-not $dockerStructuredCategory) {
                    $dockerSecretPattern = '(?i)(?:^|\s)(?:aspera\.node\.password|spring\.datasource\.password|app\.bootstrap-admin\.password|ASPERA_NODE_PASSWORD|SPRING_DATASOURCE_PASSWORD|BOOTSTRAP_ADMIN_PASSWORD)(?:\s*=\s*|\s+)(?<secretValue>"(?:\\.|[^"])*"|''(?:\\.|[^''])*''|[^\s]+)'
                    foreach ($assignment in [regex]::Matches($assignmentLine, $dockerSecretPattern)) {
                        $value = $assignment.Groups['secretValue'].Value.Trim().Trim('"', "'")
                        if (-not (Test-SafePlaceholderValue -Value $value)) {
                            $dockerStructuredCategory = 'literal secret assignment'
                            break
                        }
                    }
                }
            }

            if ($line -match '-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----') {
                $category = 'private key material'
            } elseif ($line -match '(?i)\b(?:gh[pousr]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,}|AKIA[0-9A-Z]{16}|sk-[A-Za-z0-9_-]{20,})\b') {
                $category = 'credential-shaped token'
            } elseif ($inlineStructuredCategory) {
                $category = $inlineStructuredCategory
            } elseif ($dockerStructuredCategory) {
                $category = $dockerStructuredCategory
            } elseif ($assignmentLine -match '(?i)^(?:(?:export|set|env|arg)\s+)?(?:\$env:)?["'']?(?<configKey>aspera\.node\.(?:url|username|allowed-origins)|spring\.datasource\.(?:url|username)|app\.bootstrap-admin\.(?:username|email)|ASPERA_NODE_(?:URL|USERNAME|ALLOWED_ORIGINS)|SPRING_DATASOURCE_(?:URL|USERNAME)|BOOTSTRAP_ADMIN_(?:USERNAME|EMAIL))["'']?\s*[:=]\s*(?<configValue>.*?)\s*$') {
                $configKey = $Matches['configKey']
                $value = $Matches['configValue'].Trim().Trim('"', "'")
                $safeConfigurationExample = Test-SafeInfrastructureValue -Value $value `
                    -ConfigKey $configKey -NormalizedPath $normalized
                if (-not $safeConfigurationExample) {
                    $category = 'literal infrastructure endpoint or identity'
                }
            } elseif ($assignmentLine -match '(?i)^(?:(?:export|set|env|arg)\s+)?(?:\$env:)?["'']?(?:aspera\.node\.password|spring\.datasource\.password|app\.bootstrap-admin\.password|ASPERA_NODE_PASSWORD|SPRING_DATASOURCE_PASSWORD|BOOTSTRAP_ADMIN_PASSWORD)["'']?\s*[:=]\s*(.*?)\s*$') {
                $value = $Matches[1].Trim().Trim('"', "'")
                $safeSecretPlaceholder = Test-SafePlaceholderValue -Value $value
                if (-not $safeSecretPlaceholder) {
                    $category = 'literal secret assignment'
                }
            } elseif ($line -match '(?i)(?:setPassword|passwordEncoder\.encode)\s*\(\s*["''](?<passwordLiteral>[^"'']{4,})["'']\s*\)') {
                $value = $Matches['passwordLiteral']
                if (-not (Test-SafePlaceholderValue -Value $value)) {
                    $category = 'hard-coded password literal'
                }
            } elseif ($assignmentLine -notmatch '^\s*[\{\[]' -and
                    $extension -in @('.properties', '.yml', '.yaml', '.env', '.json') -and
                    $line -match '(?i)^\s*["'']?[^#\r\n]*?(?:password|passwd|secret|token|api[_-]?key|private[_-]?key)["'']?\s*[:=]\s*(.*?)\s*[,;]?\s*$') {
                $value = $Matches[1].Trim().Trim('"', "'")
                $placeholder = Test-SafePlaceholderValue -Value $value -AllowBoolean $true
                if (-not $placeholder) {
                    $category = 'literal secret assignment'
                }
            }

            if ($category) {
                $findings.Add("$normalized`:$lineNumber`: $category")
            }
        }
    }

    if ($findings.Count -gt 0) {
        Write-Error ("Secret scan failed.`n" + ($findings -join "`n"))
        exit 1
    }

    Write-Host "Secret scan passed ($($files.Count) files checked)."
} finally {
    Pop-Location
}
