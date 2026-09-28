package com.gamebex.client.launch

import com.gamebex.client.together.Role
import com.gamebex.client.together.TogetherMode
import com.gamebex.client.together.emu.TogetherRaConfig
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphicsTunerTogetherTest {
    @Test fun togetherPathsDoNotTouchNormalConfig() {
        val base = createTempDirectory("gbx-gfx").toFile()
        val normal = GraphicsTuner.retroArchConfigFileUnder(base)
        normal.parentFile.mkdirs()
        normal.writeText("sentinel")
        val cfg = requireNotNull(TogetherRaConfig.write(base, Role.HOST, TogetherMode.RA_NETPLAY, "10.42.1.20", "Sam"))
        assertEquals("sentinel", normal.readText())
        assertEquals(
            requireNotNull(javaClass.classLoader?.getResource("together/host-ra-netplay.cfg")).readText()
                .replace("/storage/emulated/0", base.absolutePath),
            cfg.readText(),
        )
        assertTrue(cfg.isFile)
        assertFalse(GraphicsTuner.togetherDirUnder(base).absolutePath == normal.parentFile.absolutePath)
        val together = GraphicsTuner.togetherDirUnder(base)
        assertTrue(together.absolutePath.endsWith("GameBEX/together"))
        assertTrue(File(together, "saves").isDirectory)
        assertTrue(File(together, "states").isDirectory)
        assertTrue(File(GraphicsTuner.sharedDirUnder(base), "ra").isDirectory)
        assertEquals(
            "# Managed by GameBEX Play together. Empty on purpose: both phones run core defaults.\n",
            File(together, "core-options.cfg").readText(),
        )
        assertEquals(GraphicsTuner.togetherConfigFileUnder(base, Role.HOST), cfg)
        assertTrue(cfg.absolutePath.endsWith("GameBEX/ra/retroarch-together-host.cfg"))
        assertFalse(GraphicsTuner.retroArchFreshConfigFileUnder(base).exists())
    }

    @Test fun writeReturnsNullWhenTheBaseIsAFile() {
        val base = kotlin.io.path.createTempFile("gbx-not-a-dir").toFile()
        assertEquals(null, TogetherRaConfig.write(base, Role.HOST, TogetherMode.RA_NETPLAY, "10.42.1.20", "Sam"))
    }

    @Test fun normalManagedTextRemainsCurrent() {
        val base = createTempDirectory("gbx-gfx-normal").toFile()
        val cfg = requireNotNull(GraphicsTuner.writeRetroArchConfigUnder(base))
        val text = cfg.readText()
        val golden = requireNotNull(javaClass.classLoader?.getResource("together/retroarch-frontend.cfg")).readText()
            .replace("/storage/emulated/0", base.absolutePath)
        assertEquals(golden, text)
        assertTrue(text.contains("auto_overrides_enable = \"true\""))
        assertTrue(text.contains("savestate_auto_load = \"true\""))
        assertFalse(text.contains("retroarch-together"))
        assertEquals(GraphicsTuner.retroArchConfigFileUnder(base), cfg)
        assertFalse(cfg.absolutePath.contains("together"))
    }
}
