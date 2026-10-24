[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$BackupDirectory)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$backupRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot 'data/backups'))
$backup = [IO.Path]::GetFullPath($BackupDirectory)
if (-not $backup.StartsWith($backupRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Only project-local Ticknova backups can be restored here.' }
$dataRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot 'data'))
foreach ($path in @($dataRoot, $backupRoot, $backup, (Join-Path $backup 'uploads'))) {
    if ((Test-Path -LiteralPath $path) -and ((Get-Item -LiteralPath $path -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Restore source cannot contain symlinked directories.' }
}
$manifestPath = Join-Path $backup 'manifest.json'
$dump = Join-Path $backup 'database.dump'
$uploads = Join-Path $backup 'uploads'
if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf) -or -not (Test-Path -LiteralPath $dump -PathType Leaf) -or -not (Test-Path -LiteralPath $uploads -PathType Container)) { throw 'Backup is incomplete.' }
$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
if ($manifest.formatVersion -ne 1 -or $manifest.databaseSha256 -notmatch '^[0-9a-f]{64}$' -or $manifest.schemaVersion -notmatch '^[0-9]+$') { throw 'Unsupported or invalid backup manifest.' }
if ((Get-FileHash -LiteralPath $dump -Algorithm SHA256).Hash.ToLowerInvariant() -ne $manifest.databaseSha256) { throw 'Database dump hash differs.' }
$files = @($manifest.files)
$actualEntries = @(Get-ChildItem -LiteralPath $uploads -Force)
if ($actualEntries.Count -ne $files.Count -or @($actualEntries | Where-Object { $_.PSIsContainer -or ($_.Attributes -band [IO.FileAttributes]::ReparsePoint) }).Count -ne 0) { throw 'Upload contents differ from manifest.' }
$fileHashes = @{}
foreach ($entry in $files) {
    if ($entry.name -notmatch '^[0-9a-f-]{36}\.bin$' -or $entry.sha256 -notmatch '^[0-9a-f]{64}$' -or $fileHashes.ContainsKey($entry.name)) { throw 'Invalid upload manifest entry.' }
    $file = Join-Path $uploads $entry.name
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "Upload is missing: $($entry.name)" }
    if ((Get-Item -LiteralPath $file).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Symlinked upload found in backup.' }
    $hash = (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($hash -ne $entry.sha256) { throw "Upload hash differs: $($entry.name)" }
    $fileHashes[$entry.name] = $hash
}
foreach ($reference in @($manifest.references)) {
    if ($reference.key -notmatch '^[0-9a-f-]{36}\.bin$' -or $reference.sha256 -notmatch '^[0-9a-f]{64}$' -or -not $fileHashes.ContainsKey($reference.key) -or $fileHashes[$reference.key] -ne $reference.sha256) { throw 'A document references a missing or altered upload.' }
}
$projectName = 'ticknova-restore-' + [guid]::NewGuid().ToString('N').Substring(0,8)
$compose = @('compose','--env-file','.env','-f','deploy/compose.restore.yaml','-p',$projectName)
$previousUploads = [Environment]::GetEnvironmentVariable('TICKNOVA_RESTORE_UPLOADS','Process')
$env:TICKNOVA_RESTORE_UPLOADS = $uploads
Push-Location -LiteralPath $projectRoot
try {
    & docker @compose up -d --wait postgres
    if ($LASTEXITCODE -ne 0) { throw 'Isolated restore database did not start.' }
    $db = (& docker @compose ps -q postgres).Trim()
    if ($LASTEXITCODE -ne 0 -or -not $db) { throw 'Restore database container was not found.' }
    & docker cp $dump "${db}:/tmp/ticknova-restore.dump"
    if ($LASTEXITCODE -ne 0) { throw 'Cannot copy dump into restore database.' }
    & docker exec $db pg_restore --exit-on-error --no-owner --no-acl -U ticknova_restore -d ticknova_restore /tmp/ticknova-restore.dump
    if ($LASTEXITCODE -ne 0) { throw 'pg_restore failed.' }
    $schema = (& docker exec $db psql -U ticknova_restore -d ticknova_restore -At -c 'SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1').Trim()
    if ($LASTEXITCODE -ne 0 -or $schema -ne $manifest.schemaVersion) { throw 'Restored Flyway schema version differs.' }
    $restored = @(& docker exec $db psql -U ticknova_restore -d ticknova_restore -At -c 'SELECT storage_key || ''|'' || sha256 FROM document_version ORDER BY storage_key' | Where-Object { $_ })
    if ($LASTEXITCODE -ne 0) { throw 'Cannot read restored document references.' }
    $expected = @($manifest.references | ForEach-Object { "$($_.key)|$($_.sha256)" } | Sort-Object)
    if (@(Compare-Object -ReferenceObject $expected -DifferenceObject @($restored | Sort-Object)).Count -ne 0) { throw 'Restored document file references differ.' }
    & docker @compose up -d --wait api
    if ($LASTEXITCODE -ne 0) { throw 'Restored API readiness failed.' }
    $api = (& docker @compose ps -q api).Trim()
    foreach ($reference in @($manifest.references)) {
        $actual = (& docker exec $api sha256sum "/var/lib/ticknova/uploads/$($reference.key)")
        if ($LASTEXITCODE -ne 0 -or ($actual -split '\s+')[0].ToLowerInvariant() -ne $reference.sha256) { throw "Restored API cannot read upload: $($reference.key)" }
    }
    Write-Output "Restore verified in isolated project $projectName; schema $schema, $($manifest.references.Count) referenced uploads."
} finally {
    & docker @compose down --volumes --remove-orphans | Out-Null
    [Environment]::SetEnvironmentVariable('TICKNOVA_RESTORE_UPLOADS',$previousUploads,'Process')
    Pop-Location
}
