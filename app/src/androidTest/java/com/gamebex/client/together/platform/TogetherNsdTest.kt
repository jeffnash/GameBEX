package com.gamebex.client.together.platform

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gamebex.client.together.HostAd
import com.gamebex.client.together.TogetherMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TogetherNsdTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun advertiseAndBrowse_preservesTxtFields() {
        runBlocking {
            val service = TogetherNsd(app, "self-device", filterSelf = false)
            val advertisement = service.advertise(
                HostAd("peer-device", "abcdef01", "Sam", "Kirby & the Amazing Mirror", "gbc", TogetherMode.GB_LINK, 55440),
            )
            service.startBrowsing()
            try {
                val host = withTimeout(15_000) { service.found.first { it.isNotEmpty() }.first() }
                assertEquals("peer-device", host.deviceId)
                assertEquals("abcdef01", host.sessionId)
                assertEquals("Sam", host.name)
                assertEquals("Kirby & the Amazing Mirror", host.gameTitle)
                assertEquals("gbc", host.folder)
                assertEquals(TogetherMode.GB_LINK, host.mode)
                assertEquals(55440, host.port)
                assertTrue(host.address.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+")))
            } finally {
                service.stopBrowsing()
                assertTrue(service.found.value.isEmpty())
                advertisement.stop()
            }
        }
    }

    @Test
    fun stopThenStartWithin100ms_canBrowseAgain() {
        runBlocking {
            val service = TogetherNsd(app, "self-device", filterSelf = false)
            val advertisement = service.advertise(
                HostAd("peer-device", "session02", "Sam", "Tetris", "gb", TogetherMode.GB_LINK, 55440),
            )
            try {
                service.startBrowsing()
                withTimeout(15_000) { service.found.first { it.isNotEmpty() } }
                service.stopBrowsing()
                service.startBrowsing()
                withTimeout(15_000) { service.found.first { it.isNotEmpty() } }
                Log.i("WP4_EVIDENCE", "NSD browse/stop/start verified")
            } finally {
                service.stopBrowsing()
                advertisement.stop()
            }
        }
    }

    @Test
    fun stop_disappearsWithin15s() {
        runBlocking {
            val service = TogetherNsd(app, "self-device", filterSelf = false)
            val advertisement = service.advertise(
                HostAd("peer-device", "stopgone1", "Sam", "Tetris", "gb", TogetherMode.GB_LINK, 55440),
            )
            service.startBrowsing()
            try {
                withTimeout(15_000) { service.found.first { it.isNotEmpty() } }
                advertisement.stop()
                withTimeout(15_000) { service.found.first { it.isEmpty() } }
            } finally {
                advertisement.stop()
                service.stopBrowsing()
            }
        }
    }
}

