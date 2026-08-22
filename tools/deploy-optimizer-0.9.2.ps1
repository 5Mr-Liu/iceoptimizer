[CmdletBinding()]
param(
    [ValidateSet('RLCraft', 'Dregora')]
    [string]$Pack = 'Dregora',
    [ValidateSet('Client', 'Server')]
    [string]$Target = 'Client',
    [string]$ModsDirectory
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$sourceMain = Join-Path $projectRoot 'build\libs\ice-rlcraft-optimizer-0.9.2.jar'
$sourceCore = Join-Path $projectRoot 'build\libs\ice-rlcraft-optimizer-core-0.9.2.jar'
$expectedMainHash = 'AE885A59BA5732BC22F85A310819D802C00D2FC1FDBDCEB1D4801A986FEB33B2'
$expectedCoreHash = 'E5E2535D5337877F9B6F7071FE60207493A954C7DD0DFC93E4A87FE7BA24A9EC'

if ([string]::IsNullOrWhiteSpace($ModsDirectory)) {
    if ($Target -eq 'Server') {
        throw 'Dedicated-server deployment requires -ModsDirectory with the exact server mods directory.'
    }
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
        throw "SHA-256 mismatch for $Path`nExpected: $Expected`nActual:   $actual"
    }
}

Assert-FileHash -Path $sourceMain -Expected $expectedMainHash
Assert-FileHash -Path $sourceCore -Expected $expectedCoreHash

if (-not (Test-Path -LiteralPath $ModsDirectory -PathType Container)) {
    throw "RLCraft $Target mods directory does not exist: $ModsDirectory"
}
$ModsDirectory = (Resolve-Path -LiteralPath $ModsDirectory).Path
if ((Split-Path -Leaf $ModsDirectory) -ne 'mods') {
    throw "Refusing to deploy outside a directory named mods: $ModsDirectory"
}

$javaProcesses = @(Get-Process -ErrorAction SilentlyContinue | Where-Object {
    $_.ProcessName -in @('java', 'javaw')
})
if ($javaProcesses.Count -ne 0) {
    throw 'Minecraft or the dedicated server is still running. Stop every Java game process before deploying ICE Optimizer 0.9.2.'
}

$legacyCombined = @(Get-ChildItem -LiteralPath $ModsDirectory -File | Where-Object {
    $_.Name -match '^ice-rlcraft-runtime(?:-core)?-[0-9].*\.jar$'
})
if ($legacyCombined.Count -ne 0) {
    throw "Legacy combined ICE JAR(s) must be removed first: $($legacyCombined.Name -join ', ')"
}

$newMain = Join-Path $ModsDirectory 'ice-rlcraft-optimizer-0.9.2.jar'
$newCore = Join-Path $ModsDirectory 'ice-rlcraft-optimizer-core-0.9.2.jar'
$stageMain = Join-Path $ModsDirectory 'ice-rlcraft-optimizer-0.9.2.jar.deploying'
$stageCore = Join-Path $ModsDirectory 'ice-rlcraft-optimizer-core-0.9.2.jar.deploying'

foreach ($stage in @($stageMain, $stageCore)) {
    if (Test-Path -LiteralPath $stage) {
        throw "Unexpected unfinished deployment file exists: $stage"
    }
}

$installed = @(Get-ChildItem -LiteralPath $ModsDirectory -File | Where-Object {
    $_.Name -match '^ice-rlcraft-optimizer(?:-core)?-[0-9].*\.jar$'
})
$unexpectedCurrent = @($installed | Where-Object {
    $_.Name -notin @('ice-rlcraft-optimizer-0.9.2.jar', 'ice-rlcraft-optimizer-core-0.9.2.jar')
})
$alreadyCurrent =
    (Test-Path -LiteralPath $newMain -PathType Leaf) -and
    (Test-Path -LiteralPath $newCore -PathType Leaf) -and
    $unexpectedCurrent.Count -eq 0

if ($alreadyCurrent) {
    Assert-FileHash -Path $newMain -Expected $expectedMainHash
    Assert-FileHash -Path $newCore -Expected $expectedCoreHash
    Write-Host "ICE RLCraft Optimizer 0.9.2 is already deployed and verified for $Pack $Target." -ForegroundColor Green
    exit 0
}

$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$backupDirectory = Join-Path $projectRoot "rollback\optimizer-$Pack-$Target-before-0.9.2-$timestamp"
New-Item -ItemType Directory -Path $backupDirectory | Out-Null
foreach ($file in $installed) {
    Copy-Item -LiteralPath $file.FullName -Destination $backupDirectory
}

try {
    Copy-Item -LiteralPath $sourceMain -Destination $stageMain
    Copy-Item -LiteralPath $sourceCore -Destination $stageCore
    Assert-FileHash -Path $stageMain -Expected $expectedMainHash
    Assert-FileHash -Path $stageCore -Expected $expectedCoreHash

    foreach ($file in $installed) {
        Remove-Item -LiteralPath $file.FullName
    }
    Move-Item -LiteralPath $stageMain -Destination $newMain
    Move-Item -LiteralPath $stageCore -Destination $newCore

    Assert-FileHash -Path $newMain -Expected $expectedMainHash
    Assert-FileHash -Path $newCore -Expected $expectedCoreHash
}
catch {
    foreach ($partial in @($newMain, $newCore, $stageMain, $stageCore)) {
        if (Test-Path -LiteralPath $partial) {
            Remove-Item -LiteralPath $partial -Force -ErrorAction SilentlyContinue
        }
    }
    foreach ($backup in @(Get-ChildItem -LiteralPath $backupDirectory -File -ErrorAction SilentlyContinue)) {
        Copy-Item -LiteralPath $backup.FullName -Destination (Join-Path $ModsDirectory $backup.Name) -Force -ErrorAction SilentlyContinue
    }
    throw
}

Write-Host "ICE RLCraft Optimizer 0.9.2 deployed successfully for $Pack $Target." -ForegroundColor Green
Write-Host "Mods: $ModsDirectory"
Write-Host "Backup: $backupDirectory"
Write-Host "Main SHA-256: $expectedMainHash"
Write-Host "Core SHA-256: $expectedCoreHash"
Write-Host 'Existing ice-optimizer files and all saves were left untouched.'
