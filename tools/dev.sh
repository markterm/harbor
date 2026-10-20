#!/usr/bin/env bash
# Development loop on an emulator or phone:
#   tools/dev.sh install            build the debug APK and install it
#   tools/dev.sh run                install, then (re)start the app
#   tools/dev.sh shot NAME          screenshot to $SHOTS/NAME.png (default build/shots)
#   tools/dev.sh tap X Y            tap at device pixels
#   tools/dev.sh log                Harbor's log lines since the last start
# ANDROID_SERIAL picks the device when several are connected. VARIANT=profile installs the
# release code signed with the debug key (judge speed with it: debug builds run Kotlin slowly).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}"
ADB=("$SDK/platform-tools/adb")
[ -n "${ANDROID_SERIAL:-}" ] && ADB+=(-s "$ANDROID_SERIAL")
SHOTS="${SHOTS:-$ROOT/build/shots}"
VARIANT="${VARIANT:-debug}"
case "${1:-}" in
  install)
    (cd "$ROOT" && ./gradlew "assemble${VARIANT^}" --console=plain -q)
    "${ADB[@]}" install -r "$ROOT/app/build/outputs/apk/$VARIANT/app-$VARIANT.apk" | tail -1 ;;
  run)
    "$0" install
    "${ADB[@]}" shell am force-stop app.harborsynth
    "${ADB[@]}" logcat -c
    "${ADB[@]}" shell am start -n app.harborsynth/com.markwhite.harbor.MainActivity >/dev/null ;;
  shot)
    mkdir -p "$SHOTS"
    "${ADB[@]}" exec-out screencap -p > "$SHOTS/${2:-shot}.png"
    echo "$SHOTS/${2:-shot}.png" ;;
  tap) "${ADB[@]}" shell input tap "$2" "$3" ;;
  log) "${ADB[@]}" logcat -d | grep -E "harbor|Harbor|AndroidRuntime" || true ;;
  *) sed -n '2,8p' "$0"; exit 2 ;;
esac
