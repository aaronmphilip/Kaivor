# BharatDroid Android

BharatDroid is an Android automation agent. The Android Studio project is in `android/`; the other top-level folders contain the supporting web/product material retained from the original workspace.

## Resume from a clean laptop

1. Install **Android Studio** (stable channel) from the official Android developer website.
2. In SDK Manager, install **Android SDK Platform 34**, **Android SDK Build-Tools**, and **Android SDK Platform-Tools**.
3. Install **JDK 17** and set Android Studio's Gradle JDK to it. The project compiles Java/Kotlin with version 17.
4. Install **Git** and clone this repository.
5. Open the cloned repository's `android/` directory in Android Studio.
6. Let Gradle sync using the included wrapper. On Windows, build from `android/` with:

   ```powershell
   .\gradlew.bat assembleDebug
   ```

7. Start an API 34 emulator or connect an Android device with Developer options and USB debugging enabled.
8. Run the `app` configuration from Android Studio.

## Toolchain

- Android SDK compile/target: API 34
- Minimum Android version: API 26
- JDK: 17
- Kotlin and Android Gradle Plugin versions: see `android/gradle/libs.versions.toml`
- Gradle version: use the included wrapper (`android/gradle/wrapper`)

## First-run requirements

The agent needs internet access, a Telegram bot token, and an AI provider API key configured through the app's onboarding flow. The target apps used by skills must be installed on the phone. Enable the BharatDroid Accessibility Service and keep the foreground service running. For reliable background operation, set BharatDroid's battery usage to **Unrestricted / Don't optimize** in Android settings. Notification relay additionally requires Notification Access.

Never hard-code Telegram tokens or AI keys into source control.

## Current known issues

The original planning notes are preserved in `docs/project-notes/`. They include unresolved Rapido pickup handling, Google Maps directions/ETA behavior, reliability/crashes, task completion, voice/TTS, and broader app coverage. Treat them as historical work context, not as verified fixes.

## Repository layout

- `android/`: Android Studio project
- `docs/project-notes/`: Obsidian planning notes copied from the original BharatDroid project folder
- `apps/`, `packages/`, `skills/`: supporting product material
- `downloads/`: locally produced APK artifacts, when present

Android Studio regenerates `local.properties` and IDE state. Do not commit those files, secrets, Gradle caches, or build outputs.

