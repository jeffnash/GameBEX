package com.gamebex.client

import android.content.Intent
import com.gamebex.client.together.GuestStep
import com.gamebex.client.together.GuestView
import com.gamebex.client.together.HostStep
import com.gamebex.client.together.HostView
import com.gamebex.client.together.TogetherMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JoinLinkGuardTest {
    @Test fun restoredOrHistoryDeliveryIsNotANewJoin() {
        assertTrue(joinLinkDeliveryIsReplay(true, 0))
        assertTrue(joinLinkDeliveryIsReplay(false, Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY))
        assertFalse(joinLinkDeliveryIsReplay(false, Intent.FLAG_ACTIVITY_NEW_TASK))
        assertFalse(joinLinkDeliveryIsReplay(false, 0))
    }

    @Test fun onlyALiveHostOrGuestBlocksAnotherJoinLink() {
        assertFalse(togetherSessionActive(null, null))
        assertFalse(togetherSessionActive(host(HostStep.ENDED), null))
        assertFalse(togetherSessionActive(null, GuestView(GuestStep.ENDED, "Sam")))
        assertTrue(togetherSessionActive(host(HostStep.CHECKING), null))
        assertTrue(togetherSessionActive(host(HostStep.WAITING), GuestView(GuestStep.ENDED, "Sam")))
        assertTrue(togetherSessionActive(null, GuestView(GuestStep.CONNECTING, "Sam")))
    }

    private fun host(step: HostStep) = HostView(
        step = step,
        gameId = 1L,
        gameTitle = "Game",
        folder = "snes",
        mode = TogetherMode.RA_NETPLAY,
        myName = "Sam",
    )
}
