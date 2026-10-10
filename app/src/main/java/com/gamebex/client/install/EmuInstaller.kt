package com.gamebex.client.install

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.gamebex.client.data.EmuCatalog
import com.gamebex.client.data.local.LibraryDb
import com.gamebex.client.net.hostHttpClient
import com.gamebex.client.net.HOST_UA
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request
import kotlin.coroutines.coroutineContext

/** Per-package install/update pipeline state. */
sealed interface InstallState {
    data object Idle : InstallState
    data class Downloading(val read: Long, val total: Long?) : InstallState
    data object Validating : InstallState
    /** Unknown-sources permission missing: UI shows the guided fix flow. */
    data object NeedsUnknownSources : InstallState
    /** Session committed; Android's mandatory confirmation is now showing. */
    data object AwaitingApproval : InstallState
    data object Installing : InstallState
    data class Done(val versionName: String?, val versionCode: Long) : InstallState
    data class Failed(val reason: String, val retryable: Boolean = true) : InstallState
    /** Installed copy is signed by someone else: never auto-resolve. */
    data class SignerConflict(
        val installedVersion: String?,
        val installedSigner: String,
        val pinnedSigner: String,
    ) : InstallState
}

/**
 * Emulator auto-install service. "Auto-install" = automatic
 * acquisition/validation/staging/continuation around Android's MANDATORY user
 * confirmation — never silent. Only registry-PINNED releases install; anything
 * else fails closed with an honest reason.
 */
class EmuInstaller(private val appContext: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = appContext.getSharedPreferences("emu_installer", Context.MODE_PRIVATE)
    private val db by lazy { LibraryDb.get(appContext) }

    private val _states = MutableStateFlow<Map<String, InstallState>>(emptyMap())
    val states: StateFlow<Map<String, InstallState>> = _states

    /** Upstream discovery cache: pkg -> "checkedMs|label". Display-only. */
    private val _upstream = MutableStateFlow<Map<String, String>>(emptyMap())
    val upstream: StateFlow<Map<String, String>> = _upstream

    private val jobs = mutableMapOf<String, Job>()
    /** Retained so cancel() can interrupt the blocking HTTP call. */
    private val calls = mutableMapOf<String, Call>()
    private var pendingAfterPermission: String? = null

    fun stateOf(pkg: String): InstallState = _states.value[pkg] ?: InstallState.Idle

    private fun set(pkg: String, s: InstallState) {
        if (s !is InstallState.Downloading) Log.d(TAG, "state $pkg -> $s")
        _states.value = _states.value + (pkg to s)
    }

    fun canInstallUnknown(): Boolean =
        appContext.packageManager.canRequestPackageInstalls()

    fun unknownSourcesIntent(): Intent = Intent(
        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        android.net.Uri.parse("package:${appContext.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Call from UI onResume: continue a permission-gated install. */
    fun onResumed() {
        val pkg = pendingAfterPermission ?: return
        if (!canInstallUnknown()) return
        pendingAfterPermission = null
        start(pkg)
    }

    fun retry(pkg: String) = start(pkg)

    fun cancel(pkg: String) {
        // Interrupt the blocking HTTP call; the pipeline's cancellation
        // checks then stop the copy loop before any commit.
        calls.remove(pkg)?.cancel()
        // Keep the job entry until it completes so start() admits no
        // replacement while the old writer is still winding down.
        jobs[pkg]?.cancel()
        try {
            val pi = appContext.packageManager.packageInstaller
            pi.mySessions.filter { it.appPackageName == pkg }.forEach {
                runCatching { pi.abandonSession(it.sessionId) }
            }
        } catch (_: Exception) { }
        clearActive(pkg)
        persistSession(pkg, -1)
        set(pkg, InstallState.Idle)
    }

    fun dismiss(pkg: String) {
        if (stateOf(pkg) is InstallState.Done || stateOf(pkg) is InstallState.Failed) {
            set(pkg, InstallState.Idle)
        }
    }

    fun start(pkg: String) {
        Log.d(TAG, "start($pkg) tap received")
        val entry = EmuCatalog.byPkg[pkg] ?: run {
            Log.w(TAG, "start($pkg): unknown package"); return
        }
        if (entry.origin is EmuCatalog.Origin.Guided) return // UI opens links instead.
        // Refuses taps while a job runs, while a cancelled job winds down,
        // and while a committed session awaits its terminal result.
        if (!shouldAdmitStart(jobs[pkg]?.isCompleted == false, stateOf(pkg))) {
            Log.d(TAG, "start($pkg): attempt already in flight, ignoring")
            return
        }
        if (!canInstallUnknown()) {
            pendingAfterPermission = pkg
            set(pkg, InstallState.NeedsUnknownSources)
            return
        }
        val job = scope.launch { runPipeline(pkg) }
        job.invokeOnCompletion { if (jobs[pkg] === job) jobs.remove(pkg) }
        jobs[pkg] = job
    }

    private suspend fun runPipeline(pkg: String) = withContext(Dispatchers.IO) {
        val entry = EmuCatalog.byPkg[pkg] ?: return@withContext
        val url = when (val o = entry.origin) {
            is EmuCatalog.Origin.FDroid -> o.url
            is EmuCatalog.Origin.Direct -> o.url
            is EmuCatalog.Origin.Guided -> return@withContext
        }
        val pinnedSha = entry.pinnedSha256 ?: run {
            set(pkg, InstallState.Failed("No reviewed build pinned for ${entry.emu.label}.", false))
            return@withContext
        }
        try {
            ensureActive()
            val file = localCopy(pkg, url, pinnedSha) ?: download(pkg, url)
            ensureActive()
            set(pkg, InstallState.Validating)
            val problem = validate(pkg, file, entry)
            if (problem != null) {
                file.delete()
                if (problem.conflict != null) set(pkg, problem.conflict) else {
                    set(pkg, InstallState.Failed(problem.reason, problem.retryable))
                }
                clearActive(pkg)
                return@withContext
            }
            ensureActive()
            persistActive(pkg)
            commitSession(pkg, file)
            // Terminal state arrives via InstallStatusReceiver -> onSessionStatus.
        } catch (e: CancellationException) {
            // Never commit, never overwrite the canceller's state; just
            // drop our own marker and the partial file. Our stale session
            // callbacks (if any) are ignored by onSessionStatus's guard.
            clearActive(pkg)
            File(appContext.cacheDir, "emu-apks/$pkg.apk").delete()
            throw e
        } catch (e: java.io.IOException) {
            // A cancelled Call surfaces as IOException: yield to the
            // cancellation path instead of overwriting the Idle state.
            coroutineContext.ensureActive()
            Log.w(TAG, "download failed for $pkg", e)
            set(pkg, InstallState.Failed(com.gamebex.client.ui.UserErrors.emuDownloadFailed()))
            clearActive(pkg)
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            Log.w(TAG, "install failed for $pkg", e)
            set(pkg, InstallState.Failed(com.gamebex.client.ui.UserErrors.emuInstallFailed()))
            clearActive(pkg)
        }
    }

    /** The pinned APK from `GameBEX/apks/`, copied into the cache (validation may delete it). */
    private fun localCopy(pkg: String, url: String, pinnedSha: String): File? {
        val source = runCatching { LocalApks.find(LocalApks.dir(), url, pinnedSha) { sha256Of(it) } }.getOrNull() ?: return null
        val out = File(File(appContext.cacheDir, "emu-apks").apply { mkdirs() }, "$pkg.apk")
        set(pkg, InstallState.Downloading(0, source.length()))
        return runCatching { source.copyTo(out, overwrite = true) }
            .onSuccess { Log.i(TAG, "using the local copy ${source.name} for $pkg") }
            .getOrNull()
    }

    private suspend fun download(pkg: String, url: String): File {
        val dir = File(appContext.cacheDir, "emu-apks").apply { mkdirs() }
        val out = File(dir, "$pkg.apk")
        out.delete()
        val client = hostHttpClient()
        val req = Request.Builder().url(url).header("User-Agent", HOST_UA).build()
        val call = client.newCall(req)
        val ctx = coroutineContext
        calls[pkg] = call
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
                val body = resp.body ?: throw java.io.IOException("empty body")
                val total = body.contentLength().takeIf { it > 0 }
                var read = 0L
                var lastEmit = 0L
                set(pkg, InstallState.Downloading(0, total))
                body.byteStream().use { input ->
                    out.outputStream().use { output ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            ctx.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            read += n
                            // Throttle progress emissions: ~4/sec max, always emit completion.
                            val now = System.currentTimeMillis()
                            if (now - lastEmit >= 250) {
                                lastEmit = now
                                set(pkg, InstallState.Downloading(read, total))
                            }
                        }
                        set(pkg, InstallState.Downloading(read, total))
                    }
                }
            }
        } finally {
            if (calls[pkg] === call) calls.remove(pkg)
        }
        ctx.ensureActive()
        return out
    }

    private data class ValidationProblem(
        val reason: String,
        val retryable: Boolean = false,
        val conflict: InstallState.SignerConflict? = null,
    )

    private fun validate(pkg: String, file: File, entry: EmuCatalog.Entry): ValidationProblem? {
        if (sha256Of(file) != entry.pinnedSha256!!.lowercase()) {
            return ValidationProblem(
                "Downloaded file doesn't match the reviewed build (upstream " +
                    "may have rebuilt it). Refusing to install.",
            )
        }
        val pm = appContext.packageManager
        val info = pm.getPackageArchiveInfo(
            file.absolutePath,
            if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES,
        ) ?: return ValidationProblem("Downloaded file is not a valid APK.")
        if (info.packageName != pkg) {
            return ValidationProblem("APK is for ${info.packageName}, expected $pkg. Refusing.")
        }
        val archiveVc = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        if (archiveVc != entry.pinnedVersionCode) {
            return ValidationProblem(
                "APK is version $archiveVc, reviewed pin is ${entry.pinnedVersionCode}. Refusing.",
            )
        }
        if (!abiOk(file)) {
            return ValidationProblem("APK has no arm64 code for this device. Refusing.")
        }
        val archiveSigner = archiveSignerSha256(pm, file)
            ?: return ValidationProblem("APK signature unreadable. Refusing.")
        if (archiveSigner != entry.pinnedSignerSha256!!.lowercase()) {
            return ValidationProblem(
                "APK signer doesn't match the reviewed build. Refusing to install.",
            )
        }
        // Signer continuity vs installed copy (never auto-resolve a mismatch).
        try {
            val installed = if (Build.VERSION.SDK_INT >= 28) {
                pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION") pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
            }
            val installedSigner = installedSignerSha256(installed)
            if (installedSigner != null && installedSigner != archiveSigner) {
                return ValidationProblem(
                    "signer conflict", false,
                    InstallState.SignerConflict(
                        installed.versionName,
                        installedSigner.short(),
                        archiveSigner.short(),
                    ),
                )
            }
        } catch (_: PackageManager.NameNotFoundException) { /* fresh install */ }
        return null
    }

    private suspend fun commitSession(pkg: String, file: File) {
        val ctx = coroutineContext
        ctx.ensureActive()
        val pi = appContext.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(pkg)
        val sessionId = pi.createSession(params)
        try {
            persistSession(pkg, sessionId)
            set(pkg, InstallState.Installing)
            pi.openSession(sessionId).use { session ->
                file.inputStream().use { input ->
                    session.openWrite("apk", 0, file.length()).use { output ->
                        val buf = ByteArray(256 * 1024)
                        var sinceCheck = 0
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            sinceCheck += n
                            if (sinceCheck >= 1024 * 1024) {
                                sinceCheck = 0
                                ctx.ensureActive()
                            }
                        }
                        session.fsync(output)
                    }
                }
                val intent = Intent(appContext, InstallStatusReceiver::class.java).apply {
                    action = InstallStatusReceiver.ACTION_STATUS
                    putExtra(InstallStatusReceiver.EXTRA_PKG, pkg)
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pi2 = PendingIntent.getBroadcast(
                    appContext, sessionId, intent, flags,
                )
                ctx.ensureActive()
                set(pkg, InstallState.AwaitingApproval)
                session.commit(pi2.intentSender)
            }
        } catch (e: CancellationException) {
            runCatching { pi.abandonSession(sessionId) }
            persistSession(pkg, -1)
            throw e
        }
    }

    /** Called by InstallStatusReceiver for our session results. */
    fun onSessionStatus(pkg: String, status: Int, message: String?, action: Intent?) {
        // Ignore callbacks for superseded sessions: only a package with a
        // live committed session (or a persisted one after process death)
        // accepts results. A cancel/retry in between voids earlier commits.
        if (!shouldAcceptCallback(stateOf(pkg), pkg in activePkgs())) {
            Log.d(TAG, "ignoring stale session callback for $pkg (state=${stateOf(pkg)})")
            return
        }
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                set(pkg, InstallState.AwaitingApproval)
                try {
                    action?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    appContext.startActivity(action)
                } catch (e: Exception) {
                    Log.w(TAG, "user-action intent failed", e)
                    set(pkg, InstallState.Failed("System installer didn't open. Tap retry."))
                    clearActive(pkg)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                clearActive(pkg)
                persistSession(pkg, -1)
                scope.launch(Dispatchers.IO) {
                    val pm = appContext.packageManager
                    val installed = try {
                        if (Build.VERSION.SDK_INT >= 28) pm.getPackageInfo(pkg, 0)
                        else @Suppress("DEPRECATION") pm.getPackageInfo(pkg, 0)
                    } catch (_: PackageManager.NameNotFoundException) { null }
                    if (installed == null) {
                        set(pkg, InstallState.Failed("Installer reported success but the app is missing."))
                        return@launch
                    }
                    val vc = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
                    runCatching {
                        db.emuStates().upsert(
                            com.gamebex.client.data.local.EmuState(
                                pkg = pkg, versionCode = vc,
                                versionName = installed.versionName,
                                source = "host", updatedAtMs = System.currentTimeMillis(),
                            ),
                        )
                    }
                    File(appContext.cacheDir, "emu-apks/$pkg.apk").delete()
                    set(pkg, InstallState.Done(installed.versionName, vc))
                }
            }
            else -> {
                clearActive(pkg)
                persistSession(pkg, -1)
                val entry = EmuCatalog.byPkg[pkg]
                val incompatible = message?.contains("UPDATE_INCOMPATIBLE", true) == true ||
                    message?.contains("signatures do not match", true) == true
                if (incompatible && entry?.pinnedSignerSha256 != null) {
                    val installedSigner = try {
                        val i = if (Build.VERSION.SDK_INT >= 28) {
                            pm2().getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
                        } else {
                            @Suppress("DEPRECATION") pm2().getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
                        }
                        installedSignerSha256(i) ?: "unknown"
                    } catch (_: Exception) { "unknown" }
                    set(pkg, InstallState.SignerConflict(null, installedSigner.short(), entry.pinnedSignerSha256.short()))
                } else {
                    set(pkg, InstallState.Failed("System installer failed${message?.let { ": $it" } ?: "."}"))
                }
            }
        }
    }

    private fun pm2(): PackageManager = appContext.packageManager

    /** Update discovery (display-only, 24h cache). Never offers installs. */
    fun refreshUpstream() {
        scope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val out = mutableMapOf<String, String>()
            for (e in EmuCatalog.entries) {
                val cached = prefs.getString("up_${e.emu.pkg}", null)?.split("|")
                if (cached != null && cached.size == 2 && now - (cached[0].toLongOrNull() ?: 0) < 24 * 3600_000L) {
                    out[e.emu.pkg] = cached[1]
                    continue
                }
                val label = when (val u = e.updateCheck) {
                    is EmuCatalog.UpdateCheck.Github -> fetchGithubTag(u.apiLatest, e.pinnedVersionName)
                    is EmuCatalog.UpdateCheck.FDroidApi -> fetchFDroidSuggested(u.pkg, e.pinnedVersionCode)
                    EmuCatalog.UpdateCheck.None -> null
                } ?: continue
                prefs.edit().putString("up_${e.emu.pkg}", "$now|$label").apply()
                out[e.emu.pkg] = label
            }
            _upstream.value = out
        }
    }

    private fun fetchGithubTag(api: String, pinnedName: String?): String? = try {
        hostHttpClient().newCall(Request.Builder().url(api).build()).execute().use { r ->
            if (!r.isSuccessful) return null
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(r.body!!.string())
                as? kotlinx.serialization.json.JsonObject ?: return null
            val tag = (obj["tag_name"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                ?: return null
            val matches = pinnedName != null &&
                (pinnedName.contains(tag) || tag.contains(pinnedName.substringBefore(" ")))
            "upstream $tag " + if (matches) "(matches reviewed pin)" else "(unreviewed)"
        }
    } catch (_: Exception) { null }

    private fun fetchFDroidSuggested(pkg: String, pinnedVc: Long): String? = try {
        val url = "https://f-droid.org/api/v1/packages/$pkg"
        hostHttpClient().newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) return null
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(r.body!!.string())
                as? kotlinx.serialization.json.JsonObject ?: return null
            val vc = (obj["suggestedVersionCode"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                ?: return null
            "f-droid build $vc " + if (vc.toLongOrNull() == pinnedVc) "(matches reviewed pin)" else "(unreviewed)"
        }
    } catch (_: Exception) { null }

    /** Process-death recovery: reconcile each persisted active install. */
    fun reconcileAfterLaunch() {
        val pkgs = activePkgs().toList()
        if (pkgs.isEmpty()) return
        for (pkg in pkgs) {
            val sessionId = prefs.getInt("active_session_$pkg", -1)
            if (sessionId >= 0) {
                try {
                    appContext.packageManager.packageInstaller.mySessions
                        .firstOrNull { it.sessionId == sessionId }?.let {
                            // Session still open but orphaned: abandon, ask for retry.
                            runCatching { appContext.packageManager.packageInstaller.abandonSession(sessionId) }
                        }
                } catch (_: Exception) { }
            }
            clearActive(pkg)
            persistSession(pkg, -1)
            // If the app got installed while we were dead, status receiver already
            // persisted emu_state; surface Done, else ask for retry.
            scope.launch(Dispatchers.IO) {
                val installed = try {
                    appContext.packageManager.getPackageInfo(pkg, 0)
                } catch (_: PackageManager.NameNotFoundException) { null }
                val entry = EmuCatalog.byPkg[pkg]
                if (installed != null && entry != null) {
                    val vc = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
                    if (vc == entry.pinnedVersionCode) {
                        set(pkg, InstallState.Done(installed.versionName, vc))
                        return@launch
                    }
                }
                set(pkg, InstallState.Failed("Interrupted. Tap retry to continue."))
            }
        }
    }

    fun installedInfo(pkg: String): Triple<Boolean, String?, Long> {
        return try {
            val i = appContext.packageManager.getPackageInfo(pkg, 0)
            val vc = if (Build.VERSION.SDK_INT >= 28) i.longVersionCode else i.versionCode.toLong()
            Triple(true, i.versionName, vc)
        } catch (_: PackageManager.NameNotFoundException) {
            Triple(false, null, 0)
        }
    }

    fun shutdown() {
        calls.values.forEach { runCatching { it.cancel() } }
        calls.clear()
        scope.cancel()
    }

    /** Mark one package active; concurrent packages each keep their own marker. */
    private fun persistActive(pkg: String) {
        val set = prefs.getStringSet(KEY_ACTIVE_PKGS, emptySet()).orEmpty().toMutableSet()
        set.add(pkg)
        prefs.edit().putStringSet(KEY_ACTIVE_PKGS, set).apply()
    }

    private fun clearActive(pkg: String) {
        val set = prefs.getStringSet(KEY_ACTIVE_PKGS, emptySet()).orEmpty().toMutableSet()
        prefs.edit().putStringSet(KEY_ACTIVE_PKGS, (set - pkg).toSet()).apply()
    }

    private fun activePkgs(): Set<String> =
        prefs.getStringSet(KEY_ACTIVE_PKGS, emptySet()).orEmpty().toSet()

    private fun persistSession(pkg: String, sessionId: Int) {
        prefs.edit().putInt("active_session_$pkg", sessionId).apply()
    }

    companion object {
        private const val TAG = "GameBEXInstall"
        private const val KEY_ACTIVE_PKGS = "active_pkgs"

        /**
         * Pure attempt policy: admit a tap only when no job for the package
         * is still winding down and no committed session awaits its result.
         */
        fun shouldAdmitStart(jobIncomplete: Boolean, state: InstallState): Boolean {
            if (jobIncomplete) return false
            return state !is InstallState.Installing && state !is InstallState.AwaitingApproval
        }

        /**
         * Pure callback policy: accept a session result only for a package
         * with a live committed session, or a persisted one (process died
         * between commit and callback). Anything else is a superseded
         * session's stale callback and must not move state.
         */
        fun shouldAcceptCallback(state: InstallState, persistedActive: Boolean): Boolean =
            state is InstallState.Installing ||
                state is InstallState.AwaitingApproval ||
                persistedActive

        fun sha256Of(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        private fun abiOk(file: File): Boolean {
            ZipFile(file).use { zip ->
                val libs = zip.entries().asSequence()
                    .map { it.name }
                    .filter { it.startsWith("lib/") && it.endsWith(".so") }
                    .toList()
                if (libs.isEmpty()) return true // pure-Java APK (e.g. J2ME Loader)
                return libs.any { it.startsWith("lib/arm64-v8a/") }
            }
        }

        private fun archiveSignerSha256(pm: PackageManager, file: File): String? {
            val sig = if (Build.VERSION.SDK_INT >= 28) {
                val i = pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
                    ?: return null
                i.signingInfo?.apkContentsSigners?.firstOrNull()
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNATURES)
                    ?.signatures?.firstOrNull()
            } ?: return null
            return sha256Hex(sig.toByteArray())
        }

        private fun installedSignerSha256(info: android.content.pm.PackageInfo): String? {
            val sig = if (Build.VERSION.SDK_INT >= 28) {
                info.signingInfo?.apkContentsSigners?.firstOrNull()
            } else {
                @Suppress("DEPRECATION") info.signatures?.firstOrNull()
            } ?: return null
            return sha256Hex(sig.toByteArray())
        }

        private fun sha256Hex(bytes: ByteArray): String {
            val md = MessageDigest.getInstance("SHA-256")
            return md.digest(bytes).joinToString("") { "%02x".format(it) }
        }

        private fun String.short(): String = take(16) + "…"
    }
}
