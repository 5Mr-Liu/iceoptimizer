param([switch]$Targeted, [string]$Candidate = 'nbt-complete-20261106')
$ErrorActionPreference = 'Stop'
$iceRoot = Split-Path $PSScriptRoot -Parent
$iceMods = 'D:/Program Files/Mcserver/rlcraftDregora/.minecraft/versions/RLCraft Dregora/mods'
$iceEvidence = Join-Path $iceRoot "build/$Candidate"
$env:JAVA_HOME = 'C:/Program Files/Eclipse Adoptium/jdk-8.0.492.9-hotspot'
$env:GRADLE_USER_HOME = Join-Path $iceRoot '.gradle-sandbox-home'
$iceMixin = (Get-ChildItem -LiteralPath $iceMods -Filter '*FermiumBooter*.jar' | Select-Object -First 1).FullName
$iceSrg = Join-Path $env:GRADLE_USER_HOME 'caches/forge_gradle/minecraft_user_repo/net/minecraftforge/forge/1.12.2-14.23.5.2860/forge-1.12.2-14.23.5.2860-srg.jar'
New-Item -ItemType Directory -Path $iceEvidence -Force | Out-Null
@'
allprojects {
    afterEvaluate {
        tasks.create(name: 'writeNbtProbeClasspath') {
            doLast {
                project.file(project.property('nbtProbeClasspath')).text = project.sourceSets.test.runtimeClasspath.asPath
            }
        }
    }
}
'@ | Set-Content -LiteralPath "$iceEvidence/probe-classpath.gradle" -Encoding ASCII
$iceTasks = if ($Targeted) {
    @('test', '--tests', '*Nbt*', '--tests', '*QualityTools*', '--tests', '*RecurrentCleanup*', '--tests', '*LycanitesEquipmentRead*', '--tests', '*Trinkets*', '--tests', '*CapabilityState*', 'writeNbtProbeClasspath')
} else { @('build', 'optimizerBundleZip', 'verifyArtifactStartup', 'writeNbtProbeClasspath') }
$iceLog = Join-Path $iceEvidence $(if ($Targeted) { 'targeted-tests.log' } else { 'build.log' })
Push-Location -LiteralPath $iceRoot
try {
    & ./gradlew.bat --offline '-Dnet.minecraftforge.gradle.test_certs=false' `
        "-PrenderLibJar=$iceMods/RenderLib-1.12.2-1.4.5.jar" `
        "-PotgJar=$iceMods/OpenTerrainGenerator-1.12.2-v9.7.jar" `
        "-PivToolkitJar=$iceMods/IvToolkit-1.3.3-1.12.jar" `
        "-PmixinApiJar=$iceMixin" `
        "-PqualityToolsJar=$iceMods/QualityTools-1.0.7_for_1.12.2.jar" `
        "-PrecurrentComplexJar=$iceMods/RecurrentComplex-1.4.8.6.jar" `
        "-PrltweakerJar=$iceMods/RLTweaker-1.12.2-0.7.5.jar" `
        "-PpotionCoreJar=$iceMods/PotionCore-1.9_for_1.12.2.jar" `
        "-PlycanitesJar=$iceMods/lycanitesmobs-1.12.2-2.0.8.10.jar" `
        "-PtrinketsJar=$iceMods/Trinkets and Baubles-0.32.5.jar" `
        "-PbountifulBaublesJar=$iceMods/Bountiful Baubles-1.12.2-0.1.8.jar" `
        "-PspartanWeaponryJar=$iceMods/SpartanWeaponry-1.12.2-1.6.1.jar" `
        "-PminecraftSrgJar=$iceSrg" `
        "-PnbtProbeClasspath=$iceEvidence/runtime-classpath.txt" `
        -I "$iceEvidence/probe-classpath.gradle" @iceTasks *> $iceLog
    $iceExit = $LASTEXITCODE
    Get-Content -LiteralPath $iceLog -Tail 35
    if ($iceExit -ne 0) { throw "NBT build failed: $iceExit; see $iceLog" }
} finally { Pop-Location }
