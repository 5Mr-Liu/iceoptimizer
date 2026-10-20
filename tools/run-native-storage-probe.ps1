param(
    [string]$OutputDirectory = 'build/fps-target/120-hotfix10-storage-probe',
    [string]$OldJar = 'build/fps-target/120-hotfix9-release/ice-rlcraft-optimizer-1.20.jar',
    [string]$Java = 'C:/Program Files/Eclipse Adoptium/jdk-8.0.492.9-hotspot/bin/java.exe'
)
$ErrorActionPreference = 'Stop'
$iceRoot = Split-Path $PSScriptRoot -Parent
$iceJava = 'C:/Program Files/Eclipse Adoptium/jdk-8.0.492.9-hotspot/bin'
$iceGame = 'D:/Program Files/Mcserver/rlcraftDregora/.minecraft/versions/RLCraft Dregora'
$iceLwjgl = 'D:/Program Files/Mcserver/rlcraftDregora/.minecraft/libraries/org/lwjgl/lwjgl/lwjgl/2.9.4-nightly-20150209/lwjgl-2.9.4-nightly-20150209.jar'
$iceNatives = Join-Path $iceGame 'natives-windows-x86_64'
if (Get-CimInstance Win32_Process | Where-Object {
    $_.Name -match '^java(w)?\.exe$' -and $_.CommandLine -match 'net\.minecraft\.launchwrapper\.Launch'
}) { throw 'Stop the game before running GPU probes' }
Push-Location -LiteralPath $iceRoot
try {
    $iceOutput = (New-Item -ItemType Directory -Force -Path $OutputDirectory).FullName
    $iceClasses = (New-Item -ItemType Directory -Force -Path (Join-Path $iceOutput 'classes')).FullName
    & "$iceJava/javac.exe" -proc:none -encoding UTF-8 -source 8 -target 8 -cp $iceLwjgl -d $iceClasses `
        'src/main/java/dev/rlcraft/ice/optimizer/render/terrain/NativeTerrainStorage.java' `
        'tools/NativeTerrainStorageProbe.java' *> (Join-Path $iceOutput 'compile.log')
    if ($LASTEXITCODE -ne 0) { throw 'Probe compilation failed' }
    $iceJvm = @("-Dorg.lwjgl.librarypath=$iceNatives", "-Djava.library.path=$iceNatives")
    & $Java @iceJvm '-Dice.probe.oldStorage=true' -cp "$OldJar;$iceClasses;$iceLwjgl" `
        dev.rlcraft.ice.optimizer.render.terrain.NativeTerrainStorageProbe *> (Join-Path $iceOutput 'old.log')
    if ($LASTEXITCODE -ne 0) { throw 'Baseline reproduction failed' }
    & $Java @iceJvm -cp "$iceClasses;$iceLwjgl" `
        dev.rlcraft.ice.optimizer.render.terrain.NativeTerrainStorageProbe *> (Join-Path $iceOutput 'fixed.log')
    if ($LASTEXITCODE -ne 0) { throw 'Fixed storage probe failed' }
    Get-Content -LiteralPath (Join-Path $iceOutput 'old.log')
    Get-Content -LiteralPath (Join-Path $iceOutput 'fixed.log')
} finally { Pop-Location }
