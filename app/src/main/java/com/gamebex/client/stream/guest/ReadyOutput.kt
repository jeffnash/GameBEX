package com.gamebex.client.stream.guest

import java.util.ArrayDeque

internal data class ReadyOutput<C>(
    val codec: C,
    val generation: Long,
    val index: Int,
    val pts: Long,
    val size: Int,
    val flags: Int,
    val callbackNs: Long,
) {
    val picture: Boolean get() = size > 0 && (flags and 2) == 0
}

internal class ReadyOutputs<C>(
    private val latest: Boolean,
    private val post: (() -> Unit) -> Unit,
    private val isCurrent: (ReadyOutput<C>) -> Boolean,
    private val finish: (ReadyOutput<C>, Boolean) -> Unit,
    private val capacity: Int = 120,
) {
    private val outputs = ArrayDeque<ReadyOutput<C>>()
    private var drainPosted = false
    private var ticket = 0L

    init {
        require(capacity > 0)
    }

    fun offer(output: ReadyOutput<C>) {
        if (!latest) {
            finish(output, output.picture && isCurrent(output))
            return
        }
        if (outputs.size >= capacity) finish(outputs.removeFirst(), false)
        outputs.addLast(output)
        if (drainPosted) return
        drainPosted = true
        val postedTicket = ticket
        post {
            if (ticket == postedTicket) {
                drainPosted = false
                val batch = outputs.toList()
                outputs.clear()
                val visible = batch.indexOfLast { it.picture && isCurrent(it) }
                batch.forEachIndexed { index, item -> finish(item, index == visible) }
            }
        }
    }

    fun clear() {
        ticket += 1
        drainPosted = false
        while (outputs.isNotEmpty()) finish(outputs.removeFirst(), false)
    }
}
