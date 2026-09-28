package com.gamebex.client.ui

import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamebex.client.R
import com.gamebex.client.ui.console.ConsoleColors

@OptIn(ExperimentalTextApi::class)
private fun spaceGrotesk(weight: FontWeight) = Font(
    R.font.space_grotesk,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

/** UI face for everything except short pixel eyebrows. */
val SpaceGrotesk = FontFamily(
    spaceGrotesk(FontWeight.Normal),
    spaceGrotesk(FontWeight.Medium),
    spaceGrotesk(FontWeight.SemiBold),
    spaceGrotesk(FontWeight.Bold),
)

/** Pixel face: short uppercase labels ("PRESS START", eyebrows) only. */
val Silkscreen = FontFamily(Font(R.font.silkscreen_bold, FontWeight.Bold))

private val Scheme = darkColorScheme(
    primary = ConsoleColors.Accent,
    secondary = ConsoleColors.TextSecondary,
    error = ConsoleColors.Error,
    background = ConsoleColors.Background,
    surface = ConsoleColors.Surface,
    surfaceVariant = ConsoleColors.HighSurface,
    onPrimary = ConsoleColors.Background,
    onBackground = ConsoleColors.TextPrimary,
    onSurface = ConsoleColors.TextPrimary,
    onSurfaceVariant = ConsoleColors.TextSecondary,
    outline = ConsoleColors.Steel,
)

private val Type: Typography = Typography().let { t ->
    // Space Grotesk is set tight; Material's 0.1–0.5sp tracking clips rail labels.
    fun TextStyle.brand() = copy(fontFamily = SpaceGrotesk, letterSpacing = 0.sp)
    Typography(
        displayLarge = t.displayLarge.brand(),
        displayMedium = t.displayMedium.brand(),
        displaySmall = t.displaySmall.brand(),
        headlineLarge = t.headlineLarge.brand(),
        headlineMedium = t.headlineMedium.brand(),
        headlineSmall = t.headlineSmall.brand(),
        titleLarge = t.titleLarge.brand(),
        titleMedium = t.titleMedium.brand(),
        titleSmall = t.titleSmall.brand(),
        bodyLarge = t.bodyLarge.brand(),
        bodyMedium = t.bodyMedium.brand(),
        bodySmall = t.bodySmall.brand(),
        labelLarge = t.labelLarge.brand(),
        labelMedium = t.labelMedium.brand(),
        labelSmall = t.labelSmall.brand(),
    )
}

@Composable
fun GameBexTheme(content: @Composable () -> Unit) {
    androidx.compose.material3.MaterialTheme(
        colorScheme = Scheme,
        typography = Type,
        // Dialogs and sheets use the 12px brand panel radius, not M3's 28.
        shapes = androidx.compose.material3.Shapes(
            extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        ),
        content = content,
    )
}
