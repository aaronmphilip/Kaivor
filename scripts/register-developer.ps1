# Prepare Kaivor for install WITH Play Protect enabled.
# Google requires verified developer registration or Play Store distribution.
$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$apk = Join-Path $root "downloads\Kaivor-v1.0.apk"

Write-Host "=== Kaivor — Install with Play Protect ON ===" -ForegroundColor Cyan
Write-Host ""
Write-Host "Google blocks Telegram/browser APK installs that declare Accessibility."
Write-Host "Rebuilding the APK cannot bypass this. You need ONE of these official paths:"
Write-Host ""

if (-not (Test-Path $apk)) {
    Write-Host "APK missing — building release APK first..." -ForegroundColor Yellow
    & (Join-Path $PSScriptRoot "build-public-apk.ps1")
}

$apksigner = Get-ChildItem "$env:LOCALAPPDATA\Android\Sdk\build-tools" -Directory |
    Sort-Object Name -Descending |
    Select-Object -First 1 |
    ForEach-Object { Join-Path $_.FullName "apksigner.bat" }

Write-Host "Package: com.kaivor.agent"
Write-Host "APK: $apk"
if (Test-Path $apksigner) {
    & $apksigner verify --print-certs $apk 2>&1 | Select-String "certificate"
}
Write-Host ""

Write-Host "OPTION 1 — Android Developer Verification (sideload, Play Protect stays on)" -ForegroundColor Green
Write-Host "  1. Open https://developer.android.com/developer-verification"
Write-Host "  2. Sign in → Android Developer Console → verify your identity"
Write-Host "  3. Register package name: com.kaivor.agent"
Write-Host "  4. Upload this APK to prove you own the signing key:"
Write-Host "     $apk"
Write-Host "  5. Hobbyist? Use free Limited Distribution (up to 20 devices)"
Write-Host "  Console: https://android.google.com/developerconsole/developers"
Write-Host ""

Write-Host "OPTION 2 — Google Play internal testing (Play Protect stays on)" -ForegroundColor Green
Write-Host "  1. Play Console → Create app → Upload AAB from scripts/build-play-bundle.ps1"
Write-Host "  2. Testing → Internal testing → Add your Gmail as tester"
Write-Host "  3. Install from the Play Store tester link (not Telegram)"
Write-Host ""

Write-Host "OPTION 3 — Play Protect appeal (after registering developer)" -ForegroundColor Green
Write-Host "  https://support.google.com/googleplay/android-developer/contact/protectappeals"
Write-Host ""

$open = Read-Host "Open Android Developer Console in browser? (y/n)"
if ($open -eq "y") {
    Start-Process "https://android.google.com/developerconsole/developers"
}