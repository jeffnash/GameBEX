package com.gamebex.client.ui.home.components

import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import com.gamebex.client.ui.console.ConsoleColors
import com.gamebex.client.ui.home.model.ArtKind
import com.gamebex.client.ui.home.model.ArtRef
import com.gamebex.client.ui.home.model.CardState
import com.gamebex.client.ui.home.model.InviteKind
import com.gamebex.client.ui.console.consoleContrastRatio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure decisions behind the card art and chrome:
 * the case palette and tags, the 2-line title budget, the state-line
 * glyph split, and the shadow/fill/border rules.
 */
class CardArtTest {

    @Test fun casePaletteIsKeyedOnTheSystemFolder() {
        assertEquals(CASE_PALETTE[0], caseColorFor("gc"))
        assertEquals(CASE_PALETTE[1], caseColorFor("ps2"))
        assertEquals(CASE_PALETTE[2], caseColorFor("n64"))
        assertEquals(CASE_PALETTE[3], caseColorFor("snes"))
        assertEquals(CASE_PALETTE[4], caseColorFor("gba"))
        assertEquals(CASE_PALETTE[5], caseColorFor("psx"))
        assertEquals(CASE_PALETTE[6], caseColorFor("dreamcast"))
        assertEquals(CASE_PALETTE[7], caseColorFor("nds"))
    }

    @Test fun casePaletteIsCaseInsensitiveAndUnknownFoldersHashIntoIt() {
        assertEquals(CASE_PALETTE[0], caseColorFor(" GC "))
        val unknown = caseColorFor("wonderswan")
        assertTrue(CASE_PALETTE.contains(unknown))
        // Stable across calls and JVM runs (String.hashCode is stable).
        assertEquals(unknown, caseColorFor("wonderswan"))
        // Null folder falls back to the title for the hash, never a crash.
        assertTrue(CASE_PALETTE.contains(caseColorFor(null, "Crazy Taxi")))
    }

    @Test fun caseInkStaysLegibleOnEveryPaletteEntry() {
        for (color in CASE_PALETTE) {
            val ink = caseInk(color)
            assertTrue(
                "case ink contrast on $color",
                consoleContrastRatio(ink, color) >= 4.5,
            )
        }
    }

    @Test fun everyCommonSystemFolderHasItsOwnDistinctCaseColour() {
        val folders = listOf(
            "nes", "gba", "genesis", "mastersystem", "gamegear", "n64", "nds", "3ds",
            "psx", "ps2", "psp", "saturn", "dreamcast", "gc", "wii", "snes", "gb", "gbc",
            "arcade", "mame", "fbneo", "pce", "msx",
        )
        val colors = folders.map { caseColorFor(it) }
        for (i in folders.indices) {
            for (j in i + 1 until folders.size) {
                assertNotEquals(
                    "${folders[i]} and ${folders[j]} share a case colour",
                    colors[i],
                    colors[j],
                )
            }
        }
        // Six prominent systems never collide.
        val six = listOf("nes", "gba", "genesis", "gc", "snes", "psx").map { caseColorFor(it) }
        assertEquals(six.size, six.toSet().size)
        // Alternate spellings of one system share that system's colour.
        assertEquals(caseColorFor("3ds"), caseColorFor("n3ds"))
        assertEquals(caseColorFor("pce"), caseColorFor("tg16"))
        assertEquals(caseColorFor("pce"), caseColorFor("pcengine"))
    }

    @Test fun caseInkStaysLegibleOnEverySystemFolderColour() {
        val folders = listOf(
            "nes", "gba", "genesis", "mastersystem", "gamegear", "n64", "nds", "3ds",
            "psx", "ps2", "psp", "saturn", "dreamcast", "gc", "wii", "snes", "gb", "gbc",
            "arcade", "mame", "fbneo", "pce", "msx",
        )
        for (folder in folders) {
            val color = caseColorFor(folder)
            assertTrue(
                "case ink contrast on $folder ($color)",
                consoleContrastRatio(caseInk(color), color) >= 4.5,
            )
        }
    }

    @Test fun bandTextIsTrimmedToTheBandFontSize() {
        // The tag's line box is exactly its font size — no font padding,
        // both leadings trimmed — so the system name renders fully inside the band.
        val style = caseBandStyle(12.sp)
        assertEquals(12.sp, style.lineHeight)
        assertEquals(PlatformTextStyle(includeFontPadding = false), style.platformStyle)
        assertEquals(LineHeightStyle.Trim.Both, style.lineHeightStyle?.trim)
    }

    @Test fun artFileTrustsTheResolvedArtRefWithoutTouchingTheDisk() {
        // No existence stat in composition. A file the factory already
        // resolved is used as-is; http(s) models are not local files.
        val missing = java.io.File("/nonexistent-gamebex/cover.png")
        assertEquals(missing, artFile(ArtRef(missing, ArtKind.COVER)))
        assertNull(artFile(ArtRef("https://example.test/c.png", ArtKind.COVER, sourceId = "s")))
    }

    @Test fun caseTagIsShortInThumbnailsAndFullNameOnTv() {
        assertEquals("GC", caseTag("gc", full = false))
        assertEquals("GameCube", caseTag("gc", full = true))
        assertEquals("PS2", caseTag("ps2", full = false))
        assertEquals("PlayStation 2", caseTag("ps2", full = true))
        assertNull(caseTag(null, full = true))
        assertNull(caseTag("", full = false))
    }

    @Test fun titleStartsAt28AndStepsDownToFitTwoLines() {
        // Normal scale: 2 trimmed lines of 28 px cost 56 px of the 60 px
        // budget (84 - 2 gap - 22 state), so the ladder lands on 28 and
        // "Sonic the Hedgehog 2" wraps to two full lines.
        assertEquals(28f, titleFontPx(84f - 2f - 22f * 1f, 1f))
        // Larger (Home scale capped at 1.1): 28 overflows two lines,
        // 26 fits.
        assertEquals(26f, titleFontPx(84f - 2f - 22f * 1.1f, 1.1f))
    }

    @Test fun titleNeverShrinksBelow22BeforeEllipsizing() {
        assertEquals(22f, titleFontPx(10f, 1.1f))
        assertEquals(22f, titleFontPx(0f, 1f))
    }

    @Test fun stateLineSplitsLeadingGlyphsOff() {
        assertEquals(
            StateGlyph.CLOUD to "Download again · 1.2 GB",
            parseStateLine("☁ Download again · 1.2 GB"),
        )
        assertEquals(
            StateGlyph.WARN to "Needs BIOS",
            parseStateLine("⚠ Needs BIOS"),
        )
    }

    @Test fun stateLineKeepsPlainLinesVerbatim() {
        assertEquals(null to "GC · saved 12 min ago", parseStateLine("GC · saved 12 min ago"))
        assertEquals(null to "", parseStateLine(""))
    }

    @Test fun shadowOnlyExistsWhenFocusedOrPressed() {
        // A selected-but-unfocused card carries no shadow, so a
        // transparent tile can never tint its own fill.
        assertEquals(0, CardChrome.shadowOffsetPx(focused = false, pressed = false))
        assertEquals(5, CardChrome.shadowOffsetPx(focused = true, pressed = false))
        assertEquals(1, CardChrome.shadowOffsetPx(focused = true, pressed = true))
        assertEquals(1, CardChrome.shadowOffsetPx(focused = false, pressed = true))
    }

    @Test fun focusAndSelectionLightenTheFill() {
        assertEquals(ConsoleColors.HighSurface, CardChrome.fill(true, false, ConsoleColors.Surface))
        assertEquals(ConsoleColors.HighSurface, CardChrome.fill(false, true, ConsoleColors.Surface))
        assertEquals(ConsoleColors.Surface, CardChrome.fill(false, false, ConsoleColors.Surface))
        assertEquals(
            ConsoleColors.Background,
            CardChrome.fill(false, false, ConsoleColors.Background),
        )
    }

    @Test fun bordersArePinkWhenFocusedAndMutedWhenOnlySelected() {
        assertEquals(ConsoleColors.Accent, CardChrome.borderColor(focused = true, selected = true))
        assertEquals(ConsoleColors.TextTertiary, CardChrome.borderColor(focused = false, selected = true))
        assertEquals(ConsoleColors.Steel, CardChrome.borderColor(focused = false, selected = false))
        assertEquals(3, CardChrome.borderWidthPx(focused = true))
        assertEquals(2, CardChrome.borderWidthPx(focused = false))
    }

    @Test fun offlineCardLinesAreAmber() {
        assertEquals(
            ConsoleColors.Warning,
            stateLineColor(CardState.WaitingForConnection, "Download when online"),
        )
        assertEquals(
            ConsoleColors.Warning,
            stateLineColor(
                CardState.Downloading(read = 1, total = 2, etaSec = null, paused = true, armed = false),
                "Paused · offline · 50%",
            ),
        )
    }

    @Test fun armedQueueLineIsMint() {
        assertEquals(
            ConsoleColors.Success,
            stateLineColor(CardState.Queued(armed = true), "Queued · plays when ready"),
        )
    }

    @Test fun stateLineColoursKeepTheSpecTable() {
        // The remaining states keep their colours.
        assertEquals(
            ConsoleColors.TextTertiary,
            stateLineColor(CardState.Queued(armed = false), "Queued"),
        )
        assertEquals(
            ConsoleColors.TextTertiary,
            stateLineColor(
                CardState.Downloading(read = 1, total = 2, etaSec = null, paused = true, armed = false),
                "Paused · 50%",
            ),
        )
        assertEquals(
            ConsoleColors.Success,
            stateLineColor(
                CardState.Downloading(read = 1, total = 2, etaSec = 60, paused = false, armed = false),
                "50% · 1 min left",
            ),
        )
        assertEquals(ConsoleColors.TextSecondary, stateLineColor(CardState.Played(0), "GC · played today"))
        assertEquals(ConsoleColors.Accent, stateLineColor(CardState.OffPhone(null, 0), "☁ Download again"))
    }

    @Test fun inviteIconsMatchTheMockupAndTheG25TvNotices() {
        // Check systems shows the warn glyph; Scan phone shows the
        // folder glyph matching its TV notice "FIND YOUR GAMES".
        assertEquals(InviteIcon.WARN, inviteIconOf(InviteKind.CHECK_SYSTEMS))
        assertEquals(InviteIcon.FOLDER, inviteIconOf(InviteKind.SCAN_PHONE))
        assertEquals(InviteIcon.PLUG, inviteIconOf(InviteKind.ADD_PLUGIN))
        assertEquals(InviteIcon.DICE, inviteIconOf(InviteKind.ROLL_AGAIN))
        assertEquals(InviteIcon.PLUG, inviteIconOf(null))
    }

    @Test fun linkBodyBudgetIsTwoWholeLineBoxes() {
        // The explicit height budget the row clamps to — two whole
        // line boxes, each rounded up, so a fractional line height
        // still gets its full second line.
        assertEquals(44f, twoLineBudgetPx(22f))
        assertEquals(2 * 29f, twoLineBudgetPx(28.6f))
        assertEquals(2 * 23f, twoLineBudgetPx(22.01f))
        // Exactly two lines, never more.
        assertEquals(2f * kotlin.math.ceil(22f), twoLineBudgetPx(22f))
    }

    @Test fun linkBodyStyleTrimsBothLeadings() {
        // The body's measured height is exactly its line boxes,
        // matching the budget above.
        val style = linkBodyStyle(22.sp)
        assertEquals(22.sp, style.lineHeight)
        assertEquals(PlatformTextStyle(includeFontPadding = false), style.platformStyle)
        assertEquals(LineHeightStyle.Trim.Both, style.lineHeightStyle?.trim)
    }

    @Test fun pausedUnknownSizeRingIsStaticNotAnInfiniteSpinner() {
        assertEquals(RingFill.STATIC, ringFill(progress = null, paused = true))
    }

    @Test fun ringFillModesKeepTheirNeighbours() {
        assertEquals(RingFill.SPINNER, ringFill(progress = null, paused = false))
        assertEquals(RingFill.DETERMINATE, ringFill(progress = 0.5f, paused = true))
        assertEquals(RingFill.DETERMINATE, ringFill(progress = 0f, paused = false))
    }
}
