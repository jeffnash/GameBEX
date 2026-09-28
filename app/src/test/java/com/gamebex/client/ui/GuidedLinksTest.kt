package com.gamebex.client.ui

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guided-origin dispatch: direct pkgs install, guided pkgs open links. */
class GuidedLinksTest {
    @Test fun dolphinIsGuidedWithBothTargets() {
        val links = guidedLinksFor("org.dolphinemu.dolphinemu")!!
        assertTrue(links.playUrl.contains("play.google.com"))
        assertTrue(links.siteUrl.contains("dolphin-emu.org"))
    }

    @Test fun directOriginsAreNotGuided() {
        assertNull(guidedLinksFor("com.retroarch.aarch64"))
        assertNull(guidedLinksFor("org.ppsspp.ppsspp"))
        assertNull(guidedLinksFor("not.a.package"))
    }
}
