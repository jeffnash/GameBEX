package com.gamebex.client.together.proto

import com.gamebex.client.together.ByeReason
import com.gamebex.client.together.CoreBuild
import com.gamebex.client.together.GuestPhase
import com.gamebex.client.together.LinkMsg
import com.gamebex.client.together.OfferedGame
import com.gamebex.client.together.Role
import com.gamebex.client.together.TogetherMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkJsonTest {
    private val game = OfferedGame("Tetris", "nes", "tetris.nes", 1234L, "ABCDEF12", 1234L, "mesen")
    private val core = CoreBuild("mesen", "a".repeat(64), 99L)

    @Test fun everyMessageSubtypeRoundTripsWithDiscriminator() {
        val messages = listOf<LinkMsg>(
            LinkMsg.Hello(1, Role.GUEST, "device", "Sam", "1.0"),
            LinkMsg.Offer("session", TogetherMode.RA_NETPLAY, game, core, "10.42.1.2"),
            LinkMsg.GuestState("session", GuestPhase.DOWNLOADING, "copying", 42),
            LinkMsg.CoreRequest("session", "mesen"),
            LinkMsg.CoreBegin("session", 3, core),
            LinkMsg.CoreEnd("session", 3),
            LinkMsg.CoreUnavailable("session", "mesen"),
            LinkMsg.Launch("session", 1),
            LinkMsg.HostLive("session", 1),
            LinkMsg.Ping(7),
            LinkMsg.Pong(7),
            LinkMsg.Bye(ByeReason.LEFT),
        )
        for (message in messages) {
            val text = LinkJson.encode(message)
            assertTrue(text.contains("\"t\":"))
            assertEquals(message, LinkJson.decode(text))
        }
        assertTrue(LinkJson.encode(LinkMsg.Hello(1, Role.GUEST, "d", "n", "v")).contains("\"t\":\"hello\""))
    }

    @Test fun unknownTypeIsDropped() {
        assertNull(LinkJson.decode("{\"t\":\"future\",\"x\":1}"))
    }

    @Test fun unknownFieldsAreIgnored() {
        val decoded = LinkJson.decode("{\"t\":\"ping\",\"n\":3,\"future\":true}")
        assertEquals(LinkMsg.Ping(3), decoded)
    }

    @Test fun nullsAreNotEncoded() {
        val text = LinkJson.encode(LinkMsg.Hello(1, Role.GUEST, "d", "n", "v", null, null))
        assertFalse(text.contains("null"))
        assertNotNull(LinkJson.decode(text))
    }
}
