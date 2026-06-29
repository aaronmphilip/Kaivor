# Clean rebuild of the public Kaivor APK — same pipeline as BharatDroid:
# gradlew clean → assembleDebug → copy to downloads/Kaivor-v1.0.apk
$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$android = Join-Path $root "android"
$out = Join-Path $root "downloads\Kaivor-v1.0.apk"
$debugApk = Join-Path $android "app\build\outputs\apk\debug\app-debug.apk"

Push-Location $android
try {
    & .\gradlew.bat --stop 2>$null
    & .\gradlew.bat clean assembleDebug testDebugUnitTest --no-build-cache
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed with exit code $LASTEXITCODE" }
} finally {
    Pop-Location
}

if (-not (Test-Path $debugApk)) {
    throw "Debug APK not found at $debugApk"
}

Copy-Item $debugApk $out -Force
$hash = (Get-FileHash $out -Algorithm SHA256).Hash.ToLowerInvariant()
$size = (Get-Item $out).Length
Write-Host "Built public APK: $out"
Write-Host "Size: $size bytes"
Write-Host "SHA-256: $hash"
Write-Host "Signing: Android Debug (BharatDroid-style)"