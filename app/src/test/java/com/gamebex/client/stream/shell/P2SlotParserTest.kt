package com.gamebex.client.stream.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `getevent` parsing and the official slot rule as an executable oracle. */
class P2SlotParserTest {
    private val sample = """
        add device 1: /dev/input/event5
          bus:      0018
          vendor    0000
          product   0000
          version   0001
          name:     "GameSir-X5s"
          location: "gamebex:stream"
          id:       ""
          version:  1.0.1
          events:
            KEY (0001): 006b 0072 0073 008b 009e 00a4 0120 0121 0122 0123
                        0130 0131 0132 0133 0134 0135 0136 0137 0138 0139
            ABS (0003): 0000 0001 0002 0005 0010 0011
            MSC (0004): 0004
        add device 2: /dev/input/event2
          name:     "AT Translated Set 2 keyboard"
          events:
            KEY (0001): 0001 0002 0003 0004 001c 0038
            MSC (0004): 0004
        add device 3: /dev/input/event3
          name:     "fts_touch"
          events:
            KEY (0001): 014a 0145
            ABS (0003): 0000 0001 002f 0030 0035 0036
        add device 4: /dev/input/event9
          name:     "GameBEX Player 2"
          events:
            KEY (0001): 0130 0131 0133 0134 0136 0137 0138 0139 013a 013b 013d 013e
    """.trimIndent()

    @Test fun devicesClassifiesPadsKeyboardsAndTouch() {
        val devs = GeteventParser.devices(sample)
        assertEquals(4, devs.size)
        assertTrue(devs.getValue("/dev/input/event5").isPad)
        // Wrapped KEY lines still count.
        assertEquals("GameSir-X5s", devs.getValue("/dev/input/event5").name)
        assertFalse(devs.getValue("/dev/input/event2").isPad)
        // Touch (BTN_TOUCH 0x14a + tool codes) is not a pad.
        assertFalse(devs.getValue("/dev/input/event3").isPad)
        assertTrue(devs.getValue("/dev/input/event9").isPad)
    }

    @Test fun oursAreOurs() {
        assertTrue(RealPadWatcher.isOurs("GameBEX Player 2"))
        assertTrue(RealPadWatcher.isOurs("GameBEX Player 1 slot"))
        assertFalse(RealPadWatcher.isOurs("GameSir-X5s"))
        assertFalse(RealPadWatcher.isOurs(""))
    }

    @Test fun garbageLinesAreIgnored() {
        assertTrue(GeteventParser.devices("could not get driver version for /dev/input/mice, Not a typewriter\n").isEmpty())
        assertTrue(GeteventParser.devices("").isEmpty())
    }

    @Test fun eventLinesRouteToNodes() {
        assertEquals(
            "/dev/input/event5",
            GeteventParser.eventNode("/dev/input/event5: EV_KEY       BTN_A                DOWN"),
        )
        assertEquals(
            "/dev/input/event5",
            GeteventParser.eventNode("/dev/input/event5: EV_ABS       ABS_X                00000080"),
        )
        // Sync reports alone carry no input.
        assertNull(GeteventParser.eventNode("/dev/input/event5: EV_SYN       SYN_REPORT           00000000"))
        assertNull(GeteventParser.eventNode("add device 1: /dev/input/event5"))
        assertNull(GeteventParser.eventNode("garbage"))
    }

    @Test fun addedNodesAreSpotted() {
        assertEquals("/dev/input/event6", GeteventParser.addedNode("add device 7: /dev/input/event6"))
        assertNull(GeteventParser.addedNode("/dev/input/event5: EV_KEY BTN_A DOWN"))
    }

    /**
     * Executable oracle for the official rule: slots are assigned
     * in first-input order; a user's buttons read slot[user], while
     * hat/analog follow the user→slot mapping (reservation), so they
     * always work.
     */
    private class OfficialSlotModel {
        private val slots = mutableListOf<String>()
        fun firstInput(device: String) {
            if (device !in slots) slots += device
        }
        fun buttonWorks(user: Int, device: String): Boolean = slots.getOrNull(user) == device
        fun slots(): List<String> = slots.toList()
    }

    @Test fun playerTwoAloneLandsInSlotZeroWithDeadButtons() {
        val ra = OfficialSlotModel()
        ra.firstInput("GameBEX Player 2")
        assertEquals(listOf("GameBEX Player 2"), ra.slots())
        // User 2 reads slot 1 (empty): buttons dead. Hat/analog use the
        // reservation mapping, so they still work.
        assertFalse(ra.buttonWorks(user = 1, device = "GameBEX Player 2"))
    }

    @Test fun placeholderFirstPutsPlayerTwoInSlotOne() {
        val ra = OfficialSlotModel()
        ra.firstInput("GameBEX Player 1 slot")
        ra.firstInput("GameBEX Player 2")
        assertEquals(listOf("GameBEX Player 1 slot", "GameBEX Player 2"), ra.slots())
        assertTrue(ra.buttonWorks(user = 1, device = "GameBEX Player 2"))
    }

    @Test fun realPadFirstPutsPlayerTwoInSlotOne() {
        val ra = OfficialSlotModel()
        ra.firstInput("GameSir-X5s")
        ra.firstInput("GameBEX Player 2")
        assertTrue(ra.buttonWorks(user = 0, device = "GameSir-X5s"))
        assertTrue(ra.buttonWorks(user = 1, device = "GameBEX Player 2"))
    }

    @Test fun playerTwoFirstCrossTalksIntoUserOne() {
        val ra = OfficialSlotModel()
        ra.firstInput("GameBEX Player 2")
        ra.firstInput("GameSir-X5s")
        // User 1 reads slot 0 = P2's buttons; the host's own pad is dead.
        assertTrue(ra.buttonWorks(user = 0, device = "GameBEX Player 2"))
        assertFalse(ra.buttonWorks(user = 0, device = "GameSir-X5s"))
        assertFalse(ra.buttonWorks(user = 1, device = "GameBEX Player 2"))
    }
}
