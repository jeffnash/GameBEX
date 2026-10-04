package com.gamebex.client.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.gamebex.client.R
import com.gamebex.client.together.TogetherCopy
import com.gamebex.client.ui.console.ConsoleColors
import com.gamebex.client.ui.console.ConsoleDesign
import com.gamebex.client.ui.console.ConsoleGeometry
import com.gamebex.client.ui.console.ConsoleSearchField
import com.gamebex.client.ui.console.LocalConsoleGeometry
import com.gamebex.client.ui.home.components.CloudGlyph
import com.gamebex.client.ui.home.components.WarnGlyph
import com.gamebex.client.ui.home.model.HeaderModel
import com.gamebex.client.ui.home.model.HomeCopy
import com.gamebex.client.ui.home.model.HomeDestination
import kotlin.math.min

// U+2601 / U+26A0 with VS-15 (U+FE0E) force the monochrome text glyph so it can
// be tinted (grey cloud, amber warning) instead of rendering as a color emoji.

/** A Downloads badge is a mint count, or "offline" in TextTertiary. */
internal enum class BadgeTone { NONE, COUNT, OFFLINE }

internal fun downloadsBadgeTone(badge: String?): BadgeTone = when {
    badge.isNullOrEmpty() -> BadgeTone.NONE
    badge.all(Char::isDigit) -> BadgeTone.COUNT
    else -> BadgeTone.OFFLINE
}

/** System shows an amber pixel ⚠ + count only when above 0. */
internal fun systemWarnText(warnings: Int): String? =
    if (warnings > 0) "$warnings" else null

/**
 * Home type scale rule: Home text follows
 * `min(g.textScale, 1.1)` instead of the full user text scale, so "System ⚠ 2"
 * and the nav pills stay whole at Larger text sizes.
 */
private fun homeTypeScale(g: ConsoleGeometry): Float = min(g.textScale, 1.1f)

/** Home-scaled type: canvas px at the home type scale. */
private fun homeT(g: ConsoleGeometry, px: Int): TextUnit = (px * g.scale * homeTypeScale(g)).sp

/**
 * A parsed "Browse {site}" nav label: [cloud] is the leading
 * pixel cloud glyph, [lead] is "Browse" (or "Discover" with no site) and
 * [site] is the site name, shown capped with an ellipsis when wide.
 */
internal data class BrowseParts(val cloud: Boolean, val lead: String, val site: String?)

internal fun parseBrowseLabel(label: String): BrowseParts {
    val cloud = label.startsWith(HomeCopy.glyphCloudPrefix)
    val rest = label.removePrefix(HomeCopy.glyphCloudPrefix).trimStart()
    val at = rest.indexOf(' ')
    return if (at < 0) BrowseParts(cloud, rest, null)
    else BrowseParts(cloud, rest.take(at), rest.substring(at + 1).trimStart().ifEmpty { null })
}

/**
 * Header fit rules, in device pixels:
 *  - the search field shrinks before any nav item may clip;
 *  - when [fixedPx] + the search hit floor + the full nav row exceed the row,
 *    the Browse pill collapses to "☁ Browse".
 */
internal fun browseCollapses(
    rowWidthPx: Int,
    fixedPx: Int,
    navFullPx: Int,
    minSearchPx: Int,
): Boolean = fixedPx + minSearchPx + navFullPx > rowWidthPx

/** Search width: whatever the nav row leaves, clamped to [0, maxSearchPx]. */
internal fun searchWidthPx(
    rowWidthPx: Int,
    fixedPx: Int,
    navPx: Int,
    maxSearchPx: Int,
): Int = (rowWidthPx - fixedPx - navPx).coerceIn(0, maxSearchPx)

enum class HeaderItem { SEARCH, LIBRARY, BROWSE, DOWNLOADS, TOGETHER, SYSTEM }

/**
 * Home header: logo, search field, then nav pills
 * (Library · Browse {site} · Downloads {badge} · Together · System {⚠ n}). Every hit box
 * is at least 84×84; a pill tap calls [onNav], a field tap calls [onSearchTap].
 *
 * The row is a [SubcomposeLayout] so the nav pills are measured
 * at their real widths first — the search field then shrinks (never past its
 * hit floor) before any nav item may clip, "Browse {site}" caps the site name
 * with an ellipsis and collapses to "☁ Browse" when even that doesn't fit, and
 * nav type follows the Home type scale rule (`min(g.textScale, 1.1)`), so
 * "System ⚠ 2" stays fully visible at Larger text sizes.
 */
@Composable
fun HomeHeader(
    header: HeaderModel,
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    focused: HeaderItem?,
    searchFocus: FocusRequester,
    onSubmit: () -> Unit,
    onSearchTap: () -> Unit,
    onClear: () -> Unit,
    onNav: (HomeDestination) -> Unit,
    modifier: Modifier = Modifier,
    /** Real editor focus changes (tap-on-text starts editing). */
    onFocusChange: (Boolean) -> Unit = {},
    /** The mockup's (X) key-hint glyph in the empty field. */
    showKeyHint: Boolean = false,
) {
    val g = LocalConsoleGeometry.current
    val rowW = g.x(ConsoleDesign.WIDTH - 48)
    val rowH = g.hitMin(g.x(84))
    val parts = parseBrowseLabel(header.browseLabel)

    SubcomposeLayout(modifier = modifier.width(rowW).height(rowH)) { constraints ->
        val maxW = constraints.maxWidth
        val rowHPx = constraints.maxHeight
        val logoPx = g.x(48).roundToPx()
        val gapPx = g.x(16).roundToPx()
        val minSearchPx = g.hitMin(g.x(84)).roundToPx()
        val maxSearchPx = g.x(640).roundToPx()

        val logo = subcompose("logo") {
            Image(
                painterResource(R.drawable.gamebex_icon_foreground),
                contentDescription = "GameBEX",
                modifier = Modifier.size(g.x(48)),
            )
        }.map { it.measure(Constraints0) }
        val fixedPx = logo.sumOf { it.width } + gapPx

        fun navRow(collapsed: Boolean) = subcompose("nav-$collapsed") {
            NavRow(header, parts, collapsed, focused, onNav)
        }.map { it.measure(Constraints0) }

        val navFull = navRow(collapsed = false)
        val navFullPx = navFull.sumOf { it.width }
        val collapsed = browseCollapses(maxW, fixedPx, navFullPx, minSearchPx)
        val nav = if (collapsed) navRow(collapsed = true) else navFull
        val navPx = nav.sumOf { it.width }

        val searchW = searchWidthPx(maxW, fixedPx, navPx, maxSearchPx)
        val search = subcompose("search") {
            ConsoleSearchField(
                value = value,
                onValueChange = onValueChange,
                placeholder = header.searchPlaceholder,
                focused = focused == HeaderItem.SEARCH,
                focusRequester = searchFocus,
                onTap = onSearchTap,
                onSubmit = onSubmit,
                modifier = Modifier
                    .width(with(density) { searchW.toDp() })
                    .height(rowH),
                onClear = onClear,
                visibleHeight = g.x(64),
                onFocusChange = onFocusChange,
                showKeyHint = showKeyHint,
            )
        }.map { it.measure(Constraints0) }

        val navH = nav.maxOfOrNull { it.height } ?: 0
        val searchH = search.maxOfOrNull { it.height } ?: 0
        layout(maxW, rowHPx) {
            var x = 0
            for (p in logo) {
                p.place(x, (rowHPx - p.height) / 2)
                x += p.width
            }
            x += gapPx
            for (p in search) {
                p.place(x, (rowHPx - searchH) / 2 + (searchH - p.height) / 2)
            }
            var nx = maxW
            for (p in nav.asReversed()) {
                nx -= p.width
                p.place(nx, (rowHPx - navH) / 2 + (navH - p.height) / 2)
            }
        }
    }
}

/** Zero-constraints measure: every piece reports its intrinsic size. */
private val Constraints0 = androidx.compose.ui.unit.Constraints()

/** The nav pills row; [collapsed] shortens the Browse pill to "☁ Browse". */
@Composable
private fun NavRow(
    header: HeaderModel,
    parts: BrowseParts,
    collapsed: Boolean,
    focused: HeaderItem?,
    onNav: (HomeDestination) -> Unit,
) {
    val g = LocalConsoleGeometry.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        NavPill(focused == HeaderItem.LIBRARY, { onNav(HomeDestination.Library()) }) {
            NavText(HomeCopy.navLibrary, focused == HeaderItem.LIBRARY, homeT(g, 20))
        }

        NavPill(focused == HeaderItem.BROWSE, { onNav(HomeDestination.Discover) }) {
            if (parts.cloud) {
                // Filled pixel cloud (mockup `U.cloud`, 22×15),
                // not the outline Unicode "☁".
                CloudGlyph(
                    tint = ConsoleColors.TextTertiary,
                    modifier = Modifier.size(g.x(22), g.x(15)),
                )
            }
            NavText(parts.lead, focused == HeaderItem.BROWSE, homeT(g, 20))
            // "Browse {site}": the site name is width-capped with an ellipsis;
            // the collapsed form drops it entirely ("☁ Browse").
            if (!collapsed && parts.site != null) {
                Text(
                    parts.site,
                    color = if (focused == HeaderItem.BROWSE) {
                        ConsoleColors.TextPrimary
                    } else {
                        ConsoleColors.TextSecondary
                    },
                    fontSize = homeT(g, 20),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = g.x(SITE_NAME_CAP)),
                )
            }
        }

        NavPill(focused == HeaderItem.DOWNLOADS, { onNav(HomeDestination.Downloads) }) {
            NavText(HomeCopy.navDownloads, focused == HeaderItem.DOWNLOADS, homeT(g, 20))
            header.downloadsBadge?.let { badge ->
                val tone = downloadsBadgeTone(badge)
                Text(
                    badge,
                    color = if (tone == BadgeTone.COUNT) ConsoleColors.Success else ConsoleColors.TextTertiary,
                    fontSize = homeT(g, 20), fontWeight = FontWeight.Bold, maxLines = 1,
                )
            }
        }

        // The Play together hub, read exactly like its rail cell.
        NavPill(focused == HeaderItem.TOGETHER, { onNav(HomeDestination.Together) }) {
            NavText(TogetherCopy.navLabel, focused == HeaderItem.TOGETHER, homeT(g, 20))
        }

        NavPill(focused == HeaderItem.SYSTEM, { onNav(HomeDestination.System) }) {
            NavText(HomeCopy.navSystem, focused == HeaderItem.SYSTEM, homeT(g, 20))
            systemWarnText(header.systemWarnings)?.let { warn ->
                // Filled pixel warning triangle (mockup
                // `ICO.warn`), not the outline Unicode "⚠".
                WarnGlyph(
                    tint = ConsoleColors.Warning,
                    modifier = Modifier.size(g.x(16), g.x(14)),
                )
                Text(
                    warn, color = ConsoleColors.Warning,
                    fontSize = homeT(g, 20), fontWeight = FontWeight.Bold, maxLines = 1,
                )
            }
        }
    }
}

/** Design-px cap on the site name in "Browse {site}". */
private const val SITE_NAME_CAP = 320

/** A nav label: TextSecondary idle, TextPrimary when focused (mockup `.nb .v`). */
@Composable
private fun NavText(label: String, focused: Boolean, size: TextUnit) {
    Text(
        label,
        color = if (focused) ConsoleColors.TextPrimary else ConsoleColors.TextSecondary,
        fontSize = size,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * A nav pill: the visible pill is centered inside an 84×84 hit box,
 * so the tap target is large while the pill stays compact. Focus draws the
 * pink ring. [content] holds the label plus any badge / glyph.
 */
@Composable
private fun NavPill(
    focused: Boolean,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    val g = LocalConsoleGeometry.current
    val shape = RoundedCornerShape(g.x(10))
    Box(
        modifier = Modifier
            .sizeIn(minWidth = g.hitMin(g.x(84)), minHeight = g.hitMin(g.x(84)))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .background(if (focused) ConsoleColors.HighSurface else Color.Transparent, shape)
                .then(
                    if (focused) {
                        Modifier.border(g.x(ConsoleDesign.FOCUS_STROKE), ConsoleColors.FocusRing, shape)
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = g.x(16), vertical = g.x(12)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(g.x(6)),
            content = content,
        )
    }
}
