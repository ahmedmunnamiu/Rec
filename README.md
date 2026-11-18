# Advanced Auto Call Recorder

An Android call recorder (Kotlin, Jetpack Compose, Material 3, Hilt, Room, DataStore, Media3, WorkManager) with a
**multi-engine architecture and an Engine Test Center that measures what your phone can really record**.

- Package: `com.ahmedmunna.callrecorder` · min SDK 26 (Android 8.0) · target/compile SDK 36 (Android 16)
- No root needed · works offline · no internet permission · nothing is uploaded
- Built by GitHub Actions: see [BUILD_INSTRUCTIONS.md](BUILD_INSTRUCTIONS.md)

## The honest part first

Since Android 10, **normal apps cannot record the other person's voice from the call stream.** The audio sources
`VOICE_CALL`, `VOICE_UPLINK`, `VOICE_DOWNLINK` and `REMOTE_SUBMIX` need the signature-level permission
`CAPTURE_AUDIO_OUTPUT`. Neither this app nor ADB can obtain it. Some vendor ROMs allow it anyway, so the app **tests
it with real audio instead of assuming** and never labels a recording "two-way" unless a guided call test captured
both people.

On a typical phone the app therefore records through the microphone: your voice clearly, and the other person only
if the call is on speakerphone (which you must allow explicitly). Details: [KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md)
and [RECORDING_ENGINE_GUIDE.md](RECORDING_ENGINE_GUIDE.md).

> Recording calls is regulated by law in many places and often requires everyone's consent. You are responsible for
> using this app lawfully. A notification is always visible while recording; there is no hidden mode.

## Features

| Area | What it does |
|---|---|
| Engines A–N | VOICE_CALL, VOICE_UPLINK, VOICE_DOWNLINK, MIC, VOICE_RECOGNITION, VOICE_COMMUNICATION, DEFAULT, CAMCORDER, UNPROCESSED, REMOTE_SUBMIX (detection), SPEAKERPHONE, BLUETOOTH, ADB (setup), optional ROOT |
| Engine Test Center | Real test recordings, audio analysis (silence, low volume, clipping, distortion), guided two-phase call test, recommendation, manual override, playback of each test |
| Auto recording | Incoming / outgoing / answered / ended / missed detection, foreground microphone service, duplicate prevention, retry with fallback engines, recovery of interrupted recordings |
| Rules | All calls, selected numbers only, unknown only, exclusions, minimum length, auto-favorite |
| Quality | M4A/AAC, WAV, 3GP, AMR-NB, AMR-WB (MP3 shown but disabled: Android has no MP3 encoder); only encoder-supported rates/bitrates are selectable; quality test |
| Storage | `Android/media/<package>/CallRecordings/` or any folder via Storage Access Framework; integrity check; low-storage and limit warnings; cleanup that only *suggests* and always asks before deleting |
| Library | Search (number, name, date, notes, tags, transcript), filters, sorting, folders, favorites, bulk export / delete (with confirmation), contact photos |
| Player | Media3 ExoPlayer, background playback and lock-screen controls, speed, ±10 s, waveform, volume, repeat, resume position, share, rename, notes, tags, trim (new copy), export |
| Privacy | PIN (salted PBKDF2) and biometric lock, private folder, screenshot protection while locked, optional on-device transcription (off by default) |
| Backup | ZIP export/import, scheduled backups with WorkManager |
| Permission Center | Granted / Denied / Restricted / Not supported with explanations and settings shortcuts |
| ADB | `adb/` scripts for Linux/macOS/Windows plus an in-app guide using the real package name |

## Project layout

```
AdvancedCallRecorder/
├── app/src/main/java/com/ahmedmunna/callrecorder/
│   ├── engine/      engines A-M, capture sessions, analysis, verdict, selector, tester
│   ├── root/        optional root backend (isolated)
│   ├── service/     call tracking, recording service, recovery, boot receiver
│   ├── data/        Room database, DataStore settings, repository
│   ├── storage/     file placement, SAF, naming
│   ├── player/      Media3 service, controller, waveform, trimmer
│   ├── backup/ work/ transcribe/ security/ permissions/ adb/ util/ di/
│   └── ui/          Compose screens and theme
├── adb/             setup_adb.sh, setup_adb.bat, reset_adb.sh, check_permissions.sh, README.md
├── .github/workflows/android-build.yml
└── *.md             build, permissions, engine guide, limitations, changelog
```

## Quick start

1. Push the repository to GitHub. The **Android Build** workflow runs automatically.
2. Download `AdvancedCallRecorder-debug-apk` from the run's artifacts and install the APK.
3. Open the app → grant the permissions shown on Home → run the **Engine Test**.
4. Turn on **Auto recording**.

Optional: `./adb/setup_adb.sh` grants the permissions ADB is allowed to grant.

## License

MIT, see [LICENSE](LICENSE).
