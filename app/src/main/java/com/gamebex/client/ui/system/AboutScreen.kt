package com.gamebex.client.ui.system

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import com.gamebex.client.BuildConfig
import com.gamebex.client.ui.console.ConsoleColors
import com.gamebex.client.ui.console.ConsoleHints
import com.gamebex.client.ui.console.ControllerGlyph
import com.gamebex.client.ui.console.ControllerHint
import com.gamebex.client.ui.console.LocalConsoleGeometry
import com.gamebex.client.ui.console.RailHost
import com.gamebex.client.ui.console.SecondaryConsoleAction
import com.gamebex.client.ui.console.consoleKeys
import com.gamebex.client.ui.console.rememberScreenFocus
import com.gamebex.client.ui.console.screenFocus
import kotlinx.coroutines.launch

private const val SOURCE_URL = "https://github.com/jeffnash/GameBEX"
internal const val APP_LICENSE_ASSET = "licenses/GameBEX_AGPL-3.0-or-later.txt"

@Composable
fun AboutScreen(onBack: () -> Unit, rail: RailHost, hints: ConsoleHints? = null) {
    val g = LocalConsoleGeometry.current
    val assets = LocalContext.current.assets
    val uriHandler = LocalUriHandler.current
    var document by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableIntStateOf(0) }
    val scroll = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val screenFocus = rememberScreenFocus()
    val paragraphs = remember(document) {
        document?.split(Regex("\\n\\s*\\n"))?.filter { it.isNotBlank() }.orEmpty()
    }
    fun back() { if (document == null) onBack() else document = null }
    fun activate(index: Int) {
        selected = index
        when (index) {
            0 -> runCatching { uriHandler.openUri(SOURCE_URL) }
            1 -> document = assets.open(APP_LICENSE_ASSET).bufferedReader().use { it.readText() }
            2 -> document = assets.list("licenses").orEmpty()
                .filter { "licenses/$it" != APP_LICENSE_ASSET }.sorted()
                .joinToString("\n\n") { name ->
                    name.removeSuffix(".txt").replace('_', ' ') + "\n\n" +
                        assets.open("licenses/$name").bufferedReader().use { it.readText() }
                }
        }
    }
    fun move(delta: Int): Boolean {
        if (document == null) {
            selected = (selected + delta).coerceIn(0, 2)
            scope.launch { scroll.animateScrollToItem(5) }
        } else scope.launch { scroll.scrollBy(delta * 160f) }
        return true
    }
    fun scrollPage(delta: Int): Boolean {
        scope.launch { scroll.scrollBy(delta * 160f) }
        return true
    }
    BackHandler { back() }
    LaunchedEffect(document) {
        scroll.scrollToItem(0)
        hints?.set(listOf(
            ControllerHint(ControllerGlyph.A, if (document == null) "Open" else ""),
            ControllerHint(ControllerGlyph.B, "Back"),
        ).filter { it.text.isNotEmpty() })
    }
    Box(Modifier.fillMaxSize().testTag("about-screen").screenFocus(screenFocus).consoleKeys(
        onLeft = { if (document == null && selected == 0) rail.enterRail() else move(-1) },
        onRight = { move(1) },
        onUp = { scrollPage(-1) },
        onDown = { scrollPage(1) },
        onA = { if (document == null) activate(selected); true },
        onB = { back(); true },
    )) {
        Column(Modifier.fillMaxSize().padding(horizontal = g.x(48), vertical = g.x(30))) {
            Text(
                if (document == null) "ABOUT" else if (selected == 1) "GNU AGPL-3.0-or-later" else "OPEN-SOURCE NOTICES",
                color = ConsoleColors.TextPrimary, fontSize = g.t(30),
                lineHeight = g.t(36), fontWeight = FontWeight.Black,
            )
            if (document == null) {
                LazyColumn(Modifier.weight(1f).padding(top = g.x(14)), state = scroll, verticalArrangement = Arrangement.spacedBy(g.x(10))) {
                    item { AboutText("GameBEX ${BuildConfig.VERSION_NAME}") }
                    item { AboutText("© Jeff Nash and GameBEX contributors") }
                    item { AboutText("Free software under the GNU AGPL-3.0-or-later") }
                    item { AboutText(SOURCE_URL) }
                    item { AboutText("The GameBEX name and logo are not licensed. See TRADEMARK.md in the source for permitted uses.") }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(g.x(12))) {
                            listOf("Source code", "Full license", "Open-source notices").forEachIndexed { index, label ->
                                SecondaryConsoleAction(label, selected == index, { activate(index) },
                                    modifier = Modifier.testTag("about-action-$index"))
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    Modifier.weight(1f).padding(vertical = g.x(14)).testTag("about-document"),
                    state = scroll, verticalArrangement = Arrangement.spacedBy(g.x(14)),
                ) {
                    items(paragraphs) { AboutText(it) }
                }
            }
            SecondaryConsoleAction("Back", false, { back() }, modifier = Modifier.testTag("about-back"))
        }
    }
}

@Composable
private fun AboutText(text: String) {
    val g = LocalConsoleGeometry.current
    Text(text, color = ConsoleColors.TextSecondary, fontSize = g.tMin(17), lineHeight = g.tMin(24))
}
