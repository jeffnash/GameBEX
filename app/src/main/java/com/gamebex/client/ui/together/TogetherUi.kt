package com.gamebex.client.ui.together

import kotlinx.coroutines.flow.MutableStateFlow

/** Hand-offs from MainActivity to the navigation layer. */
object TogetherUi {
    val pendingLink = MutableStateFlow<String?>(null)
    val pendingOpen = MutableStateFlow(false)
    val hubMessage = MutableStateFlow<String?>(null)
}
