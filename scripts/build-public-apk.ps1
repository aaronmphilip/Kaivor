# Build the public Kaivor APK the same way BharatDroid does:
# debug-signed artifact copied to downloads/Kaivor-v1.0.apk
$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$android = Join-Path $root "android"
$out = Join-Path $root "downloads\Kaivor-v1.0.apk"
$debugApk = Join-Path $android "app\build\outputs\apk\sideload\debug\app-sideload-debug.apk"

Push-Location $android
try {
    & .\gradlew.bat assembleSideloadDebug testSideloadDebugUnitTest
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed with exit code $LASTEXITCODE" }
} finally {
    Pop-Location
}

if (-not (Test-Path $debugApk)) {
    throw "Debug APK not found at $debugApk"
}

Copy-Item $debugApk $out -Force
$hash = (Get-FileHash $out -Algorithm SHA256).Hash.ToLowerInvariant()
Write-Host "Built public APK: $out"
Write-Host "SHA-256: $hash"
Write-Host "Flavor: sideload (no notification listener / phone services)"
Write-Host "Signing: Android Debug"