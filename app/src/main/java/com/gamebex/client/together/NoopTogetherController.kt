package com.gamebex.client.together

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object NoopTogetherController : TogetherController {
    override val nearby: StateFlow<List<NearbyHost>> = MutableStateFlow<List<NearbyHost>>(emptyList()).asStateFlow()
    override val invite: StateFlow<NearbyHost?> = MutableStateFlow<NearbyHost?>(null).asStateFlow()
    override val host: StateFlow<HostView?> = MutableStateFlow<HostView?>(null).asStateFlow()
    override val guest: StateFlow<GuestView?> = MutableStateFlow<GuestView?>(null).asStateFlow()
    override val myAddress: StateFlow<String?> = MutableStateFlow<String?>(null).asStateFlow()
    override val myName: StateFlow<String> = MutableStateFlow("").asStateFlow()
    override val customName: StateFlow<String?> = MutableStateFlow<String?>(null).asStateFlow()
    override fun rename(name: String?) {}
    override fun startBrowsing() {}
    override fun stopBrowsing() {}
    override fun dismissInvite() {}
    override fun hostGame(gameId: Long) {}
    override fun hostStart() {}
    override fun hostRemoveGuest() {}
    override fun hostPlayAgain() {}
    override fun join(target: NearbyHost) {}
    override fun joinAddress(text: String): String? = TogetherCopy.notSupported
    override fun joinLink(uri: String): String? = TogetherCopy.notSupported
    override fun act(action: TogetherAction) {}
    override fun pick(gameId: Long) {}
    override fun end() {}
    override fun onForeground() {}
}
