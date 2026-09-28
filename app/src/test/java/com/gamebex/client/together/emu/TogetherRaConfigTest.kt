package com.gamebex.client.together.emu

import com.gamebex.client.together.Role
import com.gamebex.client.together.TogetherMode
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TogetherRaConfigTest {
    private fun golden(name: String): String =
        requireNotNull(javaClass.classLoader?.getResource("together/$name")).readText()

    @Test fun goldenConfigsMatch() {
        assertEquals(golden("host-ra-netplay.cfg"), TogetherRaConfig.cfgText("/storage/emulated/0", Role.HOST, TogetherMode.RA_NETPLAY, "10.42.1.20", "Sam"))
        assertEquals(golden("guest-ra-netplay.cfg"), TogetherRaConfig.cfgText("/storage/emulated/0", Role.GUEST, TogetherMode.RA_NETPLAY, "10.42.1.20", "Sam"))
        assertEquals(golden("gb-guest.cfg"), TogetherRaConfig.cfgText("/storage/emulated/0", Role.GUEST, TogetherMode.GB_LINK, "10.42.1.20", "Sam"))
    }

    @Test fun netplayStateAndOverridesAreIsolated() {
        val guest = TogetherRaConfig.cfgText("/storage/emulated/0", Role.GUEST, TogetherMode.RA_NETPLAY, "10.42.1.20", "Sam")
        val host = TogetherRaConfig.cfgText("/storage/emulated/0", Role.HOST, TogetherMode.RA_NETPLAY, "10.42.1.20", "Sam")
        assertTrue(guest.contains("auto_overrides_enable = \"false\""))
        assertTrue(host.contains("auto_overrides_enable = \"false\""))
    }

    // Every together RetroArch config boots fresh; battery saves keep today's folders.
    @Test fun everyRoleAndModeBootsFreshAndKeepsItsSaveFolder() {
        for (mode in listOf(TogetherMode.RA_NETPLAY, TogetherMode.GB_LINK)) {
            for (role in Role.entries) {
                val cfg = TogetherRaConfig.cfgText("/storage/emulated/0", role, mode, "10.42.1.20", "Sam")
                assertTrue("$mode $role loads a state", cfg.contains("savestate_auto_load = \"false\""))
                assertTrue("$mode $role saves a state", cfg.contains("savestate_auto_save = \"false\""))
                val isolated = mode == TogetherMode.RA_NETPLAY && role == Role.GUEST
                val saves = if (isolated) "/storage/emulated/0/GameBEX/together/saves" else "/storage/emulated/0/RetroArch/saves"
                assertTrue("$mode $role saves", cfg.contains("savefile_directory = \"$saves\""))
            }
        }
    }

    @Test fun gbDigitsAndHostOptions() {
        assertEquals(listOf("0", "1", "0", "0", "4", "2", "0", "0", "1", "0", "2", "3"), TogetherRaConfig.gbIpDigits("10.42.1.23"))
        try {
            TogetherRaConfig.gbIpDigits("256.1.1.1")
            throw AssertionError("expected invalid address")
        } catch (_: IllegalArgumentException) {
        }
        val host = TogetherRaConfig.coreOptionsText(TogetherMode.GB_LINK, Role.HOST, "10.42.1.23")
        assertFalse(host.contains("_ip_"))
    }

    @Test fun gbIpDigitsStayAsciiUnderNonRootLocale() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("ar-EG"))
        try {
            assertEquals(
                listOf("0", "1", "0", "0", "4", "2", "0", "0", "1", "0", "2", "3"),
                TogetherRaConfig.gbIpDigits("10.42.1.23"),
            )
        } finally {
            Locale.setDefault(previous)
        }
    }
}
