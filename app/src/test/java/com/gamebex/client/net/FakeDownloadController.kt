package com.gamebex.client.net

import com.gamebex.source.DownloadFile
import com.gamebex.source.RomEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakeDownloadController : DownloadController {
    private val _jobs = MutableStateFlow<Map<Long, DownloadEngine.DlSnapshot>>(emptyMap())
    override val jobs: StateFlow<Map<Long, DownloadEngine.DlSnapshot>> = _jobs
    val calls = mutableListOf<String>()
    var nextId = 1L
    var enqueueError: Exception? = null

    fun emit(snap: DownloadEngine.DlSnapshot) {
        _jobs.value = _jobs.value + (snap.id to snap)
    }

    override suspend fun enqueueSource(
        entry: RomEntry,
        file: DownloadFile,
        description: String?,
        targetFileName: String,
    ): Long {
        calls += "enqueue:${entry.key}:${file.variantKey}:$targetFileName"
        enqueueError?.let { throw it }
        return nextId++
    }

    override fun pause(id: Long) {
        calls += "pause:$id"
    }

    override fun resume(id: Long) {
        calls += "resume:$id"
    }

    override fun cancel(id: Long) {
        calls += "cancel:$id"
    }

    override fun delete(id: Long) {
        calls += "delete:$id"
    }
}
