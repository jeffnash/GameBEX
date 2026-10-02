package com.gamebex.client.stream.shell

import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.IBinder
import android.util.Log
import android.view.Surface
import com.gamebex.client.stream.StreamError

/**
 * Mirrors display 0 onto an encoder input surface, as the shell uid.
 *
 * 1. `DisplayManager.createVirtualDisplay(name, w, h, displayIdToMirror, surface)`,
 * a static @SystemApi method guarded by CAPTURE_VIDEO_OUTPUT, which the shell
 * holds. It exists from Android 14 (AOSP android14-release
 * DisplayManager.java) and is what scrcpy v4.1 tries first
 * (server/src/main/java/com/genymobile/scrcpy/video/ScreenCapture.java). The framework keeps the aspect ratio.
 * 2. Android 13, or when (1) throws: the SurfaceControl display path scrcpy
 * falls back to (createDisplay, setDisplaySurface, setDisplayProjection,
 * setDisplayLayerStack in one transaction), non-secure because the shell may
 * not create secure displays since Android 12. [fit] keeps the aspect ratio.
 *
 * Neither path shows FLAG_SECURE windows; they stay black on the guest.
 */
class ScreenMirror private constructor(
    private val virtualDisplay: VirtualDisplay?,
    private val displayToken: IBinder?,
) : AutoCloseable {
    val api: String get() = if (virtualDisplay != null) API_DISPLAY_MANAGER else API_SURFACE_CONTROL

    override fun close() {
        runCatching { virtualDisplay?.release() }
        displayToken?.let { token ->
            runCatching { surfaceControl().getMethod("destroyDisplay", IBinder::class.java).invoke(null, token) }
        }
    }

 /** Size and layer stack of display 0, read from DisplayManagerGlobal (hidden). */
    class DisplayInfo(val width: Int, val height: Int, val layerStack: Int)

    companion object {
        const val API_DISPLAY_MANAGER: String = "display-manager"
        const val API_SURFACE_CONTROL: String = "surface-control"
        private const val TAG = "GameBEXStream"
        private const val NAME = "gamebex-stream"
        private const val MAIN_DISPLAY = 0

 /** True when either capture path exists in this Android build. */
        fun available(): Boolean = mirrorMethod() != null || runCatching {
            surfaceControl().getMethod("createDisplay", String::class.java, Boolean::class.javaPrimitiveType)
        }.isSuccess

 /** Starts mirroring into [surface] at [width]x[height]. Throws StreamFailure(DISPLAY_CAPTURE). */
        fun open(width: Int, height: Int, surface: Surface): ScreenMirror {
            val method = mirrorMethod()
            var first: Throwable? = null
            if (method != null) {
                try {
                    val vd = method.invoke(null, NAME, width, height, MAIN_DISPLAY, surface) as VirtualDisplay?
                    if (vd != null) return ScreenMirror(vd, null)
                    first = IllegalStateException("createVirtualDisplay returned null")
                } catch (t: Throwable) {
                    first = t
                    Log.w(TAG, "DisplayManager mirror failed; trying SurfaceControl", t)
                }
            }
            return try {
                ScreenMirror(null, surfaceControlMirror(width, height, surface))
            } catch (t: Throwable) {
                Log.w(TAG, "SurfaceControl mirror failed", t)
                throw StreamFailure(StreamError.DISPLAY_CAPTURE, "no display capture: ${first ?: t}", t)
            }
        }

 /** Display 0 as DisplayManagerGlobal sees it, or null when the hidden fields are gone. */
        fun displayInfo(): DisplayInfo? = runCatching {
            val global = Class.forName("android.hardware.display.DisplayManagerGlobal")
            val instance = global.getMethod("getInstance").invoke(null)
            val info = global.getMethod("getDisplayInfo", Int::class.javaPrimitiveType).invoke(instance, MAIN_DISPLAY)
                ?: return null
            val cls = info.javaClass
            DisplayInfo(
                width = cls.getField("logicalWidth").getInt(info),
                height = cls.getField("logicalHeight").getInt(info),
                layerStack = cls.getField("layerStack").getInt(info),
            )
        }.onFailure { Log.w(TAG, "no display info", it) }.getOrNull()

 /**
 * The largest rectangle with the aspect ratio of [srcW]x[srcH] centred in
 * [dstW]x[dstH], as left, top, right, bottom. Pure, for the tests.
 */
        fun fit(srcW: Int, srcH: Int, dstW: Int, dstH: Int): IntArray {
            if (srcW <= 0 || srcH <= 0) return intArrayOf(0, 0, dstW, dstH)
            val byWidth = dstW.toLong() * srcH <= dstH.toLong() * srcW
            val w = if (byWidth) dstW else ((dstH.toLong() * srcW) / srcH).toInt()
            val h = if (byWidth) ((dstW.toLong() * srcH) / srcW).toInt() else dstH
            val left = (dstW - w) / 2
            val top = (dstH - h) / 2
            return intArrayOf(left, top, left + w, top + h)
        }

        private fun mirrorMethod() = runCatching {
            DisplayManager::class.java.getMethod(
                "createVirtualDisplay",
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Surface::class.java,
            )
        }.getOrNull()

        private fun surfaceControl(): Class<*> = Class.forName("android.view.SurfaceControl")

        private fun surfaceControlMirror(width: Int, height: Int, surface: Surface): IBinder {
            val info = displayInfo() ?: throw IllegalStateException("display 0 has no info")
            val sc = surfaceControl()
            val token = sc.getMethod("createDisplay", String::class.java, Boolean::class.javaPrimitiveType)
                .invoke(null, NAME, false) as IBinder
            val box = fit(info.width, info.height, width, height)
            sc.getMethod("openTransaction").invoke(null)
            try {
                sc.getMethod("setDisplaySurface", IBinder::class.java, Surface::class.java).invoke(null, token, surface)
                sc.getMethod(
                    "setDisplayProjection",
                    IBinder::class.java,
                    Int::class.javaPrimitiveType,
                    Rect::class.java,
                    Rect::class.java,
                ).invoke(null, token, 0, Rect(0, 0, info.width, info.height), Rect(box[0], box[1], box[2], box[3]))
                sc.getMethod("setDisplayLayerStack", IBinder::class.java, Int::class.javaPrimitiveType)
                    .invoke(null, token, info.layerStack)
            } finally {
                sc.getMethod("closeTransaction").invoke(null)
            }
            return token
        }
    }
}
