[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repositoryRoot = (git rev-parse --show-toplevel).Trim()
if (-not $repositoryRoot) {
    throw 'Git repository root was not found.'
}

$requiresAsciiAlias = $repositoryRoot.ToCharArray() | Where-Object { [int]$_ -gt 127 } | Select-Object -First 1
$aliasDrive = $null
$exitCode = 1

try {
    if ($requiresAsciiAlias) {
        $used = @(Get-PSDrive -PSProvider FileSystem | ForEach-Object { $_.Name.ToUpperInvariant() })
        $aliasDrive = @('W', 'V', 'U', 'T', 'S', 'R') | Where-Object { $used -notcontains $_ } | Select-Object -First 1
        if (-not $aliasDrive) {
            throw 'No unused drive letter is available for the non-ASCII Windows path workaround.'
        }

        & subst.exe "$aliasDrive`:" $repositoryRoot
        if ($LASTEXITCODE -ne 0) {
            throw 'Failed to create the temporary ASCII-only drive alias.'
        }
        $buildRoot = "$aliasDrive`:\"
    } else {
        $buildRoot = $repositoryRoot
    }

    Push-Location $buildRoot
    try {
        & .\gradlew.bat --no-daemon clean test bootJar
        $exitCode = $LASTEXITCODE
    } finally {
        Pop-Location
    }
} finally {
    if ($aliasDrive) {
        & subst.exe "$aliasDrive`:" /D | Out-Null
    }
}

exit $exitCode
