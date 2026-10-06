package com.gamebex.client.stream.guest

import com.gamebex.client.stream.proto.PadState
import com.gamebex.client.together.LinkMsg
import java.io.File
import java.util.Base64
import kotlin.math.round
import kotlin.math.sqrt

/**
 * Stock RetroArch overlay configs: parsing, RetroPad mapping and the
 * host-side bundle reader. Android-free so JVM unit tests cover it all.
 *
 * Format (grounded on libretro/common-overlays stock cfgs, e.g. neo-retropad
 * and psx): `overlays = N`, then per page `overlayN_overlay` (base image),
 * `overlayN_name`, `overlayN_normalized`, `overlayN_full_screen`,
 * `overlayN_range_mod`, `overlayN_descs = K` and
 * `overlayN_descJ = "name,x,y,shape,w,h"` with optional
 * `overlayN_descJ_overlay` (per-desc image) and
 * `overlayN_descJ_range_mod`. Coordinates are full-screen fractions when
 * normalized; x,y is the CENTER and w,h are HALF-extents (a desc covers
 * x±w, y±h — confirmed by neo-retropad's dpad_area covering its d-pad
 * descs). Names may combine inputs with `|` (`up|left`); areas
 * (`dpad_area`, `abxy_area`), `overlay_next` and menu/hotkey names are not
 * player-2 gamepad controls. The guest viewer is landscape and stock cfgs
 * put landscape first, so page 0 is the play page.
 *
 * RetroPad→wire mapping is the 26a table ("Wire naming", as laid out
 * in TouchLayout PSX_DIGITAL/GENERIC and StreamRaConfig's player-2 binds):
 * RetroPad b/a/y/x sit where Android A/B/X/Y sit, so b→[PadState.A],
 * a→[PadState.B], y→[PadState.X], x→[PadState.Y].
 */

/** Guest flag key (captain owns StreamFlags storage; both sides read it default-off). */
const val OVERLAY_26B_FLAG: String = "overlay_26b"

enum class OverlayShape { RECT, RADIAL }

/**
 * One overlay descriptor. [x]/[y] is the normalized center (0..1 of the
 * full screen); [w]/[h] are normalized HALF-extents. [image] is the
 * `descJ_overlay` path exactly as written (relative to the overlay cfg).
 */
data class OverlayDesc(
    val input: String,
    val x: Float,
    val y: Float,
    val shape: OverlayShape,
    val w: Float,
    val h: Float,
    val image: String? = null,
    val overlayIndex: Int = 0,
)

data class OverlayPage(
    val index: Int,
    val name: String = "",
    val image: String? = null,
    val normalized: Boolean = false,
    val fullScreen: Boolean = false,
    val descs: List<OverlayDesc> = emptyList(),
)

data class OverlayLayout(val name: String = "", val pages: List<OverlayPage> = emptyList()) {
 /** The landscape play page: stock cfgs put it first. */
    fun playPage(): OverlayPage? = pages.firstOrNull()

    fun mapped(): MappedOverlay = mapOverlayLayout(this)
}

/** What one descriptor drives on the wire. */
sealed interface OverlayOutput {
 /** One or more [PadState] button bits. */
    data class Buttons(val bits: Int) : OverlayOutput

 /** D-pad directions; holders combine into one hat via [PadState.hatFrom]. */
    data class Hat(val up: Boolean, val down: Boolean, val left: Boolean, val right: Boolean) : OverlayOutput

 /** A full trigger: the L2/R2 bit plus its axis at 255 while held. */
    data class Trigger(val left: Boolean) : OverlayOutput

 /** An analog stick pair; [right] selects rx/ry, otherwise lx/ly. */
    data class Stick(val right: Boolean) : OverlayOutput

 /**
 * One digital direction of an analog stick (overlay `l/r_x/y_±`
 * names: the N64 C-buttons). Holders push the axis to ±127;
 * opposites cancel, diagonals add.
 */
    data class StickDir(val right: Boolean, val dx: Int, val dy: Int) : OverlayOutput

 /** Both triggers at once (`l2|r2` combos); each side acts like [Trigger]. */
    data class Triggers(val left: Boolean, val right: Boolean) : OverlayOutput

 /** Known non-gamepad input (menu/areas/navigation): skipped, never a fallback trip. */
    data object Ignored : OverlayOutput

 /** Anything else: the layout falls back to 26a rather than miss a control. */
    data object Unknown : OverlayOutput
}

data class MappedDesc(val desc: OverlayDesc, val output: OverlayOutput)

/**
 * The play page partitioned for rendering. [unknown] holds the distinct
 * unmapped input names (sorted) for tests and logs.
 */
data class MappedOverlay(
    val page: OverlayPage?,
    val gamepad: List<MappedDesc>,
    val unknown: List<String>,
) {
 /**
 * True when the guest renders this overlay: a normalized play page with
 * at least one gamepad control and nothing unmapped. Otherwise the
 * guest falls back to the 26a layout for the profile — never silently
 * missing controls.
 */
    val usableForPlay: Boolean get() = page?.normalized == true && gamepad.isNotEmpty() && unknown.isEmpty()
}

/** Single RetroPad name → output. Multi-input (`a|b`) names use [overlayOutputFor]. */
fun overlayOutputForPart(part: String): OverlayOutput = when (part) {
    "a" -> OverlayOutput.Buttons(PadState.B)
    "b" -> OverlayOutput.Buttons(PadState.A)
    "x" -> OverlayOutput.Buttons(PadState.Y)
    "y" -> OverlayOutput.Buttons(PadState.X)
    "l" -> OverlayOutput.Buttons(PadState.L1)
    "r" -> OverlayOutput.Buttons(PadState.R1)
    "l2" -> OverlayOutput.Trigger(left = true)
    "r2" -> OverlayOutput.Trigger(left = false)
    "l3" -> OverlayOutput.Buttons(PadState.L3)
    "r3" -> OverlayOutput.Buttons(PadState.R3)
    "select" -> OverlayOutput.Buttons(PadState.SELECT)
    "start" -> OverlayOutput.Buttons(PadState.START)
    "up" -> OverlayOutput.Hat(up = true, down = false, left = false, right = false)
    "down" -> OverlayOutput.Hat(up = false, down = true, left = false, right = false)
    "left" -> OverlayOutput.Hat(up = false, down = false, left = true, right = false)
    "right" -> OverlayOutput.Hat(up = false, down = false, left = false, right = true)
    "analog_left" -> OverlayOutput.Stick(right = false)
    "analog_right" -> OverlayOutput.Stick(right = true)
    "l_x_plus" -> OverlayOutput.StickDir(right = false, dx = 1, dy = 0)
    "l_x_minus" -> OverlayOutput.StickDir(right = false, dx = -1, dy = 0)
    "l_y_plus" -> OverlayOutput.StickDir(right = false, dx = 0, dy = 1)
    "l_y_minus" -> OverlayOutput.StickDir(right = false, dx = 0, dy = -1)
    "r_x_plus" -> OverlayOutput.StickDir(right = true, dx = 1, dy = 0)
    "r_x_minus" -> OverlayOutput.StickDir(right = true, dx = -1, dy = 0)
    "r_y_plus" -> OverlayOutput.StickDir(right = true, dx = 0, dy = 1)
    "r_y_minus" -> OverlayOutput.StickDir(right = true, dx = 0, dy = -1)
    in IGNORED_OVERLAY_INPUTS -> OverlayOutput.Ignored
    else -> if (part.endsWith("_area") || part.startsWith("overlay_")) OverlayOutput.Ignored else OverlayOutput.Unknown
}

/**
 * Full descriptor name → output. `|` combinations merge only within one
 * kind (buttons OR, hat dirs OR, same stick); mixed or half-unknown names
 * are [OverlayOutput.Unknown].
 */
fun overlayOutputFor(input: String): OverlayOutput {
    val parts = input.split('|').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    if (parts.isEmpty()) return OverlayOutput.Unknown
    if (parts.size == 1) return overlayOutputForPart(parts[0])
    val mapped = parts.map(::overlayOutputForPart)
    if (mapped.all { it is OverlayOutput.Ignored }) return OverlayOutput.Ignored
    var bits = 0
    var hatUp = false
    var hatDown = false
    var hatLeft = false
    var hatRight = false
    var trigLeft = false
    var trigRight = false
    var dirRight: Boolean? = null
    var dirDx = 0
    var dirDy = 0
    var dirMixed = false
    var kinds = 0
    mapped.forEach { output ->
        when (output) {
            is OverlayOutput.Buttons -> {
                kinds = kinds or 1
                bits = bits or output.bits
            }
            is OverlayOutput.Hat -> {
                kinds = kinds or 2
                hatUp = hatUp || output.up
                hatDown = hatDown || output.down
                hatLeft = hatLeft || output.left
                hatRight = hatRight || output.right
            }
            is OverlayOutput.Trigger -> {
                kinds = kinds or 4
                if (output.left) trigLeft = true else trigRight = true
            }
            is OverlayOutput.StickDir -> {
                kinds = kinds or 8
                if (dirRight == null) dirRight = output.right
                else if (dirRight != output.right) dirMixed = true
                dirDx += output.dx
                dirDy += output.dy
            }
            else -> return OverlayOutput.Unknown
        }
    }
    if (kinds == 1) return OverlayOutput.Buttons(bits)
    if (kinds == 2) return OverlayOutput.Hat(hatUp, hatDown, hatLeft, hatRight)
    if (kinds == 4) return OverlayOutput.Triggers(trigLeft, trigRight)
    if (kinds == 8 && !dirMixed) {
        return OverlayOutput.StickDir(dirRight == true, dirDx.coerceIn(-1, 1), dirDy.coerceIn(-1, 1))
    }
    return OverlayOutput.Unknown
}

/** Stock non-gamepad descriptor names: overlay navigation, menu, hotkeys, areas. */
private val IGNORED_OVERLAY_INPUTS: Set<String> = setOf(
    "nul", "null", "none", "empty", "void",
    "dpad_area", "abxy_area",
    "menu", "menu_toggle", "menu_cancel", "menu_search", "menu_info", "menu_default",
    "quit", "close_content", "reset",
    "load_state", "save_state", "state_slot_increase", "state_slot_decrease", "state_slot_plus", "state_slot_minus",
    "fullscreen_toggle", "screenshot", "mute", "volume_up", "volume_down",
    "rewind", "hold_toggle", "pause_toggle", "slowmotion_toggle", "slowmotion_hold",
    "toggle_fast_forward", "fast_forward", "fastforward",
    "frameadvance", "movie_record_toggle", "record_toggle",
    "shader_next", "shader_prev", "shader_toggle",
    "cheat_index_plus", "cheat_index_minus", "cheat_toggle",
    "disk_eject_toggle", "disk_next", "disk_prev", "disk_index_plus", "disk_index_minus",
    "netplay_host_toggle", "netplay_game_watch", "netplay_ping_toggle",
    "ui_companion_toggle", "game_focus_toggle", "runahead_toggle", "preempt_toggle",
    "fps_toggle", "statistics_toggle", "help_toggle", "osk_toggle",
    "vrr_runloop_toggle", "video_rotation_plus", "video_rotation_minus",
    "keyboard_a", "keyboard_b",
)

fun mapOverlayLayout(layout: OverlayLayout): MappedOverlay {
    val page = layout.playPage()
    if (page == null) return MappedOverlay(null, emptyList(), emptyList())
    val gamepad = mutableListOf<MappedDesc>()
    val unknown = sortedSetOf<String>()
    page.descs.forEach { desc ->
        when (val output = overlayOutputFor(desc.input)) {
            is OverlayOutput.Ignored -> Unit
            is OverlayOutput.Unknown -> unknown += desc.input
            else -> gamepad += MappedDesc(desc, output)
        }
    }
    return MappedOverlay(page, gamepad, unknown.toList())
}

/** Tolerant: comments, blanks and unknown keys are skipped. */
object OverlayCfg {
    private const val MAX_PAGES: Int = 32
    private const val MAX_DESCS: Int = 128
    private val COUNT = Regex("""^overlay(\d+)_descs$""")
    private val TUPLE = Regex("""^overlay(\d+)_desc(\d+)$""")
    private val KEY = Regex("[A-Za-z0-9_]+")

    fun parse(text: String, name: String = ""): OverlayLayout {
        val values = LinkedHashMap<String, String>()
        text.lineSequence().forEach { line ->
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) return@forEach
            val eq = t.indexOf('=')
            if (eq < 0) return@forEach
            val key = t.substring(0, eq).trim()
            if (!key.matches(KEY)) return@forEach
            values[key] = unquoteCfgValue(t.substring(eq + 1).trim())
        }
 // Stock cfgs always say `overlays = N`; derive it when absent.
        val declared = values["overlays"]?.toIntOrNull()?.coerceIn(0, MAX_PAGES)
        val pageCount = declared ?: run {
            val highest = (values.keys.mapNotNull { COUNT.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() } +
                values.keys.mapNotNull { TUPLE.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }).maxOrNull()
            if (highest == null) 0 else (highest + 1).coerceIn(0, MAX_PAGES)
        }
        val pages = (0 until pageCount).map { i ->
            val count = values["overlay${i}_descs"]?.toIntOrNull()?.coerceIn(0, MAX_DESCS) ?: 0
            val pageRange = values["overlay${i}_range_mod"]?.toFloatOrNull()?.coerceIn(0.1f, 8f) ?: 1f
            val descs = (0 until count).mapNotNull { j ->
                parseDesc(values, i, j, pageRange)
            }
            OverlayPage(
                index = i,
                name = values["overlay${i}_name"] ?: "",
                image = values["overlay${i}_overlay"]?.takeIf { it.isNotEmpty() },
                normalized = values["overlay${i}_normalized"] == "true",
                fullScreen = values["overlay${i}_full_screen"] == "true",
                descs = descs,
            )
        }
        return OverlayLayout(name = name, pages = pages)
    }

    private fun parseDesc(values: Map<String, String>, i: Int, j: Int, pageRange: Float): OverlayDesc? {
        val tuple = values["overlay${i}_desc$j"] ?: return null
        val parts = tuple.split(',').map { it.trim() }
        if (parts.size < 6) return null
        val input = parts[0].lowercase()
        if (input.isEmpty()) return null
        val x = parts[1].toFloatOrNull() ?: return null
        val y = parts[2].toFloatOrNull() ?: return null
        val shape = when (parts[3].lowercase()) {
            "rect" -> OverlayShape.RECT
            "radial" -> OverlayShape.RADIAL
            else -> return null
        }
        val w = parts[4].toFloatOrNull() ?: return null
        val h = parts[5].toFloatOrNull() ?: return null
        if (!x.isFinite() || !y.isFinite() || !w.isFinite() || !h.isFinite()) return null
        if (w <= 0f || h <= 0f || w > 1f || h > 1f) return null
        val descRange = values["overlay${i}_desc${j}_range_mod"]?.toFloatOrNull()?.coerceIn(0.1f, 8f) ?: 1f
        val scale = pageRange * descRange
        return OverlayDesc(
            input = input,
            x = x.coerceIn(0f, 1f),
            y = y.coerceIn(0f, 1f),
            shape = shape,
            w = (w * scale).coerceIn(0.001f, 1f),
            h = (h * scale).coerceIn(0.001f, 1f),
            image = values["overlay${i}_desc${j}_overlay"]?.takeIf { it.isNotEmpty() },
            overlayIndex = i,
        )
    }

    internal fun unquoteCfgValue(raw: String): String {
        val t = raw.trim()
        if (t.length >= 2 && t.startsWith("\"") && t.endsWith("\"")) return t.substring(1, t.length - 1)
        return t
    }
}

/**
 * Stick deflection for one overlay stick sample. The same curve as
 * TouchHitTester: linear past the dead zone, saturated at travel, rounded
 * to wire units. [travelPx] is the desc's smaller half-extent in pixels.
 */
fun overlayStickOutput(dxPx: Float, dyPx: Float, travelPx: Float, deadFraction: Float): IntArray {
    val dist = sqrt(dxPx * dxPx + dyPx * dyPx)
    val dead = deadFraction.coerceIn(0f, 0.9f) * travelPx
    if (travelPx <= 0f || dist <= dead || !dist.isFinite()) return intArrayOf(0, 0)
    val mag = ((dist - dead) / (travelPx - dead)).coerceIn(0f, 1f)
    return intArrayOf(round(dxPx / dist * mag * 127f).toInt(), round(dyPx / dist * mag * 127f).toInt())
}

/**
 * Host side: finds the active RetroArch overlay cfg and images on shared
 * storage and packs them into one [LinkMsg.StreamOverlay].
 *
 * Resolution mirrors RetroArch: the per-core override
 * `<configDir>/config/<core>/<core>.cfg` wins over `<configDir>/retroarch.cfg`;
 * the active `input_overlay` path there names the overlay cfg, and image
 * refs inside it resolve against the overlay cfg's own directory. RA's own
 * cfg is cross-app unreadable on some phones (see RetroArchTuner), so
 * anything missing reads as "no overlay", never an error.
 */
object HostOverlayBundle {
 /** The overlay cfg text is trimmed past this; the play page always fits. */
    const val MAX_CFG_CHARS: Int = 12_000

 /** Stock overlays name up to ~20 images; the byte budget binds first. */
    const val MAX_IMAGES: Int = 24

 /** A single image bigger than this is dropped (vector fallback art instead). */
    const val MAX_IMAGE_BYTES: Int = 32_768

 /**
 * Total base64 budget. 48_000 chars of images + 12_000 of cfg + names
 * stays under one link JSON frame (Frames.MAX_JSON = 65_536).
 */
    const val MAX_IMAGE_BASE64_TOTAL: Int = 48_000

    private val OVERLAY_KEY = Regex("""^input_overlay\s*=\s*(.*)$""")

 /** The last uncommented `input_overlay` value wins; null when unset or empty. */
    fun overlayPathFromCfgText(text: String): String? {
        var found: String? = null
        text.lineSequence().forEach { line ->
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) return@forEach
            val m = OVERLAY_KEY.find(t) ?: return@forEach
            found = OverlayCfg.unquoteCfgValue(m.groupValues[1])
        }
        return found?.takeIf { it.isNotEmpty() }
    }

 /** Bundled-asset references (`:...`) live inside RetroArch: unreadable, skip them. */
    fun isReadableOverlayPath(path: String): Boolean = !path.startsWith(":")

 /** Graphics-variant suffixes on core ids (`mupen64plus_next_gles3`). */
    private val CORE_VARIANT_SUFFIX = Regex("_(gles[0-9]+|vulkan|gl)$")

 /**
 * The core override cfg for [coreName] (a TogetherSupport.coreFor id):
 * exact `<id>/<id>.cfg` first, then the directory whose name matches
 * ignoring separators and case (RetroArch names override dirs after the
 * core's display name, e.g. `Mupen64Plus-Next` for `mupen64plus_next`),
 * then both again with the graphics-variant suffix stripped.
 */
    fun findCoreOverride(configDir: File, coreName: String?): File? {
        if (coreName.isNullOrEmpty()) return null
        findCoreOverrideInner(configDir, coreName)?.let { return it }
        val stripped = CORE_VARIANT_SUFFIX.replace(coreName, "")
        if (stripped != coreName && stripped.isNotEmpty()) {
            findCoreOverrideInner(configDir, stripped)?.let { return it }
        }
        return null
    }

    private fun findCoreOverrideInner(configDir: File, coreName: String): File? {
        val root = File(configDir, "config")
        val exact = File(root, "$coreName/$coreName.cfg")
        if (runCatching { exact.isFile }.getOrDefault(false)) return exact
        val want = coreName.filter { it.isLetterOrDigit() }.lowercase()
        if (want.isEmpty()) return null
        val match = runCatching { root.listFiles() }.getOrNull()
            ?.firstOrNull { it.isDirectory && it.name.filter { c -> c.isLetterOrDigit() }.lowercase() == want }
            ?: return null
        return File(match, "${match.name}.cfg").takeIf { runCatching { it.isFile }.getOrDefault(false) }
    }

 /**
 * Reads and packs the active overlay, or null when there is nothing
 * worth sending (no overlay configured, files unreadable, or the play
 * page has no descriptors). Runs on the caller's thread; the engine
 * calls it on IO.
 */
    fun read(configDir: File, coreName: String?, sessionId: String, round: Int): LinkMsg.StreamOverlay? {
        val overrideText = findCoreOverride(configDir, coreName)?.let { runCatching { it.readText() }.getOrNull() }
        val mainText = runCatching { File(configDir, "retroarch.cfg").readText() }.getOrNull()
        val overlayPath = overlayPathFromCfgText(overrideText ?: "")
            ?: overlayPathFromCfgText(mainText ?: "")
            ?: return null
        if (!isReadableOverlayPath(overlayPath)) return null
        return packFile(resolveHostPath(configDir, overlayPath), sessionId, round)
    }

 /**
 * Packs one overlay cfg file (plus the images its play page names,
 * resolved beside the cfg). The seeder path calls this directly —
 * a seeded overlay is named by the stream session config, not by
 * RetroArch's own cfgs, so [read] above can't find it.
 */
    fun packFile(cfgFile: File, sessionId: String, round: Int): LinkMsg.StreamOverlay? {
        val cfgText = runCatching { cfgFile.readText() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val layout = OverlayCfg.parse(cfgText)
        val page = layout.playPage()
        if (page == null || page.descs.isEmpty()) return null
 // Gamepad art packs first: when the byte budget binds, the
 // dropped refs are menu/fast-forward toys (vector fallback),
 // never A/B (the n64 B sits last in its cfg).
        val gamepadFirst = page.descs.sortedBy { desc ->
            when (overlayOutputFor(desc.input)) {
                is OverlayOutput.Ignored, is OverlayOutput.Unknown -> 1
                else -> 0
            }
        }
        val refs = linkedSetOf<String>()
        page.image?.let(refs::add)
        gamepadFirst.mapNotNullTo(refs) { it.image }
        val baseDir = cfgFile.parentFile ?: cfgFile.absoluteFile.parentFile ?: File(".")
        val images = mutableListOf<Pair<String, ByteArray>>()
        var dropped = false
        for (ref in refs) {
            if (images.size >= MAX_IMAGES) {
                dropped = true
                break
            }
 // Never follow `..` or asset refs out of the overlay's own tree.
            if (ref.contains("..") || !isReadableOverlayPath(ref)) {
                dropped = true
                continue
            }
            val raw = runCatching { resolveHostPath(baseDir, ref).readBytes() }.getOrNull()
            if (raw == null || raw.isEmpty() || raw.size > MAX_IMAGE_BYTES) {
                dropped = true
                continue
            }
            images += ref to shrinkPng(raw)
        }
        return build(sessionId, round, page.name, cfgText, images, imagesDropped = dropped)
    }

 /**
 * Halves a PNG's dimensions (quarter bytes, transparency kept) so a
 * full stock overlay fits the link frame. On any failure — including
 * JVM unit tests, where Bitmap is a stub — returns the input.
 */
    internal fun shrinkPng(raw: ByteArray): ByteArray = runCatching {
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size, opts)
        if (opts.outWidth < 48 || opts.outHeight < 48) return raw
        opts.inJustDecodeBounds = false
        opts.inSampleSize = 2
        val bmp = android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size, opts) ?: return raw
        val out = java.io.ByteArrayOutputStream()
        if (!bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)) return raw
        val shrunk = out.toByteArray()
        if (shrunk.isEmpty() || shrunk.size >= raw.size) raw else shrunk
    }.getOrNull() ?: raw

    private fun resolveHostPath(base: File, ref: String): File {
        val file = File(ref)
        return if (file.isAbsolute) file else File(base, ref)
    }

 /**
 * Packs already-read overlay data into the message, enforcing the frame
 * caps. Pure: tests drive this (and [read] above against temp dirs).
 */
    fun build(
        sessionId: String,
        round: Int,
        name: String,
        cfgText: String,
        images: List<Pair<String, ByteArray>>,
        imagesDropped: Boolean = false,
    ): LinkMsg.StreamOverlay {
        val cfgCapped = cfgText.length > MAX_CFG_CHARS
        val cfg = if (cfgCapped) cfgText.take(MAX_CFG_CHARS) else cfgText
        val packed = mutableListOf<LinkMsg.OverlayImage>()
        var used = 0
        var dropped = imagesDropped
        for ((ref, bytes) in images) {
            if (packed.size >= MAX_IMAGES) {
                dropped = true
                break
            }
            if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) {
                dropped = true
                continue
            }
            val b64 = Base64.getEncoder().encodeToString(bytes)
            if (used + b64.length > MAX_IMAGE_BASE64_TOTAL) {
                dropped = true
                continue
            }
            used += b64.length
            packed += LinkMsg.OverlayImage(ref, b64)
        }
        return LinkMsg.StreamOverlay(
            sessionId = sessionId,
            round = round,
            name = name,
            cfg = cfg,
            images = packed,
            truncated = cfgCapped || dropped,
        )
    }
}
 /**
 * Packs the active overlay cfg, its effective settings and every image
 * all pages name. Null when there is nothing worth sending. Blocking
 * file IO; the engine calls it on IO. [streamCfgText] is the session
 * config RetroArch loads (`CONFIGFILE`); [screenW]/[screenH] is the host
 * screen in pixels.
 */
