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

    @Test fun downPastTheLastNearbyHostReachesTheAddressButton() {
        val fake = FakeTogetherController().also { it.setNearby(listOf(com.gamebex.client.together.NearbyHost("device", "session", "Sam", "Game", "snes", com.gamebex.client.together.TogetherMode.RA_NETPLAY, "10.42.1.20", 55440))) }
        rule.setTogetherContent { hints -> TogetherHubScreen(fake, TogetherUiTestSupport.rail, hints, {}, {}) }
        rule.onAllNodes(isRoot()).get(0).performKeyInput { pressKey(Key.DirectionDown) }
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

    @Test fun eyebrowAndHeadingsSitAboveTheirListsAtLarger() {
        val display = TestDisplay(textScale = LARGER)
        rule.setTogetherContentAt(display) { hints -> TogetherHubScreen(fakeWithHosts(), TogetherUiTestSupport.rail, hints, {}, {}, hostable = GAMES) }
        for (fontScale in FONT_SCALES) {
            rule.runOnIdle { display.fontScale = fontScale }
            val eyebrow = rule.onNodeWithText(TogetherCopy.eyebrow, useUnmergedTree = true).box()
            val hostHeading = rule.onNodeWithText(TogetherCopy.hostTitle, useUnmergedTree = true).box()
            val firstGame = rule.onNodeWithText(GAMES.first().title).box()
            val heading = rule.onNodeWithText(TogetherCopy.nearby, useUnmergedTree = true).box()
            val firstRow = rule.onNodeWithText(HOSTS.first().name).box()
            assertTrue("font $fontScale: eyebrow $eyebrow overlaps heading $hostHeading", eyebrow.bottom <= hostHeading.top)
            assertTrue("font $fontScale: heading $hostHeading overlaps the list $firstGame", hostHeading.bottom <= firstGame.top)
            assertTrue("font $fontScale: heading $heading overlaps the list $firstRow", heading.bottom <= firstRow.top)
            assertTrue("font $fontScale: the columns overlap", firstGame.right <= firstRow.left)
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
        rule.setTogetherContentAt(TestDisplay()) { hints -> TogetherHubScreen(fakeWithHosts(), TogetherUiTestSupport.rail, hints, {}, {}, hostable = GAMES) }
        val eyebrow = rule.onNodeWithText(TogetherCopy.eyebrow, useUnmergedTree = true).box()
        val label = rule.onNodeWithText(TogetherCopy.thisPhoneLabel, useUnmergedTree = true).box()
        // Eyebrow (84, 48); both headings y 76; both lists from y 140 (84 tall, 8 apart);
        // the join column at x 800 with this phone's block from y 424 and its buttons at y 532.
        val unit = (label.left - eyebrow.left) / (800f - 84f)
        fun y(top: Float) = (top - eyebrow.top) / unit + 48f
        fun x(left: Float) = (left - eyebrow.left) / unit + 84f
        assertEquals(76f, y(rule.onNodeWithText(TogetherCopy.hostTitle, useUnmergedTree = true).box().top), 1f)
        assertEquals(76f, y(rule.onNodeWithText(TogetherCopy.nearby, useUnmergedTree = true).box().top), 1f)
        val firstGame = rule.onNodeWithText(GAMES[0].title).box()
        assertEquals(84f, x(firstGame.left), 1f)
        assertEquals(140f, y(firstGame.top), 1f)
        assertEquals(84f, firstGame.height / unit, 1f)
        assertEquals(232f, y(rule.onNodeWithText(GAMES[1].title).box().top), 1f)
        val first = rule.onNodeWithText(HOSTS[0].name).box()
        val second = rule.onNodeWithText(HOSTS[1].name).box()
        assertEquals(800f, x(first.left), 1f)
        assertEquals(140f, y(first.top), 1f)
        assertEquals(84f, first.height / unit, 1f)
        assertEquals(232f, y(second.top), 1f)
        assertEquals(424f, y(label.top), 1f)
        assertEquals(448f, y(rule.onNodeWithText(ADDRESS, useUnmergedTree = true).box().top), 1f)
        assertEquals(494f, y(rule.onNodeWithText(TogetherCopy.shownAs(FakeTogetherController.DEFAULT_NAME), useUnmergedTree = true).box().top), 1f)
        assertEquals(532f, y(rule.onNodeWithText(TogetherCopy.joinByAddress.uppercase()).box().top), 1f)
        assertEquals(532f, y(rule.onNodeWithText(TogetherCopy.rename.uppercase()).box().top), 1f)
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

    @Test fun aSwipeReachesNearbyHostsPastTheFirstThree() {
        // Only three rows show; touch must reach the rest.
        val hosts = (1..8).map { i ->
            com.gamebex.client.together.NearbyHost(
                "d$i", "s$i", "Host $i", "Game $i", "snes",
                com.gamebex.client.together.TogetherMode.RA_NETPLAY, "10.42.1.$i", 55440,
            )
        }
        val fake = FakeTogetherController().also { it.setNearby(hosts) }
        rule.setTogetherContent { hints -> TogetherHubScreen(fake, TogetherUiTestSupport.rail, hints, {}, {}) }
        rule.onNodeWithText("Host 7").assertDoesNotExist()
        rule.onNodeWithText("Host 2").performTouchInput {
            swipe(center, androidx.compose.ui.geometry.Offset(center.x, center.y - height * 12f), durationMillis = 800)
        }
        rule.onNodeWithText("Host 7").assertExists().performClick()
        rule.runOnIdle { assertEquals("Host 7", fake.guest.value?.hostName) }
    }

    @Test fun theCursorStartsOnTheHostListAndAHostsTheSelectedGame() {
        val hosted = mutableListOf<Long>()
        var captured: com.gamebex.client.ui.console.ConsoleHints? = null
        rule.setTogetherContent { hints ->
            captured = hints
            TogetherHubScreen(fakeWithHosts(), TogetherUiTestSupport.rail, hints, {}, {}, hostable = GAMES, onHost = { hosted += it })
        }
        rule.runOnIdle { assertEquals(TogetherCopy.hostAction, captured!!.aHint()) }
        rule.onAllNodes(isRoot()).get(0).performKeyInput { pressKey(Key.DirectionDown) }
        rule.onAllNodes(isRoot()).get(0).performKeyInput { pressKey(Key.ButtonA) }
        rule.runOnIdle { assertEquals(listOf(GAMES[1].id), hosted) }
    }

    @Test fun tappingAGameHostsIt() {
        val hosted = mutableListOf<Long>()
        rule.setTogetherContent { hints -> TogetherHubScreen(fakeWithHosts(), TogetherUiTestSupport.rail, hints, {}, {}, hostable = GAMES, onHost = { hosted += it }) }
        rule.onNodeWithText(GAMES[2].title).performClick()
        rule.runOnIdle { assertEquals(listOf(GAMES[2].id), hosted) }
    }

    @Test fun gameRowsShowTheirSystemAndMode() {
        rule.setTogetherContent { hints -> TogetherHubScreen(FakeTogetherController(), TogetherUiTestSupport.rail, hints, {}, {}, hostable = GAMES) }
        for (game in GAMES) rule.onNodeWithText(hostRowLine(game), useUnmergedTree = true).assertExists()
    }

    @Test fun rightJoinsNearbyAndLeftComesBackToTheSameGame() {
        val fake = fakeWithHosts()
        val hosted = mutableListOf<Long>()
        var captured: com.gamebex.client.ui.console.ConsoleHints? = null
        rule.setTogetherContent { hints ->
            captured = hints
            TogetherHubScreen(fake, TogetherUiTestSupport.rail, hints, {}, {}, hostable = GAMES, onHost = { hosted += it })
        }
        rule.onAllNodes(isRoot()).get(0).performKeyInput { pressKey(Key.DirectionDown); pressKey(Key.DirectionRight) }
        rule.runOnIdle { assertEquals(TogetherCopy.join, captured!!.aHint()) }
        rule.onAllNodes(isRoot()).get(0).performKeyInput { pressKey(Key.DirectionLeft); pressKey(Key.ButtonA) }
        rule.runOnIdle { assertEquals(listOf(GAMES[1].id), hosted); assertEquals(null, fake.guest.value) }
        rule.onAllNodes(isRoot()).get(0).performKeyInput { pressKey(Key.DirectionRight); pressKey(Key.ButtonA) }
        rule.runOnIdle { assertEquals(HOSTS.first().name, fake.guest.value?.hostName) }
    }

    @Test fun leftFromTheHostListEntersTheRail() {
        var entered = 0
        val rail = object : com.gamebex.client.ui.console.RailHost { override fun enterRail(): Boolean { entered++; return true } }
        rule.setTogetherContent { hints -> TogetherHubScreen(fakeWithHosts(), rail, hints, {}, {}, hostable = GAMES) }
        rule.onAllNodes(isRoot()).get(0).performKeyInput { pressKey(Key.DirectionLeft) }
        rule.runOnIdle { assertEquals(1, entered) }
    }

    @Test fun hostWindowFollowsSelection() {
        val games = (1..7).map { i -> HostableGame(i.toLong(), "Title $i", "snes", com.gamebex.client.together.TogetherMode.RA_NETPLAY, 0) }
        val hosted = mutableListOf<Long>()
        rule.setTogetherContent { hints -> TogetherHubScreen(FakeTogetherController(), TogetherUiTestSupport.rail, hints, {}, {}, hostable = games, onHost = { hosted += it }) }
        rule.onNodeWithText("Title 6").assertDoesNotExist()
        rule.onAllNodes(isRoot()).get(0).performKeyInput { repeat(5) { pressKey(Key.DirectionDown) } }
        rule.onNodeWithText("Title 6").assertExists()
        rule.onNodeWithText("Title 1").assertDoesNotExist()
        rule.onAllNodes(isRoot()).get(0).performKeyInput { pressKey(Key.ButtonA) }
        rule.runOnIdle { assertEquals(listOf(6L), hosted) }
    }

    @Test fun anEmptyLibrarySaysWhichSystemsCanPlayTogether() {
        val hostable = mutableStateOf<List<HostableGame>?>(null)
        rule.setTogetherContent { hints -> TogetherHubScreen(FakeTogetherController(), TogetherUiTestSupport.rail, hints, {}, {}, hostable = hostable.value) }
        // Still loading: no empty line yet.
        rule.onNodeWithText(TogetherCopy.hostEmpty).assertDoesNotExist()
        rule.runOnIdle { hostable.value = emptyList() }
        rule.onNodeWithText(TogetherCopy.hostEmpty).assertExists()
        rule.onNodeWithText(TogetherCopy.hubEmpty).assertExists()
        rule.runOnIdle { hostable.value = GAMES }
        rule.onNodeWithText(TogetherCopy.hostEmpty).assertDoesNotExist()
        rule.onNodeWithText(GAMES[0].title).assertExists()
    }

    @Test fun bothListsFitAtEveryTextSize() {
        val display = TestDisplay()
        rule.setTogetherContentAt(display) { hints -> TogetherHubScreen(fakeWithHosts(), TogetherUiTestSupport.rail, hints, {}, {}, hostable = GAMES) }
        for ((textScale, fontScale) in listOf(1f to 1f, 1.25f to 1f, 1.4f to 1f, 1.4f to 1.3f)) {
            rule.runOnIdle { display.textScale = textScale; display.fontScale = fontScale }
            val at = "$textScale x $fontScale"
            var previousBottom = Float.NEGATIVE_INFINITY
            for (game in GAMES) {
                val row = rule.onNodeWithText(game.title).box()
                assertFalse("$at: ${game.title} is cut", rule.onNodeWithText(game.title, useUnmergedTree = true).textIsCut())
                assertFalse("$at: ${hostRowLine(game)} is cut", rule.onNodeWithText(hostRowLine(game), useUnmergedTree = true).textIsCut())
                assertTrue("$at: game rows overlap", row.top >= previousBottom - EPS)
                previousBottom = row.bottom
            }
            val lastHost = rule.onNodeWithText(HOSTS.last().name).box()
            val label = rule.onNodeWithText(TogetherCopy.thisPhoneLabel, useUnmergedTree = true).box()
            assertTrue("$at: the nearby list overlaps this phone's block", lastHost.bottom <= label.top + EPS)
        }
    }

    private fun com.gamebex.client.ui.console.ConsoleHints.aHint(): String? =
        hints.firstOrNull { it.glyph == com.gamebex.client.ui.console.ControllerGlyph.A }?.text

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
        val GAMES = listOf(
            HostableGame(11, "Super Mario World", "snes", com.gamebex.client.together.TogetherMode.RA_NETPLAY, 300),
            HostableGame(12, "Pokemon Red", "gb", com.gamebex.client.together.TogetherMode.GB_LINK, 200),
            HostableGame(13, "Mario Kart 64", "n64", com.gamebex.client.together.TogetherMode.STREAM, 100),
        )
    }
}
