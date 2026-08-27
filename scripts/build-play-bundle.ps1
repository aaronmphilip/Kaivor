# Build signed AAB for Google Play internal/closed testing.
$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$android = Join-Path $root "android"
$out = Join-Path $root "downloads\Kaivor-release.aab"
$bundle = Join-Path $android "app\build\outputs\bundle\release\app-release.aab"

Push-Location $android
try {
    & .\gradlew.bat bundleRelease
    if ($LASTEXITCODE -ne 0) { throw "Gradle bundleRelease failed" }
} finally {
    Pop-Location
}

if (-not (Test-Path $bundle)) {
    throw "AAB not found at $bundle"
}

Copy-Item $bundle $out -Force
Write-Host "Play bundle: $out"
Write-Host "Upload to Play Console → Testing → Internal testing"