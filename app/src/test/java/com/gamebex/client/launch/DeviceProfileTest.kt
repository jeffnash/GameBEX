package com.gamebex.client.launch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceProfileTest {
    private val gib = 1024L * 1024 * 1024

    /** moto g 2025 (MT6835): 6×A55 @ 2.0 GHz + 2×A76 @ 2.4 GHz, 3.7 GB. */
    private val motoFreqs = List(6) { 2_000_000L } + List(2) { 2_400_000L }
    private val motoRam = (3.7 * gib).toLong()

    @Test fun referencePhoneIsLow() {
        val cpu = DeviceProfile.summarize(motoFreqs)!!
        assertEquals(8, cpu.cores)
        assertEquals(2_400_000L, cpu.topKHz)
        assertEquals(2, cpu.topClusterCores)
        assertEquals(2, cpu.perfCores)
        assertEquals(DeviceTier.LOW, DeviceProfile.tierOf(cpu, motoRam))
        // Each single reason is enough on its own.
        assertEquals(DeviceTier.LOW, DeviceProfile.tierOf(cpu, 12 * gib))
        assertEquals(DeviceTier.LOW, DeviceProfile(soc = "MT6835", cpu = cpu, totalRamBytes = motoRam).tier)
    }

    @Test fun flagshipLayoutsCountAllNonLittleCores() {
        // 1×3.2 + 4×2.8 + 3×2.0: prime cluster is 1 core, but 5 are fast.
        val cpu = DeviceProfile.summarize(List(3) { 2_000_000L } + List(4) { 2_800_000L } + 3_200_000L)!!
        assertEquals(1, cpu.topClusterCores)
        assertEquals(5, cpu.perfCores)
        assertEquals(DeviceTier.HIGH, DeviceProfile.tierOf(cpu, (7.5 * gib).toLong()))
        // Same CPU with a "6 GB" phone's reported RAM: MID, not HIGH.
        assertEquals(DeviceTier.MID, DeviceProfile.tierOf(cpu, (5.5 * gib).toLong()))
    }

    @Test fun midRangeAndEdges() {
        // 4×2.85 + 4×2.0, 8 GB → MID (top < 3.0 GHz).
        val mid = DeviceProfile.summarize(List(4) { 2_000_000L } + List(4) { 2_850_000L })!!
        assertEquals(DeviceTier.MID, DeviceProfile.tierOf(mid, 8 * gib))
        // Low RAM alone → LOW.
        assertEquals(DeviceTier.LOW, DeviceProfile.tierOf(mid, 4 * gib))
        // Single slow cluster: all cores count, but the clock makes it LOW.
        val little = DeviceProfile.summarize(List(8) { 1_800_000L })!!
        assertEquals(8, little.perfCores)
        assertEquals(DeviceTier.LOW, DeviceProfile.tierOf(little, 8 * gib))
        // Unreadable sysfs: never guess HIGH.
        assertNull(DeviceProfile.summarize(emptyList()))
        assertNull(DeviceProfile.summarize(listOf(0L, -1L)))
        assertEquals(DeviceTier.MID, DeviceProfile.tierOf(null, 12 * gib))
        assertEquals(DeviceTier.LOW, DeviceProfile.tierOf(null, motoRam))
        assertEquals(DeviceTier.MID, DeviceProfile.tierOf(null, 0))
    }

    @Test fun summaryLine() {
        val p = DeviceProfile("MT6835", DeviceProfile.summarize(motoFreqs), motoRam)
        assertEquals("MT6835 · 2 fast cores @ 2.4 GHz · 3.7 GB", p.summary)
    }
}
