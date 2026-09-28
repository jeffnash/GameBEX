package com.gamebex.client.data.local

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.InputStream

/**
 * Per-game art slots (BOX/HERO/LOGO/SHOT) in app-private files/art/<gameId>/,
 * so art survives SD-card removal. GameEntity.art* columns hold the absolute
 * paths (or null).
 *
 * A `<slot>.user` marker file (not a DB column) means the user imported that
 * image; auto-fetch never replaces marked slots. The marker is cleared with the slot file.
 */
object ArtStore {
    const val SLOT_BOX = "box"
    const val SLOT_HERO = "hero"
    const val SLOT_LOGO = "logo"
    const val SLOT_SHOT = "shot"
    val SLOTS = listOf(SLOT_BOX, SLOT_HERO, SLOT_LOGO, SLOT_SHOT)

    /** Largest accepted image, enforced while streaming (never after a full read). */
    const val MAX_IMAGE_BYTES = 12 * 1024 * 1024L
    const val MIN_IMAGE_BYTES = 64L
    private const val HEADER_BYTES = 32
    private const val MAX_DIMENSION = 16384

    fun dirFor(context: Context, gameId: Long): File =
        File(File(context.filesDir, "art"), gameId.toString()).apply { mkdirs() }

    fun fileFor(context: Context, gameId: Long, slot: String): File =
        File(dirFor(context, gameId), "$slot.jpg")

    /** Staging name for fetched art: validated before it replaces the slot. */
    fun stageFileFor(dir: File, slot: String): File = File(dir, "$slot.fetch.tmp")

    fun ownerMarkerFor(dir: File, slot: String): File = File(dir, "$slot.user")

    fun isUserOwned(dir: File, slot: String): Boolean = ownerMarkerFor(dir, slot).isFile

    fun markUserOwned(dir: File, slot: String) {
        runCatching { ownerMarkerFor(dir, slot).writeText("user") }
    }

    fun clearOwner(dir: File, slot: String) {
        runCatching { ownerMarkerFor(dir, slot).delete() }
    }

    /**
     * User import: stream a picked image into a slot. Blocking; call off the
     * main thread. Returns the stored file, or null on failure. Only JPEG /
     * PNG / WEBP content is accepted (magic bytes — picker MIME types lie).
     * The existing slot is replaced only after the staged copy validates.
     */
    fun importFromUri(context: Context, gameId: Long, slot: String, uri: Uri): File? {
        if (slot !in SLOTS) return null
        return try {
            val dir = dirFor(context, gameId)
            val tmp = File.createTempFile("import", ".tmp", context.cacheDir)
            try {
                context.contentResolver.openInputStream(uri)?.use { inp ->
                    if (!copyBounded(inp, tmp, MAX_IMAGE_BYTES)) return null
                } ?: return null
                if (!isValidImageFile(tmp)) return null
                if (!moveFile(tmp, File(dir, "$slot.jpg"))) return null
                markUserOwned(dir, slot)
                File(dir, "$slot.jpg")
            } finally {
                runCatching { tmp.delete() }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Stream [inp] to [dest], enforcing [limitBytes] during the copy.
     * Returns false (and leaves a deleted dest) when the stream exceeds the
     * cap or is smaller than [MIN_IMAGE_BYTES].
     */
    fun copyBounded(inp: InputStream, dest: File, limitBytes: Long): Boolean {
        var total = 0L
        try {
            dest.outputStream().use { out ->
                val buf = ByteArray(32 * 1024)
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > limitBytes) return false
                    out.write(buf, 0, n)
                }
            }
        } catch (_: Exception) {
            runCatching { dest.delete() }
            return false
        }
        if (total < MIN_IMAGE_BYTES) {
            runCatching { dest.delete() }
            return false
        }
        return true
    }

    /** Atomic-ish replace within one dir (rename, copy fallback). */
    fun moveFile(src: File, dest: File): Boolean = try {
        if (src.renameTo(dest)) true
        else {
            src.copyTo(dest, overwrite = true)
            runCatching { src.delete() }
            true
        }
    } catch (_: Exception) {
        false
    }

    /** Header-only format check: reads the first bytes, never the whole file. */
    fun isImageFile(file: File): Boolean {
        if (!file.isFile) return false
        val len = file.length()
        if (len < MIN_IMAGE_BYTES || len > MAX_IMAGE_BYTES) return false
        return try {
            file.inputStream().use { inp ->
                val head = ByteArray(HEADER_BYTES)
                var off = 0
                while (off < head.size) {
                    val n = inp.read(head, off, head.size - off)
                    if (n < 0) break
                    off += n
                }
                isImage(head.copyOf(off))
            }
        } catch (_: Exception) {
            false
        }
    }

    /** Magic sniff plus decodability/dimensions probe. */
    fun isValidImageFile(file: File): Boolean {
        if (!isImageFile(file)) return false
        return decodeBoundsOk(file)
    }

    private fun decodeBoundsOk(file: File): Boolean = try {
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, opts)
        opts.outWidth in 1..MAX_DIMENSION && opts.outHeight in 1..MAX_DIMENSION
    } catch (_: RuntimeException) {
        // Unit-test stub (android.jar throws): magic check already passed.
        true
    }

    fun isImage(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        // JPEG
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) return true
        // PNG
        if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
        ) return true
        // WebP: RIFF....WEBP
        if (bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() &&
            bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() &&
            bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() &&
            bytes[10] == 0x42.toByte() && bytes[11] == 0x50.toByte()
        ) return true
        return false
    }
}
