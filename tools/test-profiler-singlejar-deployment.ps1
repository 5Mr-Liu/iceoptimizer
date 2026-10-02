[CmdletBinding()]
param([Parameter(Mandatory = $true)][string]$Archive)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$projectRoot = Split-Path -Parent $PSScriptRoot
$deployment = Join-Path $PSScriptRoot 'deploy-profiler.ps1'
$Archive = (Resolve-Path -LiteralPath $Archive).Path
$archiveHash = (Get-FileHash -LiteralPath $Archive -Algorithm SHA256).Hash
$fixtureRoot = Join-Path $projectRoot ('.refactor-backups\profiler-deploy-fixtures\' + [Guid]::NewGuid().ToString('N'))
$instance = Join-Path $fixtureRoot 'instance'
$mods = Join-Path $instance 'mods'
New-Item -ItemType Directory -Path $mods -Force | Out-Null

function Get-Snapshot {
    $snapshot = @{}
    foreach ($file in (Get-ChildItem -LiteralPath $instance -Recurse -File)) {
        $snapshot[$file.FullName] = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash
    }
    $snapshot
}

function Assert-Snapshot($Expected) {
    $actual = Get-Snapshot
    if ($actual.Count -ne $Expected.Count) { throw 'Fixture file set changed' }
    foreach ($path in $Expected.Keys) {
        if (-not $actual.ContainsKey($path) -or $actual[$path] -ne $Expected[$path]) {
            throw "Fixture changed: $path"
        }
    }
}

function Assert-Rejected([scriptblock]$Action, $Expected) {
    $rejected = $false
    try { & $Action | Out-Null } catch { $rejected = $true }
    if (-not $rejected) { throw 'Invalid deployment was accepted' }
    Assert-Snapshot $Expected
}

function New-InvalidArchive([string]$Case, [string]$EntryName, $Replacement) {
    $directory = Join-Path $fixtureRoot $Case
    New-Item -ItemType Directory -Path $directory | Out-Null
    $path = Join-Path $directory (Split-Path -Leaf $Archive)
    Copy-Item -LiteralPath $Archive -Destination $path
    $zip = [IO.Compression.ZipFile]::Open($path, [IO.Compression.ZipArchiveMode]::Update)
    try {
        $entry = $zip.GetEntry($EntryName)
        if ($null -eq $entry) { throw "Source archive lacks $EntryName" }
        $entry.Delete()
        if ($null -ne $Replacement) {
            $writer = New-Object IO.StreamWriter($zip.CreateEntry($EntryName).Open())
            try { $writer.Write($Replacement) } finally { $writer.Dispose() }
        }
    } finally { $zip.Dispose() }
    $path
}

foreach ($name in @('ice-rlcraft-profiler-1.0.10.jar', 'ice-rlcraft-profiler-core-1.0.10.jar',
    'ice-rlcraft-profiler-1.0.9.jar', 'ice-rlcraft-optimizer-1.0.11.jar',
    'ice-rlcraft-optimizer-core-1.0.7.jar', 'other[mod].jar', 'ice-rlcraft-profiler-helper.jar')) {
    [IO.File]::WriteAllText((Join-Path $mods $name), "original $name")
}
foreach ($relative in @('config\ice-profiler.cfg', 'reports\session\report.json', 'saves\world\level.dat')) {
    $path = Join-Path $instance $relative
    New-Item -ItemType Directory -Path (Split-Path -Parent $path) -Force | Out-Null
    [IO.File]::WriteAllText($path, "preserve $relative")
}
$original = Get-Snapshot
Assert-Rejected { & $deployment -Archive $Archive -ModsDirectory $mods -ExpectedSha256 ('0' * 64) } $original

# Archive validation must happen before any installed JAR is removed.
foreach ($entry in @('dev/rlcraft/ice/IceProfilerMod.class', 'dev/rlcraft/ice/hooks/IceProfilerLoadingPlugin.class',
    'dev/rlcraft/ice/hooks/IceProfilerTransformer.class', 'dev/rlcraft/ice/hooks/ProbeProtocol.class')) {
    $invalid = New-InvalidArchive ('missing-' + (Split-Path -Leaf $entry)) $entry $null
    Assert-Rejected { & $deployment -Archive $invalid -ModsDirectory $mods } $original
}
$invalid = New-InvalidArchive 'invalid-plugin' 'META-INF/MANIFEST.MF' "Manifest-Version: 1.0`r`nImplementation-Version: 1.0.12`r`n`r`n"
Assert-Rejected { & $deployment -Archive $invalid -ModsDirectory $mods } $original
$zip = [IO.Compression.ZipFile]::OpenRead($Archive)
try {
    $reader = New-Object IO.StreamReader($zip.GetEntry('META-INF/MANIFEST.MF').Open())
    try { $sourceManifest = $reader.ReadToEnd() } finally { $reader.Dispose() }
} finally { $zip.Dispose() }
$invalid = New-InvalidArchive 'invalid-mod-flag' 'META-INF/MANIFEST.MF' ($sourceManifest -replace 'FMLCorePluginContainsFMLMod: true', 'FMLCorePluginContainsFMLMod: false')
Assert-Rejected { & $deployment -Archive $invalid -ModsDirectory $mods } $original
$invalid = New-InvalidArchive 'invalid-metadata' 'mcmod.info' '[{"modid":"iceprofiler","version":"0.0.0"}]'
Assert-Rejected { & $deployment -Archive $invalid -ModsDirectory $mods } $original
$wrongName = Join-Path $fixtureRoot 'ice-rlcraft-profiler-0.0.0.jar'
Copy-Item -LiteralPath $Archive -Destination $wrongName
Assert-Rejected { & $deployment -Archive $wrongName -ModsDirectory $mods } $original

# Inject a move failure after the script removed the old JARs. No production
# test switches or filesystem permissions are needed to exercise restoration.
function Invoke-MoveFailure {
    function Move-Item { param($LiteralPath, $Destination) throw 'fixture move failure' }
    & $deployment -Archive $Archive -ModsDirectory $mods
}
Assert-Rejected { Invoke-MoveFailure } $original

$result = & $deployment -Archive $Archive -ModsDirectory $mods -ExpectedSha256 $archiveHash
if ($result.State -ne 'Installed') { throw 'Migration did not install' }
$installed = Get-Snapshot
$newArchive = Join-Path $mods (Split-Path -Leaf $Archive)
$expectedInstalled = $original.Clone()
foreach ($path in @($original.Keys)) {
    if ((Split-Path -Parent $path) -eq $mods -and
        (Split-Path -Leaf $path) -match '^ice-rlcraft-profiler(?:-core)?-[0-9][A-Za-z0-9.+_-]*\.jar$') {
        $expectedInstalled.Remove($path)
    }
}
$expectedInstalled[$newArchive] = $archiveHash
Assert-Snapshot $expectedInstalled

$record = Get-Content -LiteralPath $result.RollbackManifest -Raw | ConvertFrom-Json
if ($record.Previous.Count -ne 3) { throw 'Backup did not contain exactly the three old profiler JARs' }
$again = & $deployment -Archive $Archive -ModsDirectory $mods
if ($again.State -ne 'AlreadyCurrent') { throw 'Deployment is not idempotent' }
Assert-Snapshot $installed

# A damaged rollback backup must be rejected before replacing the installed JAR.
$backup = Join-Path (Split-Path -Parent $result.RollbackManifest) $record.Previous[0].Name
$backupBytes = [IO.File]::ReadAllBytes($backup)
try {
    [IO.File]::WriteAllText($backup, 'damaged backup fixture')
    Assert-Rejected { & $deployment -RollbackManifest $result.RollbackManifest } $installed
} finally { [IO.File]::WriteAllBytes($backup, $backupBytes) }
$manifestText = [IO.File]::ReadAllText($result.RollbackManifest)
try {
    $record.Product = 'ice-rlcraft-optimizer'
    $record | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $result.RollbackManifest -Encoding UTF8
    Assert-Rejected { & $deployment -RollbackManifest $result.RollbackManifest } $installed
} finally { [IO.File]::WriteAllText($result.RollbackManifest, $manifestText) }

$rollback = & $deployment -RollbackManifest $result.RollbackManifest
if ($rollback.State -ne 'RolledBack') { throw 'Rollback did not complete' }
Assert-Snapshot $original

# A new installation has an empty previous-file snapshot; rolling it back
# must remove the profiler without inventing a Main/Core pair.
$instance = Join-Path $fixtureRoot 'fresh-instance'
$mods = Join-Path $instance 'mods'
New-Item -ItemType Directory -Path $mods -Force | Out-Null
$fresh = & $deployment -Archive $Archive -ModsDirectory $mods
if ($fresh.State -ne 'Installed') { throw 'Fresh installation did not complete' }
$rollback = & $deployment -RollbackManifest $fresh.RollbackManifest
if ($rollback.State -ne 'RolledBack') { throw 'Fresh installation rollback did not complete' }
Assert-Snapshot @{}
Write-Output "PASS: single-JAR validation, Main/Core/single migration, source hash rejection, failure restoration, idempotency, exact rollback, fresh install/rollback, damaged-backup rejection, optimizer/other mods/config/reports/world preservation. Fixture: $fixtureRoot"
