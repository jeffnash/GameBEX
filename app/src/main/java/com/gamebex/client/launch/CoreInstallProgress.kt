package com.gamebex.client.launch

import com.gamebex.client.data.CoreNames
import java.util.Locale

data class CoreInstallProgress(
    val core: String,
    val read: Long = 0,
    val total: Long = 0,
    val installing: Boolean = false,
    val stalled: Boolean = false,
) {
    val fraction: Float?
        get() = if (total > 0 && !installing) (read.toDouble() / total).coerceIn(0.0, 1.0).toFloat() else null

    val text: String
        get() = when {
            stalled -> "Still working…"
            installing -> "Installing in RetroArch…"
            read > 0 && total > 0 -> "Downloading ${mb(read)} of ${mb(total)} MB"
            read > 0 -> "Downloading ${mb(read)} MB"
            else -> "Installing the ${label()} core…"
        }

    private fun label(): String = when (core) {
        "mupen64plus_next_gles3", "mupen64plus_next", "parallel_n64" -> "N64"
        else -> CoreNames.display(core)
    }

    private fun mb(bytes: Long): String = String.format(Locale.ROOT, "%.1f", bytes.coerceAtLeast(0) / 1_048_576.0)
}
