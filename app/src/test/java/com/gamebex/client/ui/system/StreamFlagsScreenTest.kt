package com.gamebex.client.ui.system

import com.gamebex.client.stream.StreamFlags
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamFlagsScreenTest {
    private val toggle = StreamFlags.BY_KEY.getValue("audio_regulator")
    private val choice = StreamFlags.BY_KEY.getValue("ra_poll")

    @Test fun effectiveFallsBackToDefaultOnNullOrEmpty() {
        assertEquals("0", flagEffective(toggle, null))
        assertEquals("0", flagEffective(toggle, ""))
        assertEquals("1", flagEffective(toggle, "1"))
        assertEquals("0", flagEffective(choice, null))
        assertEquals("2", flagEffective(choice, "2"))
    }

    @Test fun valueLabelsShowOnOffAndChoiceLabels() {
        assertEquals("Off", flagValueLabel(toggle, "0"))
        assertEquals("On", flagValueLabel(toggle, "1"))
        assertEquals("Off", flagValueLabel(toggle, "bogus"))
        assertEquals("Early (default)", flagValueLabel(choice, "0"))
        assertEquals("Late", flagValueLabel(choice, "2"))
        assertEquals("9", flagValueLabel(choice, "9"))
    }

    @Test fun cycleFlipsTogglesAndWrapsChoices() {
        assertEquals("1", flagCycle(toggle, "0", 1))
        assertEquals("0", flagCycle(toggle, "1", 1))
        assertEquals("1", flagCycle(toggle, "0", -1))
        assertEquals("1", flagCycle(choice, "0", 1))
        assertEquals("0", flagCycle(choice, "2", 1))
        assertEquals("2", flagCycle(choice, "0", -1))
        // Unknown stored value restarts from the default, then steps.
        assertEquals("1", flagCycle(choice, "9", 1))
    }
}
