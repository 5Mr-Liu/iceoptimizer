param(
    [string]$JavaBin = 'C:/Program Files/Eclipse Adoptium/jdk-8.0.492.9-hotspot/bin',
    [string]$Lwjgl = 'D:/Program Files/Mcserver/rlcraftDregora/.minecraft/libraries/org/lwjgl/lwjgl/lwjgl/2.9.4-nightly-20150209/lwjgl-2.9.4-nightly-20150209.jar',
    [string]$Natives = 'D:/Program Files/Mcserver/rlcraftDregora/.minecraft/versions/RLCraft Dregora/natives-windows-x86_64',
    [string]$OutputDirectory = 'build/gl21-compat',
    [switch]$Gl43,
    [switch]$MultiTexture,
    [switch]$Churn
)
$ErrorActionPreference = 'Stop'
if (Get-CimInstance Win32_Process | Where-Object {
    $_.Name -match '^java(w)?\.exe$' -and $_.CommandLine -match 'net\.minecraft\.launchwrapper\.Launch'
}) { throw 'Stop the game before running GPU probes' }
Push-Location -LiteralPath (Split-Path $PSScriptRoot -Parent)
try {
    $iceOutput = (New-Item -ItemType Directory -Force -Path $OutputDirectory).FullName
    $iceClasses = (New-Item -ItemType Directory -Force -Path (Join-Path $iceOutput 'probe-classes')).FullName
    $iceCp = "build/classes/java/main;build/classes/java/early;$Lwjgl"
    & "$JavaBin/javac.exe" -proc:none -encoding UTF-8 -source 8 -target 8 -cp $iceCp -d $iceClasses `
        tools/NativeTerrainCompatibilityProbe.java tools/NativeTerrainPageChurnProbe.java *> (Join-Path $iceOutput 'probe-compile.log')
    if ($LASTEXITCODE -ne 0) { throw 'Probe compilation failed; build the optimizer first' }
    $iceLog = Join-Path $iceOutput $(if ($Churn) { 'churn21.log' } elseif ($Gl43) { 'gpu43.log' } else { 'gpu21.log' })
    $iceMain = if ($Churn) { 'dev.rlcraft.ice.optimizer.render.terrain.NativeTerrainPageChurnProbe' } else { 'dev.rlcraft.ice.optimizer.render.terrain.NativeTerrainCompatibilityProbe' }
    if ($Churn) {
        $iceArguments = @(
            ('"-Djava.library.path=' + $Natives + '"'),
            ('"-Dorg.lwjgl.librarypath=' + $Natives + '"'),
            '-cp', ('"' + $iceClasses + ';' + $iceCp + '"'), $iceMain
        )
        $iceProcess = Start-Process -FilePath "$JavaBin/java.exe" -ArgumentList $iceArguments `
            -WindowStyle Hidden -PassThru -RedirectStandardOutput $iceLog `
            -RedirectStandardError (Join-Path $iceOutput 'churn-stderr.log')
        $icePeakWorkingSet = 0L
        $icePeakPrivate = 0L
        while (-not $iceProcess.HasExited) {
            $iceProcess.Refresh()
            $icePeakWorkingSet = [Math]::Max($icePeakWorkingSet, $iceProcess.PeakWorkingSet64)
            $icePeakPrivate = [Math]::Max($icePeakPrivate, $iceProcess.PrivateMemorySize64)
            Start-Sleep -Milliseconds 20
        }
        $iceProcess.WaitForExit()
        $iceExit = $iceProcess.ExitCode
        Add-Content -LiteralPath $iceLog -Value "PROCESS peak_working_set_bytes=$icePeakWorkingSet sampled_peak_private_bytes=$icePeakPrivate sample_interval_ms=20"
    } else {
        & "$JavaBin/java.exe" "-Djava.library.path=$Natives" "-Dorg.lwjgl.librarypath=$Natives" `
            "-Dice.probe.gl43=$($Gl43.IsPresent.ToString().ToLowerInvariant())" `
            "-Dice.probe.multitexture=$($MultiTexture.IsPresent.ToString().ToLowerInvariant())" `
            -cp "$iceClasses;$iceCp" $iceMain *> $iceLog
        $iceExit = $LASTEXITCODE
    }
    Get-Content -LiteralPath $iceLog -Tail 5
    if ($iceExit -ne 0) { throw "GPU probe failed; see $iceLog" }
} finally { Pop-Location }
