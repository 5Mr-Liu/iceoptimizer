param(
    [string]$JavaBin = 'C:/Program Files/Eclipse Adoptium/jdk-8.0.492.9-hotspot/bin',
    [string]$OutputDirectory = 'build/fps-target/129-nothirium-fog-compat-v1'
)
$ErrorActionPreference = 'Stop'
$iceRuntime = (Get-Content -LiteralPath 'build/performance-audit/entity-runtime-classpath.txt' -Raw).Trim()
$iceRenderLib = Get-ChildItem -LiteralPath 'D:/Program Files/Mcserver/rlcraftDregora/.minecraft/versions/RLCraft Dregora/mods' -Filter '*RenderLib*.jar' | Select-Object -First 1 -ExpandProperty FullName
if (Test-Path -LiteralPath $OutputDirectory) { throw 'Use a new output directory to preserve prior experiments' }
$iceClasses = New-Item -ItemType Directory -Path (Join-Path $OutputDirectory 'classes') -Force
& "$JavaBin/javac.exe" -proc:none -encoding UTF-8 -source 8 -target 8 -cp "$iceRuntime;$iceRenderLib" -d $iceClasses.FullName tools/NothiriumFogShaderRewrite.java tools/NothiriumFogCompat.java tools/NothiriumFogCompatTransformer.java tools/NothiriumFogCompatPlugin.java
if ($LASTEXITCODE -ne 0) { throw 'Compatibility compilation failed' }
$iceManifest = Join-Path $OutputDirectory 'manifest.mf'
"FMLCorePlugin: dev.rlcraft.ice.benchmark.compat.NothiriumFogCompatPlugin`n" | Set-Content -LiteralPath $iceManifest -Encoding ASCII
& "$JavaBin/jar.exe" cfm (Join-Path $OutputDirectory 'ice-nothirium-fog-experiment.jar') $iceManifest -C $iceClasses.FullName .
if ($LASTEXITCODE -ne 0) { throw 'Compatibility packaging failed' }
Get-FileHash -LiteralPath (Join-Path $OutputDirectory 'ice-nothirium-fog-experiment.jar')
