param(
    [string]$JavaBin = 'C:/Program Files/Eclipse Adoptium/jdk-8.0.492.9-hotspot/bin',
    [string]$BaselineClasses = '.refactor-backups/1.0.27-algorithms/baseline/classes',
    [string]$NativeDirectory = '.refactor-backups/1.0.11-fbp/natives',
    [string]$OutputDirectory = 'build/algorithm-validation/final',
    [int]$Repeats = 2
)
$ErrorActionPreference = 'Stop'
$iceRoot = Split-Path $PSScriptRoot -Parent
Push-Location -LiteralPath $iceRoot
try {
    if (-not (Test-Path -LiteralPath $BaselineClasses)) { throw 'Frozen 1.0.26 classes are required; never substitute the new build for the baseline.' }
    $iceRuntime = (Get-Content -LiteralPath 'build/performance-audit/entity-runtime-classpath.txt' -Raw).Trim()
    $iceNatives = (Resolve-Path -LiteralPath $NativeDirectory).Path
    $iceOutput = New-Item -ItemType Directory -Force -Path $OutputDirectory
    $iceProbes = (New-Item -ItemType Directory -Force -Path (Join-Path $iceOutput 'probes')).FullName
    $iceSources = @('tools/TerrainSubmissionNativeProbe.java', 'tools/TerrainGpuSubmissionNativeProbe.java',
        'tools/TerrainAlgorithmNativeProbe.java', 'tools/HudAlgorithmNativeProbe.java')
    & "$JavaBin/javac.exe" -proc:none -encoding UTF-8 -source 8 -target 8 -cp $iceRuntime -d $iceProbes @iceSources *> (Join-Path $iceOutput 'compile.log')
    if ($LASTEXITCODE -ne 0) { throw 'Native comparison compilation failed' }
    foreach ($iceRepeat in 1..$Repeats) {
        $iceArms = if ($iceRepeat % 2 -eq 1) { @('current', 'new') } else { @('new', 'current') }
        foreach ($iceArm in $iceArms) {
            $iceClasses = if ($iceArm -eq 'current') { $BaselineClasses } else { 'build/classes/java/main' }
            $iceJvm = @("-Dorg.lwjgl.librarypath=$iceNatives", "-Djava.library.path=$iceNatives")
            $iceTerrainFlags = @('-Dice.cubes=true', '-Dice.visibility=true')
            if ($iceArm -eq 'new') { $iceTerrainFlags += '-Dice.requireExact=true' }
            Write-Output "terrain repeat=$iceRepeat arm=$iceArm"
            & "$JavaBin/java.exe" @iceJvm @iceTerrainFlags -cp "$iceClasses;$iceProbes;$iceRuntime" dev.rlcraft.ice.optimizer.render.terrain.TerrainAlgorithmNativeProbe *> (Join-Path $iceOutput "$iceRepeat-$iceArm-terrain.log")
            if ($LASTEXITCODE -ne 0) { throw "Terrain comparison failed: $iceArm" }
            foreach ($iceGlyphs in @(1,4,32,128)) {
                $iceHudFlags = @('-Dice.hud.cached=true', '-Dice.hud.glyphsOnly=true', "-Dice.hud.primitives=$iceGlyphs")
                if ($iceArm -eq 'current') { $iceHudFlags += '-Dice.baseline=true' }
                Write-Output "hud repeat=$iceRepeat arm=$iceArm glyphs=$iceGlyphs"
                & "$JavaBin/java.exe" @iceJvm @iceHudFlags -cp "$iceClasses;$iceProbes;$iceRuntime" dev.rlcraft.ice.optimizer.render.hud.HudAlgorithmNativeProbe *> (Join-Path $iceOutput "$iceRepeat-$iceArm-hud-$iceGlyphs.log")
                if ($LASTEXITCODE -ne 0) { throw "HUD comparison failed: $iceArm / $iceGlyphs" }
            }
        }
    }
    Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $BaselineClasses 'dev/rlcraft/ice/optimizer/render/terrain/LwjglTerrainArena.class'),
        (Join-Path $BaselineClasses 'dev/rlcraft/ice/optimizer/render/hud/LwjglHudRenderer.class'),
        'build/classes/java/main/dev/rlcraft/ice/optimizer/render/terrain/LwjglTerrainArena.class',
        'build/classes/java/main/dev/rlcraft/ice/optimizer/render/hud/LwjglHudRenderer.class' |
        Format-List | Out-File -Encoding utf8 (Join-Path $iceOutput 'class-hashes.txt')
    Write-Output "Comparisons complete: $($iceOutput.FullName)"
} finally { Pop-Location }
