# End-to-end check of the hosted App update channel (no device required).
# Optional: if adb device present and -InstallOld/-InstallNew, exercises upgrade path.
param(
    [switch]$InstallOld,
    [switch]$InstallNew,
    [string]$ManifestUrl = "https://dsh.wannian.fun/dsha/update/latest.json"
)

$ErrorActionPreference = "Stop"
Write-Host "== fetch $ManifestUrl =="
$json = curl.exe -sS $ManifestUrl
$m = $json | ConvertFrom-Json
Write-Host $json
if (-not $m.versionCode -or -not $m.apkUrl) { throw "invalid manifest" }

$tmp = Join-Path $env:TEMP "dsha-update-test.apk"
Write-Host "== download $($m.apkUrl) =="
curl.exe -sS -L -o $tmp $m.apkUrl
$actual = (Get-FileHash -Algorithm SHA256 $tmp).Hash.ToLowerInvariant()
$size = (Get-Item $tmp).Length
if ($m.sha256 -and ($actual -ne $m.sha256.ToLowerInvariant())) { throw "SHA-256 mismatch" }
if ($m.apkBytes -gt 0 -and $size -ne [int64]$m.apkBytes) { throw "size mismatch" }
Write-Host "OK size=$size sha256=$actual"

$devices = adb devices 2>$null | Select-Object -Skip 1 | Where-Object { $_ -match "device$" }
if (-not $devices) {
    Write-Host "No adb device — channel OK; install/auto-prompt not exercised."
    Remove-Item $tmp -Force
    exit 0
}

if ($InstallNew) {
    Write-Host "== adb install -r (new) =="
    adb install -r $tmp
}
Remove-Item $tmp -Force
Write-Host "DONE"
