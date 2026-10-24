[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$files = @('app.py','worker.py','limits.py','uv.lock','pyproject.toml')
$imageHashes = @(& docker run --rm --entrypoint sha256sum ticknova-docling:0.1.0 @($files | ForEach-Object { "/app/$_" }))
if ($LASTEXITCODE -ne 0 -or $imageHashes.Count -ne $files.Count) { throw 'Cannot inspect the local parser image.' }
foreach ($file in $files) {
    $expected = (Get-FileHash -LiteralPath (Join-Path $projectRoot "docling-service/$file") -Algorithm SHA256).Hash.ToLowerInvariant()
    if (-not ($imageHashes -contains "$expected  /app/$file")) { throw "Local parser image is stale: $file. Rebuild it before verification." }
}
Write-Output 'Local parser image matches all five source and dependency files.'
