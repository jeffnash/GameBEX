package com.gamebex.client.launch

import android.content.ContextWrapper
import android.content.Intent
import com.gamebex.client.together.TogetherCaps
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.runBlocking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Core auto-fetch pure pieces: URL shape and .so picking. */
class RetroCoreFetcherTest {
    @Test fun coreZipUrlPointsAtNightlyArm64() {
        assertEquals(
            "https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/mupen64plus_next_gles3_libretro_android.so.zip",
            RetroCoreFetcher.coreZipUrl("mupen64plus_next_gles3"),
        )
    }

    @Test fun pickSoEntryPrefersExact() {
        val names = listOf(
            "mupen64plus_next_gles3_libretro_android.so",
            "mupen64plus_next_gles3_libretro.info",
        )
        assertEquals(
            "mupen64plus_next_gles3_libretro_android.so",
            RetroCoreFetcher.pickSoEntry(names, "mupen64plus_next_gles3"),
        )
    }

    @Test fun pickSoEntryIgnoresDirectoryPrefix() {
        val names = listOf("cores/mupen64plus_next_gles3_libretro_android.so")
        assertEquals(
            "cores/mupen64plus_next_gles3_libretro_android.so",
            RetroCoreFetcher.pickSoEntry(names, "mupen64plus_next_gles3"),
        )
    }

    @Test fun pickSoEntryFallsBackToLoneSo() {
        assertEquals(
            "other_libretro_android.so",
            RetroCoreFetcher.pickSoEntry(listOf("other_libretro_android.so"), "mupen64plus_next_gles3"),
        )
    }

    @Test fun pickSoEntryRejectsAmbiguousAndEmpty() {
        assertNull(
            RetroCoreFetcher.pickSoEntry(
                listOf("a_libretro_android.so", "b_libretro_android.so"),
                "mupen64plus_next_gles3",
            ),
        )
        assertNull(RetroCoreFetcher.pickSoEntry(emptyList(), "mupen64plus_next_gles3"))
    }

    @Test fun elfHeaderDetectsMagic() {
        assertTrue(RetroCoreFetcher.hasElfHeader(byteArrayOf(0x7F, 0x45, 0x4C, 0x46, 2, 1, 1, 0)))
        assertFalse(RetroCoreFetcher.hasElfHeader("PK\u0003\u0004".toByteArray())) // zip
        assertFalse(RetroCoreFetcher.hasElfHeader("<htm".toByteArray())) // error page
        assertFalse(RetroCoreFetcher.hasElfHeader(byteArrayOf(0x7F, 0x45)))
    }

    @Test fun usableCoreNeedsSizeAndElf() {
        val elf = byteArrayOf(0x7F, 0x45, 0x4C, 0x46)
        assertTrue(RetroCoreFetcher.isUsableCore(100_001, elf))
        assertFalse(RetroCoreFetcher.isUsableCore(100_001, "oops".toByteArray())) // big but not ELF
        assertFalse(RetroCoreFetcher.isUsableCore(100_001, null)) // unreadable header
        assertFalse(RetroCoreFetcher.isUsableCore(50_000, elf)) // small even with ELF
        assertFalse(RetroCoreFetcher.isUsableCore(100_000, elf)) // boundary is exclusive
    }

    @Test fun detectsAarch64ElfOnly() {
        val arm = ByteArray(20).also {
            it[0] = 0x7F
            it[1] = 0x45
            it[2] = 0x4C
            it[3] = 0x46
            it[18] = 0xB7.toByte()
        }
        val x86 = arm.copyOf().also { it[18] = 0x3E }
        assertTrue(RetroCoreFetcher.isAarch64Elf(arm))
        assertFalse(RetroCoreFetcher.isAarch64Elf(x86))
        assertFalse(RetroCoreFetcher.isAarch64Elf(arm.copyOf(19)))
    }

    @Test fun gles3BaseNameParses() {
        assertEquals(
            "mupen64plus_next_gles3",
            RetroCoreProbe.coreBaseName("mupen64plus_next_gles3_libretro_android.so"),
        )
    }

    @Test fun urlHostNormalizesForRedirectPolicy() {
        assertEquals(
            "buildbot.libretro.com",
            RetroCoreFetcher.urlHost("https://BuildBot.Libretro.com/nightly/x.zip"),
        )
        assertEquals("evil.test", RetroCoreFetcher.urlHost("https://evil.test:8443/y.zip?z=1"))
        assertNull(RetroCoreFetcher.urlHost("not a url"))
    }

    @Test fun coreZipCapIsSane() {
        // Cores ship ~1-30 MB zipped; the cap must bound RAM/disk abuse
        // without rejecting real packages.
        assertTrue(RetroCoreFetcher.MAX_CORE_ZIP_BYTES >= 50L * 1024 * 1024)
        assertTrue(RetroCoreFetcher.MAX_CORE_ZIP_BYTES <= 256L * 1024 * 1024)
    }

    @Test fun ensureCoreRefusesMissingRomAndAttachesRomExtraDespiteSideloadWithoutRom() = runBlocking {
        assertTrue(TogetherCaps.SIDELOAD_WITHOUT_ROM)
        val ctx = object : ContextWrapper(null) {
            var started = 0
            override fun startActivity(intent: Intent) { started += 1 }
        }
        val missing = File(createTempDirectory("ensure-core-rom-gate").toFile(), "gone.sfc")
        assertEquals(
            RetroCoreFetcher.Result.Failed("ROM file is no longer available."),
            RetroCoreFetcher.ensureCore(ctx, "snes9x", null),
        )
        assertEquals(
            RetroCoreFetcher.Result.Failed("ROM file is no longer available."),
            RetroCoreFetcher.ensureCore(ctx, "snes9x", missing),
        )
        assertEquals(0, ctx.started)
        val rom = File(createTempDirectory("ensure-core-rom-extra").toFile(), "game.sfc").apply {
            writeText("rom")
        }
        val intent = object : Intent() {
            val romExtras = linkedMapOf<String, String>()
            override fun putExtra(name: String, value: String?): Intent {
                if (value != null) romExtras[name] = value
                return this
            }
        }
        RetroCoreFetcher.applyRomExtra(intent, rom)
        assertEquals(rom.absolutePath, intent.romExtras["ROM"])
    }

}
