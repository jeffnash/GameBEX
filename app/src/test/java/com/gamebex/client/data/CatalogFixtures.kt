package com.gamebex.client.data

import com.gamebex.source.ConsoleItem
import com.gamebex.source.DownloadFile
import com.gamebex.source.RomDetail
import com.gamebex.source.RomEntry
import com.gamebex.source.SourceRef

/** Shared catalog DTO builders. */
object CatalogFixtures {
    fun ref(
        sourceId: String = "acme",
        itemKey: String = "gb/tetris",
        pageUrl: String = "https://acme.com/roms/game-boy/tetris.html",
    ) = SourceRef(sourceId = sourceId, itemKey = itemKey, pageUrl = pageUrl)

    fun entry(
        sourceId: String = "acme",
        itemKey: String = "gb/tetris",
        title: String = "Tetris",
        platformId: String? = "gb",
        consoleName: String? = "Game Boy",
        coverUrl: String? = null,
    ) = RomEntry(
        ref = ref(sourceId, itemKey, "https://acme.com/roms/x/$itemKey.html"),
        title = title, platformId = platformId,
        consoleName = consoleName, coverUrl = coverUrl,
    )

    fun detail(entry: RomEntry = entry(), description: String? = "Desc") = RomDetail(
        entry = entry, description = description,
    )

    fun file(
        ref: SourceRef = ref(),
        variantKey: String = "https://acme.com/download/tetris-1/1",
        name: String = "tetris.zip",
    ) = DownloadFile(
        ref = ref, variantKey = variantKey, name = name,
        kind = "ROM", size = "1 MB", pageUrl = variantKey,
    )

    fun console(
        id: String = "game-boy",
        name: String = "Game Boy",
        platformId: String? = "gb",
        count: Int = 10,
    ) = ConsoleItem(id = id, name = name, platformId = platformId, count = count)
}
