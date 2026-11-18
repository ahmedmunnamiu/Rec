# ADB setup

The app works without ADB. ADB is a convenience to grant ordinary permissions from a computer and to inspect state.

**What ADB can and cannot do**

- Can: grant runtime permissions (`RECORD_AUDIO`, `READ_PHONE_STATE`, `READ_CALL_LOG`, `READ_CONTACTS`,
  `POST_NOTIFICATIONS`), set AppOps the shell is allowed to change, add the battery-optimisation exemption.
- **Cannot:** grant signature/privileged permissions such as `CAPTURE_AUDIO_OUTPUT`. ADB therefore does **not**
  unlock `VOICE_CALL`, `VOICE_UPLINK`, `VOICE_DOWNLINK` or `REMOTE_SUBMIX`. Nothing here bypasses Android security.

Package name used everywhere: `com.ahmedmunna.callrecorder`

## 1. Install platform-tools

Download Android SDK Platform-Tools from developer.android.com and make `adb` available on your PATH.

## 2. Connect the phone

**USB debugging**
1. Settings → About phone → tap *Build number* 7 times.
2. Settings → System → Developer options → enable *USB debugging*.
3. Connect the cable, accept *Allow USB debugging?* on the phone.
4. `adb devices -l` must show the phone as `device` (not `unauthorized` / `offline`).

**Wireless debugging (Android 11+)**
1. Enable Developer options, then *Wireless debugging* (same Wi-Fi as the computer).
2. *Pair device with pairing code* → note `IP:pairing-port` and the code.
3. `adb pair <ip>:<pairing-port>` and enter the code.
4. `adb connect <ip>:<port>` using the port shown on the Wireless debugging screen.
5. `adb devices -l` should list it.

## 3. Install the APK

```bash
adb install -r AdvancedCallRecorder-debug.apk
```

## 4. Scripts

| Script | Purpose |
|---|---|
| `setup_adb.sh` / `setup_adb.bat` | Detect device and package, grant permissions, set AppOps, battery exemption, print a summary |
| `check_permissions.sh` | Read-only report of permissions, AppOps, battery exemption and debugging state |
| `reset_adb.sh` | Revoke permissions, reset AppOps, remove exemption (`--clear-data` also clears app data, not the audio files) |

```bash
./adb/setup_adb.sh                 # one device connected
./adb/setup_adb.sh -s <serial>     # several devices
./adb/check_permissions.sh
./adb/reset_adb.sh
```

Windows: `adb\setup_adb.bat [serial]`.

## 5. Manual commands

```bash
adb shell pm grant com.ahmedmunna.callrecorder android.permission.RECORD_AUDIO
adb shell pm grant com.ahmedmunna.callrecorder android.permission.READ_PHONE_STATE
adb shell pm grant com.ahmedmunna.callrecorder android.permission.READ_CALL_LOG
adb shell pm grant com.ahmedmunna.callrecorder android.permission.READ_CONTACTS
adb shell pm grant com.ahmedmunna.callrecorder android.permission.POST_NOTIFICATIONS   # Android 13+
adb shell appops set com.ahmedmunna.callrecorder RECORD_AUDIO allow
adb shell appops set com.ahmedmunna.callrecorder RUN_ANY_IN_BACKGROUND allow
adb shell dumpsys deviceidle whitelist +com.ahmedmunna.callrecorder
```

## 6. Troubleshooting

| Symptom | Fix |
|---|---|
| `no devices/emulators found` | Cable/port, enable USB debugging, `adb kill-server && adb start-server` |
| `unauthorized` | Unlock the phone, accept the prompt (revoke USB debugging authorizations and retry if no prompt appears) |
| `offline` | Reconnect, restart adb |
| `more than one device` | Use `-s <serial>` |
| `Security exception: Permission is not a changeable permission type` | The permission is not grantable by ADB (expected for `CAPTURE_AUDIO_OUTPUT`) |
| `Operation not allowed` / `SecurityException` for appops or grant on Xiaomi/Oppo | Enable *USB debugging (Security settings)* in Developer options |
| Package not found | Install the APK first |

The in-app **Settings → ADB Setup** screen shows the same commands, live permission/AppOps status and USB/Wireless
debugging state.
