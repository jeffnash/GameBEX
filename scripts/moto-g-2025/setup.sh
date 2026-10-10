#!/usr/bin/env bash
#
# setup.sh: set up a moto g 2025 ("kansas") as a GameBEX handheld over adb, in one go.
#
#   1. Debloat: runs ./debloat.sh with the same options (DEBLOAT=0 skips).
#   2. Installs GameBEX: GAMEBEX_APK=<file>, or the latest GitHub release.
#   3. Grants GameBEX All files access, permission to install emulators, and notifications.
#   4. Installs Shizuku (SHIZUKU_APK=<file>, or the latest official release) and starts it
#      (SHIZUKU=0 skips both).
#   5. Optionally makes GameBEX the home screen (MAKE_HOME=1).
#
# Runs from any Linux or macOS machine with bash, curl and adb. On the phone: Settings >
# About phone > tap "Build number" 7 times, then Settings > System > Developer options >
# USB debugging ON, plug in, and accept the "Allow USB debugging?" prompt.
#
# Usage (start with a dry run):
#   DRY_RUN=1 ./setup.sh     show what it would do; change nothing, download nothing
#   ./setup.sh               do it
#   ./setup.sh shizuku       only (re)start Shizuku, e.g. after the phone rebooted
#   ./setup.sh --help        all options
#
# It never uninstalls anything except through debloat.sh's own list (which asks first and
# writes a restore file). If a GameBEX signed with a different key is already installed,
# it stops and explains instead of uninstalling it.

set -euo pipefail
set -f

die() { printf 'error: %s\n' "$*" >&2; exit 1; }
warn() { printf 'warning: %s\n' "$*" >&2; }
step() { printf '\n== %s\n' "$*"; }

usage() {
  cat <<'EOF'
setup.sh: set up a moto g 2025 as a GameBEX handheld over adb.

Usage:
  ./setup.sh             debloat, install GameBEX + Shizuku, grant permissions, start Shizuku
  ./setup.sh shizuku     only (re)start Shizuku (it stops on every reboot)
  ./setup.sh --help      this text

Start with a dry run:  DRY_RUN=1 ./setup.sh

Options (environment variables, set before the command):
  DRY_RUN=1          Print what would happen; change nothing and download nothing.
  SERIAL=<serial>    Phone to use (see `adb devices`). Default: the only connected device.
  FORCE=1            Run on a phone that isn't a moto g 2025 (GameBEX steps only; debloat still refuses).
  DEBLOAT=0          Skip the debloat step. All debloat.sh options (INCLUDE, EXCLUDE, KEEP, PERF)
                     are passed through to it.
  GAMEBEX_APK=<file> Install this APK instead of downloading the latest GitHub release.
  SHIZUKU=0          Don't install or start Shizuku.
  SHIZUKU_APK=<file> Install this Shizuku APK instead of downloading the latest official release.
  MAKE_HOME=1        Make GameBEX the phone's home screen (GameBEX: System > Device > System launcher
                     switches back).
EOF
}

MODE=all
case "${1:-}" in
"") ;;
shizuku) MODE=shizuku ;;
-h | --help) usage; exit 0 ;;
*) usage >&2; die "unknown argument '$1'" ;;
esac

DRY=${DRY_RUN:-0}
GAMEBEX_PKG=com.gamebex.client
GAMEBEX_REPO=jeffnash/GameBEX
SHIZUKU_PKG=moe.shizuku.privileged.api
SHIZUKU_REPO=RikkaApps/Shizuku
HERE=$(cd "$(dirname "$0")" && pwd)
WORK=""
cleanup() { if [ -n "$WORK" ]; then rm -rf -- "$WORK"; fi; }
trap cleanup EXIT

ADB=$(command -v adb || true)
if [ -z "$ADB" ] && [ -n "${ANDROID_HOME:-}" ] && [ -x "$ANDROID_HOME/platform-tools/adb" ]; then ADB=$ANDROID_HOME/platform-tools/adb; fi
[ -n "$ADB" ] || die "adb not found. Install Android platform-tools (see README.md) and run again."

if [ -z "${SERIAL:-}" ]; then
  READY=$("$ADB" devices | awk -F'\t' 'NR > 1 && $2 == "device" { print $1 }')
  case "$(printf '%s' "$READY" | awk 'NF' | wc -l | tr -d ' ')" in
  1) SERIAL=$READY ;;
  0) die "no authorized phone connected. Plug it in, unlock it, and accept the USB debugging prompt." ;;
  *) die "more than one phone connected; pick one: SERIAL=<serial> ./setup.sh" ;;
  esac
fi
a() { "$ADB" -s "$SERIAL" "$@"; }
ash() { a shell "$@" | tr -d '\r'; }

DEVICE=$(ash getprop ro.product.device)
MODEL=$(ash getprop ro.product.model)
RELEASE=$(ash getprop ro.build.version.release)
printf 'phone  : %s (%s), Android %s, serial %s\n' "$MODEL" "$DEVICE" "$RELEASE" "$SERIAL"
if [ "$DEVICE" != kansas ] && [ "${FORCE:-0}" != 1 ]; then
  die "this isn't a moto g 2025 (ro.product.device=$DEVICE). FORCE=1 runs the GameBEX steps anyway (debloat still refuses)."
fi
[ "$DRY" = 1 ] && echo "mode   : DRY RUN (nothing will be changed or downloaded)"

# latest_apk REPO: the first .apk asset of the repo's latest GitHub release
latest_apk() {
  curl -fsSL "https://api.github.com/repos/$1/releases/latest" 2>/dev/null |
    grep -o '"browser_download_url": *"[^"]*\.apk"' | head -n 1 | sed 's/.*"\(https[^"]*\)"$/\1/'
}

# get_apk FILE REPO LABEL OPTION: use the given file, or download the latest release into $WORK
get_apk() {
  local given=$1 repo=$2 label=$3 option=$4 url out
  if [ -n "$given" ]; then
    [ -f "$given" ] || die "$label APK not found: $given"
    printf '%s\n' "$given"
    return
  fi
  url=$(latest_apk "$repo")
  [ -n "$url" ] || die "couldn't find a $label release at https://github.com/$repo/releases; download the APK yourself and pass it with $option=<file>."
  if [ "$DRY" = 1 ]; then printf '%s\n' "$url"; return; fi
  [ -n "$WORK" ] || WORK=$(mktemp -d "${TMPDIR:-/tmp}/gamebex-setup.XXXXXX")
  out="$WORK/$(basename "$url")"
  curl -fsSL -o "$out" "$url" || die "download failed: $url"
  printf '%s\n' "$out"
}

sha256_of() { if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d" " -f1; else shasum -a 256 "$1" | cut -d" " -f1; fi; }

# install_apk FILE PKG LABEL
install_apk() {
  local file=$1 pkg=$2 label=$3 out
  if [ "$DRY" = 1 ]; then echo "would install $label: $file"; return; fi
  echo "installing $label: $(basename "$file") (sha256 $(sha256_of "$file"))"
  out=$(a install -r "$file" 2>&1 | tr -d '\r') || true
  case "$out" in
  *Success*) echo "  installed" ;;
  *INSTALL_FAILED_UPDATE_INCOMPATIBLE*)
    die "a $label signed with a different key is already installed (for example a self-built debug copy).
Android can't update it in place. Uninstalling it erases $label's own data (for GameBEX: its library and
settings; game files on the SD card stay). If that's OK: $ADB -s $SERIAL uninstall $pkg, then run setup again." ;;
  *) die "installing $label failed: $out" ;;
  esac
}

start_shizuku() {
  local path starter out
  path=$(ash pm path "$SHIZUKU_PKG" | sed -n 's/^package://p' | head -n 1)
  if [ -z "$path" ]; then
    if [ "$DRY" = 1 ]; then echo "would start Shizuku (after installing it)"; return; fi
    die "Shizuku isn't installed."
  fi
  starter="${path%/base.apk}/lib/arm64/libshizuku.so"
  if [ "$DRY" = 1 ]; then echo "would start Shizuku: $starter"; return; fi
  out=$(ash "$starter" 2>&1) || true
  sleep 1
  if ash pidof shizuku_server >/dev/null 2>&1; then
    echo "  Shizuku is running"
  else
    warn "Shizuku didn't report running. Starter output: $(printf '%s' "$out" | tail -n 3 | tr '\n' ' ')
Open the Shizuku app and start it from there."
  fi
}

if [ "$MODE" = shizuku ]; then
  step "Starting Shizuku"
  start_shizuku
  exit 0
fi

if [ "${DEBLOAT:-1}" != 0 ]; then
  step "1/5 Debloat (debloat.sh; asks before removing anything)"
  if [ "$DEVICE" != kansas ]; then
    warn "skipping debloat: it only runs on a moto g 2025."
  else
    SERIAL=$SERIAL DRY_RUN=$DRY "$HERE/debloat.sh"
  fi
fi

step "2/5 GameBEX"
APK=$(get_apk "${GAMEBEX_APK:-}" "$GAMEBEX_REPO" GameBEX GAMEBEX_APK)
install_apk "$APK" "$GAMEBEX_PKG" GameBEX

step "3/5 GameBEX permissions"
if [ "$DRY" = 1 ]; then
  echo "would allow: All files access, installing emulators, notifications"
else
  ash appops set --uid "$GAMEBEX_PKG" MANAGE_EXTERNAL_STORAGE allow >/dev/null 2>&1 || true
  ash appops set "$GAMEBEX_PKG" REQUEST_INSTALL_PACKAGES allow >/dev/null 2>&1 || true
  ash pm grant "$GAMEBEX_PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
  for op in MANAGE_EXTERNAL_STORAGE REQUEST_INSTALL_PACKAGES; do
    printf '  %-26s %s\n' "$op" "$(ash appops get "$GAMEBEX_PKG" "$op" | head -n 1)"
  done
fi

if [ "${SHIZUKU:-1}" != 0 ]; then
  step "4/5 Shizuku"
  if [ -n "$(ash pm path "$SHIZUKU_PKG")" ] && [ -z "${SHIZUKU_APK:-}" ]; then
    echo "  already installed"
  else
    SAPK=$(get_apk "${SHIZUKU_APK:-}" "$SHIZUKU_REPO" Shizuku SHIZUKU_APK)
    install_apk "$SAPK" "$SHIZUKU_PKG" Shizuku
  fi
  start_shizuku
fi

step "5/5 Home screen"
if [ "${MAKE_HOME:-0}" = 1 ]; then
  if [ "$DRY" = 1 ]; then echo "would make GameBEX the home screen"
  else
    ash cmd package set-home-activity "$GAMEBEX_PKG/.MainActivity" || warn "couldn't set the home screen; use GameBEX: System > Device > Set as home screen."
  fi
else
  echo "  skipped (MAKE_HOME=1 makes GameBEX the home screen)"
fi

step "Done"
cat <<EOF
Next, on the phone:
  1. Open GameBEX and finish its first-run screen.
  2. Pair your controller in Settings > Connected devices, then in GameBEX open
     System > Device > Controls & text size > Set up emulators, and allow Shizuku access.
  3. Add games: copy them to GameBEX/<system>/ on the SD card, or import a plugin under
     System > Device > Game sources.
  4. Install emulators from GameBEX's System screen.
Shizuku stops when the phone reboots; plug in and run "./setup.sh shizuku" to start it again
(or start it from the Shizuku app with wireless debugging).
EOF
