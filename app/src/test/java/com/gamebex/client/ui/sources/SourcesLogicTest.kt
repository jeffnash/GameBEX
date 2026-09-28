package com.gamebex.client.ui.sources

import com.gamebex.client.sources.InstalledSource
import com.gamebex.client.sources.StaleSourcePreviewException
import com.gamebex.source.SourceException
import com.gamebex.source.SourceFailure
import org.junit.Assert.assertEquals
import org.junit.Test

/** Sources UI logic: row status and registry-exception copy. */
class SourcesLogicTest {
    private fun entry(
        id: String = "s1",
        enabled: Boolean = true,
        available: Boolean = true,
        reason: String? = null,
    ) = InstalledSource(
        id = id, revision = "r1", enabled = enabled,
        name = "S1", available = available, reason = reason,
    )

    @Test fun rowStatusSelectedWins() {
        assertEquals(SourceRowStatus.Selected, sourceRowStatus(entry(), "s1"))
    }

    @Test fun rowStatusEnabled() {
        assertEquals(SourceRowStatus.Enabled, sourceRowStatus(entry(), "other"))
        assertEquals(SourceRowStatus.Enabled, sourceRowStatus(entry(), null))
    }

    @Test fun rowStatusDisabled() {
        assertEquals(SourceRowStatus.Disabled, sourceRowStatus(entry(enabled = false), "s1"))
    }

    @Test fun rowStatusUnavailable() {
        assertEquals(
            SourceRowStatus.Unavailable,
            sourceRowStatus(entry(available = false, reason = "definition file is missing"), "s1"),
        )
    }

    @Test fun rowStatusIncompatible() {
        assertEquals(
            SourceRowStatus.Incompatible,
            sourceRowStatus(
                entry(available = false, reason = "definition is incompatible with this app version"),
                "s1",
            ),
        )
    }

    @Test fun rowStatusLabels() {
        assertEquals("Selected", SourceRowStatus.Selected.label())
        assertEquals("Enabled", SourceRowStatus.Enabled.label())
        assertEquals("Disabled", SourceRowStatus.Disabled.label())
        assertEquals("Incompatible", SourceRowStatus.Incompatible.label())
        assertEquals("Unavailable", SourceRowStatus.Unavailable.label())
    }

    @Test fun importErrorCarriesJsonPath() {
        val e = SourceException(
            SourceFailure.INVALID_DEFINITION, null, null,
            fieldPath = "operations.search.steps[0].request.url",
        )
        assertEquals(
            "That file isn't a valid source (operations.search.steps[0].request.url).",
            SourcesViewModel.importError(e),
        )
    }

    @Test fun importErrorWithoutPath() {
        assertEquals(
            "That file isn't a valid source.",
            SourcesViewModel.importError(SourceException(SourceFailure.INVALID_DEFINITION, null, null)),
        )
        assertEquals(
            "That source needs a newer GameBEX version.",
            SourcesViewModel.importError(SourceException(SourceFailure.INCOMPATIBLE, null, null)),
        )
        assertEquals(
            "That source isn't available.",
            SourcesViewModel.importError(SourceException(SourceFailure.UNAVAILABLE, null, null)),
        )
        assertEquals(
            "Couldn't read that file.",
            SourcesViewModel.importError(SourceException(SourceFailure.NETWORK, null, null)),
        )
    }

    @Test fun actionErrorMapping() {
        assertEquals(
            "That source isn't available.",
            SourcesViewModel.actionError(SourceException(SourceFailure.UNAVAILABLE, "s", null)),
        )
        assertEquals(
            "Source changed; review again.",
            SourcesViewModel.actionError(StaleSourcePreviewException()),
        )
        assertEquals(
            "That didn't work. Try again.",
            SourcesViewModel.actionError(RuntimeException("boom")),
        )
    }
}
