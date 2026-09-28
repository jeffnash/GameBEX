package com.gamebex.client.together.emu

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InstalledCoreLedgerTest {
    @Test fun recordThenGet() {
        val dir = createTempDirectory("core-ledger").toFile()
        val so = File(dir, "snes9x_libretro_android.so").apply { writeBytes(ByteArray(128) { it.toByte() }) }
        val ledger = InstalledCoreLedger(File(dir, "installed.json"))
        ledger.record("snes9x", so)
        assertEquals(so.length(), ledger.get("snes9x")!!.size)
        assertEquals(so.absolutePath, ledger.verified("snes9x")!!.file.absolutePath)
    }

    @Test fun changedFileFailsVerification() {
        val dir = createTempDirectory("core-ledger-changed").toFile()
        val so = File(dir, "snes9x.so").apply { writeText("original") }
        val ledger = InstalledCoreLedger(File(dir, "installed.json"))
        ledger.record("snes9x", so)
        so.writeText("changed")
        assertNull(ledger.verified("snes9x"))
    }
}
