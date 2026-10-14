$ErrorActionPreference = 'Stop'
$iceRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../build/fps-target'))
$icePrefix = $iceRoot + [IO.Path]::DirectorySeparatorChar
$iceCanonical = Join-Path $iceRoot 'live-129-survival-build18-v9-1/mods'
$iceLog = Join-Path $iceRoot 'completed-mods-deduplication.jsonl'
if (Get-CimInstance Win32_Process | Where-Object { $_.Name -in @('java.exe','javaw.exe') }) { throw 'Java must be stopped for snapshot deduplication' }
$iceIndex = @{}
Get-ChildItem -LiteralPath $iceCanonical -File -Filter '*.jar' | ForEach-Object {
    $iceIndex[$_.Name] = @{ Path=$_.FullName; Length=$_.Length; Hash=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash }
}
$iceNames = @('live-129-combat-original-v9-1','live-129-combat-build18-v9-1',
 'live-129-combat-cold-off-v9-1','live-129-survival-original-v9-1','live-129-survival-build17-v9-1',
 'live-129-terrain-detail-build17-1','live-129-combat-original-v8-1','live-129-combat-original-v7-1',
 'live-129-combat-build15-v7-1','live-129-combat-build14-v6-1','live-129-survival-build14-v6-1',
 'live-129-renderer-cold-off-survival-v7-1')
$iceSaved = 0L
foreach ($iceName in $iceNames) {
    $iceSnapshot = [IO.Path]::GetFullPath((Join-Path $iceRoot $iceName))
    if (-not $iceSnapshot.StartsWith($icePrefix,[StringComparison]::OrdinalIgnoreCase)) { throw 'Snapshot escapes workspace' }
    if (-not (Test-Path -LiteralPath (Join-Path $iceSnapshot 'ICE_ISOLATED_BENCHMARK.marker'))) { throw 'Missing isolated snapshot marker' }
    foreach ($iceFile in (Get-ChildItem -LiteralPath (Join-Path $iceSnapshot 'mods') -File -Filter '*.jar')) {
        $iceMatch = $iceIndex[$iceFile.Name]
        if (-not $iceMatch -or $iceFile.Length -ne $iceMatch.Length) { continue }
        $iceHash = (Get-FileHash -LiteralPath $iceFile.FullName -Algorithm SHA256).Hash
        if ($iceHash -ne $iceMatch.Hash) { continue }
        $iceLink = $iceFile.FullName + '.ice-dedup-link'
        $iceBackup = $iceFile.FullName + '.ice-dedup-backup'
        if ((Test-Path -LiteralPath $iceLink) -or (Test-Path -LiteralPath $iceBackup)) { throw 'Interrupted operation requires inspection' }
        foreach ($icePath in @($iceFile.FullName,$iceLink,$iceBackup,$iceMatch.Path)) {
            if (-not ([IO.Path]::GetFullPath($icePath)).StartsWith($icePrefix,[StringComparison]::OrdinalIgnoreCase)) { throw 'File escapes snapshots' }
        }
        @{ snapshot=$iceName;file=$iceFile.Name;sha256=$iceHash;canonical=$iceMatch.Path;bytes=$iceFile.Length } | ConvertTo-Json -Compress | Add-Content -LiteralPath $iceLog -Encoding UTF8
        New-Item -ItemType HardLink -Path $iceLink -Target $iceMatch.Path | Out-Null
        Move-Item -LiteralPath $iceFile.FullName -Destination $iceBackup
        try { Move-Item -LiteralPath $iceLink -Destination $iceFile.FullName }
        catch { Move-Item -LiteralPath $iceBackup -Destination $iceFile.FullName; throw }
        Remove-Item -LiteralPath $iceBackup
        $iceSaved += $iceFile.Length
    }
    Write-Output "Completed snapshot: $iceName; matched bytes: $iceSaved"
}
Write-Output 'Identical JAR contents now share NTFS storage. Replace a JAR before editing; never write it in place.'
