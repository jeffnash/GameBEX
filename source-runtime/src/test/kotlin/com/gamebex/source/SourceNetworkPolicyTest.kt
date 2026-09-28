package com.gamebex.source

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SourceNetworkPolicyTest {
    /** Test-only local-address policy: loopback hosts only, no other checks. */
    private object LocalTestPolicy : HostPolicy {
        override fun checkUrl(url: HttpUrl, declaredOrigins: Set<String>) {
            val host = url.host
            if (host != "localhost" && host != "127.0.0.1" && host != "::1") {
                throw SourceException(SourceFailure.POLICY, "test", null)
            }
        }

        override fun checkAddresses(host: String, addresses: List<InetAddress>) = Unit
    }

    /** Local policy restricted to one port, proving per-hop origin checks. */
    private class PortPolicy(val allowedPort: Int) : HostPolicy {
        override fun checkUrl(url: HttpUrl, declaredOrigins: Set<String>) {
            if (url.host != "localhost" && url.host != "127.0.0.1") {
                throw SourceException(SourceFailure.POLICY, "test", null)
            }
            if (url.port != allowedPort) throw SourceException(SourceFailure.POLICY, "test", null)
        }

        override fun checkAddresses(host: String, addresses: List<InetAddress>) = Unit
    }

    private fun http(
        policy: HostPolicy = LocalTestPolicy,
        dns: Dns = Dns.SYSTEM,
        configurer: (OkHttpClient.Builder) -> Unit = {},
    ): SourceHttp = SourceHttp(
        TestFixtures.validated(),
        "rev-test",
        SourceCookieJar(),
        policy,
        dns,
        configurer,
    )

    private fun get(url: String, query: Map<String, String> = emptyMap()): EvaluatedRequest =
        EvaluatedRequest("GET", NetworkPurpose.CATALOG, url, query, emptyMap(), emptyMap())

    private suspend fun <T> withServer(block: suspend (MockWebServer) -> T): T =
        withContext(Dispatchers.IO) {
            val server = MockWebServer()
            server.start()
            try {
                block(server)
            } finally {
                server.shutdown()
            }
        }

    @Test fun encodedQueryIsOneValue() = runTest {
        withServer { server ->
            server.enqueue(MockResponse().setBody("{}"))
            http().execute(get(server.url("/search").toString(), mapOf("q" to "Mario & Zelda")))
            val recorded = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("Mario & Zelda", recorded.requestUrl!!.queryParameter("q"))
            assertEquals(setOf("q"), recorded.requestUrl!!.queryParameterNames)
        }
    }

    @Test fun postFormTokenRequest() = runTest {
        withServer { server ->
            server.enqueue(MockResponse().setBody("{}"))
            http().execute(
                EvaluatedRequest(
                    "POST", NetworkPurpose.CATALOG, server.url("/mint").toString(),
                    emptyMap(), mapOf("action" to "k_get_download"), emptyMap(),
                ),
            )
            val recorded = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("POST", recorded.method)
            assertEquals("action=k_get_download", recorded.body.readUtf8())
        }
    }

    @Test fun sameOriginRedirectFollowed() = runTest {
        withServer { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/b"))
            server.enqueue(MockResponse().setBody("final"))
            val res = http().execute(get(server.url("/a").toString()))
            assertEquals("final", res.body)
            assertTrue(res.url.endsWith("/b"))
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun permittedCrossOriginRedirectFollowed() = runTest {
        withServer { cdn ->
            withServer { origin ->
                origin.enqueue(
                    MockResponse().setResponseCode(302)
                        .setHeader("Location", cdn.url("/asset").toString()),
                )
                cdn.enqueue(MockResponse().setBody("cdn-bytes"))
                val res = http().execute(get(origin.url("/file").toString()))
                assertEquals("cdn-bytes", res.body)
            }
        }
    }

    @Test fun deniedRedirectRejected() = runTest {
        withServer { cdn ->
            withServer { origin ->
                origin.enqueue(
                    MockResponse().setResponseCode(302)
                        .setHeader("Location", cdn.url("/asset").toString()),
                )
                try {
                    http(policy = PortPolicy(origin.port)).execute(get(origin.url("/file").toString()))
                    fail("expected POLICY")
                } catch (e: SourceException) {
                    assertEquals(SourceFailure.POLICY, e.failure)
                }
                assertEquals(0, cdn.requestCount)
            }
        }
    }

    @Test fun redirectDowngradesPostToGet() = runTest {
        withServer { server ->
            server.enqueue(MockResponse().setResponseCode(303).setHeader("Location", "/g"))
            server.enqueue(MockResponse().setBody("ok"))
            http().execute(
                EvaluatedRequest(
                    "POST", NetworkPurpose.CATALOG, server.url("/p").toString(),
                    emptyMap(), mapOf("a" to "b"), emptyMap(),
                ),
            )
            assertEquals("POST", server.takeRequest(5, TimeUnit.SECONDS)!!.method)
            val second = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("GET", second.method)
            assertEquals(0, second.body.size)
        }
    }

    @Test fun redirectKeepsPostFor307() = runTest {
        withServer { server ->
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", "/p2"))
            server.enqueue(MockResponse().setBody("ok"))
            http().execute(
                EvaluatedRequest(
                    "POST", NetworkPurpose.CATALOG, server.url("/p").toString(),
                    emptyMap(), mapOf("a" to "b"), emptyMap(),
                ),
            )
            server.takeRequest(5, TimeUnit.SECONDS)
            val second = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("POST", second.method)
            assertEquals("a=b", second.body.readUtf8())
        }
    }

    @Test fun non2xxThrowsHttp() = runTest {
        withServer { server ->
            server.enqueue(MockResponse().setResponseCode(404).setBody("nope"))
            try {
                http().execute(get(server.url("/missing").toString()))
                fail("expected HTTP")
            } catch (e: SourceException) {
                assertEquals(SourceFailure.HTTP, e.failure)
                assertEquals(404, e.statusCode)
            }
        }
    }

    private fun dnsFor(vararg addresses: String): Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            addresses.map { InetAddress.getByName(it) }
    }

    private fun productionHttp(dns: Dns): SourceHttp {
        val policy = ProductionHostPolicy("synthetic")
        return SourceHttp(TestFixtures.validated(), "rev", SourceCookieJar(), policy, CheckedDns(dns, policy))
    }

    @Test fun dnsRebindingThroughCheckedResolver() = runTest {
        try {
            productionHttp(dnsFor("127.0.0.1")).execute(get("https://rebind.example.com/"))
            fail("expected POLICY")
        } catch (e: SourceException) {
            assertEquals(SourceFailure.POLICY, e.failure)
        }
    }

    @Test fun privateIpv4Rejected() = runTest {
        try {
            productionHttp(dnsFor("10.9.9.9")).execute(get("https://private.example.com/"))
            fail("expected POLICY")
        } catch (e: SourceException) {
            assertEquals(SourceFailure.POLICY, e.failure)
        }
    }

    @Test fun privateIpv6Rejected() = runTest {
        try {
            productionHttp(dnsFor("fd00::1")).execute(get("https://private6.example.com/"))
            fail("expected POLICY")
        } catch (e: SourceException) {
            assertEquals(SourceFailure.POLICY, e.failure)
        }
    }

    @Test fun mappedPrivateIpv6Rejected() = runTest {
        try {
            productionHttp(dnsFor("::ffff:10.0.0.1")).execute(get("https://mapped.example.com/"))
            fail("expected POLICY")
        } catch (e: SourceException) {
            assertEquals(SourceFailure.POLICY, e.failure)
        }
    }

    @Test fun mixedDnsAnswersRejected() = runTest {
        try {
            productionHttp(dnsFor("93.184.216.34", "10.0.0.1")).execute(get("https://mixed.example.com/"))
            fail("expected POLICY")
        } catch (e: SourceException) {
            assertEquals(SourceFailure.POLICY, e.failure)
        }
    }

    @Test fun publicAddressesPassPolicy() {
        val policy = ProductionHostPolicy("synthetic")
        policy.checkAddresses(
            "public.example.com",
            listOf(InetAddress.getByName("93.184.216.34"), InetAddress.getByName("2606:2800:220:1:248:1893:25c8:1946")),
        )
        // IPv4 sorts first under preference without dropping valid answers.
        val sorted = CheckedDns(dnsFor("2606:2800:220:1:248:1893:25c8:1946", "93.184.216.34"), policy, true)
            .lookup("public.example.com")
        assertTrue(sorted[0] is java.net.Inet4Address)
        assertEquals(2, sorted.size)
    }

    @Test fun declaredOriginEnforced() {
        val policy = ProductionHostPolicy("synthetic")
        val declared = setOf("https://catalog.example.com")
        policy.checkUrl("https://catalog.example.com/x".toHttpUrl(), declared)
        for (bad in listOf(
            "http://catalog.example.com/x",
            "https://catalog.example.com:8443/x",
            "https://user@catalog.example.com/x",
            "https://evil.example.com/x",
            "https://127.0.0.1/x",
            "https://localhost/x",
            "https://[::1]/x",
        )) {
            try {
                policy.checkUrl(bad.toHttpUrl(), declared)
                fail("expected POLICY for $bad")
            } catch (e: SourceException) {
                assertEquals(SourceFailure.POLICY, e.failure)
            }
        }
    }

    @Test fun invalidHeaderRejected() = runTest {
        withServer { server ->
            try {
                http().execute(
                    EvaluatedRequest(
                        "GET", NetworkPurpose.CATALOG, server.url("/x").toString(),
                        emptyMap(), emptyMap(), mapOf("X-Bad" to "a\r\nb"),
                    ),
                )
                fail("expected POLICY")
            } catch (e: SourceException) {
                assertEquals(SourceFailure.POLICY, e.failure)
            }
            try {
                http().execute(
                    EvaluatedRequest(
                        "GET", NetworkPurpose.CATALOG, server.url("/x").toString(),
                        emptyMap(), emptyMap(), mapOf("Range" to "bytes=0-1"),
                    ),
                )
                fail("expected POLICY")
            } catch (e: SourceException) {
                assertEquals(SourceFailure.POLICY, e.failure)
            }
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun defaultHeadersMergedWithRequestOverride() = runTest {
        withServer { server ->
            server.enqueue(MockResponse().setBody("{}"))
            val def = DefinitionValidator.validate(
                TestFixtures.minimalDefinition().copy(
                    defaults = RequestDefaults(
                        headers = mapOf("X-A" to "default", "X-B" to "default"),
                    ),
                ),
            )
            val client = SourceHttp(def, "rev", SourceCookieJar(), LocalTestPolicy, Dns.SYSTEM)
            client.execute(
                EvaluatedRequest(
                    "GET", NetworkPurpose.CATALOG, server.url("/x").toString(),
                    emptyMap(), emptyMap(), mapOf("x-b" to "request"),
                ),
            )
            val recorded = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("default", recorded.getHeader("X-A"))
            assertEquals("request", recorded.getHeader("X-B"))
        }
    }

    @Test fun cookieOriginSeparation() = runTest {
        withServer { serverB ->
            withServer { serverA ->
                val jar = SourceCookieJar()
                val client = SourceHttp(TestFixtures.validated(), "rev", jar, LocalTestPolicy, Dns.SYSTEM)
                // Plain session cookie from A attaches to A.
                serverA.enqueue(MockResponse().setHeader("Set-Cookie", "s=1; Path=/").setBody("{}"))
                serverA.enqueue(MockResponse().setBody("{}"))
                client.execute(get(serverA.url("/one").toString()))
                client.execute(get(serverA.url("/two").toString()))
                serverA.takeRequest(5, TimeUnit.SECONDS)
                assertEquals("s=1", serverA.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Cookie"))
                // Browser-verified cookie for A never attaches to B, even though
                // the plain Domain=localhost jar cookie legitimately does (cookies ignore ports).
                jar.importBrowserCookies(serverA.url("/"), listOf("v=secret; Domain=localhost; Path=/"))
                serverB.enqueue(MockResponse().setBody("{}"))
                client.execute(get(serverB.url("/x").toString()))
                val bCookie = serverB.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Cookie")
                assertTrue(bCookie == null || !bCookie.contains("v=secret"))
                serverA.enqueue(MockResponse().setBody("{}"))
                client.execute(get(serverA.url("/three").toString()))
                val cookie = serverA.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Cookie")!!
                assertTrue(cookie.contains("s=1"))
                assertTrue(cookie.contains("v=secret"))
            }
        }
    }

    @Test fun cancellationCancelsCall() {
        runBlocking {
            val server = MockWebServer()
            server.start()
            try {
                val failed = AtomicInteger()
                val reported = CountDownLatch(1)
                val client = http(
                    configurer = {
                        it.eventListener(
                            object : EventListener() {
                                override fun callFailed(call: Call, e: IOException) {
                                    failed.incrementAndGet()
                                    reported.countDown()
                                }
                            },
                        )
                    },
                )
                // No response enqueued: the server holds the connection open,
                // so cancellation lands while the call is in flight.
                val job = launch(Dispatchers.IO) {
                    client.execute(get(server.url("/slow").toString()))
                }
                server.takeRequest(10, TimeUnit.SECONDS)!!
                job.cancelAndJoin()
                assertTrue(job.isCancelled)
                // OkHttp reports the cancelled call on its own thread, which can land after the join.
                assertTrue(reported.await(10, TimeUnit.SECONDS))
                assertEquals(1, failed.get())
            } finally {
                server.shutdown()
            }
        }
    }

    @Test fun metadataBodyLimitEnforced() = runTest {
        withServer { server ->
            server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(SOURCE_MAX_METADATA_BYTES + 1))))
            try {
                http().execute(get(server.url("/big").toString()))
                fail("expected INVALID_RESPONSE")
            } catch (e: SourceException) {
                assertEquals(SourceFailure.INVALID_RESPONSE, e.failure)
            }
        }
    }

    @Test fun transferClientsAndChecks() {
        val client = productionHttp(Dns.SYSTEM)
        assertSame(client.downloadClient(false), client.downloadClient(false))
        assertNotSame(client.downloadClient(true), client.downloadClient(true))
        client.checkTransferHeaders(mapOf("Referer" to "https://catalog.example.com/"))
        try {
            client.checkTransferHeaders(mapOf("Range" to "bytes=0-1"))
            fail("expected POLICY")
        } catch (e: SourceException) {
            assertEquals(SourceFailure.POLICY, e.failure)
        }
        client.checkTransferUrl("https://dl.example.com/f.zip")
        try {
            client.checkTransferUrl("https://evil.example.com/f.zip")
            fail("expected POLICY")
        } catch (e: SourceException) {
            assertEquals(SourceFailure.POLICY, e.failure)
        }
    }
}
