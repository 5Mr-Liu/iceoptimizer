param([string]$Log = 'build/fps-target/native-build.log', [switch]$PackageOnly)
$ErrorActionPreference = 'Stop'
$iceRoot = Split-Path $PSScriptRoot -Parent
if (Get-CimInstance Win32_Process | Where-Object {
    $_.Name -match '^java(w)?\.exe$' -and $_.CommandLine -match 'net\.minecraft\.launchwrapper\.Launch'
}) { throw 'Stop the game before compiling a candidate' }
$env:JAVA_HOME = 'C:/Program Files/Eclipse Adoptium/jdk-8.0.492.9-hotspot'
$env:GRADLE_USER_HOME = Join-Path $iceRoot '.gradle-sandbox-home'
$iceMods = 'D:/Program Files/Mcserver/rlcraftDregora/.minecraft/versions/RLCraft Dregora/mods'
$iceMixin = (Get-ChildItem -LiteralPath $iceMods -Filter '*FermiumBooter*.jar' | Select-Object -First 1).FullName
Push-Location -LiteralPath $iceRoot
try {
    # PackageOnly is for user-requested manual testing: compile/remap/package
    # the optimizer without running tests or starting a Forge/game process.
    $iceTasks = @(if ($PackageOnly) { 'optimizerBundleZip' } else { 'build'; 'optimizerBundleZip'; 'verifyArtifactStartup' })
    & ./gradlew.bat --offline '-Dnet.minecraftforge.gradle.test_certs=false' `
        "-PrenderLibJar=$iceMods/RenderLib-1.12.2-1.4.5.jar" `
        "-PotgJar=$iceMods/OpenTerrainGenerator-1.12.2-v9.7.jar" `
        "-PivToolkitJar=$iceMods/IvToolkit-1.3.3-1.12.jar" `
        "-PmixinApiJar=$iceMixin" `
        '-PnothiriumJar=build/fps-target/renderer-method-research-20261012/Nothirium-1.12.2-0.4.9-beta.jar' `
        @iceTasks *> $Log
    $iceExit = $LASTEXITCODE
    Get-Content -LiteralPath $Log -Tail 28
    if ($iceExit -ne 0) { throw "Build failed: $iceExit; see $Log" }
} finally { Pop-Location }
