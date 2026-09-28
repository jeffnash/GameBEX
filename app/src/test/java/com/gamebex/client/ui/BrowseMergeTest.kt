package com.gamebex.client.ui

import com.gamebex.client.data.CatalogFixtures.entry
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pagination merge: stable SourceKey identity, first copy wins. */
class BrowseMergeTest {
    private fun e(key: String, title: String = "T$key") = entry(itemKey = key, title = title)

    @Test fun appendsNewPages() {
        val m = mergeCatalogPages(listOf(e("gb/a")), listOf(e("gb/b")))
        assertEquals(listOf("gb/a", "gb/b"), m.map { it.key.itemKey })
    }

    @Test fun dropsDuplicateKeysKeepingFirst() {
        val old = e("gb/a", "Old")
        val m = mergeCatalogPages(listOf(old), listOf(e("gb/a", "New"), e("gb/b")))
        assertEquals(listOf("gb/a", "gb/b"), m.map { it.key.itemKey })
        assertEquals("Old", m.first().title)
    }

    @Test fun sameItemDifferentSourceIsDistinct() {
        val m = mergeCatalogPages(
            listOf(entry(sourceId = "a", itemKey = "gb/x")),
            listOf(entry(sourceId = "b", itemKey = "gb/x")),
        )
        assertEquals(2, m.size)
    }

    @Test fun dedupesWithinNext() {
        val m = mergeCatalogPages(emptyList(), listOf(e("gb/a"), e("gb/a")))
        assertEquals(1, m.size)
    }

    @Test fun emptyNextKeepsExisting() {
        val cur = listOf(e("gb/a"))
        assertEquals(cur, mergeCatalogPages(cur, emptyList()))
    }
}
