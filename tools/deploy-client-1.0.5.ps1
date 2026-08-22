[CmdletBinding()]
param(
    [ValidateSet('RLCraft', 'Dregora')]
    [string]$Pack = 'Dregora',
    [string]$ModsDirectory
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$version = '1.0.5'
$projectRoot = Split-Path -Parent $PSScriptRoot
$artifacts = @(
    [PSCustomObject]@{
        Name = "ice-rlcraft-optimizer-$version.jar"
        Hash = 'FFCEBDDDCDBFDA20E04DE102B17DCB861D52188B14051419BDC0815521919EC4'
    },
    [PSCustomObject]@{
        Name = "ice-rlcraft-optimizer-core-$version.jar"
        Hash = '8F082BE7DFC7E0A4FDC03EBF86F9E4A32BC8C0FFA65A28CA0D6CDFE5FD65BF31'
    },
    [PSCustomObject]@{
        Name = "ice-rlcraft-profiler-$version.jar"
        Hash = '8CB4B2B145A3215750F49C2EA8EB89FE3476DBF5D524A85403FF8D60F525B2E9'
    },
    [PSCustomObject]@{
        Name = "ice-rlcraft-profiler-core-$version.jar"
        Hash = '6FE9D528874BD7C788B9C0775133C510FFBC1CEE9ACE3C20138C27BF3E64CA01'
    }
)

if ([string]::IsNullOrWhiteSpace($ModsDirectory)) {
    if ($Pack -eq 'Dregora') {
        $ModsDirectory = 'D:\Program Files\Mcserver\rlcraftDregora\.minecraft\versions\RLCraft Dregora\mods'
    }
    else {
        $ModsDirectory = 'D:\Program Files\Mcserver\Rlcraft\.minecraft\versions\RLCraft\mods'
    }
}

function Assert-FileHash {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Expected
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Missing required file: $Path"
    }
    $actual = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash
    if ($actual -ne $Expected) {
        $newline = [Environment]::NewLine
        throw ("SHA-256 mismatch for {0}{1}Expected: {2}{1}Actual:   {3}" -f $Path, $newline, $Expected, $actual)
    }
}

foreach ($artifact in $artifacts) {
    $artifact | Add-Member -NotePropertyName Source -NotePropertyValue (
        Join-Path $projectRoot "build\libs\$($artifact.Name)")
    Assert-FileHash -Path $artifact.Source -Expected $artifact.Hash
}

if (-not (Test-Path -LiteralPath $ModsDirectory -PathType Container)) {
    throw "RLCraft client mods directory does not exist: $ModsDirectory"
}
$ModsDirectory = (Resolve-Path -LiteralPath $ModsDirectory).Path
if ((Split-Path -Leaf $ModsDirectory) -ne 'mods') {
    throw "Refusing to deploy outside a directory named mods: $ModsDirectory"
}

$javaProcesses = @(Get-Process -ErrorAction SilentlyContinue | Where-Object {
    $_.ProcessName -in @('java', 'javaw')
})
if ($javaProcesses.Count -ne 0) {
    throw "Minecraft may still be running. Stop Java game processes before deploying ICE client $version."
}

$legacyCombined = @(Get-ChildItem -LiteralPath $ModsDirectory -File | Where-Object {
    $_.Name -match '^ice-rlcraft-runtime(?:-core)?-[0-9].*\.jar$'
})
if ($legacyCombined.Count -ne 0) {
    throw "Legacy combined ICE JAR(s) must be removed first: $($legacyCombined.Name -join ', ')"
}

$installed = @(Get-ChildItem -LiteralPath $ModsDirectory -File | Where-Object {
    $_.Name -match '^ice-rlcraft-(?:optimizer|profiler)(?:-core)?-[0-9].*\.jar$'
})
$expectedNames = @($artifacts | ForEach-Object Name)
$unexpectedCurrent = @($installed | Where-Object {
    $_.Name -notin $expectedNames
})
$alreadyCurrent = $unexpectedCurrent.Count -eq 0
foreach ($artifact in $artifacts) {
    $destination = Join-Path $ModsDirectory $artifact.Name
    if (-not (Test-Path -LiteralPath $destination -PathType Leaf)) {
        $alreadyCurrent = $false
        break
    }
    $actual = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash
    if ($actual -ne $artifact.Hash) {
        $alreadyCurrent = $false
        break
    }
}
if ($alreadyCurrent) {
    Write-Host "ICE optimizer and profiler $version are already deployed and verified for $Pack Client." -ForegroundColor Green
    exit 0
}

$stages = @()
foreach ($artifact in $artifacts) {
    $stage = Join-Path $ModsDirectory "$($artifact.Name).deploying"
    if (Test-Path -LiteralPath $stage) {
        throw "Unexpected unfinished deployment file exists: $stage"
    }
    $stages += $stage
}

$timestamp = Get-Date -Format 'yyyyMMdd-HHmmssfff'
$backupDirectory = Join-Path $projectRoot (
    "rollback\client-$Pack-before-$version-$timestamp")
New-Item -ItemType Directory -Path $backupDirectory | Out-Null
foreach ($file in $installed) {
    Copy-Item -LiteralPath $file.FullName -Destination $backupDirectory
}

try {
    for ($index = 0; $index -lt $artifacts.Count; $index++) {
        $artifact = $artifacts[$index]
        $stage = $stages[$index]
        Copy-Item -LiteralPath $artifact.Source -Destination $stage
        Assert-FileHash -Path $stage -Expected $artifact.Hash
    }

    foreach ($file in $installed) {
        Remove-Item -LiteralPath $file.FullName
    }
    for ($index = 0; $index -lt $artifacts.Count; $index++) {
        Move-Item -LiteralPath $stages[$index] -Destination (
            Join-Path $ModsDirectory $artifacts[$index].Name)
    }

    foreach ($artifact in $artifacts) {
        Assert-FileHash -Path (Join-Path $ModsDirectory $artifact.Name) `
            -Expected $artifact.Hash
    }
}
catch {
    foreach ($artifact in $artifacts) {
        $partial = Join-Path $ModsDirectory $artifact.Name
        if (Test-Path -LiteralPath $partial) {
            Remove-Item -LiteralPath $partial -Force -ErrorAction SilentlyContinue
        }
    }
    foreach ($stage in $stages) {
        if (Test-Path -LiteralPath $stage) {
            Remove-Item -LiteralPath $stage -Force -ErrorAction SilentlyContinue
        }
    }
    foreach ($backup in @(Get-ChildItem -LiteralPath $backupDirectory -File `
        -ErrorAction SilentlyContinue)) {
        Copy-Item -LiteralPath $backup.FullName -Destination (
            Join-Path $ModsDirectory $backup.Name) -Force `
            -ErrorAction SilentlyContinue
    }
    throw
}

Write-Host "ICE optimizer and profiler $version deployed successfully for $Pack Client." -ForegroundColor Green
Write-Host "Mods: $ModsDirectory"
Write-Host "Backup: $backupDirectory"
foreach ($artifact in $artifacts) {
    Write-Host "$($artifact.Name) SHA-256: $($artifact.Hash)"
}
Write-Host 'Other mods, saves, configs and ICE session data were left untouched.'
