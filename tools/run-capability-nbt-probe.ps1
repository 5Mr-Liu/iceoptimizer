param(
    [string]$Output = 'build/nbt-20261106/capability-probe',
    [string]$ClasspathFile = 'build/nbt-20261106/runtime-classpath.txt',
    [string]$JavaBin = 'C:/Program Files/Eclipse Adoptium/jdk-8.0.492.9-hotspot/bin',
    [string]$NormalAsmJar = 'D:/Program Files/Mcserver/rlcraftDregora/.minecraft/versions/RLCraft Dregora/mods/fermiumasm-5.28.jar'
)
$ErrorActionPreference = 'Stop'
$iceRoot = Split-Path $PSScriptRoot -Parent
Push-Location -LiteralPath $iceRoot
try {
    $iceClasspath = (Get-Content -LiteralPath $ClasspathFile -Raw).Trim() + ';' + $NormalAsmJar
    $iceOutput = [System.IO.Path]::GetFullPath((Join-Path $iceRoot $Output))
    $iceClasses = Join-Path $iceOutput 'classes'
    New-Item -ItemType Directory -Path $iceClasses -Force | Out-Null
    & "$JavaBin/javac.exe" -encoding UTF-8 -source 8 -target 8 -cp $iceClasspath -d $iceClasses tools/CapabilityNbtProbe.java
    if ($LASTEXITCODE -ne 0) { throw 'Capability NBT probe compilation failed' }
    "Premain-Class: dev.rlcraft.ice.hooks.CapabilityNbtProbe`r`n" | Set-Content -LiteralPath "$iceOutput/agent.mf" -Encoding ASCII
    & "$JavaBin/jar.exe" cfm "$iceOutput/agent.jar" "$iceOutput/agent.mf" -C $iceClasses .
    if ($LASTEXITCODE -ne 0) { throw 'Capability NBT agent packaging failed' }
    foreach ($iceMap in @('normal', 'vanilla')) {
        for ($iceRun = 1; $iceRun -le 2; $iceRun++) {
            $iceLog = "$iceOutput/$iceMap-$iceRun.log"
            & "$JavaBin/java.exe" -Xms256m -Xmx256m "-javaagent:$iceOutput/agent.jar=$iceMap" -cp "$iceClasses;$iceClasspath" dev.rlcraft.ice.hooks.CapabilityNbtProbe *> $iceLog
            if ($LASTEXITCODE -ne 0) { Get-Content -LiteralPath $iceLog -Tail 30; throw 'Capability NBT probe failed' }
            Get-Content -LiteralPath $iceLog
        }
    }
} finally { Pop-Location }
