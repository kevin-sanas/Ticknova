function Get-PerformanceStatus {
    param($Summary,[int]$ExitCode,[int]$FinalTickets)
    if($ExitCode -ne 0 -or $FinalTickets -ne 1500 -or $null -eq $Summary -or $null -eq $Summary.metrics){return 'FAILED'}
    if($Summary.metrics.tickets_created.values.count -ne 500 -or $Summary.metrics.answers_completed.values.count -ne 30){return 'FAILED'}
    return 'PASS'
}
function Complete-PerformanceCleanup {
    param([int]$ExitCode,[scriptblock]$RemoveTemporaryFiles)
    if($ExitCode -ne 0){throw 'Performance teardown failed; private .env.perf and runtime fixtures were preserved for recovery.'}
    & $RemoveTemporaryFiles
}
