param([string]$JavaBin = 'C:/Program Files/Eclipse Adoptium/jdk-8.0.492.9-hotspot/bin',
    [string]$OutputDirectory = 'build/fps-target/live-fixture')
$ErrorActionPreference = 'Stop'
$iceRoot = Split-Path $PSScriptRoot -Parent
Push-Location -LiteralPath $iceRoot
try {
    $iceRuntime = (Get-Content 'build/performance-audit/entity-runtime-classpath.txt' -Raw).Trim()
    $iceOutput = $OutputDirectory
    New-Item -ItemType Directory -Force -Path "$iceOutput/classes" | Out-Null
    & "$JavaBin/javac.exe" -proc:none -encoding UTF-8 -source 8 -target 8 -cp $iceRuntime -d "$iceOutput/classes" tools/LiveFpsBenchmark.java tools/EmptyTerrainUploadProbe.java tools/FixedRenderCamera.java tools/LiveGameplayScenario.java tools/LiveCombatRandom.java tools/TerrainSceneCapture.java
    if ($LASTEXITCODE -ne 0) { throw 'Fixture compilation failed' }
    & "$JavaBin/jar.exe" cf "$iceOutput/mapped-fixture.jar" -C "$iceOutput/classes" .
    if ($LASTEXITCODE -ne 0) { throw 'Fixture packaging failed' }
    $iceRemapper = '.gradle-sandbox-home/caches/forge_gradle/maven_downloader/net/md-5/SpecialSource/1.8.3/SpecialSource-1.8.3-shaded.jar'
    & "$JavaBin/java.exe" -cp "$iceRemapper;$iceRuntime" net.md_5.specialsource.SpecialSource --live -i "$iceOutput/mapped-fixture.jar" -o "$iceOutput/ice-live-benchmark.jar" -m .gradle-sandbox-home/caches/minecraft/de/oceanlabs/mcp/mcp_stable/39/1.12.2/srgs/mcp-srg.srg *> "$iceOutput/remap.log"
    if ($LASTEXITCODE -ne 0) { Get-Content "$iceOutput/remap.log" -Tail 20; throw 'Fixture inheritance remap failed' }
    New-Item -ItemType Directory -Force -Path "$iceOutput/agent-classes" | Out-Null
    & "$JavaBin/javac.exe" -encoding UTF-8 -source 8 -target 8 -cp $iceRuntime -d "$iceOutput/agent-classes" tools/RenderClassDumpAgent.java tools/RenderSectionInstrumenter.java tools/RenderSectionClock.java tools/GameplayRandomInstrumenter.java
    if ($LASTEXITCODE -ne 0) { throw 'Class capture agent compilation failed' }
    "Premain-Class: dev.rlcraft.ice.benchmark.RenderClassDumpAgent`n" | Set-Content -LiteralPath "$iceOutput/agent-manifest.mf" -Encoding ASCII
    & "$JavaBin/jar.exe" cfm "$iceOutput/render-class-dump-agent.jar" "$iceOutput/agent-manifest.mf" -C "$iceOutput/agent-classes" .
    if ($LASTEXITCODE -ne 0) { throw 'Class capture agent packaging failed' }
    Write-Output 'Built test-only SRG benchmark fixture with inherited-member mappings'
} finally { Pop-Location }
