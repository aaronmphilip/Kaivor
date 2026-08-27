# Kaivor Android

Kaivor is an Android application plus the supporting web/assets folders in this checkout. The Android Studio project is in `android/`.

## Resume from a clean laptop

1. Install **Android Studio** (stable channel) from the official Android developer website.
2. In Android Studio's SDK Manager, install **Android SDK Platform 34**, **Android SDK Build-Tools**, and **Android SDK Platform-Tools**.
3. Install a **JDK 17** and configure Android Studio's Gradle JDK to that JDK. This project uses Java/Kotlin 17.
4. Install **Git** and clone this repository.
5. Open the repository's `android/` directory in Android Studio. Do not open the repository root as the Android project.
6. Allow Gradle to sync and download dependencies. The included Gradle wrapper is authoritative; use `gradlew.bat` on Windows.
7. Create or start an Android emulator with API 34, or connect a physical Android device with Developer options and USB debugging enabled.
8. Run the `app` configuration. For a command-line debug build from `android/`, run:

   ```powershell
   .\gradlew.bat assembleDebug
   ```

## Toolchain and dependencies

- Android SDK compile/target: API 34
- Minimum Android version: API 26
- JDK: 17
- Kotlin: 1.9.23
- Android Gradle Plugin: 8.4.0
- Gradle wrapper: 8.6
- AndroidX Core KTX, AppCompat, Material, ConstraintLayout
- Kotlin Coroutines, OkHttp, Gson, Jsoup, PDFBox Android, DataStore Preferences, Lifecycle Service

Gradle dependency versions are kept in `android/gradle/libs.versions.toml`.

## Release signing

The original release keystore and `keystore.properties` were intentionally **not uploaded**. They contain signing credentials and machine-specific paths. A debug build works without them. To create a release build later, create a new local `android/keystore.properties` with the keys expected by `android/app/build.gradle.kts` and point `storeFile` at a local keystore. Do not commit either file.

If the old signing identity is required for an existing Play Store application, recover the keystore from a separately secured backup; it is not recoverable from this repository's clean checkout.

## Important files

- `android/`: Android Studio project
- `scripts/`: Windows build/install helpers
- `assets/`: brand assets
- `downloads/`: locally produced APK artifacts, when present
- `apps/`, `packages/`, `skills/`: supporting web/product material retained from the original workspace

## Working safely

Do not commit `local.properties`, `.env*`, signing files, `node_modules`, Gradle caches, or build outputs. Android Studio regenerates its local state after cloning.

