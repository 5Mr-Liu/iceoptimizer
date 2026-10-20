param(
    [string]$Java = 'C:/Program Files/Eclipse Adoptium/jdk-8.0.492.9-hotspot/bin/java.exe',
    [string]$OutputDirectory = 'build/fps-target/native-terrain-draw-probe',
    [switch]$OldUpload
)
$ErrorActionPreference = 'Stop'
$iceRoot = Split-Path $PSScriptRoot -Parent
$iceJavaBin = 'C:/Program Files/Eclipse Adoptium/jdk-8.0.492.9-hotspot/bin'
$iceLwjgl = 'D:/Program Files/Mcserver/rlcraftDregora/.minecraft/libraries/org/lwjgl/lwjgl/lwjgl/2.9.4-nightly-20150209/lwjgl-2.9.4-nightly-20150209.jar'
$iceNatives = 'D:/Program Files/Mcserver/rlcraftDregora/.minecraft/versions/RLCraft Dregora/natives-windows-x86_64'
if (Get-CimInstance Win32_Process | Where-Object {
    $_.Name -match '^java(w)?\.exe$' -and $_.CommandLine -match 'net\.minecraft\.launchwrapper\.Launch'
}) { throw 'Stop the game before running GPU probes' }
Push-Location -LiteralPath $iceRoot
try {
    $iceOutput = (New-Item -ItemType Directory -Force -Path $OutputDirectory).FullName
    $iceClasses = (New-Item -ItemType Directory -Force -Path (Join-Path $iceOutput 'classes')).FullName
    & "$iceJavaBin/javac.exe" -proc:none -encoding UTF-8 -source 8 -target 8 -cp $iceLwjgl -d $iceClasses `
        'src/main/java/dev/rlcraft/ice/optimizer/render/terrain/NativeTerrainShader.java' `
        'src/main/java/dev/rlcraft/ice/optimizer/render/terrain/NativeTerrainStorage.java' `
        'tools/NativeTerrainStorageProbe.java' 'tools/NativeTerrainShaderProbe.java' *> (Join-Path $iceOutput 'compile.log')
    if ($LASTEXITCODE -ne 0) { throw 'Draw probe compilation failed' }
    $iceUpload = if ($OldUpload) { 'orphan' } else { 'copy' }
    & $Java "-Djava.library.path=$iceNatives" "-Dorg.lwjgl.librarypath=$iceNatives" `
        '-Dice.probe.indexedQuads=true' '-Dice.probe.shaderReference=true' "-Dice.probe.upload=$iceUpload" `
        -cp "$iceClasses;$iceLwjgl" dev.rlcraft.ice.benchmark.NativeTerrainShaderProbe *> (Join-Path $iceOutput 'draw.log')
    $iceExit = $LASTEXITCODE
    Get-Content -LiteralPath (Join-Path $iceOutput 'draw.log') -Tail 8
    if ($iceExit -ne 0) { throw "Draw probe failed ($iceUpload); see draw.log. OldUpload is a negative control, not a required passing run." }
} finally { Pop-Location }
