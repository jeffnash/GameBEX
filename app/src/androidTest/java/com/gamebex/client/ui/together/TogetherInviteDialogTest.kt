package com.gamebex.client.ui.together

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gamebex.client.debug.FakeTogetherController
import com.gamebex.client.together.NearbyHost
import com.gamebex.client.together.TogetherCopy
import com.gamebex.client.together.TogetherMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TogetherInviteDialogTest {
    @get:Rule val rule = createComposeRule()

    @Test fun joinIsInitiallyFocusedAndAJoins() {
        val fake = FakeTogetherController().also { it.setInvite(NearbyHost("d", "s", "Sam", "Game", "snes", TogetherMode.RA_NETPLAY, "10.42.1.20", 55440)) }
        rule.setContent { TogetherInviteDialog(fake, "library") {} }
        rule.onNodeWithText(TogetherCopy.join).performKeyInput { pressKey(Key.ButtonA) }
        assertEquals("Sam", fake.guest.value?.hostName)
    }

    @Test fun bDismissesInvite() {
        val fake = FakeTogetherController().also { it.setInvite(NearbyHost("d", "s", "Sam", "Game", "snes", TogetherMode.RA_NETPLAY, "10.42.1.20", 55440)) }
        rule.setContent { TogetherInviteDialog(fake, "library") {} }
        rule.onNodeWithText(TogetherCopy.join).performKeyInput { pressKey(Key.ButtonB) }
        assertEquals(null, fake.invite.value)
    }

    @Test fun togetherRouteSuppressesInvite() {
        val fake = FakeTogetherController().also { it.setInvite(NearbyHost("d", "s", "Sam", "Game", "snes", TogetherMode.RA_NETPLAY, "10.42.1.20", 55440)) }
        rule.setContent { TogetherInviteDialog(fake, "together/guest") {} }
        rule.onNodeWithText(TogetherCopy.inviteTitle).assertDoesNotExist()
    }
}
