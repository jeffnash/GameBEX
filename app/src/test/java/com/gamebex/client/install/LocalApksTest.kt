package com.gamebex.client.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The installer uses a copy of the pinned APK on the phone before downloading. */
class LocalApksTest {
    @get:Rule val tmp = TemporaryFolder()
    private val url = "https://buildbot.libretro.com/nightly/android/2026-09-27-RetroArch_aarch64.apk"

    @Test fun pinnedCopyIsUsed() {
        val copy = tmp.newFile("2026-09-27-RetroArch_aarch64.apk").apply { writeText("apk") }
        assertEquals(copy, LocalApks.find(tmp.root, url, "ABC123") { "abc123" })
    }

    @Test fun aDifferentBuildOrNoFileIsIgnored() {
        tmp.newFile("2026-09-27-RetroArch_aarch64.apk").writeText("apk")
        assertNull(LocalApks.find(tmp.root, url, "abc123") { "fff000" })
        assertNull(LocalApks.find(tmp.root, "https://example.org/other.apk", "abc123") { "abc123" })
    }

    @Test fun theNameComesFromTheUrlPathOnly() {
        assertEquals("app.apk", LocalApks.fileName("https://example.org/dl/app.apk?x=1"))
        assertNull(LocalApks.fileName("https://example.org/dl/"))
        assertNull(LocalApks.fileName("https://example.org/dl/..%2Fx.apk"))
    }
}
