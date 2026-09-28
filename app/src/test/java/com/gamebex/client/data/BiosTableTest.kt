package com.gamebex.client.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** BIOS table: every pinned md5 must be a well-formed 32-hex digest. */
class BiosTableTest {
    @Test fun pinnedMd5sAreWellFormed() {
        val bad = BiosTable.TABLE.flatMap { (folder, needs) ->
            needs.flatMap { it.files }.mapNotNull { f ->
                val md5 = f.md5 ?: return@mapNotNull null
                if (md5.matches(Regex("[0-9a-f]{32}"))) null
                else "$folder/${f.name}=$md5"
            }
        }
        assertTrue("malformed md5 pins: $bad", bad.isEmpty())
    }

    @Test fun dcBootPinIsCanonical() {
        val md5 = BiosTable.TABLE.values.flatten().flatMap { it.files }
            .first { it.name == "dc_boot.bin" }.md5
        assertEquals("e10c53c2f8b90bab96ead2d368858623", md5)
    }

    @Test fun gbBiosPinIsCanonical() {
        val f = BiosTable.TABLE.values.flatten().flatMap { it.files }
            .first { it.name == "gb_bios.bin" }
        assertEquals("32fbbd84168d3482956eb3c5051637f5", f.md5)
        assertEquals(256L, f.sizeBytes)
    }

    @Test fun gbcBiosPinIsCanonical() {
        val f = BiosTable.TABLE.values.flatten().flatMap { it.files }
            .first { it.name == "gbc_bios.bin" }
        assertEquals("dbfce9db9deaa2567f6a84fde55f9680", f.md5)
        assertEquals(2304L, f.sizeBytes)
    }

    @Test fun gbaBiosPinIsCanonical() {
        val f = BiosTable.TABLE.values.flatten().flatMap { it.files }
            .first { it.name == "gba_bios.bin" }
        assertEquals("a860e8c0b6d573d191e4ec7db1b1e4f6", f.md5)
        assertEquals(16384L, f.sizeBytes)
    }
}
