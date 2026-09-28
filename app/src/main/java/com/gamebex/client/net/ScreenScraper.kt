package com.gamebex.client.net

import android.content.Context
import android.util.Log
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

/**
 * ScreenScraper client (api2). Checksum first: jeuInfos by CRC32 +
 * size + file name (+ systemeid) of the local ROM ([RomFingerprint]);
 * a checksum miss falls back to name search (works for hacks, renamed
 * dumps, and merged arcade sets where CRCs can't match), then full
 * jeuInfos for the best match's media stack.
 *
 * Rules honored (per Skyscraper's proven usage): max 1 request / 1.2s,
 * softname identification, daily-quota / closed-API / blacklist markers
 * fail the worker honestly instead of hammering.
 *
 * Credentials are USER-supplied (Settings harness in game detail; stored
 * in app-private prefs, never in the repo): dev id/password identify the
 * quota bucket, ss id/password the contributing account.
 */
class ScreenScraper(
    private val http: OkHttpClient = OkHttpClient(),
    val baseUrl: String = "https://www.screenscraper.fr",
) {
    companion object {
        private const val TAG = "GameBEXSS"
        private const val PREFS = "screenscraper"
        private const val ENC_PREFS = "ss_creds_enc"
        private const val MIN_GAP_MS = 1_200L

        /** Folder slug -> ScreenScraper systemeid (Skyscraper-verified). */
        val SYSTEM_IDS = mapOf(
            "3do" to 29, "amiga" to 64, "apple2" to 86,
            "atari2600" to 26, "atari7800" to 41, "atarijaguar" to 27,
            "dragon" to 91, "dreamcast" to 23, "gamegear" to 21,
            "gb" to 9, "gba" to 12, "gbc" to 10, "gc" to 13, "genesis" to 1,
            "mame" to 75, "mastersystem" to 2, "msx" to 113,
            "n3ds" to 17, "n64" to 14, "naomi" to 75, "nds" to 15,
            "neogeo" to 142, "neogeocd" to 70, "nes" to 3, "ngpc" to 82,
            "openbor" to 214, "pc98" to 208, "pcfx" to 72, "pico8" to 234,
            "pokemini" to 211, "ps2" to 58, "psp" to 61, "psx" to 57,
            "saturn" to 22, "scummvm" to 123, "sega32x" to 19, "segacd" to 20,
            "snes" to 4, "switch" to 225, "tg16" to 31, "virtualboy" to 11,
            "wii" to 16, "wiiu" to 18,
            "windows" to 135, "win9x" to 135, "windows3x" to 135,
        )

        /** Slot -> preferred media types, in order. */
        val SLOT_TYPES = mapOf(
            "box" to listOf("box-2D", "box-3D"),
            "hero" to listOf("fanart"),
            "logo" to listOf("wheel", "wheel-carbon", "wheel-steel"),
            "shot" to listOf("screenshot", "screenshot-title", "titlescreen"),
        )
        private val REGION_PREF = listOf("us", "wor", "eu", "ss", "jp", "fr", "de")

        data class Creds(val devId: String, val devPassword: String, val ssid: String, val sspassword: String) {
            fun complete(): Boolean = devId.isNotBlank() && devPassword.isNotBlank()
        }

        fun loadCreds(ctx: Context): Creds {
            runCatching { return loadEnc(ctx) }
            return loadPlain(ctx)
        }

        fun saveCreds(ctx: Context, c: Creds) {
            val saved = runCatching { saveEnc(ctx, c) }.isSuccess
            if (!saved) savePlain(ctx, c)
        }

        private fun encPrefs(ctx: Context): android.content.SharedPreferences {
            val master = androidx.security.crypto.MasterKey.Builder(ctx)
                .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                .build()
            return androidx.security.crypto.EncryptedSharedPreferences.create(
                ctx, ENC_PREFS, master,
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }

        private fun readCreds(p: android.content.SharedPreferences): Creds = Creds(
            p.getString("devid", "").orEmpty(), p.getString("devpassword", "").orEmpty(),
            p.getString("ssid", "").orEmpty(), p.getString("sspassword", "").orEmpty(),
        )

        private fun loadEnc(ctx: Context): Creds {
            val c = readCreds(encPrefs(ctx))
            if (c.devId.isBlank() && c.ssid.isBlank()) {
                // Creds saved while the keystore was unavailable: move them in.
                val plain = loadPlain(ctx)
                if (plain.devId.isNotBlank() || plain.ssid.isNotBlank()) {
                    saveEnc(ctx, plain)
                    return plain
                }
            }
            return c
        }

        private fun saveEnc(ctx: Context, c: Creds) {
            encPrefs(ctx).edit()
                .putString("devid", c.devId.trim()).putString("devpassword", c.devPassword.trim())
                .putString("ssid", c.ssid.trim()).putString("sspassword", c.sspassword.trim())
                .apply()
            clearPlainCreds(ctx)
        }

        private fun loadPlain(ctx: Context): Creds =
            readCreds(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

        private fun savePlain(ctx: Context, c: Creds) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("devid", c.devId.trim()).putString("devpassword", c.devPassword.trim())
                .putString("ssid", c.ssid.trim()).putString("sspassword", c.sspassword.trim())
                .apply()
        }

        /** Drops plaintext fallback creds once encrypted (test override key stays). */
        private fun clearPlainCreds(ctx: Context) {
            runCatching {
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .remove("devid").remove("devpassword").remove("ssid").remove("sspassword")
                    .apply()
            }
        }

        /** Checksum-lookup verdict from the raw response, before JSON parsing. */
        enum class HashStatus { PARSE, MISS, UNAVAILABLE }

        /**
         * Pure: classify a jeuInfos-by-checksum response. 404/400 and SS's
         * "not found" text are misses (fall back to name search); no
         * response, service markers, and auth/quota codes are unavailable
         * (the name search would hit the same wall).
         */
        fun classifyHashResponse(httpCode: Int?, body: String?): HashStatus {
            if (httpCode == null) return HashStatus.UNAVAILABLE
            if (body != null && serviceMarkerOf(body) != null) return HashStatus.UNAVAILABLE
            if (httpCode == 404 || httpCode == 400) return HashStatus.MISS
            if (httpCode !in 200..299 || body == null) return HashStatus.UNAVAILABLE
            // Plain-text errors only: a JSON game body may mention anything.
            if (!body.trimStart().startsWith("{")) {
                val lower = body.lowercase()
                if ("non trouv" in lower || "not found" in lower) return HashStatus.MISS
            }
            return HashStatus.PARSE
        }

        /** Pure: name search runs when no checksum was tried or the checksum missed. */
        fun shouldNameSearch(hash: HashStatus?): Boolean = hash == null || hash == HashStatus.MISS

        /**
         * Pure: accept a checksum hit? SS falls back to fuzzy file-name
         * matching when no checksum matches, so a hit needs our CRC among
         * its ROMs, or (no ROM CRCs listed) a title that agrees with ours.
         * A known system mismatch always rejects.
         */
        fun acceptHashHit(
            ourCrc: String,
            hitRomCrcs: List<String>,
            titleScore: Int,
            hitSys: Int?,
            wantSys: Int?,
        ): Boolean {
            if (wantSys != null && hitSys != null && hitSys != wantSys) return false
            if (hitRomCrcs.any { it.equals(ourCrc, ignoreCase = true) }) return true
            if (hitRomCrcs.isNotEmpty()) return false
            return titleScore >= 30
        }

        internal fun serviceMarkerOf(body: String): String? = when {
            body.contains("API totalement ferm") -> "API closed, try later"
            body.contains("blacklist") -> "client blacklisted"
            body.contains("Votre quota") -> "daily quota reached"
            else -> null
        }

        /** Test override for the mock-server harness (empty = production). */
        fun baseOverride(ctx: Context): String =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("base_override", "").orEmpty()

        fun withOverrides(ctx: Context): ScreenScraper {
            val o = baseOverride(ctx)
            return if (o.isBlank()) ScreenScraper() else ScreenScraper(baseUrl = o)
        }
    }

    data class Media(val type: String, val region: String, val url: String)
    data class GameHit(val ssId: Long, val title: String, val systemId: Int?)
    data class GameInfo(
        val ssId: Long,
        val title: String,
        val synopsis: String?,
        val medias: List<Media>,
    )

    sealed interface Lookup {
        data class Found(val info: GameInfo) : Lookup
        data class NotFound(val searched: String) : Lookup
        /** No credentials configured. */
        data object NoCreds : Lookup
        /** API closed / blacklisted / quota out / network down. Retryable later. */
        data class Unavailable(val reason: String) : Lookup
    }

    @Volatile private var lastCallMs = 0L

    private suspend fun throttle() {
        val wait = MIN_GAP_MS - (System.currentTimeMillis() - lastCallMs)
        if (wait > 0) kotlinx.coroutines.delay(wait)
    }

    /** Raw response: [code] null = no response at all. */
    private data class Resp(val code: Int?, val body: String?)

    /**
     * One throttled GET off the main thread. URLs carry credentials: never
     * log them (nor exception messages, which may echo the URL).
     */
    private suspend fun fetch(url: String): Resp = withContext(Dispatchers.IO) {
        throttle()
        try {
            val req = Request.Builder().url(url)
                .header("User-Agent", "GameBEXClient/1.0").build()
            http.newCall(req).execute().use { resp ->
                lastCallMs = System.currentTimeMillis()
                if (!resp.isSuccessful) Log.w(TAG, "SS HTTP ${resp.code}")
                Resp(resp.code, runCatching { resp.body?.string() }.getOrNull())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastCallMs = System.currentTimeMillis()
            Log.w(TAG, "SS request failed: ${e.javaClass.simpleName}")
            Resp(null, null)
        }
    }

    private suspend fun get(url: String): String? {
        val r = fetch(url)
        return if (r.code != null && r.code in 200..299) r.body else null
    }

    private fun auth(creds: Creds): String {
        val e = { s: String -> URLEncoder.encode(s, "UTF-8") }
        var q = "devid=${e(creds.devId)}&devpassword=${e(creds.devPassword)}&softname=gamebex&output=json"
        if (creds.ssid.isNotBlank()) q += "&ssid=${e(creds.ssid)}"
        if (creds.sspassword.isNotBlank()) q += "&sspassword=${e(creds.sspassword)}"
        return q
    }

    /**
     * Name lookup: search candidates, score against the local title,
     * fetch full infos for the winner. systemeid narrows when known;
     * without it the search spans all systems.
     */
    suspend fun lookup(
        title: String,
        folderSlug: String?,
        creds: Creds,
        rom: RomFingerprint? = null,
    ): Lookup {
        if (!creds.complete()) return Lookup.NoCreds
        val query = cleanTitle(title)
        val sys = folderSlug?.let(SYSTEM_IDS::get)
        // Checksum first; only a clean miss falls through to name search.
        var hashStatus: HashStatus? = null
        if (rom != null) {
            when (val h = hashLookup(rom, query, sys, creds)) {
                is HashResult.Hit -> return Lookup.Found(h.info)
                is HashResult.Unavailable -> return Lookup.Unavailable(h.reason)
                HashResult.Miss -> hashStatus = HashStatus.MISS
            }
        }
        if (!shouldNameSearch(hashStatus)) return Lookup.NotFound(query)
        return nameLookup(title, query, sys, creds)
    }

    private sealed interface HashResult {
        data class Hit(val info: GameInfo) : HashResult
        data object Miss : HashResult
        data class Unavailable(val reason: String) : HashResult
    }

    /** jeuInfos by CRC32 + size + file name (+ systemeid when known). */
    private suspend fun hashLookup(rom: RomFingerprint, query: String, sys: Int?, creds: Creds): HashResult {
        val e = { v: String -> URLEncoder.encode(v, "UTF-8") }
        val url = "$baseUrl/api2/jeuInfos.php?${auth(creds)}" +
            "&romtype=rom&crc=${e(rom.crc)}&romtaille=${rom.size}&romnom=${e(rom.name)}" +
            (if (sys != null) "&systemeid=$sys" else "")
        val resp = fetch(url)
        when (classifyHashResponse(resp.code, resp.body)) {
            HashStatus.MISS -> return HashResult.Miss
            HashStatus.UNAVAILABLE -> return HashResult.Unavailable(
                resp.body?.let(::serviceMarkerOf) ?: if (resp.code == null) "network" else "HTTP ${resp.code}",
            )
            HashStatus.PARSE -> Unit
        }
        return try {
            val root = JSONObject(resp.body ?: return HashResult.Miss)
            val jeu = root.optJSONObject("response")?.optJSONObject("jeu") ?: return HashResult.Miss
            val info = parseInfos(root) ?: return HashResult.Miss
            val crcs = (asArray(jeu.opt("roms")) + asArray(jeu.opt("rom")))
                .mapNotNull { it.optString("romcrc", "").takeIf(String::isNotBlank) }
            val hitSys = jeu.optJSONObject("systeme")?.optInt("id", -1)?.takeIf { it >= 0 }
            val titleScore = if (query.isBlank()) 0 else score(info.title, query, null, null)
            if (acceptHashHit(rom.crc, crcs, titleScore, hitSys, sys)) {
                Log.d(TAG, "SS checksum hit (game ${info.ssId})")
                HashResult.Hit(info)
            } else {
                Log.d(TAG, "SS checksum answer rejected (game ${info.ssId})")
                HashResult.Miss
            }
        } catch (e: Exception) {
            Log.w(TAG, "SS checksum parse failed: ${e.javaClass.simpleName}")
            HashResult.Miss
        }
    }

    /** Name search. */
    private suspend fun nameLookup(title: String, query: String, sys: Int?, creds: Creds): Lookup {
        if (query.isBlank()) return Lookup.NotFound(title)
        val url = "$baseUrl/api2/jeuRecherche.php?${auth(creds)}" +
            (if (sys != null) "&systemeid=$sys" else "") +
            "&recherche=${URLEncoder.encode(query, "UTF-8")}"
        val body = get(url) ?: return Lookup.Unavailable("network")
        serviceMarker(body)?.let { return Lookup.Unavailable(it) }
        val hits = parseHits(body)
        if (hits.isEmpty()) return Lookup.NotFound(query)
        val best = hits.maxByOrNull { score(it.title, query, it.systemId, sys) }
            ?: return Lookup.NotFound(query)
        if (score(best.title, query, best.systemId, sys) <= 0) {
            Log.d(TAG, "SS no confident match for '$query' (${hits.size} candidates)")
            return Lookup.NotFound(query)
        }
        return infos(best.ssId, creds)?.let { Lookup.Found(it) }
            ?: Lookup.Unavailable("infos failed")
    }

    /** Full game infos (media stack) by ScreenScraper id. */
    suspend fun infos(ssId: Long, creds: Creds): GameInfo? {
        if (!creds.complete()) return null
        val url = "$baseUrl/api2/jeuInfos.php?${auth(creds)}&gameid=$ssId"
        val body = get(url) ?: return null
        if (serviceMarker(body) != null) return null
        return try {
            parseInfos(JSONObject(body))
        } catch (e: Exception) {
            Log.w(TAG, "SS infos parse failed", e)
            null
        }
    }

    /** Pick one URL per slot from a media list (region-preferred). */
    fun pickSlots(medias: List<Media>): Map<String, String> {
        val out = mutableMapOf<String, String>()
        for ((slot, types) in SLOT_TYPES) {
            val cands = medias.filter { it.type in types }
            if (cands.isEmpty()) continue
            val pick = REGION_PREF.firstNotNullOfOrNull { r -> cands.firstOrNull { it.region == r } }
                ?: cands.first()
            out[slot] = pick.url
        }
        return out
    }

    // Parsing (lenient: SS collapses 1-item arrays to objects).

    private fun serviceMarker(body: String): String? = serviceMarkerOf(body)

    private fun parseHits(body: String): List<GameHit> {
        val out = mutableListOf<GameHit>()
        try {
            val jeux = JSONObject(body).optJSONObject("response")?.opt("jeux") ?: return out
            for (o in asArray(jeux)) {
                val id = o.optLong("id", 0)
                if (id <= 0) continue
                val title = firstText(o.opt("noms")) ?: continue
                val sys = o.optJSONObject("systeme")?.optInt("id", -1)?.takeIf { it >= 0 }
                out.add(GameHit(id, title, sys))
            }
        } catch (e: Exception) {
            Log.w(TAG, "SS hits parse failed", e)
        }
        return out
    }

    private fun parseInfos(root: JSONObject): GameInfo? {
        val jeu = root.optJSONObject("response")?.optJSONObject("jeu") ?: return null
        val id = jeu.optLong("id", 0)
        if (id <= 0) return null
        val title = firstText(jeu.opt("noms")) ?: "?"
        val synopsis = jeu.opt("synopsis")?.let { firstText(it, "texte") }
        val medias = mutableListOf<Media>()
        for (m in asArray(jeu.opt("medias") ?: return GameInfo(id, title, synopsis, medias))) {
            val url = m.optString("url", "")
            if (url.isBlank()) continue
            medias.add(Media(m.optString("type", ""), m.optString("region", ""), url))
        }
        return GameInfo(id, title, synopsis, medias)
    }

    /** SS quirk: a lone item is an object, several are an array. */
    private fun asArray(v: Any?): List<JSONObject> = when (v) {
        is org.json.JSONArray -> (0 until v.length()).mapNotNull { v.optJSONObject(it) }
        is JSONObject -> listOf(v)
        else -> emptyList()
    }

    private fun firstText(v: Any?, field: String = "texte"): String? = when (v) {
        is org.json.JSONArray -> (0 until v.length()).mapNotNull {
            v.optJSONObject(it)?.optString(field, "")?.takeIf(String::isNotBlank)
        }.firstOrNull()
        is JSONObject -> v.optString(field, "").takeIf(String::isNotBlank)
        is String -> v.takeIf(String::isNotBlank)
        else -> null
    }

    /** Strip tags/hacks/regions for search + compare. Visible for tests. */
    fun cleanTitle(title: String): String = com.gamebex.client.data.TitleText.stripTags(title)
        .replace('_', ' ').replace('-', ' ')
        .replace(Regex("\\s+"), " ").trim()

    private fun norm(s: String): String =
        cleanTitle(s).lowercase().replace(Regex("[^a-z0-9 ]"), "").replace(Regex("\\s+"), " ").trim()

    /** 0 = reject; system match breaks title ties. Visible for tests. */
    fun score(candidate: String, query: String, candSys: Int?, wantSys: Int?): Int {
        val c = norm(candidate)
        val q = norm(query)
        if (c.isEmpty() || q.isEmpty()) return 0
        val base = when {
            c == q -> 100
            c.startsWith(q) || q.startsWith(c) -> 60
            c.contains(q) || q.contains(c) -> 30
            else -> {
                val ct = c.split(' ').toSet()
                val qt = q.split(' ').toSet()
                val overlap = ct.intersect(qt).size
                if (overlap * 2 >= qt.size && overlap > 0) 10 else 0
            }
        }
        if (base <= 0) return 0
        return base + if (wantSys != null && candSys == wantSys) 25 else 0
    }
}
