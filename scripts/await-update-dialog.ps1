$ErrorActionPreference = "Continue"
$serial = if ($env:ANDROID_SERIAL) { $env:ANDROID_SERIAL } else { "emulator-5554" }
function Adb { adb -s $serial @args }

Adb shell am force-stop com.labteto.dshmobile.debug | Out-Null
Adb logcat -c | Out-Null
Adb shell am start -n com.labteto.dshmobile.debug/com.labteto.dshmobile.MainActivity
Write-Host "launched; waiting for update dialog..."

$dumpPath = Join-Path $env:TEMP "dsha-uidump.xml"
$found = $false
for ($i = 1; $i -le 45; $i++) {
    Start-Sleep -Seconds 2
    Adb shell uiautomator dump /sdcard/uidump.xml 2>$null | Out-Null
    Adb pull /sdcard/uidump.xml $dumpPath 2>$null | Out-Null
    if (-not (Test-Path $dumpPath)) { continue }
    $xml = Get-Content $dumpPath -Raw -ErrorAction SilentlyContinue
    if ($null -eq $xml) { continue }
    if ($xml.Contains("发现新版本") -or ($xml.Contains("0.12.3") -and $xml.Contains("更新"))) {
        Write-Host "FOUND update dialog after $($i * 2)s"
        $found = $true
        break
    }
    if (($i % 5) -eq 0) { Write-Host "t=$($i * 2)s waiting..." }
}

if (-not $found) {
    Write-Host "dialog not found; visible texts:"
    if (Test-Path $dumpPath) {
        [regex]::Matches((Get-Content $dumpPath -Raw), 'text="([^"]*)"') | ForEach-Object {
            if ($_.Groups[1].Value.Trim().Length -gt 0) { $_.Groups[1].Value }
        } | Select-Object -Unique | Select-Object -First 40
    }
} else {
    # Tap 更新 button via UI bounds
    $match = [regex]::Match((Get-Content $dumpPath -Raw), 'text="更新"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if ($match.Success) {
        $x = [int]((([int]$match.Groups[1].Value) + ([int]$match.Groups[3].Value)) / 2)
        $y = [int]((([int]$match.Groups[2].Value) + ([int]$match.Groups[4].Value)) / 2)
        Write-Host "tap 更新 at $x,$y"
        Adb shell input tap $x $y
        Start-Sleep -Seconds 8
        Adb shell uiautomator dump /sdcard/uidump2.xml 2>$null | Out-Null
        Adb pull /sdcard/uidump2.xml (Join-Path $env:TEMP "dsha-uidump2.xml") 2>$null | Out-Null
        Write-Host "after tap texts:"
        [regex]::Matches((Get-Content (Join-Path $env:TEMP "dsha-uidump2.xml") -Raw), 'text="([^"]*)"') | ForEach-Object {
            if ($_.Groups[1].Value.Trim().Length -gt 0) { $_.Groups[1].Value }
        } | Select-Object -Unique | Select-Object -First 40
    } else {
        Write-Host "更新 button bounds not parsed"
    }
}

Write-Host "=== installed package ==="
Adb shell dumpsys package com.labteto.dshmobile.debug | Select-String -Pattern "versionName|versionCode" | Select-Object -First 6
Write-Host "=== logcat ==="
Adb logcat -d -t 300 | Select-String -Pattern "okhttp|AppUpdate|Update|dsha|wannian|HTTP|Exception|发现" | Select-Object -Last 40
