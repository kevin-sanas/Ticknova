[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $projectRoot '.env'
function New-LocalSecret {
    $bytes = [byte[]]::new(32)
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+','-').Replace('/','_')
}
if (Test-Path -LiteralPath $envPath) {
    $existing = [System.IO.File]::ReadAllText($envPath)
    if ($existing -notmatch '(?m)^DOCLING_INTERNAL_TOKEN=.+$') {
        [System.IO.File]::AppendAllText($envPath, "`nDOCLING_INTERNAL_TOKEN=$(New-LocalSecret)`n", [System.Text.UTF8Encoding]::new($false))
        Write-Output 'Preserved .env and added the missing private parser token.'
    } else { Write-Output '.env already exists; preserved.' }
    exit 0
}
$content = @(
    "DB_PASSWORD=$(New-LocalSecret)",
    "SESSION_TOKEN_PEPPER=$(New-LocalSecret)",
    "DEMO_INITIAL_PASSWORD=$(New-LocalSecret)",
    'DEMO_SEED_ENABLED=true',
    "TEST_DB_PASSWORD=$(New-LocalSecret)",
    "DOCLING_INTERNAL_TOKEN=$(New-LocalSecret)",
    'WEB_PORT=5173',
    'API_PORT=8080'
)
[System.IO.File]::WriteAllLines($envPath, $content, [System.Text.UTF8Encoding]::new($false))
Write-Output 'Generated project-local .env. Read DEMO_INITIAL_PASSWORD locally for the first login; secrets are not printed.'
