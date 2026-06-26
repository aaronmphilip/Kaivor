# Install Kaivor via ADB — bypasses Play Protect sideload block.
# Requires: USB debugging enabled, Android platform-tools (adb) on PATH.
param(
    [string]$ApkPath = "$PSScriptRoot\..\downloads\Kaivor-v1.0.apk"
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $ApkPath)) {
    Write-Error "APK not found: $ApkPath"
}

Write-Host "Checking adb..."
$adb = Get-Command adb -ErrorAction SilentlyContinue
if (-not $adb) {
    Write-Error "adb not found. Install Android platform-tools and add adb to PATH."
}

Write-Host "Waiting for device..."
adb wait-for-device
$devices = adb devices | Select-String "device$"
if (-not $devices) {
    Write-Error "No authorized device. Enable USB debugging and accept the RSA prompt on your phone."
}

Write-Host "Installing $ApkPath ..."
adb install -r $ApkPath
if ($LASTEXITCODE -eq 0) {
    Write-Host "Kaivor installed successfully."
} else {
    Write-Error "adb install failed (exit $LASTEXITCODE). Uninstall old Kaivor first if signatures differ."
}