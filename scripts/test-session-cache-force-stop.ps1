param(
    [string]$Serial = "127.0.0.1:16384",
    [string]$Adb = $(if ($env:ADB) { $env:ADB } else { "adb" })
)

$ErrorActionPreference = "Stop"
$packageName = "com.labteto.dshmobile.debug"
$activityName = "com.labteto.dshmobile.browser.RuntimeFixtureActivity"
$component = "$packageName/$activityName"
$apkPath = Join-Path $PSScriptRoot "../app/build/outputs/apk/debug/app-debug.apk"

if (-not (Test-Path -LiteralPath $Adb)) { throw "adb not found: $Adb" }
if (-not (Test-Path -LiteralPath $apkPath)) { throw "Build debug APK first: $apkPath" }
$deviceLine = & $Adb devices | Where-Object { $_ -match "^$([regex]::Escape($Serial))\s+device$" }
if (-not $deviceLine) { throw "Device is not online: $Serial" }
& $Adb -s $Serial install -r $apkPath
if ($LASTEXITCODE -ne 0) { throw "Debug fixture APK install failed" }

function Start-FixtureOperation([string]$operation, [string]$expectedMarker) {
    & $Adb -s $Serial logcat -c
    & $Adb -s $Serial shell am start -W -n $component --es operation $operation | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Could not start fixture operation: $operation" }
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    do {
        Start-Sleep -Milliseconds 400
        $lines = & $Adb -s $Serial logcat -d -s SessionCacheFixture:I '*:S'
        if (($lines -join "`n").Contains($expectedMarker)) { return ($lines -join "`n") }
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Fixture marker not observed for $operation. Logcat: $($lines -join ' | ')"
}

& $Adb -s $Serial shell am force-stop $packageName
$writeLog = Start-FixtureOperation "write" "SESSION_CACHE_FIXTURE:WRITE_OK:"
& $Adb -s $Serial shell am force-stop $packageName
if ($LASTEXITCODE -ne 0) { throw "Could not force-stop debug fixture app" }
$readLog = Start-FixtureOperation "read" "SESSION_CACHE_FIXTURE:READ_OK:"
Write-Output "WRITE: $writeLog"
Write-Output "AFTER_FORCE_STOP_READ: $readLog"
