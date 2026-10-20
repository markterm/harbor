#!/usr/bin/env bash
# Milestone 0: how many times real time each firmware runs on a phone, per clock, JIT on and off.
#
#   tools/m0-bench.sh DIR_WITH_FWSC_FILES [MS]
#
# Builds native/harbor-bench for the device's ABI, pushes it and the packages to
# /data/local/tmp/harbor, runs them, and writes docs/m0-results/<model>.tsv. ANDROID_SERIAL picks
# the device. Packages aren't in this repository: fetch them from their authors (the catalog's
# "package" URLs, app/src/main/assets/firmwares.json) or the FM-1 Firmware Hub.
#
# `adb shell` runs in the shell's SELinux domain, not an app's; the app's own check is the Audio &
# performance screen ("Compiling hot code (JIT)") and its "Copy a report".
set -euo pipefail
export MSYS_NO_PATHCONV=1 # Git Bash on Windows: leave device paths alone (host paths go through hostpath)
hostpath() { if command -v cygpath >/dev/null; then cygpath -w "$1"; else printf "%s" "$1"; fi; }
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIR="${1:?usage: tools/m0-bench.sh DIR_WITH_FWSC_FILES [MS]}"
MS="${2:-8000}"
SDK="${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}"
ADB=("$SDK/platform-tools/adb")
[ -n "${ANDROID_SERIAL:-}" ] && ADB+=(-s "$ANDROID_SERIAL")
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$SDK/ndk/29.0.14206865}"
CARGO="${CARGO:-$HOME/.cargo/bin/cargo}"

abi="$("${ADB[@]}" shell getprop ro.product.cpu.abi | tr -d '\r')"
case "$abi" in
  arm64-v8a) triple=aarch64-linux-android ;;
  x86_64) triple=x86_64-linux-android ;;
  *) echo "unsupported ABI $abi" >&2; exit 1 ;;
esac
if ! log="$(cd "$ROOT/native" && "$CARGO" ndk -t "$abi" -P 31 build --release -p harbor-bench 2>&1)"; then
  echo "$log" >&2; exit 1
fi
model="$("${ADB[@]}" shell getprop ro.product.model | tr -d '\r' | tr ' /' '__')"
soc="$("${ADB[@]}" shell getprop ro.soc.model | tr -d '\r')"
"${ADB[@]}" shell mkdir -p /data/local/tmp/harbor
"${ADB[@]}" push "$(hostpath "$ROOT/native/target/$triple/release/harbor-bench")" /data/local/tmp/harbor/ >/dev/null
"${ADB[@]}" shell chmod 755 /data/local/tmp/harbor/harbor-bench
files=()
for f in "$DIR"/*.fwsc; do
  "${ADB[@]}" push "$(hostpath "$f")" /data/local/tmp/harbor/ >/dev/null
  files+=("/data/local/tmp/harbor/$(basename "$f")")
done
mkdir -p "$ROOT/docs/m0-results"
out="$ROOT/docs/m0-results/$model.tsv"
{
  printf "# %s (%s, %s), %s ms per run, %s\n" "$model" "$soc" "$abi" "$MS" "$(date -u +%Y-%m-%d)"
  printf "firmware\tMHz\tjit\trealtime\n"
  for jit in "" "--no-jit"; do
    "${ADB[@]}" shell /data/local/tmp/harbor/harbor-bench "${files[@]}" --ms "$MS" --mhz 240,200,160,120 --json $jit \
      | tr -d '\r' | sed -E 's/.*"firmware":"([^"]*)","mhz":([0-9]+),"jit":([a-z]+),"realtime":([0-9.]+).*/\1\t\2\t\3\t\4/'
  done
} | tee "$out"
echo "wrote $out"
