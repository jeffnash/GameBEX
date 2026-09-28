# GameBEX Architecture (authoritative)

GameBEX (`com.gamebex.client`) is a sideload-only Android console OS for
retro games: SD-first ROM library, download queue with prep pipeline,
and one-press launch into RetroArch cores or standalone emulators.
Landscape 1600×720, D-pad-first, dark console UI.

## Modules (`app/src/main/java/com/gamebex/client/`)

| Package | Owns | Key files |
|---|---|---|
| `ui/` (+`ui/console/`, `ui/home/`, `ui/game/`, `ui/system/`, `ui/sources/`, `ui/discover/`) | Compose screens, console rail, D-pad focus | `MainActivity.kt`, `Library.kt`, `Components.kt` |
| `data/` | Platform/emulator registries | `PlatformRegistry.kt`, `EmuCatalog.kt` |
| `data/local/` | Room DB, scanner, prep, sidecars, covers | `LibraryDb.kt`, `LibraryScanner.kt`, `PrepPipeline.kt` |
| `net/` | Transfers, art, BIOS fetch, host HTTP | `DownloadEngine.kt`, `DownloadIngest.kt`, `ArtWorker.kt`, `ScreenScraper.kt`, `HostHttp.kt` |
| `sources/` | Source registry, route codec, image loaders | `SourceRegistry.kt`, `CatalogRouteCodec.kt`, `SourceImages.kt` |
| `launch/` | Emulator routing, core fetch, tuning, ROM provider | `EmuRouter.kt`, `RetroCoreFetcher.kt`, `RomProvider.kt`, `GraphicsTuner.kt` |
| `install/` | Emulator APK install + Fix-All queue | `EmuInstaller.kt` |
| `cocoon/` | SD/internal path roots, volume identity | `CocoonDirs.kt`, `CocoonMap.kt` |

Plus the pure-JVM `:source-runtime` module: the source definition
schema, validator, workflow interpreter, and policy-checked HTTP
(`SourceHttp`). Dependency direction: `ui` → `sources`/`net`/`launch`
→ `data.local` → `data`; both app and `:source-runtime` consume the
shared `com.gamebex.source` models. `data.local` never imports `net`
(cycle-free by construction).

## ROM sources (plugins)

- The app ships with no catalog. A source is an immutable JSON
  definition (see `docs/source-schema.md`) installed from a file via
  the Sources screen; each install is pinned by its SHA-256 revision.
- Schema/runtime separation: definitions declare origins, platforms,
  and workflows; `:source-runtime` validates and interprets them, and
  `SourceRegistry` (app) owns install/enable/select/retention.
  Source network access is origin-allowlisted per purpose
  (catalog/download/artwork/assist), HTTPS-only, with checked DNS;
  definitions cannot set destinations, volumes, or file paths.
- Downloads pin the source revision, resolve a fresh transfer URL per
  attempt, and park as `source_unavailable` (bytes kept) when the
  source is missing/disabled — resuming only on explicit retry.
- Cover art renders only through per-source Coil loaders
  (`sources/SourceImages.kt`); naked catalog URLs never load.
- BIOS independence: `launch/BiosFetcher.kt` (RetroBIOS) is
  host-owned and runs with zero sources installed. Emulator installs
  (`install/EmuInstaller.kt`, `net/HostHttp.kt`), cores, and
  ScreenScraper are likewise host-owned services.
- No source definition is ever bundled or auto-fetched.

## Storage (SD-first)

- Roots: SD `GameBEX/` tree when mounted, else internal
  `Downloads/GameBEX/` (`cocoon/CocoonDirs.kt`, `RomStore`).
- Requires `MANAGE_EXTERNAL_STORAGE` (all-files access); without it the
  scanner skips SD volumes and the UI surfaces the grant nudge.
  Sideload-only: no Play SAF path by design.
- Room (`LibraryDb`, v1, schema exported to `app/schemas/`; an
  unknown on-device version is dropped and rebuilt by rescan): games,
  downloads, enrich queue, emulator state. Games/downloads carry
  source provenance (`sourceId`/`sourceItemKey`/`sourceVariantKey`/
  `sourceRevision`); downloads add `transferKind` (ROM/ART/INTERNAL)
  and origin-scoped assist cookies. Scans preload one volume's rows,
  collect a plan, and commit in a single transaction
  (`LibraryScanner.scanVolume`); freshness window 60 s with a
  grant+volume fingerprint (`refreshIfStale`).
- Prep (`PrepPipeline`): disc archives extract to unique prep dirs with
  an exact manifest; only manifest files are ever deleted.

## Launch matrix

`launch/EmuRouter.kt` routes each platform to a RetroArch core
(`snes9x`, `mgba`, `gambatte`, `flycast`, `melonDS`, `ppsspp`, …) or a
standalone (`EmuCatalog.kt`); PS2 stays on NetherSX2 (no stable
`lrps2` mobile core). Raw disc archives gated by
`ArchiveHub.ROUTE_EXTS` always go through prep first. RetroArch is
driven via its managed top-level config — never via the
cross-app-unreadable `/sdcard/Android/data` path.

## Core provisioning trust

`RetroCoreFetcher` streams buildbot zips (100 MB cap, same-host
redirects only, size + ELF validation) and verifies SHA-256
trust-on-first-use (`launch/CorePinStore.kt`): the first validated
fetch pins the zip + `.so` hashes, and every later fetch or cached
sideload must match or fails closed with Core Downloader guidance.
TOFU is forced because the buildbot publishes no hashes, signatures,
or immutable builds (nightly `latest/` only). Residual risk: a
compromised first fetch pins attacker bytes.

## Build, test, lint

Prereqs: JDK 17 (`JAVA_HOME=~/jdk17`), Android SDK
(`ANDROID_HOME=~/android-sdk`), one `adb` device for connected tests.

```sh
export JAVA_HOME=~/jdk17 ANDROID_HOME=~/android-sdk
./gradlew :app:testDebugUnitTest :source-runtime:test :app:assembleDebug :app:lintDebug
./gradlew :app:connectedDebugAndroidTest   # needs device (migration, race, scan bench)
```

- Release: R8 + resource shrinking on (`isMinifyEnabled`,
  `isShrinkResources`); ~2.2 MB APK. Vendor rule: icons from
  `material-icons-core` + local `ui/GameBexIcons.kt` only.
- Perf harness: `LibraryFilterBenchTest`, `CoreFetchCapBenchTest`
  (JVM), `LibraryScanBenchTest` (device, asserts O(1) SELECTs).
- CI: `.github/workflows/verify.yml` runs app + `:source-runtime`
  unit tests, debug assembly, lint, and the extraction check
  (no `GameBexApi`, no `romsfun` in `:source-runtime`).

## Credentials & privacy

- ScreenScraper creds: Keystore-backed EncryptedSharedPreferences;
  passwords use password fields; backup rules exclude them.
- Assist-WebView cookies live only for the job, then are cleared;
  navigation is host-allowlisted, third-party cookies off.

## Docs map

- This file: product/module truth.
- `source-schema.md`: the exact source definition language.
- `FORMATS.md`: format support and prep behavior.
- `LEGAL.md`: full legal notices.
- `../README.md`: maintainer setup + validation entry point.
