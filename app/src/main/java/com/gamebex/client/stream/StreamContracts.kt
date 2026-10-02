package com.gamebex.client.stream

import com.gamebex.client.data.Emu
import com.gamebex.client.data.local.GameEntity
import java.io.File

/**
 * The host app's handle on the shell-uid stream process (Shizuku in the app,
 * fakes in tests). Every call is safe off the main thread and never throws:
 * failures come back as values.
 */
interface StreamHost {
    /** What this phone can do; binds the shell service when needed. */
    suspend fun probe(): StreamProbe

    /** Starts capture, encoding, the media and input listeners, and player 2's pad. */
    suspend fun start(config: StreamConfig): StreamStarted

    /**
     * After the game is in front: capture only its app (the host picks it in Android's share prompt),
     * falling back to the whole display when that is unavailable or declined.
     */
    suspend fun attachCapture() {}

    /** Null when the shell service is gone. */
    suspend fun status(): StreamStatus?

    /** Stops everything [start] began and removes player 2's pad. Idempotent. */
    suspend fun stop()
}

/** Default for builds and tests without a shell: every stream start fails. */
object NoStreamHost : StreamHost {
    override suspend fun probe(): StreamProbe = StreamProbe()
    override suspend fun start(config: StreamConfig): StreamStarted =
        StreamStarted(ok = false, error = StreamError.SHELL_UNAVAILABLE)
    override suspend fun status(): StreamStatus? = null
    override suspend fun stop() = Unit
}

/** How player 2 gets bound in the host's emulator. */
interface StreamPlayer2 {
    /** Prepares [emu]'s player-2 binding for [game] before the host launch. Never throws. */
    suspend fun prepare(game: GameEntity, emu: Emu): P2Prep

    /** Puts back everything [prepare] changed. Idempotent; also runs after a crash. */
    suspend fun restore(): Boolean
}

/** Default for builds and tests without player-2 binding: every emulator gets a plain refusal. */
object NoStreamPlayer2 : StreamPlayer2 {
    override suspend fun prepare(game: GameEntity, emu: Emu): P2Prep = P2Prep.Failed(com.gamebex.client.together.TogetherCopy.streamUnavailable)
    override suspend fun restore(): Boolean = true
}

sealed interface P2Prep {
    /** RetroArch: launch with this session config (player 2 reserved for the pad). */
    data class RetroArchConfig(val cfg: File) : P2Prep

    /** The emulator's own config files were edited; launch as usual. */
    data object Written : P2Prep

    /** GameBEX can't write this emulator's bindings; show these steps (NetherSX2). */
    data class Guide(val lines: List<String>) : P2Prep

    data class Failed(val text: String) : P2Prep
}
