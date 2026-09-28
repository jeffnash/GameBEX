package com.gamebex.client.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.gamebex.client.GameBexApp
import com.gamebex.client.R
import com.gamebex.client.net.AssistSession
import com.gamebex.client.ui.console.ConsoleColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * Browser verification for a challenged download: the user waits/taps
 * through the host's check in this WebView, then Continue hands the
 * clearance cookies (bound to job + source + revision + origin) to the
 * engine, which re-resolves fresh and retries with cookies. The session
 * URL is resolved HERE (never persisted: single-use), so the WebView's
 * challenge GETs can't burn the engine's.
 *
 * Deliberately the native browser model, not a full sandbox: Android
 * does not expose every redirect hop to shouldInterceptRequest, and
 * shouldOverrideUrlLoading excludes POST. Origin checks run at every
 * available callback; app-controlled HTTP keeps strict enforcement.
 */
class AssistActivity : ComponentActivity() {

    companion object {
        const val EXTRA_JOB_ID = "job_id"

        fun intent(context: Context, jobId: Long): Intent =
            Intent(context, AssistActivity::class.java)
                .putExtra(EXTRA_JOB_ID, jobId)

        /**
         * App-level browser session lock: CookieManager is process-global,
         * so only one verification session runs at a time. A second
         * request waits with a visible status instead of clearing the
         * first session's cookies.
         */
        internal val sessionMutex = Mutex()

        /** Normalized origin (`scheme://host`, default ports omitted), or null. Pure. */
        fun originOfUrl(url: String?): String? = try {
            val uri = java.net.URI(url ?: return null)
            val scheme = uri.scheme?.lowercase() ?: return null
            val host = uri.host?.lowercase() ?: return null
            val port = uri.port
            val default = if (scheme == "http") 80 else 443
            "$scheme://$host" + if (port < 0 || port == default) "" else ":$port"
        } catch (_: Exception) {
            null
        }

        /** Navigation policy: http(s) to a declared origin only. Pure. */
        fun navAllowed(url: String?, allowedOrigins: Set<String>): Boolean {
            if (url == null) return false
            val scheme = runCatching { java.net.URI(url).scheme?.lowercase() }.getOrNull()
            if (scheme != "http" && scheme != "https") return false
            return originOfUrl(url) in allowedOrigins
        }

        /** Subrequest policy: browser-local schemes pass; the rest need an allowed origin. Pure. */
        fun subrequestAllowed(url: String?, allowedOrigins: Set<String>): Boolean {
            val scheme = runCatching { java.net.URI(url ?: return false).scheme?.lowercase() }.getOrNull()
            if (scheme == "data" || scheme == "blob" || scheme == "about") return true
            return navAllowed(url, allowedOrigins)
        }

        /** Host list for the pre-verification domain notice. Pure. */
        fun displayHosts(allowedOrigins: Set<String>): List<String> =
            allowedOrigins.mapNotNull {
                runCatching { java.net.URI(it).host?.lowercase() }.getOrNull()
            }.distinct().sorted()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val jobId = intent.getLongExtra(EXTRA_JOB_ID, -1)
        setContent {
            AssistScreen(jobId = jobId, onDone = { finish() })
        }
    }
}

@Composable
private fun AssistScreen(jobId: Long, onDone: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = context.applicationContext as GameBexApp
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf<AssistSession?>(null) }
    var status by remember { mutableStateOf("Preparing verification…") }
    var failed by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var lockHeld by remember { mutableStateOf(false) }

    fun clearBrowserState() {
        CookieManager.getInstance().removeAllCookies(null)
        runCatching { android.webkit.WebStorage.getInstance().deleteAllData() }
    }

    androidx.compose.runtime.LaunchedEffect(jobId) {
        status = "Waiting for the other verification to finish…"
        AssistActivity.sessionMutex.lock()
        lockHeld = true
        try {
            status = "Preparing verification…"
            clearBrowserState()
            val s = withContext(Dispatchers.IO) {
                runCatching { app.engine.assistSession(jobId) }.getOrNull()
            }
            if (s == null) {
                failed = true
                status = "Couldn't prepare verification. The download may have moved on."
            } else {
                session = s
                status = "Complete any check below, then Continue."
            }
        } finally {
            if (session == null) {
                clearBrowserState()
                lockHeld = false
                AssistActivity.sessionMutex.unlock()
            }
        }
    }

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            webView?.destroy()
            webView = null
            clearBrowserState()
            if (lockHeld) {
                lockHeld = false
                AssistActivity.sessionMutex.unlock()
            }
        }
    }

    val blockedMsg = stringResource(R.string.assist_blocked_redirect)

    /** Hands clearance cookies to the engine, then wipes the WebView session. */
    suspend fun handOff(url: String?): Boolean {
        val s = session ?: return false
        if (jobId < 0 || url == null) return false
        // Recheck the top-level origin at handoff time.
        val origin = AssistActivity.originOfUrl(url)
        if (origin == null || origin !in s.allowedOrigins) return false
        val cookies = CookieManager.getInstance().getCookie(url)
        if (cookies.isNullOrBlank()) return false
        if (!app.engine.validateAssistHandoff(jobId)) {
            status = "This download moved on — close and retry from Downloads."
            return false
        }
        app.engine.provideAssist(jobId, cookies, origin)
        clearBrowserState()
        status = "Verified — resuming download."
        onDone()
        return true
    }

    fun finishWithCookies() {
        scope.launch {
            if (!handOff(webView?.url ?: session?.url)) {
                if (!failed) status = "No verification cookie yet — wait for the check to finish."
            }
        }
    }

    val s = session
    Column(Modifier.fillMaxSize().background(ConsoleColors.Background).padding(16.dp)) {
        Text("One quick check", color = ConsoleColors.TextPrimary, fontSize = 22.sp)
        Text(
            "This file host wants to see a browser once. Finish anything below, then Continue — the download resumes by itself.",
            color = ConsoleColors.TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        if (failed) {
            Text(status, color = ConsoleColors.Error, fontSize = 14.sp, modifier = Modifier.padding(vertical = 24.dp))
        } else if (s == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(status, color = ConsoleColors.TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
        } else {
            // Domains shown before entering verification.
            Text(
                "Verifying with: " + AssistActivity.displayHosts(s.allowedOrigins).joinToString(", "),
                color = ConsoleColors.TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp),
            )
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = s.assist.javascript
                        settings.domStorageEnabled = s.assist.domStorage
                        s.assist.userAgent?.let { settings.userAgentString = it }
                        settings.safeBrowsingEnabled = true
                        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        // Hardened challenge surface: no file/content access,
                        // first-party cookies only, declared-origin navigation.
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: android.webkit.WebResourceRequest,
                            ): Boolean {
                                val to = request.url.toString()
                                if (!AssistActivity.navAllowed(to, s.allowedOrigins)) {
                                    status = blockedMsg
                                    return true
                                }
                                return false
                            }

                            override fun shouldInterceptRequest(
                                view: WebView,
                                request: android.webkit.WebResourceRequest,
                            ): WebResourceResponse? {
                                val url = request.url.toString()
                                if (AssistActivity.subrequestAllowed(url, s.allowedOrigins)) return null
                                return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), null)
                            }

                            override fun onPageFinished(view: WebView, url: String) {
                                status = "Page loaded — if the check cleared, Continue."
                            }
                        }
                        setDownloadListener { url, _, _, _, _ ->
                            // Challenge passed and the host offered bytes to the
                            // WebView: hand cookies to the engine (it re-resolves).
                            scope.launch {
                                if (!handOff(url)) {
                                    status = "Almost — tap Continue."
                                }
                            }
                        }
                        val start = s.url
                        if (!AssistActivity.navAllowed(start, s.allowedOrigins)) {
                            failed = true
                            status = "The verification address is outside this source's declared origins."
                        } else {
                            loadUrl(start)
                        }
                        webView = this
                    }
                },
                onRelease = { it.destroy() },
                modifier = Modifier.weight(1f).fillMaxWidth()
                    .background(ConsoleColors.Surface, RoundedCornerShape(12.dp)),
            )
            Text(status, color = ConsoleColors.TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onDone, modifier = Modifier.weight(1f)) {
                Text("Not now", color = ConsoleColors.TextSecondary, fontSize = 15.sp)
            }
            Button(
                onClick = { finishWithCookies() },
                enabled = !failed && s != null,
                colors = ButtonDefaults.buttonColors(containerColor = ConsoleColors.Accent, contentColor = ConsoleColors.Background),
                modifier = Modifier.weight(1f),
            ) {
                Text("Continue", fontSize = 15.sp)
            }
        }
    }
}
