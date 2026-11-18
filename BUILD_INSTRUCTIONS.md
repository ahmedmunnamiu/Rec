# Build instructions

## A. GitHub Actions (recommended)

1. Create a repository and upload **everything** in this folder, including the hidden `.github/` folder and
   `gradle/wrapper/gradle-wrapper.jar`. (When uploading through the web UI, drag the whole folder so `.github` is included.)
2. Open the **Actions** tab → **Android Build** (it also runs on every push; you can start it manually with *Run workflow*).
3. When the run finishes, download from **Artifacts**:
   - `AdvancedCallRecorder-debug-apk` → `AdvancedCallRecorder-debug.apk`
   - `AdvancedCallRecorder-release-apk` → `AdvancedCallRecorder-release.apk` (only with signing configured)
   - `AdvancedCallRecorder-Source` → `AdvancedCallRecorder-Source.zip` (all tracked files, including `adb/` and `.github/`)
   - `reports` → unit-test and lint reports

The workflow: checkout → JDK 17 → Android SDK + licenses → validate wrapper → unit tests → debug APK → lint →
(optional) release APK → collect outputs → source ZIP → upload.

### Signed release APK (optional)

Add these **repository secrets** (Settings → Secrets and variables → Actions). Never commit keystores or passwords.

| Secret | Value |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | `base64 -w0 my-release.keystore` output |
| `RELEASE_KEYSTORE_PASSWORD` | keystore password |
| `RELEASE_KEY_ALIAS` | key alias |
| `RELEASE_KEY_PASSWORD` | key password |

Without them the release step is skipped and the debug APK is still produced. Debug APKs are installable directly.

## B. Local build

Requirements: JDK 17, Android SDK with platform 36 and build-tools 36.0.0 (Android Studio Ladybug or newer installs these).

```bash
./gradlew testDebugUnitTest      # unit tests
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew lintDebug              # lint
```

Open the folder in Android Studio to run on a device.

## C. Install

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

or copy the APK to the phone and open it (allow "Install unknown apps" for your file manager/browser).
Android may show "Play Protect" or "Restricted settings" prompts for sideloaded apps; see PERMISSIONS_GUIDE.md.

## Notes

- If `./gradlew` reports "Permission denied" after a web upload, run `chmod +x gradlew` (the workflow does this itself).
- Dependency versions are pinned in `gradle/libs.versions.toml`.
