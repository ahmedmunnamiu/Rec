# Changelog

## 1.0.0

Initial release.

- 14 recording engines (A–N) with a capability/test framework and an Engine Test Center (real-audio analysis,
  guided two-phase call test, recommendation, manual override).
- Automatic call detection and recording via a microphone foreground service, with duplicate prevention, fallback
  engines, recovery of interrupted recordings and missed-call detection.
- Recording rules (all / selected / unknown, exclusions, minimum length, auto-favorite).
- Quality settings validated against device encoders; recording quality test.
- Room database, DataStore settings, Storage Access Framework, integrity check, cleanup suggestions, ZIP backup/restore, scheduled backup.
- Media3 player (background, lock-screen, speed, waveform, trim, export, share, notes, tags).
- Library with search, filters, sorting, folders, bulk actions.
- PIN + biometric lock, private folder, optional on-device transcription.
- Permission Center, ADB module and scripts, optional isolated root backend.
- GitHub Actions workflow producing debug/release APK and source ZIP; unit tests for the analysis, verdict, selector,
  cleanup, PIN and number-matching logic.

### Build status of this release

Written and statically checked in an environment without the Android SDK or network access, so **no compile or APK
build was possible there**. The first GitHub Actions run is the first real compile.
