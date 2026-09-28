# Legal notices — read before use

GameBEX is a free, open-source frontend and library manager for
playing legally owned video games on Android. This document states
what the project is, what it is not, and what responsibilities stay
with you, the user. It is not legal advice; when in doubt, consult a
lawyer in your jurisdiction.

## 1. No copyrighted content ships with this project

The repository and its release APKs contain **no games, ROMs, disc
images, BIOS/firmware files, keys, artwork, or download catalogs**.
Anything playable or firmware-shaped on your device got there because
**you** put it there: files you copied, sources you imported, or
fetches you explicitly approved.

## 2. Only play what you legally own

- Only add games you own and, where your jurisdiction requires it,
  dumped from media you physically possess. Downloading a ROM for a
  game you do not own is copyright infringement in most places —
  including "I own it on another platform" in many of them.
- "Abandonware", "out of print", and "the publisher is defunct" are
  not legal categories. Assume everything is still copyrighted.
- Homebrew, demoscene, and freely licensed games are the worry-free
  way to try the app.

## 3. BIOS and firmware

Some systems need proprietary firmware (PSX BIOS, Dreamcast
`dc_boot.bin`, ...). GameBEX verifies checksums and places files
where emulators read them, and it can fetch missing files from a
third-party firmware collection **only after you explicitly ask** —
but fetching does not make it lawful. Dump firmware from hardware
you own whenever possible, and treat remote BIOS downloads exactly
like ROM downloads under §2.

## 4. ROM sources (plugins) are third-party files

Download sources are JSON definitions you import; the authors and
operators of those sources are third parties this project does not
control, endorse, or review. We do not operate any ROM site, do not
guarantee any source's contents or legality, and do not verify that
files a source serves match their labels. Importing a source and
downloading from it is your decision and your responsibility.

## 5. Emulators belong to their authors

All emulation is performed by third-party software — RetroArch and
its libretro cores, Dolphin, PPSSPP, melonDS, NetherSX2, and others
— each under its own license (many are GPL). GameBEX launches them
and, for RetroArch cores, fetches official Libretro buildbot builds;
it does not fork, modify, or relicense them. Core/emulator bugs
belong upstream; app yours here.

## 6. Artwork and metadata

Cover art and descriptions come from ScreenScraper (which needs your
own free account) or from imported sources, or artwork you import
yourself. Game art is typically owned by the games' publishers; it
is cached on your device for personal use.

## 7. Trademarks and affiliation

PlayStation, Dreamcast, Saturn, Nintendo, SEGA, Game Boy, and all
game titles and artwork are trademarks or copyrights of their
respective owners. GameBEX is an independent project with **no
affiliation with, sponsorship by, or endorsement from** Sony, Sega,
Nintendo, or any emulator author. No OEM names are used to imply
endorsement.

## 8. Warranty and liability

The software is provided "as is" under the GNU AGPL-3.0-or-later, without
warranty of any kind. The authors are not liable for anything you
do with it — including what you download, what sources you import,
or whether your use complies with your local law.

## 9. Takedown / contact

This repository hosts no infringing content, but if you are a
rights-holder and believe something here (docs, screenshots, test
fixtures) oversteps, open a GitHub issue and it will be addressed
promptly.
