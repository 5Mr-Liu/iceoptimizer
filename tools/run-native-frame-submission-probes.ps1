param(
    [string]$OutputDirectory = 'build/frame-submission-20261105/final-probes',
    [string]$BaselineSources = 'build/frame-submission-20261105/baseline-source',
    [string]$JavaBin = 'C:/Program Files/Eclipse Adoptium/jdk-8.0.492.9-hotspot/bin',
    [string]$Lwjgl = 'D:/Program Files/Mcserver/rlcraftDregora/.minecraft/libraries/org/lwjgl/lwjgl/lwjgl/2.9.4-nightly-20150209/lwjgl-2.9.4-nightly-20150209.jar',
    [string]$Natives = 'D:/Program Files/Mcserver/rlcraftDregora/.minecraft/versions/RLCraft Dregora/natives-windows-x86_64'
)
$ErrorActionPreference = 'Stop'
if (Get-CimInstance Win32_Process | Where-Object {
    $_.Name -match '^java(w)?\.exe$' -and $_.CommandLine -match 'net\.minecraft\.launchwrapper\.Launch'
}) { throw 'Stop the game before running GPU probes' }
Push-Location -LiteralPath (Split-Path $PSScriptRoot -Parent)
try {
    $iceOutput = (New-Item -ItemType Directory -Force -Path $OutputDirectory).FullName
    $iceMapped = '.gradle-sandbox-home/caches/forge_gradle/minecraft_user_repo/net/minecraftforge/forge/1.12.2-14.23.5.2860_mapped_stable_39-1.12/forge-1.12.2-14.23.5.2860_mapped_stable_39-1.12.jar'
    $iceCp = "build/classes/java/main;build/classes/java/early;$Lwjgl;$iceMapped"
    $iceSources = 'src/main/java/dev/rlcraft/ice/optimizer/render/terrain'
    foreach ($iceVariant in @('baseline','candidate')) {
        $iceClasses = (New-Item -ItemType Directory -Force -Path "$iceOutput/$iceVariant-classes").FullName
        $iceFiles = @('tools/NativeTerrainPreparationBenchmark.java','tools/NativeTerrainCommandUploadProbe.java')
        if ($iceVariant -eq 'baseline') {
            $iceFiles += @("$BaselineSources/NativeTerrainFaceIndex.java", "$BaselineSources/NativeTerrainGl43.java")
        } else {
            $iceFiles += @("$iceSources/NativeTerrainFaceIndex.java", "$iceSources/NativeTerrainSequentialIndices.java",
                "$iceSources/NativeTerrainGl43.java", "$iceSources/NativeTerrainCommandData.java")
        }
        & "$JavaBin/javac.exe" -proc:none -encoding UTF-8 -source 8 -target 8 -cp $iceCp -d $iceClasses @iceFiles *> "$iceOutput/compile-$iceVariant.log"
        if ($LASTEXITCODE -ne 0) { throw "Compilation failed: $iceVariant" }
    }
    $iceRound = 0
    foreach ($iceVariant in @('baseline','candidate','candidate','baseline')) {
        $iceRound++
        $iceRunCp = "$iceOutput/$iceVariant-classes;$iceCp"
        & "$JavaBin/java.exe" -Xms256m -Xmx256m -cp $iceRunCp dev.rlcraft.ice.optimizer.render.terrain.NativeTerrainPreparationBenchmark *> "$iceOutput/cpu-$iceRound-$iceVariant.log"
        if ($LASTEXITCODE -ne 0) { throw "CPU probe failed: $iceVariant" }
        & "$JavaBin/java.exe" "-Djava.library.path=$Natives" "-Dorg.lwjgl.librarypath=$Natives" -cp $iceRunCp dev.rlcraft.ice.optimizer.render.terrain.NativeTerrainCommandUploadProbe *> "$iceOutput/commands-$iceRound-$iceVariant.log"
        if ($LASTEXITCODE -ne 0) { throw "GPU probe failed: $iceVariant" }
        Get-Content "$iceOutput/cpu-$iceRound-$iceVariant.log"
        Get-Content "$iceOutput/commands-$iceRound-$iceVariant.log" -Tail 4
    }
} finally { Pop-Location }
