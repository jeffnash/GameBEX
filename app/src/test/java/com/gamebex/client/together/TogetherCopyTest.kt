package com.gamebex.client.together

import com.gamebex.client.data.Emu
import com.gamebex.client.launch.EmuRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TogetherCopyTest {
    @Test fun everyByeReason() {
        assertEquals("Sam ended the session.", TogetherCopy.byeText(ByeReason.LEFT, "Sam"))
        assertEquals("Sam removed you from the session.", TogetherCopy.byeText(ByeReason.REMOVED, "Sam"))
        assertEquals("Sam already has a player 2.", TogetherCopy.byeText(ByeReason.FULL, "Sam"))
        assertEquals("Update GameBEX on both phones to play together.", TogetherCopy.byeText(ByeReason.VERSION, "Sam"))
        assertEquals("The session closed.", TogetherCopy.byeText(ByeReason.ERROR, "Sam"))
    }

    @Test fun phaseLines() {
        assertEquals("Their core is a different build", TogetherCopy.phaseLine(GuestPhase.CORE_SYNC, null, null))
        assertEquals("Copying your core to their phone… 40%", TogetherCopy.phaseLine(GuestPhase.CORE_SYNC, null, 40))
        assertFalse(TogetherCopy.phaseLine(GuestPhase.MISMATCH, "x", null).contains("x"))
    }

    @Test fun guestInstructionsIncludeHost() {
        val instructions = TogetherCopy.guestInstructions(TogetherMode.RA_NETPLAY, "Sam", "Y")
        assertEquals(3, instructions.size)
        assertEquals("The game starts on this phone.", instructions[0])
        assertEquals("When Sam opens the room, you'll get a notification.", instructions[1])
        assertEquals(
            "Then press SELECT and Y, go back to the Main Menu, open Netplay and choose Connect to Netplay Host. The game restarts and you're player 2.",
            instructions[2],
        )
        assertEquals(
            "When the game starts, hold SELECT and press R1 to open the room. The game restarts once.",
            TogetherCopy.hostInstructions(TogetherMode.RA_NETPLAY)[0],
        )
        assertEquals("Ready. Waiting for Sam to start.", TogetherCopy.readyWaiting("Sam"))
    }

    @Test fun gbLinkInstructionsExplainTheLinkRules() {
        val rules = listOf(
            "Use the game's link cable menu, for example Cable Club to trade.",
            "Only one of you picks 2 player. If it jumps back to 1 player, try again.",
            "Keep RetroArch open on both phones. Opening its menu or leaving the app on one phone freezes the other.",
        )
        assertEquals(listOf("The game starts on both phones, linked.") + rules, TogetherCopy.hostInstructions(TogetherMode.GB_LINK))
        assertEquals(listOf("The game starts a few seconds after Sam's, linked.") + rules, TogetherCopy.guestInstructions(TogetherMode.GB_LINK, "Sam", "Y"))
    }

    @Test fun retroArchMenuButtonUsesThePadsPrintedLabel() {
        // RetroArch's menu is keycode 100 (BUTTON_Y): "Y" on Xbox-style pads, "X" on Nintendo-style.
        assertTrue(TogetherCopy.guestInstructions(TogetherMode.RA_NETPLAY, "Sam", "X")[2].startsWith("Then press SELECT and X,"))
        assertEquals("SELECT + Y, Main Menu, Netplay, Connect to Netplay Host.", TogetherCopy.alertRoomOpenText("Y"))
        assertEquals("Y", com.gamebex.client.ui.console.glyphLabel(com.gamebex.client.ui.console.ControllerGlyph.Y, com.gamebex.client.ui.console.ControllerLayout.Xbox, false))
        assertEquals("X", com.gamebex.client.ui.console.glyphLabel(com.gamebex.client.ui.console.ControllerGlyph.Y, com.gamebex.client.ui.console.ControllerLayout.Nintendo, false))
    }

    @Test fun timingDefaultsMatchTheSpec() {
        val timing = TogetherTiming()
        assertEquals(5_000L, timing.helloMs)
        assertEquals(10_000L, timing.offerMs)
        assertEquals(20_000L, timing.silenceMs)
    }

    @Test fun everyOutcomeType() {
        assertEquals("why", TogetherCopy.outcomeText(EmuRouter.Outcome.Blocked("why")))
        assertEquals("setup", TogetherCopy.outcomeText(EmuRouter.Outcome.NeedsSetup("setup")))
        assertEquals("failed", TogetherCopy.outcomeText(EmuRouter.Outcome.Failed("failed")))
        assertEquals("RetroArch isn't installed on this phone. Install it from System, then come back.", TogetherCopy.outcomeText(EmuRouter.Outcome.MissingEmu(Emu.RETROARCH)))
        assertEquals("", TogetherCopy.outcomeText(EmuRouter.Outcome.Launched("RetroArch", "pkg")))
    }

    @Test fun openOnceProblemOpensTheRightEmulator() {
        assertEquals(
            Problem(TogetherCopy.retroArchFileAccess, TogetherAction.OPEN_RETROARCH),
            TogetherCopy.problemFor(Readiness.NeedsOpenOnce(Emu.RETROARCH)),
        )
        assertEquals(TogetherAction.OPEN_PPSSPP, TogetherCopy.problemFor(Readiness.NeedsOpenOnce(Emu.PPSSPP)).action)
        assertEquals("Open RetroArch", TogetherCopy.actionLabel(TogetherAction.OPEN_RETROARCH))
    }
}
