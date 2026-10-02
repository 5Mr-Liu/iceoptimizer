[CmdletBinding()]
param(
    [string]$Archive,
    [string]$ModsDirectory,
    [string]$ExpectedSha256,
    [string]$RollbackManifest
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$rollbackRoot = Join-Path $projectRoot 'rollback'
$profilerPattern = '^ice-rlcraft-profiler(?:-core)?-[0-9][A-Za-z0-9.+_-]*\.jar$'

function Get-Hash([string]$Path) {
    (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash
}

function Resolve-ModsDirectory([string]$Path) {
    $resolved = [IO.Path]::GetFullPath($Path)
    if ((Split-Path -Leaf $resolved) -ne 'mods') { throw "Expected a directory named mods: $resolved" }
    if (-not (Test-Path -LiteralPath $resolved -PathType Container)) {
        throw "Mods directory does not exist: $resolved"
    }
    (Resolve-Path -LiteralPath $resolved).Path
}

function Get-Installed([string]$Directory) {
    @(Get-ChildItem -LiteralPath $Directory -File | Where-Object { $_.Name -match $profilerPattern })
}

function Backup-Installed([object[]]$Files, [string]$Directory) {
    New-Item -ItemType Directory -Path $Directory | Out-Null
    foreach ($file in $Files) {
        $hash = Get-Hash $file.FullName
        $destination = Join-Path $Directory $file.Name
        Copy-Item -LiteralPath $file.FullName -Destination $destination
        if ((Get-Hash $destination) -ne $hash) { throw "Backup verification failed: $destination" }
        [pscustomobject]@{ Name = $file.Name; Sha256 = $hash }
    }
}

function Assert-SourceArchive([string]$Path) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($Path)
    try {
        $names = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)
        foreach ($entry in $zip.Entries) {
            if (-not $names.Add($entry.FullName)) { throw "Duplicate JAR entry: $($entry.FullName)" }
        }
        foreach ($name in @('dev/rlcraft/ice/IceProfilerMod.class', 'dev/rlcraft/ice/hooks/IceProfilerLoadingPlugin.class',
            'dev/rlcraft/ice/hooks/IceProfilerTransformer.class', 'dev/rlcraft/ice/hooks/ProbeProtocol.class', 'mcmod.info')) {
            if (-not $names.Contains($name)) { throw "Not a complete single profiler JAR: missing $name" }
        }
        $entry = $zip.GetEntry('META-INF/MANIFEST.MF')
        if ($null -eq $entry) { throw 'Missing JAR manifest' }
        $reader = New-Object IO.StreamReader($entry.Open())
        try { $manifest = $reader.ReadToEnd() -replace '\r?\n ', '' } finally { $reader.Dispose() }
        if ($manifest -notmatch '(?m)^FMLCorePlugin: dev\.rlcraft\.ice\.hooks\.IceProfilerLoadingPlugin\r?$' -or
            $manifest -notmatch '(?m)^FMLCorePluginContainsFMLMod: true\r?$') {
            throw 'The archive does not declare a complete Forge profiler plugin/mod'
        }
        if ($manifest -notmatch '(?m)^Implementation-Version: ([^\r\n]+)\r?$') { throw 'Missing version' }
        $version = $Matches[1]
        if ((Split-Path -Leaf $Path) -ne "ice-rlcraft-profiler-$version.jar") {
            throw 'Profiler filename and manifest version disagree'
        }
        $reader = New-Object IO.StreamReader($zip.GetEntry('mcmod.info').Open())
        try { $metadata = @($reader.ReadToEnd() | ConvertFrom-Json) } finally { $reader.Dispose() }
        if ($metadata.Count -ne 1 -or $metadata[0].modid -ne 'iceprofiler' -or $metadata[0].version -ne $version) {
            throw 'Profiler metadata and manifest version disagree'
        }
    } finally { $zip.Dispose() }
}

if (-not [string]::IsNullOrWhiteSpace($RollbackManifest)) {
    if (-not [string]::IsNullOrWhiteSpace($Archive)) { throw 'Choose deployment or rollback' }
    $manifestPath = (Resolve-Path -LiteralPath $RollbackManifest).Path
    $backupDirectory = Split-Path -Parent $manifestPath
    $resolvedRollbackRoot = [IO.Path]::GetFullPath($rollbackRoot).TrimEnd('\') + '\'
    if (-not $manifestPath.StartsWith($resolvedRollbackRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Rollback manifests must be inside this workspace rollback directory'
    }
    $record = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    if ($record.Product -ne 'ice-rlcraft-profiler') { throw 'Not a profiler deployment manifest' }
    if (-not [string]::IsNullOrWhiteSpace($ModsDirectory) -and
        (Resolve-ModsDirectory $ModsDirectory) -ne (Resolve-ModsDirectory $record.ModsDirectory)) {
        throw 'The rollback target differs from the recorded mods directory'
    }
    $ModsDirectory = Resolve-ModsDirectory $record.ModsDirectory
    foreach ($old in @($record.Previous)) {
        if ($old.Name -notmatch $profilerPattern -or (Split-Path -Leaf $old.Name) -ne $old.Name) {
            throw 'Invalid profiler filename in rollback manifest'
        }
        if ((Get-Hash (Join-Path $backupDirectory $old.Name)) -ne $old.Sha256) {
            throw "Rollback backup hash mismatch: $($old.Name)"
        }
    }
    $current = @(Get-Installed $ModsDirectory)
    $displacedDirectory = Join-Path $backupDirectory ('rollback-displaced-' + [Guid]::NewGuid().ToString('N'))
    $displaced = @(Backup-Installed $current $displacedDirectory)
    try {
        foreach ($file in $current) { Remove-Item -LiteralPath $file.FullName }
        foreach ($old in @($record.Previous)) {
            $destination = Join-Path $ModsDirectory $old.Name
            Copy-Item -LiteralPath (Join-Path $backupDirectory $old.Name) -Destination $destination
            if ((Get-Hash $destination) -ne $old.Sha256) { throw "Restored hash mismatch: $destination" }
        }
    } catch {
        foreach ($file in (Get-Installed $ModsDirectory)) { Remove-Item -LiteralPath $file.FullName }
        foreach ($file in $displaced) {
            Copy-Item -LiteralPath (Join-Path $displacedDirectory $file.Name) -Destination (Join-Path $ModsDirectory $file.Name)
        }
        throw
    }
    [pscustomobject]@{ State = 'RolledBack'; ModsDirectory = $ModsDirectory; DisplacedBackup = $displacedDirectory }
    return
}

if ([string]::IsNullOrWhiteSpace($Archive)) {
    $properties = Get-Content -LiteralPath (Join-Path $projectRoot 'gradle.properties') -Raw
    if ($properties -notmatch '(?m)^mod_version=([^\r\n]+)') { throw 'Missing build version' }
    $Archive = Join-Path $projectRoot "build\libs\ice-rlcraft-profiler-$($Matches[1]).jar"
}
$Archive = (Resolve-Path -LiteralPath $Archive).Path
Assert-SourceArchive $Archive
$sourceHash = Get-Hash $Archive
if (-not [string]::IsNullOrWhiteSpace($ExpectedSha256) -and $sourceHash -ne $ExpectedSha256) {
    throw 'Source archive SHA-256 does not match the requested build'
}
if ([string]::IsNullOrWhiteSpace($ModsDirectory)) {
    $ModsDirectory = Join-Path $projectRoot 'build\deploy-fixtures\manual\mods'
    New-Item -ItemType Directory -Path $ModsDirectory -Force | Out-Null
}
$ModsDirectory = Resolve-ModsDirectory $ModsDirectory
$installed = @(Get-Installed $ModsDirectory)
$newName = Split-Path -Leaf $Archive
$destination = Join-Path $ModsDirectory $newName
if ($installed.Count -eq 1 -and $installed[0].Name -eq $newName -and (Get-Hash $destination) -eq $sourceHash) {
    [pscustomobject]@{ State = 'AlreadyCurrent'; ModsDirectory = $ModsDirectory; Sha256 = $sourceHash }
    return
}
$stage = "$destination.deploying"
if (Test-Path -LiteralPath $stage) { throw "Unfinished deployment file exists: $stage" }
$backupDirectory = Join-Path $rollbackRoot ('profiler-singlejar-' + [Guid]::NewGuid().ToString('N'))
$previous = @(Backup-Installed $installed $backupDirectory)
$manifestPath = Join-Path $backupDirectory 'deployment.json'
[pscustomobject]@{
    Product = 'ice-rlcraft-profiler'; ModsDirectory = $ModsDirectory
    Installed = $newName; Sha256 = $sourceHash; Previous = $previous
} | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $manifestPath -Encoding UTF8
$mutationStarted = $false
try {
    Copy-Item -LiteralPath $Archive -Destination $stage
    if ((Get-Hash $stage) -ne $sourceHash) { throw 'Staged archive hash mismatch' }
    $mutationStarted = $true
    foreach ($file in $installed) { Remove-Item -LiteralPath $file.FullName }
    Move-Item -LiteralPath $stage -Destination $destination
    if ((Get-Hash $destination) -ne $sourceHash) { throw 'Installed archive hash mismatch' }
} catch {
    if ($mutationStarted) {
        if (Test-Path -LiteralPath $destination -PathType Leaf) { Remove-Item -LiteralPath $destination }
        foreach ($old in $previous) {
            Copy-Item -LiteralPath (Join-Path $backupDirectory $old.Name) -Destination (Join-Path $ModsDirectory $old.Name) -Force
        }
    }
    if (Test-Path -LiteralPath $stage -PathType Leaf) { Remove-Item -LiteralPath $stage }
    throw
}
[pscustomobject]@{ State = 'Installed'; ModsDirectory = $ModsDirectory; Sha256 = $sourceHash; RollbackManifest = $manifestPath }
