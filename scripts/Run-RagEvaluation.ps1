[CmdletBinding()]
param([switch]$Real,[switch]$AllowPaid,[ValidateRange(1,120)][int]$MaxChatCalls=120,[ValidateRange(1,256)][int]$MaxEmbeddingInputs=128,[ValidatePattern('^$|^[a-z0-9-]+(,[a-z0-9-]+)*$')][string]$CaseIds='',[ValidateSet('ALL','HYBRID')][string]$Strategy='ALL')
$ErrorActionPreference = 'Stop'
if ($Real -and -not $AllowPaid) { throw 'Real evaluation requires a reviewed call/cost estimate and explicit -AllowPaid. No provider call was made.' }
if ($AllowPaid -and -not $Real) { throw 'Use -AllowPaid only together with -Real.' }
$projectRoot = Split-Path -Parent $PSScriptRoot
$compose = @('compose','--env-file','.env.eval','-p','ticknova-eval','-f','deploy/compose.test.yaml','-f','deploy/compose.eval.yaml')
$names = @('EVAL_AI_PROVIDER','EVAL_ALLOW_PAID','EVAL_MAX_CHAT_CALLS','EVAL_MAX_EMBEDDING_INPUTS','EVAL_CASE_IDS','EVAL_STRATEGY')
$previous = @{}; foreach ($name in $names) { $previous[$name] = [Environment]::GetEnvironmentVariable($name,'Process') }
$started = $false
Push-Location -LiteralPath $projectRoot
try {
    & (Join-Path $PSScriptRoot 'New-LocalEnv.ps1') -FileName '.env.eval'
    if ($Real) {
        $settings = [IO.File]::ReadAllText((Join-Path $projectRoot '.env.eval'))
        foreach ($key in @('AI_BASE_URL','AI_API_KEY','AI_MODEL','EMBEDDING_MODEL')) { if ($settings -notmatch "(?m)^$key=\S+") { throw "Configure $key locally in .env.eval first. No provider call was made." } }
    }
    $env:EVAL_AI_PROVIDER = if ($Real) { 'openai' } else { 'mock' }
    $env:EVAL_ALLOW_PAID = if ($Real -and $AllowPaid) { 'true' } else { 'false' }
    $env:EVAL_MAX_CHAT_CALLS = [string]$MaxChatCalls; $env:EVAL_MAX_EMBEDDING_INPUTS = [string]$MaxEmbeddingInputs
    $env:EVAL_CASE_IDS=$CaseIds; $env:EVAL_STRATEGY=$Strategy
    docker @compose config --quiet
    if ($LASTEXITCODE -ne 0) { throw 'Evaluation Compose configuration is invalid.' }
    $started = $true
    docker @compose down --volumes --remove-orphans
    if ($LASTEXITCODE -ne 0) { throw 'Cannot reset dedicated evaluation environment.' }
    docker volume create ticknova-maven-cache | Out-Null
    docker @compose run --rm api-test
    if ($LASTEXITCODE -ne 0) { throw 'RAG benchmark failed; inspect the recorded evidence. No demo database was used.' }
} finally {
    if ($started) { docker @compose down --volumes --remove-orphans | Out-Null }
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name,$previous[$name],'Process') }
    Pop-Location
}
