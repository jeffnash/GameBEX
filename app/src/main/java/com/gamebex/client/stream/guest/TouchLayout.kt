package com.gamebex.client.stream.guest

import com.gamebex.client.stream.proto.PadState

/**
 * Per-system touch layouts: profiles, anchors, control tables and output
 * bindings. Geometry is in dp and resolved to pixels once by
 * [resolveTouchLayout]; hit testing lives in TouchHitTester.
 *
 * All bit names below are [PadState] constants, not RetroPad labels. Axes are
 * -127..127, triggers 0..255, hat 0..7 clockwise from up or HAT_CENTER (8).
 */

const val TOUCH_MIN_TARGET_DP: Float = 48f
const val TOUCH_CLEARANCE_DP: Float = 8f

enum class TouchBackend { RETROARCH, DOLPHIN, NETHERSX2, ARMSX2, UNKNOWN }

/** Maps the wire name (StreamEndpoint.backend); unknown names get the generic pad. */
fun touchBackendFromName(name: String): TouchBackend = when (name) {
    "retroarch" -> TouchBackend.RETROARCH
    "dolphin" -> TouchBackend.DOLPHIN
    "nethersx2" -> TouchBackend.NETHERSX2
    "armsx2" -> TouchBackend.ARMSX2
    else -> TouchBackend.UNKNOWN
}

object TouchFolders {
    const val N64: String = "n64"
    const val PSX: String = "psx"
    const val PS2: String = "ps2"
    const val GC: String = "gc"
    const val WII: String = "wii"
    const val SATURN: String = "saturn"
    const val DREAMCAST: String = "dreamcast"
    const val NAOMI: String = "naomi"
    const val TDO: String = "3do"
}

object TouchControllerModes {
    const val PSX_DIGITAL: String = "psx_digital"
    const val PSX_DUALSHOCK: String = "psx_dualshock"
    const val GC_CORE: String = "gc_core"
    const val GC_STANDALONE: String = "gc_standalone"
    const val WII_NUNCHUK: String = "wii_nunchuk"
    const val SATURN_PAD: String = "saturn_pad"
    const val SATURN_3D: String = "saturn_3d"
}

/** Normalized rectangle inside the encoded frame, when the host provides one. */
data class TouchViewport(val u0: Float, val v0: Float, val u1: Float, val v1: Float)

/**
 * Identifies the host's emulator route. Optional metadata: a null or
 * unrecognized profile falls back to [TouchLayoutId.GENERIC], never to a
 * guess from the folder name alone.
 */
data class TouchProfile(
    val folder: String,
    val backend: TouchBackend = TouchBackend.UNKNOWN,
    val controllerMode: String = "",
    val mappingRevision: Int = 0,
    val viewport: TouchViewport? = null,
)

enum class TouchLayoutId { GENERIC, N64, PSX_DIGITAL, PSX_DUALSHOCK, PS2, GC_CORE, GC_STANDALONE, WII_NUNCHUK_RA, WII_NUNCHUK_STANDALONE, SATURN, SATURN_3D, DREAMCAST, NAOMI, TDO }

fun selectTouchLayout(profile: TouchProfile?): TouchLayoutId {
    if (profile == null) return TouchLayoutId.GENERIC
    return when (profile.folder) {
        TouchFolders.N64 ->
            if (profile.backend == TouchBackend.RETROARCH) TouchLayoutId.N64 else TouchLayoutId.GENERIC
        TouchFolders.PSX ->
            if (profile.backend != TouchBackend.RETROARCH) TouchLayoutId.GENERIC
            else if (profile.controllerMode == TouchControllerModes.PSX_DUALSHOCK) TouchLayoutId.PSX_DUALSHOCK
            else TouchLayoutId.PSX_DIGITAL
        TouchFolders.PS2 ->
            if (profile.backend == TouchBackend.NETHERSX2 || profile.backend == TouchBackend.ARMSX2) TouchLayoutId.PS2
            else TouchLayoutId.GENERIC
        TouchFolders.GC -> when (profile.backend) {
            TouchBackend.RETROARCH -> TouchLayoutId.GC_CORE
            TouchBackend.DOLPHIN -> TouchLayoutId.GC_STANDALONE
            else -> TouchLayoutId.GENERIC
        }
        TouchFolders.WII ->
            if (profile.controllerMode != TouchControllerModes.WII_NUNCHUK) TouchLayoutId.GENERIC
            else when (profile.backend) {
                TouchBackend.RETROARCH -> TouchLayoutId.WII_NUNCHUK_RA
                TouchBackend.DOLPHIN -> TouchLayoutId.WII_NUNCHUK_STANDALONE
                else -> TouchLayoutId.GENERIC
            }
        TouchFolders.SATURN ->
            if (profile.backend != TouchBackend.RETROARCH) TouchLayoutId.GENERIC
            else if (profile.controllerMode == TouchControllerModes.SATURN_3D) TouchLayoutId.SATURN_3D
            else TouchLayoutId.SATURN
        TouchFolders.DREAMCAST ->
            if (profile.backend == TouchBackend.RETROARCH) TouchLayoutId.DREAMCAST else TouchLayoutId.GENERIC
        TouchFolders.NAOMI ->
            if (profile.backend == TouchBackend.RETROARCH) TouchLayoutId.NAOMI else TouchLayoutId.GENERIC
        TouchFolders.TDO ->
            if (profile.backend == TouchBackend.RETROARCH) TouchLayoutId.TDO else TouchLayoutId.GENERIC
        else -> TouchLayoutId.GENERIC
    }
}

/** What a touch control drives on the wire. */
sealed interface TouchOutput {
    /** One or more [PadState] button bits. */
    data class Buttons(val bits: Int) : TouchOutput

    /** A stick axes pair; [right] selects rx/ry, otherwise lx/ly. */
    data class Stick(val right: Boolean) : TouchOutput

    /**
     * A full trigger. Digital press emits the L2/R2 bit plus axis 255;
     * [analogDrag] instead drags 0..255 with the full-press bit at t>=0.95.
     */
    data class Trigger(val left: Boolean, val analogDrag: Boolean = false) : TouchOutput

    /**
     * A GameCube half trigger. The RetroArch core binds halves to L3/R3 with
     * zero trigger axes; standalone Dolphin binds lt/rt=128 with no bits.
     */
    data class HalfTrigger(val left: Boolean, val bit: Int, val axisValue: Int) : TouchOutput

    enum class CDir { UP, DOWN, LEFT, RIGHT }

    /** An N64 C button: held directions combine into rx/ry, opposites cancel. */
    data class CButton(val dir: CDir) : TouchOutput

    /** Local-only control (Menu); never contributes controller input. */
    data object Menu : TouchOutput
}

/** Group anchors as fractions of the safe area: side-bar centers plus Wide fallbacks. */
enum class TouchAnchor(val u: Float, val v: Float, val wideU: Float, val wideV: Float) {
    LP(0.10f, 0.47f, 0.095f, 0.68f),
    RP(0.90f, 0.47f, 0.905f, 0.68f),
    LS(0.10f, 0.82f, 0.245f, 0.82f),
    RS(0.90f, 0.82f, 0.755f, 0.82f),
    LD(0.10f, 0.67f, 0.095f, 0.68f),
    RD(0.90f, 0.67f, 0.905f, 0.68f),
    LO(0.055f, 0.14f, 0.055f, 0.17f),
    LI(0.145f, 0.14f, 0.145f, 0.17f),
    RO(0.945f, 0.14f, 0.945f, 0.17f),
    RI(0.855f, 0.14f, 0.855f, 0.17f),
    LA(0.20f, 0.25f, 0.245f, 0.58f),
    RA(0.80f, 0.25f, 0.755f, 0.58f),
    SELECT(0.44f, 0.91f, 0.44f, 0.91f),
    START(0.56f, 0.91f, 0.56f, 0.91f),
    START_ALONE(0.50f, 0.91f, 0.50f, 0.91f),
    MENU(0.50f, 0.08f, 0.50f, 0.08f),
}

sealed interface TouchControl {
    val id: String
    val anchor: TouchAnchor
    /** Wide-mode center override for this control, when the table gives one. */
    val wideU: Float?
    val wideV: Float?
    /** Mirrored by the swap-sides preset; center utilities and Menu stay put. */
    val mirror: Boolean
    /** May overlap the picture (utilities, aux, Menu); exempt from the side-bar fit check. */
    val overlayOk: Boolean
}

/** One rolling d-pad: a cross inside a circular input region. */
data class DpadControl(
    override val id: String,
    override val anchor: TouchAnchor,
    val diameterDp: Float,
    override val wideU: Float? = null,
    override val wideV: Float? = null,
    override val mirror: Boolean = true,
) : TouchControl {
    override val overlayOk: Boolean = false
}

data class StickControl(
    override val id: String,
    override val anchor: TouchAnchor,
    val right: Boolean,
    val diameterDp: Float = 112f,
    val knobDp: Float = 40f,
    val travelDp: Float = 36f,
    /** Definition default before the Fixed/Floating setting: right camera sticks float. */
    val floatingDefault: Boolean = false,
    override val wideU: Float? = null,
    override val wideV: Float? = null,
    override val mirror: Boolean = true,
) : TouchControl {
    override val overlayOk: Boolean = false
}

data class FaceButtonDef(
    val id: String,
    val label: String,
    val offsetXDp: Float,
    val offsetYDp: Float,
    val hitDp: Float,
    val artDp: Float,
    val output: TouchOutput,
)

/** A sliding face cluster with explicit two-button chord pairs (button ids). */
data class FaceClusterControl(
    override val id: String,
    override val anchor: TouchAnchor,
    val atU: Float? = null,
    val atV: Float? = null,
    val buttons: List<FaceButtonDef>,
    val chords: Set<Pair<String, String>> = emptySet(),
    override val wideU: Float? = null,
    override val wideV: Float? = null,
    override val mirror: Boolean = true,
) : TouchControl {
    override val overlayOk: Boolean = false
}

/** A shoulder, utility, aux or half-trigger button. */
data class KeyControl(
    override val id: String,
    override val anchor: TouchAnchor,
    val label: String,
    val widthDp: Float,
    val heightDp: Float,
    val roundDp: Float = 12f,
    val circular: Boolean = false,
    val output: TouchOutput,
    /** True where the host reads a continuous trigger (Dreamcast, standalone GC). */
    val analogCapable: Boolean = false,
    override val wideU: Float? = null,
    override val wideV: Float? = null,
    override val mirror: Boolean = true,
    override val overlayOk: Boolean =
        anchor == TouchAnchor.SELECT || anchor == TouchAnchor.START || anchor == TouchAnchor.START_ALONE ||
            anchor == TouchAnchor.LA || anchor == TouchAnchor.RA,
) : TouchControl

/** The local 48dp Menu control; activating it releases touch input. */
data class MenuControl(
    override val id: String = "menu",
    override val anchor: TouchAnchor = TouchAnchor.MENU,
    val sizeDp: Float = 48f,
) : TouchControl {
    override val wideU: Float? = null
    override val wideV: Float? = null
    override val mirror: Boolean = false
    override val overlayOk: Boolean = true
}

/** Normalized chord pair: order-independent, stable for tie-breaking. */
fun touchChord(a: String, b: String): Pair<String, String> = if (a <= b) a to b else b to a

data class TouchLayoutSpec(val id: TouchLayoutId, val name: String, val controls: List<TouchControl>)

private fun selectStartPair(): List<KeyControl> = listOf(
    KeyControl("select", TouchAnchor.SELECT, "Select", 64f, 48f, output = TouchOutput.Buttons(PadState.SELECT), mirror = false),
    KeyControl("start", TouchAnchor.START, "Start", 64f, 48f, output = TouchOutput.Buttons(PadState.START), mirror = false),
)

private fun startAlone(): KeyControl =
    KeyControl("start", TouchAnchor.START_ALONE, "Start", 64f, 48f, output = TouchOutput.Buttons(PadState.START), mirror = false)

/** Four-button diamond: 42dp center-to-button offsets, 56dp hit discs. */
private fun diamondCluster(
    id: String,
    anchor: TouchAnchor,
    top: FaceButtonDef,
    right: FaceButtonDef,
    bottom: FaceButtonDef,
    left: FaceButtonDef,
): FaceClusterControl {
    val o = 42f
    fun at(def: FaceButtonDef, x: Float, y: Float): FaceButtonDef = def.copy(offsetXDp = x, offsetYDp = y)
    val buttons = listOf(at(top, 0f, -o), at(right, o, 0f), at(bottom, 0f, o), at(left, -o, 0f))
    val ids = buttons.map { it.id }
    return FaceClusterControl(
        id = id,
        anchor = anchor,
        buttons = buttons,
        chords = setOf(
            touchChord(ids[0], ids[1]), touchChord(ids[1], ids[2]),
            touchChord(ids[2], ids[3]), touchChord(ids[3], ids[0]),
        ),
    )
}

private fun faceButton(id: String, label: String, bits: Int, hitDp: Float = 56f, artDp: Float = 48f): FaceButtonDef =
    FaceButtonDef(id, label, 0f, 0f, hitDp, artDp, TouchOutput.Buttons(bits))

private fun baseTouchLayoutSpec(id: TouchLayoutId): TouchLayoutSpec = when (id) {
    TouchLayoutId.GENERIC -> TouchLayoutSpec(
        id, "Generic",
        listOf(
            DpadControl("dpad", TouchAnchor.LP, 120f),
            StickControl("stick_l", TouchAnchor.LS, right = false),
            diamondCluster(
                "face", TouchAnchor.RP,
                faceButton("y", "Y", PadState.Y), faceButton("b", "B", PadState.B),
                faceButton("a", "A", PadState.A), faceButton("x", "X", PadState.X),
            ),
            KeyControl("l", TouchAnchor.LO, "L", 64f, 48f, output = TouchOutput.Buttons(PadState.L1)),
            KeyControl("r", TouchAnchor.RO, "R", 64f, 48f, output = TouchOutput.Buttons(PadState.R1)),
        ) + selectStartPair() + MenuControl(),
    )
    TouchLayoutId.N64 -> TouchLayoutSpec(
        id, "Nintendo 64",
        listOf(
            StickControl("stick", TouchAnchor.LP, right = false),
            DpadControl("dpad", TouchAnchor.LS, 104f),
            // Matches RetroArch's own n64 overlay (overlays/gamepads/n64/n64.cfg):
            // A/B level at mid-right (B left of A), C-buttons bottom-right, Z at (0.80, 0.25).
            FaceClusterControl(
                id = "ab", anchor = TouchAnchor.RP, atU = 0.875f, atV = 0.47f,
                wideU = 0.875f, wideV = 0.47f,
                buttons = listOf(
                    FaceButtonDef("b", "B", -30f, 0f, 56f, 48f, TouchOutput.Buttons(PadState.X)),
                    FaceButtonDef("a", "A", 30f, 0f, 56f, 48f, TouchOutput.Buttons(PadState.A)),
                ),
                chords = setOf(touchChord("a", "b")),
            ),
            FaceClusterControl(
                id = "c", anchor = TouchAnchor.RP, atU = 0.875f, atV = 0.778f,
                wideU = 0.875f, wideV = 0.778f,
                buttons = listOf(
                    FaceButtonDef("cup", "▲", 0f, -36f, 48f, 40f, TouchOutput.CButton(TouchOutput.CDir.UP)),
                    FaceButtonDef("cright", "▶", 36f, 0f, 48f, 40f, TouchOutput.CButton(TouchOutput.CDir.RIGHT)),
                    FaceButtonDef("cdown", "▼", 0f, 36f, 48f, 40f, TouchOutput.CButton(TouchOutput.CDir.DOWN)),
                    FaceButtonDef("cleft", "◀", -36f, 0f, 48f, 40f, TouchOutput.CButton(TouchOutput.CDir.LEFT)),
                ),
            ),
            KeyControl("l", TouchAnchor.LO, "L", 64f, 48f, output = TouchOutput.Buttons(PadState.L1)),
            KeyControl("r", TouchAnchor.RO, "R", 64f, 48f, output = TouchOutput.Buttons(PadState.R1)),
            KeyControl(
                "z", TouchAnchor.RA, "Z", 64f, 48f, output = TouchOutput.Trigger(left = true),
                // RA's own wide slot is mid-screen; Z stays put on wide phones.
                wideU = 0.80f, wideV = 0.25f,
            ),
            startAlone(),
            MenuControl(),
        ),
    )
    TouchLayoutId.PSX_DIGITAL -> TouchLayoutSpec(
        id, "PlayStation (digital)",
        listOf(
            DpadControl("dpad", TouchAnchor.LD, 120f),
            diamondCluster(
                "face", TouchAnchor.RD,
                faceButton("triangle", "△", PadState.Y), faceButton("circle", "○", PadState.B),
                faceButton("cross", "×", PadState.A), faceButton("square", "□", PadState.X),
            ),
            KeyControl("l1", TouchAnchor.LO, "L1", 64f, 48f, output = TouchOutput.Buttons(PadState.L1)),
            KeyControl("l2", TouchAnchor.LI, "L2", 64f, 48f, output = TouchOutput.Trigger(left = true)),
            KeyControl("r1", TouchAnchor.RO, "R1", 64f, 48f, output = TouchOutput.Buttons(PadState.R1)),
            KeyControl("r2", TouchAnchor.RI, "R2", 64f, 48f, output = TouchOutput.Trigger(left = false)),
        ) + selectStartPair() + MenuControl(),
    )
    TouchLayoutId.PSX_DUALSHOCK, TouchLayoutId.PS2 -> run {
        val name = if (id == TouchLayoutId.PS2) "PlayStation 2" else "PlayStation (DualShock)"
        TouchLayoutSpec(
            id, name,
            listOf(
                DpadControl("dpad", TouchAnchor.LP, 120f),
                diamondCluster(
                    "face", TouchAnchor.RP,
                    faceButton("triangle", "△", PadState.Y), faceButton("circle", "○", PadState.B),
                    faceButton("cross", "×", PadState.A), faceButton("square", "□", PadState.X),
                ),
                StickControl("stick_l", TouchAnchor.LS, right = false),
                StickControl("stick_r", TouchAnchor.RS, right = true, floatingDefault = true),
                KeyControl("l3", TouchAnchor.LA, "L3", 48f, 48f, circular = true, output = TouchOutput.Buttons(PadState.L3)),
                KeyControl("r3", TouchAnchor.RA, "R3", 48f, 48f, circular = true, output = TouchOutput.Buttons(PadState.R3)),
                KeyControl("l1", TouchAnchor.LO, "L1", 64f, 48f, output = TouchOutput.Buttons(PadState.L1)),
                KeyControl("l2", TouchAnchor.LI, "L2", 64f, 48f, output = TouchOutput.Trigger(left = true)),
                KeyControl("r1", TouchAnchor.RO, "R1", 64f, 48f, output = TouchOutput.Buttons(PadState.R1)),
                KeyControl("r2", TouchAnchor.RI, "R2", 64f, 48f, output = TouchOutput.Trigger(left = false)),
            ) + selectStartPair() + MenuControl(),
        )
    }
    TouchLayoutId.GC_CORE, TouchLayoutId.GC_STANDALONE -> run {
        val standalone = id == TouchLayoutId.GC_STANDALONE
        val faceBits = if (standalone) {
            mapOf("a" to PadState.A, "b" to PadState.X, "x" to PadState.B, "y" to PadState.Y)
        } else {
            mapOf("a" to PadState.B, "b" to PadState.A, "x" to PadState.Y, "y" to PadState.X)
        }
        val halfL = if (standalone) TouchOutput.HalfTrigger(left = true, bit = 0, axisValue = 128)
        else TouchOutput.HalfTrigger(left = true, bit = PadState.L3, axisValue = 0)
        val halfR = if (standalone) TouchOutput.HalfTrigger(left = false, bit = 0, axisValue = 128)
        else TouchOutput.HalfTrigger(left = false, bit = PadState.R3, axisValue = 0)
        TouchLayoutSpec(
            id, if (standalone) "GameCube (standalone)" else "GameCube (core)",
            listOf(
                StickControl("stick", TouchAnchor.LP, right = false),
                DpadControl("dpad", TouchAnchor.LS, 104f),
                StickControl("cstick", TouchAnchor.RS, right = true, wideU = 0.74f, wideV = 0.82f),
                FaceClusterControl(
                    id = "face", anchor = TouchAnchor.RP, atU = 0.91f, atV = 0.48f,
                    wideU = 0.915f, wideV = 0.68f,
                    buttons = listOf(
                        FaceButtonDef("a", "A", 0f, 0f, 64f, 56f, TouchOutput.Buttons(faceBits.getValue("a"))),
                        FaceButtonDef("b", "B", -54f, 40f, 48f, 40f, TouchOutput.Buttons(faceBits.getValue("b"))),
                        FaceButtonDef("x", "X", 42f, -46f, 48f, 40f, TouchOutput.Buttons(faceBits.getValue("x"))),
                        FaceButtonDef("y", "Y", -28f, -62f, 48f, 40f, TouchOutput.Buttons(faceBits.getValue("y"))),
                    ),
                    chords = setOf(touchChord("a", "b"), touchChord("a", "x"), touchChord("a", "y")),
                ),
                KeyControl("l", TouchAnchor.LO, "L", 64f, 48f, output = TouchOutput.Trigger(left = true), analogCapable = standalone),
                KeyControl("r", TouchAnchor.RO, "R", 64f, 48f, output = TouchOutput.Trigger(left = false), analogCapable = standalone),
                KeyControl("lhalf", TouchAnchor.LI, "L½", 64f, 48f, output = halfL),
                KeyControl("rhalf", TouchAnchor.RI, "R½", 64f, 48f, output = halfR),
                KeyControl("z", TouchAnchor.RA, "Z", 48f, 48f, circular = true, output = TouchOutput.Buttons(PadState.R1)),
                startAlone(),
                MenuControl(),
            ),
        )
    }
    TouchLayoutId.WII_NUNCHUK_RA, TouchLayoutId.WII_NUNCHUK_STANDALONE -> run {
        val standalone = id == TouchLayoutId.WII_NUNCHUK_STANDALONE
        val diamond = if (standalone) {
            diamondCluster(
                "face", TouchAnchor.RP,
                faceButton("b1", "1", PadState.X), faceButton("b2", "2", PadState.Y),
                faceButton("a", "A", PadState.A), faceButton("b", "B", PadState.B),
            )
        } else {
            diamondCluster(
                "face", TouchAnchor.RP,
                faceButton("b1", "1", PadState.START), faceButton("b2", "2", PadState.SELECT),
                faceButton("a", "A", PadState.B), faceButton("b", "B", PadState.A),
            )
        }
        val extras = if (standalone) {
            listOf(
                KeyControl("c", TouchAnchor.LO, "C", 64f, 48f, output = TouchOutput.Buttons(PadState.L1)),
                KeyControl("z", TouchAnchor.LI, "Z", 64f, 48f, output = TouchOutput.Trigger(left = true)),
                KeyControl("minus", TouchAnchor.SELECT, "−", 64f, 48f, output = TouchOutput.Buttons(PadState.SELECT), mirror = false),
                KeyControl("plus", TouchAnchor.START, "+", 64f, 48f, output = TouchOutput.Buttons(PadState.START), mirror = false),
            )
        } else {
            listOf(
                KeyControl("c", TouchAnchor.LO, "C", 64f, 48f, output = TouchOutput.Buttons(PadState.Y)),
                KeyControl("z", TouchAnchor.LI, "Z", 64f, 48f, output = TouchOutput.Buttons(PadState.X)),
                KeyControl("minus", TouchAnchor.SELECT, "−", 64f, 48f, output = TouchOutput.Buttons(PadState.L1), mirror = false),
                KeyControl("plus", TouchAnchor.START, "+", 64f, 48f, output = TouchOutput.Buttons(PadState.R1), mirror = false),
                KeyControl("home", TouchAnchor.RA, "Home", 48f, 48f, circular = true, output = TouchOutput.Buttons(PadState.R3)),
                KeyControl("shake_n", TouchAnchor.RI, "Shake N", 64f, 48f, output = TouchOutput.Trigger(left = true)),
                KeyControl("shake_r", TouchAnchor.RO, "Shake R", 64f, 48f, output = TouchOutput.Trigger(left = false)),
            )
        }
        TouchLayoutSpec(
            id, if (standalone) "Wii Remote + Nunchuk (standalone)" else "Wii Remote + Nunchuk",
            listOf(
                StickControl("nunchuk", TouchAnchor.LP, right = false),
                DpadControl("dpad", TouchAnchor.LS, 104f),
                diamond,
            ) + extras + MenuControl(),
        )
    }
    TouchLayoutId.SATURN, TouchLayoutId.SATURN_3D -> run {
        val threeD = id == TouchLayoutId.SATURN_3D
        fun sixGroup(anchor: TouchAnchor): FaceClusterControl = FaceClusterControl(
            id = "six", anchor = anchor,
            buttons = listOf(
                FaceButtonDef("x", "X", -56f, -28f, 48f, 40f, TouchOutput.Buttons(PadState.X)),
                FaceButtonDef("y", "Y", 0f, -28f, 48f, 40f, TouchOutput.Buttons(PadState.Y)),
                FaceButtonDef("z", "Z", 56f, -28f, 48f, 40f, TouchOutput.Buttons(PadState.L1)),
                FaceButtonDef("a", "A", -56f, 28f, 48f, 40f, TouchOutput.Buttons(PadState.A)),
                FaceButtonDef("b", "B", 0f, 28f, 48f, 40f, TouchOutput.Buttons(PadState.B)),
                FaceButtonDef("c", "C", 56f, 28f, 48f, 40f, TouchOutput.Buttons(PadState.R1)),
            ),
            chords = setOf(
                touchChord("x", "y"), touchChord("y", "z"),
                touchChord("a", "b"), touchChord("b", "c"),
            ),
        )
        val movement = if (threeD) {
            listOf(
                StickControl("stick", TouchAnchor.LP, right = false),
                DpadControl("dpad", TouchAnchor.LS, 104f),
                sixGroup(TouchAnchor.RP),
                KeyControl("l", TouchAnchor.LO, "L", 64f, 48f, output = TouchOutput.Trigger(left = true)),
                KeyControl("r", TouchAnchor.RO, "R", 64f, 48f, output = TouchOutput.Trigger(left = false)),
                KeyControl("mode", TouchAnchor.SELECT, "Mode", 64f, 48f, output = TouchOutput.Buttons(PadState.SELECT), mirror = false),
                KeyControl("start", TouchAnchor.START, "Start", 64f, 48f, output = TouchOutput.Buttons(PadState.START), mirror = false),
            )
        } else {
            listOf(
                DpadControl("dpad", TouchAnchor.LD, 120f),
                sixGroup(TouchAnchor.RD),
                KeyControl("l", TouchAnchor.LO, "L", 64f, 48f, output = TouchOutput.Trigger(left = true)),
                KeyControl("r", TouchAnchor.RO, "R", 64f, 48f, output = TouchOutput.Trigger(left = false)),
                startAlone(),
            )
        }
        TouchLayoutSpec(id, if (threeD) "Saturn (3D Pad)" else "Saturn", movement + MenuControl())
    }
    TouchLayoutId.DREAMCAST -> TouchLayoutSpec(
        id, "Dreamcast",
        listOf(
            StickControl("stick", TouchAnchor.LP, right = false),
            DpadControl("dpad", TouchAnchor.LS, 104f),
            diamondCluster(
                "face", TouchAnchor.RD,
                faceButton("y", "Y", PadState.Y), faceButton("b", "B", PadState.B),
                faceButton("a", "A", PadState.A), faceButton("x", "X", PadState.X),
            ),
            KeyControl("l", TouchAnchor.LO, "L", 64f, 48f, output = TouchOutput.Trigger(left = true), analogCapable = true),
            KeyControl("r", TouchAnchor.RO, "R", 64f, 48f, output = TouchOutput.Trigger(left = false), analogCapable = true),
            startAlone(),
            MenuControl(),
        ),
    )
    TouchLayoutId.NAOMI -> TouchLayoutSpec(
        id, "NAOMI",
        listOf(
            DpadControl("dpad", TouchAnchor.LD, 120f),
            FaceClusterControl(
                id = "six", anchor = TouchAnchor.RD,
                buttons = listOf(
                    FaceButtonDef("b1", "1", -56f, -28f, 48f, 40f, TouchOutput.Buttons(PadState.A)),
                    FaceButtonDef("b2", "2", 0f, -28f, 48f, 40f, TouchOutput.Buttons(PadState.B)),
                    FaceButtonDef("b3", "3", 56f, -28f, 48f, 40f, TouchOutput.Buttons(PadState.X)),
                    FaceButtonDef("b4", "4", -56f, 28f, 48f, 40f, TouchOutput.Buttons(PadState.Y)),
                    FaceButtonDef("b5", "5", 0f, 28f, 48f, 40f, TouchOutput.Buttons(PadState.R1)),
                    FaceButtonDef("b6", "6", 56f, 28f, 48f, 40f, TouchOutput.Buttons(PadState.L1)),
                ),
                chords = setOf(
                    touchChord("b1", "b2"), touchChord("b2", "b3"),
                    touchChord("b4", "b5"), touchChord("b5", "b6"),
                ),
            ),
            KeyControl("b8", TouchAnchor.LO, "8", 64f, 48f, output = TouchOutput.Trigger(left = true)),
            KeyControl("b7", TouchAnchor.RO, "7", 64f, 48f, output = TouchOutput.Trigger(left = false)),
            KeyControl("coin", TouchAnchor.SELECT, "Coin", 64f, 48f, output = TouchOutput.Buttons(PadState.SELECT), mirror = false),
            KeyControl("start", TouchAnchor.START, "Start", 64f, 48f, output = TouchOutput.Buttons(PadState.START), mirror = false),
            MenuControl(),
        ),
    )
    TouchLayoutId.TDO -> TouchLayoutSpec(
        id, "3DO",
        listOf(
            DpadControl("dpad", TouchAnchor.LD, 120f),
            FaceClusterControl(
                id = "abc", anchor = TouchAnchor.RD,
                buttons = listOf(
                    FaceButtonDef("a", "A", -50f, 20f, 52f, 44f, TouchOutput.Buttons(PadState.X)),
                    FaceButtonDef("b", "B", 0f, -12f, 52f, 44f, TouchOutput.Buttons(PadState.A)),
                    FaceButtonDef("c", "C", 50f, 20f, 52f, 44f, TouchOutput.Buttons(PadState.B)),
                ),
                chords = setOf(touchChord("a", "b"), touchChord("b", "c")),
            ),
            KeyControl("l", TouchAnchor.LO, "L", 64f, 48f, output = TouchOutput.Buttons(PadState.L1)),
            KeyControl("r", TouchAnchor.RO, "R", 64f, 48f, output = TouchOutput.Buttons(PadState.R1)),
            KeyControl("stop", TouchAnchor.SELECT, "■ Stop", 64f, 48f, output = TouchOutput.Buttons(PadState.SELECT), mirror = false),
            KeyControl("play", TouchAnchor.START, "▶❚❚", 64f, 48f, output = TouchOutput.Buttons(PadState.START), mirror = false),
            MenuControl(),
        ),
    )
}

/**
 * The layout table for [id] with preference variants applied: the N64
 * C-buttons/C-stick preset and analog-drag triggers where supported. Stick
 * Fixed/Floating resolves at geometry time (see [ResolvedStick]).
 */
fun touchLayoutSpec(id: TouchLayoutId, prefs: TouchPrefs): TouchLayoutSpec {
    val base = baseTouchLayoutSpec(id)
    val swappedC = prefs.n64CStick && id == TouchLayoutId.N64
    val drag = prefs.analogDragTriggers
    if (!swappedC && !drag) return base
    val c = base.controls.filterIsInstance<FaceClusterControl>().firstOrNull { it.id == "c" }
    val controls = base.controls.map { control ->
        if (swappedC && control.id == "c" && control is FaceClusterControl) {
            CStickControl(atU = c?.atU, atV = c?.atV, wideU = control.wideU, wideV = control.wideV)
        } else if (drag && control is KeyControl && control.analogCapable) {
            val out = control.output
            if (out is TouchOutput.Trigger) control.copy(output = out.copy(analogDrag = true)) else control
        } else {
            control
        }
    }
    return base.copy(controls = controls)
}

/** Right stick pinned to the N64 C-cluster center (the C-stick preset). */
data class CStickControl(
    val atU: Float?,
    val atV: Float?,
    override val wideU: Float?,
    override val wideV: Float?,
    override val id: String = "cstick",
    override val anchor: TouchAnchor = TouchAnchor.RP,
    val right: Boolean = true,
    val diameterDp: Float = 112f,
    val knobDp: Float = 40f,
    val travelDp: Float = 36f,
    val floatingDefault: Boolean = false,
    override val mirror: Boolean = true,
) : TouchControl {
    override val overlayOk: Boolean = false
}

data class TouchRectPx(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    fun grown(by: Float): TouchRectPx = TouchRectPx(left - by, top - by, right + by, bottom + by)
    fun intersects(o: TouchRectPx): Boolean = left < o.right && o.left < right && top < o.bottom && o.top < bottom
    fun contains(r: TouchRectPx): Boolean = left <= r.left && top <= r.top && r.right <= right && r.bottom <= bottom
}

data class ResolvedDpad(val control: DpadControl, val cx: Float, val cy: Float, val radiusPx: Float) {
    fun bounds(): TouchRectPx = TouchRectPx(cx - radiusPx, cy - radiusPx, cx + radiusPx, cy + radiusPx)
}

data class ResolvedStick(
    val control: TouchControl,
    val right: Boolean,
    val cx: Float,
    val cy: Float,
    val baseRadiusPx: Float,
    val knobRadiusPx: Float,
    val travelPx: Float,
    val floating: Boolean,
) {
    fun bounds(): TouchRectPx = TouchRectPx(cx - baseRadiusPx, cy - baseRadiusPx, cx + baseRadiusPx, cy + baseRadiusPx)
}

data class ResolvedFaceButton(
    val clusterId: String,
    val def: FaceButtonDef,
    val cx: Float,
    val cy: Float,
    val hitRadiusPx: Float,
    val artRadiusPx: Float,
)

data class ResolvedCluster(val control: FaceClusterControl, val buttons: List<ResolvedFaceButton>) {
    fun bounds(): TouchRectPx = TouchRectPx(
        buttons.minOf { it.cx - it.hitRadiusPx }, buttons.minOf { it.cy - it.hitRadiusPx },
        buttons.maxOf { it.cx + it.hitRadiusPx }, buttons.maxOf { it.cy + it.hitRadiusPx },
    )
}

data class ResolvedKey(val control: KeyControl, val rect: TouchRectPx) {
    val cx: Float get() = (rect.left + rect.right) / 2f
    val cy: Float get() = (rect.top + rect.bottom) / 2f
    val radiusPx: Float get() = rect.width / 2f
}

data class ResolvedMenu(val control: MenuControl, val rect: TouchRectPx)

data class ResolvedTouchLayout(
    val specId: TouchLayoutId,
    val wide: Boolean,
    val density: Float,
    val content: TouchRectPx,
    val dpads: List<ResolvedDpad>,
    val sticks: List<ResolvedStick>,
    val clusters: List<ResolvedCluster>,
    val keys: List<ResolvedKey>,
    val menu: ResolvedMenu?,
)

/**
 * Resolves [definition] to pixels inside a safe area of [safeWidthPx] x [safeHeightPx]
 * (origin at 0,0; the caller already applied system insets). Controls keep an
 * 8dp clearance inside the safe area. The side-bar arrangement is used only
 * when every non-exempt group fits beside the content rect with 8dp between
 * unrelated groups; otherwise the Wide anchors apply and controls may overlap
 * the picture. Sizes are preserved, never shrunk to fit.
 */
fun resolveTouchLayout(
    definition: TouchLayoutSpec,
    safeWidthPx: Float,
    safeHeightPx: Float,
    density: Float,
    prefs: TouchPrefs,
    viewport: TouchViewport?,
): ResolvedTouchLayout {
    val content = touchContentRect(safeWidthPx, safeHeightPx, prefs.contentAspect, viewport)
    val sideBar = resolveAttempt(definition, safeWidthPx, safeHeightPx, density, prefs, wide = false)
    if (touchFitsSideBar(sideBar, safeWidthPx, safeHeightPx, density, content)) {
        return sideBar.copy(content = content)
    }
    return touchShiftWideSecondaries(
        resolveAttempt(definition, safeWidthPx, safeHeightPx, density, prefs, wide = true).copy(content = content),
        safeWidthPx, density,
    )
}

/** Secondary groups, shifted inward in Wide mode. */
private val WIDE_SECONDARY_ANCHORS: Set<TouchAnchor> = setOf(TouchAnchor.LS, TouchAnchor.RS)

/**
 * In Wide mode each secondary (LS/RS) group moves horizontally toward the
 * screen center until its bounds clear every outer group's bounds by
 * [TOUCH_CLEARANCE_DP], or the center is reached. Sizes are preserved.
 * The Menu utility is not an outer group.
 */
fun touchShiftWideSecondaries(layout: ResolvedTouchLayout, safeW: Float, density: Float): ResolvedTouchLayout {
    if (!layout.wide) return layout
    val m = TOUCH_CLEARANCE_DP * density
    val centerX = safeW / 2f
    val outer = mutableListOf<TouchRectPx>()
    layout.dpads.forEach { if (it.control.anchor !in WIDE_SECONDARY_ANCHORS) outer += it.bounds().grown(m) }
    layout.sticks.forEach { if (it.control.anchor !in WIDE_SECONDARY_ANCHORS) outer += it.bounds().grown(m) }
    layout.clusters.forEach { if (it.control.anchor !in WIDE_SECONDARY_ANCHORS) outer += it.bounds().grown(m) }
    layout.keys.forEach { if (it.control.anchor !in WIDE_SECONDARY_ANCHORS) outer += it.rect.grown(m) }
    if (outer.isEmpty()) return layout
    fun shiftFor(cx: Float, bounds: TouchRectPx): Float {
        if (cx == centerX) return 0f
        val dir = if (cx < centerX) 1f else -1f
        var dx = 0f
        var steps = 0
        while (steps < 400 && (cx + dx - centerX) * dir < 0f) {
            val grown = TouchRectPx(bounds.left + dx - m, bounds.top - m, bounds.right + dx + m, bounds.bottom + m)
            if (outer.none { it.intersects(grown) }) return dx
            dx += dir * density
            steps++
        }
        return dx
    }
    return layout.copy(
        dpads = layout.dpads.map {
            if (it.control.anchor in WIDE_SECONDARY_ANCHORS) it.copy(cx = it.cx + shiftFor(it.cx, it.bounds())) else it
        },
        sticks = layout.sticks.map {
            if (it.control.anchor in WIDE_SECONDARY_ANCHORS) it.copy(cx = it.cx + shiftFor(it.cx, it.bounds())) else it
        },
        clusters = layout.clusters.map { cluster ->
            if (cluster.control.anchor !in WIDE_SECONDARY_ANCHORS) cluster
            else {
                val bounds = cluster.bounds()
                val dx = shiftFor((bounds.left + bounds.right) / 2f, bounds)
                if (dx == 0f) cluster else cluster.copy(buttons = cluster.buttons.map { b -> b.copy(cx = b.cx + dx) })
            }
        },
        keys = layout.keys.map {
            if (it.control.anchor in WIDE_SECONDARY_ANCHORS) {
                val dx = shiftFor((it.rect.left + it.rect.right) / 2f, it.rect)
                if (dx == 0f) it else it.copy(rect = TouchRectPx(it.rect.left + dx, it.rect.top, it.rect.right + dx, it.rect.bottom))
            } else it
        },
    )
}

/**
 * The largest size percent in [TouchPrefs.SIZE_MIN, requested] (step
 * [TouchPrefs.SIZE_STEP]) whose resolved layout has no overlapping hit areas.
 * The Touch controls slider shows this value when clamping applies.
 */
fun effectiveTouchSizePercent(
    definition: TouchLayoutSpec,
    safeW: Float,
    safeH: Float,
    density: Float,
    prefs: TouchPrefs,
    viewport: TouchViewport?,
): Int {
    var size = prefs.validated().sizePercent
    while (size > TouchPrefs.SIZE_MIN) {
        val layout = resolveTouchLayout(definition, safeW, safeH, density, prefs.copy(sizePercent = size), viewport)
        if (touchLayoutOverlaps(layout).isEmpty()) return size
        size -= TouchPrefs.SIZE_STEP
    }
    return TouchPrefs.SIZE_MIN
}

private fun resolveAttempt(
    definition: TouchLayoutSpec,
    safeW: Float,
    safeH: Float,
    density: Float,
    prefs: TouchPrefs,
    wide: Boolean,
): ResolvedTouchLayout {
    val baseScale = prefs.sizePercent / 100f
    val dpads = mutableListOf<ResolvedDpad>()
    val sticks = mutableListOf<ResolvedStick>()
    val clusters = mutableListOf<ResolvedCluster>()
    val keys = mutableListOf<ResolvedKey>()
    var menu: ResolvedMenu? = null
    definition.controls.forEach { control ->
        var u = if (wide) control.anchor.wideU else control.anchor.u
        var v = if (wide) control.anchor.wideV else control.anchor.v
        if (control is FaceClusterControl) {
            if (!wide && control.atU != null && control.atV != null) {
                u = control.atU; v = control.atV
            }
        }
        if (control is CStickControl) {
            if (!wide && control.atU != null && control.atV != null) {
                u = control.atU; v = control.atV
            }
        }
        val wideU = control.wideU
        val wideV = control.wideV
        if (wide && wideU != null && wideV != null) {
            u = wideU; v = wideV
        }
        if (prefs.swapSides && control.mirror) u = 1f - u
        val centerX = u * safeW
        val centerY = v * safeH
        val minHitDp = touchMinHitDp(control)
        val scale = maxOf(baseScale, TOUCH_MIN_TARGET_DP / minHitDp)
        val clampDx: (TouchRectPx) -> Float = { bounds ->
            val m = TOUCH_CLEARANCE_DP * density
            if (bounds.left < m) m - bounds.left else if (bounds.right > safeW - m) safeW - m - bounds.right else 0f
        }
        val clampDy: (TouchRectPx) -> Float = { bounds ->
            val m = TOUCH_CLEARANCE_DP * density
            if (bounds.top < m) m - bounds.top else if (bounds.bottom > safeH - m) safeH - m - bounds.bottom else 0f
        }
        when (control) {
            is DpadControl -> {
                val r = control.diameterDp / 2f * scale * density
                val dx = clampDx(TouchRectPx(centerX - r, centerY - r, centerX + r, centerY + r))
                val dy = clampDy(TouchRectPx(centerX - r, centerY - r, centerX + r, centerY + r))
                dpads += ResolvedDpad(control, centerX + dx, centerY + dy, r)
            }
            is StickControl -> {
                val floating = prefs.stickMode == TouchStickMode.FLOATING || control.floatingDefault
                val r = control.diameterDp / 2f * scale * density
                val dx = clampDx(TouchRectPx(centerX - r, centerY - r, centerX + r, centerY + r))
                val dy = clampDy(TouchRectPx(centerX - r, centerY - r, centerX + r, centerY + r))
                sticks += ResolvedStick(
                    control, control.right, centerX + dx, centerY + dy, r,
                    control.knobDp / 2f * scale * density, control.travelDp * scale * density, floating,
                )
            }
            is CStickControl -> {
                val floating = prefs.stickMode == TouchStickMode.FLOATING || control.floatingDefault
                val r = control.diameterDp / 2f * scale * density
                val dx = clampDx(TouchRectPx(centerX - r, centerY - r, centerX + r, centerY + r))
                val dy = clampDy(TouchRectPx(centerX - r, centerY - r, centerX + r, centerY + r))
                sticks += ResolvedStick(
                    control, control.right, centerX + dx, centerY + dy, r,
                    control.knobDp / 2f * scale * density, control.travelDp * scale * density, floating,
                )
            }
            is FaceClusterControl -> {
                val resolved = control.buttons.map { def ->
                    val cx = centerX + def.offsetXDp * scale * density
                    val cy = centerY + def.offsetYDp * scale * density
                    ResolvedFaceButton(control.id, def, cx, cy, def.hitDp / 2f * scale * density, def.artDp / 2f * scale * density)
                }
                val bounds = TouchRectPx(
                    resolved.minOf { it.cx - it.hitRadiusPx }, resolved.minOf { it.cy - it.hitRadiusPx },
                    resolved.maxOf { it.cx + it.hitRadiusPx }, resolved.maxOf { it.cy + it.hitRadiusPx },
                )
                val dx = clampDx(bounds)
                val dy = clampDy(bounds)
                clusters += ResolvedCluster(control, resolved.map { it.copy(cx = it.cx + dx, cy = it.cy + dy) })
            }
            is KeyControl -> {
                val w = control.widthDp * scale * density
                val h = control.heightDp * scale * density
                val bounds = TouchRectPx(centerX - w / 2f, centerY - h / 2f, centerX + w / 2f, centerY + h / 2f)
                val dx = clampDx(bounds)
                val dy = clampDy(bounds)
                keys += ResolvedKey(control, TouchRectPx(bounds.left + dx, bounds.top + dy, bounds.right + dx, bounds.bottom + dy))
            }
            is MenuControl -> {
                val s = control.sizeDp * density
                val bounds = TouchRectPx(centerX - s / 2f, centerY - s / 2f, centerX + s / 2f, centerY + s / 2f)
                val dx = clampDx(bounds)
                val dy = clampDy(bounds)
                menu = ResolvedMenu(control, TouchRectPx(bounds.left + dx, bounds.top + dy, bounds.right + dx, bounds.bottom + dy))
            }
        }
    }
    return ResolvedTouchLayout(definition.id, wide, density, TouchRectPx(0f, 0f, safeW, safeH), dpads, sticks, clusters, keys, menu)
}

private fun touchMinHitDp(control: TouchControl): Float = when (control) {
    is DpadControl -> control.diameterDp
    is StickControl -> control.diameterDp
    is CStickControl -> control.diameterDp
    is FaceClusterControl -> control.buttons.minOf { it.hitDp }
    is KeyControl -> minOf(control.widthDp, control.heightDp)
    is MenuControl -> control.sizeDp
}

/** The assumed content rect: the aspect-fit frame, narrowed by a host viewport. */
fun touchContentRect(
    safeW: Float,
    safeH: Float,
    aspect: TouchContentAspect,
    viewport: TouchViewport?,
): TouchRectPx {
    val ratio = if (aspect == TouchContentAspect.FOUR_THREE) 4f / 3f else 16f / 9f
    val frame = if (safeW / safeH > ratio) {
        val w = safeH * ratio
        TouchRectPx((safeW - w) / 2f, 0f, (safeW + w) / 2f, safeH)
    } else {
        val h = safeW / ratio
        TouchRectPx(0f, (safeH - h) / 2f, safeW, (safeH + h) / 2f)
    }
    if (viewport == null) return frame
    return TouchRectPx(
        frame.left + viewport.u0.coerceIn(0f, 1f) * frame.width,
        frame.top + viewport.v0.coerceIn(0f, 1f) * frame.height,
        frame.left + viewport.u1.coerceIn(0f, 1f) * frame.width,
        frame.top + viewport.v1.coerceIn(0f, 1f) * frame.height,
    )
}

private fun touchFitsSideBar(
    layout: ResolvedTouchLayout,
    safeW: Float,
    safeH: Float,
    density: Float,
    content: TouchRectPx,
): Boolean {
    val m = TOUCH_CLEARANCE_DP * density
    val safe = TouchRectPx(m, m, safeW - m, safeH - m)
    val blocked = content.grown(m)
    fun TouchRectPx.insideSafe(): Boolean = safe.contains(this)
    layout.dpads.forEach { if (!it.bounds().insideSafe() || it.bounds().intersects(blocked)) return false }
    layout.sticks.forEach { if (!it.bounds().insideSafe() || it.bounds().intersects(blocked)) return false }
    layout.clusters.forEach { if (!it.bounds().insideSafe() || it.bounds().intersects(blocked)) return false }
    layout.keys.forEach {
        if (!it.rect.insideSafe()) return false
        if (!it.control.overlayOk && it.rect.intersects(blocked)) return false
    }
    return true
}

/**
 * Hit-shape intersections between unrelated controls (different control ids).
 * Buttons inside one cluster are related by construction. Empty means the
 * layout has no unintended target overlap.
 */
fun touchLayoutOverlaps(layout: ResolvedTouchLayout): List<String> {
    data class Shape(val id: String, val label: String, val circle: FloatArray?, val rect: TouchRectPx?)
    val shapes = mutableListOf<Shape>()
    layout.dpads.forEach { shapes += Shape(it.control.id, it.control.id, floatArrayOf(it.cx, it.cy, it.radiusPx), null) }
    layout.sticks.forEach { shapes += Shape(it.control.id, it.control.id, floatArrayOf(it.cx, it.cy, it.baseRadiusPx), null) }
    layout.clusters.forEach { cluster ->
        cluster.buttons.forEach { b ->
            shapes += Shape(cluster.control.id, "${cluster.control.id}:${b.def.id}", floatArrayOf(b.cx, b.cy, b.hitRadiusPx), null)
        }
    }
    layout.keys.forEach {
        if (it.control.circular) shapes += Shape(it.control.id, it.control.id, floatArrayOf(it.cx, it.cy, it.radiusPx), null)
        else shapes += Shape(it.control.id, it.control.id, null, it.rect)
    }
    layout.menu?.let { shapes += Shape(it.control.id, it.control.id, null, it.rect) }
    fun circlesHit(a: FloatArray, b: FloatArray): Boolean {
        val dx = a[0] - b[0]
        val dy = a[1] - b[1]
        return dx * dx + dy * dy < (a[2] + b[2]) * (a[2] + b[2]) - 0.25f
    }
    fun circleRectHit(c: FloatArray, r: TouchRectPx): Boolean {
        val nx = c[0].coerceIn(r.left, r.right)
        val ny = c[1].coerceIn(r.top, r.bottom)
        val dx = c[0] - nx
        val dy = c[1] - ny
        return dx * dx + dy * dy < c[2] * c[2] - 0.25f
    }
    val overlaps = mutableListOf<String>()
    for (i in shapes.indices) {
        for (j in i + 1 until shapes.size) {
            val a = shapes[i]
            val b = shapes[j]
            if (a.id == b.id) continue
            val hit = if (a.circle != null && b.circle != null) circlesHit(a.circle, b.circle)
            else if (a.circle != null && b.rect != null) circleRectHit(a.circle, b.rect)
            else if (a.rect != null && b.circle != null) circleRectHit(b.circle, a.rect)
            else a.rect!!.intersects(b.rect!!)
            if (hit) overlaps += "${a.label} <-> ${b.label}"
        }
    }
    return overlaps.sorted()
}

