package com.gamebex.client.launch

import com.gamebex.client.data.Emu
import com.gamebex.client.launch.EmuControllerSetup.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** Direct → Shizuku fallback decisions, the shell path allowlist, and status mapping. */
class EmuFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val own = "com.gamebex.client"
    private val pad = ControllerRegistry.Pad(12, "GameSir-X5s", "pad")

    /** Our uid under Android 11+: other apps' dirs look absent. */
    private object Hidden : EmuFiles {
        override fun isDirectory(path: String) = false
        override fun isFile(path: String) = false
        override fun exists(path: String) = false
        override fun readText(path: String): String? = null
        override fun writeTextAtomic(path: String, text: String) = throw IOException("EACCES")
        override fun mkdirs(path: String) = false
        override fun copy(path: String, dest: String) = throw IOException("EACCES")
        override fun list(path: String) = listOf("com.gamebex.client")
    }

    /** Dir visible but every read/write denied (EACCES). */
    private object Denied : EmuFiles {
        override fun isDirectory(path: String) = true
        override fun isFile(path: String) = true
        override fun exists(path: String) = true
        override fun readText(path: String): String = throw IOException("EACCES")
        override fun writeTextAtomic(path: String, text: String) = throw IOException("EACCES")
        override fun mkdirs(path: String) = false
        override fun copy(path: String, dest: String) = throw IOException("EACCES")
    }

    /** Stand-in for the shell service: real IO, counting calls. */
    private class Counting : EmuFiles by DirectFiles {
        var writes = 0
        override fun writeTextAtomic(path: String, text: String): Boolean {
            writes++
            return DirectFiles.writeTextAtomic(path, text)
        }
    }

    /** Only our own pkg is listed in Android/data (hidden), but the emulator dir exists on disk. */
    private fun sdcard(): File {
        val b = tmp.newFolder("sdcard")
        File(b, "Android/data/$own").mkdirs()
        return b
    }

    private fun dolphinIni(b: File): File =
        File(b, "Android/data/${Emu.DOLPHIN.pkg}/files/Config/Dolphin.ini").apply {
            parentFile!!.mkdirs()
            writeText("[Android]\nShowInputOverlay = True\n")
        }

    private fun run(emu: Emu, b: File, direct: EmuFiles, priv: Privileged): Pair<EmuControllerSetup.Result, Int> {
        var asked = 0
        val r = EmuControllerSetup.applyUnder(emu, true, b, own, listOf(pad), direct, { asked++; priv }) { "" }
        return r to asked
    }

    @Test fun directOkNeverTouchesShizuku() {
        val b = sdcard()
        val ini = dolphinIni(b)
        val (r, asked) = run(Emu.DOLPHIN, b, DirectFiles, Privileged.NotRunning)
        assertEquals(Status.ONE_TIME, r.status)
        assertEquals(0, asked)
        assertTrue(ini.readText().contains("ShowInputOverlay = False"))
    }

    @Test fun hiddenDirGoesThroughShizukuWithBackupAndAtomicWrite() {
        val b = sdcard()
        val ini = dolphinIni(b)
        val shell = Counting()
        val (r, asked) = run(Emu.DOLPHIN, b, Hidden, Privileged.Ready(shell))
        assertEquals(Status.ONE_TIME, r.status)
        assertEquals(1, asked)
        assertEquals(1, shell.writes)
        assertTrue(ini.readText().contains("ShowInputOverlay = False"))
        assertEquals("[Android]\nShowInputOverlay = True\n", File(ini.path + EmuControllerSetup.BACKUP_SUFFIX).readText())
        // Idempotent through Shizuku too.
        assertEquals(Status.ONE_TIME, run(Emu.DOLPHIN, b, Hidden, Privileged.Ready(shell)).first.status)
        assertEquals(1, shell.writes)
    }

    @Test fun deniedDirectRetriesViaShizuku() {
        val b = sdcard()
        File(b, "Android/data/${Emu.FLYCAST.pkg}/files").mkdirs()
        val shell = Counting()
        val (r, asked) = run(Emu.FLYCAST, b, Denied, Privileged.Ready(shell))
        assertEquals(Status.CONFIGURED, r.status)
        assertEquals(1, asked)
        assertEquals(
            EmuControllerConfig.flycastMappingFor(emptySet()),
            File(b, "Android/data/${Emu.FLYCAST.pkg}/files/mappings/Android_GameSir-X5s.cfg").readText(),
        )
    }

    @Test fun shizukuStatesMapToPreciseStatuses() {
        val b = sdcard()
        dolphinIni(b)
        assertEquals(Status.SHIZUKU_START, run(Emu.DOLPHIN, b, Hidden, Privileged.NotRunning).first.status)
        assertEquals(Status.SHIZUKU_ALLOW, run(Emu.DOLPHIN, b, Hidden, Privileged.NotGranted).first.status)
        assertEquals(Status.BLOCKED, run(Emu.DOLPHIN, b, Hidden, Privileged.Unavailable).first.status)
        assertEquals(Status.BLOCKED, run(Emu.DOLPHIN, b, Denied, Privileged.Outdated).first.status)
        assertEquals(Status.FAILED, run(Emu.DOLPHIN, b, Hidden, Privileged.NoResponse).first.status)
        assertTrue(Status.SHIZUKU_START.needsShizuku && Status.SHIZUKU_ALLOW.needsShizuku)
        assertFalse(Status.BLOCKED.needsShizuku)
        assertEquals("Dolphin: Start Shizuku to finish setup", run(Emu.DOLPHIN, b, Hidden, Privileged.NotRunning).first.line)
        assertEquals("Dolphin: Allow GameBEX in Shizuku", run(Emu.DOLPHIN, b, Hidden, Privileged.NotGranted).first.line)
    }

    @Test fun shizukuReadyButNeverOpenedIsOpenOnce() {
        val b = sdcard()
        val (r, _) = run(Emu.VITA3K, b, Hidden, Privileged.Ready(Counting()))
        assertEquals(Status.OPEN_ONCE, r.status)
    }

    @Test fun shizukuFailureIsFailedNotThrown() {
        val b = sdcard()
        dolphinIni(b)
        val broken = object : EmuFiles by DirectFiles {
            override fun writeTextAtomic(path: String, text: String): Boolean = throw IOException("binder died")
        }
        assertEquals(Status.FAILED, run(Emu.DOLPHIN, b, Hidden, Privileged.Ready(broken)).first.status)
    }

    @Test fun visibleOtherAppsButMissingDirIsOpenOnceWithoutShizuku() {
        val b = sdcard()
        File(b, "Android/data/org.ppsspp.ppsspp").mkdirs()
        val (r, asked) = run(Emu.DOLPHIN, b, DirectFiles, Privileged.NotRunning)
        assertEquals(Status.OPEN_ONCE, r.status)
        assertEquals(0, asked)
    }

    private val root = ShellPathPolicy.DATA_ROOT

    @Test fun allowlistAcceptsEmulatorDataFiles() {
        for (e in Emu.entries) {
            val p = "$root${e.pkg}/files/config.yml"
            assertEquals(p, ShellPathPolicy.allowed(p))
        }
        assertEquals("${root}com.flycast.emulator/files/mappings/Android_GameSir-X5s.cfg",
            ShellPathPolicy.allowed("${root}com.flycast.emulator/files/mappings/Android_GameSir-X5s.cfg"))
    }

    @Test fun allowlistRejectsEverythingElse() {
        val bad = listOf(
            "",
            "relative/path",
            "/sdcard/Android/data/org.dolphinemu.dolphinemu/files/x", // not canonical root
            "/storage/emulated/10/Android/data/org.dolphinemu.dolphinemu/files/x",
            "${root}com.gamebex.client/files/x", // ourselves: not an emulator
            "${root}com.evil.app/files/x",
            "${root}org.dolphinemu.dolphinemu", // package dir itself
            "${root}org.dolphinemu.dolphinemu/",
            "${root}org.dolphinemu.dolphinemu/../com.evil.app/x",
            "${root}org.dolphinemu.dolphinemu/files/../../com.evil.app/x",
            "${root}org.dolphinemu.dolphinemu/files/./x",
            "${root}org.dolphinemu.dolphinemu/files//x",
            "${root}org.dolphinemu.dolphinemu/files/x/..",
            "${root}org.dolphinemu.dolphinemu/files/x\u0000.ini",
            "${root}org.dolphinemu.dolphinemu/files/..\\..\\x",
            "${root}org.dolphinemu.dolphinemuX/files/x",
            "/storage/emulated/0/Android/data/../obb/org.dolphinemu.dolphinemu/x",
            "${root}org.dolphinemu.dolphinemu/files/" + "a".repeat(1100),
        )
        for (p in bad) assertNull(p, ShellPathPolicy.allowed(p))
    }
}
