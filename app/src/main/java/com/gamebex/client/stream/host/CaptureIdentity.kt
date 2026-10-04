package com.gamebex.client.stream.host

import kotlinx.coroutines.CancellationException

internal object CaptureIdentity {
    fun ownsProjection(current: Any?, originating: Any): Boolean = current === originating

    fun stopProjection(current: Any?, originating: Any, stop: () -> Unit): Boolean {
        if (!ownsProjection(current, originating)) return false
        stop()
        return true
    }

    fun ownsSession(currentEpoch: Long, epoch: Long, currentService: Any?, service: Any): Boolean =
        currentEpoch == epoch && currentService === service

    fun supported(sdk: Int): Boolean = sdk >= 33

    fun <T> consentIntent(sdk: Int, userChoice: () -> T, wholeScreen: () -> T): T =
        if (sdk >= 34) userChoice() else wholeScreen()

    fun <D : Any> surfaceChange(
        current: () -> Boolean,
        display: D?,
        present: Boolean,
        width: Int,
        height: Int,
        resize: (D, Int, Int) -> Unit,
        retarget: (D) -> Unit,
        storeSurface: () -> Unit,
        stop: () -> Unit,
        previousSurface: Any? = null,
        nextSurface: Any? = null,
        previousWidth: Int = -1,
        previousHeight: Int = -1,
    ): Boolean {
        if (!current()) return false
        if (present && (width <= 0 || height <= 0)) {
            stop()
            return false
        }
        if (previousSurface === nextSurface && previousWidth == width && previousHeight == height) return true
        return try {
            if (display != null) {
                if (present) resize(display, width, height)
                retarget(display)
            }
            storeSurface()
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            stop()
            false
        }
    }

    suspend fun attach(
        capture: suspend () -> Boolean,
        current: () -> Boolean,
        stop: () -> Unit,
    ) {
        val shared = try {
            capture()
        } catch (cancelled: CancellationException) {
            if (current()) stop()
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (!shared && current()) stop()
    }
}
