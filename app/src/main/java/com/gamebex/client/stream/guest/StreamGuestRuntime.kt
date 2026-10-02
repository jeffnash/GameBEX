package com.gamebex.client.stream.guest

import android.content.Context
import android.content.Intent
import com.gamebex.client.stream.StreamEndpoint
import com.gamebex.client.stream.StreamJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The guest's single process-wide viewing session. The viewer Activity
 * attaches to it, so reopening the viewer while the host still streams
 * reconnects instead of starting over.
 */
object StreamGuestRuntime {
    const val EXTRA_ENDPOINT: String = "endpoint"
    const val EXTRA_LOOPBACK: String = "loopback"

    private val lock = Any()
    private var session: StreamViewerSession? = null
    private var endpoint: StreamEndpoint? = null
    private val _closeRequests = MutableStateFlow(0)

    /** Bumps when the viewer must close (the Play together session ended). */
    val closeRequests: StateFlow<Int> = _closeRequests.asStateFlow()

    private val _viewerOpen = MutableStateFlow(false)
    val viewerOpen: StateFlow<Boolean> = _viewerOpen.asStateFlow()

    fun intent(context: Context, endpoint: StreamEndpoint, loopback: Boolean = false): Intent =
        Intent(context, StreamViewerActivity::class.java)
            .putExtra(EXTRA_ENDPOINT, StreamJson.json.encodeToString(StreamEndpoint.serializer(), endpoint))
            .putExtra(EXTRA_LOOPBACK, loopback)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun open(context: Context, endpoint: StreamEndpoint) {
        context.startActivity(intent(context, endpoint))
    }

    /** The live session for [ep] unless it has ended; otherwise leaves it and starts a new one. */
    fun attach(ep: StreamEndpoint, loopback: Boolean): StreamViewerSession = synchronized(lock) {
        val current = session
        if (current != null && endpoint == ep && current.ui.value.status != ViewerStatus.ENDED) return current
        current?.leave()
        val next = StreamViewerSession(ep, loopback) { detach() }
        session = next
        endpoint = ep
        _viewerOpen.value = true
        next.start()
        next
    }

    fun detach() {
        synchronized(lock) {
            session = null
            endpoint = null
        }
        _viewerOpen.value = false
    }

    /** Called when the Play together session ends (End or Leave). */
    fun close() {
        val s = synchronized(lock) { session }
        s?.leave()
        detach()
        _closeRequests.value += 1
    }
}
