package com.gamebex.client.ui

import com.gamebex.source.SourceException
import com.gamebex.source.SourceFailure
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Test

/** Error copy mapping: known failures get actionable text, never raw internals. */
class UserErrorsTest {
    private fun http(status: Int) =
        SourceException(SourceFailure.HTTP, "acme", "browse", statusCode = status)

    @Test fun networkFailuresNameSource() {
        val want = "Couldn't reach Acme — check your connection and retry."
        assertEquals(
            want,
            UserErrors.forCatalog(SourceException(SourceFailure.NETWORK, "acme", "browse"), "Acme"),
        )
        assertEquals(want, UserErrors.forCatalog(UnknownHostException("x"), "Acme"))
        assertEquals(want, UserErrors.forCatalog(SocketTimeoutException("read timed out"), "Acme"))
        assertEquals(want, UserErrors.forSearch(ConnectException("refused"), "Acme"))
    }

    @Test fun httpMapsByTypedStatus() {
        assertEquals(
            "That page is no longer available on Acme.",
            UserErrors.forCatalog(http(404), "Acme"),
        )
        assertEquals(
            "Acme is having trouble — retry shortly.",
            UserErrors.forCatalog(http(503), "Acme"),
        )
        assertEquals(
            "Acme is rate-limiting requests — retry shortly.",
            UserErrors.forSearch(http(429), "Acme"),
        )
    }

    @Test fun sourceStates() {
        assertEquals("Import a ROM source to discover games.", UserErrors.noSource())
        assertEquals(
            "Acme is disabled. Enable it in Sources.",
            UserErrors.sourceDisabled("Acme"),
        )
        assertEquals(
            "Install Acme to continue this download.",
            UserErrors.sourceMissing("Acme"),
        )
        assertEquals(
            "Acme is disabled. Enable it in Sources.",
            UserErrors.forCatalog(SourceException(SourceFailure.UNAVAILABLE, "acme", "browse"), "Acme"),
        )
    }

    @Test fun definitionStates() {
        assertEquals(
            "Acme changed its response. Update the source or retry later.",
            UserErrors.forCatalog(SourceException(SourceFailure.INVALID_RESPONSE, "acme", "browse"), "Acme"),
        )
        assertEquals(
            "Acme requested an unapproved address. Review its source definition.",
            UserErrors.forCatalog(SourceException(SourceFailure.POLICY, "acme", "browse"), "Acme"),
        )
        assertEquals(
            "This source needs a newer GameBEX version.",
            UserErrors.forCatalog(SourceException(SourceFailure.INCOMPATIBLE, "acme", "browse"), "Acme"),
        )
    }

    @Test fun unknownFailuresFallBackWithoutInternals() {
        assertEquals(
            "Couldn't load this list — retry.",
            UserErrors.forCatalog(IOException("weird: internal (host=db-3)"), "Acme"),
        )
        assertEquals("Search failed — retry.", UserErrors.forSearch(RuntimeException(), "Acme"))
    }

    @Test fun launchCopyCarriesNoDevJargon() {
        val copy = listOf(
            UserErrors.emuMissing("RetroArch"),
            UserErrors.unsupportedSystem("Switch"),
            UserErrors.unknownSystem(),
            UserErrors.noDirectBoot("Vita3K"),
            UserErrors.guidedImport("Vita3K", "Persona 4 Golden"),
            UserErrors.launchFailed(),
            UserErrors.freshUnavailable(),
            UserErrors.prepFailed(),
            UserErrors.emuDownloadFailed(),
            UserErrors.emuInstallFailed(),
        )
        for (s in copy) {
            for (bad in listOf("P1", "P2", "P3", "post-v1", "verifies", "route", "null", "Exception")) {
                assertEquals("'$bad' in: $s", false, s.contains(bad))
            }
        }
        assertEquals("GameBEX can't play Switch games yet.", UserErrors.unsupportedSystem("Switch"))
        assertEquals("RetroArch isn't installed.", UserErrors.emuMissing("RetroArch"))
    }
}
