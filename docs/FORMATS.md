# Format support & prep behavior

What Finish Setup does with each file type. The rule throughout:
prep reports what is actually true — a format the route's emulator
cannot boot is `NEEDS_MANUAL` with the fix named, never `READY`.

## Universal disc rules

- **Sheets:** `.cue` / `.gdi` / `.m3u` validate (refs must exist);
  `.ccd` validates on PSX/Saturn/TG-CD (same-basename `.img`/`.bin`
  + optional `.sub`). Same-disc duplicates collapse to one boot
  sheet (Dreamcast prefers `.gdi`, elsewhere `.cue`, then `.ccd`).
- **Cue audio:** tracks must be raw (`BINARY`/`WAVE`). MP3/OGG/FLAC
  cues fail with a convert-to-WAV/BIN note.
- **CHD:** container v5+ required (older: upgrade with `chdman`).
- **Multi-disc:** an `.m3u` is generated over ordered sheets/images.
- **Single-ROM archives:** a zip holding one cart-bootable file
  (Jaguar `.j64`, Dreamcast `.elf`, ...) extracts and boots it;
  multi-ROM archives are refused with an explanation.
- **Dreamcast 45000-cues:** CD-R-numbered rips (ISO extents below
  45150) would hang black in flycast — prep boots a generated
  `.gdi` declaring track 3 at 45000 instead.

## Per-folder notes (disc folders)

| Folder | Boots | Needs manual step |
|---|---|---|
| Dreamcast | cue/gdi/m3u, chd, cdi, iso, elf, lst | `.dat` strays; 45000-cue → auto-GDI |
| PlayStation | cue/ccd/m3u, chd, iso, pbp, exe, toc | ecm (unecm), mdf/mds, psf music, z/znx/cbn |
| Saturn | cue/ccd/m3u, chd, iso, toc | mds/mdf |
| Sega CD, Neo Geo CD, TG-CD, 3DO | cue/m3u (+ccd/chd/toc on TG-CD), chd, iso | mp3-cues, old CHD |
| GameCube, Wii | iso, rvz/gcm/gcz/wbfs/wia/ciso, dol/elf, wad | **NKit** (restore to ISO), json/dff |
| PSP | iso, chd, cso, pbp, elf | prx plugins |
| PS2 | via NetherSX2 manual pick | — |
| Jaguar | carts (`.j64`/`.jag`/...) loose or zipped | **CD not emulated** (cue/cdi); ambiguous `.bin` → rename |

## Cartridge folders

Everything accepted boots as-is, except:

- **Amiga:** rp9/uae/nrg/mds/ccd are refused with notes.
- **MSX:** only blueMSX extensions boot (rom/ri/mx1/mx2/col/dsk/
  cas/sg/sc/m3u); disk variants, wav, ogv, xsa are refused.
- **NES `.fds`:** needs `disksys.rom` in `RetroArch/system`.
- **N64 `.ndd`:** needs `IPL.n64` (or `64DD_IPL.bin`) in
  `RetroArch/system/Mupen64plus/`.

## Arcade folders (mame, neogeo, naomi)

Zips stay zipped and must keep exact shortnames (e.g. `galaga.zip`).
