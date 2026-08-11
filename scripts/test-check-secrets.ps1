[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

$repositoryRoot = (git rev-parse --show-toplevel).Trim()
if (-not $repositoryRoot) {
    throw 'Git repository root was not found.'
}

$temporaryIndex = Join-Path ([System.IO.Path]::GetTempPath()) (
    'aspera-secret-scanner-' + [guid]::NewGuid().ToString('N') + '.index'
)
$originalIndex = $env:GIT_INDEX_FILE
$powerShellExecutable = (Get-Process -Id $PID).Path

function Set-SyntheticIndexEntry {
    param(
        [string]$Path,
        [string]$Content
    )

    & git read-tree HEAD
    if ($LASTEXITCODE -ne 0) {
        throw 'Could not initialize the synthetic Git index.'
    }
    $blob = ($Content | & git hash-object -w --stdin).Trim()
    if ($LASTEXITCODE -ne 0 -or -not $blob) {
        throw 'Could not create the synthetic Git blob.'
    }
    & git update-index --add --cacheinfo "100644,$blob,$Path"
    if ($LASTEXITCODE -ne 0) {
        throw "Could not stage the synthetic case: $Path"
    }
}

function Invoke-StagedSecretScan {
    $previousErrorPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $script:lastSecretScanOutput = @(& $powerShellExecutable -NoLogo -NoProfile -File `
            (Join-Path $repositoryRoot 'scripts/check-secrets.ps1') -Staged 2>&1)
        return $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorPreference
    }
}

Push-Location $repositoryRoot
try {
    $env:GIT_INDEX_FILE = $temporaryIndex
    $nodeKeyPrefix = 'ASPERA_' + 'NODE_'
    $passwordEncoderPrefix = 'password' + 'Encoder.encode("'

    $quotedEndpoint = [ordered]@{}
    $quotedEndpoint[$nodeKeyPrefix + 'URL'] = 'https://node.prod.invalid:9092'

    $negativeCases = @(
        @{
            Path = 'scripts/synthetic-quoted-endpoint.json'
            Content = $quotedEndpoint | ConvertTo-Json -Compress
        },
        @{
            Path = 'scripts/synthetic-node-user.conf'
            Content = ($nodeKeyPrefix + 'USERNAME=sa')
        },
        @{
            Path = '.env.example'
            Content = ($nodeKeyPrefix + 'PASSWORD=' + ('test' + 'ActualPassword_123'))
        },
        @{
            Path = 'scripts/synthetic-mixed-origins.toml'
            Content = ($nodeKeyPrefix + 'ALLOWED_ORIGINS=https://node.prod.invalid:9092,' +
                'https://node.example.internal:9092')
        },
        @{
            Path = 'src/main/resources/nested/application-secrets.yaml'
            Content = 'placeholder: value'
        },
        @{
            Path = 'ops/.pgpass'
            Content = 'placeholder'
        },
        @{
            Path = 'ops/signing-key.p8'
            Content = 'placeholder'
        },
        @{
            Path = 'Dockerfile'
            Content = ('ENV ' + $nodeKeyPrefix + 'PASSWORD=' + ('prod' + 'PasswordValue'))
        },
        @{
            Path = 'Dockerfile'
            Content = ('ENV ' + $nodeKeyPrefix + 'PASSWORD ' + ('legacy' + 'PasswordValue'))
        },
        @{
            Path = 'Dockerfile'
            Content = ('ENV SAFE_OPTION=x ' + $nodeKeyPrefix + 'URL=https://node.prod.invalid:9092')
        },
        @{
            Path = 'src/test/java/SyntheticPassword.java'
            Content = ('class SyntheticPassword { void configure() { ' + $passwordEncoderPrefix +
                ('prod' + 'PasswordValue') + '"); } }')
        }
    )

    foreach ($testCase in $negativeCases) {
        Set-SyntheticIndexEntry -Path $testCase.Path -Content $testCase.Content
        if ((Invoke-StagedSecretScan) -eq 0) {
            throw "Secret scanner did not reject: $($testCase.Path)"
        }
    }

    $safeConfiguration = [ordered]@{}
    $safeConfiguration[$nodeKeyPrefix + 'URL'] = 'https://node.example.internal:9092'
    $safeConfiguration[$nodeKeyPrefix + 'USERNAME'] = 'replace_with_node_username'
    $safeConfiguration[$nodeKeyPrefix + 'PASSWORD'] = '${ASPERA_NODE_PASSWORD:}'
    $safeConfiguration[$nodeKeyPrefix + 'ALLOWED_ORIGINS'] = (
        'https://node-a.example.internal:9092,https://node-b.example.internal:9092'
    )
    Set-SyntheticIndexEntry -Path 'scripts/synthetic-safe.json' `
        -Content ($safeConfiguration | ConvertTo-Json -Compress)
    if ((Invoke-StagedSecretScan) -ne 0) {
        throw ('Secret scanner rejected the safe placeholder case: ' +
            ($script:lastSecretScanOutput -join [Environment]::NewLine))
    }

    Set-SyntheticIndexEntry -Path 'src/test/java/SyntheticPlaceholder.java' `
        -Content 'class SyntheticPlaceholder { void configure() { passwordEncoder.encode("REPLACE_TEST_PASSWORD"); } }'
    if ((Invoke-StagedSecretScan) -ne 0) {
        throw ('Secret scanner rejected the explicit test placeholder case: ' +
            ($script:lastSecretScanOutput -join [Environment]::NewLine))
    }

    Write-Host "Secret scanner regression tests passed ($($negativeCases.Count + 2) cases)."
} finally {
    Pop-Location
    if ([string]::IsNullOrEmpty($originalIndex)) {
        Remove-Item Env:GIT_INDEX_FILE -ErrorAction SilentlyContinue
    } else {
        $env:GIT_INDEX_FILE = $originalIndex
    }
    foreach ($candidate in @($temporaryIndex, "$temporaryIndex.lock")) {
        if ((Test-Path -LiteralPath $candidate) -and
                [System.IO.Path]::GetFullPath($candidate).StartsWith(
                    [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath()),
                    [System.StringComparison]::OrdinalIgnoreCase)) {
            Remove-Item -LiteralPath $candidate -Force
        }
    }
}
