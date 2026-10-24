[CmdletBinding()]
param([switch]$SkipParserBuild)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $projectRoot
try {
    & (Join-Path $PSScriptRoot 'New-LocalEnv.ps1') -FileName '.env.demo'
    $compose = @('compose','--env-file','.env.demo','-p','ticknova-demo','-f','deploy/compose.yaml','-f','deploy/compose.demo.yaml')
    docker @compose config --quiet
    if ($LASTEXITCODE -ne 0) { throw 'Compose configuration is invalid.' }
    if ($SkipParserBuild) { & (Join-Path $PSScriptRoot 'Assert-ParserImage.ps1'); docker @compose build api web } else { docker @compose build }
    if ($LASTEXITCODE -ne 0) { throw 'Local image build failed.' }
    docker @compose up --no-build --detach --wait
    if ($LASTEXITCODE -ne 0) { throw 'Local startup failed; inspect project service logs.' }
    Write-Output 'Ticknova is available at http://127.0.0.1:5173 (default port).'
    & (Join-Path $PSScriptRoot 'Seed-Demo.ps1')
    # Seeding signs in through the real first-login flow. Hand over a fresh runtime quota.
    docker @compose restart api
    if ($LASTEXITCODE -ne 0) { throw 'Demo API restart failed.' }
    docker @compose up -d --no-deps --wait api
    if ($LASTEXITCODE -ne 0) { throw 'Demo API did not become ready.' }
    Write-Output 'Accounts: admin / agent / requester / editor / reviewer. Passwords are in .env.demo (DEMO_<USER>_PASSWORD). The seed completes the real initial password change before populating data.'
} finally { Pop-Location }
