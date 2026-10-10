# Creates the DSHA release signing key OUTSIDE the repository (default: ~/.dsha-release).
# The keystore and its password never enter git; back both up. Losing them means every installed
# copy must be uninstalled before a differently signed APK can be installed.
#
# Usage:  .\scripts\new-release-keystore.ps1 [-OutDir <dir>]
# Then:   see docs/DEPLOYMENT.md "Release signing" for the four DSH_* variables / GitHub secrets.
param([string]$OutDir = (Join-Path $HOME ".dsha-release"))

$ErrorActionPreference = "Stop"
$keystore = Join-Path $OutDir "release.keystore"
$envFile = Join-Path $OutDir "release.env.txt"
if (Test-Path $keystore) { throw "Refusing to overwrite existing $keystore" }
if ((Resolve-Path -LiteralPath $PSScriptRoot).Path -and ($OutDir -like "$((Resolve-Path "$PSScriptRoot\..").Path)*")) {
    throw "OutDir must be outside the repository"
}

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$bytes = New-Object byte[] 24
[System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
$password = [Convert]::ToBase64String($bytes).Replace('+', 'A').Replace('/', 'B').Replace('=', 'C')
$alias = "dsha-release"

& keytool -genkeypair -keystore $keystore -alias $alias -keyalg RSA -keysize 4096 -validity 10000 `
    -storetype PKCS12 -storepass $password -keypass $password -dname "CN=DSHA, O=Z-6354, C=CN"
if ($LASTEXITCODE -ne 0) { throw "keytool failed" }

@"
DSH_KEYSTORE=$keystore
DSH_KEYSTORE_PASSWORD=$password
DSH_KEY_ALIAS=$alias
DSH_KEY_PASSWORD=$password
"@ | Set-Content -Path $envFile -Encoding ascii

# Owner-only access to the directory contents.
& icacls $OutDir /inheritance:r /grant:r "$($env:USERNAME):(OI)(CI)F" | Out-Null

$fp = & keytool -list -v -keystore $keystore -storepass $password 2>&1 | Select-String 'SHA256:' | Select-Object -First 1
Write-Host "Created $keystore"
Write-Host "Credentials: $envFile (back this folder up; never commit it)"
Write-Host $fp.Line.Trim()
