#!/usr/bin/env bash
# Advanced Auto Call Recorder - read-only permission / AppOps inspection.
# Usage: ./check_permissions.sh [-s <device-serial>] [-p <package>]
PKG="com.ahmedmunna.callrecorder"
SERIAL="${ANDROID_SERIAL:-}"
while getopts "s:p:h" opt; do
  case "$opt" in s) SERIAL="$OPTARG" ;; p) PKG="$OPTARG" ;; h) sed -n '2,3p' "$0"; exit 0 ;; *) exit 2 ;; esac
done

command -v adb >/dev/null 2>&1 || { echo "adb not found (install Android platform-tools)."; exit 1; }
if [ -z "$SERIAL" ]; then
  mapfile -t DEVICES < <(adb devices | awk 'NR>1 && $2=="device"{print $1}')
  [ "${#DEVICES[@]}" -eq 1 ] || { echo "Need exactly one authorised device (found ${#DEVICES[@]}). Use -s <serial>."; adb devices -l; exit 1; }
  SERIAL="${DEVICES[0]}"
fi
A=(adb -s "$SERIAL")

"${A[@]}" shell pm list packages "$PKG" | tr -d '\r' | grep -qx "package:$PKG" || { echo "$PKG is not installed on $SERIAL"; exit 1; }
echo "Device $SERIAL - package $PKG"
echo; echo "== Permissions =="
DUMP=$("${A[@]}" shell dumpsys package "$PKG" | tr -d '\r')
for p in RECORD_AUDIO READ_PHONE_STATE READ_CALL_LOG READ_CONTACTS POST_NOTIFICATIONS CAPTURE_AUDIO_OUTPUT SYSTEM_ALERT_WINDOW FOREGROUND_SERVICE_MICROPHONE; do
  line=$(printf '%s\n' "$DUMP" | grep -m1 "android.permission.$p")
  if [ -z "$line" ]; then printf '  %-30s not declared / not applicable on this Android version\n' "$p"
  elif printf '%s' "$line" | grep -q "granted=true"; then printf '  %-30s GRANTED\n' "$p"
  else printf '  %-30s NOT granted\n' "$p"; fi
done
echo "  (CAPTURE_AUDIO_OUTPUT is system-only; 'NOT granted' is expected and cannot be changed with ADB.)"

echo; echo "== AppOps =="
"${A[@]}" shell appops get "$PKG" 2>&1 | tr -d '\r' | grep -E "RECORD_AUDIO|RUN_ANY_IN_BACKGROUND|SYSTEM_ALERT_WINDOW|PHONE_CALL|READ_CALL_LOG|POST_NOTIFICATION" || echo "  (no matching AppOps entries reported)"

echo; echo "== Battery optimisation =="
if "${A[@]}" shell dumpsys deviceidle whitelist 2>/dev/null | tr -d '\r' | grep -q "$PKG"; then echo "  exempt"; else echo "  NOT exempt (the system may stop the recorder in the background)"; fi

echo; echo "== Debugging status =="
echo "  adb_enabled=$("${A[@]}" shell settings get global adb_enabled | tr -d '\r')  adb_wifi_enabled=$("${A[@]}" shell settings get global adb_wifi_enabled 2>/dev/null | tr -d '\r')"
