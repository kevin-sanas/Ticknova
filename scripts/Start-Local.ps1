[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $projectRoot
try {
    & (Join-Path $PSScriptRoot 'New-LocalEnv.ps1')
    docker compose --env-file .env -f deploy/compose.yaml config --quiet
    if ($LASTEXITCODE -ne 0) { throw 'Compose configuration is invalid.' }
    docker compose --env-file .env -f deploy/compose.yaml up --build --detach --wait
    if ($LASTEXITCODE -ne 0) { throw 'Local startup failed; inspect project service logs.' }
    Write-Output 'Ticknova is available at http://127.0.0.1:5173 (default port).'
    Write-Output 'Accounts: admin / agent / requester / editor. Passwords are in the local .env (DEMO_<USER>_PASSWORD when present, otherwise DEMO_INITIAL_PASSWORD). First login requires a change.'
} finally { Pop-Location }
