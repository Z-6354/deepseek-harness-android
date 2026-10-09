# Publish a signed release APK to dsh.wannian.fun update channel.
# Usage:
#   .\scripts\publish-app-update.ps1 -ApkPath app\build\outputs\apk\release\app-release.apk -Version 0.16.0
param(
    [Parameter(Mandatory = $true)][string]$ApkPath,
    [Parameter(Mandatory = $true)][string]$Version,
    [string]$HostName = $env:DSHA_UPDATE_HOST,
    [string]$RemoteDir = "/var/www/dsha/update",
    [string]$PublicBase = "https://dsh.wannian.fun/dsha/update",
    [string]$Notes = ""
)

$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($HostName)) {
    throw "Set `$env:DSHA_UPDATE_HOST (e.g. ubuntu@<host>) or pass -HostName; the server address is intentionally not stored in the repo."
}
if (-not (Test-Path $ApkPath)) { throw "APK not found: $ApkPath" }

$apk = Get-Item $ApkPath
$sha = (Get-FileHash -Algorithm SHA256 $apk.FullName).Hash.ToLowerInvariant()
$bytes = $apk.Length
$parts = $Version.TrimStart('v').Split('.')
$major = [int]($parts[0]); $minor = [int]($parts[1]); $patch = [int]($parts[2])
$code = $major * 10000 + $minor * 100 + $patch
$name = "dsha-$Version.apk"
$remoteApk = "$RemoteDir/$name"
$apkUrl = "$PublicBase/$name"

Write-Host "Uploading $name ($bytes bytes) sha256=$sha"
$remoteTmpApk = "/tmp/$name"
scp $apk.FullName "${HostName}:$remoteTmpApk"
if ($LASTEXITCODE -ne 0) { throw "scp APK failed" }

$json = @"
{
  "versionName": "$Version",
  "versionCode": $code,
  "apkUrl": "$apkUrl",
  "apkName": "$name",
  "apkBytes": $bytes,
  "sha256": "$sha",
  "releaseNotes": $($Notes | ConvertTo-Json)
}
"@
$tmp = [System.IO.Path]::GetTempFileName()
Set-Content -Path $tmp -Value $json -Encoding utf8NoBOM
scp $tmp "${HostName}:/tmp/latest.json"
Remove-Item $tmp -Force
if ($LASTEXITCODE -ne 0) { throw "scp latest.json failed" }

Write-Host "Published $PublicBase/latest.json"
ssh $HostName "sudo install -m 664 -o www-data -g www-data $remoteTmpApk $remoteApk && sudo install -m 664 -o www-data -g www-data /tmp/latest.json $RemoteDir/latest.json && ls -la $RemoteDir"
