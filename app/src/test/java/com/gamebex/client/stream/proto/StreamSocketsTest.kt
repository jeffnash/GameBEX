package com.gamebex.client.stream.proto

import java.net.DatagramSocket
import java.net.SocketException
import org.junit.Assert.*
import org.junit.Test

class StreamSocketsTest {
    @Test fun cs6Is192LegacyEfIs184AndVideoRemains136() {
        assertEquals(192, StreamSockets.inputTos(true))
        assertEquals(184, StreamSockets.inputTos(false))
        assertEquals(136, StreamSockets.TOS_VIDEO)
    }

    @Test fun markInputReturnsActualSocketValue() {
        val socket = RecordingSocket()
        try {
            socket.actual = 64
            assertEquals(64, StreamSockets.markInput(socket, true))
            assertEquals(192, socket.requested)
            assertEquals(1, socket.setCalls)
            StreamSockets.markInput(socket)
            assertEquals(184, socket.requested)
        } finally { socket.close() }
    }

    @Test fun disabledDoesNotMutateSocket() {
        val previous = StreamSockets.enabled
        val socket = RecordingSocket()
        try {
            StreamSockets.enabled = false
            assertNull(StreamSockets.markInput(socket, true))
            assertEquals(0, socket.setCalls)
        } finally {
            StreamSockets.enabled = previous
            socket.close()
        }
    }

    @Test fun refusedMarkReturnsNull() {
        val socket = RecordingSocket()
        try {
            socket.refuse = true
            assertNull(StreamSockets.markInput(socket, true))
        } finally { socket.close() }
    }

    @Test fun audioAlwaysUsesEfAndReturnsTheActualMark() {
        val socket = RecordingSocket()
        try {
            socket.actual = 64
            assertEquals(64, StreamSockets.markAudio(socket))
            assertEquals(184, socket.requested)
            assertEquals(1, socket.setCalls)
        } finally { socket.close() }
    }

    @Test fun audioMarkIsBestEffortAndDisabledLeavesSocketUnchanged() {
        val previous = StreamSockets.enabled
        val socket = RecordingSocket()
        try {
            StreamSockets.enabled = false
            assertNull(StreamSockets.markAudio(socket))
            assertEquals(0, socket.setCalls)
            StreamSockets.enabled = true
            socket.refuse = true
            assertNull(StreamSockets.markAudio(socket))
        } finally {
            StreamSockets.enabled = previous
            socket.close()
        }
    }

    private class RecordingSocket : DatagramSocket() {
        var requested = -1
        var actual = 0
        var setCalls = 0
        var refuse = false
        override fun setTrafficClass(tc: Int) {
            setCalls++
            if (refuse) throw SocketException("refused")
            requested = tc
        }
        override fun getTrafficClass(): Int = actual
    }
}
