#!/usr/bin/env bash
# Advanced Auto Call Recorder - ADB setup
# Grants the permissions ADB is ALLOWED to grant. It cannot grant signature/privileged permissions
# (for example CAPTURE_AUDIO_OUTPUT) and it does not try to bypass any Android security restriction.
#
# Usage:  ./setup_adb.sh [-s <device-serial>] [-p <package>]
PKG="com.ahmedmunna.callrecorder"
SERIAL="${ANDROID_SERIAL:-}"

while getopts "s:p:h" opt; do
  case "$opt" in
    s) SERIAL="$OPTARG" ;;
    p) PKG="$OPTARG" ;;
    h) sed -n '2,7p' "$0"; exit 0 ;;
    *) exit 2 ;;
  esac
done

ok=0; warn=0; fail=0
say()  { printf '%s\n' "$*"; }
good() { printf '  [ OK ] %s\n' "$*"; ok=$((ok+1)); }
bad()  { printf '  [FAIL] %s\n' "$*"; fail=$((fail+1)); }
note() { printf '  [WARN] %s\n' "$*"; warn=$((warn+1)); }

command -v adb >/dev/null 2>&1 || { say "adb not found. Install Android platform-tools: https://developer.android.com/tools/releases/platform-tools"; exit 1; }

say "== Device detection =="
adb start-server >/dev/null 2>&1
mapfile -t DEVICES < <(adb devices | awk 'NR>1 && $2=="device"{print $1}')
UNAUTH=$(adb devices | awk 'NR>1 && $2=="unauthorized"{print $1}')
OFFLINE=$(adb devices | awk 'NR>1 && $2=="offline"{print $1}')
[ -n "$UNAUTH" ]  && say "Unauthorized device(s): $UNAUTH -> unlock the phone and accept the 'Allow USB debugging?' prompt."
[ -n "$OFFLINE" ] && say "Offline device(s): $OFFLINE -> reconnect the cable or run 'adb kill-server && adb start-server'."

if [ -z "$SERIAL" ]; then
  if [ "${#DEVICES[@]}" -eq 0 ]; then
    say "No authorised device found. See adb/README.md for USB and Wireless debugging steps."; exit 1
  elif [ "${#DEVICES[@]}" -gt 1 ]; then
    say "Several devices connected: ${DEVICES[*]}"; say "Re-run with -s <serial>."; exit 1
  fi
  SERIAL="${DEVICES[0]}"
fi
A=(adb -s "$SERIAL")
say "Using device: $SERIAL"
say "Model:   $("${A[@]}" shell getprop ro.product.model | tr -d '\r')"
SDK=$("${A[@]}" shell getprop ro.build.version.sdk | tr -d '\r')
say "Android: $("${A[@]}" shell getprop ro.build.version.release | tr -d '\r') (API $SDK)"

say; say "== Package detection =="
if "${A[@]}" shell pm list packages "$PKG" | tr -d '\r' | grep -qx "package:$PKG"; then
  good "$PKG is installed"
else
  say "  $PKG is not installed. Install the APK first:  adb -s $SERIAL install -r AdvancedCallRecorder-debug.apk"; exit 1
fi

grant() {
  out=$("${A[@]}" shell pm grant "$PKG" "$1" 2>&1 | tr -d '\r')
  if [ -z "$out" ]; then good "granted $1"; else bad "$1 -> $out"; fi
}
appop() {
  out=$("${A[@]}" shell appops set "$PKG" "$1" "$2" 2>&1 | tr -d '\r')
  if [ -z "$out" ]; then good "appops $1 = $2"; else note "appops $1 -> $out (this device may not allow the shell to change it)"; fi
}

say; say "== Runtime permissions (grantable through ADB) =="
grant android.permission.RECORD_AUDIO
grant android.permission.READ_PHONE_STATE
grant android.permission.READ_CALL_LOG
grant android.permission.READ_CONTACTS
if [ "${SDK:-0}" -ge 33 ]; then grant android.permission.POST_NOTIFICATIONS; fi

say; say "== AppOps (where the shell is permitted) =="
appop RECORD_AUDIO allow
appop RUN_ANY_IN_BACKGROUND allow
appop SYSTEM_ALERT_WINDOW allow

say; say "== Battery optimisation exemption =="
out=$("${A[@]}" shell dumpsys deviceidle whitelist +"$PKG" 2>&1 | tr -d '\r')
case "$out" in *Added*|*added*) good "added to the battery-optimisation allowlist" ;; *) note "deviceidle: ${out:-no output}" ;; esac

say; say "== Not grantable through ADB =="
say "  CAPTURE_AUDIO_OUTPUT is a signature/privileged permission. ADB cannot grant it, so VOICE_CALL,"
say "  VOICE_UPLINK, VOICE_DOWNLINK and REMOTE_SUBMIX stay restricted unless your ROM allows them anyway."
say "  Run the Engine Test Center in the app to see what your phone really supports."

say; say "== Summary: $ok ok, $warn warnings, $fail failed =="
[ "$fail" -eq 0 ] || exit 1
