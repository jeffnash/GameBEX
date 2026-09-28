# moto g 2025 debloat

`debloat.sh` removes carrier apps, ad installers and preloaded games from a
**moto g 2025** (codename `kansas`: XT2513-1/-2/-3 and XT2513V, including the
Straight Talk, Total and Tracfone models). It also applies a few gaming display
tweaks. It works over adb from any Linux or macOS computer. You don't need
root or an unlocked bootloader.

Packages are removed with `pm uninstall --user 0`, so each app stays on the
phone's system image and one command brings it back. The script writes down
what it removed so you can undo it.

## Before you start

1. Install adb (Android platform-tools): `brew install android-platform-tools`
   on macOS, `sudo apt install adb` on Debian/Ubuntu, or the
   [official download](https://developer.android.com/tools/releases/platform-tools).
2. On the phone, go to Settings > About phone and tap **Build number** 7 times.
   Then go to Settings > System > Developer options and turn on **USB debugging**.
3. Plug the phone in and accept the **Allow USB debugging?** prompt.

## Use

```sh
DRY_RUN=1 ./debloat.sh          # shows exactly what would change, and changes nothing
./debloat.sh                    # does it (you type "yes" to confirm)
```

| Group              | Default | What                                                                                   |
|--------------------|---------|----------------------------------------------------------------------------------------|
| `carrier`          | yes     | carrier account apps, caller-ID, cloud and diagnostics apps                            |
| `ads`              | yes     | ironSource / Digital Turbine app installers, Glance lock-screen ads, Play Auto Install |
| `apps`             | yes     | preloaded games, TikTok, Facebook, Temu, Chime, 1Weather, …                            |
| `carrier-services` | no      | Wi-Fi calling, visual voicemail, carrier device management, Google Fi. Only use this on a phone with no SIM. |
| `moto`             | no      | Moto help/feedback, crash and usage reporting, demo mode, Recorder, FM radio, AI Key   |
| `google`           | no      | YouTube, Maps, Gmail, Drive, Calendar, Assistant, Google app, Android Auto, Wallet, …  |
| `system`           | no      | easter egg, screen savers, system tracing, Switch Access, print suggestions            |

```sh
INCLUDE=google,system ./debloat.sh     # add opt-in groups (INCLUDE=all for everything)
EXCLUDE=apps ./debloat.sh              # keep a default group
KEEP=com.google.android.youtube INCLUDE=google ./debloat.sh
PERF=0 ./debloat.sh                    # skip the display tweaks
SERIAL=ZT4... ./debloat.sh             # choose a phone when more than one is connected
./debloat.sh --list                    # every package, with a description
./debloat.sh --help                    # every option
```

**Display tweaks** (turn them off with `PERF=0`): animation speed is set to
0.5x, and the screen is locked at 120 Hz. Setting `peak_refresh_rate` alone
still lets the screen drop to 60 Hz. `min_refresh_rate` is the setting that
holds it at 120 Hz. This uses some extra battery.

## Undo

Each real run writes `debloat-<serial>-<date>.txt` (the packages it removed)
and a `.log` file to the current folder.

```sh
./debloat.sh restore debloat-<serial>-<date>.txt       # add DRY_RUN=1 to preview
adb shell cmd package install-existing <package>       # restore a single package
adb shell settings put global window_animation_scale 1 # also transition_animation_scale, animator_duration_scale
adb shell settings delete system min_refresh_rate
```

Android fully uninstalls preloaded third-party apps such as games, social
apps and shopping apps. You can reinstall those from the Play Store. A factory
reset restores everything.

## What it never touches

The script has a never-touch list, and it refuses to run if any package on
that list ends up in the removal set. The list covers:

- device financing and device-lock packages: PayJoy, Device Lock, Motorola PAKS
- carrier SIM-unlock packages: T-Mobile, Tracfone and Verizon remote SIM unlock, `#TFUNLOCK#`
- telephony, SIM, eSIM and IMS, carrier config, Wi-Fi calling setup, emergency alerts
- system updates, including Google Play system updates and every package in a
  mainline (APEX) module
- the Play Store and Play services, the keyboard, WebView, the launcher,
  Settings, the camera and the thermal service

The script checks the phone before it does anything. It won't run unless the
phone reports `ro.product.device=kansas`; `FORCE=1` overrides this, but only
use it if you know the phone well. It also stops if adb is missing, if the
phone hasn't authorized this computer, or if more than one phone is connected
and you haven't set `SERIAL`. Some carrier packages are protected by the
firmware; the script lists those separately and doesn't count them as errors.

No warranty: you run this on your own phone at your own risk. Do a dry run
first and read it.
