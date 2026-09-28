package com.gamebex.client.debug

import com.gamebex.client.together.GuestPhase
import com.gamebex.client.together.GuestStep
import com.gamebex.client.together.GuestView
import com.gamebex.client.together.HostStep
import com.gamebex.client.together.HostView
import com.gamebex.client.together.NearbyHost
import com.gamebex.client.together.Problem
import com.gamebex.client.together.TogetherAction
import com.gamebex.client.together.TogetherController
import com.gamebex.client.together.TogetherMode
import com.gamebex.client.together.TogetherCopy
import com.gamebex.client.together.net.JoinLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Debug-only controller with deterministic screen states for UI iteration. */
class FakeTogetherController : TogetherController {
    private val nearbyFlow = MutableStateFlow<List<NearbyHost>>(emptyList())
    private val inviteFlow = MutableStateFlow<NearbyHost?>(null)
    private val hostFlow = MutableStateFlow<HostView?>(null)
    private val guestFlow = MutableStateFlow<GuestView?>(null)
    private val addressFlow = MutableStateFlow<String?>("10.42.1.20")
    override val nearby: StateFlow<List<NearbyHost>> = nearbyFlow.asStateFlow()
    override val invite: StateFlow<NearbyHost?> = inviteFlow.asStateFlow()
    override val host: StateFlow<HostView?> = hostFlow.asStateFlow()
    override val guest: StateFlow<GuestView?> = guestFlow.asStateFlow()
    override val myAddress: StateFlow<String?> = addressFlow.asStateFlow()
    private val customNameFlow = MutableStateFlow<String?>(null)
    private val nameFlow = MutableStateFlow(DEFAULT_NAME)
    override val myName: StateFlow<String> = nameFlow.asStateFlow()
    override val customName: StateFlow<String?> = customNameFlow.asStateFlow()
    val renames = mutableListOf<String?>()

    var lastAction: TogetherAction? = null
    var hostStarts = 0
    var hostGames = 0
    var hostRemoves = 0
    var hostPlayAgains = 0
    var lastPicked: Long? = null
    var startedBrowsing = 0
    var stoppedBrowsing = 0
    var ended = 0

    fun setHost(value: HostView?) { hostFlow.value = value }
    fun setGuest(value: GuestView?) { guestFlow.value = value }
    fun setNearby(value: List<NearbyHost>) { nearbyFlow.value = value }
    fun setInvite(value: NearbyHost?) { inviteFlow.value = value }

    override fun startBrowsing() { startedBrowsing++ }
    override fun stopBrowsing() { stoppedBrowsing++ }
    override fun dismissInvite() { inviteFlow.value = null }
    override fun hostGame(gameId: Long) { hostGames++; if (hostFlow.value == null || hostFlow.value?.gameId != gameId || hostFlow.value?.step == HostStep.ENDED) hostFlow.value = HostView(HostStep.CHECKING, gameId, TogetherCopy.joining, "snes", TogetherMode.RA_NETPLAY, TogetherCopy.otherPhone, "10.42.1.20", 55440, "gamebex://join?v=1&h=10.42.1.20&p=55440&n=Player") }
    override fun hostStart() { hostStarts++ }
    override fun hostRemoveGuest() { hostRemoves++; hostFlow.value = hostFlow.value?.copy(guestName = null, guestPhase = null) }
    override fun hostPlayAgain() { hostPlayAgains++ }
    override fun join(target: NearbyHost) { guestFlow.value = GuestView(GuestStep.CONNECTING, target.name) }
    override fun joinAddress(text: String): String? {
        val parsed = JoinLink.parseAddress(text) ?: return TogetherCopy.addressInvalid
        guestFlow.value = GuestView(GuestStep.CONNECTING, parsed.name)
        return null
    }
    override fun joinLink(uri: String): String? = JoinLink.parseLink(uri)?.let { join(it); null } ?: TogetherCopy.linkInvalid
    override fun act(action: TogetherAction) { lastAction = action }
    override fun pick(gameId: Long) { lastPicked = gameId }
    override fun end() { ended++ }
    override fun onForeground() = Unit
    override fun rename(name: String?) {
        renames += name
        val saved = com.gamebex.client.together.platform.TogetherPrefs.sanitizeCustomName(name)
        customNameFlow.value = saved
        nameFlow.value = saved ?: DEFAULT_NAME
    }

    companion object {
        /** The fixture phone's default name (device name plus the address's last octet). */
        const val DEFAULT_NAME = "Pixel 8 ·20"
    }
}

object TogetherFixtures {
    val states: Set<String> = setOf(
        "checking", "not-ready", "waiting", "joined", "launching", "in-game", "ended",
        "connecting", "waiting-offer", "guest-checking", "action-needed", "downloading", "core-sync",
        "installing-core", "picking-game", "guest-ready", "guest-launching", "guest-in-game", "guest-ended",
        "hub-empty", "hub-3", "invite", "address-error",
    )

    fun host(state: String): HostView = when (state) {
        "not-ready" -> baseHost(HostStep.NOT_READY).copy(problem = Problem(TogetherCopy.needsEmulator("RetroArch"), TogetherAction.OPEN_SYSTEM))
        "waiting" -> baseHost(HostStep.WAITING).copy(qrText = "gamebex://join?v=1&h=10.42.1.20&p=55440&n=Player")
        "joined" -> baseHost(HostStep.JOINED).copy(guestName = TogetherCopy.otherPhone, guestPhase = GuestPhase.READY)
        "launching" -> baseHost(HostStep.LAUNCHING).copy(guestName = TogetherCopy.otherPhone, guestPhase = GuestPhase.PLAYING)
        "in-game" -> baseHost(HostStep.IN_GAME).copy(guestName = TogetherCopy.otherPhone, guestPhase = GuestPhase.PLAYING)
        "ended" -> baseHost(HostStep.ENDED)
        else -> baseHost(HostStep.CHECKING)
    }

    fun guest(state: String): GuestView = when (state) {
        "waiting-offer" -> baseGuest(GuestStep.WAITING_OFFER)
        "guest-checking" -> baseGuest(GuestStep.CHECKING)
        "action-needed" -> baseGuest(GuestStep.ACTION_NEEDED).copy(phase = GuestPhase.NEEDS_GAME, problem = Problem(TogetherCopy.actionHeadline(GuestPhase.NEEDS_GAME), TogetherAction.DOWNLOAD))
        "downloading" -> baseGuest(GuestStep.DOWNLOADING).copy(progress = 40)
        "core-sync" -> baseGuest(GuestStep.CORE_SYNC).copy(progress = 40)
        "installing-core" -> baseGuest(GuestStep.INSTALLING_CORE)
        "picking-game" -> baseGuest(GuestStep.PICKING_GAME).copy(choices = listOf(1L, 2L, 3L).map { com.gamebex.client.together.GameChoice(it, TogetherCopy.joining, "gb") })
        "guest-ready" -> baseGuest(GuestStep.READY)
        "guest-launching" -> baseGuest(GuestStep.LAUNCHING).copy(countdown = 3)
        "guest-in-game" -> baseGuest(GuestStep.IN_GAME)
        "guest-ended" -> baseGuest(GuestStep.ENDED).copy(message = TogetherCopy.ended)
        else -> baseGuest(GuestStep.CONNECTING)
    }

    private fun baseHost(step: HostStep) = HostView(step, 1L, TogetherCopy.joining, "snes", TogetherMode.RA_NETPLAY, TogetherCopy.otherPhone, "10.42.1.20", 55440, instructions = if (step == HostStep.LAUNCHING || step == HostStep.IN_GAME) TogetherCopy.hostInstructions(TogetherMode.RA_NETPLAY) else emptyList())
    private fun baseGuest(step: GuestStep) = GuestView(step, TogetherCopy.otherPhone, TogetherCopy.joining, "snes", TogetherMode.RA_NETPLAY, instructions = if (step == GuestStep.LAUNCHING || step == GuestStep.IN_GAME) TogetherCopy.guestInstructions(TogetherMode.RA_NETPLAY, TogetherCopy.otherPhone, "Y") else emptyList())
}
