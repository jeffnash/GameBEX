package com.gamebex.client.together.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

/** Finds the phone's private LAN address without treating mobile/VPN links as LAN. */
object LanAddress {
    private val preferredNames = listOf("wlan0", "wlan1", "swlan0", "ap0", "softap0")
    private val skippedPrefixes = listOf("rmnet", "ccmni", "tun", "ipsec", "wg", "ppp", "dummy", "p2p", "ifb", "v4-")

    fun pick(candidates: List<Pair<String, String>>): String? {
        val usable = candidates.filter { (name, address) ->
            skippedPrefixes.none { name.startsWith(it) } && isSiteLocalIpv4(address)
        }
        return usable.sortedWith(compareBy<Pair<String, String>> {
            val index = preferredNames.indexOf(it.first)
            if (index < 0) preferredNames.size else index
        }.thenBy { it.first }).firstOrNull()?.second
    }

    internal data class NetLink(
        val wifi: Boolean,
        val ethernet: Boolean,
        val vpnTransport: Boolean,
        val notVpn: Boolean,
        val addresses: List<String>,
    )

    /** First private IPv4 on Wi-Fi or Ethernet that is not a VPN, in list order. */
    internal fun pickLan(networks: List<NetLink>): String? {
        for (network in networks) {
            if (network.vpnTransport || !network.notVpn) continue
            if (!network.wifi && !network.ethernet) continue
            network.addresses.firstOrNull { isPrivateIpv4(it) }?.let { return it }
        }
        return null
    }

    internal data class Iface(
        val name: String,
        val up: Boolean,
        val loopback: Boolean,
        val virtual: Boolean,
        val addresses: List<String>,
    )

    /** Interface scan used when [pickLan] finds no Wi-Fi or Ethernet address. */
    internal fun pickFromInterfaces(interfaces: List<Iface>, vpnInterfaceNames: Set<String>): String? {
        val candidates = buildList {
            for (network in interfaces) {
                val name = network.name
                val valid = network.up && !network.loopback && !network.virtual
                if (!valid || skippedPrefixes.any { name.startsWith(it) } || name in vpnInterfaceNames) continue
                for (text in network.addresses) {
                    if (isSiteLocalIpv4(text)) add(name to text)
                }
            }
        }
        return pick(candidates)
    }

    fun find(ctx: Context): String? {
        val manager = ctx.getSystemService(ConnectivityManager::class.java)
        val active = manager?.activeNetwork
        val networks = buildList {
            if (manager != null) {
                if (active != null) add(active)
                for (network in manager.allNetworks) {
                    if (network != active) add(network)
                }
            }
        }
        val vpnInterfaceNames = mutableSetOf<String>()
        val links = networks.map { network ->
            val caps = manager?.getNetworkCapabilities(network)
            val props = manager?.getLinkProperties(network)
            val vpnTransport = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            if (vpnTransport) props?.interfaceName?.let { vpnInterfaceNames += it }
            val addresses = props?.linkAddresses.orEmpty().mapNotNull { link ->
                (link.address as? Inet4Address)?.hostAddress
            }
            NetLink(
                wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
                ethernet = caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true,
                vpnTransport = vpnTransport,
                notVpn = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true,
                addresses = addresses,
            )
        }
        pickLan(links)?.let { return it }

        val interfaces = buildList {
            val enumerated = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return@buildList
            for (network in enumerated.asSequence()) {
                val name = network.name ?: continue
                val flags = runCatching {
                    Triple(network.isUp, network.isLoopback, network.isVirtual)
                }.getOrNull() ?: continue
                val addresses = network.inetAddresses.asSequence().mapNotNull { address ->
                    (address as? Inet4Address)?.hostAddress
                }.toList()
                add(Iface(name, flags.first, flags.second, flags.third, addresses))
            }
        }
        return pickFromInterfaces(interfaces, vpnInterfaceNames)
    }

    private fun isSiteLocalIpv4(value: String): Boolean =
        runCatching {
            val address = java.net.InetAddress.getByName(value) as? Inet4Address ?: return false
            address.isSiteLocalAddress
        }.getOrDefault(false)

    private fun isPrivateIpv4(value: String): Boolean =
        runCatching {
            val address = java.net.InetAddress.getByName(value) as? Inet4Address ?: return false
            address.isSiteLocalAddress || address.isLinkLocalAddress
        }.getOrDefault(false)
}
