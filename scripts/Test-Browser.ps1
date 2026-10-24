[CmdletBinding()]
param(
    [ValidateSet('All','Smoke','Knowledge','Ai','Design','KnowledgeVisual','Recovery','Galaxy1','Galaxy2','Demo')][string]$Scope = 'All',
    [switch]$KnowledgeOnly,[switch]$AiOnly,[switch]$DesignOnly,[switch]$KnowledgeVisualOnly,
    [switch]$KeepArtifacts,[switch]$SkipParserBuild
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$artifactRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([IO.Path]::DirectorySeparatorChar)
$runArtifacts = [IO.Path]::GetFullPath((Join-Path $artifactRoot ('ticknova-browser-' + [guid]::NewGuid().ToString('N'))))
if (-not $runArtifacts.StartsWith($artifactRoot + [IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe artifact path.' }
$testEnv = Join-Path $projectRoot '.env.browser'
$compose = @('compose','--env-file','.env.browser','-p','ticknova-browser','-f','deploy/compose.test.yaml','-f','deploy/compose.browser.yaml')
$previousWorker = $env:BROWSER_AI_WORKER_ENABLED
$runLock = $null; $environmentCreated = $false
$lockPath = Join-Path $projectRoot '.cache/browser-test.lock'
Push-Location -LiteralPath $projectRoot
try {
    $legacyScopes = @($KnowledgeOnly,$AiOnly,$DesignOnly,$KnowledgeVisualOnly).Where({ $_ })
    if ($legacyScopes.Count -gt 1 -or ($legacyScopes.Count -and $Scope -ne 'All')) { throw 'Choose only one browser scope.' }
    if ($KnowledgeOnly) { $Scope = 'Knowledge' }; if ($AiOnly) { $Scope = 'Ai' }
    if ($DesignOnly) { $Scope = 'Design' }; if ($KnowledgeVisualOnly) { $Scope = 'KnowledgeVisual' }
    New-Item -ItemType Directory -Path (Split-Path -Parent $lockPath) -Force | Out-Null
    $runLock = [IO.File]::Open($lockPath,[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    if (Test-Path -LiteralPath $testEnv) { Remove-Item -LiteralPath $testEnv -Force }
    & (Join-Path $PSScriptRoot 'New-LocalEnv.ps1') -FileName '.env.browser'
    $environmentCreated = $true
    $env:BROWSER_AI_WORKER_ENABLED = if ($Scope -eq 'Galaxy2') { 'false' } else { 'true' }
    if (!(Test-Path -LiteralPath 'frontend/node_modules/playwright/index.mjs')) {
        npm --prefix frontend ci --ignore-scripts --cache (Join-Path $projectRoot 'frontend/.cache/npm')
        if ($LASTEXITCODE -ne 0) { throw 'Cannot install the locked browser test dependencies.' }
    }
    & docker @compose down --volumes --remove-orphans
    if ($LASTEXITCODE -ne 0) { throw 'Cannot reset the dedicated browser environment.' }
    if ($SkipParserBuild) { & (Join-Path $PSScriptRoot 'Assert-ParserImage.ps1'); & docker @compose build api web } else { & docker @compose build api web docling }
    if ($LASTEXITCODE -ne 0) { throw 'Browser image build failed.' }
    & docker @compose up --no-build -d --wait postgres docling api web
    if ($LASTEXITCODE -ne 0) { throw 'Isolated browser startup failed.' }
    $database = (& docker @compose exec -T postgres psql -U ticknova_test -d ticknova_browser -At -c 'SELECT current_database()').Trim()
    $webContainer = (& docker @compose ps -q web).Trim()
    if ($LASTEXITCODE -ne 0 -or $database -ne 'ticknova_browser' -or -not $webContainer) { throw 'Refusing browser tests: isolated database identity mismatch.' }
    New-Item -ItemType Directory -Path $runArtifacts | Out-Null
    if ($Scope -eq 'Demo') {
        & docker run --rm --network "container:$webContainer" -v "${projectRoot}:/workspace" -w /workspace mcr.microsoft.com/playwright:v1.64.1-noble@sha256:7852dc5a7d72f5fe458a597d690f6c42fbc8e9e26ddfa95f8e6ef552558d9bbf node scripts/Seed-Demo.mjs http://127.0.0.1 /workspace/.env.browser verified-empty-ticknova-browser
        if ($LASTEXITCODE -ne 0) { throw 'Isolated demo dataset initialization failed.' }
    }
    $browserScripts = switch ($Scope) {
        'All' { 'Smoke','Knowledge','Ai','Knowledge-Recovery' }
        'Smoke' { 'Smoke' }
        'Knowledge' { 'Smoke','Knowledge' }
        'Ai' { 'Smoke','Ai' }
        'Design' { 'Smoke','Design' }
        'KnowledgeVisual' { 'Smoke','Knowledge-Visual' }
        'Recovery' { 'Knowledge-Recovery' }
        'Galaxy1' { 'Galaxy-Batch1' }
        'Galaxy2' { 'Galaxy-Batch2' }
        'Demo' { 'Demo' }
    }
    foreach ($browserScript in $browserScripts) {
        # Each suite gets its own in-memory login quota; production limiter settings remain unchanged.
        & docker @compose restart api
        if ($LASTEXITCODE -ne 0) { throw 'Cannot restart the isolated API between suites.' }
        & docker @compose up -d --no-deps --wait api
        if ($LASTEXITCODE -ne 0) { throw 'Isolated API did not become ready.' }
        & docker run --rm --network "container:$webContainer" -v "${projectRoot}:/workspace" -v "${runArtifacts}:/evidence" -w /workspace -e PLAYWRIGHT_BROWSERS_PATH=/ms-playwright -e TICKNOVA_URL=http://127.0.0.1 -e TICKNOVA_ENV_FILE=/workspace/.env.browser -e TICKNOVA_EVIDENCE_DIR=/evidence mcr.microsoft.com/playwright:v1.64.1-noble@sha256:7852dc5a7d72f5fe458a597d690f6c42fbc8e9e26ddfa95f8e6ef552558d9bbf node "scripts/Browser-$browserScript.mjs"
        if ($LASTEXITCODE -ne 0) { throw "Browser verification failed in $browserScript." }
    }
} finally {
    if ($environmentCreated) {
        & docker @compose down --volumes --remove-orphans | Out-Null
        if (Test-Path -LiteralPath $testEnv) { Remove-Item -LiteralPath $testEnv -Force }
    }
    if ($runLock) { $runLock.Dispose(); Remove-Item -LiteralPath $lockPath -Force }
    if (Test-Path -LiteralPath $runArtifacts) {
        if ($KeepArtifacts) { Write-Output "Temporary browser artifacts: $runArtifacts" }
        else { Remove-Item -LiteralPath $runArtifacts -Recurse -Force }
    }
    $env:BROWSER_AI_WORKER_ENABLED = $previousWorker
    Pop-Location
}
