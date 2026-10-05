package com.gamebex.client.stream.guest

import android.util.Log
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gamebex.client.ui.GameBexTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Every per-system layout renders its signature controls. */
@RunWith(AndroidJUnit4::class)
class StreamTouchPadTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @org.junit.Before
    fun lockLandscape() {
        rule.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        val deadline = android.os.SystemClock.uptimeMillis() + 10_000
        while (rule.activity.resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE &&
            android.os.SystemClock.uptimeMillis() < deadline) {
            Thread.sleep(100)
        }
    }

    private fun show(profile: TouchProfile?) {
        rule.setContent {
            GameBexTheme {
                StreamTouchPad({ _, _, _ -> }, { _, _, _ -> }, {}, profile = profile)
            }
        }
    }

    @Test
    fun n64ShowsCButtonsAndStart() {
        show(TouchProfile(TouchFolders.N64, TouchBackend.RETROARCH))
        rule.onNodeWithTag(TAG_TOUCH_PAD, useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag(touchControlTag("c:cup"), useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag(touchControlTag("start"), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun gameCubeCoreShowsHalvesAndCStick() {
        show(TouchProfile(TouchFolders.GC, TouchBackend.RETROARCH))
        rule.onNodeWithTag(touchControlTag("lhalf"), useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag(touchControlTag("rhalf"), useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag(touchControlTag("cstick"), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun dualShockShowsTwoSticksAndR3() {
        show(TouchProfile(TouchFolders.PSX, TouchBackend.RETROARCH, TouchControllerModes.PSX_DUALSHOCK))
        rule.onNodeWithTag(TAG_TOUCH_STICK, useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag(touchControlTag("r3"), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun unknownProfileFallsBackToGenericPad() {
        show(TouchProfile("psp", TouchBackend.UNKNOWN))
        rule.onNodeWithTag(TAG_TOUCH_PAD, useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag(TAG_TOUCH_STICK, useUnmergedTree = true).assertIsDisplayed()
    }

    /**
     * Renders every layout in turn for the host to capture. The host watches
     * logcat for `WP26SHOT SHOW <id>` and screencaps each.
     */
    @Test
    fun screenshotParadeLogsEachLayout() {
        val parade = listOf(
            TouchProfile("unknown", TouchBackend.UNKNOWN) to TouchLayoutId.GENERIC,
            TouchProfile(TouchFolders.N64, TouchBackend.RETROARCH) to TouchLayoutId.N64,
            TouchProfile(TouchFolders.PSX, TouchBackend.RETROARCH, TouchControllerModes.PSX_DIGITAL) to TouchLayoutId.PSX_DIGITAL,
            TouchProfile(TouchFolders.PSX, TouchBackend.RETROARCH, TouchControllerModes.PSX_DUALSHOCK) to TouchLayoutId.PSX_DUALSHOCK,
            TouchProfile(TouchFolders.PS2, TouchBackend.NETHERSX2) to TouchLayoutId.PS2,
            TouchProfile(TouchFolders.GC, TouchBackend.RETROARCH) to TouchLayoutId.GC_CORE,
            TouchProfile(TouchFolders.GC, TouchBackend.DOLPHIN) to TouchLayoutId.GC_STANDALONE,
            TouchProfile(TouchFolders.WII, TouchBackend.RETROARCH, TouchControllerModes.WII_NUNCHUK) to TouchLayoutId.WII_NUNCHUK_RA,
            TouchProfile(TouchFolders.WII, TouchBackend.DOLPHIN, TouchControllerModes.WII_NUNCHUK) to TouchLayoutId.WII_NUNCHUK_STANDALONE,
            TouchProfile(TouchFolders.SATURN, TouchBackend.RETROARCH, TouchControllerModes.SATURN_PAD) to TouchLayoutId.SATURN,
            TouchProfile(TouchFolders.SATURN, TouchBackend.RETROARCH, TouchControllerModes.SATURN_3D) to TouchLayoutId.SATURN_3D,
            TouchProfile(TouchFolders.DREAMCAST, TouchBackend.RETROARCH) to TouchLayoutId.DREAMCAST,
            TouchProfile(TouchFolders.NAOMI, TouchBackend.RETROARCH) to TouchLayoutId.NAOMI,
            TouchProfile(TouchFolders.TDO, TouchBackend.RETROARCH) to TouchLayoutId.TDO,
        )
        val current = androidx.compose.runtime.mutableStateOf(parade.first().first)
        rule.setContent {
            GameBexTheme {
                StreamTouchPad({ _, _, _ -> }, { _, _, _ -> }, {}, profile = current.value)
            }
        }
        for ((profile, id) in parade) {
            current.value = profile
            rule.waitForIdle()
            rule.onNodeWithTag(TAG_TOUCH_PAD, useUnmergedTree = true).assertIsDisplayed()
            Log.i("WP26SHOT", "SHOW $id")
            Thread.sleep(3000)
        }
    }
}
