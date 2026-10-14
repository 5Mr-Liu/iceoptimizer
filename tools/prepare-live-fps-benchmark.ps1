param(
    [string]$GameDirectory = 'D:/Program Files/Mcserver/rlcraftDregora/.minecraft/versions/RLCraft Dregora',
    [string]$World = 'DregoraRL4',
    [string]$OutputDirectory = 'build/fps-target/live-baseline',
    [string]$BenchmarkRoot = '',
    [AllowEmptyString()][string]$OptimizerJar = '.refactor-backups/1.0.28-fps-target/baseline/artifacts/ice-rlcraft-optimizer-1.0.27.jar',
    [string]$Arm = 'frozen-1.0.27'
)
$ErrorActionPreference = 'Stop'
$iceRoot = Split-Path $PSScriptRoot -Parent
Push-Location -LiteralPath $iceRoot
try {
    $iceSource = (Resolve-Path -LiteralPath $GameDirectory).Path
    if ($BenchmarkRoot) {
        if (-not [IO.Path]::IsPathRooted($BenchmarkRoot) -or [IO.Path]::IsPathRooted($OutputDirectory)) {
            throw 'An explicit benchmark root must be absolute and OutputDirectory must be relative'
        }
        $iceBase = [IO.Path]::GetFullPath($BenchmarkRoot).TrimEnd('\','/')
        $iceSourcePrefix = $iceSource.TrimEnd('\','/') + [IO.Path]::DirectorySeparatorChar
        if ($iceBase -eq $iceSource -or $iceBase.StartsWith($iceSourcePrefix,[StringComparison]::OrdinalIgnoreCase)) {
            throw 'Benchmark root must not be inside the original game'
        }
        $iceDestination = [IO.Path]::GetFullPath((Join-Path $iceBase $OutputDirectory))
        $iceAllowed = $iceBase + [IO.Path]::DirectorySeparatorChar
    } else {
        $iceDestination = [IO.Path]::GetFullPath((Join-Path $iceRoot $OutputDirectory))
        $iceAllowed = [IO.Path]::GetFullPath((Join-Path $iceRoot 'build/fps-target')) + [IO.Path]::DirectorySeparatorChar
    }
    if (-not $iceDestination.StartsWith($iceAllowed, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Benchmark destination must remain under its isolated benchmark root'
    }
    $iceSourcePrefix = $iceSource.TrimEnd('\','/') + [IO.Path]::DirectorySeparatorChar
    $iceDestinationPrefix = $iceDestination.TrimEnd('\','/') + [IO.Path]::DirectorySeparatorChar
    if ($iceDestination -eq $iceSource -or
        $iceDestination.StartsWith($iceSourcePrefix,[StringComparison]::OrdinalIgnoreCase) -or
        $iceSource.StartsWith($iceDestinationPrefix,[StringComparison]::OrdinalIgnoreCase)) {
        throw 'Benchmark source and destination must not overlap'
    }
    if (Test-Path -LiteralPath (Join-Path $iceDestination 'ICE_ISOLATED_BENCHMARK.marker')) {
        throw 'Refusing to silently overwrite an existing benchmark game snapshot'
    }
    New-Item -ItemType Directory -Force -Path $iceDestination | Out-Null
    $iceLog = Join-Path $iceRoot 'build/fps-target/live-copy.log'
    foreach ($iceFolder in @('mods','config','scripts','resources','resourcepacks','shaderpacks',
        'structures','bendspacks','fancymenu_data','fancymenu_setups','llibrary')) {
        $iceFrom = Join-Path $iceSource $iceFolder
        if (-not (Test-Path -LiteralPath $iceFrom)) { continue }
        & robocopy $iceFrom (Join-Path $iceDestination $iceFolder) /E /COPY:DAT /DCOPY:DAT /R:1 /W:1 /MT:8 /NFL /NDL /NJH /NJS /NP /XF 'ice-rlcraft-optimizer-*.jar' 'ice-rlcraft-profiler-*.jar' >> $iceLog
        if ($LASTEXITCODE -ge 8) { throw "Copy failed: $iceFolder" }
    }
    foreach ($iceFile in @('options.txt','optionsof.txt','optionsshaders.txt','modpack.cfg','log4j2.xml')) {
        $iceFrom = Join-Path $iceSource $iceFile
        if (Test-Path -LiteralPath $iceFrom) { Copy-Item -LiteralPath $iceFrom -Destination $iceDestination }
    }
    $iceWorldSource = Join-Path (Join-Path $iceSource 'saves') $World
    if (-not (Test-Path -LiteralPath (Join-Path $iceWorldSource 'level.dat'))) { throw 'Source world missing' }
    & robocopy $iceWorldSource (Join-Path $iceDestination 'saves/icebench') /E /COPY:DAT /DCOPY:DAT /R:1 /W:1 /MT:8 /NFL /NDL /NJH /NJS /NP /XF 'session.lock' >> $iceLog
    if ($LASTEXITCODE -ge 8) { throw 'World snapshot failed' }
    if ($OptimizerJar) { Copy-Item -LiteralPath $OptimizerJar -Destination (Join-Path $iceDestination 'mods') }
    @{
        createdUtc = [DateTime]::UtcNow.ToString('o')
        sourceWorld = $World
        arm = $Arm
        sourceGameUnmodified = $true
        benchmarkRoot = $iceAllowed
    } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $iceDestination 'ICE_ISOLATED_BENCHMARK.marker') -Encoding UTF8
    Write-Output "Prepared isolated benchmark: $iceDestination"
} finally { Pop-Location }
