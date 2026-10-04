package com.gamebex.client.stream.shell

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RSoundAudioSourceTest {
    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    @Test fun readinessRequiresListenerAndValidatedPlaybackWithoutStartingLoopback() {
        val fallback = TestFallback()
        val player = RSoundTestPlayer()
        val source = source(fallback, player)
        try {
            assertTrue(source.start(AudioSink {}))
            assertTrue(source.rsoundReady)
            assertTrue(source.boundPort > 0)
            assertEquals("rsound-waiting", source.audioMode)
            assertEquals(0, fallback.starts.get())
            assertEquals(0, player.started.get())
        } finally {
            source.stop()
        }
        assertFalse(source.rsoundReady)
        assertTrue(player.closed)
    }

    @Test fun socketConfigurationLeavesReceiveWindowAtOsDefault() {
        val source = source(null, RSoundTestPlayer())
        val configure = RSoundAudioSource::class.java.getDeclaredMethod("configure", Socket::class.java)
        configure.isAccessible = true
        val socket = object : Socket() {
            override fun setReceiveBufferSize(size: Int) {
                throw AssertionError("RSound must leave the OS receive window unchanged")
            }
        }
        socket.use {
            val defaultReceiveBufferSize = it.receiveBufferSize
            configure.invoke(source, it)
            assertEquals(defaultReceiveBufferSize, it.receiveBufferSize)
            assertTrue(it.tcpNoDelay)
        }
    }

    @Test fun diagnosticAggregatesKeepOriginalReadArrivalDespiteLoggerDelay() {
        val logs = CopyOnWriteArrayList<String>()
        val firstRead = CountDownLatch(1)
        val releaseLogger = CountDownLatch(1)
        val chunks = CopyOnWriteArrayList<PcmChunk>()
        val player = RSoundTestPlayer()
        val source = RSoundAudioSource(
            null, RSoundPlaybackFactory { player }, port = 0,
            logger = {
                logs.add(it)
                if (it.startsWith("RSound first PCM read")) {
                    firstRead.countDown()
                    releaseLogger.await(1, TimeUnit.SECONDS)
                }
            },
        )
        try {
            assertTrue(source.start(AudioSink { chunks.add(it) }))
            client(source).use { client ->
                val connected = logs.single { it.startsWith("RSound connected") }
                assertTrue(connected.contains("dataReceiveBufferSize="))
                assertTrue(connected.contains("dataSendBufferSize="))
                assertTrue(connected.contains("dataTcpNoDelay=true"))
                client.send(ByteArray(960) { 1 })
                assertTrue(firstRead.await(1, TimeUnit.SECONDS))
                val loggerBlockedAtNs = System.nanoTime()
                Thread.sleep(30)
                releaseLogger.countDown()
                await { logs.any { it.startsWith("RSound local") } }
                val summary = logs.first { it.startsWith("RSound local") }
                fun value(label: String): Long = Regex("(?:^| )$label=(\\d+)")
                    .find(summary)!!.groupValues[1].toLong()
                assertTrue(value("readCount") >= 1)
                assertEquals(1L, value("deliverCount"))
                assertEquals(1L, value("writeCount"))
                for (label in listOf("read", "deliver", "write")) {
                    assertTrue(value("${label}TotalUs") >= value("${label}MaxUs"))
                    assertTrue(value("${label}MaxUs") >= value("${label}LastUs"))
                }
                await { chunks.size == 1 }
                assertTrue(chunks.single().captureNs < loggerBlockedAtNs)
                val arrivalSummary = logs.first { it.startsWith("RSound bridge-arrival") }
                for (label in listOf("origin=bridge-arrival", "readGapP50Us=", "readGapP95Us=", "readGapMaxUs=", "readGapsOver30Ms=", "receivedBytes=", "actualByteCadenceBps=")) {
                    assertTrue(arrivalSummary.contains(label))
                }
                assertTrue(value("arrivalToDeliverLastUs") >= 30_000)
            }
        } finally {
            releaseLogger.countDown()
            source.stop()
        }
    }

    @Test fun transportChunkSizesRequireBoundedWholeFrames() {
        for (chunkBytes in listOf(4, 512, 960, 1024)) {
            source(null, RSoundTestPlayer(), transportChunkBytes = chunkBytes).stop()
        }
        for (chunkBytes in listOf(-4, 0, 1, 3, 513, 1023, 1025, 1028)) {
            assertThrows(IllegalArgumentException::class.java) {
                source(null, RSoundTestPlayer(), transportChunkBytes = chunkBytes)
            }
        }
    }

    @Test fun bindCollisionFallsBackImmediatelyAndNeverCreatesPlayback() {
        ServerSocket(0, 1, loopback).use { occupied ->
            val fallback = TestFallback()
            val factories = AtomicInteger()
            val source = RSoundAudioSource(
                fallback, RSoundPlaybackFactory { factories.incrementAndGet(); RSoundTestPlayer() },
                port = occupied.localPort, logger = {},
            )
            try {
                assertTrue(source.start(AudioSink {}))
                assertFalse(source.rsoundReady)
                assertEquals("loopback", source.audioMode)
                assertNotNull(source.fallbackReason)
                assertEquals("setup", source.failureKind)
                assertEquals(source.failureKind, source.audioFailureKind)
                assertEquals(1, fallback.starts.get())
                assertEquals(0, factories.get())
            } finally {
                source.stop()
            }
        }
    }

    @Test fun unreadyOversizedAndThrowingPlaybackFallBackAndReleaseListener() {
        for (factory in listOf(
            RSoundPlaybackFactory { RSoundTestPlayer(initialized = false) },
            RSoundPlaybackFactory { RSoundTestPlayer(bufferBytes = 100_000) },
            RSoundPlaybackFactory { throw IOException("track unavailable") },
        )) {
            val fallback = TestFallback()
            val source = RSoundAudioSource(fallback, factory, port = 0, logger = {})
            try {
                assertTrue(source.start(AudioSink {}))
                assertFalse(source.rsoundReady)
                assertEquals(-1, source.boundPort)
                assertEquals(1, fallback.starts.get())
                assertTrue(source.fallbackReason!!.contains("startup failed"))
                assertEquals("setup", source.failureKind)
            } finally {
                source.stop()
            }
        }
    }

    @Test fun falseStartWithoutFallbackReleasesAllResourcesAndRejectsNonLoopbackBind() {
        val player = RSoundTestPlayer(initialized = false)
        val source = source(null, player)
        assertFalse(source.start(AudioSink {}))
        assertTrue(player.closed)
        assertFalse(source.rsoundReady)
        assertEquals(-1, source.boundPort)
        source.stop()
        val outside = RSoundAudioSource(
            null, RSoundPlaybackFactory { throw AssertionError("must not create playback") },
            port = 0, address = InetAddress.getByAddress(byteArrayOf(0, 0, 0, 0)), logger = {},
        )
        assertFalse(outside.start(AudioSink {}))
        assertEquals(-1, outside.boundPort)
        outside.stop()
    }

    @Test fun launchDeadlineRetainsListenerAndLateClientStopsFallbackBeforePlayback() {
        val fallback = TestFallback()
        val events = CopyOnWriteArrayList<String>()
        val chunks = CopyOnWriteArrayList<PcmChunk>()
        val player = RSoundTestPlayer(onStart = {
            assertFalse(fallback.running)
            events.add("playback-start")
        })
        fallback.onStop = { events.add("fallback-stop") }
        val source = source(fallback, player, noConnectMs = 80)
        try {
            assertTrue(source.start(AudioSink { chunks.add(it) }))
            Thread.sleep(120)
            assertEquals(0, fallback.starts.get())
            source.gameLaunched()
            source.gameLaunched()
            await { fallback.starts.get() == 1 && fallback.running }
            assertTrue(source.rsoundReady)
            assertEquals("loopback", source.audioMode)
            assertTrue(source.fallbackReason!!.contains("no connection"))
            fallback.emit(1)
            await { chunks.size == 1 }
            client(source).use { late ->
                assertEquals(listOf("fallback-stop", "playback-start"), events.toList())
                assertEquals("rsound", source.audioMode)
                assertEquals(null, source.fallbackReason)
                late.send(ByteArray(960) { 2 })
                await { chunks.size == 2 && player.writtenBytes.get() == 960 }
                assertTrue(chunks[1].seq > chunks[0].seq)
                fallback.emit(3)
                assertEquals(2, chunks.size)
            }
        } finally {
            source.stop()
        }
        val stoppedCount = chunks.size
        fallback.emit(9)
        assertEquals(stoppedCount, chunks.size)
    }

    @Test fun fragmentedPcmHas240FrameChunksAndReadArrivalTimestamp() {
        val chunks = CopyOnWriteArrayList<PcmChunk>()
        val firstRead = CountDownLatch(1)
        val player = RSoundTestPlayer()
        val source = RSoundAudioSource(
            null, RSoundPlaybackFactory { player }, port = 0,
            logger = { if (it.startsWith("RSound first PCM read")) firstRead.countDown() },
        )
        try {
            assertTrue(source.start(AudioSink { chunks.add(it) }))
            client(source).use { client ->
                val firstNs = System.nanoTime()
                client.send(byteArrayOf(11, 12, 13))
                assertTrue(firstRead.await(1, TimeUnit.SECONDS))
                val restNs = System.nanoTime()
                client.send(ByteArray(1917) { 14 })
                await { chunks.size == 2 && player.writtenBytes.get() == 1920 }
                assertEquals(listOf(240, 240), chunks.map { it.frames })
                assertEquals(listOf(960, 960), chunks.map { it.data.size })
                assertArrayEquals(byteArrayOf(11, 12, 13), chunks[0].data.copyOfRange(0, 3))
                assertTrue(chunks[0].captureNs >= firstNs)
                assertTrue(chunks[0].captureNs < restNs)
                assertTrue(chunks[1].captureNs >= restNs)
                assertEquals(chunks[0].seq + 1, chunks[1].seq)
            }
        } finally {
            source.stop()
        }
    }

    @Test fun infoUsesActualPlayedFramesNotReceivedOrWrittenPointerAndCloseCtlKeepsData() {
        val player = RSoundTestPlayer(bufferBytes = 1920)
        val source = source(null, player, useControlProtocol = true, transportChunkBytes = 960)
        try {
            assertTrue(source.start(AudioSink {}))
            client(source, useControlProtocol = true, transportChunkBytes = 960).use { client ->
                client.send(ByteArray(1920) { 1 })
                await { player.writtenBytes.get() == 1920 }
                player.playedFrames = 120
                client.control.getOutputStream().write(RSoundProtocol.controlReply(" IDENTITY RetroArch"))
                val info = RSoundProtocol.controlReply(" INFO 1920")
                for (byte in info) client.control.getOutputStream().write(byte.toInt())
                assertEquals(" INFO 1920 480", readControlBody(client.control))
                player.playedFrames = 0
                client.control.getOutputStream().write(RSoundProtocol.controlReply(" INFO 1920"))
                assertEquals(" INFO 1920 0", readControlBody(client.control))
                client.control.getOutputStream().write(RSoundProtocol.controlReply(" CLOSECTL"))
                assertEquals(" CLOSECTL OK", readControlBody(client.control))
                assertClosed(client.control)
                client.send(ByteArray(960))
                await { player.writtenBytes.get() == 2880 }
                assertEquals("rsound", source.audioMode)
            }
        } finally {
            source.stop()
        }
    }

    @Test fun newValidDataControlPairReplacesOldAndSequenceSurvivesReconnectAndFallback() {
        val fallback = TestFallback()
        val players = CopyOnWriteArrayList<RSoundTestPlayer>()
        val chunks = CopyOnWriteArrayList<PcmChunk>()
        val source = RSoundAudioSource(
            fallback, RSoundPlaybackFactory { RSoundTestPlayer().also { players.add(it) } },
            port = 0, logger = {},
        )
        try {
            assertTrue(source.start(AudioSink { chunks.add(it) }))
            client(source).use { old ->
                old.send(ByteArray(960) { 1 })
                await { chunks.size == 1 && players[0].writtenBytes.get() == 960 }
                client(source).use { replacement ->
                    assertTrue(players[0].closed)
                    assertEquals(2, players.size)
                    assertEquals(0, fallback.starts.get())
                    assertClosed(old.data)
                    replacement.send(ByteArray(960) { 2 })
                    await { chunks.size == 2 }
                }
                await { fallback.running }
                fallback.emit(3)
                await { chunks.size == 3 }
                client(source).use { reconnected ->
                    assertFalse(fallback.running)
                    reconnected.send(ByteArray(960) { 4 })
                    await { chunks.size == 4 }
                }
            }
            assertEquals(listOf(1, 2, 3, 4), chunks.map { it.seq })
        } finally {
            source.stop()
        }
    }

    @Test fun pairAndHeaderWaitsAreBoundedAndServerSurvivesRejectedClients() {
        val source = RSoundAudioSource(
            null, RSoundPlaybackFactory { RSoundTestPlayer() }, port = 0,
            pairWaitMs = 80, headerWaitMs = 80, logger = {},
        )
        try {
            assertTrue(source.start(AudioSink {}))
            connect(source).use { lone ->
                assertClosed(lone)
            }
            connect(source).use { data ->
                connect(source).use { control ->
                    data.getOutputStream().write(byteArrayOf('R'.code.toByte()))
                    assertClosed(data)
                    assertClosed(control)
                }
            }
            connect(source).use { data ->
                connect(source).use { control ->
                    data.getOutputStream().write(ByteArray(44))
                    assertClosed(data)
                    assertClosed(control)
                }
            }
            client(source).use { valid -> valid.send(ByteArray(960)) }
            assertTrue(source.rsoundReady)
        } finally {
            source.stop()
        }
    }

    @Test fun fragmentedControlDeadlineAndOversizedLengthCloseOnlyControl() {
        val player = RSoundTestPlayer()
        val slowControl = RSoundAudioSource(
            null, RSoundPlaybackFactory { player }, port = 0, headerWaitMs = 80, logger = {},
        )
        try {
            assertTrue(slowControl.start(AudioSink {}))
            client(slowControl).use { client ->
                client.control.getOutputStream().write('R'.code)
                assertClosed(client.control)
                client.send(ByteArray(960))
                await { player.writtenBytes.get() == 960 }
                assertEquals("rsound", slowControl.audioMode)
            }
        } finally {
            slowControl.stop()
        }
        val oversized = source(null, RSoundTestPlayer())
        try {
            assertTrue(oversized.start(AudioSink {}))
            client(oversized).use { client ->
                client.control.getOutputStream().write("RSD  257".toByteArray())
                assertClosed(client.control)
            }
        } finally {
            oversized.stop()
        }
    }

    @Test fun blockingLocalWritesNeverPaceIngressOrGuestAndStopUnblocksWithoutLateCallbacks() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val player = RSoundTestPlayer(
            writer = { _, _, count -> entered.countDown(); release.await(); count },
            onClose = { release.countDown() },
        )
        val chunks = CopyOnWriteArrayList<PcmChunk>()
        val source = source(null, player)
        try {
            assertTrue(source.start(AudioSink { chunks.add(it) }))
            client(source).use { client ->
                client.send(ByteArray(960) { 1 })
                assertTrue(entered.await(1, TimeUnit.SECONDS))
                client.send(ByteArray(3 * 960) { 2 })
                await { chunks.size == 4 }
                assertEquals(0, player.writtenBytes.get())
                assertEquals(listOf(1, 2, 3, 4), chunks.map { it.seq })
                assertEquals(listOf(1, 2, 2, 2), chunks.map { it.data[0].toInt() })
                assertEquals("rsound", source.audioMode)
                assertTrue(source.clientConnected)
                val startNs = System.nanoTime()
                source.stop()
                assertTrue(System.nanoTime() - startNs < 1_500_000_000L)
                assertTrue(player.closed)
                assertClosed(client.data)
                Thread.sleep(30)
                assertEquals(4, chunks.size)
            }
        } finally {
            release.countDown()
            source.stop()
        }
    }

    @Test fun localQueueOverflowFailsExplicitlyAfterEightPendingChunksAndListenerAcceptsReconnect() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fallback = TestFallback()
        val logs = CopyOnWriteArrayList<String>()
        val chunks = CopyOnWriteArrayList<PcmChunk>()
        val players = CopyOnWriteArrayList<RSoundTestPlayer>()
        val source = RSoundAudioSource(fallback, RSoundPlaybackFactory {
            val player = if (players.isEmpty()) RSoundTestPlayer(
                writer = { _, _, count -> entered.countDown(); release.await(); count },
                onClose = { release.countDown() },
            ) else RSoundTestPlayer()
            player.also { players.add(it) }
        }, port = 0, logger = { logs.add(it) }, useControlProtocol = true, transportChunkBytes = 960)
        try {
            assertTrue(source.start(AudioSink { chunks.add(it) }))
            client(source, useControlProtocol = true, transportChunkBytes = 960).use { old ->
                old.send(ByteArray(960) { 1 })
                assertTrue(entered.await(1, TimeUnit.SECONDS))
                repeat(8) { index ->
                    old.send(ByteArray(960) { (index + 2).toByte() })
                    await { chunks.size == index + 2 }
                }
                await {
                    old.control.getOutputStream().write(RSoundProtocol.controlReply(" INFO 8640"))
                    assertEquals(" INFO 8640 0", readControlBody(old.control))
                    logs.last { it.startsWith("RSound INFO") }.contains("localQueueChunks=8 ")
                }
                val info = logs.last { it.startsWith("RSound INFO") }
                assertTrue(info.contains("receivedBytes=8640 "))
                assertTrue(info.contains("writtenBytes=0 "))
                assertTrue(info.contains("localQueueChunks=8 "))
                assertTrue(info.contains("localQueuedAudioUs=40000 "))
                assertEquals(0, fallback.starts.get())
                assertEquals(0, players[0].writtenBytes.get())
                old.send(ByteArray(960) { 10 })
                await { fallback.running }
                assertEquals("local-playback", source.failureKind)
                assertTrue(source.fallbackReason!!.contains("local queue overflow: 8 chunks / 40ms"))
                val closed = logs.last { it.startsWith("RSound local queue closed") }
                assertTrue(closed.contains("localPendingChunks=8 "))
                assertTrue(closed.contains("localInFlightBytes=960 "))
                assertTrue(closed.contains("localRejectedBytes=960 "))
                assertTrue(closed.contains("localDiscardedBytes=9600 "))
                assertTrue(closed.contains("localDiscardedFrames=2400 "))
                assertTrue(source.rsoundReady)
                assertTrue(players[0].closed)
                assertClosed(old.data)
                client(source, useControlProtocol = true, transportChunkBytes = 960).use { reconnected ->
                    assertFalse(fallback.running)
                    assertEquals(null, source.failureKind)
                    reconnected.send(ByteArray(960) { 9 })
                    await { players[1].writtenBytes.get() == 960 && chunks.any { it.data[0] == 9.toByte() } }
                    assertEquals("rsound", source.audioMode)
                }
            }
        } finally {
            release.countDown()
            source.stop()
        }
    }

    @Test fun consecutive32MillisecondBurstsPreserveChunkerRemainderWithoutNormalOverflow() {
        val logs = CopyOnWriteArrayList<String>()
        val accepted = ByteArrayOutputStream(3 * 6144)
        val player = RSoundTestPlayer(writer = { bytes, offset, count ->
            Thread.sleep(2)
            synchronized(accepted) { accepted.write(bytes, offset, count) }
            count
        })
        val fallback = TestFallback()
        val source = RSoundAudioSource(fallback, RSoundPlaybackFactory { player }, port = 0,
            logger = { logs.add(it) }, useControlProtocol = true, transportChunkBytes = 960)
        val pcm = ByteArray(3 * 6144) { (it % 127).toByte() }
        try {
            assertTrue(source.start(AudioSink {}))
            client(source, useControlProtocol = true, transportChunkBytes = 960).use { client ->
                repeat(3) { burst ->
                    client.send(pcm.copyOfRange(burst * 6144, (burst + 1) * 6144))
                    val completeBytes = ((burst + 1) * 6144 / 960) * 960
                    await { player.writtenBytes.get() == completeBytes }
                }
                await { player.writtenBytes.get() == 19 * 960 }
                assertEquals("rsound", source.audioMode)
                assertEquals(0, fallback.starts.get())
                assertEquals(null, source.failureKind)
                assertArrayEquals(pcm.copyOfRange(0, 19 * 960), synchronized(accepted) { accepted.toByteArray() })
                client.control.getOutputStream().write(RSoundProtocol.controlReply(" INFO ${pcm.size}"))
                assertEquals(" INFO ${pcm.size} 0", readControlBody(client.control))
                val info = logs.last { it.startsWith("RSound INFO") }
                assertTrue(info.contains("receivedBytes=${pcm.size} "))
                assertTrue(info.contains("writtenBytes=${19 * 960} "))
                assertTrue(info.contains("localQueueCapacityChunks=8 "))
                assertTrue(logs.first { it.startsWith("RSound ready") }.contains("localQueueCapacityMs=40"))
            }
        } catch (failure: Throwable) {
            throw AssertionError("RSound burst failure: mode=${source.audioMode} connected=${source.clientConnected} failureKind=${source.failureKind} fallbackReason=${source.fallbackReason} writtenBytes=${player.writtenBytes.get()} acceptedBytes=${synchronized(accepted) { accepted.size() }} fallbackStarts=${fallback.starts.get()}\n${logs.joinToString("\n")}", failure)
        } finally {
            source.stop()
        }
    }

    @Test fun localPartialWriteFailureReportsAllUnwrittenQueuedFramesAndNoSuccessfulBytesAsLost() {
        val secondWrite = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val logs = CopyOnWriteArrayList<String>()
        val chunks = CopyOnWriteArrayList<PcmChunk>()
        val fallback = TestFallback()
        val player = RSoundTestPlayer(writer = { _, _, _ ->
            if (calls.incrementAndGet() == 1) 480 else {
                secondWrite.countDown()
                release.await()
                -6
            }
        }, onClose = { release.countDown() })
        val source = RSoundAudioSource(fallback, RSoundPlaybackFactory { player }, port = 0,
            logger = { logs.add(it) }, useControlProtocol = true, transportChunkBytes = 960)
        try {
            assertTrue(source.start(AudioSink { chunks.add(it) }))
            client(source, useControlProtocol = true, transportChunkBytes = 960).use { client ->
                client.send(ByteArray(960))
                assertTrue(secondWrite.await(1, TimeUnit.SECONDS))
                client.send(ByteArray(2 * 960))
                await { chunks.size == 3 }
                await {
                    client.control.getOutputStream().write(RSoundProtocol.controlReply(" INFO 2880"))
                    assertEquals(" INFO 2880 0", readControlBody(client.control))
                    logs.last { it.startsWith("RSound INFO") }.contains("localQueueChunks=2 ")
                }
                assertTrue(logs.last { it.startsWith("RSound INFO") }.contains("writtenBytes=480 "))
                release.countDown()
                await { fallback.running }
                assertEquals("local-playback", source.failureKind)
                val loss = logs.single { it.startsWith("RSound local queue closed") }
                assertTrue(loss.contains("localPendingBytes=1920 "))
                assertTrue(loss.contains("localInFlightBytes=480 "))
                assertTrue(loss.contains("localRejectedBytes=0 "))
                assertTrue(loss.contains("localDiscardedBytes=2400 "))
                assertTrue(loss.contains("localDiscardedFrames=600 "))
                assertTrue(loss.contains("writtenBytes=480"))
                assertEquals(2, calls.get())
                assertTrue(source.rsoundReady)
                assertClosed(client.data)
            }
        } finally {
            release.countDown()
            source.stop()
        }
    }

    @Test fun localSpeakerRetainsOwnedBytesInFifoAndInfoCapsAtSuccessfulPartialWrites() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val accepted = CopyOnWriteArrayList<Byte>()
        val chunks = CopyOnWriteArrayList<PcmChunk>()
        val logs = CopyOnWriteArrayList<String>()
        val delegate = RSoundTestPlayer(writer = { bytes, offset, count ->
            val call = calls.incrementAndGet()
            if (call == 2) {
                entered.countDown()
                release.await()
            }
            val written = if (call == 1) 480 else count
            repeat(written) { accepted.add(bytes[offset + it]) }
            written
        }, onClose = { release.countDown() })
        val player = object : RSoundPlayer by delegate {
            override fun stats() = RSoundPlaybackStats(100_000, 100_000, null, null, 0)
        }
        val source = RSoundAudioSource(null, RSoundPlaybackFactory { player }, port = 0,
            logger = { logs.add(it) }, useControlProtocol = true, transportChunkBytes = 960)
        val pcm = List(4) { value -> ByteArray(960) { (value + 1).toByte() } }
        try {
            assertTrue(source.start(AudioSink {
                chunks.add(it)
                it.data.fill(99)
            }))
            client(source, useControlProtocol = true, transportChunkBytes = 960).use { client ->
                client.send(pcm[0])
                assertTrue(entered.await(1, TimeUnit.SECONDS))
                for (bytes in pcm.drop(1)) client.send(bytes)
                await { chunks.size == 4 }
                client.control.getOutputStream().write(RSoundProtocol.controlReply(" INFO 3840"))
                assertEquals(" INFO 3840 480", readControlBody(client.control))
                val info = logs.last { it.startsWith("RSound INFO") }
                assertTrue(info.contains("clientBytes=3840 "))
                assertTrue(info.contains("receivedBytes=3840 "))
                assertTrue(info.contains("writtenBytes=480 "))
                assertTrue(info.contains("playedBytes=480 "))
                release.countDown()
                await { delegate.writtenBytes.get() == 3840 }
                assertArrayEquals(pcm.flatMap { it.toList() }.toByteArray(), accepted.toByteArray())
                client.control.getOutputStream().write(RSoundProtocol.controlReply(" INFO 3840"))
                assertEquals(" INFO 3840 3840", readControlBody(client.control))
                assertEquals(listOf(1, 2, 3, 4), chunks.map { it.seq })
                assertEquals("rsound", source.audioMode)
            }
        } finally {
            release.countDown()
            source.stop()
        }
    }

    @Test fun replacingBlockedLocalWriterClosesOldQueueAndIgnoresRetiredFailure() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val retired = CountDownLatch(1)
        val oldCalls = AtomicInteger()
        val players = CopyOnWriteArrayList<RSoundTestPlayer>()
        val chunks = CopyOnWriteArrayList<PcmChunk>()
        val fallback = TestFallback()
        val source = RSoundAudioSource(fallback, RSoundPlaybackFactory {
            val player = if (players.isEmpty()) RSoundTestPlayer(writer = { _, _, _ ->
                oldCalls.incrementAndGet()
                entered.countDown()
                release.await()
                retired.countDown()
                throw IOException("retired local write")
            }) else RSoundTestPlayer()
            player.also { players.add(it) }
        }, port = 0, logger = {})
        try {
            assertTrue(source.start(AudioSink { chunks.add(it) }))
            client(source).use { old ->
                old.send(ByteArray(960) { 1 })
                assertTrue(entered.await(1, TimeUnit.SECONDS))
                old.send(ByteArray(2 * 960) { 2 })
                await { chunks.size == 3 }
                client(source).use { replacement ->
                    assertTrue(players[0].closed)
                    replacement.send(ByteArray(960) { 9 })
                    await { players[1].writtenBytes.get() == 960 && chunks.size == 4 }
                    release.countDown()
                    assertTrue(retired.await(1, TimeUnit.SECONDS))
                    replacement.send(ByteArray(960) { 10 })
                    await { players[1].writtenBytes.get() == 1920 && chunks.size == 5 }
                    assertEquals(1, oldCalls.get())
                    assertEquals(0, fallback.starts.get())
                    assertEquals(null, source.failureKind)
                    assertEquals("rsound", source.audioMode)
                    assertEquals(listOf(1, 2, 3, 4, 5), chunks.map { it.seq })
                    assertEquals(listOf(1, 2, 2, 9, 10), chunks.map { it.data[0].toInt() })
                }
            }
        } finally {
            release.countDown()
            source.stop()
        }
    }

    @Test fun stopAndRestartBoundLocalWriterJoinAndNeverReplayRetiredQueue() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val retired = CountDownLatch(1)
        val oldCalls = AtomicInteger()
        val oldSends = AtomicInteger()
        val newSends = AtomicInteger()
        val players = CopyOnWriteArrayList<RSoundTestPlayer>()
        val fallback = TestFallback()
        val source = RSoundAudioSource(fallback, RSoundPlaybackFactory {
            val player = if (players.isEmpty()) RSoundTestPlayer(writer = { _, _, count ->
                oldCalls.incrementAndGet()
                entered.countDown()
                while (release.count > 0) {
                    try {
                        release.await()
                    } catch (_: InterruptedException) {
                        Unit
                    }
                }
                retired.countDown()
                count
            }) else RSoundTestPlayer()
            player.also { players.add(it) }
        }, port = 0, logger = {})
        try {
            assertTrue(source.start(AudioSink { oldSends.incrementAndGet() }))
            client(source).use { old ->
                old.send(ByteArray(960))
                assertTrue(entered.await(1, TimeUnit.SECONDS))
                old.send(ByteArray(2 * 960))
                await { oldSends.get() == 3 }
                val stopAtNs = System.nanoTime()
                source.stop()
                assertTrue(System.nanoTime() - stopAtNs < 1_500_000_000L)
                assertTrue(players[0].closed)
                assertClosed(old.data)
                assertTrue(source.start(AudioSink { newSends.incrementAndGet() }))
                client(source).use { restarted ->
                    restarted.send(ByteArray(960))
                    await { newSends.get() == 1 && players[1].writtenBytes.get() == 960 }
                    release.countDown()
                    assertTrue(retired.await(1, TimeUnit.SECONDS))
                    restarted.send(ByteArray(960))
                    await { newSends.get() == 2 && players[1].writtenBytes.get() == 1920 }
                    assertEquals(1, oldCalls.get())
                    assertEquals(3, oldSends.get())
                    assertEquals(0, fallback.starts.get())
                    assertEquals(null, source.failureKind)
                    assertEquals("rsound", source.audioMode)
                }
            }
        } finally {
            release.countDown()
            source.stop()
        }
    }

    @Test fun playbackErrorOrStopControlFallsBackWithoutClosingAdvertisedListener() {
        for (stopControl in listOf(false, true)) {
            val fallback = TestFallback()
            val player = if (stopControl) RSoundTestPlayer() else RSoundTestPlayer(writer = { _, _, _ -> -6 })
            val source = source(fallback, player)
            try {
                assertTrue(source.start(AudioSink {}))
                client(source).use { client ->
                    if (stopControl) {
                        client.control.getOutputStream().write(RSoundProtocol.controlReply(" STOP"))
                    } else client.send(ByteArray(960))
                    await { fallback.running }
                    assertEquals("loopback", source.audioMode)
                    assertTrue(source.rsoundReady)
                    assertTrue(player.closed)
                    assertNotNull(source.fallbackReason)
                    assertEquals(if (stopControl) null else "local-playback", source.failureKind)
                }
            } finally {
                source.stop()
            }
        }
    }

    @Test fun stopUnblocksAcceptPairHeaderAndLaunchTimerAndSamePortRestarts() {
        val fallback = TestFallback()
        val timerSource = source(fallback, RSoundTestPlayer(), noConnectMs = 80)
        assertTrue(timerSource.start(AudioSink {}))
        timerSource.gameLaunched()
        timerSource.stop()
        Thread.sleep(100)
        assertEquals(0, fallback.starts.get())
        for (header in listOf(false, true)) {
            val waiting = source(null, RSoundTestPlayer())
            try {
                assertTrue(waiting.start(AudioSink {}))
                connect(waiting).use { data ->
                    val control = if (header) connect(waiting) else null
                    try {
                        waiting.stop()
                        assertClosed(data)
                        if (control != null) assertClosed(control)
                    } finally {
                        control?.close()
                    }
                }
            } finally {
                waiting.stop()
            }
        }
        val first = source(null, RSoundTestPlayer())
        var port = -1
        try {
            assertTrue(first.start(AudioSink {}))
            port = first.boundPort
            client(first).use { client ->
                first.stop()
                assertClosed(client.data)
            }
        } finally {
            first.stop()
        }
        val restarted = RSoundAudioSource(null, RSoundPlaybackFactory { RSoundTestPlayer() }, port = port, logger = {})
        try {
            assertTrue(restarted.start(AudioSink {}))
            assertTrue(restarted.rsoundReady)
            client(restarted).use { client -> client.send(ByteArray(960)) }
        } finally {
            restarted.stop()
        }
    }

    @Test fun stalledGuestCannotBlockLocalWritesAndDropsOnlyOldestQueuedAudio() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val player = RSoundTestPlayer()
        val chunks = CopyOnWriteArrayList<PcmChunk>()
        val source = source(null, player)
        try {
            assertTrue(source.start(AudioSink {
                chunks.add(it)
                if (it.seq == 1) {
                    entered.countDown()
                    release.await()
                }
            }))
            client(source).use { client ->
                client.send(ByteArray(960) { 1 })
                assertTrue(entered.await(1, TimeUnit.SECONDS))
                repeat(11) { index ->
                    client.send(ByteArray(960) { 2 })
                    await { player.writtenBytes.get() == (index + 2) * 960 }
                }
                assertEquals(1, chunks.size)
                assertEquals(5L, source.guestDrops)
                assertEquals(source.guestDrops, source.guestAudioDrops)
                assertEquals("rsound", source.audioMode)
                assertTrue(source.clientConnected)
                assertFalse(player.closed)
                release.countDown()
                await { chunks.size == 7 }
                assertEquals(listOf(1, 7, 8, 9, 10, 11, 12), chunks.map { it.seq })
                assertTrue(chunks.zipWithNext().all { (first, next) -> first.captureNs <= next.captureNs })
                assertTrue(chunks.all { it.frames == 240 && it.data.size == 960 })
            }
        } finally {
            release.countDown()
            source.stop()
        }
    }

    @Test fun throwingGuestIsReportedWithoutFallbackOrMutingLocalPlaybackAndFlushRecovers() {
        val fallback = TestFallback()
        val player = RSoundTestPlayer()
        val sends = AtomicInteger()
        val source = source(fallback, player)
        try {
            assertTrue(source.start(AudioSink {
                if (sends.incrementAndGet() == 1) throw IOException("guest transport lost")
            }))
            client(source).use { client ->
                client.send(ByteArray(960))
                await { source.failureKind == "guest-network" && player.writtenBytes.get() == 960 }
                assertEquals("guest-network", source.audioFailureKind)
                assertEquals(null, source.fallbackReason)
                repeat(10) { index ->
                    client.send(ByteArray(960))
                    await { player.writtenBytes.get() == (index + 2) * 960 }
                }
                assertEquals(1, sends.get())
                assertEquals(0, fallback.starts.get())
                assertEquals("rsound", source.audioMode)
                assertTrue(source.rsoundReady)
                assertFalse(player.closed)
                assertTrue(source.guestDrops >= 11)
                source.flushGuestAudio()
                assertEquals(null, source.failureKind)
                client.send(ByteArray(960))
                await { sends.get() == 2 && player.writtenBytes.get() == 11520 }
            }
        } finally {
            source.stop()
        }
    }

    @Test fun stopAndRestartNeverWaitForAnUninterruptibleGuestOrLetRetiredWorkerSendAgain() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val retired = CountDownLatch(1)
        val oldSends = AtomicInteger()
        val newSends = AtomicInteger()
        val players = CopyOnWriteArrayList<RSoundTestPlayer>()
        val source = RSoundAudioSource(null, RSoundPlaybackFactory {
            RSoundTestPlayer().also { players.add(it) }
        }, port = 0, logger = {})
        try {
            assertTrue(source.start(AudioSink {
                oldSends.incrementAndGet()
                entered.countDown()
                while (release.count > 0) {
                    try {
                        release.await()
                    } catch (_: InterruptedException) {
                        Unit
                    }
                }
                retired.countDown()
            }))
            client(source).use { old ->
                old.send(ByteArray(960))
                assertTrue(entered.await(1, TimeUnit.SECONDS))
                repeat(10) { index ->
                    old.send(ByteArray(960))
                    await { players[0].writtenBytes.get() == (index + 2) * 960 }
                }
                val stopAtNs = System.nanoTime()
                source.stop()
                assertTrue(System.nanoTime() - stopAtNs < 1_500_000_000L)
                assertTrue(players[0].closed)
                assertFalse(source.clientConnected)
                assertTrue(source.start(AudioSink { newSends.incrementAndGet() }))
                client(source).use { restarted ->
                    restarted.send(ByteArray(960))
                    await { newSends.get() == 1 && players[1].writtenBytes.get() == 960 }
                    release.countDown()
                    assertTrue(retired.await(1, TimeUnit.SECONDS))
                    Thread.sleep(20)
                    assertEquals(1, oldSends.get())
                }
            }
        } finally {
            release.countDown()
            source.stop()
        }
    }

    @Test fun playbackActivationFailureIsLocalButDataEofIsNot() {
        val fallback = TestFallback()
        val failing = RSoundTestPlayer(onStart = { throw IOException("cannot play") })
        val activation = source(fallback, failing)
        try {
            assertTrue(activation.start(AudioSink {}))
            connect(activation).use { data ->
                connect(activation).use {
                    data.getOutputStream().write(rsoundHeader())
                    assertClosed(data)
                    await { fallback.running }
                    assertEquals("local-playback", activation.failureKind)
                }
            }
        } finally {
            activation.stop()
        }
        val disconnected = source(TestFallback(), RSoundTestPlayer())
        try {
            assertTrue(disconnected.start(AudioSink {}))
            client(disconnected).use { it.send(ByteArray(960)) }
            await { disconnected.fallbackReason?.contains("data EOF") == true }
            assertEquals(null, disconnected.failureKind)
        } finally {
            disconnected.stop()
        }
    }

    @Test fun quickAckIsCreatedOnceRearmedImmediatelyForEachPositiveReadAndClosed() {
        val creates = AtomicInteger()
        val rearms = AtomicInteger()
        val closes = AtomicInteger()
        val logs = CopyOnWriteArrayList<String>()
        val player = RSoundTestPlayer()
        val source = RSoundAudioSource(
            null, RSoundPlaybackFactory { player }, port = 0, logger = { logs.add(it) }, transportChunkBytes = 4,
            quickAckFactory = RSoundQuickAckFactory {
                assertEquals(0, player.writtenBytes.get())
                creates.incrementAndGet()
                object : RSoundQuickAck {
                    override fun rearm(): Boolean {
                        assertEquals(0, player.writtenBytes.get())
                        rearms.incrementAndGet()
                        return true
                    }
                    override fun close() { closes.incrementAndGet() }
                }
            },
        )
        try {
            assertTrue(source.start(AudioSink {}))
            client(source, transportChunkBytes = 4).use { client ->
                await { creates.get() == 1 }
                assertEquals(0, rearms.get())
                client.send(byteArrayOf(1))
                await { rearms.get() == 1 }
                client.send(byteArrayOf(2))
                await { rearms.get() == 2 }
                client.send(ByteArray(958))
                await { player.writtenBytes.get() == 960 && logs.any { it.startsWith("RSound local") } }
                val localSummary = logs.first { it.startsWith("RSound local") }
                val readCount = Regex("readCount=(\\d+)").find(localSummary)!!.groupValues[1].toInt()
                assertEquals(readCount, rearms.get())
                assertTrue(rearms.get() >= 240)
                val arrivalSummary = logs.first { it.startsWith("RSound bridge-arrival") }
                assertTrue(arrivalSummary.contains("quickAckSuccessCount=$readCount "))
                assertTrue(arrivalSummary.contains("quickAckFailureCount=0 "))
            }
            val readRearms = rearms.get()
            await { closes.get() == 1 }
            assertEquals(1, creates.get())
            assertEquals(readRearms, rearms.get())
        } finally {
            source.stop()
        }
    }

    @Test fun quickAckDisabledNeverCreatesAdapterAndUnsupportedOptionWarnsOnceWithoutMuting() {
        val creates = AtomicInteger()
        val disabledPlayer = RSoundTestPlayer()
        val disabled = RSoundAudioSource(null, RSoundPlaybackFactory { disabledPlayer }, port = 0,
            logger = {}, quickAckEnabled = false, quickAckFactory = RSoundQuickAckFactory {
                creates.incrementAndGet()
                throw AssertionError("disabled option must not inspect socket")
            })
        try {
            assertTrue(disabled.start(AudioSink {}))
            client(disabled).use {
                it.send(ByteArray(960))
                await { disabledPlayer.writtenBytes.get() == 960 }
            }
            assertEquals(0, creates.get())
        } finally {
            disabled.stop()
        }
        for (throws in listOf(false, true)) {
            val logs = CopyOnWriteArrayList<String>()
            val rearms = AtomicInteger()
            val player = RSoundTestPlayer()
            val unsupported = RSoundAudioSource(null, RSoundPlaybackFactory { player }, port = 0,
                logger = { logs.add(it) }, transportChunkBytes = 4, quickAckFactory = RSoundQuickAckFactory {
                    object : RSoundQuickAck {
                        override fun rearm(): Boolean {
                            rearms.incrementAndGet()
                            if (throws) throw UnsupportedOperationException("no TCP_QUICKACK")
                            return false
                        }
                        override fun close() {}
                    }
                })
            try {
                assertTrue(unsupported.start(AudioSink {}))
                client(unsupported, transportChunkBytes = 4).use {
                    it.send(ByteArray(960))
                    await { player.writtenBytes.get() == 960 && logs.any { message -> message.startsWith("RSound bridge-arrival") } }
                    assertTrue(rearms.get() >= 240)
                    assertEquals(1, logs.count { it.contains("TCP_QUICKACK unavailable") })
                    assertTrue(logs.first { it.startsWith("RSound bridge-arrival") }.contains("quickAckFailureCount="))
                    assertEquals("rsound", unsupported.audioMode)
                    assertEquals(null, unsupported.failureKind)
                }
            } finally {
                unsupported.stop()
            }
        }
    }

    @Test fun quickAckDescriptorCreationFailureDoesNotChangeLocalPlayback() {
        val creates = AtomicInteger()
        val logs = CopyOnWriteArrayList<String>()
        val player = RSoundTestPlayer()
        val source = RSoundAudioSource(null, RSoundPlaybackFactory { player }, port = 0,
            logger = { logs.add(it) }, quickAckFactory = RSoundQuickAckFactory {
                creates.incrementAndGet()
                throw IOException("descriptor unavailable")
            })
        try {
            assertTrue(source.start(AudioSink {}))
            client(source).use { client ->
                client.send(ByteArray(960))
                await { player.writtenBytes.get() == 960 }
                assertEquals(1, creates.get())
                assertEquals(1, logs.count { it.contains("TCP_QUICKACK unavailable") })
                assertEquals("rsound", source.audioMode)
                assertEquals(null, source.failureKind)
            }
        } finally {
            source.stop()
        }
    }

    @Test fun infoExtrapolatesOnlyTheLocalTimestampAndCapsAtWrittenNotReceivedFrames() {
        val enteredSecond = CountDownLatch(1)
        val releaseSecond = CountDownLatch(1)
        val writes = AtomicInteger()
        val delegate = RSoundTestPlayer(writer = { _, _, count ->
            if (writes.incrementAndGet() == 2) {
                enteredSecond.countDown()
                releaseSecond.await()
            }
            count
        }, onClose = { releaseSecond.countDown() })
        val player = object : RSoundPlayer by delegate {
            override fun stats() = RSoundPlaybackStats(0, 0, 0, System.nanoTime() - 1_000_000_000L, 0)
        }
        val source = RSoundAudioSource(null, RSoundPlaybackFactory { player }, port = 0,
            logger = {}, useControlProtocol = true, transportChunkBytes = 960)
        try {
            assertTrue(source.start(AudioSink {}))
            client(source, useControlProtocol = true, transportChunkBytes = 960).use { client ->
                client.send(ByteArray(1920))
                assertTrue(enteredSecond.await(1, TimeUnit.SECONDS))
                assertEquals(960, delegate.writtenBytes.get())
                client.control.getOutputStream().write(RSoundProtocol.controlReply(" INFO 1920"))
                assertEquals(" INFO 1920 960", readControlBody(client.control))
                releaseSecond.countDown()
                await { delegate.writtenBytes.get() == 1920 }
            }
        } finally {
            releaseSecond.countDown()
            source.stop()
        }
    }

    private fun source(
        fallback: AudioSource?,
        player: RSoundTestPlayer,
        noConnectMs: Long = 5000,
        useControlProtocol: Boolean = false,
        transportChunkBytes: Int = 512,
    ) = RSoundAudioSource(
        fallback, RSoundPlaybackFactory { player }, port = 0, noConnectMs = noConnectMs, logger = {},
        useControlProtocol = useControlProtocol, transportChunkBytes = transportChunkBytes,
    )

    private fun connect(source: RSoundAudioSource): Socket = Socket().apply {
        connect(InetSocketAddress(loopback, source.boundPort), 1000)
        soTimeout = 2000
        tcpNoDelay = true
    }

    private fun client(
        source: RSoundAudioSource,
        useControlProtocol: Boolean = false,
        transportChunkBytes: Int = 512,
    ): Client {
        val data = connect(source)
        var control: Socket? = null
        try {
            val connectedControl = connect(source)
            control = connectedControl
            val header = rsoundHeader()
            for (byte in header) data.getOutputStream().write(byte.toInt())
            assertArrayEquals(
                RSoundProtocol.backendReply(
                    latencyBytes = if (useControlProtocol) 1920 else 0,
                    chunkBytes = transportChunkBytes,
                    useControlProtocol = useControlProtocol,
                ),
                RSoundProtocol.readExactly(data.getInputStream(), if (useControlProtocol) 16 else 8),
            )
            if (!useControlProtocol) {
                data.soTimeout = 80
                assertThrows(SocketTimeoutException::class.java) { data.getInputStream().read() }
                data.soTimeout = 2000
            }
            return Client(data, connectedControl)
        } catch (t: Throwable) {
            data.close()
            control?.close()
            throw t
        }
    }

    private fun assertClosed(socket: Socket) {
        try {
            assertEquals(-1, socket.getInputStream().read())
        } catch (_: SocketException) {
            Unit
        }
    }

    private fun readControlBody(socket: Socket): String {
        val input = socket.getInputStream()
        val header = RSoundProtocol.readExactly(input, 8)
        assertEquals("RSD", String(header, 0, 3, Charsets.US_ASCII))
        val length = String(header, 3, 5, Charsets.US_ASCII).trim().toInt()
        return String(RSoundProtocol.readExactly(input, length), Charsets.US_ASCII)
    }

    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime() + 2_000_000_000L
        while (!condition()) {
            if (System.nanoTime() >= end) throw AssertionError("RSound condition timed out")
            Thread.sleep(2)
        }
    }

    private class Client(val data: Socket, val control: Socket) : AutoCloseable {
        fun send(bytes: ByteArray) { data.getOutputStream().write(bytes) }
        override fun close() { data.close(); control.close() }
    }

    private class TestFallback : AudioSource {
        val starts = AtomicInteger()
        @Volatile var running = false
        @Volatile var sink: AudioSink? = null
        var onStop: () -> Unit = {}
        override fun start(sink: AudioSink): Boolean {
            this.sink = sink
            running = true
            starts.incrementAndGet()
            return true
        }
        override fun stop() { running = false; onStop() }
        fun emit(value: Int) { sink?.onPcm(PcmChunk(1, System.nanoTime(), 240, ByteArray(960) { value.toByte() })) }
    }
}
