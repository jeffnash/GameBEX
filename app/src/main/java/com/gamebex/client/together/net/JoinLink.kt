package com.gamebex.client.together.net

import com.gamebex.client.together.NearbyHost
import com.gamebex.client.together.TogetherCopy
import com.gamebex.client.together.TogetherPorts
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

object JoinLink {
    fun buildLink(address: String, port: Int, name: String): String =
        "gamebex://join?v=1&h=$address&p=$port&n=" +
            URLEncoder.encode(name, "UTF-8").replace("+", "%20")

    fun parseLink(uri: String): NearbyHost? {
        val u = runCatching { URI(uri.trim()) }.getOrNull() ?: return null
        if (u.scheme != "gamebex" || u.host != "join") return null
        val q = parseQuery(u.rawQuery ?: return null) ?: return null
        if (q["v"] != "1") return null
        val h = q["h"]?.takeIf { isPrivateIpv4(it) } ?: return null
        val p = q["p"]?.toIntOrNull()?.takeIf { it in 1024..65535 } ?: return null
        val n = q["n"]
        val name = when {
            n == null -> TogetherCopy.otherPhone
            n.length in 1..48 -> n
            else -> return null
        }
        return NearbyHost("", "", name, "", "", null, h, p)
    }

    fun parseAddress(text: String): NearbyHost? {
        val t = text.trim()
        val i = t.indexOf(':')
        val h = if (i < 0) t else t.substring(0, i)
        val p = if (i < 0) TogetherPorts.LINK else t.substring(i + 1).toIntOrNull() ?: return null
        if (!isPrivateIpv4(h) || p !in 1024..65535) return null
        return NearbyHost("", "", TogetherCopy.otherPhone, "", "", null, h, p)
    }

    /** Dotted IPv4 in 10/8, 172.16/12, 192.168/16 or 169.254/16. */
    fun isPrivateIpv4(s: String): Boolean {
        val parts = s.split('.')
        if (parts.size != 4) return false
        val o = IntArray(4)
        for ((idx, part) in parts.withIndex()) {
            if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) return false
            o[idx] = part.toInt()
            if (o[idx] > 255) return false
        }
        return o[0] == 10 || (o[0] == 172 && o[1] in 16..31) ||
            (o[0] == 192 && o[1] == 168) || (o[0] == 169 && o[1] == 254)
    }

    private fun parseQuery(raw: String): Map<String, String>? {
        val out = HashMap<String, String>()
        for (pair in raw.split('&')) {
            if (pair.isEmpty()) continue
            val i = pair.indexOf('=')
            if (i <= 0) return null
            out[pair.substring(0, i)] =
                runCatching { URLDecoder.decode(pair.substring(i + 1), "UTF-8") }.getOrNull() ?: return null
        }
        return out
    }
}
