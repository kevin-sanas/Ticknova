[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $projectRoot
try {
    & (Join-Path $PSScriptRoot 'New-LocalEnv.ps1')
    docker volume create ticknova-maven-cache | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Cannot create the project dependency cache.' }
    docker compose --env-file .env -f deploy/compose.test.yaml run --rm api-test
    if ($LASTEXITCODE -ne 0) { throw 'Backend verification failed.' }
} finally { Pop-Location }
