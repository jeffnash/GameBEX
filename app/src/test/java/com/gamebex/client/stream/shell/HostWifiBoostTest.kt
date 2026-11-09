package com.gamebex.client.stream.shell

import org.junit.Assert.assertEquals
import org.junit.Test

class HostWifiBoostTest {
    @Test fun foregroundFallbackAndWorkSourceFailureEachLogOnce() {
        val logs = mutableListOf<String>()
        val warnings = HostWifiBoostWarnings(logs::add)
        repeat(3) {
            warnings.foregroundFallback()
            warnings.workSourceFailure(IllegalStateException("unavailable"))
        }
        assertEquals(listOf(
            "wifi boost top uid unavailable; using all foreground apps",
            "wifi boost WorkSource unavailable: unavailable",
        ), logs)
    }
}
