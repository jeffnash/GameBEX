package com.gamebex.client.together.session

import com.gamebex.client.data.Emu
import com.gamebex.client.data.local.GameEntity
import com.gamebex.client.launch.EmuRouter
import com.gamebex.client.stream.P2Prep
import com.gamebex.client.stream.StreamConfig
import com.gamebex.client.stream.StreamHost
import com.gamebex.client.stream.StreamPlayer2
import com.gamebex.client.stream.StreamStarted
import com.gamebex.client.stream.fakes.FakeStreamHost
import com.gamebex.client.stream.fakes.FakeStreamPlayer2
import com.gamebex.client.stream.proto.StreamWire
import com.gamebex.client.together.GuestPhase
import com.gamebex.client.together.HostStep
import com.gamebex.client.together.LinkMsg
import com.gamebex.client.together.OfferedGame
import com.gamebex.client.together.Role
import com.gamebex.client.together.TOGETHER_PROTO
import com.gamebex.client.together.TogetherEmulators
import com.gamebex.client.together.TogetherLaunch
import com.gamebex.client.together.TogetherMode
import com.gamebex.client.together.TogetherTiming
import com.gamebex.client.together.session.fakes.FakeDiscovery
import com.gamebex.client.together.session.fakes.FakeEmulators
import com.gamebex.client.together.session.fakes.FakeLibrary
import com.gamebex.client.together.session.fakes.FakeLinkTransport
import com.gamebex.client.together.session.fakes.FakePlatform
import com.gamebex.client.together.session.fakes.LinkPair
import com.gamebex.client.together.session.fakes.engineTestScope
import java.io.File
import java.nio.file.Files
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The host shares its RetroArch overlay right after StreamReady. */
class HostEngineOverlayTest {
    private val n64 = GameEntity(id = 1, title = "Mario Kart 64", folder = "n64", volumeId = "v", relPath = "mk64.z64", fileName = "mk64.z64", sizeBytes = 1, mtimeMs = 1, ext = "z64")
    private val n64Offer = OfferedGame(title = "Mario Kart 64", folder = "n64", fileName = "mk64.z64", fileSize = 1)

    private class Harness(
        val engine: HostEngine,
        val transport: FakeLinkTransport,
        val discovery: FakeDiscovery,
        val emulators: RecordingEmulators,
        val stream: FakeStreamHost,
    )

    private class RecordingEmulators(val inner: FakeEmulators = FakeEmulators()) : TogetherEmulators by inner {
        var onLaunch: ((TogetherLaunch) -> Unit)? = null

        override suspend fun launch(req: TogetherLaunch): EmuRouter.Outcome {
            onLaunch?.invoke(req)
            return inner.launch(req)
        }
    }

    private fun harness(
        scope: TestScope,
        overlay26b: Boolean = false,
        overlayDir: File? = null,
    ): Harness {
        val engineScope = scope.engineTestScope()
        val stream = FakeStreamHost()
        val recordingStream = object : StreamHost by stream {
            override suspend fun start(config: StreamConfig): StreamStarted = stream.start(config)
        }
        val player2 = FakeStreamPlayer2(P2Prep.RetroArchConfig(File("/sdcard/GameBEX/ra/retroarch-stream.cfg")))
        val recordingPlayer2 = object : StreamPlayer2 by player2 {}
        val transport = FakeLinkTransport()
        val discovery = FakeDiscovery()
        val emulators = RecordingEmulators()
        val engine = HostEngine(
            transport,
            emulators,
            FakeLibrary(gameValue = n64, offerValue = n64Offer),
            discovery,
            FakePlatform(),
            TogetherTiming(helloMs = 50, byeMs = 50),
            engineScope,
            engineScope.coroutineContext[ContinuationInterceptor] as CoroutineDispatcher,
            recordingStream,
            recordingPlayer2,
            com.gamebex.client.together.TogetherSupport::modesFor,
            { null },
            overlay26bEnabled = { overlay26b },
            overlayConfigDir = { overlayDir },
        )
        return Harness(engine, transport, discovery, emulators, stream)
    }

    private suspend fun Harness.startGame(scope: TestScope): LinkPair {
        engine.hostGame(1)
        scope.advanceUntilIdle()
        val pair = transport.pair()
        pair.guest.send(LinkMsg.Hello(TOGETHER_PROTO, Role.GUEST, "guest-device", "Sam", "1.0", streamProto = StreamWire.VERSION))
        scope.advanceUntilIdle()
        pair.guest.send(LinkMsg.GuestState(discovery.advertisements.last().sessionId, GuestPhase.READY))
        scope.advanceUntilIdle()
        engine.start()
        scope.runCurrent()
        scope.advanceTimeBy(100)
        scope.runCurrent()
        return pair
    }

    private fun overlayTree(): File {
        val root = Files.createTempDirectory("gbx-host-overlay").toFile()
        root.resolve("retroarch.cfg").writeText("input_overlay = \"overlays/pad.cfg\"\n")
        val overlays = root.resolve("overlays").apply { mkdirs() }
        overlays.resolve("pad.cfg").writeText(PAD_CFG)
        overlays.resolve("img").apply { mkdirs() }.resolve("a.png").writeBytes(byteArrayOf(1, 2, 3, 4))
        return root
    }

    @Test
    fun flagOnSharesTheHostsOverlayAfterStreamReady() = runTest {
        val root = overlayTree()
        try {
            val h = harness(this, overlay26b = true, overlayDir = root)
            h.emulators.inner.launchOutcomes = ArrayDeque(listOf(EmuRouter.Outcome.Launched("RetroArch", Emu.RETROARCH.pkg)))
            val pair = h.startGame(this)
            assertEquals(HostStep.IN_GAME, h.engine.view.value?.step)
            val sent = pair.host.sent
            val launchAt = sent.indexOfFirst { it is LinkMsg.Launch }
            val readyAt = sent.indexOfFirst { it is LinkMsg.StreamReady }
            val overlayAt = sent.indexOfFirst { it is LinkMsg.StreamOverlay }
            assertTrue(launchAt in 0 until readyAt)
            assertTrue(readyAt in 0 until overlayAt)
            val overlay = sent[overlayAt] as LinkMsg.StreamOverlay
            val ready = sent[readyAt] as LinkMsg.StreamReady
            assertEquals(ready.sessionId, overlay.sessionId)
            assertEquals(1, overlay.round)
            assertEquals("landscape", overlay.name)
            assertTrue(overlay.cfg.contains("overlay0_desc1"))
            assertEquals(listOf("img/a.png"), overlay.images.map { it.name })
            assertFalse(overlay.truncated)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun flagOffSendsNoOverlay() = runTest {
        val root = overlayTree()
        try {
            val h = harness(this, overlay26b = false, overlayDir = root)
            h.emulators.inner.launchOutcomes = ArrayDeque(listOf(EmuRouter.Outcome.Launched("RetroArch", Emu.RETROARCH.pkg)))
            val pair = h.startGame(this)
            assertEquals(HostStep.IN_GAME, h.engine.view.value?.step)
            assertTrue(pair.host.sent.filterIsInstance<LinkMsg.StreamOverlay>().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun otherEmulatorsSendNoOverlay() = runTest {
        val root = overlayTree()
        try {
            val h = harness(this, overlay26b = true, overlayDir = root)
            h.emulators.inner.launchOutcomes = ArrayDeque(listOf(EmuRouter.Outcome.Launched("Dolphin", Emu.DOLPHIN.pkg)))
            val pair = h.startGame(this)
            assertEquals(HostStep.IN_GAME, h.engine.view.value?.step)
            val ready = pair.host.sent.filterIsInstance<LinkMsg.StreamReady>().single()
            assertEquals("dolphin", ready.backend)
            assertTrue(pair.host.sent.filterIsInstance<LinkMsg.StreamOverlay>().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun missingOverlayFilesSendNothingAndStillPlay() = runTest {
        val root = Files.createTempDirectory("gbx-host-overlay-empty").toFile()
        try {
            val h = harness(this, overlay26b = true, overlayDir = root)
            h.emulators.inner.launchOutcomes = ArrayDeque(listOf(EmuRouter.Outcome.Launched("RetroArch", Emu.RETROARCH.pkg)))
            val pair = h.startGame(this)
            assertEquals(HostStep.IN_GAME, h.engine.view.value?.step)
            assertTrue(pair.host.sent.filterIsInstance<LinkMsg.StreamOverlay>().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    private companion object {
        const val PAD_CFG: String = """overlays = 1
overlay0_name = "landscape"
overlay0_normalized = "true"
overlay0_descs = 2
overlay0_desc0 = "a,0.9,0.8,radial,0.05,0.07"
overlay0_desc0_overlay = img/a.png
overlay0_desc1 = "start,0.5,0.9,rect,0.04,0.05"
"""
    }
}
