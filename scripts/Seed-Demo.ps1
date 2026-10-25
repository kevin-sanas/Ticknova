[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$compose = @('compose','--env-file','.env.demo','-p','ticknova-demo','-f','deploy/compose.yaml','-f','deploy/compose.demo.yaml')
$lockPath = Join-Path $projectRoot 'data/demo-seed.lock'
New-Item -ItemType Directory -Path (Split-Path -Parent $lockPath) -Force | Out-Null
$seedLock = [IO.File]::Open($lockPath, [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
Push-Location -LiteralPath $projectRoot
try {
    $identity = (& docker @compose exec -T postgres psql -U ticknova_demo -d ticknova_demo -At -c 'SELECT current_database()').Trim()
    if ($LASTEXITCODE -ne 0 -or $identity -ne 'ticknova_demo') { throw 'Seed refused: dedicated demo database identity mismatch.' }
    $fresh = (& docker @compose exec -T postgres psql -U ticknova_demo -d ticknova_demo -At -c "SELECT (SELECT count(*) FROM app_user)=5 AND NOT EXISTS(SELECT 1 FROM app_user WHERE username NOT IN ('admin','agent','requester','editor','reviewer') OR NOT must_change_password OR NOT active) AND (SELECT count(*) FROM user_role)=5 AND (SELECT count(*) FROM support_queue)=1 AND EXISTS(SELECT 1 FROM support_queue WHERE code='GENERAL' AND active) AND (SELECT count(*) FROM service_catalog)=1 AND EXISTS(SELECT 1 FROM service_catalog WHERE code='GENERAL' AND active) AND NOT EXISTS(SELECT 1 FROM ticket) AND NOT EXISTS(SELECT 1 FROM knowledge_space)").Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Cannot verify empty demo database.' }
    if ($fresh -ne 't') {
        $completed = (& docker @compose exec -T postgres psql -U ticknova_demo -d ticknova_demo -At -c "SELECT EXISTS(SELECT 1 FROM audit_event WHERE action='DEMO_CONTENT_SEEDED' AND subject_type='SYSTEM')").Trim()
        if ($LASTEXITCODE -ne 0 -or $completed -ne 't') { throw 'Existing data was preserved. This database has no completed demo-seed marker; an interrupted or user-populated database is never silently overwritten. Inspect it before continuing.' }
        Write-Output 'Demo seed skipped: completed seed already exists; users and business data are preserved.'
        return
    }
    $webContainer=(& docker @compose ps -q web).Trim()
    if(-not $webContainer){throw 'Demo web service is not running.'}
    docker run --rm --network "container:$webContainer" -v "${projectRoot}:/workspace:ro" -v "${projectRoot}/.env.demo:/workspace/.env.demo" -w /workspace node:24.22.0-alpine@sha256:44fce696f38ba8c987c4db89954051000bbd05ffd8d8659ba15ed4a8602e937f node scripts/Seed-Demo.mjs http://127.0.0.1 /workspace/.env.demo verified-empty-ticknova-demo
    if ($LASTEXITCODE -ne 0) { throw 'Demo seed failed. Partial demo data was retained; inspect the failure before creating another fresh demo volume.' }
    & docker @compose exec -T postgres psql -U ticknova_demo -d ticknova_demo -v ON_ERROR_STOP=1 -c "INSERT INTO audit_event(id,actor_id,action,subject_type,trace_id,metadata) SELECT gen_random_uuid(),id,'DEMO_CONTENT_SEEDED','SYSTEM','original-demo-seed',jsonb_build_object('dataset','original-service-desk-v1','mode','mock','documents',12,'tickets',24) FROM app_user WHERE username='admin'"
    if ($LASTEXITCODE -ne 0) { throw 'Demo content was created, but its completion marker could not be recorded.' }
} finally {
    Pop-Location
    $seedLock.Dispose()
    Remove-Item -LiteralPath $lockPath -Force
}
