package com.gamebex.client.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Material filled icons missing from material-icons-core, vendored locally so
 * the app does not need the multi-MB material-icons-extended artifact.
 * Path data: Material Symbols, Apache License 2.0.
 */
object GameBexIcons {
    val Download: ImageVector by lazy {
        vector(
            "Download",
            "M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z",
        )
    }
    val Explore: ImageVector by lazy {
        vector(
            "Explore",
            "M12 10.9c-.61 0-1.1.49-1.1 1.1s.49 1.1 1.1 1.1c.61 0 1.1-.49 1.1-1.1s-.49-1.1-1.1-1.1z" +
                "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2z" +
                "m2.19 12.19L6 18l3.81-8.19L18 6l-3.81 8.19z",
        )
    }
    val Storage: ImageVector by lazy {
        vector(
            "Storage",
            "M2 20h20v-4H2v4zm2-3h2v2H4v-2zM2 4v4h20V4H2zm4 3H4V5h2v2z" +
                "m-4 7h20v-4H2v4zm2-3h2v2H4v-2z",
        )
    }

    private fun vector(name: String, d: String): ImageVector {
        val nodes = PathParser().parsePathString(d).toNodes()
        return ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(
            pathData = nodes,
            pathFillType = PathFillType.NonZero,
            fill = SolidColor(Color.Black),
        ).build()
    }
}
