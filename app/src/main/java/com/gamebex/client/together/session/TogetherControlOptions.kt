package com.gamebex.client.together.session

import com.gamebex.client.stream.StreamDebug

internal data class TogetherControlOptions(
    val controlResume: Boolean = false,
    val linkCutEveryS: Int = 0,
) {
    companion object {
        fun read() = TogetherControlOptions(
            controlResume = StreamDebug.flag("control_resume", default = false),
            linkCutEveryS = StreamDebug.int("link_cut_every_s", 0, Int.MIN_VALUE..Int.MAX_VALUE).coerceIn(0, 120),
        )
    }
}
