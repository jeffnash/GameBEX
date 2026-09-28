package com.gamebex.source

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.InetAddress

/**
 * Artwork client (Coil adapter): every hop — initial URL plus each
 * redirect target — is policy-checked. A redirect to a private or
 * unapproved origin fails the load instead of fetching it.
 */
class SourceArtworkClientTest {
    /** Loopback-only policy pinned to one port. */
    private class PortPolicy(val allowedPort: Int) : HostPolicy {
        override fun checkUrl(url: HttpUrl, declaredOrigins: Set<String>) {
            if (url.host != "localhost" && url.host != "127.0.0.1") {
                throw SourceException(SourceFailure.POLICY, "test", null)
            }
            if (url.port != allowedPort) throw SourceException(SourceFailure.POLICY, "test", null)
        }

        override fun checkAddresses(host: String, addresses: List<InetAddress>) = Unit
    }

    private fun http(policy: HostPolicy): SourceHttp = SourceHttp(
        TestFixtures.validated(),
        "rev-test",
        SourceCookieJar(),
        policy,
        Dns.SYSTEM,
    )

    private fun get(client: OkHttpClient, url: String): String =
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("http ${resp.code}")
            resp.body!!.string()
        }

    @Test fun sameOriginRedirectSucceeds() = runTest {
        withContext(Dispatchers.IO) {
            val server = MockWebServer()
            server.start()
            try {
                val art = http(PortPolicy(server.port)).artworkHttpClient()
                server.enqueue(
                    MockResponse().setResponseCode(302)
                        .setHeader("Location", server.url("/img").toString()),
                )
                server.enqueue(MockResponse().setBody("PNG-BYTES"))
                assertEquals("PNG-BYTES", get(art, server.url("/cover").toString()))
            } finally {
                server.shutdown()
            }
        }
    }

    @Test fun redirectToUnapprovedOriginFails() = runTest {
        withContext(Dispatchers.IO) {
            val a = MockWebServer()
            val b = MockWebServer()
            a.start()
            b.start()
            try {
                val art = http(PortPolicy(a.port)).artworkHttpClient()
                a.enqueue(
                    MockResponse().setResponseCode(302)
                        .setHeader("Location", b.url("/evil").toString()),
                )
                b.enqueue(MockResponse().setBody("EVIL-BYTES"))
                try {
                    get(art, a.url("/cover").toString())
                    fail("redirect to unapproved origin must fail")
                } catch (e: IOException) {
                    assertTrue(e.message!!.contains("artwork origin rejected"))
                }
                assertEquals(0, b.requestCount)
            } finally {
                a.shutdown()
                b.shutdown()
            }
        }
    }

    @Test fun initialUnapprovedOriginFails() = runTest {
        withContext(Dispatchers.IO) {
            val server = MockWebServer()
            server.start()
            try {
                val art = http(PortPolicy(-1)).artworkHttpClient()
                server.enqueue(MockResponse().setBody("NOPE"))
                try {
                    get(art, server.url("/cover").toString())
                    fail("unapproved origin must fail")
                } catch (e: IOException) {
                    assertTrue(e.message!!.contains("artwork origin rejected"))
                }
                assertEquals(0, server.requestCount)
            } finally {
                server.shutdown()
            }
        }
    }

    @Test fun artworkClientFollowsRedirectsWithoutCookies() {
        val art = http(PortPolicy(1)).artworkHttpClient()
        assertTrue(art.followRedirects)
        assertTrue(art.followSslRedirects)
        assertEquals(1, art.networkInterceptors.size)
    }
}
