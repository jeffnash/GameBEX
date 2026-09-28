package com.gamebex.client.data

import com.gamebex.client.data.local.LibraryScanner
import org.junit.Assert.assertEquals
import org.junit.Test

/** Scanner display titles from real No-Intro / Redump / TOSEC names. */
class TitleTextTest {
    private fun t(base: String) = LibraryScanner.prettyTitle(base)

    @Test fun noIntroTagsStripped() {
        assertEquals("Super Mario Bros.", t("Super Mario Bros. (World)"))
        assertEquals("Dr. Mario", t("Dr. Mario (Japan, USA) (Rev 1)"))
        assertEquals(
            "Legend of Zelda, The - A Link to the Past",
            t("Legend of Zelda, The - A Link to the Past (USA)"),
        )
        assertEquals("Pokemon - Red Version", t("Pokemon - Red Version (USA, Europe) (SGB Enhanced)"))
        assertEquals("Tetris", t("Tetris (Japan) (En) [!]"))
        assertEquals("Mother 3", t("Mother 3 (Japan) [T+Eng1.3]"))
    }

    @Test fun redumpTagsStripped() {
        assertEquals("Final Fantasy VII", t("Final Fantasy VII (USA) (Disc 1)"))
        assertEquals("Castlevania - Symphony of the Night", t("Castlevania - Symphony of the Night (USA)"))
        assertEquals("Spider-Man", t("Spider-Man (USA) (v1.1)"))
    }

    @Test fun separatorsAndFallback() {
        assertEquals("Sonic the Hedgehog", t("Sonic_the_Hedgehog_(USA)"))
        assertEquals("Super Mario World", t("Super.Mario.World"))
        assertEquals("galaga", t("galaga"))
        assertEquals("(Proto)", t("(Proto)"))
    }

    @Test fun legacyTitleRecognizedForMigration() {
        assertEquals("Dr Mario (USA)", LibraryScanner.legacyPrettyTitle("Dr._Mario_(USA)"))
    }

    @Test fun stripTagsSharedWithScreenScraper() {
        assertEquals("Dr. Mario", TitleText.stripTags("Dr. Mario (Japan, USA) [b1]"))
        assertEquals(
            "Castlevania Symphony of the Night",
            com.gamebex.client.net.ScreenScraper().cleanTitle("Castlevania - Symphony of the Night (USA)"),
        )
    }
}
