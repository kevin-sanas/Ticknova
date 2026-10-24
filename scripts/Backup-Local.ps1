[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$backupRoot = Join-Path $projectRoot 'data/backups'
$backupRoot = [IO.Path]::GetFullPath($backupRoot)
$dataRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot 'data'))
foreach ($path in @($dataRoot, $backupRoot)) {
    if ((Test-Path -LiteralPath $path) -and ((Get-Item -LiteralPath $path -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Backup location cannot be a symlink.' }
}
$folder = 'ticknova-' + [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ') + '-' + [guid]::NewGuid().ToString('N').Substring(0,8)
$backup = [IO.Path]::GetFullPath((Join-Path $backupRoot $folder))
if (-not $backup.StartsWith($backupRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe backup path.' }
if (-not (Test-Path -LiteralPath (Join-Path $projectRoot '.env'))) { throw 'Project .env is missing; start Ticknova first.' }
New-Item -ItemType Directory -Path (Join-Path $backup 'uploads') -Force | Out-Null
$compose = @('compose','--env-file','.env','-f','deploy/compose.yaml')
$postgresStarted = $false
$apiStopped = $false
$dumpPath = '/tmp/ticknova-backup-' + [guid]::NewGuid().ToString('N') + '.dump'
$postgresId = ''
$result = ''
Push-Location -LiteralPath $projectRoot
try {
    $postgresId = (& docker @compose ps -a -q postgres).Trim()
    $apiId = (& docker @compose ps -a -q api).Trim()
    if ($LASTEXITCODE -ne 0 -or -not $postgresId -or -not $apiId) { throw 'Ticknova postgres and api containers must already exist.' }
    $postgresRunning = (& docker inspect -f '{{.State.Running}}' $postgresId).Trim() -eq 'true'
    $apiRunning = (& docker inspect -f '{{.State.Running}}' $apiId).Trim() -eq 'true'
    if (-not $postgresRunning) {
        & docker @compose up -d --wait postgres
        if ($LASTEXITCODE -ne 0) { throw 'Cannot start Ticknova postgres for backup.' }
        $postgresStarted = $true
    }
    if ($apiRunning) {
        & docker @compose stop -t 30 api
        if ($LASTEXITCODE -ne 0) { throw 'Cannot quiesce Ticknova api.' }
        $apiStopped = $true
    }
    & docker exec $postgresId pg_dump -U ticknova -d ticknova -Fc -f $dumpPath
    if ($LASTEXITCODE -ne 0) { throw 'pg_dump failed.' }
    & docker cp "${postgresId}:${dumpPath}" (Join-Path $backup 'database.dump')
    if ($LASTEXITCODE -ne 0) { throw 'Cannot copy database dump.' }
    & docker cp "${apiId}:/var/lib/ticknova/uploads/." (Join-Path $backup 'uploads')
    if ($LASTEXITCODE -ne 0) { throw 'Cannot copy uploads.' }
    $rows = @(& docker exec $postgresId psql -U ticknova -d ticknova -At -c 'SELECT storage_key || ''|'' || sha256 FROM document_version ORDER BY storage_key')
    if ($LASTEXITCODE -ne 0) { throw 'Cannot read document file references.' }
    $references = @($rows | Where-Object { $_ } | ForEach-Object {
        $parts = $_.Split('|')
        if ($parts.Count -ne 2 -or $parts[0] -notmatch '^[0-9a-f-]{36}\.bin$' -or $parts[1] -notmatch '^[0-9a-f]{64}$') { throw 'Invalid document file reference in database.' }
        $file = Join-Path (Join-Path $backup 'uploads') $parts[0]
        if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "Referenced upload is missing: $($parts[0])" }
        if ((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant() -ne $parts[1]) { throw "Referenced upload hash differs: $($parts[0])" }
        @{ key = $parts[0]; sha256 = $parts[1] }
    })
    $files = @(Get-ChildItem -LiteralPath (Join-Path $backup 'uploads') -Force | ForEach-Object {
        if (-not $_.PSIsContainer -and $_.Name -match '^[0-9a-f-]{36}\.bin$' -and -not ($_.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            @{ name = $_.Name; sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() }
        } else { throw 'Unexpected upload entry in backup.' }
    })
    $schema = (& docker exec $postgresId psql -U ticknova -d ticknova -At -c 'SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1').Trim()
    if ($LASTEXITCODE -ne 0 -or -not $schema) { throw 'Cannot read Flyway schema version.' }
    $manifest = @{
        formatVersion = 1
        createdAtUtc = [DateTime]::UtcNow.ToString('o')
        schemaVersion = $schema
        databaseSha256 = (Get-FileHash -LiteralPath (Join-Path $backup 'database.dump') -Algorithm SHA256).Hash.ToLowerInvariant()
        files = $files
        references = $references
    }
    [IO.File]::WriteAllText((Join-Path $backup 'manifest.json'),($manifest | ConvertTo-Json -Depth 6),[Text.UTF8Encoding]::new($false))
    $result = "Backup created: $backup"
} catch {
    if ($backup.StartsWith($backupRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -and (Test-Path -LiteralPath $backup)) {
        foreach ($path in @($dataRoot, $backupRoot, $backup)) {
            if ((Get-Item -LiteralPath $path -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Backup cleanup stopped at a symlinked path.' }
        }
        Remove-Item -LiteralPath $backup -Recurse -Force
    }
    throw
} finally {
    $cleanupError = ''
    if ($postgresId) { & docker exec $postgresId rm -f $dumpPath 2>$null | Out-Null }
    if ($apiStopped) {
        & docker @compose up -d --no-deps --wait api | Out-Null
        if ($LASTEXITCODE -ne 0) { $cleanupError = 'Backup finished, but Ticknova api could not be restarted.' }
    }
    if ($postgresStarted -and -not $apiStopped) {
        & docker @compose stop postgres | Out-Null
        if ($LASTEXITCODE -ne 0) { $cleanupError = 'Backup finished, but Ticknova postgres could not be stopped.' }
    }
    Pop-Location
    if ($cleanupError) { throw $cleanupError }
}
Write-Output $result
