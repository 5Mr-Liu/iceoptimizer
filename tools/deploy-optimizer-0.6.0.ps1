[CmdletBinding()]
param(
    [string]$ModsDirectory = 'D:\Program Files\Mcserver\Rlcraft\.minecraft\versions\RLCraft\mods'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$sourceMain = Join-Path $projectRoot 'build\libs\ice-rlcraft-optimizer-0.6.0.jar'
$sourceCore = Join-Path $projectRoot 'build\libs\ice-rlcraft-optimizer-core-0.6.0.jar'
$expectedMainHash = 'F1C97EA94399864F699AE45FCEFF995FF06CD112FFB58A1ED4BFD510BDEC41C7'
$expectedCoreHash = '239E43AB4182A74AF3443475BC1A87C6933DBA879996AFBD59FB9EAEA8E9F529'

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
    throw "RLCraft mods directory does not exist: $ModsDirectory"
}
$ModsDirectory = (Resolve-Path -LiteralPath $ModsDirectory).Path

$javaProcesses = @(Get-Process -ErrorAction SilentlyContinue | Where-Object {
    $_.ProcessName -in @('java', 'javaw')
})
if ($javaProcesses.Count -ne 0) {
    throw 'Minecraft/Java is still running. Close it before deploying 0.6.0.'
}

$oldMain = Join-Path $ModsDirectory 'ice-rlcraft-optimizer-0.5.0.jar'
$oldCore = Join-Path $ModsDirectory 'ice-rlcraft-optimizer-core-0.5.0.jar'
$newMain = Join-Path $ModsDirectory 'ice-rlcraft-optimizer-0.6.0.jar'
$newCore = Join-Path $ModsDirectory 'ice-rlcraft-optimizer-core-0.6.0.jar'
$stageMain = Join-Path $ModsDirectory 'ice-rlcraft-optimizer-0.6.0.jar.deploying'
$stageCore = Join-Path $ModsDirectory 'ice-rlcraft-optimizer-core-0.6.0.jar.deploying'

$alreadyDeployed =
    (Test-Path -LiteralPath $newMain -PathType Leaf) -and
    (Test-Path -LiteralPath $newCore -PathType Leaf) -and
    -not (Test-Path -LiteralPath $oldMain) -and
    -not (Test-Path -LiteralPath $oldCore)

if ($alreadyDeployed) {
    Assert-FileHash -Path $newMain -Expected $expectedMainHash
    Assert-FileHash -Path $newCore -Expected $expectedCoreHash
    Write-Host 'ICE RLCraft Optimizer 0.6.0 is already deployed and verified.' -ForegroundColor Green
    exit 0
}

foreach ($requiredOldFile in @($oldMain, $oldCore)) {
    if (-not (Test-Path -LiteralPath $requiredOldFile -PathType Leaf)) {
        throw "Expected 0.5.0 installation is incomplete: $requiredOldFile"
    }
}
foreach ($unexpectedFile in @($newMain, $newCore, $stageMain, $stageCore)) {
    if (Test-Path -LiteralPath $unexpectedFile) {
        throw "Unexpected deployment file already exists: $unexpectedFile"
    }
}

$installedOptimizerJars = @(Get-ChildItem -LiteralPath $ModsDirectory -File | Where-Object {
    $_.Name -match '^ice-rlcraft-optimizer(?:-core)?-[0-9].*\.jar$'
})
$expectedInstalledNames = @(
    'ice-rlcraft-optimizer-0.5.0.jar',
    'ice-rlcraft-optimizer-core-0.5.0.jar'
)
$unexpectedInstalled = @($installedOptimizerJars | Where-Object {
    $_.Name -notin $expectedInstalledNames
})
if ($unexpectedInstalled.Count -ne 0) {
    throw "Unexpected optimizer JAR(s) found: $($unexpectedInstalled.Name -join ', ')"
}

$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$backupDirectory = Join-Path $projectRoot "rollback\optimizer-0.5.0-before-0.6.0-$timestamp"
New-Item -ItemType Directory -Path $backupDirectory | Out-Null
Copy-Item -LiteralPath $oldMain -Destination $backupDirectory
Copy-Item -LiteralPath $oldCore -Destination $backupDirectory

try {
    Copy-Item -LiteralPath $sourceMain -Destination $stageMain
    Copy-Item -LiteralPath $sourceCore -Destination $stageCore
    Assert-FileHash -Path $stageMain -Expected $expectedMainHash
    Assert-FileHash -Path $stageCore -Expected $expectedCoreHash

    Remove-Item -LiteralPath $oldMain
    Remove-Item -LiteralPath $oldCore
    Move-Item -LiteralPath $stageMain -Destination $newMain
    Move-Item -LiteralPath $stageCore -Destination $newCore

    Assert-FileHash -Path $newMain -Expected $expectedMainHash
    Assert-FileHash -Path $newCore -Expected $expectedCoreHash
}
catch {
    foreach ($partialFile in @($newMain, $newCore, $stageMain, $stageCore)) {
        if (Test-Path -LiteralPath $partialFile) {
            Remove-Item -LiteralPath $partialFile -Force -ErrorAction SilentlyContinue
        }
    }
    if (-not (Test-Path -LiteralPath $oldMain)) {
        Copy-Item -LiteralPath (Join-Path $backupDirectory 'ice-rlcraft-optimizer-0.5.0.jar') -Destination $oldMain -ErrorAction SilentlyContinue
    }
    if (-not (Test-Path -LiteralPath $oldCore)) {
        Copy-Item -LiteralPath (Join-Path $backupDirectory 'ice-rlcraft-optimizer-core-0.5.0.jar') -Destination $oldCore -ErrorAction SilentlyContinue
    }
    throw
}

$profilerJars = @(Get-ChildItem -LiteralPath $ModsDirectory -File | Where-Object {
    $_.Name -like 'ice-rlcraft-profiler*.jar'
})
if ($profilerJars.Count -ne 0) {
    throw "Profiler JAR was not installed by this script, but one is present: $($profilerJars.Name -join ', ')"
}

Write-Host 'ICE RLCraft Optimizer 0.6.0 deployed successfully.' -ForegroundColor Green
Write-Host "Backup: $backupDirectory"
Write-Host "Main SHA-256: $expectedMainHash"
Write-Host "Core SHA-256: $expectedCoreHash"
