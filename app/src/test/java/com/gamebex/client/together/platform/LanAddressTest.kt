package com.gamebex.client.together.platform

import org.junit.Assert.assertEquals
import org.junit.Test

class LanAddressTest {
    @Test
    fun pick_prefersWifiInterfaceOrder() {
        assertEquals(
            "10.42.1.2",
            LanAddress.pick(listOf("ap0" to "10.42.1.4", "swlan0" to "10.42.1.3", "wlan0" to "10.42.1.2")),
        )
    }

    @Test
    fun pick_skipsMobileAndVpnInterfaces() {
        assertEquals("10.42.1.3", LanAddress.pick(listOf("rmnet0" to "10.42.1.2", "tun0" to "10.0.0.2", "wlan0" to "10.42.1.3")))
    }

    @Test
    fun pick_ignoresLinkLocalIpv4() {
        assertEquals("10.42.1.3", LanAddress.pick(listOf("wlan0" to "169.254.1.2", "ap0" to "10.42.1.3")))
    }

    @Test
    fun pick_ignoresPublicIpv4() {
        assertEquals("10.42.1.3", LanAddress.pick(listOf("wlan0" to "8.8.8.8", "ap0" to "10.42.1.3")))
    }

    @Test
    fun pick_emptyCandidatesReturnsNull() {
        assertEquals(null, LanAddress.pick(emptyList()))
    }

    @Test
    fun pickLan_skipsWifiVpnAndReturnsRealWifi() {
        val realWifi = LanAddress.NetLink(
            wifi = true,
            ethernet = false,
            vpnTransport = false,
            notVpn = true,
            addresses = listOf("10.42.1.50"),
        )
        val wifiOverVpn = LanAddress.NetLink(
            wifi = true,
            ethernet = false,
            vpnTransport = true,
            notVpn = false,
            addresses = listOf("10.8.0.2"),
        )
        assertEquals("10.42.1.50", LanAddress.pickLan(listOf(wifiOverVpn, realWifi)))
        assertEquals("10.42.1.50", LanAddress.pickLan(listOf(wifiOverVpn.copy(notVpn = true), realWifi)))
        assertEquals(
            "10.42.1.50",
            LanAddress.pickLan(listOf(wifiOverVpn.copy(vpnTransport = false, addresses = listOf("10.9.0.2")), realWifi)),
        )
    }

    @Test
    fun pick_skipsIpsecWireguardAndPpp() {
        assertEquals(
            null,
            LanAddress.pick(
                listOf(
                    "ipsec0" to "10.1.0.2",
                    "wg0" to "10.2.0.2",
                    "ppp0" to "10.3.0.2",
                ),
            ),
        )
    }

    @Test
    fun pickFromInterfaces_skipsVpnTransportInterface() {
        val wifi = LanAddress.Iface(
            name = "wlan0",
            up = true,
            loopback = false,
            virtual = false,
            addresses = listOf("10.42.1.9"),
        )
        assertEquals("10.42.1.9", LanAddress.pickFromInterfaces(listOf(wifi), emptySet()))
        assertEquals(null, LanAddress.pickFromInterfaces(listOf(wifi), setOf("wlan0")))
    }
}
