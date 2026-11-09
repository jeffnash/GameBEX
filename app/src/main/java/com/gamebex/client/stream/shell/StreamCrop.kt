package com.gamebex.client.stream.shell

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLES11Ext
import android.opengl.EGLExt
import android.util.Log
import android.view.Surface
import com.gamebex.client.stream.StreamConfig
import com.gamebex.client.stream.StreamDebug
import com.gamebex.client.stream.VideoLevel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Crop the stream to the game picture before encoding (host on touch
 * only, so the side-bar overlay buttons never leave the host). Debug switch
 * `stream_crop=1` (+ optional `stream_crop_aspect`, default 4:3), sampled once
 * per session. The shell configures the encoder at the cropped size and runs a
 * GL pass ([StreamCropPass]) between the app-capture surface (full size) and
 * the encoder input surface; the guest already sizes from Info. Any EGL setup
 * failure disables the crop for that open (logged) instead of killing the
 * stream.
 */
data class StreamCropOptions(
    val enabled: Boolean = false,
    val aspectNum: Int = 4,
    val aspectDen: Int = 3,
    /** Overlay viewport + display ("vx,vy,vw,vh,dw,dh", display px); "" = legacy display center. */
    val viewport: String = "",
) {
    companion object {
        fun sample(
            debug: Boolean = com.gamebex.client.BuildConfig.DEBUG,
            prop: (String) -> String? = StreamDebug::prop,
        ): StreamCropOptions {
            if (!debug) return StreamCropOptions()
            if (prop("stream_crop") != "1") return StreamCropOptions()
            val (num, den) = aspectOf(prop("stream_crop_aspect"))
            return StreamCropOptions(true, num, den, prop("stream_crop_viewport") ?: "")
        }

        /** Shell entry: StreamConfig value with the adb override on debug builds. */
        fun resolve(config: StreamConfig?): StreamCropOptions {
            val enabled = shellFlag("stream_crop", config?.crop ?: false)
            if (!enabled) return StreamCropOptions()
            val propAspect = if (com.gamebex.client.BuildConfig.DEBUG) StreamDebug.prop("stream_crop_aspect") else null
            val (num, den) = (aspectOrNull(propAspect) ?: aspectOrNull(config?.cropAspect) ?: intArrayOf(4, 3))
                .let { it[0] to it[1] }
            val vpProp = if (com.gamebex.client.BuildConfig.DEBUG) StreamDebug.prop("stream_crop_viewport") else null
            val vp = if (vpProp != null && parseViewport(vpProp) != null) vpProp else (config?.cropViewport ?: "")
            return StreamCropOptions(true, num, den, vp)
        }

        /** Resolves the crop aspect the way the shell does (default 4:3). Internal: the host stamps the same rect on the Together link. */
        internal fun aspectOf(value: String?): IntArray = aspectOrNull(value) ?: intArrayOf(4, 3)

        /** Parses a crop aspect ("N:D", small positive ints) or null when it is missing/malformed. */
        internal fun aspectOrNull(value: String?): IntArray? {
            val parts = (value ?: return null).split(":")
            if (parts.size != 2) return null
            val num = parts[0].toIntOrNull() ?: return null
            val den = parts[1].toIntOrNull() ?: return null
            if (num < 1 || den < 1 || num > 32 || den > 32) return null
            return intArrayOf(num, den)
        }
    }
}

data class CropRect(val left: Int, val top: Int, val width: Int, val height: Int)

/**
 * Largest even rect of [num]:[den] centered in the overlay viewport.
 * With a landscape [viewport] ("vx,vy,vw,vh,dw,dh" in display px) the
 * rect is display-px: the capture runs display-native (no 1604→1600
 * resampling smear) and the GL pass crops it 1:1 into the encoder.
 * The origin is exact (odd allowed: the GL pass UV-crops,
 * only the encoder output must align); sizes stay even. A portrait or
 * missing viewport falls back to the legacy behavior (largest even
 * rect centered in [srcW]x[srcH], even origin): the game centers in
 * the emulator window, not the display — centering in the 1604 display
 * on the lab phones shifts the crop 35px right of the 1534 window's
 * game picture, until the landscape re-pin corrects it.
 */
internal fun streamCropRect(srcW: Int, srcH: Int, num: Int, den: Int, viewport: String = ""): CropRect {
    require(srcW > 0 && srcH > 0 && num > 0 && den > 0)
    val vp = parseViewport(viewport)?.takeIf { it[4] >= it[5] }
    if (vp == null) {
        var w = (srcH.toLong() * num / den).toInt()
        var h = srcH
        if (w > srcW) {
            w = srcW
            h = (srcW.toLong() * den / num).toInt()
        }
        w -= w % 2
        h -= h % 2
        if (w < 2 || h < 2) return CropRect(0, 0, srcW - srcW % 2, srcH - srcH % 2)
        val left = ((srcW - w) / 2) and 1.inv()
        val top = ((srcH - h) / 2) and 1.inv()
        return CropRect(left, top, w, h)
    }
    // Native path: the viewport string carries the display size; the
    // rect is display-px and the capture runs at the display size.
    val dw = vp[4]
    val dh = vp[5]
    var w = (dh.toLong() * num / den).toInt()
    var h = dh
    if (w > dw) {
        w = dw
        h = (dw.toLong() * den / num).toInt()
    }
    w -= w % 2
    h -= h % 2
    if (w < 2 || h < 2) return CropRect(0, 0, dw - dw % 2, dh - dh % 2)
    val cx = vp[0] + vp[2] / 2.0
    val cy = vp[1] + vp[3] / 2.0
    val left = (cx - w / 2.0).toInt().coerceIn(0, dw - w)
    val top = (cy - h / 2.0).toInt().coerceIn(0, dh - h)
    return CropRect(left, top, w, h)
}

/**
 * The capture size for [options]: the viewport's display size when a
 * landscape viewport is present (native capture, no resampling smear),
 * else the level size (legacy). Pure: tests drive this.
 */
internal fun streamCropCaptureSize(level: VideoLevel, options: StreamCropOptions): Pair<Int, Int> {
    val vp = parseViewport(options.viewport)?.takeIf { it[4] >= it[5] }
    if (options.enabled && vp != null) return vp[4] to vp[5]
    return level.width to level.height
}

/**
 * The window a default-cutout-mode app gets on a [displayW]x[displayH]
 * display with [cutouts] bounding rects (display coords, each
 * [left, top, right, bottom]), as [x, y, w, h]. Shell-side mirror of
 * the host's `defaultCutoutViewport` (stream.shell must not import
 * together): the system letterboxes the window off any display edge a
 * cutout touches; floating hole-punches letterbox nothing. Pure: tests
 * drive this.
 */
internal fun shellCutoutViewport(
    displayW: Int,
    displayH: Int,
    cutouts: List<IntArray>,
): IntArray {
    var x = 0
    var y = 0
    var w = displayW
    var h = displayH
    cutouts.forEach { r ->
        val (left, top, right, bottom) = r
        if (left <= 0 && right - left > 0) {
            val nx = right.coerceIn(0, displayW)
            if (nx > x) {
                w -= nx - x
                x = nx
            }
        } else if (right >= displayW && right - left > 0) {
            w = minOf(w, left - x)
        }
        if (top <= 0 && bottom - top > 0) {
            val ny = bottom.coerceIn(0, displayH)
            if (ny > y) {
                h -= ny - y
                y = ny
            }
        } else if (bottom >= displayH && bottom - top > 0) {
            h = minOf(h, top - y)
        }
    }
    if (w <= 0 || h <= 0) return intArrayOf(0, 0, displayW, displayH)
    return intArrayOf(x, y, w, h)
}

/**
 * Samples the display + cutout in the CURRENT rotation as a
 * crop-viewport string ("vx,vy,vw,vh,dw,dh"), or "" when unreadable.
 * The stream starts while the phone is portrait (lobby) but the
 * emulator runs landscape with a letterboxed window, so the config's
 * portrait-time viewport centers the crop like legacy; the shell
 * re-samples after the game launches (see `gameLaunched`).
 */
internal fun currentCropViewport(context: android.content.Context?): String {
    if (context == null) return ""
    return runCatching {
        val wm = context.getSystemService(android.content.Context.WINDOW_SERVICE) as? android.view.WindowManager
            ?: return ""
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val display = wm.maximumWindowMetrics.bounds
            val cutouts = wm.currentWindowMetrics.windowInsets.displayCutout?.boundingRects.orEmpty()
                .map { intArrayOf(it.left, it.top, it.right, it.bottom) }
            val vp = shellCutoutViewport(display.width(), display.height(), cutouts)
            "${vp[0]},${vp[1]},${vp[2]},${vp[3]},${display.width()},${display.height()}"
        } else {
            @Suppress("DEPRECATION")
            val size = android.graphics.Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(size)
            "0,0,${size.x},${size.y},${size.x},${size.y}"
        }
    }.getOrNull() ?: ""
}

/**
 * True once the display reports a landscape rotation, polling [pollMs]
 * until [timeoutMs] (the emulator takes seconds to rotate after
 * launch). Injectable [rotation] for tests.
 */
internal fun waitForLandscapeRotation(
    context: android.content.Context?,
    timeoutMs: Long = 15_000L,
    pollMs: Long = 250L,
    rotation: () -> Int? = {
        runCatching {
            val wm = context?.getSystemService(android.content.Context.WINDOW_SERVICE) as? android.view.WindowManager
            @Suppress("DEPRECATION")
            wm?.defaultDisplay?.rotation
        }.getOrNull()
    },
): Boolean {
    val endNs = System.nanoTime() + timeoutMs * 1_000_000L
    do {
        when (runCatching { rotation() }.getOrNull()) {
            android.view.Surface.ROTATION_90, android.view.Surface.ROTATION_270 -> return true
        }
        if (System.nanoTime() >= endNs) return false
        runCatching { Thread.sleep(pollMs) }
    } while (true)
}

/** Parses "vx,vy,vw,vh,dw,dh" (all ints, sane sizes) or null. */
internal fun parseViewport(viewport: String): IntArray? {
    if (viewport.isBlank()) return null
    val p = viewport.split(",").map { it.toIntOrNull() ?: return null }
    if (p.size != 6 || p[2] <= 0 || p[3] <= 0 || p[4] <= 0 || p[5] <= 0) return null
    if (p[0] < 0 || p[1] < 0 || p[0] + p[2] > p[4] || p[1] + p[3] > p[5]) return null
    return p.toIntArray()
}

/** Encoder size for [rect]: 16-aligned (AVC-friendly), at least 16px. */
internal fun streamCropEncodeSize(rect: CropRect): Pair<Int, Int> =
    maxOf(16, rect.width - rect.width % 16) to maxOf(16, rect.height - rect.height % 16)

/**
 * The level to configure the encoder at when cropping, or [level]
 * unchanged. The rect is display-px on the native path (a landscape
 * viewport is present) and level-px on the legacy path; the full-frame
 * checks run against the matching source size.
 */
internal fun streamCropEncodeLevel(level: VideoLevel, options: StreamCropOptions): Pair<VideoLevel, CropRect?> {
    if (!options.enabled) return level to null
    val (srcW, srcH) = streamCropCaptureSize(level, options)
    val rect = streamCropRect(srcW, srcH, options.aspectNum, options.aspectDen, options.viewport)
    if (rect.width >= srcW && rect.height >= srcH) return level to null
    val (w, h) = streamCropEncodeSize(rect)
    if (w >= srcW && h >= srcH) return level to null
    return level.copy(width = w, height = h) to rect
}

/**
 * GL pass: app capture renders full frames into [inputSurface]; a dedicated
 * thread crops [rect] and draws it into [out] (the encoder input surface),
 * preserving frame timestamps. All EGL state lives on that thread; the
 * constructor throws when EGL setup fails so the caller can fall back.
 */
class StreamCropPass(
    srcW: Int,
    srcH: Int,
    rect: CropRect,
    private val out: Surface,
    private val trace: CaptureTrace? = null,
) {
    private val lock = Object()
    private var running = true
    private var frameAvailable = false
    private var arrivedNs = 0L
    private var surfaceTexture: SurfaceTexture? = null
    val inputSurface: Surface

    private val thread: Thread

    init {
        require(srcW > 0 && srcH > 0)
        val setup = EglSetup(out)
        try {
            val texId = setup.genOesTexture()
            val st = SurfaceTexture(texId)
            st.setDefaultBufferSize(srcW, srcH)
            st.setOnFrameAvailableListener {
                synchronized(lock) {
                    frameAvailable = true
                    if (trace != null) arrivedNs = System.nanoTime()
                    lock.notifyAll()
                }
            }
            surfaceTexture = st
            inputSurface = Surface(st)
        } catch (t: Throwable) {
            setup.release()
            throw t
        }
        val u0 = rect.left.toFloat() / srcW
        val v0 = 1f - (rect.top + rect.height).toFloat() / srcH
        val u1 = (rect.left + rect.width).toFloat() / srcW
        val v1 = 1f - rect.top.toFloat() / srcH
        thread = Thread({
            try {
                setup.makeCurrent()
                val program = setup.buildProgram()
                val stMatrix = FloatArray(16)
                while (true) {
                    val arrived = synchronized(lock) {
                        while (running && !frameAvailable) lock.wait()
                        if (!running) return@Thread
                        frameAvailable = false
                        arrivedNs
                    }
                    val st = synchronized(lock) { surfaceTexture } ?: return@Thread
                    st.updateTexImage()
                    st.getTransformMatrix(stMatrix)
                    setup.draw(program, stMatrix, u0, v0, u1, v1)
                    if (trace != null) {
                        GLES20.glFinish()
                        trace.onDrawn(st.timestamp, arrived, System.nanoTime())
                    }
                    EGLExt.eglPresentationTimeANDROID(setup.display, setup.surface, st.timestamp)
                    EGL14.eglSwapBuffers(setup.display, setup.surface)
                }
            } catch (_: InterruptedException) {
                Unit
            } catch (t: Throwable) {
                runCatching { Log.w("GameBEXStream", "crop pass failed: ${t.javaClass.simpleName}: ${t.message}") }
            } finally {
                setup.release()
            }
        }, "gbx-crop-gl").apply { isDaemon = true; start() }
    }

    fun release() {
        synchronized(lock) {
            running = false
            lock.notifyAll()
        }
        runCatching { thread.join(1000) }
        synchronized(lock) {
            runCatching { surfaceTexture?.release() }
            surfaceTexture = null
            runCatching { inputSurface.release() }
        }
    }

    private class EglSetup(out: Surface) {
        var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
            private set
        private var context: EGLContext = EGL14.EGL_NO_CONTEXT
        var surface: EGLSurface = EGL14.EGL_NO_SURFACE
            private set
        private var released = false

        init {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
            val config = chooseConfig(display)
            val contextAttrs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, contextAttrs, 0)
            check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
            surface = EGL14.eglCreateWindowSurface(display, config, out, intArrayOf(EGL14.EGL_NONE), 0)
            check(surface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
        }

        private fun chooseConfig(display: EGLDisplay): EGLConfig {
            val attrs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0) && count[0] > 0) {
                "eglChooseConfig found no recordable RGBA8888 ES2 config"
            }
            return configs[0]!!
        }

        fun makeCurrent() {
            check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent failed" }
        }

        fun genOesTexture(): Int {
            val tex = IntArray(1)
            GLES20.glGenTextures(1, tex, 0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            return tex[0]
        }

        fun buildProgram(): Int {
            val vertex = """
                attribute vec4 aPosition;
                attribute vec2 aTexCoord;
                uniform vec4 uCrop;
                uniform mat4 uSTMatrix;
                varying vec2 vTexCoord;
                void main() {
                  vec2 cropped = vec2(mix(uCrop.x, uCrop.z, aTexCoord.x), mix(uCrop.y, uCrop.w, aTexCoord.y));
                  vTexCoord = (uSTMatrix * vec4(cropped, 0.0, 1.0)).xy;
                  gl_Position = aPosition;
                }
            """.trimIndent()
            val fragment = """
                #extension GL_OES_EGL_image_external : require
                precision mediump float;
                varying vec2 vTexCoord;
                uniform samplerExternalOES sTexture;
                void main() { gl_FragColor = texture2D(sTexture, vTexCoord); }
            """.trimIndent()
            val program = GLES20.glCreateProgram()
            val vs = compile(GLES20.GL_VERTEX_SHADER, vertex)
            val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragment)
            GLES20.glAttachShader(program, vs)
            GLES20.glAttachShader(program, fs)
            GLES20.glLinkProgram(program)
            val status = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] == GLES20.GL_TRUE) { "crop shader link failed" }
            return program
        }

        private fun compile(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            check(status[0] == GLES20.GL_TRUE) { "crop shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}" }
            return shader
        }

        fun draw(program: Int, stMatrix: FloatArray, u0: Float, v0: Float, u1: Float, v1: Float) {
            GLES20.glUseProgram(program)
            val pos = GLES20.glGetAttribLocation(program, "aPosition")
            val tex = GLES20.glGetAttribLocation(program, "aTexCoord")
            GLES20.glUniform4f(GLES20.glGetUniformLocation(program, "uCrop"), u0, v0, u1, v1)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uSTMatrix"), 1, false, stMatrix, 0)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sTexture"), 0)
            GLES20.glEnableVertexAttribArray(pos)
            GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, QUAD_POS)
            GLES20.glEnableVertexAttribArray(tex)
            GLES20.glVertexAttribPointer(tex, 2, GLES20.GL_FLOAT, false, 0, QUAD_TEX)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(pos)
            GLES20.glDisableVertexAttribArray(tex)
        }

        fun release() {
            if (released) return
            released = true
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
            display = EGL14.EGL_NO_DISPLAY
            context = EGL14.EGL_NO_CONTEXT
            surface = EGL14.EGL_NO_SURFACE
        }

        companion object {
            private const val EGL_RECORDABLE_ANDROID = 0x3142
            private val QUAD_POS: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0)
            }
            private val QUAD_TEX: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)); position(0)
            }
        }
    }
}
