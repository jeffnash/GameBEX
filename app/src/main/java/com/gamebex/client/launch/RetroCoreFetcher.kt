package com.gamebex.client.launch
import com.gamebex.client.together.emu.InstalledCoreLedger

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.gamebex.client.MainActivity
import com.gamebex.client.net.HOST_UA
import com.gamebex.client.together.platform.TogetherRuntime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * RetroArch core auto-fetch. When a Play press targets a core the probe
 * proves missing, the core is fetched from the official Libretro buildbot
 * and handed to RetroArch's exported CoreSideloadActivity without a ROM.
 * Installation is successful only after the installed-core probe confirms
 * it; the caller then owns the managed game launch. Android permissions
 * allow no other app to copy into RA's private cores directory directly.
 *
 * Acquisition is serialized per core, streamed to disk under a byte cap
 * (never buffered in RAM), and validated (zip structure + size + ELF
 * header) before the atomic rename; cached cores must pass the same
 * validation (a length check alone trusts partials). Redirects are
 * followed same-host only.
 *
 * Cryptographic verification is trust-on-first-use ([CorePinStore]): the
 * buildbot publishes no hashes, signatures, or immutable builds (nightly
 * `latest/` only), so the first validated fetch of a
 * core pins its zip + .so SHA-256 and every later fetch or cached
 * sideload must match, else the fetch fails closed with Core Downloader
 * guidance. Residual risk, stated plainly: a compromised FIRST fetch
 * pins attacker bytes; users who need stronger provenance use RA's own
 * Core Downloader.
 */
object RetroCoreFetcher {
    private const val TAG = "GameBEXCore"
    const val RA_PKG = "com.retroarch.aarch64"
    private const val SIDELOAD_ACTIVITY = "com.retroarch.browser.debug.CoreSideloadActivity"
    private const val MIN_SO_BYTES = 100_000L
    /** Largest core package accepted (cores ship ~1–30 MB zipped). */
    const val MAX_CORE_ZIP_BYTES = 100L * 1024 * 1024
    private const val MAX_REDIRECTS = 5
    internal const val INSTALL_TIMEOUT_MS = 20_000L
    internal const val INSTALL_POLL_MS = 1_000L
    internal const val INSTALL_QUERY_TIMEOUT_MS = 1_000L
    const val INSTALL_SETUP_GUIDANCE = "RetroArch couldn't install the core. Open RetroArch once to finish its setup, then try again."

    /** One lock per core: concurrent launches for the same core queue. */
    private val coreLocks = ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()

    sealed interface Result {
        data class Fetched(val core: String) : Result
        data class Failed(val reason: String, val needsSetup: Boolean = false) : Result
    }

    /** Nightly arm64 core zip for a base core name (`mupen64plus_next_gles3`). */
    fun coreZipUrl(core: String): String =
        "https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/${core}_libretro_android.so.zip"

    /**
     * Entry holding the core .so: exact `<core>_libretro_android.so` first,
     * else the lone .so when the zip holds exactly one, else null.
     */
    fun pickSoEntry(names: List<String>, core: String): String? {
        val want = "${core}_libretro_android.so"
        names.firstOrNull { it.substringAfterLast("/") == want }?.let { return it }
        val sos = names.filter { it.endsWith(".so") }
        return sos.singleOrNull()
    }

    /** ELF magic (`\x7fELF`): a core that fails this is a partial/error page. */
    fun hasElfHeader(header: ByteArray): Boolean =
        header.size >= 4 &&
            header[0] == 0x7F.toByte() && header[1] == 0x45.toByte() &&
            header[2] == 0x4C.toByte() && header[3] == 0x46.toByte()

    /** Cached/staged core trust check: length gate plus binary identity. */
    fun isUsableCore(length: Long, header: ByteArray?): Boolean =
        length > MIN_SO_BYTES && header != null && hasElfHeader(header)

    /** Lowercase-hex SHA-256 of a file, or null when unreadable. */
    internal fun sha256Hex(file: File): String? {
        if (!file.isFile) return null
        return try {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { inp ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            null
        }
    }

    fun readHeader(file: File, n: Int = 16): ByteArray? {
        if (!file.isFile) return null
        return try {
            file.inputStream().use { inp ->
                val buf = ByteArray(n)
                var off = 0
                while (off < n) {
                    val r = inp.read(buf, off, n - off)
                    if (r < 0) break
                    off += r
                }
                buf.copyOf(off)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun isCachedUsable(file: File): Boolean =
        file.isFile && isUsableCore(file.length(), readHeader(file))

    sealed interface StageResult {
        data class Staged(val file: File) : StageResult
        data class Failed(val reason: String) : StageResult
    }

    suspend fun stageCore(ctx: Context, core: String, onProgress: ((Long, Long) -> Unit)? = null): StageResult =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            // Top-level shared dir: RA cannot read our app-specific dir.
            val stageDir = GraphicsTuner.coreStageDir().apply { mkdirs() }
            val soName = "${core}_libretro_android.so"
            val staged = File(stageDir, soName)
            val pins = CorePinStore.inDir(File(ctx.filesDir, "corepins"))
            val updaterHint = "Install the ${com.gamebex.client.data.CoreNames.display(core)} core in RetroArch → Online Updater → Core Downloader."
            try {
                currentCoroutineContext().ensureActive()
                val lock = coreLocks.getOrPut(core) { kotlinx.coroutines.sync.Mutex() }
                lock.withLock {
                    currentCoroutineContext().ensureActive()
                    // Cached sideloads re-verify against the pin: the stage dir
                    // is world-readable by design, so a swap must fail closed.
                    val pin = pins.get(core)
                    if (isCachedUsable(staged) && pin != null && sha256Hex(staged) != pin.so) {
                        Log.w(TAG, "staged $core fails pin check; refetching")
                        runCatching { staged.delete() }
                    }
                    if (!isCachedUsable(staged)) {
                        runCatching { staged.delete() } // never trust a partial
                        val tmp = File.createTempFile("core", ".zip", ctx.cacheDir)
                        try {
                            if (!downloadTo(coreZipUrl(core), tmp, onProgress)) return@withContext StageResult.Failed("Couldn't reach the core server. $updaterHint")
                            // TOFU: a pinned core must byte-match its pin;
                            // anything else fails closed, never sideloads.
                            val zipHash = sha256Hex(tmp)
                            if (pin != null && zipHash != pin.zip) {
                                Log.w(TAG, "$core zip fails pin check")
                                return@withContext StageResult.Failed("Core download changed unexpectedly — not installed. $updaterHint")
                            }
                            ZipFile(tmp).use { zip ->
                                val pick = pickSoEntry(zip.entries().asSequence().map { it.name }.toList(), core)
                                    ?: return@withContext StageResult.Failed("Core package had no usable file. Install the $core core in RetroArch → Online Updater → Core Downloader.")
                                // Stage aside, validate, then rename: a failure
                                // must never leave a trusted-looking partial.
                                val part = File(stageDir, "$soName.part")
                                try {
                                    zip.getInputStream(zip.getEntry(pick)).use { inp -> part.outputStream().use { inp.copyTo(it) } }
                                    if (!isCachedUsable(part)) return@withContext StageResult.Failed("Core download was too small to be valid. Install the $core core in RetroArch → Online Updater → Core Downloader.")
                                    if (!part.renameTo(staged)) part.copyTo(staged, overwrite = true)
                                    if (!isCachedUsable(staged)) { runCatching { staged.delete() }; return@withContext StageResult.Failed("Core download was too small to be valid. $updaterHint") }
                                    // First validated fetch pins the hashes;
                                    // later fetches must match (checked above).
                                    if (pin == null) {
                                        val soHash = sha256Hex(staged)
                                        if (zipHash != null && soHash != null) pins.put(core, CorePin(zipHash, soHash))
                                    }
                                } finally { runCatching { part.delete() } }
                            }
                        } finally { runCatching { tmp.delete() } }
                    }
                }
                currentCoroutineContext().ensureActive()
                // Last look before handing the path to RetroArch: re-verify
                // size/ELF/pin so a swap inside the stage dir fails closed.
                val finalPin = pins.get(core)
                if (!isCachedUsable(staged) || (finalPin != null && sha256Hex(staged) != finalPin.so)) {
                    Log.w(TAG, "staged $core failed pre-sideload check")
                    runCatching { staged.delete() }
                    return@withContext StageResult.Failed("Core file changed before launch — not started. $updaterHint")
                }
                currentCoroutineContext().ensureActive()
                StageResult.Staged(staged)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "core fetch failed", e)
                StageResult.Failed("Couldn't fetch the $core core. Install it in RetroArch → Online Updater → Core Downloader.")
            }
        }

    /** CoreSideloadActivity must not boot a game before our managed launch. */
    internal fun applySideloadExtras(intent: Intent, so: File) {
        intent.putExtra("LIBRETRO", so.absolutePath)
    }

    suspend fun ensureCore(ctx: Context, core: String, romFile: File?, onProgress: ((Long, Long) -> Unit)? = null): Result =
        withContext(Dispatchers.IO) {
            try {
                currentCoroutineContext().ensureActive()
                // Keep the solo launch's ROM gate, but never send it to sideload.
                if (romFile == null || !romFile.isFile) {
                    return@withContext Result.Failed("ROM file is no longer available.")
                }
                val staged = when (val result = stageCore(ctx, core, onProgress)) {
                    is StageResult.Staged -> result.file
                    is StageResult.Failed -> return@withContext Result.Failed(result.reason)
                }
                installCore(ctx, core, staged, onProgress)
            } catch (e: CancellationException) {
                throw e
            }
        }

    /**
     * Poll starts at handoff; each query gets at most one second and query
     * starts are one second apart. Neither silence nor an empty reply is
     * confirmation. The injected clock/probe/record seam also runs on the JVM.
     */
    internal suspend fun verifyCoreInstall(
        core: String,
        nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
        wait: suspend (Long) -> Unit = { delay(it) },
        probe: suspend (timeoutMs: Long) -> Set<String>?,
        onConfirmed: () -> Unit = {},
    ): Boolean {
        val confirmed = withTimeoutOrNull(INSTALL_TIMEOUT_MS) {
            val started = nowMs()
            while (true) {
                currentCoroutineContext().ensureActive()
                val remaining = INSTALL_TIMEOUT_MS - (nowMs() - started)
                if (remaining <= 0L) return@withTimeoutOrNull false
                val queryStarted = nowMs()
                val queryBudget = minOf(INSTALL_QUERY_TIMEOUT_MS, remaining)
                val cores = withTimeoutOrNull(queryBudget) { probe(queryBudget) }
                if (nowMs() - started >= INSTALL_TIMEOUT_MS) return@withTimeoutOrNull false
                if (cores?.any { it.equals(core, ignoreCase = true) } == true) {
                    return@withTimeoutOrNull true
                }
                val left = INSTALL_TIMEOUT_MS - (nowMs() - started)
                if (left <= 0L) return@withTimeoutOrNull false
                val untilNext = INSTALL_POLL_MS - (nowMs() - queryStarted)
                if (untilNext > 0L) wait(minOf(untilNext, left))
            }
            @Suppress("UNREACHABLE_CODE")
            false
        } == true
        // This callback is the only publication boundary: never on timeout,
        // missing core or cancellation, including cancellation after a reply.
        currentCoroutineContext().ensureActive()
        if (confirmed) onConfirmed()
        return confirmed
    }

    /** Verified install only: no ROM, no optimistic log/ledger on handoff. */
    suspend fun installCore(
        ctx: Context,
        core: String,
        so: File,
        onProgress: ((Long, Long) -> Unit)? = null,
    ): Result = withContext(Dispatchers.IO) {
        try {
            currentCoroutineContext().ensureActive()
            if (RetroCoreProbe.setupPathsMissing(ctx)) {
                return@withContext Result.Failed(INSTALL_SETUP_GUIDANCE, needsSetup = true)
            }
            val foregroundTask = withContext(Dispatchers.Main.immediate) { existingMainTask(ctx) }
            if (foregroundTask == null) Log.w(TAG, "no existing GameBEX progress task to foreground")
            onProgress?.invoke(-1L, -1L)
            val handedOff = withContext(Dispatchers.Main.immediate) {
                currentCoroutineContext().ensureActive()
                sideloadCoreOnly(ctx, so)
            }
            if (!handedOff) {
                return@withContext Result.Failed("RetroArch blocked the core install. Install the $core core in RetroArch → Online Updater → Core Downloader.")
            }
            val installed = try {
                restoreAfterSideloadStart(
                    waitForBackground = { TogetherRuntime.foreground.first { !it } },
                    restore = { restoreMainTask(foregroundTask) },
                )
                verifyCoreInstall(
                    core = core,
                    probe = { timeout ->
                        val cores = RetroCoreProbe.queryInstalledCores(ctx, timeout)
                        restoreMainTask(foregroundTask)
                        cores
                    },
                    onConfirmed = { InstalledCoreLedger.forApp(ctx).record(core, so) },
                )
            } finally {
                withContext(NonCancellable) { restoreMainTask(foregroundTask) }
            }
            if (!installed) return@withContext Result.Failed(INSTALL_SETUP_GUIDANCE, needsSetup = true)
            Log.i(TAG, "confirmed installed core $core")
            Result.Fetched(core)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "core install failed", e)
            Result.Failed("Couldn't install the $core core. Install it in RetroArch → Online Updater → Core Downloader.")
        }
    }

    internal suspend fun restoreAfterSideloadStart(
        waitForBackground: suspend () -> Unit,
        wait: suspend (Long) -> Unit = { delay(it) },
        restore: suspend () -> Unit,
    ) {
        withTimeoutOrNull(2_000L) { waitForBackground() }
        wait(200L)
        currentCoroutineContext().ensureActive()
        restore()
    }

    private suspend fun restoreMainTask(task: ActivityManager.AppTask?) = withContext(Dispatchers.Main.immediate) {
        runCatching { task?.moveToFront() }
            .onFailure { Log.w(TAG, "couldn't return existing GameBEX task to foreground", it) }
        Unit
    }

    private fun existingMainTask(ctx: Context): ActivityManager.AppTask? = try {
        ctx.getSystemService(ActivityManager::class.java)?.appTasks?.firstOrNull { task ->
            task.taskInfo.topActivity == ComponentName(ctx.packageName, MainActivity::class.java.name)
        }
    } catch (_: Exception) {
        null
    }

    /** A successful handoff is NOT installation; [installCore] verifies it. */
    private fun sideloadCoreOnly(ctx: Context, so: File): Boolean = try {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName(RA_PKG, SIDELOAD_ACTIVITY)
            applySideloadExtras(this, so)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
        true
    } catch (e: CancellationException) { throw e }
      catch (_: android.content.ActivityNotFoundException) { false }
      catch (_: SecurityException) { false }

    fun isAarch64Elf(header: ByteArray): Boolean {
        if (!hasElfHeader(header) || header.size < 20) return false
        val machine = (header[18].toInt() and 0xFF) or ((header[19].toInt() and 0xFF) shl 8)
        return machine == 0xB7
    }

    /** Host of a URL, or null when unparseable. Pure (redirect policy). */
    fun urlHost(url: String): String? =
        runCatching { java.net.URI(url).host?.lowercase() }.getOrNull()

    /**
     * Copies [input] to [out], aborting with null once more than [cap]
     * bytes arrive. Returns the byte count on success. Constant 64 KiB
     * buffer: peak heap is O(buffer), never O(package).
     */
    internal fun copyCapped(
        input: java.io.InputStream,
        out: java.io.OutputStream,
        cap: Long,
        onRead: ((Long) -> Unit)? = null,
    ): Long? {
        val buf = ByteArray(64 * 1024)
        var read = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            read += n
            if (read > cap) return null
            out.write(buf, 0, n)
            onRead?.invoke(read)
        }
        return read
    }

    /**
     * Streams [url] to [dest] under [cap] bytes, following redirects
     * same-host only. False on any failure (caller deletes the partial).
     */
    internal fun downloadTo(
        url: String,
        dest: File,
        onProgress: ((Long, Long) -> Unit)?,
        cap: Long = MAX_CORE_ZIP_BYTES,
    ): Boolean {
        val startHost = urlHost(url) ?: return false
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
        var current = url
        repeat(MAX_REDIRECTS + 1) {
            val req = okhttp3.Request.Builder().url(current)
                .header("User-Agent", HOST_UA).build()
            client.newCall(req).execute().use { resp ->
                if (resp.isRedirect) {
                    val loc = resp.header("Location") ?: return false
                    val next = resp.request.url.resolve(loc)?.toString() ?: return false
                    if (urlHost(next) != startHost) {
                        Log.w(TAG, "core redirect off-host blocked: $next")
                        return false
                    }
                    current = next
                    return@repeat
                }
                if (!resp.isSuccessful) return false
                val body = resp.body ?: return false
                val total = body.contentLength().coerceAtLeast(0L)
                if (total > cap) {
                    Log.w(TAG, "core package too large: $total")
                    return false
                }
                try {
                    dest.outputStream().use { out ->
                        body.byteStream().use { inp ->
                            copyCapped(inp, out, cap) { read ->
                                onProgress?.invoke(read, total)
                            }
                        } ?: run {
                            Log.w(TAG, "core package exceeded cap mid-stream")
                            return false
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    return false
                }
                return dest.length() > 0
            }
        }
        return false
    }
}
