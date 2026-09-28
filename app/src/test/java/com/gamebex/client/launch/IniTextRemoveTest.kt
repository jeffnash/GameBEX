package com.gamebex.client.launch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class IniTextRemoveTest {
    @Test fun removeOnlyMatchingSectionKey() {
        val input = "[Network]\r\nEnableWlan = True\r\n\r\n[Other]\r\nEnableWlan = False\r\n"
        assertEquals("[Network]\r\n\r\n[Other]\r\nEnableWlan = False\r\n", IniText.remove(input, "Network", "EnableWlan"))
    }

    @Test fun removeKeepsCrlfAndTrailingNewline() {
        val input = "[Network]\r\nEnableWlan = True\r\nPortOffset = 10000\r\n"
        assertEquals("[Network]\r\nPortOffset = 10000\r\n", IniText.remove(input, "Network", "EnableWlan"))
    }

    @Test fun missingKeyReturnsSameInstance() {
        val input = "[Network]\nPortOffset = 10000\n"
        assertSame(input, IniText.remove(input, "Network", "EnableWlan"))
    }

    @Test fun removeEmptySectionRemovesOnlyHeader() {
        val input = "[Network]\n\n\n[Other]\nkey = value\n"
        assertEquals("\n\n[Other]\nkey = value\n", IniText.removeEmptySection(input, "Network"))
        assertSame(input, IniText.removeEmptySection(input, "Other"))
    }
}
