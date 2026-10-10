package com.gamebex.client.stream.guest

import com.gamebex.client.stream.proto.PadState
import com.gamebex.client.together.LinkMsg
import java.io.File
import java.util.Base64
import kotlin.math.round
import kotlin.math.sqrt




const val OVERLAY_26B_FLAG: String = "overlay_26b"

enum class OverlayShape { RECT, RADIAL }


data class OverlayDesc(
    val input: String,
    val x: Float,
    val y: Float,
    val shape: OverlayShape,
    val w: Float,
    val h: Float,
    val image: String? = null,
    val overlayIndex: Int = 0,

    val rangeMod: Float = 1f,

    val alphaMod: Float = 1f,
    val reachUp: Float = 1f,
    val reachDown: Float = 1f,
    val reachLeft: Float = 1f,
    val reachRight: Float = 1f,
    val movable: Boolean = false,
    val exclusive: Boolean = false,
    val rangeModExclusive: Boolean = false,
    val analogSaturatePct: Float = 1f,

    val nextTarget: String = "",
    val upInput: String? = null,
    val downInput: String? = null,
    val leftInput: String? = null,
    val rightInput: String? = null,
) {
    val directionalArea: Boolean get() = input == "dpad_area" || input == "abxy_area"

    val hitDisabled: Boolean get() =
        !x.isFinite() || !y.isFinite() || !w.isFinite() || !h.isFinite() || w <= 0f || h <= 0f ||
            reachLeft + reachRight <= 0f || reachUp + reachDown <= 0f
}

data class OverlayPage(
    val index: Int,
    val name: String = "",
    val image: String? = null,
    val normalized: Boolean = false,
    val fullScreen: Boolean = false,
    val descs: List<OverlayDesc> = emptyList(),

    val alphaMod: Float = 1f,

    val rangeMod: Float = 1f,

    val aspectRatio: Float = 1.7777778f,

    val rectX: Float = 0f,
    val rectY: Float = 0f,
    val rectW: Float = 1f,
    val rectH: Float = 1f,
    val blockXSeparation: Boolean = false,
    val blockYSeparation: Boolean = false,

    val autoXSeparation: Boolean = true,
    val autoYSeparation: Boolean = false,
)

data class OverlayLayout(val name: String = "", val pages: List<OverlayPage> = emptyList()) {

    fun playPage(): OverlayPage? = pages.firstOrNull { it.name.contains("landscape", true) } ?: pages.firstOrNull()

    fun mapped(): MappedOverlay = mapOverlayLayout(this)
}

internal class OverlayStateAccumulator {
    private var buttons = 0
    private var lx = 0
    private var ly = 0
    private var rx = 0
    private var ry = 0
    private var lt = 0
    private var rt = 0
    private val dirs = BooleanArray(4)
    fun add(output: OverlayOutput, ax: Int = 0, ay: Int = 0) {
        when (output) {
            is OverlayOutput.Buttons -> buttons = buttons or output.bits
            is OverlayOutput.Hat -> {
                dirs[0] = dirs[0] || output.up; dirs[1] = dirs[1] || output.down
                dirs[2] = dirs[2] || output.left; dirs[3] = dirs[3] || output.right
            }
            is OverlayOutput.Trigger -> if (output.left) lt = 255 else rt = 255
            is OverlayOutput.Triggers -> { if (output.left) lt = 255; if (output.right) rt = 255 }
            is OverlayOutput.Stick -> if (output.right) { rx += ax; ry += ay } else { lx += ax; ly += ay }
            is OverlayOutput.StickDir -> if (output.right) { rx += output.dx * 127; ry += output.dy * 127 } else { lx += output.dx * 127; ly += output.dy * 127 }
            else -> Unit
        }
    }
    fun state(): PadState = PadState(buttons, lx, ly, rx, ry, lt, rt, PadState.hatFrom(dirs[0], dirs[1], dirs[2], dirs[3])).sanitized()
    fun includes(output: OverlayOutput): Boolean = when (output) {
        is OverlayOutput.Buttons -> output.bits != 0 && buttons and output.bits == output.bits
        is OverlayOutput.Hat -> (output.up || output.down || output.left || output.right) &&
            (!output.up || dirs[0]) && (!output.down || dirs[1]) && (!output.left || dirs[2]) && (!output.right || dirs[3])
        is OverlayOutput.Trigger -> if (output.left) lt > 0 else rt > 0
        is OverlayOutput.Triggers -> (output.left || output.right) && (!output.left || lt > 0) && (!output.right || rt > 0)
        is OverlayOutput.StickDir -> (output.dx != 0 || output.dy != 0) &&
            (output.dx == 0 || (if (output.right) rx else lx) * output.dx > 0) &&
            (output.dy == 0 || (if (output.right) ry else ly) * output.dy > 0)
        else -> false
    }
}


@kotlinx.serialization.Serializable
sealed interface OverlayOutput {

    @kotlinx.serialization.Serializable @kotlinx.serialization.SerialName("buttons")
    data class Buttons(val bits: Int) : OverlayOutput


    @kotlinx.serialization.Serializable @kotlinx.serialization.SerialName("hat")
    data class Hat(val up: Boolean, val down: Boolean, val left: Boolean, val right: Boolean) : OverlayOutput


    @kotlinx.serialization.Serializable @kotlinx.serialization.SerialName("trigger")
    data class Trigger(val left: Boolean) : OverlayOutput


    @kotlinx.serialization.Serializable @kotlinx.serialization.SerialName("stick")
    data class Stick(val right: Boolean) : OverlayOutput


    @kotlinx.serialization.Serializable @kotlinx.serialization.SerialName("stick_dir")
    data class StickDir(val right: Boolean, val dx: Int, val dy: Int) : OverlayOutput


    @kotlinx.serialization.Serializable @kotlinx.serialization.SerialName("triggers")
    data class Triggers(val left: Boolean, val right: Boolean) : OverlayOutput


    @kotlinx.serialization.Serializable @kotlinx.serialization.SerialName("ignored")
    data object Ignored : OverlayOutput


    @kotlinx.serialization.Serializable @kotlinx.serialization.SerialName("unknown")
    data object Unknown : OverlayOutput


    @kotlinx.serialization.Serializable @kotlinx.serialization.SerialName("menu_toggle")
    data object MenuToggle : OverlayOutput


    @kotlinx.serialization.Serializable @kotlinx.serialization.SerialName("overlay_next")
    data object OverlayNext : OverlayOutput


    @kotlinx.serialization.Serializable @kotlinx.serialization.SerialName("macro")
    data class Macro(val index: Int) : OverlayOutput
}

data class MappedDesc(val desc: OverlayDesc, val output: OverlayOutput)


data class MappedOverlay(
    val page: OverlayPage?,
    val gamepad: List<MappedDesc>,
    val unknown: List<String>,

    val all: List<MappedDesc> = emptyList(),
) {

    val usableForPlay: Boolean get() = page != null && (gamepad.isNotEmpty() || unknown.isNotEmpty())
}


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
    "menu_toggle" -> OverlayOutput.MenuToggle
    "overlay_next" -> OverlayOutput.OverlayNext
    in IGNORED_OVERLAY_INPUTS -> OverlayOutput.Ignored
    else -> OverlayOutput.Ignored
}


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


private val IGNORED_OVERLAY_INPUTS: Set<String> = setOf(
    "nul", "null", "none", "empty", "void",
    "dpad_area", "abxy_area",
    "menu", "menu_cancel", "menu_search", "menu_info", "menu_default",
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
    if (page == null) return MappedOverlay(null, emptyList(), emptyList(), emptyList())
    val gamepad = mutableListOf<MappedDesc>()
    val unknown = sortedSetOf<String>()
    val all = page.descs.map { desc ->
        val output = overlayOutputFor(desc.input)
        when (output) {
            is OverlayOutput.Ignored -> if (desc.directionalArea) gamepad += MappedDesc(desc, output)
            is OverlayOutput.Unknown -> unknown += desc.input
            else -> gamepad += MappedDesc(desc, output)
        }
        MappedDesc(desc, output)
    }
    return MappedOverlay(page, gamepad, unknown.toList(), all)
}


object OverlayCfg {







    fun parse(text: String, name: String = "", pageImagePx: Map<Int, Pair<Int, Int>> = emptyMap()): OverlayLayout {
        if (text.length > HostOverlayBundle.MAX_CFG_CHARS) return OverlayLayout(name)
        val values = linkedMapOf<String, String>()
        text.lineSequence().forEach { line ->
            val (key, value) = cfgEntry(line) ?: return@forEach
            if (value == "NaN" || value == "Infinity" || value == "-Infinity") return@forEach
            values[key] = value
        }
        val declared = values["overlays"]?.toIntOrNull()
        val largest = values.keys.mapNotNull { Regex("overlay(\\d+).*?").matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }
            .filter { it in 0..31 }.maxOrNull()
        val count = (declared ?: largest?.plus(1) ?: 0).coerceIn(0, 32)
        return OverlayLayout(name, (0 until count).map { parsePage(values, it, pageImagePx[it]) })
    }

    private fun parsePage(values: Map<String, String>, i: Int, basePx: Pair<Int, Int>?): OverlayPage {
        val prefix = "overlay$i"
        fun number(suffix: String, fallback: Float) = values["${prefix}_$suffix"]?.toFloatOrNull()?.takeIf { it.isFinite() } ?: fallback
        fun flag(suffix: String, fallback: Boolean) = values["${prefix}_$suffix"]?.toBooleanStrictOrNull() ?: fallback
        val name = values["${prefix}_name"].orEmpty()
        val image = values["${prefix}_overlay"]?.takeIf { it.isNotEmpty() }
        val normalized = flag("normalized", false)
        val alpha = number("alpha_mod", 1f)
        val range = number("range_mod", 1f)
        val bx = flag("block_x_separation", false)
        val by = flag("block_y_separation", false)
        val rect = values["${prefix}_rect"]?.split(Regex("[,\\s]+"))?.mapNotNull { it.toFloatOrNull()?.takeIf(Float::isFinite) }
            ?.takeIf { it.size == 4 && it[2] > 0 && it[3] > 0 } ?: listOf(0f, 0f, 1f, 1f)
        val declared = values["${prefix}_descs"]?.toIntOrNull()
        val maxDesc = values.keys.mapNotNull { Regex("${prefix}_desc(\\d+)(?:_.*)?").matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }
            .filter { it in 0..127 }.maxOrNull()
        val count = (declared ?: maxDesc?.plus(1) ?: 0).coerceIn(0, 128)
        return OverlayPage(i, name, image, normalized, flag("full_screen", false),
            (0 until count).mapNotNull { parseDesc(values, i, it, normalized, alpha, range, basePx) }, alpha, range,
            number("aspect_ratio", if (name.contains("portrait", true)) 9f / 16f else 16f / 9f),
            rect[0], rect[1], rect[2], rect[3], bx, by,
            flag("auto_x_separation", !bx && image == null), flag("auto_y_separation", false))
    }

    private fun parseDesc(
        values: Map<String, String>,
        i: Int,
        j: Int,
        pageNormalized: Boolean,
        pageAlpha: Float,
        pageRange: Float,
        basePx: Pair<Int, Int>?,
    ): OverlayDesc? {
        val prefix = "overlay${i}_desc$j"
        val parts = values[prefix]?.split(Regex("[,\\s]+"))?.filter { it.isNotEmpty() } ?: return null
        if (parts.size != 6) return null
        val nums = listOf(parts[1], parts[2], parts[4], parts[5]).map { it.toFloatOrNull()?.takeIf(Float::isFinite) ?: return null }
        if (nums[2] < 0f || nums[3] < 0f) return null
        val shape = when (parts[3].lowercase()) { "rect" -> OverlayShape.RECT; "radial" -> OverlayShape.RADIAL; else -> return null }
        fun number(suffix: String, fallback: Float) = values["${prefix}_$suffix"]?.toFloatOrNull()?.takeIf { it.isFinite() && it >= 0f } ?: fallback
        fun flag(suffix: String, fallback: Boolean) = values["${prefix}_$suffix"]?.toBooleanStrictOrNull() ?: fallback
        val normalized = flag("normalized", pageNormalized)
        val dimensions = if (!normalized) basePx?.takeIf { it.first > 0 && it.second > 0 } ?: return null else 1 to 1
        val rx = number("reach_x", 1f)
        val ry = number("reach_y", 1f)
        return OverlayDesc(parts[0].lowercase(), nums[0] / dimensions.first, nums[1] / dimensions.second, shape,
            nums[2] / dimensions.first, nums[3] / dimensions.second, values["${prefix}_overlay"]?.takeIf { it.isNotEmpty() }, i,
            number("range_mod", pageRange), number("alpha_mod", pageAlpha),
            number("reach_up", ry), number("reach_down", ry), number("reach_left", rx), number("reach_right", rx),
            flag("movable", false), flag("exclusive", false), flag("range_mod_exclusive", false),
            number("saturate_pct", 1f), values["${prefix}_next_target"].orEmpty(),
            values["${prefix}_up"], values["${prefix}_down"], values["${prefix}_left"], values["${prefix}_right"])
    }

    internal fun cfgEntry(line: String): Pair<String, String>? {
        var quoted = false
        var escaped = false
        val value = StringBuilder()
        for (c in line) {
            if (c == '#' && !quoted) break
            value.append(c)
            if (escaped) escaped = false
            else if (c == '\\' && quoted) escaped = true
            else if (c == '"') quoted = !quoted
        }
        if (quoted) return null
        val text = value.toString().trim()
        if ('=' !in text) return null
        val key = text.substringBefore('=').trim().takeIf { it.isNotEmpty() } ?: return null
        return key to unquoteCfgValue(text.substringAfter('=').trim())
    }

    internal fun unquoteCfgValue(raw: String): String {
        val t = raw.trim()
        if (t.length >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            val out = StringBuilder()
            var escaped = false
            t.substring(1, t.length - 1).forEach { c ->
                if (escaped) { if (c != '\\' && c != '"') out.append('\\'); out.append(c); escaped = false }
                else if (c == '\\') escaped = true else out.append(c)
            }
            if (escaped) out.append('\\')
            return out.toString()
        }
        return t
    }
}


fun overlayStickOutput(dxPx: Float, dyPx: Float, travelPx: Float, deadFraction: Float): IntArray {
    val dist = sqrt(dxPx * dxPx + dyPx * dyPx)
    val dead = deadFraction.coerceIn(0f, 0.9f) * travelPx
    if (travelPx <= 0f || dist <= dead || !dist.isFinite()) return intArrayOf(0, 0)
    val mag = ((dist - dead) / (travelPx - dead)).coerceIn(0f, 1f)
    return intArrayOf(round(dxPx / dist * mag * 127f).toInt(), round(dyPx / dist * mag * 127f).toInt())
}


object HostOverlayBundle {

    const val MAX_CFG_CHARS: Int = 32_000


    const val MAX_IMAGES: Int = 128


    const val MAX_IMAGE_BYTES: Int = 192_000


    const val MAX_IMAGE_BASE64_PER_PART: Int = 56_000

    fun overlayPathFromCfgText(text: String): String? {
        var found: String? = null
        text.lineSequence().forEach { line ->
            val (key, value) = OverlayCfg.cfgEntry(line) ?: return@forEach
            if (key == "input_overlay") found = value
        }
        return found?.takeIf { it.isNotEmpty() }
    }


    fun isReadableOverlayPath(path: String): Boolean = !path.startsWith(":")


    private val CORE_VARIANT_SUFFIX = Regex("_(gles[0-9]+|vulkan|gl)$")


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


    fun activeOverlayFile(configDir: File, coreName: String?, streamCfgText: String?): File? {
        val overrideText = findCoreOverride(configDir, coreName)?.let { runCatching { it.readText() }.getOrNull() }
        val mainText = runCatching { File(configDir, "retroarch.cfg").readText() }.getOrNull()
        val overlayPath = overlayPathFromCfgText(overrideText ?: "")
            ?: overlayPathFromCfgText(streamCfgText ?: "")
            ?: overlayPathFromCfgText(mainText ?: "")
            ?: return null
        if (!isReadableOverlayPath(overlayPath)) return null
        return resolveHostPath(configDir, overlayPath)
    }


    fun effectiveSettings(configDir: File, coreName: String?, streamCfgText: String?): RaOverlaySettings {
        return effectiveSettings(configDir, coreName, streamCfgText, null)
    }

    internal fun effectiveSettings(configDir: File, coreName: String?, streamCfgText: String?, mainCfgText: String?): RaOverlaySettings {
        val overrideText = findCoreOverride(configDir, coreName)?.let { runCatching { it.readText() }.getOrNull() }
        val mainText = mainCfgText ?: runCatching { File(configDir, "retroarch.cfg").readText() }.getOrNull()
        return RaOverlaySettings.parse(mainText ?: "", streamCfgText ?: "", overrideText ?: "")
    }


    fun read(
        configDir: File,
        coreName: String?,
        sessionId: String,
        round: Int,
        streamCfgText: String? = null,
        screenW: Int = 0,
        screenH: Int = 0,
    ): List<LinkMsg.StreamOverlay>? {
        val cfgFile = activeOverlayFile(configDir, coreName, streamCfgText) ?: return null
        val settings = effectiveSettings(configDir, coreName, streamCfgText)
        return packFile(cfgFile, sessionId, round, settings, screenW, screenH)
    }


    fun packFile(
        cfgFile: File,
        sessionId: String,
        round: Int,
        settings: RaOverlaySettings = RaOverlaySettings(),
        screenW: Int = 0,
        screenH: Int = 0,
        screenX: Int = 0,
        screenY: Int = 0,
        displayW: Int = 0,
        displayH: Int = 0,
        cropX: Int = 0,
        cropY: Int = 0,
        cropW: Int = 0,
        cropH: Int = 0,
    ): List<LinkMsg.StreamOverlay>? {
        val cfgText = runCatching { cfgFile.readText() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val layout = OverlayCfg.parse(portableCfgText(cfgText))
        val page = layout.playPage()
        if (page == null || page.descs.isEmpty()) return null


        val gamepadFirst = layout.pages.flatMap { it.descs }.sortedBy { desc ->
            when (overlayOutputFor(desc.input)) {
                is OverlayOutput.Ignored, is OverlayOutput.Unknown -> 1
                else -> 0
            }
        }
        val refs = linkedSetOf<String>()
        layout.pages.mapNotNullTo(refs) { it.image }
        gamepadFirst.mapNotNullTo(refs) { it.image }
        val baseDir = cfgFile.parentFile ?: cfgFile.absoluteFile.parentFile ?: File(".")
        val images = mutableListOf<Pair<String, ByteArray>>()
        var skipped = false
        for (ref in refs) {
            if (images.size >= MAX_IMAGES) {
                skipped = true
                break
            }

            if (ref.contains("..") || !isReadableOverlayPath(ref)) {
                skipped = true
                continue
            }
            val raw = runCatching { resolveHostPath(baseDir, ref).readBytes() }.getOrNull()
            if (raw == null || raw.isEmpty() || raw.size > MAX_IMAGE_BYTES) {
                skipped = true
                continue
            }
            images += ref to raw
        }
        return build(sessionId, round, page.name, cfgText, images, settings, screenW, screenH, screenX, screenY, displayW, displayH, cropX = cropX, cropY = cropY, cropW = cropW, cropH = cropH, imagesSkipped = skipped)
    }


    fun packRefs(
        cfgFile: File,
        sessionId: String,
        round: Int,
        refs: List<String>,
    ): LinkMsg.StreamOverlay? {
        if (refs.isEmpty()) return null
        val baseDir = cfgFile.parentFile ?: cfgFile.absoluteFile.parentFile ?: File(".")
        val images = mutableListOf<Pair<String, ByteArray>>()
        refs.distinct().take(MAX_IMAGES).forEach { ref ->
            if (ref.contains("..") || !isReadableOverlayPath(ref)) return@forEach
            val raw = runCatching { resolveHostPath(baseDir, ref).readBytes() }.getOrNull()
            if (raw == null || raw.isEmpty() || raw.size > MAX_IMAGE_BYTES) return@forEach
            images += ref to raw
        }
        if (images.isEmpty()) return null
        val packed = images.map { (ref, bytes) ->
            LinkMsg.OverlayImage(ref, Base64.getEncoder().encodeToString(bytes))
        }
        return LinkMsg.StreamOverlay(
            sessionId = sessionId, round = round, images = packed,
            partIndex = -1, partCount = -1,
        )
    }

    private fun resolveHostPath(base: File, ref: String): File {
        val file = File(ref)
        return if (file.isAbsolute) file else File(base, ref)
    }


    fun build(
        sessionId: String,
        round: Int,
        name: String,
        cfgText: String,
        images: List<Pair<String, ByteArray>>,
        settings: RaOverlaySettings = RaOverlaySettings(),
        screenW: Int = 0,
        screenH: Int = 0,
        screenX: Int = 0,
        screenY: Int = 0,
        displayW: Int = 0,
        displayH: Int = 0,
        cropX: Int = 0,
        cropY: Int = 0,
        cropW: Int = 0,
        cropH: Int = 0,
        imagesSkipped: Boolean = false,
    ): List<LinkMsg.StreamOverlay> {
        val cfgCapped = cfgText.length > MAX_CFG_CHARS
        val cfg = portableCfgText(cfgText)
        val msg = settings.toMessage(screenW, screenH, screenX, screenY, displayW, displayH, cropX, cropY, cropW, cropH)

        val batches = mutableListOf<MutableList<LinkMsg.OverlayImage>>(mutableListOf())
        var used = cfg.length
        var skipped = imagesSkipped
        for ((ref, bytes) in images) {
            if (batches.sumOf { it.size } >= MAX_IMAGES) {
                skipped = true
                break
            }
            if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) {
                skipped = true
                continue
            }
            val b64 = Base64.getEncoder().encodeToString(bytes)
            if (used + b64.length > MAX_IMAGE_BASE64_PER_PART && batches.last().isNotEmpty()) {
                batches.add(mutableListOf())
                used = 0
            }
            used += b64.length
            batches.last().add(LinkMsg.OverlayImage(ref, b64))
        }
        val count = batches.size
        return batches.mapIndexed { index, batch ->
            LinkMsg.StreamOverlay(
                sessionId = sessionId,
                round = round,
                name = name,
                cfg = if (index == 0) cfg else "",
                images = batch,
                truncated = cfgCapped || skipped,
                settings = if (index == 0) msg else LinkMsg.OverlaySettingsMsg(),
                partIndex = index,
                partCount = count,
            )
        }
    }

    private fun portableCfgText(text: String): String {
        if (text.length <= MAX_CFG_CHARS) return text
        // A partial unquoted tuple could otherwise become valid but incorrect geometry.
        val prefix = text.take(MAX_CFG_CHARS)
        return prefix.take(prefix.lastIndexOf('\n') + 1)
    }
}

fun RaOverlaySettings.toMessage(
    screenW: Int,
    screenH: Int,
    screenX: Int = 0,
    screenY: Int = 0,
    displayW: Int = 0,
    displayH: Int = 0,
    cropX: Int = 0,
    cropY: Int = 0,
    cropW: Int = 0,
    cropH: Int = 0,
): LinkMsg.OverlaySettingsMsg =
    LinkMsg.OverlaySettingsMsg(
        opacity = opacity,
        autoScale = autoScale,
        showInputs = showInputs,
        scaleLandscape = scaleLandscape,
        aspectAdjustLandscape = aspectAdjustLandscape,
        xSeparationLandscape = xSeparationLandscape,
        ySeparationLandscape = ySeparationLandscape,
        xOffsetLandscape = xOffsetLandscape,
        yOffsetLandscape = yOffsetLandscape,
        scalePortrait = scalePortrait,
        aspectAdjustPortrait = aspectAdjustPortrait,
        xSeparationPortrait = xSeparationPortrait,
        ySeparationPortrait = ySeparationPortrait,
        xOffsetPortrait = xOffsetPortrait,
        yOffsetPortrait = yOffsetPortrait,
        screenX = screenX,
        screenY = screenY,
        screenW = screenW,
        screenH = screenH,
        displayW = displayW,
        displayH = displayH,
        cropX = cropX,
        cropY = cropY,
        cropW = cropW,
        cropH = cropH,
        floatingAlpha = floatingAlpha,
        dpadDiagonalSensitivity = dpadDiagonalSensitivity,
        abxyDiagonalSensitivity = abxyDiagonalSensitivity,
    )

fun LinkMsg.OverlaySettingsMsg.toSettings(): RaOverlaySettings = RaOverlaySettings(
    opacity = opacity,
    autoScale = autoScale,
    showInputs = showInputs,
    scaleLandscape = scaleLandscape,
    aspectAdjustLandscape = aspectAdjustLandscape,
    xSeparationLandscape = xSeparationLandscape,
    ySeparationLandscape = ySeparationLandscape,
    xOffsetLandscape = xOffsetLandscape,
    yOffsetLandscape = yOffsetLandscape,
    scalePortrait = scalePortrait,
    aspectAdjustPortrait = aspectAdjustPortrait,
    xSeparationPortrait = xSeparationPortrait,
    ySeparationPortrait = ySeparationPortrait,
    xOffsetPortrait = xOffsetPortrait,
    yOffsetPortrait = yOffsetPortrait,
    floatingAlpha = floatingAlpha,
    dpadDiagonalSensitivity = dpadDiagonalSensitivity,
    abxyDiagonalSensitivity = abxyDiagonalSensitivity,
)
