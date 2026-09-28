package com.gamebex.client.ui.together

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assert
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gamebex.client.debug.FakeTogetherController
import com.gamebex.client.debug.TogetherFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.gamebex.client.together.TogetherCopy
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TogetherHubScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test fun browsingStartsAndStops() {
        val fake = FakeTogetherController()
        val visible = mutableStateOf(true)
        rule.setTogetherContent { hints -> if (visible.value) TogetherHubScreen(fake, TogetherUiTestSupport.rail, hints, {}, {}) }
        rule.runOnIdle { assertEquals(1, fake.startedBrowsing); visible.value = false }
        rule.waitForIdle()
        assertEquals(1, fake.stoppedBrowsing)
    }

    @Test fun rightMovesToAddressButton() {
        val fake = FakeTogetherController().also { it.setNearby(listOf(com.gamebex.client.together.NearbyHost("device", "session", "Sam", "Game", "snes", com.gamebex.client.together.TogetherMode.RA_NETPLAY, "10.42.1.20", 55440))) }
        rule.setTogetherContent { hints -> TogetherHubScreen(fake, TogetherUiTestSupport.rail, hints, {}, {}) }
        rule.onAllNodes(isRoot()).get(0).performKeyInput { pressKey(Key.DirectionRight) }
        rule.onAllNodes(isRoot()).get(0).performKeyInput { pressKey(Key.ButtonA) }
        rule.onNode(hasSetTextAction()).assertExists()
    }

    @Test fun imeGoJoinsTypedAddress() {
        val fake = FakeTogetherController()
        rule.setTogetherContent { hints -> TogetherHubScreen(fake, TogetherUiTestSupport.rail, hints, {}, {}) }
        rule.onAllNodes(isRoot()).get(0).performKeyInput { pressKey(Key.ButtonA) }
        rule.onNode(hasSetTextAction()).performTextInput("10.42.1.20")
        rule.onNode(hasSetTextAction()).performImeAction()
        rule.runOnIdle { assertEquals(com.gamebex.client.together.TogetherCopy.otherPhone, fake.guest.value?.hostName) }
    }
    @Test fun invalidAddressShowsError() {
        val fake = FakeTogetherController()
        rule.setTogetherContent { hints -> TogetherHubScreen(fake, TogetherUiTestSupport.rail, hints, {}, {}) }
        rule.onAllNodes(isRoot()).get(0).performKeyInput { pressKey(Key.ButtonA) }
        rule.onNode(hasSetTextAction()).performTextInput("not-an-address")
        rule.onNode(hasSetTextAction()).performImeAction()
        rule.onAllNodesWithText(TogetherCopy.addressInvalid).get(0).assertExists()
    }

    @Test fun nearbyWindowFollowsSelection() {
        val hosts = (1..7).map { i ->
            com.gamebex.client.together.NearbyHost("d$i", "s$i", "Host $i", "Game $i", "snes", com.gamebex.client.together.TogetherMode.RA_NETPLAY, "10.42.1.$i", 55440)
        }
        val fake = FakeTogetherController().also { it.setNearby(hosts) }
        rule.setTogetherContent { hints -> TogetherHubScreen(fake, TogetherUiTestSupport.rail, hints, {}, {}) }
        rule.onAllNodes(isRoot()).get(0).performKeyInput { repeat(5) { pressKey(Key.DirectionDown) } }
        rule.onNodeWithText("Host 6").assertExists()
        rule.onNodeWithText("Host 1").assertDoesNotExist()
        rule.onAllNodes(isRoot()).get(0).performKeyInput { pressKey(Key.ButtonA) }
        rule.runOnIdle { assertEquals("Host 6", fake.guest.value?.hostName) }
    }

    @Test fun eyebrowSitsAboveTheNearbyHeadingAtLarger() {
        val display = TestDisplay(textScale = LARGER)
        rule.setTogetherContentAt(display) { hints -> TogetherHubScreen(fakeWithHosts(), TogetherUiTestSupport.rail, hints, {}, {}) }
        for (fontScale in FONT_SCALES) {
            rule.runOnIdle { display.fontScale = fontScale }
            val eyebrow = rule.onNodeWithText(TogetherCopy.eyebrow, useUnmergedTree = true).box()
            val heading = rule.onNodeWithText(TogetherCopy.nearby, useUnmergedTree = true).box()
            val firstRow = rule.onNodeWithText(HOSTS.first().name).box()
            assertTrue("font $fontScale: eyebrow $eyebrow overlaps heading $heading", eyebrow.bottom <= heading.top)
            assertTrue("font $fontScale: heading $heading overlaps the list $firstRow", heading.bottom <= firstRow.top)
        }
    }

    @Test fun nearbyRowsShowBothLinesAtLarger() {
        val display = TestDisplay(textScale = LARGER)
        rule.setTogetherContentAt(display) { hints -> TogetherHubScreen(fakeWithHosts(), TogetherUiTestSupport.rail, hints, {}, {}) }
        for (fontScale in FONT_SCALES) {
            rule.runOnIdle { display.fontScale = fontScale }
            var previousBottom = Float.NEGATIVE_INFINITY
            for (host in HOSTS) {
                val row = rule.onNodeWithText(host.name).box()
                val name = rule.onNodeWithText(host.name, useUnmergedTree = true)
                val line = rule.onNodeWithText(rowLine(host), useUnmergedTree = true)
                assertFalse("font $fontScale: ${host.name} is cut", name.textIsCut())
                assertFalse("font $fontScale: ${rowLine(host)} is cut", line.textIsCut())
                assertTrue("font $fontScale: ${host.name} leaves its row", name.box().top >= row.top - EPS)
                assertTrue("font $fontScale: second line leaves its row", line.box().bottom <= row.bottom + EPS)
                assertTrue("font $fontScale: rows overlap", row.top >= previousBottom - EPS)
                previousBottom = row.bottom
            }
        }
    }

    @Test fun thisPhoneLabelSitsAboveTheAddressAtLarger() {
        val display = TestDisplay(textScale = LARGER)
        rule.setTogetherContentAt(display) { hints -> TogetherHubScreen(fakeWithHosts(), TogetherUiTestSupport.rail, hints, {}, {}) }
        for (fontScale in FONT_SCALES) {
            rule.runOnIdle { display.fontScale = fontScale }
            val label = rule.onNodeWithText(TogetherCopy.thisPhoneLabel, useUnmergedTree = true).box()
            val address = rule.onNodeWithText(ADDRESS, useUnmergedTree = true).box()
            val button = rule.onNodeWithText(TogetherCopy.joinByAddress.uppercase()).box()
            assertTrue("font $fontScale: label $label overlaps address $address", label.bottom <= address.top)
            assertTrue("font $fontScale: address $address overlaps button $button", address.bottom <= button.top)
        }
    }

    @Test fun hubKeepsSpecSpacingAtDefault() {
        rule.setTogetherContentAt(TestDisplay()) { hints -> TogetherHubScreen(fakeWithHosts(), TogetherUiTestSupport.rail, hints, {}, {}) }
        val eyebrow = rule.onNodeWithText(TogetherCopy.eyebrow, useUnmergedTree = true).box()
        val label = rule.onNodeWithText(TogetherCopy.thisPhoneLabel, useUnmergedTree = true).box()
 // eyebrow (84, 48), heading y 76, rows from y 140 (84 tall, 8 apart); right column x 800.
        val unit = (label.left - eyebrow.left) / (800f - 84f)
        fun y(top: Float) = (top - eyebrow.top) / unit + 48f
        assertEquals(76f, y(rule.onNodeWithText(TogetherCopy.nearby, useUnmergedTree = true).box().top), 1f)
        val first = rule.onNodeWithText(HOSTS[0].name).box()
        val second = rule.onNodeWithText(HOSTS[1].name).box()
        assertEquals(140f, y(first.top), 1f)
        assertEquals(84f, first.height / unit, 1f)
        assertEquals(232f, y(second.top), 1f)
        assertEquals(140f, y(label.top), 1f)
        assertEquals(168f, y(rule.onNodeWithText(ADDRESS, useUnmergedTree = true).box().top), 1f)
        assertEquals(214f, y(rule.onNodeWithText(TogetherCopy.shownAs(FakeTogetherController.DEFAULT_NAME), useUnmergedTree = true).box().top), 1f)
        assertEquals(256f, y(rule.onNodeWithText(TogetherCopy.joinByAddress.uppercase()).box().top), 1f)
        assertEquals(256f, y(rule.onNodeWithText(TogetherCopy.rename.uppercase()).box().top), 1f)
    }

    @Test fun thisPhoneShowsTheNameOtherPhonesSeeAndYOffersRename() {
        val fake = fakeWithHosts()
        var captured: com.gamebex.client.ui.console.ConsoleHints? = null
        rule.setTogetherContent { hints -> captured = hints; TogetherHubScreen(fake, TogetherUiTestSupport.rail, hints, {}, {}) }
        rule.onNodeWithText(TogetherCopy.shownAs(FakeTogetherController.DEFAULT_NAME)).assertExists()
        rule.runOnIdle {
            assertTrue(captured!!.hints.any { it.glyph == com.gamebex.client.ui.console.ControllerGlyph.Y && it.text == TogetherCopy.rename })
        }
    }

    @Test fun yOpensRenameAndImeDoneSavesTheName() {
        val fake = fakeWithHosts()
        rule.setTogetherContent { hints -> TogetherHubScreen(fake, TogetherUiTestSupport.rail, hints, {}, {}) }
        rule.onAllNodes(isRoot()).get(0).performKeyInput { pressKey(Key.ButtonY) }
        rule.onNodeWithText(TogetherCopy.renameTitle).assertExists()
        rule.onNode(hasSetTextAction()).performTextInput("Sam's Deck")
        rule.onNode(hasSetTextAction()).performImeAction()
        rule.runOnIdle { assertEquals(listOf<String?>("Sam's Deck"), fake.renames) }
        rule.onNodeWithText(TogetherCopy.renameTitle).assertDoesNotExist()
        rule.onNodeWithText(TogetherCopy.shownAs("Sam's Deck")).assertExists()
    }

    @Test fun tappingRenameThenUsePhoneNameClearsTheCustomName() {
        val fake = fakeWithHosts().also { it.rename("Sam's Deck") }
        rule.setTogetherContent { hints -> TogetherHubScreen(fake, TogetherUiTestSupport.rail, hints, {}, {}) }
        rule.onNodeWithText(TogetherCopy.shownAs("Sam's Deck")).assertExists()
        rule.onNodeWithText(TogetherCopy.rename.uppercase()).performClick()
        rule.onNode(hasSetTextAction()).assert(hasText("Sam's Deck"))
        rule.onNodeWithText(TogetherCopy.usePhoneName).performClick()
        rule.runOnIdle { assertEquals(null, fake.renames.last()) }
        rule.onNodeWithText(TogetherCopy.renameTitle).assertDoesNotExist()
        rule.onNodeWithText(TogetherCopy.shownAs(FakeTogetherController.DEFAULT_NAME)).assertExists()
    }

    @Test fun thisPhoneBlockAndRenameDialogFitAtEveryTextSize() {
        val display = TestDisplay()
        val fake = fakeWithHosts().also { it.rename("Sam's Steam Deck OLED 1TB") }
        rule.setTogetherContentAt(display) { hints -> TogetherHubScreen(fake, TogetherUiTestSupport.rail, hints, {}, {}) }
        val name = TogetherCopy.shownAs(fake.myName.value)
        for ((textScale, fontScale) in listOf(1f to 1f, 1.25f to 1f, 1.4f to 1f, 1.4f to 1.3f)) {
            rule.runOnIdle { display.textScale = textScale; display.fontScale = fontScale }
            val at = "$textScale x $fontScale"
            val address = rule.onNodeWithText(ADDRESS, useUnmergedTree = true).box()
            val shown = rule.onNodeWithText(name, useUnmergedTree = true)
            val join = rule.onNodeWithText(TogetherCopy.joinByAddress.uppercase()).box()
            val rename = rule.onNodeWithText(TogetherCopy.rename.uppercase()).box()
            assertFalse("$at: name line is cut", shown.textIsCut())
            assertTrue("$at: address overlaps the name line", address.bottom <= shown.box().top)
            assertTrue("$at: name line overlaps the buttons", shown.box().bottom <= join.top)
            assertTrue("$at: buttons overlap", join.right <= rename.left)
        }
        rule.onNodeWithText(TogetherCopy.rename.uppercase()).performClick()
        for ((textScale, fontScale) in listOf(1f to 1f, 1.25f to 1f, 1.4f to 1f, 1.4f to 1.3f)) {
            rule.runOnIdle { display.textScale = textScale; display.fontScale = fontScale }
            val at = "$textScale x $fontScale"
            val field = rule.onNode(hasSetTextAction()).box()
            val usePhone = rule.onNodeWithText(TogetherCopy.usePhoneName).box()
            val cancel = rule.onNodeWithText(TogetherCopy.cancel).box()
            val save = rule.onNodeWithText(TogetherCopy.save).box()
            assertTrue("$at: field overlaps the buttons", field.bottom <= usePhone.top)
            assertTrue("$at: buttons overlap", usePhone.right <= cancel.left && cancel.right <= save.left)
            assertFalse("$at: prompt is cut", rule.onNodeWithText(TogetherCopy.renamePrompt, useUnmergedTree = true).textIsCut())
        }
    }

    @Test fun aSwipeReachesHostsPastTheFirstFive() {
 // Only five rows show; touch must reach the rest (it used to take a D-pad).
        val hosts = (1..8).map { i ->
            com.gamebex.client.together.NearbyHost(
                "d$i", "s$i", "Host $i", "Game $i", "snes",
                com.gamebex.client.together.TogetherMode.RA_NETPLAY, "10.42.1.$i", 55440,
            )
        }
        val fake = FakeTogetherController().also { it.setNearby(hosts) }
        rule.setTogetherContent { hints -> TogetherHubScreen(fake, TogetherUiTestSupport.rail, hints, {}, {}) }
        rule.onNodeWithText("Host 7").assertDoesNotExist()
        rule.onNodeWithText("Host 4").performTouchInput {
            swipe(center, androidx.compose.ui.geometry.Offset(center.x, center.y - height * 12f), durationMillis = 800)
        }
        rule.onNodeWithText("Host 7").assertExists().performClick()
        rule.runOnIdle { assertEquals("Host 7", fake.guest.value?.hostName) }
    }

    private fun fakeWithHosts() = FakeTogetherController().also { it.setNearby(HOSTS) }

    private fun rowLine(host: com.gamebex.client.together.NearbyHost) = "${host.gameTitle} · ${togetherPlatformName(host.folder)}"

    private companion object {
        const val LARGER = 1.4f
        val FONT_SCALES = listOf(1f, 1.3f)
        const val EPS = 0.5f
        const val ADDRESS = "10.42.1.20"
        val HOSTS = (1..3).map { i ->
            com.gamebex.client.together.NearbyHost(
                "d$i", "s$i", "Host $i", "Game $i", "snes",
                com.gamebex.client.together.TogetherMode.RA_NETPLAY, "10.42.1.$i", 55440,
            )
        }
    }
}
