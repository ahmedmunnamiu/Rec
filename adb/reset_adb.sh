#!/usr/bin/env bash
# Advanced Auto Call Recorder - undo what setup_adb.sh configured.
# Usage: ./reset_adb.sh [-s <device-serial>] [-p <package>] [--clear-data]
#   --clear-data  also wipes the app's data (settings and the recording database). Audio files in
#                 Android/media/<package>/ are NOT removed by this script.
PKG="com.ahmedmunna.callrecorder"
SERIAL="${ANDROID_SERIAL:-}"
CLEAR=0
while [ $# -gt 0 ]; do
  case "$1" in
    -s) SERIAL="$2"; shift 2 ;;
    -p) PKG="$2"; shift 2 ;;
    --clear-data) CLEAR=1; shift ;;
    -h|--help) sed -n '2,6p' "$0"; exit 0 ;;
    *) echo "Unknown option $1"; exit 2 ;;
  esac
done

command -v adb >/dev/null 2>&1 || { echo "adb not found (install Android platform-tools)."; exit 1; }
if [ -z "$SERIAL" ]; then
  mapfile -t DEVICES < <(adb devices | awk 'NR>1 && $2=="device"{print $1}')
  [ "${#DEVICES[@]}" -eq 1 ] || { echo "Need exactly one authorised device (found ${#DEVICES[@]}). Use -s <serial>."; exit 1; }
  SERIAL="${DEVICES[0]}"
fi
A=(adb -s "$SERIAL")
"${A[@]}" shell pm list packages "$PKG" | tr -d '\r' | grep -qx "package:$PKG" || { echo "$PKG is not installed on $SERIAL"; exit 1; }

echo "Resetting $PKG on $SERIAL"
for p in RECORD_AUDIO READ_PHONE_STATE READ_CALL_LOG READ_CONTACTS POST_NOTIFICATIONS; do
  out=$("${A[@]}" shell pm revoke "$PKG" "android.permission.$p" 2>&1 | tr -d '\r')
  [ -z "$out" ] && echo "  revoked $p" || echo "  $p: $out"
done
"${A[@]}" shell appops reset "$PKG" >/dev/null 2>&1 && echo "  AppOps reset to defaults"
"${A[@]}" shell dumpsys deviceidle whitelist -"$PKG" >/dev/null 2>&1 && echo "  removed from the battery-optimisation allowlist"
if [ "$CLEAR" -eq 1 ]; then
  "${A[@]}" shell pm clear "$PKG" | tr -d '\r'
fi
echo "Done. Re-grant with ./setup_adb.sh or from the in-app Permission Center."
