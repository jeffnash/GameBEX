package com.gamebex.client.stream.shell

import com.gamebex.client.stream.StreamCaps
import com.gamebex.client.stream.proto.PadHid
import com.gamebex.client.stream.proto.PadState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins struct uhid_event (linux/uhid.h) and the hid tool's JSON commands. */
class UhidEventsTest {
    private val le = ByteOrder.LITTLE_ENDIAN

    private fun create(): ByteArray = UhidEvents.create2(
        name = StreamCaps.PAD_NAME,
        phys = StreamCaps.PAD_PHYS,
        uniq = StreamCaps.PAD_UNIQ,
        bus = StreamCaps.PAD_BUS,
        vendor = StreamCaps.PAD_VENDOR,
        product = StreamCaps.PAD_PRODUCT,
        version = StreamCaps.PAD_VERSION,
        descriptor = PadHid.DESCRIPTOR,
        order = le,
    )

    private fun cString(bytes: ByteArray, offset: Int, width: Int): String {
        var end = offset
        while (end < offset + width && bytes[end].toInt() != 0) end++
        return String(bytes, offset, end - offset, StandardCharsets.UTF_8)
    }

    @Test
    fun create2HasTheKernelLayout() {
        val e = create()
        assertEquals(280 + PadHid.DESCRIPTOR.size, e.size)
        val b = ByteBuffer.wrap(e).order(le)
        assertEquals(UhidEvents.UHID_CREATE2, b.getInt(0))
        assertEquals("GameBEX Player 2", cString(e, 4, 128))
        assertEquals("gamebex:stream", cString(e, 132, 64))
        assertEquals("gamebex-player-2", cString(e, 196, 64))
        assertEquals(PadHid.DESCRIPTOR.size, b.getShort(260).toInt())
        assertEquals(0x06, b.getShort(262).toInt())
        assertEquals(0, b.getInt(264))
        assertEquals(0, b.getInt(268))
        assertEquals(1, b.getInt(272))
        assertEquals(0, b.getInt(276))
        assertArrayEquals(PadHid.DESCRIPTOR, e.copyOfRange(280, e.size))
    }

    @Test
    fun create2CutsLongNamesAtACharacterBoundary() {
        val name = "é".repeat(100)
        val e = UhidEvents.create2(name, "p", "u", 6, 0, 0, 1, PadHid.DESCRIPTOR, le)
        val stored = cString(e, 4, 128)
        assertEquals(63, stored.length)
        assertEquals(0, e[4 + 127].toInt())
        assertEquals("p", cString(e, 132, 64))
    }

    @Test
    fun input2CarriesTheReport() {
        val report = PadHid.report(PadState(buttons = PadState.A or PadState.START, lx = -127, rt = 255, hat = PadState.HAT_LEFT))
        val e = UhidEvents.input2(report, le)
        assertEquals(6 + PadHid.REPORT_SIZE, e.size)
        val b = ByteBuffer.wrap(e).order(le)
        assertEquals(UhidEvents.UHID_INPUT2, b.getInt(0))
        assertEquals(PadHid.REPORT_SIZE, b.getShort(4).toInt())
        assertArrayEquals(report, e.copyOfRange(6, e.size))
    }

    @Test
    fun destroyAndRepliesHaveTheirSizes() {
        val destroy = UhidEvents.destroy(le)
        assertEquals(4, destroy.size)
        assertEquals(UhidEvents.UHID_DESTROY, ByteBuffer.wrap(destroy).order(le).int)

        val get = ByteBuffer.wrap(UhidEvents.getReportReply(77, UhidEvents.EIO, le)).order(le)
        assertEquals(12, get.capacity())
        assertEquals(UhidEvents.UHID_GET_REPORT_REPLY, get.getInt(0))
        assertEquals(77, get.getInt(4))
        assertEquals(5, get.getShort(8).toInt())
        assertEquals(0, get.getShort(10).toInt())

        val set = ByteBuffer.wrap(UhidEvents.setReportReply(78, UhidEvents.EIO, le)).order(le)
        assertEquals(10, set.capacity())
        assertEquals(UhidEvents.UHID_SET_REPORT_REPLY, set.getInt(0))
        assertEquals(78, set.getInt(4))
        assertEquals(5, set.getShort(8).toInt())
    }

    @Test
    fun readsTypeAndRequestIdOfKernelEvents() {
        val buffer = ByteArray(UhidEvents.EVENT_SIZE)
        ByteBuffer.wrap(buffer).order(le).putInt(UhidEvents.UHID_GET_REPORT).putInt(4242)
        assertEquals(UhidEvents.UHID_GET_REPORT, UhidEvents.typeOf(buffer, buffer.size, le))
        assertEquals(4242, UhidEvents.requestId(buffer, buffer.size, le))
        assertEquals(-1, UhidEvents.typeOf(buffer, 3, le))
        assertEquals(-1, UhidEvents.requestId(buffer, 7, le))
    }

    @Test
    fun hidToolRegisterCommandMatchesTheProvenFormat() {
        val o = Json.parseToJsonElement(HidToolPad.registerCommand()).jsonObject
        assertEquals(1, o.getValue("id").jsonPrimitive.int)
        assertEquals("register", o.getValue("command").jsonPrimitive.content)
        assertEquals("GameBEX Player 2", o.getValue("name").jsonPrimitive.content)
        assertEquals(0, o.getValue("vid").jsonPrimitive.int)
        assertEquals(0, o.getValue("pid").jsonPrimitive.int)
        assertEquals("usb", o.getValue("bus").jsonPrimitive.content)
        val descriptor = o.getValue("descriptor").jsonArray.map { it.jsonPrimitive.int }
        assertEquals(PadHid.DESCRIPTOR.map { it.toInt() and 0xFF }, descriptor)
    }

    @Test
    fun hidToolReportCommandUsesUnsignedBytes() {
        val report = PadHid.report(PadState(lx = -1, rt = 200))
        val o = Json.parseToJsonElement(HidToolPad.reportCommand(report)).jsonObject
        assertEquals("report", o.getValue("command").jsonPrimitive.content)
        val values = o.getValue("report").jsonArray.map { it.jsonPrimitive.int }
        assertEquals(report.map { it.toInt() and 0xFF }, values)
        assertEquals(255, values[3])
        assertEquals(200, values[8])
    }
}
