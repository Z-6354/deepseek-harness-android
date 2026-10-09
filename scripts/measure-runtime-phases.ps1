# Local/authorized deployed target only. Does not clear app storage, chat data or login.
# Native receipt and web relative clocks remain separate; absent evidence stays null.
param(
  [string]$Serial = '127.0.0.1:16384',
  [string]$Package = 'com.labteto.dshmobile.debug',
  [int]$Samples = 1,
  [int]$WaitSeconds = 20,
  [string]$TraceFile = '',
  [string]$Output = "$PSScriptRoot/../artifacts/runtime-measurement.json"
)
$ErrorActionPreference = 'Stop'
if ($Samples -lt 1 -or $Samples -gt 40 -or $WaitSeconds -lt 1 -or $WaitSeconds -gt 60) { throw 'invalid sample/time bound' }
$component = "$Package/com.labteto.dshmobile.MainActivity"
$stages = @('documentStarted', 'documentCommitted', 'pageReady', 'visualComplete', 'loadingPaint', 'historyReady', 'bodyPaint', 'inputReady', 'imageShown', 'imageFailed')
$results = [System.Collections.Generic.List[object]]::new()
function Read-Sample([string]$Mode, [string[]]$ActivityOutput) {
  $trace = if ($TraceFile) { @(Get-Content -LiteralPath $TraceFile) } else { @(adb -s $Serial logcat -d -s LaunchTrace:I '*:S') }
  $traceIndex = 0
  $events = foreach ($line in $trace) {
    $traceIndex++
    if ($line -match 'browserId=(\d+) generation=(\d+) event=(\w+) elapsedMs=(\d+) webElapsedMs=([\d.]+|unknown)') {
      [pscustomobject]@{ index = $traceIndex; browser = [long]$Matches[1]; document = [long]$Matches[2]; stage = $Matches[3]; elapsed = [long]$Matches[4]; web = $(if ($Matches[5] -eq 'unknown') { $null } else { [double]::Parse($Matches[5], [Globalization.CultureInfo]::InvariantCulture) }) }
    }
  }
  $begin = @($events | Where-Object stage -eq 'beginLaunch' | Select-Object -Last 1)
  $latestDocument = @($events | Where-Object { $_.stage -eq 'documentStarted' -and ($begin.Count -eq 0 -or $_.index -ge $begin[0].index) } | Select-Object -Last 1)
  $phase = [ordered]@{}
  foreach ($stage in $stages) {
    $match = @($events | Where-Object { $_.stage -eq $stage -and ($begin.Count -eq 0 -or ($_.browser -eq $begin[0].browser -and $_.index -ge $begin[0].index)) -and ($latestDocument.Count -eq 0 -or $_.document -eq $latestDocument[0].document) } | Select-Object -First 1)
    $phase[$stage] = @{ nativeReceiptMs = $(if ($match.Count -and $begin.Count) { $match[0].elapsed - $begin[0].elapsed } else { $null }); webElapsedMs = $(if ($match.Count) { $match[0].web } else { $null }) }
  }
  $joined = $ActivityOutput -join "`n"
  $total = if ($joined -match 'TotalTime:\s*(\d+)') { [int]$Matches[1] } else { $null }
  $launchState = if ($joined -match 'LaunchState:\s*(\w+)') { $Matches[1] } else { 'unknown' }
  $newDocuments = @($events | Where-Object { $_.stage -eq 'documentStarted' -and ($begin.Count -eq 0 -or $_.index -ge $begin[0].index) }).Count
  [pscustomobject]@{
    mode = $Mode; activityTotalTimeMs = $total; launchState = $launchState; newDocuments = $newDocuments; stages = $phase
    matchedTargetOwner = $(if ($phase.bodyPaint.nativeReceiptMs -ne $null) { $true } else { $null })
    homeFlashCount = $null
    routeVerdict = $(if ($phase.bodyPaint.nativeReceiptMs -ne $null) { 'matched-owner-painted; home visibility unmeasured' } else { 'unknown; matching new plugin diagnostics required' })
    hotReuseVerdict = $(if ($Mode -eq 'hot' -and $launchState -eq 'HOT' -and $newDocuments -eq 0) { 'observed' } else { 'unknown' })
  }
}
if ($TraceFile) { $results.Add((Read-Sample 'fixture' @())) } else {
for ($run = 1; $run -le $Samples; $run++) {
  adb -s $Serial shell am force-stop $Package | Out-Null
  adb -s $Serial logcat -c | Out-Null
  $cold = @(adb -s $Serial shell am start -W -n $component)
  $until = (Get-Date).AddSeconds($WaitSeconds)
  do {
    Start-Sleep -Milliseconds 250
    $paint = @(adb -s $Serial logcat -d -s LaunchTrace:I '*:S' | Select-String 'event=inputReady ')
  } while (-not $paint.Count -and (Get-Date) -lt $until)
  $results.Add((Read-Sample 'cold' $cold))
  adb -s $Serial shell am start -a android.intent.action.MAIN -c android.intent.category.HOME | Out-Null
  Start-Sleep -Milliseconds 500
  adb -s $Serial logcat -c | Out-Null
  $hot = @(adb -s $Serial shell am start -W -n $component)
  Start-Sleep -Milliseconds 500
  $results.Add((Read-Sample 'hot' $hot))
}
}
$report = [ordered]@{ schema = 1; samplePairs = $(if ($TraceFile) { 0 } else { $Samples }); samples = $results.ToArray(); distributionVerdict = $(if ($Samples -ge 20) { 'compute content/input distribution only when every sample has matching metrics' } else { 'insufficient samples for p95 claim' }); imageRpcBytesTiming = $null; homeVisibilityTiming = $null }
$report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $Output -Encoding utf8
$report | ConvertTo-Json -Depth 8
