package com.gamebex.client.debug

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.gamebex.client.ui.GameBexTheme
import com.gamebex.client.ui.console.ConsoleDestination
import com.gamebex.client.ui.console.ConsoleGeometryRoot
import com.gamebex.client.ui.console.ConsoleScaffold
import com.gamebex.client.ui.console.ControllerHintBar
import com.gamebex.client.ui.console.rememberConsoleFocusMemory
import com.gamebex.client.ui.console.rememberConsoleHints
import com.gamebex.client.ui.together.TogetherGuestScreen
import com.gamebex.client.ui.together.TogetherHubScreen
import com.gamebex.client.ui.together.TogetherHostScreen
import com.gamebex.client.ui.together.TogetherInviteDialog
import com.gamebex.client.ui.together.JoinAddressDialog
import com.gamebex.client.ui.together.TogetherUi

import com.gamebex.client.together.TogetherCopy
/** Debug-only host for all Play together screen fixtures. */
class TogetherFixtureActivity : ComponentActivity() {
    private val state = mutableStateOf("waiting")
    private val titleOverride = mutableStateOf<String?>(null)
    private val fake = FakeTogetherController()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        state.value = intent?.getStringExtra(EXTRA_STATE).orEmpty().takeIf { it in TogetherFixtures.states } ?: "waiting"
        titleOverride.value = intent?.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        if (android.os.Build.VERSION.SDK_INT >= 28) window.attributes.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        setContent {
            val current by state
            val title by titleOverride
            LaunchedEffect(current) {
                if (current == "address-error") TogetherUi.hubMessage.value = TogetherCopy.addressInvalid
            }
            val hints = rememberConsoleHints()
            val focus = rememberConsoleFocusMemory()
            GameBexTheme {
                ConsoleGeometryRoot {
                    ConsoleScaffold(ConsoleDestination.System, {}, focus, showRail = false, controllerHints = { ControllerHintBar(hints.hints) }) { rail ->
                        when {
                            current == "hub-empty" || current == "hub-3" || current == "address-error" -> {
                                if (current == "hub-3") fake.setNearby((1..3).map { NearbyFixture.host(it) })
                                TogetherHubScreen(fake, rail, hints, onBack = { finish() }, onJoined = { state.value = "guest-ready" })
                            }
                            current == "invite" -> {
                                fake.setInvite(NearbyFixture.host(1))
                                TogetherHubScreen(fake, rail, hints, onBack = { finish() }, onJoined = { state.value = "guest-ready" })
                                TogetherInviteDialog(fake, "library", onJoined = { state.value = "guest-ready" })
                            }
                            current.startsWith("guest-") || current in setOf("connecting", "waiting-offer", "guest-checking", "action-needed", "downloading", "core-sync", "installing-core", "picking-game") -> {
                                fake.setGuest(TogetherFixtures.guest(current))
                                TogetherGuestScreen(fake, rail, hints, onBack = { finish() }, onOpenSystem = {}, onOpenPpsspp = {})
                            }
                            else -> {
                                val host = TogetherFixtures.host(current).let { view ->
                                    val heading = title
                                    if (heading.isNullOrBlank()) view else view.copy(gameTitle = heading)
                                }
                                fake.setHost(host)
                                TogetherHostScreen(fake, 1L, rail, hints, onBack = { finish() }, onOpenSystem = {}, onOpenPpsspp = {})
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        state.value = intent.getStringExtra(EXTRA_STATE).orEmpty().takeIf { it in TogetherFixtures.states } ?: state.value
        titleOverride.value = intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() } ?: titleOverride.value
    }

    private companion object {
        const val EXTRA_STATE = "state"
        const val EXTRA_TITLE = "title"
    }
}

private object NearbyFixture {
    fun host(id: Int) = com.gamebex.client.together.NearbyHost("device$id", "session$id", TogetherCopy.otherPhone, TogetherCopy.joining, "snes", com.gamebex.client.together.TogetherMode.RA_NETPLAY, "10.42.1.$id", 55440)
}
