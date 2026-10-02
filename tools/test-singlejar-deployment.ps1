[CmdletBinding()]
param([Parameter(Mandatory = $true)][string]$Archive)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$fixtureRoot = Join-Path $projectRoot ('build\deploy-fixtures\' + [Guid]::NewGuid().ToString('N'))
$mods = Join-Path $fixtureRoot 'mods'
New-Item -ItemType Directory -Path $mods | Out-Null
$oldMain = Join-Path $mods 'ice-rlcraft-optimizer-1.0.7.jar'
$oldCore = Join-Path $mods 'ice-rlcraft-optimizer-core-1.0.7.jar'
$profiler = Join-Path $mods 'ice-rlcraft-profiler-1.0.7.jar'
[IO.File]::WriteAllBytes($oldMain, [byte[]]@(11, 22, 33))
[IO.File]::WriteAllBytes($oldCore, [byte[]]@(44, 55, 66))
[IO.File]::WriteAllBytes($profiler, [byte[]]@(77, 88, 99))
$oldMainHash = (Get-FileHash -LiteralPath $oldMain).Hash
$oldCoreHash = (Get-FileHash -LiteralPath $oldCore).Hash
$profilerHash = (Get-FileHash -LiteralPath $profiler).Hash
$archiveHash = (Get-FileHash -LiteralPath $Archive).Hash
$deployment = Join-Path $PSScriptRoot 'deploy-optimizer.ps1'
$failed = $false
try { & $deployment -Archive $Archive -ModsDirectory $mods -ExpectedSha256 ('0' * 64) | Out-Null }
catch { $failed = $true }
if (-not $failed -or (Get-FileHash -LiteralPath $oldMain).Hash -ne $oldMainHash) { throw 'Hash rejection changed the fixture' }
$result = & $deployment -Archive $Archive -ModsDirectory $mods -ExpectedSha256 $archiveHash
if ($result.State -ne 'Installed') { throw 'Migration did not install' }
if ((Test-Path -LiteralPath $oldMain) -or (Test-Path -LiteralPath $oldCore)) { throw 'Old Main/Core remained installed' }
$newArchive = Join-Path $mods (Split-Path -Leaf $Archive)
if ((Get-FileHash -LiteralPath $newArchive).Hash -ne $archiveHash) { throw 'New archive differs' }
if ((Get-FileHash -LiteralPath $profiler).Hash -ne $profilerHash) { throw 'Profiler changed' }
$again = & $deployment -Archive $Archive -ModsDirectory $mods
if ($again.State -ne 'AlreadyCurrent') { throw 'Deployment is not idempotent' }
$rollback = & $deployment -RollbackManifest $result.RollbackManifest
if ($rollback.State -ne 'RolledBack') { throw 'Rollback did not complete' }
if ((Test-Path -LiteralPath $newArchive) -or
    (Get-FileHash -LiteralPath $oldMain).Hash -ne $oldMainHash -or
    (Get-FileHash -LiteralPath $oldCore).Hash -ne $oldCoreHash -or
    (Get-FileHash -LiteralPath $profiler).Hash -ne $profilerHash) { throw 'Rollback did not restore the exact fixture' }
Write-Output "PASS: source hash rejection, Main/Core migration, Profiler preservation, idempotency, exact rollback. Fixture: $fixtureRoot"
