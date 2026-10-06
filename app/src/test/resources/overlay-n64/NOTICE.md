# Fixture provenance

The `.cfg` and `.png` files in this directory are verbatim copies of
RetroArch's bundled overlay assets (extracted from the RetroArch Android
APK's `assets/overlays`), used here ONLY as parser/pack fidelity fixtures
for `OverlayCfgTest` / `OverlaySeedTest`.

They are data files, (c) the libretro / RetroArch contributors, and remain
under their upstream license (RetroArch is GNU GPLv3; see
https://github.com/libretro/RetroArch). They are mere aggregation alongside
the AGPL-licensed GameBEX code — not part of it — and they never ship in the
app: release packaging excludes `src/test/resources`.
