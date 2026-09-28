package com.gamebex.client.together.emu

import com.gamebex.client.together.Role
import com.gamebex.client.together.TogetherCopy
import com.gamebex.client.launch.DirectFiles
import com.gamebex.client.launch.EmuFiles
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class PpssppNetConfigTest {
    @Test fun candidatesOrderIncludesPspChildSystemPath() {
        val c = PpssppNetConfig(RestoreStore(File("/tmp/restore")), { DirectFiles }, { null }, { emptyList() }, "/storage/emulated/0")
            .candidates("/storage/emulated/0", listOf("/storage/1D20-0000")) { root ->
                if (root == "/storage/emulated/0") listOf("z", "PSP", "Android", ".hidden") else emptyList()
            }
        assertEquals("/storage/emulated/0/Android/data/org.ppsspp.ppsspp/files/PSP/SYSTEM/ppsspp.ini", c[0])
        assertEquals("/storage/emulated/0/PSP/SYSTEM/ppsspp.ini", c[1])
        assertTrue(c.contains("/storage/emulated/0/PSP/PSP/SYSTEM/ppsspp.ini"))
        assertTrue(c.contains("/storage/emulated/0/PSP/SYSTEM/ppsspp.ini"))
    }

    @Test fun newerCandidateWins() {
        val root = createTempDirectory("ppsspp-candidates").toFile()
        val old = File(root, "PSP/SYSTEM/ppsspp.ini").apply { parentFile.mkdirs(); writeText("old"); setLastModified(1) }
        val newer = File(root, "Android/data/org.ppsspp.ppsspp/files/PSP/SYSTEM/ppsspp.ini").apply { parentFile.mkdirs(); writeText("new"); setLastModified(2) }
        val config = PpssppNetConfig(RestoreStore(root), { DirectFiles }, { null }, { emptyList() }, root.absolutePath)
        assertEquals(newer.absolutePath, config.find())
        assertTrue(old.isFile)
    }

    @Test fun wantedMatchesSpec() {
        val config = PpssppNetConfig(RestoreStore(File("/tmp/restore")), { DirectFiles }, { null }, { emptyList() })
        assertEquals(listOf(
            PpssppNetConfig.IniKey("Network", "EnableWlan", "True"),
            PpssppNetConfig.IniKey("Network", "EnableAdhocServer", "True"),
            PpssppNetConfig.IniKey("Network", "proAdhocServer", "10.42.1.20"),
            PpssppNetConfig.IniKey("Network", "PortOffset", "10000"),
            PpssppNetConfig.IniKey("Network", "EnableUPnP", "False"),
            PpssppNetConfig.IniKey("SystemParam", "NickName", "Sam"),
        ), config.wanted(Role.HOST, "10.42.1.20", "Sam"))
        assertEquals("False", config.wanted(Role.GUEST, "x", "Sam")[1].value)
    }

    @Test fun applyThenRestoreRestoresGlobalAndPerGameBytes() = runTest {
        val root = createTempDirectory("ppsspp-apply").toFile()
        val loader = PpssppNetConfigTest::class.java.classLoader
        val global = File(root, "PSP/SYSTEM/ppsspp.ini").apply { parentFile.mkdirs(); writeText(requireNotNull(loader?.getResource("together/ppsspp-v1.19.3.ini")).readText()) }
        val perGame = File(global.parentFile, "ULUS12345_ppsspp.ini").apply { writeText("[Network]\nEnableWlan = False\n") }
        val before = mapOf(global to global.readBytes(), perGame to perGame.readBytes())
        val calls = mutableListOf<String>()
        val config = PpssppNetConfig(RestoreStore(File(root, "records")), { DirectFiles }, { { pkg -> calls += pkg; true } }, { emptyList() }, root.absolutePath)
        assertTrue(config.apply(Role.HOST, "10.42.1.20", "Sam") is PpssppNetConfig.ApplyResult.Applied)
        assertTrue(global.readText().contains("EnableAdhocServer = True"))
        assertTrue(config.restorePending())
        assertEquals(before[global]!!.toList(), global.readBytes().toList())
        assertEquals(before[perGame]!!.toList(), perGame.readBytes().toList())
        assertTrue(calls.isNotEmpty())
    }

    @Test fun failedSecondWriteRestoresFirstFile() = runTest {
        val root = createTempDirectory("ppsspp-rollback").toFile()
        val global = File(root, "PSP/SYSTEM/ppsspp.ini").apply {
            parentFile.mkdirs()
            writeText("[Network]\nEnableWlan = False\n")
        }
        val perGame = File(global.parentFile, "ULUS12345_ppsspp.ini").apply {
            writeText("[Network]\nEnableAdhocServer = False\n")
        }
        val globalBefore = global.readBytes().toList()
        val perGameBefore = perGame.readBytes().toList()
        var failedPerGameWrite = false
        val backend = object : EmuFiles by DirectFiles {
            override fun writeTextAtomic(path: String, text: String): Boolean {
                if (path == perGame.absolutePath && !failedPerGameWrite) {
                    failedPerGameWrite = true
                    return false
                }
                return DirectFiles.writeTextAtomic(path, text)
            }
        }
        val config = PpssppNetConfig(
            RestoreStore(File(root, "records")),
            { backend },
            { { true } },
            { emptyList() },
            root.absolutePath,
        )
        assertTrue(config.apply(Role.HOST, "10.42.1.20", "Sam") is PpssppNetConfig.ApplyResult.Failed)
        assertEquals(globalBefore, global.readBytes().toList())
        assertEquals(perGameBefore, perGame.readBytes().toList())
        assertTrue(config.restorePending())
    }

    @Test fun nullStopperFailsWithoutWriting() = runTest {
        val root = createTempDirectory("ppsspp-null-stop").toFile()
        val ini = File(root, "PSP/SYSTEM/ppsspp.ini").apply { parentFile.mkdirs(); writeText("[Network]\n") }
        val before = ini.readBytes().toList()
        val config = PpssppNetConfig(RestoreStore(File(root, "records")), { DirectFiles }, { null }, { emptyList() }, root.absolutePath)
        val result = config.apply(Role.HOST, "10.42.1.20", "Sam")
        assertEquals(TogetherCopy.shizukuRequired, (result as PpssppNetConfig.ApplyResult.Failed).text)
        assertEquals(before, ini.readBytes().toList())
    }

    @Test fun applyStopsBeforeReading() = runTest {
        val root = createTempDirectory("ppsspp-stop-order").toFile()
        File(root, "PSP/SYSTEM/ppsspp.ini").apply { parentFile.mkdirs(); writeText("[Network]\nEnableWlan = False\n") }
        val events = mutableListOf<String>()
        val files = object : EmuFiles by DirectFiles {
            override fun isFile(path: String): Boolean {
                events += "read"
                return DirectFiles.isFile(path)
            }
            override fun lastModified(path: String): Long {
                events += "read"
                return DirectFiles.lastModified(path)
            }
            override fun readText(path: String): String? {
                events += "read"
                return DirectFiles.readText(path)
            }
            override fun list(path: String): List<String>? {
                events += "read"
                return DirectFiles.list(path)
            }
        }
        val config = PpssppNetConfig(
            RestoreStore(File(root, "records")),
            { files },
            { { events += "stop"; true } },
            { emptyList() },
            root.absolutePath,
        )
        assertTrue(config.apply(Role.HOST, "10.42.1.20", "Sam") is PpssppNetConfig.ApplyResult.Applied)
        val stopAt = events.indexOf("stop")
        val readAt = events.indexOf("read")
        assertTrue(stopAt >= 0 && readAt >= 0 && stopAt < readAt)
    }

    @Test fun nullBackendKeepsRestoreRecord() = runTest {
        val root = createTempDirectory("ppsspp-null-backend").toFile()
        val ini = File(root, "PSP/SYSTEM/ppsspp.ini").apply { parentFile.mkdirs(); writeText("[Network]\nEnableWlan = False\n") }
        val store = RestoreStore(File(root, "records"))
        store.save("ppsspp", RestoreRecord(listOf(RestoreFile(ini.absolutePath, listOf(RestoreKey("Network", "EnableWlan", "False"))))))
        val config = PpssppNetConfig(store, { null }, { { true } }, { emptyList() }, root.absolutePath)
        assertFalse(config.restorePending())
        assertEquals(ini.absolutePath, store.load("ppsspp")?.files?.single()?.path)
    }

    @Test fun failedStopStillRestores() = runTest {
        val root = createTempDirectory("ppsspp-failed-stop").toFile()
        val ini = File(root, "PSP/SYSTEM/ppsspp.ini").apply { parentFile.mkdirs(); writeText("[Network]\nEnableWlan = True\n") }
        val store = RestoreStore(File(root, "records"))
        store.save("ppsspp", RestoreRecord(listOf(RestoreFile(ini.absolutePath, listOf(RestoreKey("Network", "EnableWlan", "False"))))))
        val config = PpssppNetConfig(store, { DirectFiles }, { { false } }, { emptyList() }, root.absolutePath)
        assertTrue(config.restorePending())
        assertNull(store.load("ppsspp"))
        assertTrue(ini.readText().contains("EnableWlan = False"))
    }

    @Test fun nullReadIsSkipped() = runTest {
        val root = createTempDirectory("ppsspp-null-read").toFile()
        val global = File(root, "PSP/SYSTEM/ppsspp.ini").apply { parentFile.mkdirs(); writeText("[Network]\nEnableWlan = False\n") }
        val missing = File(global.parentFile, "ULUS12345_ppsspp.ini").apply { writeText("gone") }
        val files = object : EmuFiles by DirectFiles {
            override fun readText(path: String): String? = if (path == missing.absolutePath) null else DirectFiles.readText(path)
        }
        val store = RestoreStore(File(root, "records"))
        val config = PpssppNetConfig(store, { files }, { { true } }, { emptyList() }, root.absolutePath)
        assertTrue(config.apply(Role.HOST, "10.42.1.20", "Sam") is PpssppNetConfig.ApplyResult.Applied)
        assertEquals(listOf(global.absolutePath), store.load("ppsspp")?.files?.map { it.path })
        assertEquals("gone", missing.readText())
        assertTrue(global.readText().contains("EnableAdhocServer = True"))
    }
}
