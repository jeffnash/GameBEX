package com.gamebex.client.together.net

import com.gamebex.client.together.TogetherPorts
import com.gamebex.client.together.TogetherCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class JoinLinkTest {
    @Test fun buildAndParseRoundTrip() {
        val link = JoinLink.buildLink("10.42.1.20", 56000, "moto g 2025 ·72")
        val host = JoinLink.parseLink(link)
        assertNotNull(host)
        assertEquals("10.42.1.20", host!!.address)
        assertEquals(56000, host.port)
        assertEquals("moto g 2025 ·72", host.name)
    }

    @Test fun invalidLinksAreRejected() {
        val valid = "gamebex://join?v=1&h=10.42.1.20&p=55440&n=Sam"
        assertNull(JoinLink.parseLink(valid.replace("gamebex", "https")))
        assertNull(JoinLink.parseLink(valid.replace("v=1", "v=2")))
        assertNull(JoinLink.parseLink(valid.replace("10.42.1.20", "8.8.8.8")))
        assertNull(JoinLink.parseLink(valid.replace("10.42.1.20", "172.32.0.1")))
        assertNull(JoinLink.parseLink(valid.replace("p=55440", "p=80")))
        assertNull(JoinLink.parseLink(valid.replace("p=55440", "p=70000")))
        assertNull(JoinLink.parseLink(valid.replace("10.42.1.20", "10.42.1")))
        assertNull(JoinLink.parseLink(valid.replace("Sam", "x".repeat(49))))
        assertEquals(TogetherCopy.otherPhone, JoinLink.parseLink(valid.substringBefore("&n="))!!.name)
    }

    @Test fun addressesParse() {
        assertEquals(TogetherPorts.LINK, JoinLink.parseAddress("10.42.1.20")!!.port)
        assertEquals(56000, JoinLink.parseAddress("10.42.1.20:56000")!!.port)
        assertEquals("10.0.0.5", JoinLink.parseAddress(" 10.0.0.5 ")!!.address)
        assertNull(JoinLink.parseAddress("abc"))
        assertNull(JoinLink.parseAddress("10.42.1.20:x"))
    }
}
