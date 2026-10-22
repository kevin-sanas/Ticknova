[CmdletBinding()]
param([switch]$KnowledgeOnly,[switch]$AiOnly,[switch]$DesignOnly,[switch]$KnowledgeVisualOnly)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $projectRoot
try {
    if (@($KnowledgeOnly,$AiOnly,$DesignOnly,$KnowledgeVisualOnly).Where({ $_ }).Count -gt 1) { throw 'Choose only one focused browser scope.' }
    if (!(Test-Path -LiteralPath 'frontend/node_modules/playwright/index.mjs')) {
        npm --prefix frontend ci --ignore-scripts --cache (Join-Path $projectRoot 'frontend/.cache/npm')
        if ($LASTEXITCODE -ne 0) { throw 'Cannot install the locked browser test dependencies.' }
    }
    $webContainer = docker compose --env-file .env -f deploy/compose.yaml ps -q web
    if (!$webContainer) { throw 'Start the project with scripts/Start-Local.ps1 before browser verification.' }
    $browserScripts = if ($KnowledgeVisualOnly) { @('scripts/Browser-Knowledge-Visual.mjs') } elseif ($DesignOnly) { @('scripts/Browser-Design.mjs') } elseif ($AiOnly) { @('scripts/Browser-Ai.mjs') } elseif ($KnowledgeOnly) { @('scripts/Browser-Knowledge.mjs') } else { @('scripts/Browser-Smoke.mjs','scripts/Browser-Knowledge.mjs','scripts/Browser-Ai.mjs') }
    foreach ($browserScript in $browserScripts) {
        docker run --rm --name ticknova-browser-smoke --network "container:$webContainer" -v "${projectRoot}:/workspace" -w /workspace -e PLAYWRIGHT_BROWSERS_PATH=/ms-playwright -e TICKNOVA_URL=http://127.0.0.1 mcr.microsoft.com/playwright:v1.64.1-noble@sha256:7852dc5a7d72f5fe458a597d690f6c42fbc8e9e26ddfa95f8e6ef552558d9bbf node $browserScript
        if ($LASTEXITCODE -ne 0) { throw 'Browser verification failed. Repeated sign-ins are subject to the local login rate limit.' }
    }
} finally { Pop-Location }
