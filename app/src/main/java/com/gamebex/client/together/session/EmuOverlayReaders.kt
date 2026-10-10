package com.gamebex.client.together.session

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import com.gamebex.client.data.Emu
import com.gamebex.client.launch.EmuFiles
import com.gamebex.client.stream.guest.ArmsOverlay
import com.gamebex.client.stream.guest.DolphinOverlay
import com.gamebex.client.stream.guest.FlycastOverlay
import com.gamebex.client.stream.guest.MelondsOverlay
import com.gamebex.client.stream.guest.NetherOverlay
import com.gamebex.client.stream.guest.OverlayOutput
import com.gamebex.client.stream.guest.PpssppOverlay
import com.gamebex.client.stream.guest.parsePpssppAtlas
import com.gamebex.client.stream.guest.ppssppLayout
import com.gamebex.client.stream.guest.HostOverlayBundle
import com.gamebex.client.together.emu.PpssppNetConfig
import com.gamebex.client.launch.DirectFiles
import com.gamebex.client.stream.proto.PadState
import com.gamebex.client.together.LinkMsg
import com.gamebex.client.together.proto.Frames
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.zip.ZipFile
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import android.util.TypedValue
import org.xmlpull.v1.XmlPullParser
import kotlinx.serialization.json.*
import com.gamebex.client.stream.guest.ResourceOverlayNode
import com.gamebex.client.stream.guest.resolveResourceOverlayBounds
import com.gamebex.client.stream.guest.effectiveIni


// Installed emulator resources are discovered by their public resource names.
@SuppressLint("DiscouragedApi")
object EmuOverlayReader {
    data class Bundle(
        val backend: String,
        val controls: List<LinkMsg.EmuControl>,

        val screenW: Int,
        val screenH: Int,

        val screenX: Int = 0,
        val screenY: Int = 0,
        val displayW: Int = 0,
        val displayH: Int = 0,

        val cropX: Int = 0,
        val cropY: Int = 0,
        val cropW: Int = 0,
        val cropH: Int = 0,
    )


    fun read(
        ctx: Context,
        emuPkg: String,
        platform: String?,
        serial: String?,
        gameExt: String = "",
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
        density: Float,
        dpi: Int,
        files: EmuFiles,
        androidData: File,
    ): Bundle? = runCatching {
        val dir = File(androidData, "$emuPkg/files").path
        val validDisplay = displayW > 0 && displayH > 0
        val bundle = when (emuPkg) {
            Emu.DOLPHIN.pkg -> readDolphin(ctx, platform, gameExt, screenW, screenH, displayW, displayH, files, dir)
            Emu.NETHER.pkg -> readNether(ctx, screenW, screenH, density, files, dir)
            Emu.ARMSX2.pkg -> readArms(ctx, serial, if (validDisplay) displayW else screenW, if (validDisplay) displayH else screenH, density, files, dir)
                ?.copy(screenW = screenW, screenH = screenH)
            Emu.FLYCAST.pkg -> readFlycast(ctx, platform, if (validDisplay) displayW else screenW, if (validDisplay) displayH else screenH,
                maxOf(ctx.resources.displayMetrics.xdpi, ctx.resources.displayMetrics.ydpi), files, dir)
                ?.copy(screenW = screenW, screenH = screenH)
            Emu.MELONDS.pkg -> readMelonds(ctx, screenW, screenH, dpi)
            Emu.PPSSPP.pkg -> readPpsspp(ctx, serial, screenW, screenH, dpi, files, dir)
            else -> null
        }
        bundle?.copy(
            controls = if (emuPkg == Emu.DOLPHIN.pkg || emuPkg == Emu.ARMSX2.pkg || emuPkg == Emu.FLYCAST.pkg) bundle.controls.map {
                it.copy(x = it.x - screenX, y = it.y - screenY,
                    renderRect = it.renderRect.mapIndexed { index, value -> value - when (index) { 0 -> screenX; 1 -> screenY; else -> 0 } },
                    knobRect = it.knobRect.mapIndexed { index, value -> value - when (index) { 0 -> screenX; 1 -> screenY; else -> 0 } },
                    cells = if (emuPkg == Emu.ARMSX2.pkg) it.cells.map { cell -> cell.copy(x = cell.x - screenX, y = cell.y - screenY) } else it.cells)
            } else bundle.controls,
            screenX = screenX, screenY = screenY,
            displayW = if (emuPkg == Emu.ARMSX2.pkg && !validDisplay) 0 else displayW,
            displayH = if (emuPkg == Emu.ARMSX2.pkg && !validDisplay) 0 else displayH,
            cropX = cropX, cropY = cropY, cropW = cropW, cropH = cropH,
        )
    }.getOrNull()

    internal fun pkgResources(ctx: Context, emuPkg: String): Resources? = runCatching {
        ctx.createPackageContext(emuPkg, Context.CONTEXT_IGNORE_SECURITY).resources
    }.getOrNull()


    internal fun rasterDrawable(res: Resources, pkg: String, name: String, w: Int, h: Int): Bitmap? {
        if (w <= 0 || h <= 0) return null
        val id = res.getIdentifier(name, "drawable", pkg)
        if (id == 0) return null

        runCatching {
            val opts = BitmapFactory.Options().apply { inScaled = false }
            res.openRawResource(id).use { BitmapFactory.decodeStream(it, null, opts) }
        }.getOrNull()?.let { src ->
            if (src.width == w && src.height == h) return src
            val out = Bitmap.createScaledBitmap(src, w, h, true)
            if (out != src) src.recycle()
            return out
        }

        return runCatching {
            val drawable = res.getDrawable(id, null) ?: return null
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            drawable.setBounds(0, 0, w, h)
            drawable.draw(canvas)
            out
        }.getOrNull()
    }

    internal fun pngBytes(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    internal fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    internal fun b64Png(bitmap: Bitmap?): String {
        if (bitmap == null) return ""


        return b64(pngBytes(bitmap))
    }


    internal fun compositeFit(w: Int, h: Int, art: Bitmap?, dx: Int, dy: Int): Bitmap? {
        if (art == null || w <= 0 || h <= 0) return art
        if (dx == 0 && dy == 0 && art.width == w && art.height == h) return art
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(art, dx.toFloat(), dy.toFloat(), null)
        return out
    }


    internal fun fitRect(srcW: Int, srcH: Int, dstW: Int, dstH: Int): IntArray {
        if (srcW <= 0 || srcH <= 0) return intArrayOf(0, 0, dstW, dstH)
        val scale = min(dstW.toFloat() / srcW, dstH.toFloat() / srcH)
        val w = (srcW * scale).toInt()
        val h = (srcH * scale).toInt()
        return intArrayOf((dstW - w) / 2, (dstH - h) / 2, w, h)
    }


    internal val BARE_EXEC_EXTS: Set<String> = setOf("dol", "elf")

    private fun readDolphin(
        ctx: Context,
        platform: String?,
        gameExt: String,
        screenW: Int,
        screenH: Int,
        displayW: Int,
        displayH: Int,
        files: EmuFiles,
        dir: String,
    ): Bundle? {
        val res = pkgResources(ctx, Emu.DOLPHIN.pkg) ?: return null
        val text = runCatching { files.readText("$dir/Config/Dolphin.ini") }.getOrNull().orEmpty()
        if (text.isBlank()) android.util.Log.w("GameBEXStream", "OVERLAY_DEFAULT dolphin public settings defaults; shared configuration unavailable")
        val settings = DolphinOverlay.parseIni(text)
        if (!settings.showOverlay) return Bundle("dolphin", emptyList(), screenW, screenH)
        val wii = platform == "wii" && gameExt.lowercase().removePrefix(".") !in BARE_EXEC_EXTS
        val width = displayW.takeIf { it > 0 } ?: screenW
        val height = displayH.takeIf { it > 0 } ?: screenH
        val minScreen = min(width, height)
        val alpha = DolphinOverlay.controlAlpha(settings.controlOpacity)
        val activeText = runCatching { files.readText("$dir/Config/${if (wii) "WiimoteNew" else "GCPadNew"}.ini") }.getOrNull().orEmpty()
        val active = effectiveIni(activeText)
        val port = active.keys.map { it.substringBefore('/') }.filter { it.matches(Regex("${if (wii) "Wiimote" else "GCPad"}[0-9]+")) }.distinct().singleOrNull()
        val extension = port?.let { active["$it/Extension"] }.orEmpty()
        val nunchuk = wii && extension.equals("Nunchuk", ignoreCase = true)
        val sideways = wii && port?.let { active["$it/Sideways"]?.let(DolphinOverlay::parseBool) } == true
        if (wii && port == null && active.isNotEmpty()) return null
        if (wii && extension.isNotBlank() && !extension.equals("None", true) && !nunchuk) {
            android.util.Log.w("GameBEXStream", "OVERLAY_DEGRADED dolphin unsupported configured extension")
            return null
        }
        val profile = DolphinProfile.parse(activeText, wii)
        fun position(name: String, axis: String): Int? {
            return DolphinOverlay.resourcePosition(name, axis, width, height) { key ->
                val id = res.getIdentifier(key, "integer", Emu.DOLPHIN.pkg)
                if (id == 0) null else res.getInteger(id)
            }
        }
        val entries = (if (wii) listOf(
            Triple("A", "WIIMOTE_BUTTON_A", 0.125f), Triple("B", "WIIMOTE_BUTTON_B", 0.2f),
            Triple("1", "WIIMOTE_BUTTON_1", 0.0875f), Triple("2", "WIIMOTE_BUTTON_2", 0.0875f),
            Triple("PLUS", "WIIMOTE_BUTTON_PLUS", 0.0625f), Triple("MINUS", "WIIMOTE_BUTTON_MINUS", 0.0625f), Triple("HOME", "WIIMOTE_BUTTON_HOME", 0.0625f),
        ) else listOf(
            Triple("A", "BUTTON_A", 0.2f), Triple("B", "BUTTON_B", 0.125f), Triple("X", "BUTTON_X", 0.175f), Triple("Y", "BUTTON_Y", 0.175f),
            Triple("Z", "BUTTON_Z", 0.225f), Triple("START", "BUTTON_START", 0.075f), Triple("L", "TRIGGER_L", 0.225f), Triple("R", "TRIGGER_R", 0.225f),
        )
        ) + if (nunchuk) listOf(Triple("NUNCHUK_C", "NUNCHUK_BUTTON_C", 0.125f), Triple("NUNCHUK_Z", "NUNCHUK_BUTTON_Z", 0.2f)) else emptyList()
        val out = mutableListOf<LinkMsg.EmuControl>()
        entries.forEach { (id, resource, fraction) ->
            val external = if (id.startsWith("NUNCHUK_")) "Nunchuk" + id.substringAfterLast('_') else if (wii) "WiimoteButton" + when (id) { "PLUS" -> "Plus"; "MINUS" -> "Minus"; "HOME" -> "Home"; else -> id }
                else if (id in setOf("L", "R")) "GCTrigger$id" else "GCButton" + if (id == "START") "Start" else id
            if (!settings.toggle("ButtonToggle$external")) return@forEach
            val positionedResource = if (!wii) requireNotNull(DolphinOverlay.gcPositionResource(id)) else if (sideways && resource in setOf("WIIMOTE_BUTTON_A", "WIIMOTE_BUTTON_B", "WIIMOTE_BUTTON_1", "WIIMOTE_BUTTON_2")) resource.replace("WIIMOTE_", "WIIMOTE_H_") else resource
            val x = position(positionedResource, "X") ?: return@forEach
            val y = position(positionedResource, "Y") ?: return@forEach
            val drawable = if (id.startsWith("NUNCHUK_")) "nunchuk_" + id.substringAfterLast('_').lowercase() else if (wii) "wiimote_" + when (id) { "1" -> "one"; "2" -> "two"; else -> id.lowercase() } else "gcpad_" + id.lowercase()
            val art = dolphinArt(res, drawable, DolphinOverlay.effectiveScale(fraction, settings.controlScale), minScreen) ?: return@forEach
            val normal = when (id) {
                "A" -> OverlayOutput.Buttons(PadState.A); "B" -> OverlayOutput.Buttons(PadState.B)
                "X", "1" -> OverlayOutput.Buttons(PadState.X); "Y", "2" -> OverlayOutput.Buttons(PadState.Y)
                "Z" -> OverlayOutput.Buttons(PadState.R1); "START", "PLUS" -> OverlayOutput.Buttons(PadState.START)
                "MINUS" -> OverlayOutput.Buttons(PadState.SELECT); "HOME" -> OverlayOutput.MenuToggle
                "NUNCHUK_C" -> OverlayOutput.Buttons(PadState.L1); "NUNCHUK_Z" -> OverlayOutput.Trigger(true)
                "L" -> OverlayOutput.Trigger(true); "R" -> OverlayOutput.Trigger(false); else -> OverlayOutput.Ignored
            }
            out += LinkMsg.EmuControl(id, art = b64Png(art.first), pressedArt = b64Png(dolphinArtSized(res, "${drawable}_pressed", art.second.first, art.second.second)),
                x = x, y = y, w = art.second.first, h = art.second.second, alpha = alpha,
                latching = settings.latch("ButtonLatching$external"), output = profile?.outputFor(id, normal) ?: normal)
        }
        val dpad = if (!wii) requireNotNull(DolphinOverlay.gcPositionResource("DPAD")) else if (sideways || nunchuk) "WIIMOTE_UP" else "WIIMOTE_O_UP"
        val dpadToggle = if (wii) "ButtonToggleWiimoteDPad" else "ButtonToggleGCDPad"
        if (settings.toggle(dpadToggle)) {
            val x = position(dpad, "X")
            val y = position(dpad, "Y")
            val image = dolphinArt(res, "gcwii_dpad", DolphinOverlay.effectiveScale(if (nunchuk) 0.2125f else if (wii) 0.275f else 0.2375f, settings.controlScale), minScreen)
            if (x != null && y != null && image != null) out += LinkMsg.EmuControl("DPAD", 1, art = b64Png(image.first),
                pressedArt = b64Png(dolphinArtSized(res, "gcwii_dpad_pressed_one_direction", image.second.first, image.second.second)),
                pressedArt2 = b64Png(dolphinArtSized(res, "gcwii_dpad_pressed_two_directions", image.second.first, image.second.second)),
                x = x, y = y, w = image.second.first, h = image.second.second, alpha = alpha)
        }
        if (!wii || nunchuk) {
            (if (nunchuk) listOf(Triple("NUNCHUK_STICK", "NUNCHUK_STICK", false)) else listOf(Triple("STICK_MAIN", requireNotNull(DolphinOverlay.gcPositionResource("STICK_MAIN")), false), Triple("STICK_C", requireNotNull(DolphinOverlay.gcPositionResource("STICK_C")), true))).forEach { (id, resource, right) ->
                val px = position(resource, "X") ?: return@forEach
                val py = position(resource, "Y") ?: return@forEach
                val toggle = if (nunchuk) "ButtonToggleNunchukStick" else if (right) "ButtonToggleGCStickC" else "ButtonToggleGCStickMain"
                if (!settings.toggle(toggle)) return@forEach
                val name = if (right) "gcpad_c" else "gcwii_joystick"
                val outer = dolphinArt(res, "gcwii_joystick_range", DolphinOverlay.effectiveScale(0.275f, settings.controlScale), minScreen) ?: return@forEach
                val knobSide = (outer.second.first / if (right) 1.833f else 1.375f).toInt() / 2 * 2
                out += LinkMsg.EmuControl(id, 2, art = b64Png(outer.first), x = px, y = py, w = outer.second.first, h = outer.second.second, alpha = alpha,
                    knobArt = b64Png(dolphinThumbSource(res, res.getIdentifier(name, "drawable", Emu.DOLPHIN.pkg))),
                    knobPressedArt = b64Png(dolphinThumbSource(res, res.getIdentifier("${name}_pressed", "drawable", Emu.DOLPHIN.pkg))), knobSize = knobSide,
                    recenter = settings.relCenter, output = OverlayOutput.Stick(right))
            }
        }
        android.util.Log.w("GameBEXStream", "OVERLAY_DEGRADED dolphin resource-default positions; private saved positions are unreadable")
        return Bundle("dolphin", out, screenW, screenH)
    }

    internal fun dolphinArt(res: Resources, name: String, scale: Float, minScreen: Int): Pair<Bitmap, Pair<Int, Int>>? {
        val source = rawPngSize(res, Emu.DOLPHIN.pkg, name) ?: return null
        val size = DolphinOverlay.rasterDimensions(source.first, source.second, scale, minScreen)
        return dolphinArtSized(res, name, size.first, size.second)?.let { it to size }
    }


    internal fun dolphinArtSized(res: Resources, name: String, w: Int, h: Int): Bitmap? {
        val id = res.getIdentifier(name, "drawable", Emu.DOLPHIN.pkg)
        return rasterDensityAwareResource(res, id, w, h)
            ?: rasterDrawable(res, Emu.DOLPHIN.pkg, name, w, h)
    }

    internal fun rasterDensityAwareResource(res: Resources, id: Int, w: Int, h: Int): Bitmap? = runCatching {
        if (id == 0 || w <= 0 || h <= 0) return null
        val decoded = BitmapFactory.decodeResource(res, id, BitmapFactory.Options().apply { inScaled = true }) ?: return null
        if (decoded.width == w && decoded.height == h) return decoded
        Bitmap.createScaledBitmap(decoded, w, h, true).also { if (it !== decoded) decoded.recycle() }
    }.getOrNull()

    internal fun dolphinThumbSource(res: Resources, id: Int): Bitmap? = runCatching {
        if (id == 0) return null
        BitmapFactory.decodeResource(res, id, BitmapFactory.Options().apply { inScaled = true })
    }.getOrNull()


    internal fun rawPngSize(res: Resources, pkg: String, name: String): Pair<Int, Int>? = runCatching {
        val id = res.getIdentifier(name, "drawable", pkg)
        if (id == 0) return null
        val drawable = res.getDrawable(id, null)
        if (drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) drawable.intrinsicWidth to drawable.intrinsicHeight else null
    }.getOrNull()


    internal class DolphinProfile private constructor(private val buttons: Map<String, Int>) {
        fun outputFor(id: String, standard: OverlayOutput): OverlayOutput {
            val bits = buttons[id] ?: return standard
            if (bits < 0) return OverlayOutput.Ignored
            if (standard is OverlayOutput.Trigger && bits == if (standard.left) PadState.L2 else PadState.R2) return standard
            return OverlayOutput.Buttons(bits)
        }

        companion object {

            fun parse(text: String, wii: Boolean): DolphinProfile? {
                val values = effectiveIni(text)
                val prefix = if (wii) "Wiimote" else "GCPad"
                val sections = values.keys.map { it.substringBefore('/') }.filter { it == "Profile" || it.matches(Regex("$prefix[0-9]+")) }.distinct()
                val section = sections.singleOrNull() ?: return null
                val names = if (wii) mapOf("Buttons/A" to "A", "Buttons/B" to "B", "Buttons/1" to "1", "Buttons/2" to "2", "Buttons/+" to "PLUS", "Buttons/-" to "MINUS", "Buttons/−" to "MINUS", "Nunchuk/Buttons/C" to "NUNCHUK_C", "Nunchuk/Buttons/Z" to "NUNCHUK_Z")
                    else mapOf("Buttons/A" to "A", "Buttons/B" to "B", "Buttons/X" to "X", "Buttons/Y" to "Y", "Buttons/Z" to "Z", "Buttons/Start" to "START", "Triggers/L" to "L", "Triggers/R" to "R")
                val buttons = names.mapNotNull { (key, id) ->
                    val expression = values["$section/$key"] ?: return@mapNotNull null
                    val code = Regex("`Button\\s+(\\d+)`").matchEntire(expression.trim())?.groupValues?.get(1)?.toIntOrNull()
                    id to (code?.let(::hidBitsForKeycode) ?: -1)
                }.toMap()
                return buttons.takeIf { it.isNotEmpty() }?.let(::DolphinProfile)
            }


            fun hidBitsForKeycode(keycode: Int): Int? = when (keycode) {
                96 -> PadState.A; 97 -> PadState.B; 99 -> PadState.X; 100 -> PadState.Y
                102 -> PadState.L1; 103 -> PadState.R1; 104 -> PadState.L2; 105 -> PadState.R2
                106 -> PadState.L3; 107 -> PadState.R3; 108 -> PadState.START; 109 -> PadState.SELECT; else -> null
            }
        }
    }

    private fun readDolphinProfile(files: EmuFiles, dir: String, wii: Boolean): DolphinProfile? =
        files.readText("$dir/Config/${if (wii) "WiimoteNew" else "GCPadNew"}.ini")?.let { DolphinProfile.parse(it, wii) }

    private fun readNether(
        ctx: Context,
        screenW: Int,
        screenH: Int,
        density: Float,
        files: EmuFiles,
        dir: String,
    ): Bundle? {
        val profile = files.readText("$dir/inputprofiles/gamebex.ini") ?: return null
        val settings = NetherOverlay.parseProfile(profile)
        val res = pkgResources(ctx, Emu.NETHER.pkg) ?: return null
        val array = res.getIdentifier("settings_touchscreen_controller_view_values", "array", Emu.NETHER.pkg)
        if (array == 0 || settings.view !in res.getStringArray(array)) return null
        if (settings.view == "none") return Bundle("nethersx2", emptyList(), screenW, screenH)
        val layout = res.getIdentifier("layout_touchscreen_controller_${settings.view}", "layout", Emu.NETHER.pkg)
        if (layout == 0) return null
        val nodes = mutableListOf<ResourceOverlayNode>()
        res.getXml(layout).use { xml ->
            while (xml.next() != XmlPullParser.END_DOCUMENT) {
                if (xml.eventType != XmlPullParser.START_TAG) continue
                val attributes = linkedMapOf<String, String>()
                for (index in 0 until xml.attributeCount) {
                    val name = xml.getAttributeName(index)
                    val resourceAttribute = xml.getAttributeNameResource(index)
                    if (resourceAttribute == 0) { attributes[name] = xml.getAttributeValue(index); continue }
                    val values = res.obtainAttributes(xml, intArrayOf(resourceAttribute))
                    try {
                        val value = values.peekValue(0) ?: continue
                        attributes[name] = when {
                            value.type == TypedValue.TYPE_DIMENSION -> values.getDimensionPixelSize(0, 0).toString() + "px"
                            value.type == TypedValue.TYPE_FLOAT -> values.getFloat(0, 0f).toString()
                            value.resourceId != 0 -> res.getResourceTypeName(value.resourceId) + "/" + res.getResourceEntryName(value.resourceId)
                            value.type in TypedValue.TYPE_FIRST_INT..TypedValue.TYPE_LAST_INT -> value.data.toString()
                            else -> values.getString(0).orEmpty()
                        }
                    } finally { values.recycle() }
                }
                val id = attributes["id"]?.removePrefix("id/") ?: continue
                if (id.startsWith("controller_")) nodes += ResourceOverlayNode(id, attributes)
            }
        }
        val bounds = resolveResourceOverlayBounds(nodes, screenW, screenH, density)
        val orientation = if (screenH > screenW) "Portrait" else "Landscape"
        val out = nodes.mapNotNull { node ->
            val original = bounds[node.id] ?: return@mapNotNull null
            val short = node.id.removePrefix("controller_button_")
            val config = when (node.id) {
                "controller_axis_left" -> "LeftAxis"; "controller_axis_right" -> "RightAxis"; "controller_dpad" -> "DPad"
                "controller_button_analog" -> "AnalogToggle"; "controller_button_pause" -> "OpenPauseMenu"
                "controller_button_quick_load" -> "QuickLoad"; "controller_button_quick_save" -> "QuickSave"
                "controller_button_toggle_speed_limit" -> "ToggleFastForward"; "controller_button_toggle_turbo" -> "ToggleTurbo"; "controller_button_toggle_slowmo" -> "ToggleSlowmo"
                "controller_button_pressure" -> "Pressure"
                else -> if (short.matches(Regex("m[1-4]"))) "Macro" + short.last() else short.replaceFirstChar { it.uppercase() } + "Button"
            }
            val defaultVisible = node.attributes["visibility"]?.toIntOrNull()?.let { it == 0 } ?: true
            if (!settings.bool(NetherOverlay.visibleKey(settings.view, config, orientation), defaultVisible)) return@mapNotNull null
            val scale = settings.float(NetherOverlay.scaleKey(settings.view, config, orientation), 1f).takeIf { it > 0f } ?: return@mapNotNull null
            val box = NetherOverlay.transformedRect(original[0], original[1], original[2], original[3],
                settings.float(NetherOverlay.xKey(settings.view, config, orientation), 0f), settings.float(NetherOverlay.yKey(settings.view, config, orientation), 0f), scale)
            val kind = if (node.id.startsWith("controller_axis_")) 2 else if (node.id == "controller_dpad") 1 else 0
            val padding = node.attributes["paddingLeft"]?.removeSuffix("px")?.toFloatOrNull()?.roundToInt() ?: 0
            val unpressed = node.attributes["unpressedDrawable"]?.substringAfter('/')
            val pressed = node.attributes["pressedDrawable"]?.substringAfter('/')
            val id = when (kind) { 2 -> if (node.id.endsWith("left")) "LeftAxis" else "RightAxis"; 1 -> "DPAD"; else -> short.uppercase() }
            val baseWidth = ((original[2] - padding * 2) * scale).roundToInt()
            val baseHeight = ((original[3] - padding * 2) * scale).roundToInt()
            val artwork = if (kind == 2) rasterDrawable(res, Emu.NETHER.pkg, NetherOverlay.ANALOG_BASE_DRAWABLE, baseWidth, baseHeight)
                else unpressed?.let { rasterDrawable(res, Emu.NETHER.pkg, it, box[2], box[3]) }
            val grow = if (kind != 2) (NetherOverlay.pressedGrowth(density) * scale).roundToInt() else 0
            val pressedArtwork = (pressed ?: unpressed)?.let { rasterDrawable(res, Emu.NETHER.pkg, it, box[2] + grow * 2, box[3] + grow * 2) }
            val cells = if (kind == 1) {
                val thirdX = original[2] / 3
                val thirdY = original[3] / 3
                listOf(1 to 0, 1 to 2, 0 to 1, 2 to 1).mapIndexed { index, (column, row) ->
                    val cell = NetherOverlay.transformedRect(original[0] + column * thirdX, original[1] + row * thirdY, thirdX, thirdY, 0f, 0f, 1f)
                    val left = box[0] + ((cell[0] - original[0]) * scale).roundToInt()
                    val top = box[1] + ((cell[1] - original[1]) * scale).roundToInt()
                    val w = (thirdX * scale).roundToInt(); val h = (thirdY * scale).roundToInt()
                    val name = "ic_controller_" + listOf("up", "down", "left", "right")[index] + "_button"
                    LinkMsg.EmuCell(b64Png(rasterDrawable(res, Emu.NETHER.pkg, name, w, h)), b64Png(rasterDrawable(res, Emu.NETHER.pkg, name, w + 2 * grow, h + 2 * grow)), left, top, w, h)
                }
            } else emptyList()
            val knobSize = if (kind == 2) (2 * NetherOverlay.knobHalf(original[2] / 2 - padding) * scale).roundToInt() else 0
            val knob = if (kind == 2) rasterDrawable(res, Emu.NETHER.pkg, NetherOverlay.ANALOG_STICK_DRAWABLE, knobSize, knobSize) else null
            LinkMsg.EmuControl(id, kind, art = b64Png(artwork), pressedArt = b64Png(pressedArtwork),
                x = box[0], y = box[1], w = box[2], h = box[3], padPx = (padding * scale).roundToInt(),
                alpha = DolphinOverlay.controlAlpha(settings.opacity), knobArt = b64Png(knob), knobSize = knobSize, pressedGrowPx = grow, cells = cells, glide = settings.gliding,
                latching = settings.bool(NetherOverlay.toggleKey(settings.view, config), false),
                output = NetherOverlay.outputFor(if (short == "pause") "OpenPauseMenu" else id))
        }
        return Bundle("nethersx2", out, screenW, screenH)
    }

    private fun readArms(
        ctx: Context,
        serial: String?,
        screenW: Int,
        screenH: Int,
        density: Float,
        files: EmuFiles,
        dir: String,
    ): Bundle? {
        val res = pkgResources(ctx, Emu.ARMSX2.pkg) ?: return null
        val wanted = serial?.takeIf { it.matches(Regex("[A-Za-z0-9_.-]+")) }?.let { "$it.touch.json" }
        val exportedRoot = "$dir/inputprofiles"
        val profiles = (findSharedFiles(files, exportedRoot) + listOfNotNull(
            wanted?.let { "$exportedRoot/$it" }?.takeIf { files.isFile(it) },
            "$exportedRoot/Default.touch.json".takeIf { files.isFile(it) },
        )).distinct()
        val selected = wanted?.let { name -> profiles.firstOrNull { it.substringAfterLast('/') == name } }
            ?: profiles.firstOrNull { it.substringAfterLast('/') == "Default.touch.json" } ?: return null
        val private = readPrivatePreferences(ctx, Emu.ARMSX2.pkg, "ARMSX2")
        val perGame = serial?.let { private["touch.layout.game.$it"] }
        val selectedName = serial?.let { private["touch.active.game.$it"] } ?: private["touch.active"]
        val selectedProfile = selectedName?.let { name ->
            runCatching { kotlinx.serialization.json.Json.parseToJsonElement(private["touch.profiles"].orEmpty()).jsonArray.firstOrNull { it.jsonObject["name"]?.jsonPrimitive?.content == name }?.jsonObject }.getOrNull()
        }
        if (selectedName != null && selectedName != "Default" && selectedProfile == null && perGame == null) return null
        val profileText = perGame?.let { "{\"layout\":$it}" } ?: selectedProfile?.toString() ?: files.readText(selected) ?: return null
        val extraEnabled = private["touch.analogExtra"]?.toBooleanStrictOrNull() ?: false
        val extraKey = private["touch.analogExtraCode"]?.toIntOrNull() ?: 96
        val gap = private["touch.dpadSpacing"]?.toFloatOrNull()?.takeIf { it.isFinite() }?.coerceIn(0f, 0.35f) ?: ArmsOverlay.DEFAULT_DPAD_GAP
        val floating = private["touch.floatingStick"]?.toBooleanStrictOrNull() ?: false
        val layout = ArmsOverlay.parseLayout(profileText).filter { it.id != "ANALOG_EXTRA" || extraEnabled }
        val opacity = private["touch.opacity"]?.toFloatOrNull()?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: ArmsOverlay.DEFAULT_OPACITY
        val out = layout.filter { it.enabled }.map { button ->
            val box = ArmsOverlay.widgetRect(button, screenW, screenH, density)
            val kind = if (button.id == "DPAD") 1 else if (button.id in setOf("L_STICK", "R_STICK")) 2 else 0
            fun sprite(name: String, w: Int = box[2], h: Int = box[3]): Bitmap? {
                val intrinsic = rawPngSize(res, Emu.ARMSX2.pkg, name) ?: return null
                val fitted = fitRect(intrinsic.first, intrinsic.second, w, h)
                return compositeFit(w, h, rasterDrawable(res, Emu.ARMSX2.pkg, name, fitted[2], fitted[3]), fitted[0], fitted[1])
            }
            val normal = when { button.id == "PAUSE" -> pauseControlArt(box[2], density, opacity); kind == 2 -> sprite("pad_stick_base"); kind == 1 -> null; else -> sprite(ArmsOverlay.drawableFor(button.id, false)) }
            val pressed = if (button.id in setOf("CIRCLE", "SQUARE")) darken(normal, 176) else if (kind == 0 && button.id != "PAUSE") sprite(ArmsOverlay.drawableFor(button.id, true)) else normal
            val cells = if (kind == 1) {
                val arms = ArmsOverlay.dpadArms(box[0], box[1], box[2], density, gap)
                listOf(arms.up, arms.down, arms.left, arms.right).mapIndexed { index, cell ->
                    val name = listOf("pad_dpad_up", "pad_dpad_up", "pad_dpad_left", "pad_dpad_right")[index]
                    val image = sprite(name, cell[2], cell[3])
                    val selectedArt = sprite(name + "_pressed", cell[2], cell[3])
                    cellOf(b64Png(if (index == 1) rotate180(image) else image), b64Png(if (index == 1) rotate180(selectedArt) else selectedArt), cell)
                }
            } else emptyList()
            val thumb = if (kind == 2) ArmsOverlay.thumbSizePx(box[2]) else 0
            LinkMsg.EmuControl(button.id, kind, art = b64Png(normal), pressedArt = b64Png(pressed),
                knobArt = if (kind == 2) b64Png(sprite("pad_thumb", thumb, thumb)) else "", knobSize = thumb,
                x = box[0], y = box[1], w = box[2], h = box[3], alpha = if (button.id == "PAUSE") 255 else (opacity * 255).roundToInt(),
                latching = button.tapToHold, turboFrames = button.turbo, cells = cells, recenter = kind == 2 && floating, dpadGap = gap,
                output = if (button.id == "ANALOG_EXTRA") DolphinProfile.hidBitsForKeycode(extraKey)?.let { OverlayOutput.Buttons(it) } ?: OverlayOutput.Ignored else ArmsOverlay.outputFor(button.id))
        }
        android.util.Log.w("GameBEXStream", "OVERLAY_DEGRADED ARMS private skin/opacity/spacing/floating/extra-button customization unavailable")
        return Bundle("armsx2", out, screenW, screenH)
    }

    private fun findSharedFiles(files: EmuFiles, root: String): List<String> {
        val out = mutableListOf<String>()
        val pending = ArrayDeque<Pair<String, Int>>()
        pending.add(root to 0)
        while (pending.isNotEmpty() && out.size < 128) {
            val (dir, depth) = pending.removeFirst()
            files.list(dir)?.filter { it.isNotEmpty() && it != "." && it != ".." && '/' !in it && '\\' !in it }?.forEach { name ->
                val path = "$dir/$name"
                if (files.isDirectory(path) && depth < 4) pending.add(path to depth + 1)
                else if (files.isFile(path)) out += path
            }
        }
        return out
    }

    private fun cellOf(art: String, pressedArt: String, rect: IntArray): LinkMsg.EmuCell =
        LinkMsg.EmuCell(art = art, pressedArt = pressedArt, x = rect[0], y = rect[1], w = rect[2], h = rect[3])

    private fun rotate180(src: Bitmap?): Bitmap? {
        if (src == null) return null
        val m = Matrix().apply { postRotate(180f, src.width / 2f, src.height / 2f) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }


    internal fun darken(src: Bitmap?, level: Int): Bitmap? {
        if (src == null) return null
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(out.width * out.height)
        out.getPixels(pixels, 0, out.width, 0, 0, out.width, out.height)
        for (i in pixels.indices) {
            val p = pixels[i]
            val a = p ushr 24 and 0xFF
            val r = (p ushr 16 and 0xFF) * level / 255
            val g = (p ushr 8 and 0xFF) * level / 255
            val b = (p and 0xFF) * level / 255
            pixels[i] = a shl 24 or (r shl 16) or (g shl 8) or b
        }
        out.setPixels(pixels, 0, out.width, 0, 0, out.width, out.height)
        return out
    }

    internal fun readFlycast(
        ctx: Context,
        platform: String?,
        screenW: Int,
        screenH: Int,
        dpi: Int,
        files: EmuFiles,
        dir: String,
        atlas: Bitmap? = flycastAtlas(ctx, platform == "naomi"),
    ): Bundle? = readFlycast(ctx, platform, screenW, screenH, dpi.toFloat(), files, dir, atlas)

    internal fun readFlycast(
        ctx: Context,
        platform: String?,
        screenW: Int,
        screenH: Int,
        dpi: Float,
        files: EmuFiles,
        dir: String,
        atlas: Bitmap? = flycastAtlas(ctx, platform == "naomi"),
    ): Bundle? {
        if (screenW <= 0 || screenH <= 0 || !dpi.isFinite() || dpi <= 0f) return null
        val config = files.readText("$dir/emu.cfg") ?: return null
        val settings = FlycastOverlay.parseCfg(config)
        if (config.isBlank()) android.util.Log.w("GameBEXStream", "OVERLAY_DEFAULT Flycast empty shared emu.cfg; public defaults")
        val placed = FlycastOverlay.layoutControls(screenW, screenH, dpi, settings.elements)
        val alpha = (FlycastOverlay.overlayAlpha(settings.transparency) * 255).roundToInt()
        fun renderRect(c: FlycastOverlay.PlacedControl): List<Float> = listOf(
            FlycastOverlay.dcToPx(c.x, screenH, screenW, true),
            FlycastOverlay.dcToPx(c.y, screenH, screenW, false),
            c.w * screenH / 480f, c.h * screenH / 480f,
        )
        fun box(c: FlycastOverlay.PlacedControl): IntArray {
            val rect = renderRect(c)
            val left = rect[0].roundToInt()
            val top = rect[1].roundToInt()
            return intArrayOf(left, top, (rect[0] + rect[2]).roundToInt() - left, (rect[1] + rect[3]).roundToInt() - top)
        }
        fun image(id: Int): String = b64Png(atlas?.let {
            val cell = FlycastOverlay.TEX_CELLS[id]
            rawFlycastCell(it, cell)
        })
        val knob = placed.firstOrNull { it.id == 12 }
        val knobBox = knob?.let(::box)
        val controls = placed.filter { it.id != 12 }.map { c ->
            val rect = box(c)
            LinkMsg.EmuControl(c.id.toString(), if (c.id == 11) 2 else 0,
                art = image(c.id), pressedArt = image(c.id),
                knobArt = if (c.id == 11 && knob != null) image(12) else "",
                knobPressedArt = if (c.id == 11 && knob != null) image(12) else "",
                knobSize = if (c.id == 11) knobBox?.get(2) ?: 0 else 0,
                x = rect[0], y = rect[1], w = rect[2], h = rect[3], alpha = alpha, output = FlycastOverlay.outputFor(c.id),
                renderRect = renderRect(c), knobRect = if (c.id == 11 && knob != null) renderRect(knob) else emptyList(),
                renderGray = FlycastOverlay.tintGray(c.id, false), pressedRenderGray = FlycastOverlay.tintGray(c.id, true),
                knobRenderGray = FlycastOverlay.tintGray(12, false), textureInset = 1)
        }.toMutableList()
        flycastDiagonals(placed, screenW, screenH)?.forEach { (id, rect) ->
            val left = placed.first { it.id == if (id == 18 || id == 20) 0 else 2 }
            val vertical = placed.first { it.id == if (id < 20) 1 else 3 }
            controls += LinkMsg.EmuControl(id.toString(), x = rect[0], y = rect[1], w = rect[2], h = rect[3], noArt = true,
                output = FlycastOverlay.diagonalOutput(id), renderRect = renderRect(left.copy(y = vertical.y, h = vertical.h)))
        }
        return Bundle("flycast", controls, screenW, screenH)
    }

    internal fun flycastDiagonals(
        placed: List<FlycastOverlay.PlacedControl>,
        screenW: Int,
        screenH: Int,
    ): List<Pair<Int, IntArray>>? {
        val left = placed.firstOrNull { it.id == 0 } ?: return null
        val up = placed.firstOrNull { it.id == 1 } ?: return null
        val right = placed.firstOrNull { it.id == 2 } ?: return null
        val down = placed.firstOrNull { it.id == 3 } ?: return null
        return listOf(left.x to up.y, right.x to up.y, left.x to down.y, right.x to down.y).mapIndexed { index, (x, y) ->
            val l = FlycastOverlay.dcToPx(x, screenH, screenW, true).roundToInt()
            val t = FlycastOverlay.dcToPx(y, screenH, screenW, false).roundToInt()
            val r = FlycastOverlay.dcToPx(x + left.w, screenH, screenW, true).roundToInt()
            val b = FlycastOverlay.dcToPx(y + up.h, screenH, screenW, false).roundToInt()
            index + 18 to intArrayOf(l, t, r - l, b - t)
        }
    }

    internal fun flycastAtlas(ctx: Context, arcade: Boolean): Bitmap? {
        val wantW = if (arcade) FlycastOverlay.ATLAS_ARCADE_W else FlycastOverlay.ATLAS_CONSOLE_W
        val wantH = if (arcade) FlycastOverlay.ATLAS_ARCADE_H else FlycastOverlay.ATLAS_CONSOLE_H
        val apk = runCatching {
            ctx.packageManager.getApplicationInfo(Emu.FLYCAST.pkg, 0).sourceDir
        }.getOrNull() ?: return null
        return runCatching {
            ZipFile(apk).use { zip ->
                val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull { abi ->
                    zip.getEntry("lib/$abi/libflycast.so") != null
                } ?: return null
                val bytes = zip.getInputStream(zip.getEntry("lib/$abi/libflycast.so")!!).readBytes()
                scanForPng(bytes, wantW, wantH)?.let { png ->
                    BitmapFactory.decodeByteArray(png, 0, png.size, BitmapFactory.Options().apply { inPremultiplied = false; inScaled = false })
                }
            }
        }.getOrNull()
    }


    internal fun scanForPng(data: ByteArray, wantW: Int, wantH: Int): ByteArray? {
        val sig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        var i = 0
        fun u32(at: Int): Int =
            (data[at].toInt() and 0xFF shl 24) or (data[at + 1].toInt() and 0xFF shl 16) or
                (data[at + 2].toInt() and 0xFF shl 8) or (data[at + 3].toInt() and 0xFF)
        while (i + 33 < data.size) {
            var match = true
            for (k in sig.indices) {
                if (data[i + k] != sig[k]) {
                    match = false
                    break
                }
            }
            if (!match) {
                i++
                continue
            }
            val w = u32(i + 16)
            val h = u32(i + 20)
            if (w == wantW && h == wantH) {

                var p = i + 8
                var end = -1
                while (p + 12 < data.size) {
                    val len = u32(p)
                    if (len < 0 || p + 12 + len > data.size) break
                    val type = String(data, p + 4, 4, Charsets.US_ASCII)
                    p += 12 + len
                    if (type == "IEND") {
                        end = p
                        break
                    }
                }
                if (end > 0) return data.copyOfRange(i, end)
            }
            i++
        }
        return null
    }


    internal fun rawFlycastCell(atlas: Bitmap, cell: IntArray): Bitmap? {
        val (x, y, w, h) = cell
        if (w <= 2 || h <= 2 || x < 0 || y < 0 || x + w > atlas.width || y + h > atlas.height) return null
        val pixels = IntArray(w * h)
        atlas.getPixels(pixels, 0, w, x, y, w, h)
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPremultiplied(false)
            setPixels(pixels, 0, w, 0, 0, w, h)
        }
    }

    internal fun cropAtlasCell(atlas: Bitmap, cell: IntArray, w: Int, h: Int, gray: Float): Bitmap? {
        if (w <= 0 || h <= 0) return null
        val sx = cell[0] + 1
        val sy = cell[1] + 1
        val sw = cell[2] - 2
        val sh = cell[3] - 2
        if (sx < 0 || sy < 0 || sx + sw > atlas.width || sy + sh > atlas.height) return null
        val cropped = Bitmap.createBitmap(atlas, sx, sy, sw, sh)
        val scaled = Bitmap.createScaledBitmap(cropped, w, h, true)
        if (scaled != cropped) cropped.recycle()
        return tintGray(scaled, gray)
    }


    internal fun tintGray(src: Bitmap?, gray: Float): Bitmap? {
        if (src == null) return null
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(out.width * out.height)
        out.getPixels(pixels, 0, out.width, 0, 0, out.width, out.height)
        for (i in pixels.indices) {
            val p = pixels[i]
            val a = p ushr 24 and 0xFF
            val r = ((p ushr 16 and 0xFF) * gray + 0.5f).toInt().coerceIn(0, 255)
            val g = ((p ushr 8 and 0xFF) * gray + 0.5f).toInt().coerceIn(0, 255)
            val b = ((p and 0xFF) * gray + 0.5f).toInt().coerceIn(0, 255)
            pixels[i] = a shl 24 or (r shl 16) or (g shl 8) or b
        }
        out.setPixels(pixels, 0, out.width, 0, 0, out.width, out.height)
        return out
    }


    private fun readMelonds(
        ctx: Context,
        screenW: Int,
        screenH: Int,
        dpi: Int,
    ): Bundle? {
        if (dpi <= 0 || screenW <= 0 || screenH <= 0) return null
        val res = pkgResources(ctx, Emu.MELONDS.pkg) ?: return null
        val prefs = readPrivatePreferences(ctx, Emu.MELONDS.pkg, Emu.MELONDS.pkg + "_preferences")
        val opacity = prefs["input_opacity"]?.toIntOrNull()?.coerceIn(0, 100) ?: 50
        val alpha = (opacity * 255f / 100f).roundToInt()
        val rects = MelondsOverlay.defaultLayout(screenW, screenH, dpi)
        val out = mutableListOf<LinkMsg.EmuControl>()
        fun image(name: String, rect: IntArray) = b64Png(rasterDrawable(res, Emu.MELONDS.pkg, name, rect[2], rect[3]))
        out += LinkMsg.EmuControl("DPAD", 1, art = image(MelondsOverlay.DPAD_DRAWABLE, rects.dpad),
            x = rects.dpad[0], y = rects.dpad[1], w = rects.dpad[2], h = rects.dpad[3], alpha = alpha)
        out += LinkMsg.EmuControl("BUTTONS", art = image(MelondsOverlay.BUTTONS_DRAWABLE, rects.buttons),
            x = rects.buttons[0], y = rects.buttons[1], w = rects.buttons[2], h = rects.buttons[3], alpha = alpha)
        MelondsOverlay.BUTTON_QUADS.forEachIndexed { index, crop ->
            val rect = MelondsOverlay.fracBox(rects.buttons, crop)
            val id = MelondsOverlay.BUTTON_IDS[index]
            out += LinkMsg.EmuControl(id, x = rect[0], y = rect[1], w = rect[2], h = rect[3], alpha = alpha, noArt = true, output = MelondsOverlay.outputFor(id))
        }
        listOf("L" to (MelondsOverlay.L_DRAWABLE to rects.l), "R" to (MelondsOverlay.R_DRAWABLE to rects.r),
            "SELECT" to (MelondsOverlay.SELECT_DRAWABLE to rects.select), "START" to (MelondsOverlay.START_DRAWABLE to rects.start)).forEach { (id, entry) ->
            val (name, rect) = entry
            out += LinkMsg.EmuControl(id, art = image(name, rect), x = rect[0], y = rect[1], w = rect[2], h = rect[3], alpha = alpha, output = MelondsOverlay.outputFor(id))
        }
        android.util.Log.w("GameBEXStream", "OVERLAY_DEGRADED melonDS resource/default layout; private selected custom layout and safe-inset verification unavailable")
        return Bundle("melonds", out, screenW, screenH)
    }

    private fun readPpsspp(ctx: Context, serial: String?, screenW: Int, screenH: Int, dpi: Int, files: EmuFiles, dir: String): Bundle? {
        val res = pkgResources(ctx, Emu.PPSSPP.pkg) ?: return null
        val metadata = res.assets.open("ui_atlas.meta").use { input ->
            val bytes = input.readBytesBounded(HostOverlayBundle.MAX_IMAGE_BYTES) ?: return null
            parsePpssppAtlas(bytes)
        } ?: return null
        val selected = PpssppNetConfig.forApp(ctx).find()
            ?: "$dir/PSP/SYSTEM/ppsspp.ini".takeIf { files.isFile(it) } ?: return null
        fun text(path: String): String? = runCatching { files.readText(path) }.getOrNull()
            ?: runCatching { DirectFiles.readText(path) }.getOrNull()
        val global = text(selected) ?: return null
        val perGame = serial?.takeIf { it.matches(Regex("[A-Z]{4}\\d{5}")) }?.let { text("${File(selected).parent}/$it" + "_ppsspp.ini") }.orEmpty()
        val values = effectiveIni(global + "\n" + perGame)
        val config = values.filterKeys { it.startsWith("Control/") }.mapKeys { it.key.removePrefix("Control/") }.toMutableMap()
        val orientation = if (screenH > screenW) "Portrait" else "Landscape"
        values.filterKeys { it.startsWith("TouchControls$orientation/") }.forEach { (key, value) -> config[key.substringAfter('/')] = value }
        val uiScale = values["Graphics/UIScaleFactor"]?.toFloatOrNull()?.takeIf { it.isFinite() } ?: 0f
        val layout = ppssppLayout(config, metadata, screenW, screenH, dpi, uiScale) ?: return null
        val alpha = DolphinOverlay.controlAlpha(config["TouchButtonOpacity"]?.toIntOrNull() ?: return null)
        val out = layout.map { c -> LinkMsg.EmuControl(c.id, c.kind, x = c.x.roundToInt(), y = c.y.roundToInt(), w = c.w.roundToInt(), h = c.h.roundToInt(), alpha = alpha,
            knobSize = c.knobSize.roundToInt(), recenter = config["AutoCenterTouchAnalog"]?.let(DolphinOverlay::parseBool) == true,
            stickTravelPx = c.travel, stickyDpad = config["StickyTouchDPad"]?.let(DolphinOverlay::parseBool) == true,
            dpadDiagonals = config["DisableDpadDiagonals"]?.let(DolphinOverlay::parseBool) != true,
            output = if (c.kind == 2) OverlayOutput.Stick(c.id == "rightAnalog") else PpssppOverlay.outputFor(c.id)) }
        android.util.Log.w("GameBEXStream", "OVERLAY_DEGRADED ppsspp installed metadata/config geometry; art unavailable")
        return Bundle("ppsspp", out, screenW, screenH)
    }

    private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray? {
        val result = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) return result.toByteArray()
            if (result.size() + count > limit) return null
            result.write(buffer, 0, count)
        }
    }

    private fun readPrivatePreferences(ctx: Context, pkg: String, name: String): Map<String, String> = runCatching {
        val info = ctx.packageManager.getApplicationInfo(pkg, 0)
        val xml = File(info.dataDir, "shared_prefs/$name.xml").readText()
        val parser = android.util.Xml.newPullParser()
        parser.setInput(xml.reader())
        val values = linkedMapOf<String, String>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG || parser.name == "map") continue
            val key = parser.getAttributeValue(null, "name") ?: continue
            val value = if (parser.name == "string") parser.nextText() else parser.getAttributeValue(null, "value") ?: continue
            values[key] = value
        }
        values
    }.getOrDefault(emptyMap())

    internal fun flattenOverBlack(src: Bitmap, alpha: Int): Bitmap {
        if (alpha >= 255) return src
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        out.eraseColor(0xFF000000.toInt())
        Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply { this.alpha = alpha })
        return out
    }


    internal fun cropFraction(box: Bitmap, crop: IntArray): Bitmap? {
        val x0 = (box.width * crop[0] / MelondsOverlay.GRID).toInt()
        val y0 = (box.height * crop[1] / MelondsOverlay.GRID).toInt()
        val x1 = (box.width * (crop[0] + crop[2]) / MelondsOverlay.GRID).toInt()
        val y1 = (box.height * (crop[1] + crop[3]) / MelondsOverlay.GRID).toInt()
        if (x1 <= x0 || y1 <= y0 || x0 < 0 || y0 < 0 || x1 > box.width || y1 > box.height) return null
        return Bitmap.createBitmap(box, x0, y0, x1 - x0, y1 - y0)
    }


    internal const val MAX_ART_CHARS_PER_PART: Int = Frames.MAX_JSON - 9_536


    fun packParts(sessionId: String, round: Int, bundle: Bundle): List<LinkMsg.StreamEmuOverlay> {
        val stripped = bundle.controls.map { it.stripArt() }
        val artSizes = bundle.controls.associate { it.id to artChars(it) }
        val parts = mutableListOf<List<LinkMsg.EmuControl>>()
        var current = mutableListOf<LinkMsg.EmuControl>()
        var currentSize = 0
        bundle.controls.forEach { c ->
            val size = artSizes[c.id] ?: 0
            if (size == 0) return@forEach
            if (current.isNotEmpty() && currentSize + size > MAX_ART_CHARS_PER_PART) {
                parts += current
                current = mutableListOf()
                currentSize = 0
            }
            current += c
            currentSize += size
        }
        if (current.isNotEmpty()) parts += current
        val partCount = 1 + parts.size
        val out = mutableListOf(
            LinkMsg.StreamEmuOverlay(
                sessionId = sessionId, round = round, backend = bundle.backend,
                controls = stripped, partIndex = 0, partCount = partCount,
                screenX = bundle.screenX, screenY = bundle.screenY,
                screenW = bundle.screenW, screenH = bundle.screenH,
                displayW = bundle.displayW, displayH = bundle.displayH,
                cropX = bundle.cropX, cropY = bundle.cropY,
                cropW = bundle.cropW, cropH = bundle.cropH,
            ),
        )
        parts.forEachIndexed { i, controls ->
            out += LinkMsg.StreamEmuOverlay(
                sessionId = sessionId, round = round, backend = bundle.backend,
                controls = controls, partIndex = i + 1, partCount = partCount,
                screenX = bundle.screenX, screenY = bundle.screenY,
                screenW = bundle.screenW, screenH = bundle.screenH,
                displayW = bundle.displayW, displayH = bundle.displayH,
                cropX = bundle.cropX, cropY = bundle.cropY,
                cropW = bundle.cropW, cropH = bundle.cropH,
            )
        }
        return out
    }

    internal fun LinkMsg.EmuControl.stripArt(): LinkMsg.EmuControl = copy(
        art = "", pressedArt = "", pressedArt2 = "",
        knobArt = "", knobPressedArt = "",
        cells = cells.map { it.copy(art = "", pressedArt = "") },
    )

    private fun artChars(c: LinkMsg.EmuControl): Int =
        c.art.length + c.pressedArt.length + c.pressedArt2.length +
            c.knobArt.length + c.knobPressedArt.length +
            c.cells.sumOf { it.art.length + it.pressedArt.length }


    internal fun pauseControlArt(sizePx: Int, density: Float, opacity: Float): Bitmap? {
        if (sizePx <= 0) return null
        val a = opacity.coerceIn(0.20f, 1f)
        val fade = (opacity / 0.20f).coerceIn(0f, 1f)
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val discR = sizePx * ArmsOverlay.PAUSE_DISC / 2f
        val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            alpha = ((0.55f * a).coerceIn(0.32f, 0.60f) * fade * 255f + 0.5f).toInt()
        }
        canvas.drawCircle(sizePx / 2f, sizePx / 2f, discR, discPaint)
        val barW = sizePx * ArmsOverlay.PAUSE_BAR_W
        val barH = sizePx * ArmsOverlay.PAUSE_BAR_H
        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            alpha = ((0.95f * a).coerceIn(0.72f, 1.0f) * fade * 255f + 0.5f).toInt()
        }

        val totalW = barW * 3f
        val left0 = (sizePx - totalW) / 2f
        val top = (sizePx - barH) / 2f
        val radius = barW / 2f
        canvas.drawRoundRect(RectF(left0, top, left0 + barW, top + barH), radius, radius, barPaint)
        canvas.drawRoundRect(RectF(left0 + barW * 2f, top, left0 + barW * 3f, top + barH), radius, radius, barPaint)
        return out
    }
}
