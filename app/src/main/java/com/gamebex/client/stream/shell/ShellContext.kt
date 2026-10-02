package com.gamebex.client.stream.shell

import android.app.Application
import android.app.Instrumentation
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.os.Process
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * A Context that speaks as the shell package, as scrcpy's FakeContext
 * does (scrcpy v4.1, server/src/main/java/com/genymobile/scrcpy/FakeContext.java):
 * audio and app-ops checks compare the caller's package with its uid, and the
 * shell uid owns com.android.shell, not com.gamebex.client or "android".
 */
class ShellContext private constructor(base: Context) : ContextWrapper(base) {
    override fun getPackageName(): String = SHELL_PACKAGE

    override fun getOpPackageName(): String = SHELL_PACKAGE

    @RequiresApi(31)
    override fun getAttributionSource(): AttributionSource =
        AttributionSource.Builder(Process.myUid()).setPackageName(SHELL_PACKAGE).build()

    override fun getApplicationContext(): Context = this

    companion object {
        const val SHELL_PACKAGE: String = "com.android.shell"
        private const val TAG = "GameBEXStream"
        @Volatile private var instance: ShellContext? = null

        /** The process-wide shell context, or null when this process has no system context. */
        fun get(): ShellContext? = instance ?: synchronized(this) {
            instance ?: ShellRuntime.systemContext()?.let { ShellContext(it) }?.also {
                instance = it
                installProcessIdentity(it)
            }
        }

        /**
         * Makes ActivityThread.currentOpPackageName() and currentApplication() answer
         * with the shell identity, as scrcpy's Workarounds.fillAppInfo and
         * fillAppContext do; AudioRecord and AudioTrack read their attribution
         * from there. Only safe in a process that runs nothing but Stream, which
         * the ":stream" UserService process and app_process are. Returns false
         * when a hidden field is missing (the capture then fails honestly).
         */
        fun installProcessIdentity(ctx: ShellContext): Boolean = try {
            val atClass = Class.forName("android.app.ActivityThread")
            val thread = atClass.getMethod("currentActivityThread").invoke(null)
                ?: throw IllegalStateException("no ActivityThread")
            val bindClass = Class.forName("android.app.ActivityThread\$AppBindData")
            val bound = atClass.getDeclaredField("mBoundApplication").apply { isAccessible = true }
            if (bound.get(thread) == null) {
                val data = bindClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
                val info = ApplicationInfo().apply { packageName = SHELL_PACKAGE }
                bindClass.getDeclaredField("appInfo").apply { isAccessible = true }.set(data, info)
                bound.set(thread, data)
            }
            val app = Instrumentation.newApplication(Application::class.java, ctx)
            atClass.getDeclaredField("mInitialApplication").apply { isAccessible = true }.set(thread, app)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "could not install the shell identity", t)
            false
        }
    }
}
