package com.gamebex.client.launch

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File
import java.util.Locale

enum class DeviceTier(val label: String) {
    LOW("Entry-level phone"),
    MID("Mid-range phone"),
    HIGH("High-end phone"),
}

/** CPU clusters from per-core `cpuinfo_max_freq`. */
data class CpuSummary(
    val cores: Int,
    /** Fastest core's max frequency, kHz. */
    val topKHz: Long,
    /** Cores in the fastest cluster (same max freq as the top core). */
    val topClusterCores: Int,
    /**
     * Cores outside the slowest cluster (all cores on a one-cluster chip).
     * This is the "big core" count: on Dimensity 6300 it's the 2×A76, on a
     * 1+3+4 or 1+4+3 flagship it's 4–5 even though the prime cluster is 1.
     */
    val perfCores: Int,
)

/**
 * What the recommendations key on. Pure core ([summarize], [tierOf]) plus
 * one Android reader ([read]). No per-model tables: everything derives from
 * the core count/frequencies and RAM the OS reports.
 */
data class DeviceProfile(
    val soc: String,
    val cpu: CpuSummary?,
    val totalRamBytes: Long,
) {
    val tier: DeviceTier get() = tierOf(cpu, totalRamBytes)

    /** "MT6835 · 2 fast cores @ 2.4 GHz · 3.7 GB". */
    val summary: String
        get() = listOfNotNull(
            soc.takeIf { it.isNotBlank() },
            cpu?.let { "${it.perfCores} fast ${if (it.perfCores == 1) "core" else "cores"} @ ${ghz(it.topKHz)} GHz" },
            totalRamBytes.takeIf { it > 0 }?.let { String.format(Locale.US, "%.1f GB", it / GIB) },
        ).joinToString(" · ")

    companion object {
        private const val GIB = 1024.0 * 1024 * 1024

        /**
         * Tier thresholds (documented; unit-tested with the reference phone):
         *  - LOW when ANY of: ≤ 2 performance cores; RAM < 5.0 GiB as the OS
         *    reports it (phones sold as "6 GB" report ~5.3–5.7 GiB, so this
         *    means "under 6 GB"); fastest core < 2.3 GHz.
         *  - HIGH when ALL of: ≥ 4 performance cores; fastest core ≥ 3.0 GHz;
         *    RAM ≥ 7.0 GiB ("8 GB" phones report ~7.2–7.6 GiB).
         *  - MID otherwise. Unknown CPU (sysfs unreadable) → RAM decides
         *    LOW, else MID: never guess HIGH.
         * Reference: moto g 2025 (MT6835: 2×A76 2.4 GHz + 6×A55 2.0 GHz,
         * 3.7 GB) → LOW on three counts.
         */
        const val LOW_MAX_PERF_CORES = 2
        const val LOW_RAM_BYTES = (5.0 * GIB).toLong()
        const val LOW_TOP_KHZ = 2_300_000L
        const val HIGH_MIN_PERF_CORES = 4
        const val HIGH_TOP_KHZ = 3_000_000L
        const val HIGH_RAM_BYTES = (7.0 * GIB).toLong()

        fun tierOf(cpu: CpuSummary?, ramBytes: Long): DeviceTier {
            val lowRam = ramBytes in 1 until LOW_RAM_BYTES
            if (lowRam) return DeviceTier.LOW
            if (cpu == null) return DeviceTier.MID
            if (cpu.perfCores <= LOW_MAX_PERF_CORES || cpu.topKHz < LOW_TOP_KHZ) return DeviceTier.LOW
            if (cpu.perfCores >= HIGH_MIN_PERF_CORES && cpu.topKHz >= HIGH_TOP_KHZ && ramBytes >= HIGH_RAM_BYTES) {
                return DeviceTier.HIGH
            }
            return DeviceTier.MID
        }

        /** Per-core max freqs (kHz; unreadable cores dropped) → clusters. Null when none. Pure. */
        fun summarize(maxKHz: List<Long>): CpuSummary? {
            val f = maxKHz.filter { it > 0 }
            if (f.isEmpty()) return null
            val top = f.max()
            val min = f.min()
            return CpuSummary(
                cores = f.size,
                topKHz = top,
                topClusterCores = f.count { it == top },
                perfCores = if (top == min) f.size else f.count { it > min },
            )
        }

        fun ghz(kHz: Long): String = String.format(Locale.US, "%.1f", kHz / 1_000_000.0)

        @Volatile private var cached: DeviceProfile? = null

        /** Reads sysfs + ActivityManager once per process. Never throws; call off main. */
        fun read(ctx: Context): DeviceProfile = cached ?: DeviceProfile(
            soc = soc(),
            cpu = summarize(cpuMaxFreqs()),
            totalRamBytes = runCatching {
                val am = ctx.getSystemService(ActivityManager::class.java)
                ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }.totalMem
            }.getOrDefault(0L),
        ).also { cached = it }

        private fun soc(): String = runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                listOf(Build.SOC_MANUFACTURER, Build.SOC_MODEL)
                    .filter { it.isNotBlank() && it != Build.UNKNOWN }
                    .joinToString(" ")
                    .ifBlank { Build.HARDWARE }
            } else {
                Build.HARDWARE
            }
        }.getOrDefault("")

        private fun cpuMaxFreqs(): List<Long> = runCatching {
            val dirs = File("/sys/devices/system/cpu").listFiles { f -> f.name.matches(Regex("cpu\\d+")) }.orEmpty()
            dirs.mapNotNull { d ->
                runCatching { File(d, "cpufreq/cpuinfo_max_freq").readText().trim().toLong() }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }
}
