package com.gamebex.client.ui.system

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gamebex.client.BuildConfig
import com.gamebex.client.ui.together.TogetherUiTestSupport
import com.gamebex.client.ui.together.performKeyInput
import com.gamebex.client.ui.together.setTogetherContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AboutScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test fun controllerOpensFullLicenseAndReturns() {
        var backs = 0
        rule.setTogetherContent { hints -> AboutScreen({ backs++ }, TogetherUiTestSupport.rail, hints) }
        rule.onNodeWithText("GameBEX ${BuildConfig.VERSION_NAME}").assertIsDisplayed()
        rule.onNodeWithText("© Jeff Nash and GameBEX contributors").assertIsDisplayed()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        rule.onRoot().performKeyInput { pressKey(Key.ButtonA) }
        rule.onNodeWithTag("about-document").assertIsDisplayed()
        rule.onNodeWithText("GNU AFFERO GENERAL PUBLIC LICENSE", substring = true).assertIsDisplayed()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        rule.onRoot().performKeyInput { pressKey(Key.ButtonB) }
        rule.onNodeWithText("GameBEX ${BuildConfig.VERSION_NAME}").assertIsDisplayed()
        rule.onRoot().performKeyInput { pressKey(Key.ButtonB) }
        rule.runOnIdle { assertEquals(1, backs) }
    }

    @Test fun noticesOpenAndIncludeBundledLibraryTerms() {
        rule.setTogetherContent { hints -> AboutScreen({}, TogetherUiTestSupport.rail, hints) }
        rule.onNodeWithTag("about-action-2").performClick()
        rule.onNodeWithTag("about-document").assertIsDisplayed()
        rule.onNodeWithText("Bundled libraries").assertIsDisplayed()
        rule.onNodeWithTag("about-document").performScrollToIndex(2)
        rule.onRoot().performKeyInput { pressKey(Key.ButtonB) }
        rule.onNodeWithTag("about-action-2").assertIsDisplayed()
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        val license = assets.open(APP_LICENSE_ASSET).bufferedReader().use { it.readText() }
        assertTrue(license.contains("13. Remote Network Interaction"))
        assertTrue(license.contains("How to Apply These Terms to Your New Programs"))
        val libraries = assets.open("licenses/bundled-libraries.txt").bufferedReader().use { it.readText() }
        listOf("androidx.compose.ui:ui-android:1.7.0", "org.tukaani:xz:1.9", "com.squareup.okhttp3:okhttp:4.12.0",
            "dev.rikka.shizuku:api:13.1.5", "org.jsoup:jsoup:1.17.2", "org.apache.commons:commons-compress:1.26.0",
            "Copyright (c) 2021 RikkaW", "Mozilla Public License Version 2.0").forEach {
            assertTrue("Missing library terms: $it", libraries.contains(it))
        }
        listOf("scrcpy_APACHE-2.0.txt", "silkscreen_OFL.txt", "space_grotesk_OFL.txt",
            "zstandard-license.txt", "zstd-jni-license.txt").forEach { name ->
            assertTrue(assets.open("licenses/$name").use { it.readBytes().isNotEmpty() })
        }
    }
}
