package com.gamebex.source

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Policy-checked HTTP execution for one immutable source revision.
 * Owns the metadata client, the cached download client, and the
 * source-scoped cookie jar.
 */
class SourceHttp(
    val definition: ValidatedDefinition,
    val revision: String,
    val jar: SourceCookieJar = SourceCookieJar(),
    private val policy: HostPolicy = ProductionHostPolicy(definition.id),
    private val dns: Dns = CheckedDns(Dns.SYSTEM, policy, definition.def.defaults.preferIpv4),
    private val clientConfigurer: (OkHttpClient.Builder) -> Unit = {},
    private val isValid: () -> Boolean = { true },
) : SourceTransport {
    private val sourceId: String get() = definition.id

    private val declaredByPurpose: Map<NetworkPurpose, Set<String>> = mapOf(
        NetworkPurpose.CATALOG to normalizeAll(definition.def.origins.catalog),
        NetworkPurpose.DOWNLOAD to normalizeAll(definition.def.origins.download),
        NetworkPurpose.ARTWORK to normalizeAll(definition.def.origins.artwork),
        NetworkPurpose.ASSIST to normalizeAll(definition.def.origins.assist),
    )

    private val metaClient: OkHttpClient by lazy {
        baseBuilder()
            .connectTimeout(SOURCE_META_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(SOURCE_META_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    private val cachedDownloadClient: OkHttpClient by lazy {
        baseBuilder()
            .connectTimeout(SOURCE_DL_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(SOURCE_DL_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(SOURCE_DL_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    private fun baseBuilder(): OkHttpClient.Builder = OkHttpClient.Builder()
        .dns(dns)
        .cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false)
        .followSslRedirects(false)
        .also(clientConfigurer)

    /**
     * Coil/OkHttp client for artwork: checked DNS, no cookies, OkHttp
     * redirect following with a network interceptor re-validating EVERY
     * hop (initial URL plus each redirect target) against the artwork
     * origins. A redirect to a private/unapproved origin fails the load.
     */
    fun artworkHttpClient(): OkHttpClient {
        val artwork = declaredByPurpose[NetworkPurpose.ARTWORK].orEmpty()
        return baseBuilder()
            .connectTimeout(SOURCE_META_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(SOURCE_META_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .addNetworkInterceptor { chain ->
                // IOException form: image loaders report it as a load
                // error (placeholder) instead of crashing the caller.
                try {
                    policy.checkUrl(chain.request().url, artwork)
                } catch (e: IOException) {
                    throw e
                } catch (e: Exception) {
                    throw IOException("artwork origin rejected: ${chain.request().url}", e)
                }
                chain.proceed(chain.request())
            }
            .build()
    }

    /** Transfer client: fresh pool per attempt, or the shared per-revision client. */
    fun downloadClient(freshConnection: Boolean): OkHttpClient {
        if (!freshConnection) return cachedDownloadClient
        return baseBuilder()
            .connectTimeout(SOURCE_DL_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(SOURCE_DL_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(SOURCE_DL_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .connectionPool(ConnectionPool(0, 1, TimeUnit.NANOSECONDS))
            .build()
    }

    fun allowedOrigins(vararg purposes: NetworkPurpose): Set<String> =
        purposes.flatMap { declaredByPurpose[it].orEmpty() }.toSet()

    fun checkTransferUrl(url: String): HttpUrl =
        parseAndCheck(url, declaredByPurpose[NetworkPurpose.DOWNLOAD].orEmpty())

    fun checkArtworkUrl(url: String): HttpUrl =
        parseAndCheck(url, declaredByPurpose[NetworkPurpose.ARTWORK].orEmpty())

    fun checkAssistUrl(url: String): HttpUrl = parseAndCheck(
        url,
        allowedOrigins(NetworkPurpose.CATALOG, NetworkPurpose.DOWNLOAD, NetworkPurpose.ASSIST),
    )

    fun checkTransferHeaders(headers: Map<String, String>) {
        for ((name, value) in headers) {
            if (isDeniedHeaderName(name)) policyFailure("transfer header is host-reserved: $name")
            if (name.contains('\r') || name.contains('\n') || value.contains('\r') || value.contains('\n')) {
                policyFailure("CR/LF in transfer header")
            }
        }
    }

    fun cookiesFor(url: HttpUrl): List<Cookie> = jar.loadFor(url)

    override suspend fun execute(request: EvaluatedRequest): SourceResponse {
        // Revocation precedes every other check: a disabled/removed source
        // loses network access even through a retained instance.
        if (!isValid()) throw SourceException(SourceFailure.UNAVAILABLE, sourceId, null)
        val declared = declaredByPurpose[request.purpose].orEmpty()
        var method = request.method
        if (method != "GET" && method != "POST") policyFailure("method must be GET or POST")
        // Definition defaults lose to per-request headers, case-insensitively.
        val headers = LinkedHashMap<String, Pair<String, String>>()
        for ((name, value) in definition.def.defaults.headers) {
            checkHeader(name, value)
            headers[name.lowercase()] = name to value
        }
        for ((name, value) in request.headers) {
            checkHeader(name, value)
            headers[name.lowercase()] = name to value
        }
        var form = request.form
        var current: HttpUrl = parseAndCheck(request.url, declared)
            .newBuilder()
            .also { builder -> for ((k, v) in request.query) builder.addQueryParameter(k, v) }
            .build()
        var hops = 0
        while (true) {
            val call = metaClient.newCall(buildRequest(current, method, form, headers.values.toList()))
            val response = awaitCall(call)
            try {
                jar.saveFromResponse(current, response.headers)
                val code = response.code
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    val location = response.header("Location")
                    if (location != null) {
                        if (hops >= SOURCE_MAX_REDIRECTS) policyFailure("too many redirects")
                        val next = current.resolve(location) ?: policyFailure("bad redirect target")
                        policy.checkUrl(next, declared)
                        if (code == 307 || code == 308) {
                            // Keep method and body.
                        } else {
                            method = "GET"
                            form = emptyMap()
                        }
                        current = next
                        hops++
                        continue
                    }
                }
                if (code !in 200..299) {
                    throw SourceException(SourceFailure.HTTP, sourceId, null, statusCode = code)
                }
                return SourceResponse(code, exposedHeaders(response.headers), readCapped(response), current.toString())
            } finally {
                response.close()
            }
        }
    }

    private fun buildRequest(
        url: HttpUrl,
        method: String,
        form: Map<String, String>,
        headers: List<Pair<String, String>>,
    ): Request {
        val builder = Request.Builder().url(url)
        if (method == "POST") {
            builder.post(FormBody.Builder().also { b -> for ((k, v) in form) b.add(k, v) }.build())
        } else {
            builder.get()
        }
        for ((name, value) in headers) builder.header(name, value)
        jar.cookieHeaderFor(url)?.let { builder.header("Cookie", it) }
        return builder.build()
    }

    private suspend fun awaitCall(call: Call): Response =
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (cont.isActive) cont.resumeWithException(mapIo(e))
                    }

                    override fun onResponse(call: Call, response: Response) {
                        if (cont.isActive) cont.resume(response) else response.close()
                    }
                },
            )
        }

    private fun mapIo(e: IOException): Throwable {
        if (e is PolicyDnsException) return e.error
        return SourceException(SourceFailure.NETWORK, sourceId, null, cause = e)
    }

    private fun readCapped(response: Response): String {
        val body = response.body ?: return ""
        val buffer = Buffer()
        val source = body.source()
        var total = 0L
        while (true) {
            val read = source.read(buffer, 8192)
            if (read == -1L) break
            total += read
            if (total > SOURCE_MAX_METADATA_BYTES) {
                throw SourceException(
                    SourceFailure.INVALID_RESPONSE, sourceId, null,
                    cause = IllegalStateException("metadata body exceeds 8 MiB"),
                )
            }
        }
        val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
        return buffer.readString(charset)
    }

    private fun exposedHeaders(headers: Headers): Map<String, List<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        for (i in 0 until headers.size) {
            val name = headers.name(i)
            if (name.equals("Set-Cookie", ignoreCase = true)) continue
            out.getOrPut(name) { ArrayList() }.add(headers.value(i))
        }
        return out
    }

    private fun checkHeader(name: String, value: String) {
        if (isDeniedHeaderName(name)) policyFailure("header is host-reserved: $name")
        if (name.contains('\r') || name.contains('\n') || value.contains('\r') || value.contains('\n')) {
            policyFailure("CR/LF in header")
        }
    }

    private fun parseAndCheck(url: String, declared: Set<String>): HttpUrl {
        val parsed = url.toHttpUrlOrNull() ?: policyFailure("malformed URL")
        policy.checkUrl(parsed, declared)
        return parsed
    }

    private fun policyFailure(msg: String): Nothing {
        throw SourceException(SourceFailure.POLICY, sourceId, null, cause = IllegalStateException(msg))
    }

    companion object {
        private fun normalizeAll(origins: Set<String>): Set<String> =
            origins.mapNotNull { raw ->
                raw.toHttpUrlOrNull()
                    ?.takeIf { it.scheme == "https" }
                    ?.let { "https://" + it.host.lowercase() }
            }.toSet()
    }
}
