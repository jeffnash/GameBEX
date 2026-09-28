package com.gamebex.client.ui.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Detail hero: local files vs remote catalog URLs route to separate slots. */
class HeroArtTest {
    @Test fun httpRefsAreRemote() {
        assertTrue(isRemoteArtRef("https://acme.com/x.png"))
        assertTrue(isRemoteArtRef("http://acme.com/x.png"))
        assertFalse(isRemoteArtRef("/data/art/hero.png"))
        assertFalse(isRemoteArtRef(null))
        assertFalse(isRemoteArtRef(""))
    }

    @Test fun localFileWinsOverRemote() {
        val (local, remote) = resolveHeroArt(
            listOf("https://img/hero.png", "/art/box.png"),
        ) { it.startsWith("/art/") }
        assertEquals("/art/box.png", local)
        assertNull(remote)
    }

    @Test fun remoteUsedWhenNoLocalFile() {
        val (local, remote) = resolveHeroArt(
            listOf(null, "https://img/cover.png"),
        ) { false }
        assertNull(local)
        assertEquals("https://img/cover.png", remote)
    }

    @Test fun staleLocalPathFallsThroughToRemote() {
        val (local, remote) = resolveHeroArt(
            listOf("/art/missing.png", "https://img/cover.png"),
        ) { false }
        assertNull(local)
        assertEquals("https://img/cover.png", remote)
    }

    @Test fun noArtGivesNulls() {
        assertEquals(null to null, resolveHeroArt(listOf(null, "")) { true })
    }
}
