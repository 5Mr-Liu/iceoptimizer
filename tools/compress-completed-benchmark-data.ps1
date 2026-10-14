param([string[]]$Snapshots = @('live-129-combat-original-v7-1','live-129-combat-build15-v7-1'),
      [string]$FilePattern = '*.BO4')
$ErrorActionPreference='Stop'
$iceRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../build/fps-target'))
$icePrefix=$iceRoot+[IO.Path]::DirectorySeparatorChar
if(Get-CimInstance Win32_Process | Where-Object {$_.Name -in @('java.exe','javaw.exe')}){throw 'Compress completed data only with Java stopped'}
foreach($iceName in $Snapshots) {
    $iceSnapshot=[IO.Path]::GetFullPath((Join-Path $iceRoot $iceName))
    if(-not $iceSnapshot.StartsWith($icePrefix,[StringComparison]::OrdinalIgnoreCase)){throw 'Snapshot escapes workspace'}
    if(-not (Test-Path -LiteralPath (Join-Path $iceSnapshot 'ICE_ISOLATED_BENCHMARK.marker')) -or
       -not (Test-Path -LiteralPath (Join-Path $iceSnapshot 'benchmark-process.json'))){throw 'Not a completed isolated snapshot'}
    $iceFolder=[IO.Path]::GetFullPath((Join-Path $iceSnapshot 'mods/OpenTerrainGenerator'))
    if(-not $iceFolder.StartsWith($iceSnapshot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Data target escapes snapshot'}
    $iceSample=@(Get-ChildItem -LiteralPath $iceFolder -Recurse -File -Filter $FilePattern | Sort-Object Length -Descending | Select-Object -First 4)
    $iceBefore=@($iceSample | ForEach-Object {(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash})
    $iceLog=Join-Path $iceRoot ($iceName+'-ntfs-compression.txt')
    & compact.exe /C /I /Q "/S:$iceFolder" $FilePattern *> $iceLog
    if($LASTEXITCODE -ne 0){throw "Compression failed: $iceName"}
    $iceAfter=@($iceSample | ForEach-Object {(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash})
    if(Compare-Object $iceBefore $iceAfter){throw "Content verification failed: $iceName"}
    Write-Output "Compressed completed data without changing file contents: $iceName"
    Get-Content -LiteralPath $iceLog -Tail 5
}
Get-PSDrive -Name D | Select-Object Free
