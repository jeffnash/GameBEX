package com.gamebex.client.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Assist WebView navigation policy: declared origins only. */
class AssistActivityTest {
    private val allowed = setOf("https://acme.com", "https://files.host-7.net")

    @Test fun originsNormalize() {
        assertEquals("https://files.host-7.net", AssistActivity.originOfUrl("https://Files.Host-7.NET/dl"))
        assertEquals("https://h.test:8443", AssistActivity.originOfUrl("https://h.test:8443/x"))
        assertEquals("https://h.test", AssistActivity.originOfUrl("https://h.test:443/x"))
        assertNull(AssistActivity.originOfUrl("not a url"))
        assertNull(AssistActivity.originOfUrl(null))
    }

    @Test fun navigationAllowsDeclaredOrigins() {
        assertTrue(AssistActivity.navAllowed("https://files.host-7.net/dl/abc", allowed))
        assertTrue(AssistActivity.navAllowed("https://acme.com/game-x.html", allowed))
    }

    @Test fun navigationBlocksExotics() {
        assertFalse(AssistActivity.navAllowed("https://evil.test/phish", allowed))
        assertFalse(AssistActivity.navAllowed("http://acme.com/x", allowed))
        assertFalse(AssistActivity.navAllowed("https://acme.com.evil.test/x", allowed))
        assertFalse(AssistActivity.navAllowed("file:///etc/passwd", allowed))
        assertFalse(AssistActivity.navAllowed("intent://evil#Intent;scheme=https;end", allowed))
        assertFalse(AssistActivity.navAllowed("content://media/1", allowed))
        assertFalse(AssistActivity.navAllowed(null, allowed))
    }

    @Test fun subrequestsAllowBrowserLocal() {
        assertTrue(AssistActivity.subrequestAllowed("data:text/plain,hi", allowed))
        assertTrue(AssistActivity.subrequestAllowed("blob:https://acme.com/x", allowed))
        assertTrue(AssistActivity.subrequestAllowed("https://files.host-7.net/cdn.js", allowed))
        assertFalse(AssistActivity.subrequestAllowed("https://evil.test/x.js", allowed))
        assertFalse(AssistActivity.subrequestAllowed("file:///x", allowed))
    }

    @Test fun displayHostsStripsSchemes() {
        assertEquals(
            listOf("acme.com", "files.host-7.net"),
            AssistActivity.displayHosts(allowed),
        )
    }
}
