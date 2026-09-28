package com.gamebex.source

import okhttp3.Cookie
import okhttp3.Dns
import okhttp3.Headers
import okhttp3.HttpUrl
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

/** Parser safety limits. ROM file streaming is not subject to metadata limits. */
const val SOURCE_MAX_METADATA_BYTES = 8 * 1024 * 1024
const val SOURCE_MAX_REDIRECTS = 20
const val SOURCE_META_CONNECT_TIMEOUT_MS = 15_000L
const val SOURCE_META_READ_TIMEOUT_MS = 30_000L
const val SOURCE_DL_CONNECT_TIMEOUT_MS = 15_000L
const val SOURCE_DL_READ_TIMEOUT_MS = 60_000L

/** Host-reserved request headers, matched case-insensitively (`Proxy-*` by prefix). */
fun isDeniedHeaderName(name: String): Boolean {
    val lower = name.lowercase()
    return lower == "cookie" || lower == "authorization" || lower == "host" ||
        lower == "content-length" || lower == "connection" || lower == "range" ||
        lower.startsWith("proxy-")
}

/**
 * Decides which URLs may be fetched and which DNS answers may be used.
 * Production code always uses [ProductionHostPolicy]; tests inject their own
 * implementation. There is no boolean switch that disables the policy.
 */
interface HostPolicy {
    fun checkUrl(url: HttpUrl, declaredOrigins: Set<String>)
    fun checkAddresses(host: String, addresses: List<InetAddress>)
}

class ProductionHostPolicy(private val sourceId: String?) : HostPolicy {
    override fun checkUrl(url: HttpUrl, declaredOrigins: Set<String>) {
        if (url.scheme != "https") violation("https is required")
        if (url.port != 443) violation("only the default https port is allowed")
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) violation("credentials in URL are not allowed")
        val host = url.host
        if (host.isEmpty()) violation("empty host")
        if (host.equals("localhost", ignoreCase = true) || host.endsWith(".localhost", ignoreCase = true)) {
            violation("localhost is not allowed")
        }
        if (host.contains(':')) violation("IP literals are not allowed")
        if (ipv4Pattern.matches(host) || numericHostPattern.matches(host)) violation("IP literals are not allowed")
        if ("https://" + host.lowercase() !in declaredOrigins) violation("origin is not declared for this purpose")
    }

    override fun checkAddresses(host: String, addresses: List<InetAddress>) {
        for (address in addresses) {
            if (!isPublicAddress(address)) violation("non-public DNS answer for $host")
        }
    }

    private fun violation(msg: String): Nothing {
        throw SourceException(SourceFailure.POLICY, sourceId, null, cause = IllegalStateException(msg))
    }

    companion object {
        private val ipv4Pattern = Regex("\\d{1,3}(\\.\\d{1,3}){3}")
        private val numericHostPattern = Regex("[0-9.]+")

        fun isPublicAddress(a: InetAddress): Boolean {
            if (a.isLoopbackAddress || a.isLinkLocalAddress || a.isMulticastAddress) return false
            if (a.isAnyLocalAddress || a.isSiteLocalAddress) return false
            val raw = a.address
            if (a is Inet6Address) {
                if (raw.size == 16 && isV4Mapped(raw)) {
                    return isPublicAddress(InetAddress.getByAddress(raw.copyOfRange(12, 16)))
                }
                return true
            }
            if (a is Inet4Address && raw.size == 4) {
                val b0 = raw[0].toInt() and 0xFF
                val b1 = raw[1].toInt() and 0xFF
                val b2 = raw[2].toInt() and 0xFF
                if (b0 == 0) return false // 0.0.0.0/8
                if (b0 == 100 && (b1 and 0xC0) == 0x40) return false // 100.64.0.0/10
                if (b0 == 192 && b1 == 0 && (b2 == 0 || b2 == 2)) return false // 192.0.0.0/24, 192.0.2.0/24
                if (b0 == 198 && b1 == 18 && (b2 and 0xFE) == 18) return false // 198.18.0.0/15
                if (b0 == 198 && b1 == 51 && b2 == 100) return false // 198.51.100.0/24
                if (b0 == 203 && b1 == 0 && b2 == 113) return false // 203.0.113.0/24
                if (b0 >= 240) return false // 240.0.0.0/4 and broadcast
            }
            return true
        }

        private fun isV4Mapped(raw: ByteArray): Boolean {
            for (i in 0..9) if (raw[i] != 0.toByte()) return false
            return raw[10] == 0xFF.toByte() && raw[11] == 0xFF.toByte()
        }
    }
}

/**
 * Carries a policy failure through OkHttp's async failure path, which only
 * delivers [java.io.IOException]. Unwrapped back into the [SourceException]
 * by the HTTP client.
 */
class PolicyDnsException(val error: SourceException) : UnknownHostException(error.message)

/**
 * Resolves through [delegate], rejects non-public answers, then applies
 * IPv4 preference. Used on the actual connection, not as a preflight.
 */
class CheckedDns(
    private val delegate: Dns = Dns.SYSTEM,
    private val policy: HostPolicy,
    private val preferIpv4: Boolean = false,
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val answers = delegate.lookup(hostname)
        if (answers.isEmpty()) throw UnknownHostException(hostname)
        try {
            policy.checkAddresses(hostname, answers)
        } catch (e: SourceException) {
            throw PolicyDnsException(e)
        }
        // sortedBy is stable: IPv4 first, original order otherwise.
        return if (preferIpv4) answers.sortedBy { it is Inet6Address } else answers
    }
}

/**
 * In-memory cookie jar scoped to one source revision. Browser-verification
 * cookies are tagged with their verified origin and never attach elsewhere.
 */
class SourceCookieJar(private val clock: () -> Long = System::currentTimeMillis) {
    private data class Entry(val cookie: Cookie, val browserOrigin: String?)

    private val entries = ArrayList<Entry>()

    @Synchronized
    fun saveFromResponse(url: HttpUrl, headers: Headers) {
        for (i in 0 until headers.size) {
            if (!headers.name(i).equals("Set-Cookie", ignoreCase = true)) continue
            val cookie = Cookie.parse(url, headers.value(i)) ?: continue
            store(cookie, null)
        }
    }

    @Synchronized
    fun importBrowserCookies(url: HttpUrl, setCookieHeaders: List<String>) {
        val origin = originOf(url)
        for (header in setCookieHeaders) {
            val cookie = Cookie.parse(url, header) ?: continue
            store(cookie, origin)
        }
    }

    private fun store(cookie: Cookie, browserOrigin: String?) {
        entries.removeAll { it.cookie.name == cookie.name && it.cookie.domain == cookie.domain && it.cookie.path == cookie.path }
        // An expired cookie is a deletion, not a value.
        if (cookie.persistent && cookie.expiresAt <= clock()) return
        entries.add(Entry(cookie, browserOrigin))
    }

    @Synchronized
    fun loadFor(url: HttpUrl): List<Cookie> {
        val now = clock()
        val origin = originOf(url)
        return entries.mapNotNull { entry ->
            val cookie = entry.cookie
            if (cookie.persistent && cookie.expiresAt <= now) return@mapNotNull null
            if (!cookie.matches(url)) return@mapNotNull null
            if (entry.browserOrigin != null && entry.browserOrigin != origin) return@mapNotNull null
            cookie
        }
    }

    @Synchronized
    fun cookieHeaderFor(url: HttpUrl): String? {
        val cookies = loadFor(url)
        if (cookies.isEmpty()) return null
        return cookies.joinToString("; ") { it.name + "=" + it.value }
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    companion object {
        fun originOf(url: HttpUrl): String {
            val port = if (url.port != HttpUrl.defaultPort(url.scheme)) ":" + url.port else ""
            return url.scheme + "://" + url.host.lowercase() + port
        }
    }
}
