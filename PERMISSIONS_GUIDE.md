# Permissions guide

Permissions are requested only when you use the feature or tap **Grant** in Settings → Permissions. Statuses are
**Granted**, **Denied**, **Restricted** (the system controls it) and **Not supported** (not used / not applicable).

## Runtime permissions

| Permission | Needed for | Notes |
|---|---|---|
| `RECORD_AUDIO` | Any recording | Required |
| `READ_PHONE_STATE` | Detecting ringing / answered / ended | Required |
| `READ_CALL_LOG` | Caller number (Android 9+ only delivers it with this permission) and the number of outgoing calls | Recommended; without it numbers show as Unknown. Restricted on some sideloaded installs |
| `READ_CONTACTS` | Contact names and photos, "unknown numbers only" | Optional |
| `POST_NOTIFICATIONS` (Android 13+) | Recording notification, failure and storage alerts | Required for visible status |
| `WRITE/READ_EXTERNAL_STORAGE` | - | Declared only for old Android (`maxSdkVersion` 28 / 32); recordings use app media storage, so not requested |

## Manifest-declared / special

| Permission | Purpose |
|---|---|
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE` | Microphone foreground service that records during a call |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Background playback with lock-screen controls |
| `MODIFY_AUDIO_SETTINGS` | Speakerphone routing, only with your consent |
| `RECEIVE_BOOT_COMPLETED` | Re-arm maintenance after reboot / update |
| `USE_BIOMETRIC` | Biometric app lock |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Optional: keeps the recorder alive under aggressive battery managers (opens the system dialog) |
| `SYSTEM_ALERT_WINDOW` | Optional: Android 12+ lets apps holding it start the microphone service from the background, improving auto-recording reliability |

## Deliberately NOT used

- `CAPTURE_AUDIO_OUTPUT` (signature-level): not declared, shown as **Restricted**. Cannot be granted by users or ADB.
- `FOREGROUND_SERVICE_PHONE_CALL`: only for apps that manage calls (dialers); this app does not qualify.
- `MANAGE_EXTERNAL_STORAGE`: unnecessary; use the folder picker for another location.
- Accessibility service, notification listener, default dialer role: no feature needs them.
- `INTERNET`: not declared, so nothing can be uploaded.

## Android 12+ background start

Android blocks starting a microphone foreground service from the background unless an exemption applies. When the
phone-state broadcast arrives, the app starts the service immediately. If Android refuses, the app logs a failed
recording ("Background start blocked by Android") and notifies you. Grant the battery and overlay exemptions to
improve reliability; behaviour differs between manufacturers.

## Sideloaded apps on Android 13+

Some permissions (call log, notifications) can be greyed out: open **Settings → Apps → Advanced Auto Call Recorder →
⋮ → Allow restricted settings**, then grant them.
