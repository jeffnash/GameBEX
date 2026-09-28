package com.gamebex.client.sources

import com.gamebex.client.data.CatalogFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source image identity: keys scope cached art to source+revision+URL. */
class SourceImagesTest {
    @Test fun sameTupleKeysStable() {
        assertEquals(
            sourceImageKey("s1", "r1", "https://art/x.jpg"),
            sourceImageKey("s1", "r1", "https://art/x.jpg"),
        )
    }

    @Test fun crossSourceKeysDiffer() {
        assertNotEquals(
            sourceImageKey("s1", "r1", "https://art/x.jpg"),
            sourceImageKey("s2", "r1", "https://art/x.jpg"),
        )
    }

    @Test fun revisionChangeRekeys() {
        assertNotEquals(
            sourceImageKey("s1", "r1", "https://art/x.jpg"),
            sourceImageKey("s1", "r2", "https://art/x.jpg"),
        )
    }

    @Test fun urlChangeRekeys() {
        assertNotEquals(
            sourceImageKey("s1", "r1", "https://art/a.jpg"),
            sourceImageKey("s1", "r1", "https://art/b.jpg"),
        )
    }

    @Test fun keyIsSha256Hex() {
        val key = sourceImageKey("s1", "r1", "https://art/x.jpg")
        assertEquals(64, key.length)
        assertTrue(key.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test fun coverImageBindsOwningSource() {
        val entry = CatalogFixtures.entry(
            itemKey = "gbc/zelda", title = "Zelda", platformId = "gbc",
            coverUrl = "https://art/z.jpg",
        )
        assertEquals(
            SourceImage(entry.ref.sourceId, "r9", "https://art/z.jpg"),
            entry.coverImage("r9"),
        )
    }

    @Test fun coverImageRejectsMissingAndNonHttp() {
        val bare = CatalogFixtures.entry(
            itemKey = "gbc/zelda", title = "Zelda", platformId = "gbc",
        )
        assertNull(bare.coverImage("r9"))
        val file = CatalogFixtures.entry(
            itemKey = "gbc/zelda", title = "Zelda", platformId = "gbc",
            coverUrl = "/data/user/0/art/box.jpg",
        )
        assertNull(file.coverImage("r9"))
    }
}
