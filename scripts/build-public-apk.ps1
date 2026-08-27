# Build the public Kaivor APK — release-signed for Play Protect / developer verification.
$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$android = Join-Path $root "android"
$out = Join-Path $root "downloads\Kaivor-v1.0.apk"
$releaseApk = Join-Path $android "app\build\outputs\apk\release\app-release.apk"
$apksigner = Get-ChildItem "$env:LOCALAPPDATA\Android\Sdk\build-tools" -Directory |
    Sort-Object Name -Descending |
    Select-Object -First 1 |
    ForEach-Object { Join-Path $_.FullName "apksigner.bat" }

Push-Location $android
try {
    & .\gradlew.bat --stop 2>$null
    & .\gradlew.bat clean assembleRelease testReleaseUnitTest --no-build-cache
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed with exit code $LASTEXITCODE" }
} finally {
    Pop-Location
}

if (-not (Test-Path $releaseApk)) {
    throw "Release APK not found at $releaseApk"
}

Copy-Item $releaseApk $out -Force
$fileHash = (Get-FileHash $out -Algorithm SHA256).Hash.ToLowerInvariant()
$size = (Get-Item $out).Length

Write-Host "Built public APK: $out"
Write-Host "Size: $size bytes"
Write-Host "SHA-256 (file): $fileHash"
Write-Host "Signing: Kaivor release keystore"

if (Test-Path $apksigner) {
    Write-Host ""
    Write-Host "Signing certificate (for developer verification / Play Protect appeal):"
    & $apksigner verify --print-certs $out 2>&1 | Select-String "certificate"
}