$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'Performance-Report.ps1')
$complete=[pscustomobject]@{metrics=[pscustomobject]@{tickets_created=[pscustomobject]@{values=[pscustomobject]@{count=500}};answers_completed=[pscustomobject]@{values=[pscustomobject]@{count=30}}}}
if((Get-PerformanceStatus $complete 0 1500) -ne 'PASS'){throw 'Complete measured workload must pass'}
if((Get-PerformanceStatus $complete 99 1500) -ne 'FAILED'){throw 'Failed k6 must never pass'}
if((Get-PerformanceStatus $complete 0 1499) -ne 'FAILED'){throw 'Wrong database count must never pass'}
foreach($metric in @('tickets_created','answers_completed')){
    $partial=$complete|ConvertTo-Json -Depth 8|ConvertFrom-Json
    $partial.metrics.$metric.values.count--
    if((Get-PerformanceStatus $partial 0 1500) -ne 'FAILED'){throw 'Incomplete successful samples must never pass'}
}
if((Get-PerformanceStatus ([pscustomobject]@{metrics=@{}}) 0 1500) -ne 'FAILED'){throw 'Missing metrics must never pass'}
$state=@{removed=$false};$rejected=$false
try{Complete-PerformanceCleanup 1 {$state.removed=$true}}catch{$rejected=$true}
if(-not $rejected -or $state.removed){throw 'Failed teardown must reject completion and preserve private configuration'}
Complete-PerformanceCleanup 0 {$state.removed=$true}
if(-not $state.removed){throw 'Successful teardown must clean its private files'}
Write-Output 'Performance report/cleanup: 8 contract checks passed.'
