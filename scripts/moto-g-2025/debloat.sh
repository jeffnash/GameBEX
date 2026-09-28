#!/usr/bin/env bash
#
# debloat.sh: remove carrier, ad and preloaded bloat from a moto g 2025
# (codename "kansas", models XT2513-x / XT2513V) over adb, and apply a few
# gaming-friendly display tweaks. No root, no unlocked bootloader needed.
#
# Runs from any Linux or macOS machine with bash and adb (Android
# platform-tools). On the phone: Settings > About phone > tap "Build number"
# 7 times, then Settings > System > Developer options > USB debugging ON,
# plug in, and accept the "Allow USB debugging?" prompt.
#
# Usage (always start with a dry run):
#   DRY_RUN=1 ./debloat.sh              show exactly what would change; change nothing
#   ./debloat.sh                        remove the default groups + perf tweaks (asks "yes")
#   INCLUDE=google,system ./debloat.sh  also remove opt-in groups
#   ./debloat.sh restore FILE           put back every package listed in FILE
#   ./debloat.sh --list                 every group and package, with descriptions
#   ./debloat.sh --help                 all options
#
# What it changes:
#   - Packages are removed for the phone's user only (`pm uninstall --user 0`).
#     System apps stay on the read-only system image; nothing is flashed.
#     Default groups: carrier apps, ad/promo installers, preloaded apps.
#     Opt-in groups: carrier-services, moto, google, system.
#   - Perf tweaks (skip with PERF=0): animation scales 0.5x, and the refresh
#     rate locked at the panel's maximum (120 Hz) via min_refresh_rate.
#   - Real runs write ./debloat-<serial>-<date>.txt (the packages actually
#     removed) and a .log beside it. Dry runs write nothing.
#
# How to undo:
#   ./debloat.sh restore debloat-<serial>-<date>.txt
#     (runs `cmd package install-existing` for each package). Preloaded
#     third-party apps (games, social, shopping) are fully uninstalled by
#     Android; reinstall those from the Play Store if you want them back.
#   Perf tweaks:  adb shell settings put global window_animation_scale 1
#                 (same for transition_animation_scale, animator_duration_scale)
#                 adb shell settings delete system min_refresh_rate
#   A factory reset restores everything.
#
# Never touched, and the script aborts if one ever lands in the removal set:
# device financing and device lock (PayJoy, Device Lock, PAKS), carrier SIM
# unlock (RSU / TFUnlock), telephony, SIM, eSIM, IMS and carrier config,
# OTA and Google Play system updates (mainline / APEX modules), Play Store and
# Play services, keyboard, WebView, launcher, Settings, camera, thermal.
#
# No warranty. You run this on your own phone at your own risk.

set -euo pipefail
set -f # no pathname expansion anywhere: nothing here should ever glob
# errexit/pipefail stop the script on any unexpected failure: always say so
# (ERR covers top-level commands with a line number, EXIT covers the rest).
QUIET_EXIT=""
trap 'rc=$?; QUIET_EXIT=1; echo "error: unexpected failure (exit $rc) at line $LINENO of $0; stopped here, nothing after this point was changed." >&2' ERR
trap 'rc=$?; if [ "$rc" -ne 0 ] && [ -z "$QUIET_EXIT" ]; then echo "error: stopped unexpectedly (exit $rc); nothing after this point was changed." >&2; fi' EXIT

NL='
'

die() { printf 'error: %s\n' "$*" >&2; QUIET_EXIT=1; exit 1; }
quit() { QUIET_EXIT=1; exit "$1"; } # a deliberate non-zero exit (the reason is already printed)
warn() { printf 'warning: %s\n' "$*" >&2; }

usage() {
  cat <<'EOF'
debloat.sh: debloat a moto g 2025 ("kansas") over adb, and apply gaming perf tweaks.

Usage:
  ./debloat.sh                  remove the selected groups + perf tweaks (asks you to type "yes")
  ./debloat.sh restore FILE     reinstall every package listed in FILE (a record from an earlier run)
  ./debloat.sh --list           print every group and package with a description, and the never-touch list
  ./debloat.sh --help           this text

Start with a dry run:  DRY_RUN=1 ./debloat.sh

Options (environment variables, set before the command: DRY_RUN=1 INCLUDE=google ./debloat.sh):
  DRY_RUN=1        Print exactly what would be removed or changed, then stop.
                   Changes nothing on the phone and writes no files.
  SERIAL=<serial>  Phone to use (see `adb devices`). Default: the only connected device.
  INCLUDE=a,b      Add opt-in groups, or INCLUDE=all.
  EXCLUDE=a,b      Drop groups, e.g. EXCLUDE=apps keeps the preloaded apps.
  KEEP=pkg,pkg     Never remove these packages, even if their group is selected.
  PERF=0           Skip the perf tweaks.
  YES=1            Don't ask for the typed "yes" (for scripts; review a dry run first).
  FORCE=1          Run on a phone that does not report ro.product.device=kansas.
                   The lists were built for moto g 2025 firmware: this can break
                   another phone. Read the dry run.
  VERBOSE=1        Also list packages that are already removed or not on this phone.
  RESTORE=FILE     Same as `./debloat.sh restore FILE`.
  ADB=/path/adb    adb binary. Default: adb on PATH, then $ANDROID_HOME/platform-tools,
                   $ANDROID_SDK_ROOT/platform-tools, ~/Library/Android/sdk/platform-tools
                   (macOS), ~/Android/Sdk/platform-tools (Linux).

Groups (./debloat.sh --list shows every package):
  carrier           default  carrier account apps, caller-ID, cloud and carrier diagnostics
  ads               default  ad/promo app installers, lock-screen ads, Play Auto Install config
  apps              default  preloaded games, social, shopping, finance, fitness and weather apps
  carrier-services  opt-in   carrier network add-ons: Wi-Fi calling, visual voicemail, device
                             management, activation, Google Fi. Only for a phone with no SIM,
                             or if you are sure your carrier does not use them.
  moto              opt-in   Motorola extras: help/feedback, crash and usage reporting, demo mode,
                             Moto Unplugged, AI Key (power-key shortcuts), Recorder, FM radio, widget
  google            opt-in   Google apps: YouTube, YT Music, Google TV, Maps, Gmail, Drive, Calendar,
                             Meet, Wallet, Home, Assistant, Google app (search bar, Circle to Search),
                             Family Link, Android Auto, ARCore
  system            opt-in   small Android extras: easter egg, screen savers, system tracing,
                             partner bookmarks, print suggestions, Switch Access

Perf tweaks (PERF=0 skips them; before and after values are printed):
  global window_animation_scale, transition_animation_scale, animator_duration_scale -> 0.5
  system peak_refresh_rate and min_refresh_rate -> the panel's max (120 Hz on this phone).
    peak_refresh_rate alone only allows 120 Hz and the screen drops to 60 Hz when idle;
    min_refresh_rate is what locks it (same as Developer options > Force peak refresh rate).
    Costs some battery.

Files (real runs only, in the current directory):
  debloat-<serial>-<date>.txt   packages actually removed, one per line: the restore list
  debloat-<serial>-<date>.log   every adb command's output

Undo:
  ./debloat.sh restore debloat-<serial>-<date>.txt
  one package:  adb shell cmd package install-existing <package>
  Preloaded third-party apps (games, social, shopping) are fully uninstalled by Android,
  so restore can't bring them back: reinstall them from the Play Store.
  Perf:  adb shell settings put global window_animation_scale 1
         adb shell settings put global transition_animation_scale 1
         adb shell settings put global animator_duration_scale 1
         adb shell settings delete system min_refresh_rate
  A factory reset restores everything.

Never touched: see ./debloat.sh --list. The script aborts if any never-touch package, or
any package the phone ships inside a mainline (APEX) module, is in the removal set.

Exit status: 0 done; 1 nothing changed (bad option, no/wrong device, not confirmed);
2 ran, but something failed (listed at the end and in the .log).
EOF
}

# ---------------------------------------------------------------- package lists
# One package per line: "<package> <description>". Lines starting with # are
# comments. "(protected)" = moto g 2025 firmware refuses to remove it; the
# script reports it as protected, not as an error.

group_list() {
  case "$1" in
  carrier) cat <<'EOF'
com.att.csoiam.mobilekey                  AT&T Sign-in Helper
com.att.dh                                AT&T Device Help
com.att.iqi                               AT&T IQI diagnostics (Carrier IQ)
com.att.personalcloud                     AT&T Personal Cloud
com.vzw.hss.myverizon                     My Verizon
com.vzw.ecid                              Verizon Call Filter
com.securityandprivacy.android.verizon.vms  Verizon Digital Secure
com.vcast.mediamanager                    Verizon Cloud
com.motorola.vzw.cloudsetup               Verizon Cloud setup prompt
com.tmobile.echolocate                    T-Mobile diagnostics
com.tmobile.echolocate.system             T-Mobile diagnostics (traffic stats)
com.tmobile.pr.adapt                      T-Mobile Adapt diagnostics
com.tmobile.m1                            T-Mobile news feed (-1 screen)
com.metropcs.metrozone                    Metro by T-Mobile MetroZone
com.cricketwireless.minus                 Cricket news feed (-1 screen)
com.cricketwireless.thescoop              Cricket The Scoop
com.mizmowireless.acctmgt                 myCricket
com.uscc.ecid                             UScellular Call Guardian
cci.usage                                 My Consumer Cellular
com.tracfone.generic.mysites              Tracfone / Straight Talk / Total bookmarks
com.swishme.tracfone                      Tracfone / Straight Talk / Total dashboard
com.tracfone.preload.accountservices      Tracfone / Straight Talk / Total My Account (protected)
EOF
  ;;
  ads) cat <<'EOF'
com.aura.oobe.motorola                    ironSource Aura app installer (Motorola)
com.aura.oobe.solutions                   ironSource Aura app services (game installer)
com.aura.jet.att                          AT&T app installer (ironSource)
com.aura.oobe.dish                        Boost App Cloud installer (ironSource)
com.aura.news.hashtag.news                Boost news feed (ironSource)
com.ironsrc.aura.tmo                      T-Mobile App Selector installer (ironSource)
com.dti.tracfone                          Digital Turbine sponsored-app installer (Tracfone)
com.dti.folderlauncher                    Games Hub folder (Digital Turbine)
com.digitalturbine.android.apps.news.uscellular  UScellular news widget (Digital Turbine)
com.dish.wireless.installer               Boost app installer
com.huub.tiger                            "The Daily" news feed (Tracfone)
com.glance.lockscreenM                    Glance lock-screen ads
com.inmobi.weather                        InMobi weather (ads)
com.adfone.aditup                         Ad It Up (watch ads for bill credit)
com.motorola.playautoinstallext           Play Auto Install extension (pushes apps at setup)
android.autoinstalls.config.motorola.layout  Play Auto Install config (apps pushed at setup)
com.facebook.appmanager                   Meta App Manager
com.facebook.services                     Meta Services
com.facebook.system                       Meta App Installer
com.meta.attribution                      Meta install attribution
com.amazon.appmanager                     Amazon app manager (protected)
EOF
  ;;
  apps) cat <<'EOF'
com.facebook.katana                       Facebook
com.instagram.android                     Instagram
com.zhiliaoapp.musically                  TikTok
com.king.candycrushsaga                   Candy Crush Saga
com.tripledot.solitaire                   Solitaire (Tripledot)
com.vitastudio.mahjong                    Vita Mahjong
com.block.juggle                          Block Blast
com.oakever.arrows                        Amaze GO!
com.puzzlegames.puzzlebrickslegend        Puzzle Bricks Legend
wool.match.color.sort.jam.puzzle          Wool Jam
com.einnovation.temu                      Temu
com.onedebit.chime                        Chime
com.fitbit.FitbitMobile                   Fitbit
com.handmark.expressweather               1Weather
EOF
  ;;
  carrier-services) cat <<'EOF'
com.att.mobile.android.vvm                AT&T Visual Voicemail
com.motorola.att.phone.extensions         AT&T dialer extensions
com.motorola.att.sim.extensions           AT&T SIM setup extensions
com.motorola.attvowifi                    AT&T Wi-Fi Calling
com.motorola.omadm.att                    AT&T device management (OMA-DM)
com.motorola.omadm.vzw                    Verizon device management (OMA-DM)
com.motorola.omadm.dish                   Boost device management (protected)
com.motorola.vzw.pco.extensions.pcoreceiver  Verizon account-status (PCO) receiver
com.motorola.vzw.phone.extensions         Verizon dialer extensions
com.motorola.vzw.provider                 Verizon unified settings
com.motorola.vzw.settings.extensions      Verizon settings extensions
com.motorola.vzw.voicemail.extensions     Verizon voicemail extensions
com.motorola.visualvoicemail              Visual Voicemail (Verizon)
com.verizon.mips.services                 My Verizon Services (Verizon hotspot needs it)
com.motorola.comcastext                   Xfinity Mobile activation
com.motorola.comcast.settings.extensions  Xfinity Mobile settings
com.xfinitymobile.cometcarrierservice     Xfinity Mobile carrier service
com.motorola.spectrum.setup.extensions    Spectrum Mobile setup
com.spectrum.cm.headless                  Spectrum Mobile connection manager (Wi-Fi offload)
com.motorola.sprint.setupext              Sprint setup extension
com.motorola.tmo.settingsext              T-Mobile settings extensions
com.motorola.tmo.setupext                 T-Mobile setup extensions
com.dish.vvm                              Boost Visual Voicemail
com.dish.wireless.activation              Boost activation (protected)
com.dish.wireless.carrierapp              Boost carrier app (protected)
com.google.android.apps.tycho             Google Fi
com.google.android.apps.carrier.carrierwifi  Carrier Wi-Fi / OpenRoaming
com.google.android.apps.cbrsnetworkmonitor   CBRS network monitor
EOF
  ;;
  moto) cat <<'EOF'
com.motorola.demo                         Retail demo mode
com.motorola.motocare                     Moto Care (old support app)
com.motorola.genie                        Device Help
com.motorola.help                         Moto Feedback
com.motorola.help.extlog                  Moto Feedback log collector
com.motorola.bug2go                       Bug2Go crash reporter
com.motorola.android.nativedropboxagent   Crash-log (DropBox) uploader
com.motorola.wifi.motowifimetrics         Wi-Fi usage metrics
com.motorola.lifetimedata                 Lifetime usage counters
com.motorola.iotservice                   Moto IoT service
com.motorola.ccc.notification             Motorola Notifications (tips, offers, surveys)
com.motorola.appforecast                  App forecast / cleanup
com.motorola.smartfeed                    Moto smart feed
com.motorola.brapps                       Motorola App Box (app suggestions)
com.motorola.timeweatherwidget            Time & Weather widget
com.motorola.om                           Moto Unplugged (focus mode)
com.motorola.aiservices                   Moto AI services
com.motorola.mykey                        AI Key (power-key shortcuts)
com.motorola.audiorecorder                Recorder app
com.motorola.fmplayer                     FM Radio app
EOF
  ;;
  google) cat <<'EOF'
com.google.android.youtube                YouTube
com.google.android.apps.youtube.music     YouTube Music
com.google.android.videos                 Google TV
com.google.android.apps.maps              Google Maps
com.google.android.gm                     Gmail
com.google.android.apps.docs              Google Drive
com.google.android.calendar               Google Calendar
com.google.android.apps.tachyon           Google Meet
com.google.android.apps.walletnfcrel      Google Wallet (tap to pay)
com.google.android.apps.chromecast.app    Google Home
com.google.android.apps.googleassistant   Google Assistant
com.google.android.googlequicksearchbox   Google app (search bar, Circle to Search, Discover)
com.android.hotwordenrollment.okgoogle    "Hey Google" voice match
com.android.hotwordenrollment.xgoogle     "Hey Google" voice match
com.google.android.gms.location.history   Google Location History stub
com.google.android.gms.supervision        Family Link parental controls
com.google.ambient.streaming              Stream apps to a Chromebook
com.google.android.projection.gearhead    Android Auto
com.google.ar.core                        ARCore (AR games such as Pokemon GO use it)
com.google.android.feedback               Google crash-report feedback
EOF
  ;;
  system) cat <<'EOF'
com.android.egg                           Android version easter egg
com.android.dreams.basic                  Basic screen savers
com.android.traceur                       System Tracing (developer tool)
com.android.providers.partnerbookmarks    Partner bookmarks for Chrome
com.google.android.printservice.recommendation  Print service suggestions
com.google.android.accessibility.switchaccess   Switch Access (accessibility)
EOF
  ;;
  *) return 1 ;;
  esac
}

group_about() {
  case "$1" in
  carrier)          echo "carrier account apps, caller-ID, cloud and carrier diagnostics" ;;
  ads)              echo "ad/promo app installers, lock-screen ads, Play Auto Install config" ;;
  apps)             echo "preloaded games, social, shopping, finance, fitness and weather apps" ;;
  carrier-services) echo "carrier network add-ons (Wi-Fi calling, voicemail, device management, activation); no-SIM phones only" ;;
  moto)             echo "Motorola extras: help, feedback, crash/usage reporting, demo, Unplugged, AI Key, Recorder, FM radio" ;;
  google)           echo "Google apps (YouTube, Maps, Gmail, Drive, Assistant, Google app, Android Auto, Wallet, ...)" ;;
  system)           echo "small Android extras (easter egg, screen savers, tracing, Switch Access, print suggestions)" ;;
  esac
}

ALL_GROUPS="carrier ads apps carrier-services moto google system"
DEFAULT_GROUPS="carrier ads apps"

# Packages this script must never remove, whatever the lists say. Removing
# these can lock a financed phone, break SIM unlock, calls, data, eSIM,
# emergency calling, updates, Play, typing, web views, the launcher or camera.
never_touch() {
  cat <<'EOF'
# device financing / device lock / carrier SIM lock
com.payjoy.access                         PayJoy device financing lock
com.motorola.android.overlay.payjoy       PayJoy framework overlay
com.android.devicelockcontroller          Android Device Lock (financed phones)
com.google.android.devicelockcontroller   Android Device Lock (financed phones)
com.motorola.paks                         Motorola PAKS finance lock
com.motorola.paks.notification            Motorola PAKS finance notifications
com.verizon.remoteSimlock                 Verizon remote SIM lock / unlock
com.motorola.extensions.tracfone          Tracfone carrier unlock (#TFUNLOCK# menu)
com.motorola.tracfone.rsu                 Tracfone remote SIM unlock
com.mediatek.rsu                          MediaTek remote SIM unlock service
com.mediatek.rsu.tfn                      Tracfone remote SIM lock service
com.tmobile.rsuapp                        T-Mobile Device Unlock
com.tmobile.rsusrv                        T-Mobile remote SIM unlock service
com.tmobile.rsuadapter.aidl               T-Mobile remote SIM unlock adapter
com.motorola.enterprise.service           ThinkShield device management
com.motorola.enterprise.adapter.service   ThinkShield device management adapter
com.android.managedprovisioning           Work profile / device-owner provisioning
# telephony, SIM, eSIM, IMS, carrier config, emergency alerts
com.android.phone                         Phone service
com.android.stk                           SIM toolkit
com.android.simappdialog                  SIM app dialog
com.android.carrierdefaultapp             Carrier captive portal / data notices
com.android.carrierconfig                 Carrier config
com.android.providers.telephony           Telephony provider (APNs, SMS)
com.android.mms.service                   MMS service
com.android.imsserviceentitlement         IMS entitlement (VoLTE / Wi-Fi calling)
com.google.android.ims                    Carrier Services (IMS)
com.google.android.carrier                Carrier Settings (VoLTE / 5G / APN config)
com.google.android.wfcactivation          Wi-Fi calling, IMS and emergency calling setup
com.google.android.apps.scone             Connectivity services (LTE / 5G switching)
com.google.android.euicc                  eSIM manager
com.oem.euiccpartnerapp                   eSIM partner app
com.mediatek.ims                          MediaTek IMS (VoLTE)
com.mediatek.op12.ims                     Verizon IMS
com.mediatek.carrierexpress               Per-SIM carrier config switching
com.mediatek.gbaservice                   GBA authentication (IMS)
com.motorola.carrierconfig                Motorola carrier config
com.motorola.carriersettingsext           Motorola carrier settings
com.motorola.msimsettings                 SIM settings
com.motorola.entitlement                  Hotspot / feature entitlement
com.motorola.android.provisioning         OMA provisioning
com.motorola.omadm.service                Device management service
com.motorola.tracfone.setup               Tracfone activation
com.motorola.setupwizard.controller       Verizon activation
com.motorola.setupwizard.devicesetup      Verizon activation
com.motorola.setupwizard.phoneservice     Verizon activation
com.vzw.apnlib                            Verizon APN library
com.android.cellbroadcastreceiver         Emergency alerts
com.google.android.cellbroadcastreceiver  Emergency alerts
com.google.android.cellbroadcastservice   Emergency alerts
# OTA and Google Play system updates (mainline)
com.motorola.ccc.ota                      Motorola system updates (OTA)
com.motorola.ccc.devicemanagement         Motorola device management (OTA)
com.motorola.installer                    Motorola update / app installer
com.google.android.modulemetadata         Mainline module metadata
com.google.mainline.telemetry             Play system update version tracking
com.google.mainline.adservices            AdServices mainline train version
com.google.android.adservices.api         AdServices mainline module
com.google.android.ondevicepersonalization.services  On-device personalization mainline module
com.google.android.federatedcompute       Federated compute mainline module
com.google.android.configupdater          Security config updates
# Play Store, Play services, installer, permissions
com.android.vending                       Google Play Store
com.google.android.gms                    Google Play services
com.google.android.gsf                    Google Services Framework
com.google.android.packageinstaller       Package installer
com.google.android.permissioncontroller   Permission controller
com.google.android.ext.services           Android system services
com.google.android.ext.shared             Android shared library
com.google.android.apps.wellbeing         Digital Wellbeing (Settings depends on it)
# keyboard, web, UI, camera, thermal, audio, game mode
com.google.android.inputmethod.latin      Gboard keyboard
com.google.android.webview                Android System WebView
com.android.chrome                        Chrome
android                                   Android framework
com.android.systemui                      System UI
com.android.settings                      Settings
com.motorola.launcher3                    Moto launcher
com.motorola.camera3                      Moto Camera
com.motorola.camera3.content.ai           Moto Camera AI
com.android.cameraextensions              Camera extensions
com.motorola.thermalservice               Thermal service
com.dolby.daxservice                      Dolby audio
com.motorola.dolby.dolbyui                Dolby audio settings
com.motorola.gamemode                     Moto Game Mode
EOF
}

# ---------------------------------------------------------------- helpers

strip_list() { awk 'NF && $1 !~ /^#/'; }
pkgs_only() { strip_list | awk '{print $1}'; }
count() { if [ -z "$1" ]; then echo 0; else printf '%s\n' "$1" | awk 'END {print NR}'; fi; }

# has LIST ITEM: is ITEM one of the newline-separated LIST's lines?
has() { case "$NL$1$NL" in *"$NL$2$NL"*) return 0 ;; esac; return 1; }
# in_words WORDS ITEM: is ITEM one of the space-separated WORDS?
in_words() { case " $1 " in *" $2 "*) return 0 ;; esac; return 1; }
# words LIST: a newline-separated list on one line
words() { printf '%s\n' "$1" | paste -sd ' ' -; }
# indent LIST [PREFIX]: print a newline-separated list, indented
indent() { if [ -n "$1" ]; then printf '%s\n' "$1" | sed "s/^/${2:-  }/"; fi; }

PKG_RE='^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$'
valid_pkg() { [[ $1 =~ $PKG_RE ]]; }

# split "a,b c" into one item per line
split_items() {
  local IFS=', 	' items item
  read -r -a items <<<"$1" || true
  for item in ${items[@]+"${items[@]}"}; do printf '%s\n' "$item"; done
}

desc_of() { # desc_of PKG LIST_TEXT
  awk -v p="$1" '$1 == p { $1 = ""; sub(/^ +/, ""); print; exit }' <<<"$2"
}

# numeric compare of two settings values; "null" never matches
same_value() { awk -v a="$1" -v b="$2" 'BEGIN { exit !(a != "null" && a != "" && a + 0 == b + 0) }'; }

# ---------------------------------------------------------------- options

for v in DRY_RUN YES FORCE PERF VERBOSE; do
  case "${!v:-}" in
  '' | 0 | 1) ;;
  *) die "$v must be 0 or 1 (got '${!v}')" ;;
  esac
done
DRY=${DRY_RUN:-0}
[ -n "$DRY" ] || DRY=0
DO_PERF=${PERF:-1}
[ -n "$DO_PERF" ] || DO_PERF=1

MODE=debloat
RESTORE_FILE=${RESTORE:-}
case "${1:-}" in
-h | --help | help) usage; exit 0 ;;
--list | list) MODE=list ;;
restore)
  [ $# -ge 2 ] || die "usage: ./debloat.sh restore FILE"
  RESTORE_FILE=$2 ;;
'') ;;
*) die "unknown argument '$1' (see ./debloat.sh --help)" ;;
esac
if [ $# -gt 2 ] || { [ $# -eq 2 ] && [ "$1" != restore ]; }; then
  die "too many arguments (see ./debloat.sh --help)"
fi
[ -z "$RESTORE_FILE" ] || MODE=restore

# ---------------------------------------------------------------- list integrity
# Hard guard: no removal group may ever contain a never-touch package.

NEVER=$(never_touch | pkgs_only)
NEVER_TEXT=$(never_touch | strip_list)
check_lists() {
  local g p bad=""
  for g in $ALL_GROUPS; do
    for p in $(group_list "$g" | pkgs_only); do
      valid_pkg "$p" || die "internal list error: bad package name '$p' in group $g"
      if has "$NEVER" "$p"; then bad="$bad $p($g)"; fi
    done
  done
  [ -z "$bad" ] || die "internal list error: never-touch package(s) in a removal group:$bad. Refusing to run."
}
check_lists

if [ "$MODE" = list ]; then
  for g in $ALL_GROUPS; do
    if in_words "$DEFAULT_GROUPS" "$g"; then kind=default; else kind=opt-in; fi
    printf '\n%s (%s): %s\n' "$g" "$kind" "$(group_about "$g")"
    group_list "$g" | strip_list | while read -r p d; do printf '  %-48s %s\n' "$p" "$d"; done
  done
  printf '\nnever-touch (the script refuses to remove these, and anything inside a mainline/APEX module):\n'
  never_touch | while IFS= read -r line; do
    case "$line" in
    '#'*) printf '  %s\n' "$line" ;;
    ?*) read -r p d <<<"$line"; printf '  %-48s %s\n' "$p" "$d" ;;
    esac
  done
  exit 0
fi

# ---------------------------------------------------------------- adb + device

find_adb() {
  local d
  if [ -n "${ADB:-}" ]; then
    [ -x "$ADB" ] || die "ADB=$ADB is not an executable file"
    ADB_BIN=$ADB; return
  fi
  if command -v adb >/dev/null 2>&1; then ADB_BIN=$(command -v adb); return; fi
  for d in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "${HOME:-}/Library/Android/sdk" "${HOME:-}/Android/Sdk"; do
    if [ -n "$d" ] && [ -x "$d/platform-tools/adb" ]; then ADB_BIN="$d/platform-tools/adb"; return; fi
  done
  die "adb not found. Install Android platform-tools
  (https://developer.android.com/tools/releases/platform-tools; macOS: brew install android-platform-tools;
  Debian/Ubuntu: sudo apt install adb), then put adb on PATH, set ANDROID_HOME, or set ADB=/path/to/adb."
}

pick_device() {
  local lines ready n state
  lines=$("$ADB_BIN" devices 2>/dev/null | tr -d '\r' | awk -F'\t' 'NF >= 2 { print $1 "\t" $2 }') ||
    die "'adb devices' failed (try: $ADB_BIN kill-server, then run again)"
  if [ -n "${SERIAL:-}" ]; then
    state=$(awk -F'\t' -v s="$SERIAL" '$1 == s { print $2; exit }' <<<"$lines")
    [ -n "$state" ] || die "device '$SERIAL' is not connected. Connected: $(awk -F'\t' 'NF { printf "%s%s (%s)", sep, $1, $2; sep = ", " }' <<<"$lines")"
  else
    ready=$(printf '%s\n' "$lines" | awk -F'\t' '$2 == "device" { print $1 }')
    n=$(count "$ready")
    if [ "$n" = 1 ]; then
      SERIAL=$ready; state=device
    elif [ "$n" = 0 ]; then
      if [ -z "$lines" ]; then
        die "no phone found. Plug it in with a data cable and turn on USB debugging
  (Settings > About phone > tap Build number 7 times; Settings > System > Developer options > USB debugging)."
      fi
      SERIAL=$(printf '%s\n' "$lines" | awk -F'\t' 'NR == 1 { print $1 }')
      state=$(printf '%s\n' "$lines" | awk -F'\t' 'NR == 1 { print $2 }')
    else
      die "$n devices connected: $(words "$ready"); pick one: SERIAL=<serial> ./debloat.sh"
    fi
  fi
  case "$state" in
  device) ;;
  unauthorized) die "phone $SERIAL has not authorized this computer. Unlock it and accept the
  \"Allow USB debugging?\" prompt (tick \"Always allow from this computer\"), then run again.
  No prompt? Unplug and replug, or Developer options > Revoke USB debugging authorizations." ;;
  offline) die "phone $SERIAL is offline. Replug the cable, or run: $ADB_BIN kill-server" ;;
  "no permissions"*) die "no USB permission for the phone. Linux needs udev rules for Android devices
  (e.g. the android-udev package; see https://developer.android.com/studio/run/device)." ;;
  *) die "phone $SERIAL is in state '$state'; it must be booted into Android ('device')." ;;
  esac
}

adb_s() { "$ADB_BIN" -s "$SERIAL" "$@"; }

# ash CMD...: adb shell, CRs stripped; returns adb's status
ash() {
  local out rc=0
  out=$(adb_s shell "$@" 2>&1) || rc=$?
  printf '%s\n' "$out" | tr -d '\r'
  return "$rc"
}

pm_list() {
  local out
  out=$(ash pm list packages "$@") || die "'pm list packages $*' failed: $out"
  printf '%s\n' "$out" | sed -n 's/^package://p'
}

PROPS=""
prop() { awk -v k="[$1]: [" 'index($0, k) == 1 { v = substr($0, length(k) + 1); sub(/\]$/, "", v); print v; exit }' <<<"$PROPS"; }

read_identity() {
  PROPS=$(ash getprop) || die "could not read the phone's properties: $PROPS"
  DEVICE=$(prop ro.product.device)
  MODEL=$(prop ro.product.model)
  PRODUCT=$(prop ro.product.name)
  RELEASE=$(prop ro.build.version.release)
  SDK=$(prop ro.build.version.sdk)
  CARRIER=$(prop ro.carrier)
  FINGERPRINT=$(prop ro.build.fingerprint)
  printf 'phone  : %s  %s (%s / %s), Android %s (SDK %s), carrier "%s"\n' \
    "$SERIAL" "$MODEL" "$DEVICE" "$PRODUCT" "$RELEASE" "$SDK" "${CARRIER:-?}"
}

check_device() {
  case "$DEVICE" in
  kansas | kansas_g) return 0 ;;
  esac
  if [ "${FORCE:-0}" != 1 ]; then
    die "this phone reports ro.product.device='$DEVICE' ($MODEL), not 'kansas' (moto g 2025).
  These lists were built for moto g 2025 firmware only. Set FORCE=1 to run anyway (dangerous)."
  fi
  cat >&2 <<EOF
!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
!! FORCE=1: this phone is NOT a moto g 2025 (ro.product.device=$DEVICE).
!! The package lists were built for moto g 2025 firmware. On another phone
!! they can remove something that phone needs. Read the plan line by line.
!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
EOF
}

confirm() {
  local ans
  if [ "${YES:-0}" = 1 ]; then return 0; fi
  [ -t 0 ] || die "no terminal to confirm on. Review DRY_RUN=1 output, then re-run with YES=1."
  printf '\nType yes to %s: ' "$1"
  read -r ans || ans=""
  if [ "$ans" != yes ]; then
    echo "Aborted; nothing changed."
    quit 1
  fi
}

LOG=""
log() { if [ -n "$LOG" ]; then printf '%s %s\n' "$(date +%H:%M:%S)" "$*" >>"$LOG"; fi; }

new_base() { # new_base PREFIX: unique "<prefix>-<serial>-<date>" in the current directory
  local s stamp base n=1
  s=$(printf '%s' "$SERIAL" | tr -c 'A-Za-z0-9._-' '_')
  stamp=$(date +%Y%m%d-%H%M%S)
  base="$1-$s-$stamp"
  while [ -e "$base.txt" ] || [ -e "$base.log" ]; do n=$((n + 1)); base="$1-$s-$stamp-$n"; done
  printf '%s' "$base"
}

# ---------------------------------------------------------------- restore mode

do_restore() {
  local file=$RESTORE_FILE line p rest n=0 bad="" rec_serial
  local to="" already="" absent="" ok="" failed="" out
  if [ ! -f "$file" ] || [ ! -r "$file" ]; then die "can't read restore file '$file'"; fi
  while IFS= read -r line || [ -n "$line" ]; do
    n=$((n + 1))
    line=${line%%#*}
    p="" rest=""
    read -r p rest <<<"$line" || true
    [ -n "$p" ] || continue
    if valid_pkg "$p" && [ -z "$rest" ]; then
      if ! has "$to" "$p"; then to="${to:+$to$NL}$p"; fi
    else
      bad="$bad  line $n: '$line'$NL"
    fi
  done <"$file"
  [ -z "$bad" ] || die "'$file' has invalid package names (not restoring anything):$NL$bad"
  [ -n "$to" ] || die "'$file' lists no packages"

  find_adb
  pick_device
  echo "mode   : restore from $file$( [ "$DRY" = 1 ] && echo '  (DRY RUN: nothing will change)')"
  read_identity
  rec_serial=$(sed -n 's/^# serial: *\([^ ]*\).*/\1/p' "$file")
  rec_serial=${rec_serial%%"$NL"*}
  if [ -n "$rec_serial" ] && [ "$rec_serial" != "$SERIAL" ]; then
    warn "this record is from phone $rec_serial, not $SERIAL. Restoring is harmless, but check it's the file you meant."
  fi

  local installed known todo=""
  installed=$(pm_list --user 0) || quit 1
  known=$(pm_list -u --user 0) || quit 1
  for p in $to; do
    if has "$installed" "$p"; then already="${already:+$already$NL}$p"
    elif has "$known" "$p"; then todo="${todo:+$todo$NL}$p"
    else absent="${absent:+$absent$NL}$p"; fi
  done

  echo
  if [ -n "$todo" ]; then
    echo "$( [ "$DRY" = 1 ] && echo 'would restore' || echo 'to restore') ($(count "$todo")):"
    indent "$todo"
  fi
  if [ -n "$already" ]; then echo "already installed, nothing to do ($(count "$already")):"; indent "$already"; fi
  if [ -n "$absent" ]; then
    echo "not on this phone, can't be restored with install-existing ($(count "$absent")); reinstall from the Play Store:"
    for p in $absent; do printf '  %-44s https://play.google.com/store/apps/details?id=%s\n' "$p" "$p"; done
  fi
  if [ -z "$todo" ]; then echo; echo "Nothing to restore."; exit 0; fi
  if [ "$DRY" = 1 ]; then
    echo; echo "DRY RUN: would run 'cmd package install-existing <package>' for the $(count "$todo") package(s) above. Nothing changed."
    exit 0
  fi
  confirm "restore $(count "$todo") package(s) on $SERIAL"

  LOG="$(dirname "$file")/$(basename "$file" .txt)-restore-$(date +%Y%m%d-%H%M%S).log"
  : >>"$LOG" || die "can't write log $LOG"
  log "restore from $file on $SERIAL ($MODEL, $FINGERPRINT)"
  echo
  for p in $todo; do
    out=$(ash cmd package install-existing "$p") || true
    log "$p: $out"
    case "$out" in
    *"installed for user"*) ok="${ok:+$ok$NL}$p"; printf '  restored  %s\n' "$p" ;;
    *) failed="${failed:+$failed$NL}$p"; printf '  FAILED    %s (%s)\n' "$p" "$(printf '%s' "$out" | tr '\n' ' ')" ;;
    esac
  done
  echo
  echo "restored $(count "$ok"), failed $(count "$failed"). Log: $LOG"
  if [ -n "$failed" ]; then quit 2; fi
  exit 0
}

if [ "$MODE" = restore ]; then do_restore; fi

# ---------------------------------------------------------------- debloat mode

# groups
SELECTED=$DEFAULT_GROUPS
for g in $(split_items "${INCLUDE:-}"); do
  if [ "$g" = all ]; then SELECTED=$ALL_GROUPS; continue; fi
  in_words "$ALL_GROUPS" "$g" || die "unknown group '$g' in INCLUDE (groups: $ALL_GROUPS)"
  in_words "$SELECTED" "$g" || SELECTED="$SELECTED $g"
done
for g in $(split_items "${EXCLUDE:-}"); do
  in_words "$ALL_GROUPS" "$g" || die "unknown group '$g' in EXCLUDE (groups: $ALL_GROUPS)"
  SELECTED=$(for s in $SELECTED; do [ "$s" = "$g" ] || printf '%s ' "$s"; done)
done
# keep the canonical order
SELECTED=$(for g in $ALL_GROUPS; do if in_words "$SELECTED" "$g"; then printf '%s ' "$g"; fi; done)
SELECTED=${SELECTED% }
KEEP_LIST=""
for p in $(split_items "${KEEP:-}"); do
  valid_pkg "$p" || die "KEEP: '$p' is not a package name"
  KEEP_LIST="${KEEP_LIST:+$KEEP_LIST$NL}$p"
done

find_adb
pick_device
read_identity
check_device
printf 'groups : %s\n' "${SELECTED:-(none)}"
OPTIN=$(for g in $ALL_GROUPS; do if ! in_words "$SELECTED" "$g"; then printf '%s ' "$g"; fi; done)
[ -z "$OPTIN" ] || printf 'not selected (INCLUDE=... to add): %s\n' "${OPTIN% }"
if [ "$DRY" = 1 ]; then echo "mode   : DRY RUN (nothing will be changed, no files written)"; fi

# one fetch of the phone's package state
INSTALLED=$(pm_list --user 0) || quit 1
PATHS=$(pm_list -f -u --user 0) || quit 1
THIRD=$(pm_list -3 --user 0) || quit 1
KNOWN=$(printf '%s\n' "$PATHS" | awk -F= '{ print $NF }')
APEX=$(printf '%s\n' "$PATHS" | awk -F= 'index($0, "/apex/") == 1 { print $NF }')
[ "$(count "$INSTALLED")" -ge 50 ] || die "the phone returned a suspiciously short package list ($(count "$INSTALLED")); not continuing."
BEFORE_COUNT=$(count "$INSTALLED")

# build the removal set
T_PKG=() T_GRP=() T_DESC=()
GONE="" ABSENT="" KEPT="" SEEN=""
for g in $SELECTED; do
  while read -r p d; do
    [ -n "$p" ] || continue
    if has "$SEEN" "$p"; then continue; fi
    SEEN="$SEEN$NL$p"
    if has "$KEEP_LIST" "$p"; then KEPT="${KEPT:+$KEPT$NL}$p"; continue; fi
    if has "$INSTALLED" "$p"; then
      T_PKG+=("$p"); T_GRP+=("$g"); T_DESC+=("$d")
    elif has "$KNOWN" "$p"; then GONE="${GONE:+$GONE$NL}$p"
    else ABSENT="${ABSENT:+$ABSENT$NL}$p"; fi
  done <<EOF
$(group_list "$g" | strip_list)
EOF
done

# hard guard on the actual removal set: never-touch list + mainline (APEX) modules
BAD=""
for p in ${T_PKG[@]+"${T_PKG[@]}"}; do
  if has "$NEVER" "$p"; then BAD="$BAD$NL  $p (never-touch: $(desc_of "$p" "$NEVER_TEXT"))"; fi
  if has "$APEX" "$p"; then BAD="$BAD$NL  $p (part of a mainline/APEX module on this firmware)"; fi
done
[ -z "$BAD" ] || die "refusing to run; protected package(s) in the removal set:$BAD"

# never-touch packages that exist on the phone but are not installed for user 0
MISSING=""
for p in $NEVER; do
  if has "$KNOWN" "$p" && ! has "$INSTALLED" "$p"; then MISSING="${MISSING:+$MISSING$NL}$p"; fi
done
if [ -n "$MISSING" ]; then
  echo
  echo "NOTE: never-touch packages that are on this phone but NOT installed for user 0."
  echo "This script did not do that (they were removed earlier, or your firmware leaves them off)."
  echo "If you removed them yourself, you probably want them back:"
  for p in $MISSING; do printf '  %-48s %s\n' "$p" "$(desc_of "$p" "$NEVER_TEXT")"; done
  echo "  restore one with: adb -s $SERIAL shell cmd package install-existing <package>"
fi

# perf plan
PERF_NS=() PERF_KEY=() PERF_TO=() PERF_FROM=()
if [ "$DO_PERF" = 1 ]; then
  HZ=$(ash dumpsys display 2>/dev/null | grep -o 'fps=[0-9.]*' | cut -d= -f2 | sort -n | tail -n 1 |
    awk '$1 >= 30 { printf "%d.0\n", $1 + 0.5 }') || HZ=""
  for k in window_animation_scale transition_animation_scale animator_duration_scale; do
    PERF_NS+=(global); PERF_KEY+=("$k"); PERF_TO+=(0.5)
  done
  if [ -n "$HZ" ]; then
    PERF_NS+=(system system); PERF_KEY+=(peak_refresh_rate min_refresh_rate); PERF_TO+=("$HZ" "$HZ")
  else
    warn "could not read the display's refresh rates; skipping the refresh-rate lock."
  fi
  for i in ${PERF_KEY[@]+"${!PERF_KEY[@]}"}; do
    v=$(ash settings get "${PERF_NS[$i]}" "${PERF_KEY[$i]}") || v="?"
    PERF_FROM+=("$v")
  done
fi

# show the plan
N=${#T_PKG[@]}
echo
if [ "$N" -gt 0 ]; then
  echo "$( [ "$DRY" = 1 ] && echo 'would remove' || echo 'to remove') ($N):"
  for i in "${!T_PKG[@]}"; do
    note=""
    if has "$THIRD" "${T_PKG[$i]}"; then note="  [user app: Play Store reinstall only]"; fi
    printf '  %-18s %-46s %s%s\n' "[${T_GRP[$i]}]" "${T_PKG[$i]}" "${T_DESC[$i]}" "$note"
  done
else
  echo "nothing to remove: every selected package is already gone."
fi
echo "already removed for user 0: $(count "$GONE"); not on this phone: $(count "$ABSENT")$( [ "${VERBOSE:-0}" = 1 ] || echo ' (VERBOSE=1 lists them)')"
if [ "${VERBOSE:-0}" = 1 ]; then
  if [ -n "$GONE" ]; then echo "  already removed:"; indent "$GONE" '    '; fi
  if [ -n "$ABSENT" ]; then echo "  not on this phone:"; indent "$ABSENT" '    '; fi
fi
if [ -n "$KEPT" ]; then echo "kept because of KEEP=: $(words "$KEPT")"; fi

PERF_CHANGES=0
echo
if [ "$DO_PERF" = 1 ]; then
  echo "perf tweaks (PERF=0 skips):"
  for i in ${PERF_KEY[@]+"${!PERF_KEY[@]}"}; do
    if same_value "${PERF_FROM[$i]}" "${PERF_TO[$i]}"; then state="already set"
    else state="$( [ "$DRY" = 1 ] && echo 'would change' || echo 'will change')"; PERF_CHANGES=$((PERF_CHANGES + 1)); fi
    printf '  %-6s %-28s %8s -> %-6s %s\n' "${PERF_NS[$i]}" "${PERF_KEY[$i]}" "${PERF_FROM[$i]}" "${PERF_TO[$i]}" "($state)"
  done
else
  echo "perf tweaks: skipped (PERF=0)"
fi

if [ "$DRY" = 1 ]; then
  echo
  if [ "$N" -gt 0 ]; then
    echo "DRY RUN: a real run would also write debloat-<serial>-<date>.txt and .log in $(pwd)."
  fi
  echo "DRY RUN: nothing changed."
  exit 0
fi
if [ "$N" -eq 0 ] && [ "$PERF_CHANGES" -eq 0 ]; then echo; echo "Nothing to do."; exit 0; fi

confirm "remove $N package(s) and apply $PERF_CHANGES setting change(s) on $SERIAL"

# record + log, created before anything changes
BASE=$(new_base debloat)
RECORD="$BASE.txt"
LOG="$BASE.log"
{
  echo "# moto g 2025 debloat record: packages removed for user 0, one per line."
  echo "# restore: ./debloat.sh restore $RECORD"
  echo "# serial: $SERIAL"
  echo "# phone: $MODEL ($DEVICE / $PRODUCT), Android $RELEASE, carrier ${CARRIER:-?}"
  echo "# build: $FINGERPRINT"
  echo "# date: $(date '+%Y-%m-%d %H:%M:%S %z')"
  echo "# groups: $SELECTED"
  for i in ${PERF_KEY[@]+"${!PERF_KEY[@]}"}; do
    echo "# perf before: settings ${PERF_NS[$i]} ${PERF_KEY[$i]} = ${PERF_FROM[$i]}"
  done
} >"$RECORD" 2>/dev/null || die "can't write $RECORD in $(pwd); cd to a writable folder and run again."
: >"$LOG" || die "can't write $LOG"
log "debloat $SERIAL ($MODEL, $FINGERPRINT), groups: $SELECTED"

FAILS=()
REMOVED="" PROTECTED="" USERAPPS=""
if [ "$N" -gt 0 ]; then
  echo
  echo "removing:"
  for i in "${!T_PKG[@]}"; do
    p=${T_PKG[$i]}
    out=$(ash pm uninstall --user 0 "$p") || true
    log "pm uninstall --user 0 $p: $out"
    case "$out" in
    *Success*)
      REMOVED="${REMOVED:+$REMOVED$NL}$p"
      echo "$p" >>"$RECORD"
      if has "$THIRD" "$p"; then USERAPPS="${USERAPPS:+$USERAPPS$NL}$p"; fi
      printf '  removed    %s\n' "$p" ;;
    *"not installed for"*)
      printf '  gone       %s\n' "$p" ;;
    *non-disable* | *protected* | *DELETE_FAILED_INTERNAL_ERROR* | *DELETE_FAILED_USER_RESTRICTED* | \
      *DELETE_FAILED_DEVICE_POLICY_MANAGER* | *DELETE_FAILED_OWNER_BLOCKED*)
      PROTECTED="${PROTECTED:+$PROTECTED$NL}$p"
      printf '  protected  %s (%s)\n' "$p" "$(printf '%s' "$out" | tr '\n' ' ')" ;;
    *)
      FAILS+=("uninstall $p: $(printf '%s' "$out" | tr '\n' ' ')")
      printf '  FAILED     %s (%s)\n' "$p" "$(printf '%s' "$out" | tr '\n' ' ')" ;;
    esac
  done
fi

if [ "$DO_PERF" = 1 ] && [ "$PERF_CHANGES" -gt 0 ]; then
  echo
  echo "perf tweaks:"
  for i in "${!PERF_KEY[@]}"; do
    ns=${PERF_NS[$i]} k=${PERF_KEY[$i]} to=${PERF_TO[$i]} from=${PERF_FROM[$i]}
    if ! same_value "$from" "$to"; then
      out=$(ash settings put "$ns" "$k" "$to") || true
      log "settings put $ns $k $to: $out"
    fi
    after=$(ash settings get "$ns" "$k") || after="?"
    log "settings get $ns $k: $after"
    if same_value "$after" "$to"; then res=ok; else res=FAILED; FAILS+=("settings $ns $k: wanted $to, got $after"); fi
    printf '  %-6s %-28s %8s -> %-6s %s\n' "$ns" "$k" "$from" "$after" "$res"
  done
fi

# post-check: removed packages are gone, never-touch packages are all still there
AFTER=$(pm_list --user 0) || { FAILS+=("could not re-read the package list after the run"); AFTER=""; }
if [ -n "$AFTER" ]; then
  for p in $REMOVED; do
    if has "$AFTER" "$p"; then FAILS+=("$p reported Success but is still installed"); fi
  done
  for p in $NEVER; do
    if has "$INSTALLED" "$p" && ! has "$AFTER" "$p"; then
      FAILS+=("CRITICAL: never-touch package $p disappeared during the run; restore it: adb -s $SERIAL shell cmd package install-existing $p")
    fi
  done
fi

# record footer
{
  if [ -n "$USERAPPS" ]; then
    echo "# user-installed (Play Auto Install) apps above are fully uninstalled; restore can't bring"
    echo "# them back, reinstall from the Play Store: $(words "$USERAPPS")"
  fi
  if [ -n "$PROTECTED" ]; then echo "# protected by the firmware, not removed: $(words "$PROTECTED")"; fi
  for f in ${FAILS[@]+"${FAILS[@]}"}; do echo "# failed: $f"; done
} >>"$RECORD"

echo
echo "summary: removed $(count "$REMOVED"), protected by firmware (not removable) $(count "$PROTECTED"), failed ${#FAILS[@]}"
echo "packages installed for user 0: $BEFORE_COUNT -> $(count "$AFTER")"
if [ -n "$PROTECTED" ]; then
  echo "protected (the firmware refuses to remove these; not an error):"
  indent "$PROTECTED"
fi
if [ -n "$USERAPPS" ]; then
  echo "fully uninstalled user apps (reinstall from the Play Store if wanted): $(words "$USERAPPS")"
fi
echo "record : $RECORD"
echo "log    : $LOG"
echo "undo   : ./debloat.sh restore $RECORD"
if [ ${#FAILS[@]} -gt 0 ]; then
  echo
  echo "FAILURES:"
  printf '  - %s\n' "${FAILS[@]}"
  quit 2
fi
exit 0
