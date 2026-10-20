param(
    [string]$GameDirectory = '',
    [string]$OutputDirectory = ''
)
$ErrorActionPreference = 'Stop'
if (-not $GameDirectory) { $GameDirectory = $PSScriptRoot }
$iceGame = (Resolve-Path -LiteralPath $GameDirectory).Path
if (-not (Test-Path -LiteralPath (Join-Path $iceGame 'mods') -PathType Container) -or
    -not (Test-Path -LiteralPath (Join-Path $iceGame 'logs') -PathType Container)) {
    throw 'Place this tool in the game version folder containing mods and logs.'
}
if (-not $OutputDirectory) {
    $OutputDirectory = Join-Path ([Environment]::GetFolderPath('Desktop')) 'ICE-Diagnostics'
}
$iceOutput = [IO.Path]::GetFullPath($OutputDirectory)
$iceStamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$iceStage = Join-Path $iceOutput ("ice-crash-$iceStamp")
New-Item -ItemType Directory -Path $iceStage -Force | Out-Null
$iceCopied = [Collections.Generic.List[object]]::new()
$iceMissing = [Collections.Generic.List[string]]::new()
function Copy-IceEvidence([string]$RelativePath) {
    $iceSource = Join-Path $iceGame $RelativePath
    if (-not (Test-Path -LiteralPath $iceSource -PathType Leaf)) {
        $iceMissing.Add($RelativePath)
        return
    }
    $iceTarget = Join-Path $iceStage $RelativePath
    New-Item -ItemType Directory -Path (Split-Path $iceTarget -Parent) -Force | Out-Null
    try {
        Copy-Item -LiteralPath $iceSource -Destination $iceTarget
        $iceInfo = Get-Item -LiteralPath $iceSource
        $iceCopied.Add(@{file=$RelativePath;sourceModifiedUtc=$iceInfo.LastWriteTimeUtc.ToString('o');
            bytes=(Get-Item -LiteralPath $iceTarget).Length;
            sha256=(Get-FileHash -LiteralPath $iceTarget -Algorithm SHA256).Hash})
    } catch {
        $iceMissing.Add("${RelativePath}: copy failed")
        Write-Warning "Could not copy $RelativePath. Close the game and try again."
    }
}
foreach ($iceRelative in @('logs/latest.log','logs/debug.log','logs/fml-client-latest.log',
    'logs/betterfoliage.log','optionsof.txt','optionsshaders.txt','config/ice-optimizer.cfg')) {
    Copy-IceEvidence $iceRelative
}
foreach ($iceArchive in @(Get-ChildItem -LiteralPath (Join-Path $iceGame 'logs') -File -Filter '*.log.gz' |
    Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 3)) {
    Copy-IceEvidence ('logs/' + $iceArchive.Name)
}
$iceCrashes = Join-Path $iceGame 'crash-reports'
if (Test-Path -LiteralPath $iceCrashes -PathType Container) {
    foreach ($iceCrash in @(Get-ChildItem -LiteralPath $iceCrashes -File -Filter '*.txt' |
        Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 3)) {
        Copy-IceEvidence ('crash-reports/' + $iceCrash.Name)
    }
}
$iceMods = @(Get-ChildItem -LiteralPath (Join-Path $iceGame 'mods') -File -Recurse -Filter '*.jar' |
    Sort-Object FullName | ForEach-Object {
        @{file=$_.FullName.Substring($iceGame.Length + 1);bytes=$_.Length;
          sha256=$(if ($_.Name -match '(?i)ice.*optimizer|optifine|fermiumasm|renderlib|rlfoliage') {
              (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
          } else { $null })}
    })
ConvertTo-Json -InputObject $iceMods -Depth 4 |
    Set-Content -LiteralPath (Join-Path $iceStage 'mod-inventory.json') -Encoding UTF8
$iceHardware = @{osVersion=[Environment]::OSVersion.VersionString;logicalProcessors=[Environment]::ProcessorCount}
try {
    $iceHardware.cpu = @(Get-CimInstance Win32_Processor | Select-Object Name,NumberOfCores,NumberOfLogicalProcessors)
    $iceHardware.gpu = @(Get-CimInstance Win32_VideoController | Select-Object Name,DriverVersion,DriverDate)
} catch { $iceHardware.hardwareQuery = 'unavailable' }
ConvertTo-Json -InputObject $iceHardware -Depth 5 |
    Set-Content -LiteralPath (Join-Path $iceStage 'hardware.json') -Encoding UTF8
@{collectedUtc=[DateTime]::UtcNow.ToString('o');copied=@($iceCopied.ToArray());missing=@($iceMissing.ToArray())} |
    ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $iceStage 'collection.json') -Encoding UTF8
Add-Type -AssemblyName System.IO.Compression.FileSystem
$iceZip = $iceStage + '.zip'
[IO.Compression.ZipFile]::CreateFromDirectory($iceStage, $iceZip)
Write-Output "Saved diagnostic archive: $iceZip"
Write-Output 'The game files were only read. This tool does not upload anything.'
if (-not (Test-Path -LiteralPath (Join-Path $iceStage 'logs/latest.log'))) {
    Write-Warning 'latest.log is missing. The archive may not contain the first error.'
}
