[CmdletBinding()]
param([switch]$SkipParserBuild)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'Performance-Report.ps1')
$projectRoot=Split-Path -Parent $PSScriptRoot
$compose=@('compose','--env-file','.env.perf','-p','ticknova-perf','-f','deploy/compose.test.yaml','-f','deploy/compose.perf.yaml')
$nodeImage='node:24.22.0-alpine@sha256:44fce696f38ba8c987c4db89954051000bbd05ffd8d8659ba15ed4a8602e937f'
$k6Image='grafana/k6:1.0.0@sha256:f21270290d702cbf0a7d6ba5d7ed100b63bcb233b558b885ed787547b3488279'
$cacheRoot=[IO.Path]::GetFullPath((Join-Path $projectRoot '.cache'))
$runRoot=[IO.Path]::GetFullPath((Join-Path $cacheRoot ('perf-'+[guid]::NewGuid().ToString('N'))))
$envPath=Join-Path $projectRoot '.env.perf'; $lockPath=Join-Path $cacheRoot 'performance.lock'
$runLock=$null; $started=$false; $createdEnv=$false; $target=$null; $report=$null; $evidenceCreated=$false
Push-Location -LiteralPath $projectRoot
try {
    New-Item -ItemType Directory -Path $cacheRoot -Force | Out-Null
    $runLock=[IO.File]::Open($lockPath,[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    if(Test-Path -LiteralPath $envPath){throw 'Existing .env.perf was preserved. Inspect it before a new ephemeral performance run.'}
    & (Join-Path $PSScriptRoot 'New-LocalEnv.ps1') -FileName '.env.perf'; $createdEnv=$true
    New-Item -ItemType Directory -Path (Join-Path $runRoot 'input'),(Join-Path $runRoot 'output') -Force | Out-Null
    docker @compose config --quiet
    if($LASTEXITCODE -ne 0){throw 'Performance Compose configuration invalid'}
    $started=$true
    docker @compose down --volumes --remove-orphans
    if($LASTEXITCODE -ne 0){throw 'Cannot reset dedicated performance environment'}
    if($SkipParserBuild){& (Join-Path $PSScriptRoot 'Assert-ParserImage.ps1'); docker @compose build api web}else{docker @compose build api web docling}
    if($LASTEXITCODE -ne 0){throw 'Performance image build failed'}
    docker @compose up --no-build -d --wait postgres docling api web
    if($LASTEXITCODE -ne 0){throw 'Performance stack did not become healthy'}
    $database=(& docker @compose exec -T postgres psql -U ticknova_test -d ticknova_perf -At -c 'SELECT current_database()').Trim()
    $fresh=(& docker @compose exec -T postgres psql -U ticknova_test -d ticknova_perf -At -c 'SELECT (SELECT count(*) FROM app_user)=4 AND NOT EXISTS(SELECT 1 FROM ticket) AND NOT EXISTS(SELECT 1 FROM knowledge_space)').Trim()
    if($LASTEXITCODE -ne 0 -or $database -ne 'ticknova_perf' -or $fresh -ne 't'){throw 'Refusing writes: fresh performance database identity mismatch'}
    $web=(& docker @compose ps -q web).Trim()
    docker run --rm --network "container:$web" -v "${projectRoot}:/workspace" -w /workspace $nodeImage node scripts/Seed-Demo.mjs http://127.0.0.1 /workspace/.env.perf verified-empty-ticknova-perf
    if($LASTEXITCODE -ne 0){throw 'Original performance seed failed'}
    docker @compose restart api
    if($LASTEXITCODE -ne 0){throw 'Cannot reset isolated login quota after seeding'}
    docker @compose up -d --no-deps --wait api
    if($LASTEXITCODE -ne 0){throw 'Isolated API not healthy'}
    $relativeRun=$runRoot.Substring($projectRoot.Length).Replace('\','/')
    docker run --rm --network "container:$web" -v "${projectRoot}:/workspace" -w /workspace $nodeImage node scripts/Prepare-Performance.mjs http://127.0.0.1 /workspace/.env.perf "/workspace$relativeRun/input/fixture.json"
    if($LASTEXITCODE -ne 0){throw 'Performance fixture preparation failed'}
    $environment=(& docker info --format '{"cpus":{{.NCPU}},"memoryBytes":{{.MemTotal}},"os":"{{.OperatingSystem}}","kernel":"{{.KernelVersion}}"}' | ConvertFrom-Json)
    $images=@{}; foreach($service in @('api','web','postgres','docling')){$id=(& docker @compose ps -q $service).Trim();$images[$service]=(& docker inspect --format '{{.Image}}' $id).Trim()}
    docker run --rm --user 0 --read-only --tmpfs /tmp:mode=1777 --network "container:$web" -v "$(Join-Path $runRoot 'input'):/input:ro" -v "$(Join-Path $runRoot 'output'):/output" -v "${projectRoot}/eval/service-desk.k6.js:/test.js:ro" $k6Image run /test.js
    $loadExit=$LASTEXITCODE
    $summaryPath=Join-Path $runRoot 'output/summary.json'
    if(!(Test-Path -LiteralPath $summaryPath)){throw 'k6 did not produce a summary; performance was not measured successfully'}
    $after=(& docker @compose exec -T postgres psql -U ticknova_test -d ticknova_perf -At -c 'SELECT count(*) FROM ticket').Trim()
    if($LASTEXITCODE -ne 0){throw 'Cannot verify persisted ticket count'}
    $summary=Get-Content -LiteralPath $summaryPath -Raw|ConvertFrom-Json
    $verdict=Get-PerformanceStatus $summary $loadExit ([int]$after)
    $report=[ordered]@{recordedAt=[DateTime]::UtcNow.ToString('o');status=$verdict;cleanupStatus='PENDING';mode='mock';database='ticknova_perf';initialTickets=1000;finalTickets=[int]$after;documents=12;k6Image=$k6Image;images=$images;environment=$environment;workload=@{listVus=50;listDurationSeconds=300;createVus=50;createIterations=500;answerVus=2;answerIterations=30};scope='Warm HTTP list/create and async cited answer submission/completion; completion includes queue, retrieval, mock generation and client polling';notRun=@('Real-provider latency','Isolated retrieval-only P95','Long-duration stability','Production performance');k6=$summary}
    $target=Join-Path $projectRoot ('docs/evidence/performance-mock-'+[DateTime]::UtcNow.ToString("yyyyMMddTHHmmssfffZ")+'.json')
    $stream=[IO.File]::Open($target,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
    $evidenceCreated=$true
    try{$writer=[IO.StreamWriter]::new($stream,[Text.UTF8Encoding]::new($false));try{$writer.WriteLine(($report|ConvertTo-Json -Depth 40))}finally{$writer.Dispose()}}finally{$stream.Dispose()}
    Write-Output "Performance evidence: $target"
    if($verdict -ne 'PASS'){throw 'Performance thresholds, sample completeness or persisted counts failed; evidence was preserved.'}
} finally {
    $cleanupExit=0
    if($started){try{docker @compose down --volumes --remove-orphans|Out-Null;$cleanupExit=$LASTEXITCODE}catch{$cleanupExit=1}}
    try {
        Complete-PerformanceCleanup $cleanupExit {
            if($createdEnv -and (Test-Path -LiteralPath $envPath)){Remove-Item -LiteralPath $envPath -Force}
            if(Test-Path -LiteralPath $runRoot){
                if(!$runRoot.StartsWith($cacheRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or ((Get-Item -LiteralPath $runRoot).Attributes -band [IO.FileAttributes]::ReparsePoint)){throw 'Unsafe performance cleanup path'}
                Remove-Item -LiteralPath $runRoot -Recurse -Force
            }
        }
        if($null -ne $report){$report.cleanupStatus='COMPLETED'}
    } catch {
        if($null -ne $report){$report.status='FAILED';$report.cleanupStatus='FAILED'}
        throw
    } finally {
        if($evidenceCreated){[IO.File]::WriteAllText($target,($report|ConvertTo-Json -Depth 40)+[Environment]::NewLine,[Text.UTF8Encoding]::new($false))}
        if($runLock){$runLock.Dispose();Remove-Item -LiteralPath $lockPath -Force}
        Pop-Location
    }
}
