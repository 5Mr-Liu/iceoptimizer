param(
    [string]$GameDirectory = 'build/fps-target/live-baseline',
    [string]$OriginalGame = 'D:/Program Files/Mcserver/rlcraftDregora/.minecraft/versions/RLCraft Dregora',
    [string]$Arm = 'frozen-1.0.27',
    [int]$WarmSeconds = 60,
    [int]$SampleSeconds = 60,
    [switch]$Survival,
    [switch]$Walk,
    [switch]$Ablation,
    [switch]$RuntimeAblation,
    [switch]$KeyToggle,
    [switch]$KeyToggleStress,
    [switch]$KeyToggleRotation,
    [switch]$EmptyUploadProbe,
    [switch]$RequireChunkReuse,
    [switch]$TerrainAblation,
    [switch]$ModelAblation,
    [switch]$EntityAblation,
    [switch]$CaptureTerrain,
    [switch]$GroundStart,
    [ValidateSet('none','survival','combat')][string]$Scenario = 'none',
    [switch]$DumpRenderClasses,
    [switch]$ProfileSections,
    [switch]$CoarseSections,
    [switch]$StableCreative,
    [switch]$FixedCameraPath,
    [switch]$MatchVanillaEntityDistance,
    [switch]$FlightSample,
    [double]$CameraYaw = [double]::NaN,
    [double]$CameraPitch = [double]::NaN,
    [switch]$FixedFinalCapture,
    [switch]$DeterministicCombat,
    [string]$FixtureJar = 'build/fps-target/live-fixture/ice-live-benchmark.jar',
    [string]$DumpAgentJar = 'build/fps-target/live-fixture/render-class-dump-agent.jar',
    [string]$Java = 'C:/Program Files/Eclipse Adoptium/jdk-8.0.492.9-hotspot/bin/java.exe'
)
$ErrorActionPreference = 'Stop'
if ($KeyToggle -and -not $RuntimeAblation) { throw 'KeyToggle requires RuntimeAblation' }
if ($KeyToggleStress -and -not $KeyToggle) { throw 'KeyToggleStress requires KeyToggle' }
if ($KeyToggleRotation -and -not $KeyToggle) { throw 'KeyToggleRotation requires KeyToggle' }
$iceFixedCamera = -not [double]::IsNaN($CameraYaw) -or -not [double]::IsNaN($CameraPitch)
if ($iceFixedCamera -and ([double]::IsNaN($CameraYaw) -or [double]::IsNaN($CameraPitch) -or [double]::IsInfinity($CameraYaw) -or [double]::IsInfinity($CameraPitch) -or $Scenario -ne 'none' -or $CameraPitch -lt -90 -or $CameraPitch -gt 90)) { throw 'Fixed natural camera requires finite yaw/pitch, pitch in [-90,90], and Scenario none' }
if ($FixedCameraPath -and (-not $iceFixedCamera -or -not $StableCreative -or $Survival -or $Scenario -ne 'none' -or $Ablation -or $RuntimeAblation -or $TerrainAblation -or $ModelAblation -or $EntityAblation)) { throw 'FixedCameraPath requires fixed yaw/pitch and StableCreative without gameplay/ablation' }
if ($FixedFinalCapture -and ($Scenario -ne 'survival' -or $CaptureTerrain)) { throw 'FixedFinalCapture requires survival and excludes terrain capture' }
if ($CoarseSections -and (-not $ProfileSections -or $EntityAblation)) { throw 'CoarseSections requires ProfileSections and excludes per-entity ablation' }
if ($EntityAblation -and (-not $ProfileSections -or $Ablation -or $RuntimeAblation -or $TerrainAblation -or $ModelAblation -or $CaptureTerrain -or $Walk)) { throw 'Entity ablation requires plain section diagnostic' }
if ($CaptureTerrain -and (-not $ProfileSections -or $Ablation -or $RuntimeAblation -or $TerrainAblation -or $ModelAblation)) { throw 'Terrain capture requires plain section diagnostic' }
if ($GroundStart -and -not $Survival) { throw 'GroundStart requires Survival' }
if ($DeterministicCombat -and ($Scenario -ne 'combat' -or $ProfileSections -or $DumpRenderClasses)) { throw 'DeterministicCombat requires an unprofiled combat scenario' }
if (($Ablation -or $RuntimeAblation -or $TerrainAblation -or $ModelAblation) -and $Walk) { throw 'Ablation uses stationary views; do not combine it with Walk' }
$iceRoot = Split-Path $PSScriptRoot -Parent
$iceGamePath = if ([IO.Path]::IsPathRooted($GameDirectory)) { $GameDirectory } else { Join-Path $iceRoot $GameDirectory }
$iceGame = (Resolve-Path -LiteralPath $iceGamePath).Path
if (-not (Test-Path -LiteralPath (Join-Path $iceGame 'ICE_ISOLATED_BENCHMARK.marker'))) {
    throw 'Refusing to launch a non-isolated directory'
}
if (Test-Path -LiteralPath (Join-Path $iceGame 'benchmark-process.json')) {
    throw 'Completed/launched snapshots are immutable; prepare a fresh snapshot'
}
$iceVersionFile = Join-Path $OriginalGame 'RLCraft Dregora.json'
$iceVersion = Get-Content -LiteralPath $iceVersionFile -Raw | ConvertFrom-Json
$iceMinecraft = Split-Path (Split-Path $OriginalGame -Parent) -Parent
$iceClassPath = [Collections.Generic.List[string]]::new()
foreach ($iceLibrary in $iceVersion.libraries) {
    $iceAllowed = -not $iceLibrary.rules
    foreach ($iceRule in $iceLibrary.rules) {
        if ($iceRule.os -and $iceRule.os.name -and $iceRule.os.name -ne 'windows') { continue }
        if ($iceRule.os -and $iceRule.os.arch -and $iceRule.os.arch -notin @('x86_64','amd64')) { continue }
        if ($iceRule.features) { continue }
        $iceAllowed = $iceRule.action -eq 'allow'
    }
    if (-not $iceAllowed) { continue }
    if ($iceLibrary.name -match ':(lwjgl-platform|jinput-platform):') { continue }
    $iceRelative = $iceLibrary.downloads.artifact.path
    if (-not $iceRelative) {
        if ($iceLibrary.natives) { continue }
        $iceCoordinates = $iceLibrary.name.Split(':')
        $iceRelative = $iceCoordinates[0].Replace('.','/') + '/' + $iceCoordinates[1] + '/' + $iceCoordinates[2] + '/' + $iceCoordinates[1] + '-' + $iceCoordinates[2] + '.jar'
    }
    $iceLibraryPath = Join-Path (Join-Path $iceMinecraft 'libraries') $iceRelative
    if (-not (Test-Path -LiteralPath $iceLibraryPath)) { throw "Missing local library: $($iceLibrary.name)" }
    $iceClassPath.Add($iceLibraryPath)
}
$iceClassPath.Add((Join-Path $OriginalGame 'RLCraft Dregora.jar'))
$iceFixture = if ([IO.Path]::IsPathRooted($FixtureJar)) { $FixtureJar } else { Join-Path $iceRoot $FixtureJar }
Copy-Item -LiteralPath $iceFixture -Destination (Join-Path $iceGame 'mods/ice-live-benchmark.jar') -Force
$iceArgs = @('-Xms2G','-Xmx6G','-XX:+UseG1GC','-Dfile.encoding=UTF-8',
    ('-Djava.library.path=' + (Join-Path $OriginalGame 'natives-windows-x86_64')),
    ('-Dorg.lwjgl.librarypath=' + (Join-Path $OriginalGame 'natives-windows-x86_64')),
    "-Dice.bench.arm=$Arm", "-Dice.bench.warmSeconds=$WarmSeconds", "-Dice.bench.sampleSeconds=$SampleSeconds",
    ('-Dice.bench.survival=' + $Survival.IsPresent.ToString().ToLowerInvariant()),
    ('-Dice.bench.walk=' + $Walk.IsPresent.ToString().ToLowerInvariant()),
    ('-Dice.bench.ablation=' + $Ablation.IsPresent.ToString().ToLowerInvariant()),
    ('-Dice.bench.runtimeAblation=' + $RuntimeAblation.IsPresent.ToString().ToLowerInvariant()),
    ('-Dice.bench.keyToggle=' + $KeyToggle.IsPresent.ToString().ToLowerInvariant()),
    ('-Dice.bench.keyToggleStress=' + $KeyToggleStress.IsPresent.ToString().ToLowerInvariant()),
    ('-Dice.bench.keyToggleRotation=' + $KeyToggleRotation.IsPresent.ToString().ToLowerInvariant()),
    ('-Dice.bench.emptyUploadProbe=' + $EmptyUploadProbe.IsPresent.ToString().ToLowerInvariant()),
    ('-Dice.bench.requireChunkReuse=' + $RequireChunkReuse.IsPresent.ToString().ToLowerInvariant()),
    ('-Dice.bench.terrainAblation=' + $TerrainAblation.IsPresent.ToString().ToLowerInvariant()),
    ('-Dice.bench.modelAblation=' + $ModelAblation.IsPresent.ToString().ToLowerInvariant()),
    ('-Dice.bench.entityAblation=' + $EntityAblation.IsPresent.ToString().ToLowerInvariant()),
    ('-Dice.bench.captureTerrain=' + $CaptureTerrain.IsPresent.ToString().ToLowerInvariant()),
    ('-Dice.bench.groundStart=' + $GroundStart.IsPresent.ToString().ToLowerInvariant()),
    ('-Dice.bench.scenario=' + $Scenario),
    ('-Dice.bench.profileSections=' + $ProfileSections.IsPresent.ToString().ToLowerInvariant()),
    ('-Dice.bench.coarseSections=' + $CoarseSections.IsPresent.ToString().ToLowerInvariant()),
    ('-Dice.bench.stableCreative=' + $StableCreative.IsPresent.ToString().ToLowerInvariant()),
    ('-Dice.bench.fixedCameraPath=' + $FixedCameraPath.IsPresent.ToString().ToLowerInvariant()),
    ('-Dice.bench.matchVanillaEntityDistance=' + $MatchVanillaEntityDistance.IsPresent.ToString().ToLowerInvariant()),
    ('-Dice.bench.fixedFinalCapture=' + $FixedFinalCapture.IsPresent.ToString().ToLowerInvariant()),
    ('-Dice.bench.deterministicCombat=' + $DeterministicCombat.IsPresent.ToString().ToLowerInvariant()),
    '-cp',($iceClassPath -join ';'), $iceVersion.mainClass,
    '--tweakClass','net.minecraftforge.fml.common.launcher.FMLTweaker',
    '--username','ICEBench','--version','RLCraft Dregora','--gameDir',$iceGame,
    '--assetsDir',(Join-Path $iceMinecraft 'assets'),'--assetIndex',$iceVersion.assetIndex.id,
    '--uuid','00000000000000000000000000000001','--accessToken','0','--userType','legacy',
    '--userProperties','{}','--versionType','Forge','--width','1920','--height','1080')
# All credentials are dummy values for a local single-player snapshot.
if ($FlightSample) {
    $iceFlight = Join-Path $iceGame 'ice-benchmark/jfr'
    New-Item -ItemType Directory -Force -Path $iceFlight | Out-Null
    $iceArgs = @("-XX:FlightRecorderOptions=repository=$iceFlight",
        "-XX:StartFlightRecording=delay=120s,duration=180s,settings=profile,dumponexit=true,filename=$iceFlight/cpu.jfr") + $iceArgs
}
if ($iceFixedCamera) {
    $iceArgs = @(('-Dice.bench.cameraYaw=' + $CameraYaw.ToString([Globalization.CultureInfo]::InvariantCulture)),
        ('-Dice.bench.cameraPitch=' + $CameraPitch.ToString([Globalization.CultureInfo]::InvariantCulture))) + $iceArgs
}
if ($DumpRenderClasses -or $ProfileSections -or $DeterministicCombat) {
    $iceAgent = if ([IO.Path]::IsPathRooted($DumpAgentJar)) { $DumpAgentJar } else { Join-Path $iceRoot $DumpAgentJar }
    $iceAgentArgument = '-javaagent:' + $iceAgent
    if ($ProfileSections) { $iceAgentArgument += '=sections' }
    if ($DeterministicCombat) { $iceAgentArgument += '=gameplay' }
    $iceArgs = @($iceAgentArgument) + $iceArgs
}
# Quote each Windows argv element, preserving paths with spaces.
$iceQuoted = $iceArgs | ForEach-Object { '"' + $_.Replace('"','\"') + '"' }
$iceInitialWorldHash = (Get-FileHash -LiteralPath (Join-Path $iceGame 'saves/icebench/level.dat') -Algorithm SHA256).Hash
$iceOptionHashes = @(@('options.txt','optionsof.txt','optionsshaders.txt') | ForEach-Object {
    $iceOptionPath = Join-Path $iceGame $_
    if (Test-Path -LiteralPath $iceOptionPath) { @{name=$_;sha256=(Get-FileHash -LiteralPath $iceOptionPath -Algorithm SHA256).Hash} }
})
# Freeze all top-level input mod identities before starting the JVM, including
# alternative renderers that are not named ice-rlcraft-optimizer. Keep the large
# inventory separate from the compact process metadata.
$iceInputMods = @(Get-ChildItem -LiteralPath (Join-Path $iceGame 'mods') -File -Filter '*.jar' |
    Sort-Object Name | ForEach-Object {
        @{name=$_.Name;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash}
    })
$iceInputModsPath = Join-Path $iceGame 'benchmark-input-mods.json'
ConvertTo-Json -InputObject $iceInputMods -Depth 4 |
    Set-Content -LiteralPath $iceInputModsPath -Encoding UTF8
$iceInputModsHash = (Get-FileHash -LiteralPath $iceInputModsPath -Algorithm SHA256).Hash
$iceRendererConfigs = @(@('ice-optimizer.cfg','nothirium.cfg') | ForEach-Object {
    $iceConfigPath = Join-Path (Join-Path $iceGame 'config') $_
    if (Test-Path -LiteralPath $iceConfigPath) {
        @{name=$_;sha256=(Get-FileHash -LiteralPath $iceConfigPath -Algorithm SHA256).Hash;
          content=[IO.File]::ReadAllText($iceConfigPath)}
    }
})
# Backends are experimental inputs, so preserve their exact prelaunch settings.
# They may intentionally differ between arms; do not treat a matching mod JAR
# alone as proof that two renderer configurations are the same.
$iceRendererConfigsPath = Join-Path $iceGame 'benchmark-input-renderer-configs.json'
ConvertTo-Json -InputObject $iceRendererConfigs -Depth 4 |
    Set-Content -LiteralPath $iceRendererConfigsPath -Encoding UTF8
$iceRendererConfigsHash = (Get-FileHash -LiteralPath $iceRendererConfigsPath -Algorithm SHA256).Hash
$iceProcess = Start-Process -FilePath $Java -ArgumentList ($iceQuoted -join ' ') -WorkingDirectory $iceGame -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $iceGame 'benchmark-stdout.log') -RedirectStandardError (Join-Path $iceGame 'benchmark-stderr.log')
$iceOptimizerFiles = @(Get-ChildItem -LiteralPath (Join-Path $iceGame 'mods') -Filter 'ice-rlcraft-optimizer-*.jar')
$iceOptimizerHashes = @($iceOptimizerFiles | ForEach-Object { @{name=$_.Name;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash} })
@{pid=$iceProcess.Id;arm=$Arm;startUtc=[DateTime]::UtcNow.ToString('o');warmSeconds=$WarmSeconds;sampleSeconds=$SampleSeconds;
    survival=$Survival.IsPresent;scenario=$Scenario;walk=$Walk.IsPresent;ablation=$Ablation.IsPresent;runtimeAblation=$RuntimeAblation.IsPresent;keyToggle=$KeyToggle.IsPresent;keyToggleStress=$KeyToggleStress.IsPresent;terrainAblation=$TerrainAblation.IsPresent;groundStart=$GroundStart.IsPresent;optimizer=$iceOptimizerHashes;
    fixtureSha256=(Get-FileHash -LiteralPath (Join-Path $iceGame 'mods/ice-live-benchmark.jar') -Algorithm SHA256).Hash;
    classDumpAgentSha256=$(if ($DumpRenderClasses -or $ProfileSections -or $DeterministicCombat) { (Get-FileHash -LiteralPath $iceAgent -Algorithm SHA256).Hash } else { $null });
    deterministicCombat=$DeterministicCombat.IsPresent;
    keyToggleRotation=$KeyToggleRotation.IsPresent;
    emptyUploadProbe=$EmptyUploadProbe.IsPresent;
    requireChunkReuse=$RequireChunkReuse.IsPresent;
    matchVanillaEntityDistance=$MatchVanillaEntityDistance.IsPresent;
    flightSample=$FlightSample.IsPresent;
    profileSections=$ProfileSections.IsPresent;coarseSections=$CoarseSections.IsPresent;stableCreative=$StableCreative.IsPresent;fixedCameraPath=$FixedCameraPath.IsPresent;modelAblation=$ModelAblation.IsPresent;captureTerrain=$CaptureTerrain.IsPresent;entityAblation=$EntityAblation.IsPresent;
    inputModsSha256=$iceInputModsHash;
    inputRendererConfigsSha256=$iceRendererConfigsHash;
    fixedFinalCapture=$FixedFinalCapture.IsPresent;
    requestedCameraYaw=$(if ($iceFixedCamera) { $CameraYaw } else { $null });
    requestedCameraPitch=$(if ($iceFixedCamera) { $CameraPitch } else { $null });
    initialWorldSha256=$iceInitialWorldHash;initialOptions=$iceOptionHashes} |
    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $iceGame 'benchmark-process.json') -Encoding UTF8
Write-Output "Isolated benchmark started: pid=$($iceProcess.Id), arm=$Arm"
