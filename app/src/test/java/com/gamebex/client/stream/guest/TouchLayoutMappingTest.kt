package com.gamebex.client.stream.guest

import com.gamebex.client.stream.proto.PadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchLayoutMappingTest {
    private fun resolved(
        id: TouchLayoutId,
        prefs: TouchPrefs = TouchPrefs(),
        w: Float = 915f,
        h: Float = 411f,
    ): ResolvedTouchLayout = resolveTouchLayout(touchLayoutSpec(id, prefs), w, h, 1f, prefs, null)

    private fun tap(layout: ResolvedTouchLayout, x: Float, y: Float): PadState {
        val t = TouchHitTester(layout)
        val state = t.onDown(1, x, y)
        t.onUp(1, x, y)
        return state
    }

    private fun buttonTap(layout: ResolvedTouchLayout, clusterId: String, buttonId: String): PadState {
        val b = layout.clusters.first { it.control.id == clusterId }.buttons.first { it.def.id == buttonId }
        return tap(layout, b.cx, b.cy)
    }

    private fun keyTap(layout: ResolvedTouchLayout, id: String): PadState {
        val k = layout.keys.first { it.control.id == id }
        return tap(layout, k.cx, k.cy)
    }

    private fun stickDrag(layout: ResolvedTouchLayout, right: Boolean, dx: Float, dy: Float): PadState {
        val s = layout.sticks.first { it.right == right }
        val t = TouchHitTester(layout)
        t.onDown(1, s.cx, s.cy)
        return t.onMove(1, s.cx + dx, s.cy + dy)
    }

    @Test
    fun genericCoversDpadDiamondShouldersAndUtilities() {
        val layout = resolved(TouchLayoutId.GENERIC)
        val dpad = layout.dpads.first()
        assertEquals(PadState.HAT_RIGHT, tap(layout, dpad.cx + dpad.radiusPx * 0.5f, dpad.cy).hat)
        assertEquals(PadState.Y, buttonTap(layout, "face", "y").buttons)
        assertEquals(PadState.B, buttonTap(layout, "face", "b").buttons)
        assertEquals(PadState.A, buttonTap(layout, "face", "a").buttons)
        assertEquals(PadState.X, buttonTap(layout, "face", "x").buttons)
        assertEquals(PadState.L1, keyTap(layout, "l").buttons)
        assertEquals(PadState.R1, keyTap(layout, "r").buttons)
        assertEquals(PadState.SELECT, keyTap(layout, "select").buttons)
        assertEquals(PadState.START, keyTap(layout, "start").buttons)
        val stick = stickDrag(layout, right = false, 36f, 0f)
        assertEquals(127, stick.lx)
    }

    @Test
    fun n64FollowsRetroPadBAndCConventions() {
        val layout = resolved(TouchLayoutId.N64)
        assertEquals(PadState.X, buttonTap(layout, "ab", "b").buttons) // N64 B via RetroPad Y.
        assertEquals(PadState.A, buttonTap(layout, "ab", "a").buttons) // N64 A via RetroPad B.
        val up = buttonTap(layout, "c", "cup")
        assertEquals(-127, up.ry)
        assertEquals(0, up.rx)
        assertEquals(127, buttonTap(layout, "c", "cright").rx)
        assertEquals(127, buttonTap(layout, "c", "cdown").ry)
        assertEquals(-127, buttonTap(layout, "c", "cleft").rx)
        assertEquals(PadState.L1, keyTap(layout, "l").buttons)
        assertEquals(PadState.R1, keyTap(layout, "r").buttons)
        val z = keyTap(layout, "z")
        assertEquals(PadState.L2, z.buttons) // Z via RetroPad L2, never L1.
        assertEquals(255, z.lt)
        assertEquals(PadState.START, keyTap(layout, "start").buttons)
    }

    @Test
    fun psxDigitalHidesAnalogControls() {
        val layout = resolved(TouchLayoutId.PSX_DIGITAL)
        assertTrue(layout.sticks.isEmpty())
        val dpad = layout.dpads.first()
        assertEquals(PadState.HAT_UP, tap(layout, dpad.cx, dpad.cy - dpad.radiusPx * 0.5f).hat)
        assertEquals(PadState.Y, buttonTap(layout, "face", "triangle").buttons)
        assertEquals(PadState.B, buttonTap(layout, "face", "circle").buttons)
        assertEquals(PadState.A, buttonTap(layout, "face", "cross").buttons)
        assertEquals(PadState.X, buttonTap(layout, "face", "square").buttons)
        assertEquals(PadState.L1, keyTap(layout, "l1").buttons)
        assertEquals(PadState.L2, keyTap(layout, "l2").buttons)
        assertEquals(255, keyTap(layout, "l2").lt)
        assertEquals(PadState.R1, keyTap(layout, "r1").buttons)
        assertEquals(255, keyTap(layout, "r2").rt)
    }

    @Test
    fun psxDualshockAddsBothSticksAndClicks() {
        val layout = resolved(TouchLayoutId.PSX_DUALSHOCK)
        assertEquals(PadState.L3, keyTap(layout, "l3").buttons)
        assertEquals(PadState.R3, keyTap(layout, "r3").buttons)
        assertEquals(-127, stickDrag(layout, right = false, 0f, -36f).ly)
        assertEquals(127, stickDrag(layout, right = true, 0f, 36f).ry)
    }

    @Test
    fun ps2SharesDualshockGeometry() {
        val ps2 = resolved(TouchLayoutId.PS2)
        val ds = resolved(TouchLayoutId.PSX_DUALSHOCK)
        assertEquals(ds.controlsIds(), ps2.controlsIds())
        assertEquals(PadState.A, buttonTap(ps2, "face", "cross").buttons)
        assertEquals(255, keyTap(ps2, "l2").lt)
    }

    @Test
    fun gameCubeCoreUsesHalfTriggerBits() {
        val layout = resolved(TouchLayoutId.GC_CORE)
        assertEquals(PadState.B, buttonTap(layout, "face", "a").buttons)
        assertEquals(PadState.A, buttonTap(layout, "face", "b").buttons)
        assertEquals(PadState.Y, buttonTap(layout, "face", "x").buttons)
        assertEquals(PadState.X, buttonTap(layout, "face", "y").buttons)
        assertEquals(255, keyTap(layout, "l").lt)
        assertEquals(PadState.L2, keyTap(layout, "l").buttons)
        val half = keyTap(layout, "lhalf")
        assertEquals(PadState.L3, half.buttons)
        assertEquals(0, half.lt)
        assertEquals(PadState.R1, keyTap(layout, "z").buttons)
        assertEquals(-127, stickDrag(layout, right = true, 0f, -36f).ry)
    }

    @Test
    fun gameCubeStandaloneUsesAnalogHalves() {
        val layout = resolved(TouchLayoutId.GC_STANDALONE)
        assertEquals(PadState.A, buttonTap(layout, "face", "a").buttons)
        assertEquals(PadState.X, buttonTap(layout, "face", "b").buttons)
        assertEquals(PadState.B, buttonTap(layout, "face", "x").buttons)
        assertEquals(PadState.Y, buttonTap(layout, "face", "y").buttons)
        val half = keyTap(layout, "lhalf")
        assertEquals(0, half.buttons)
        assertEquals(128, half.lt)
        val rhalf = keyTap(layout, "rhalf")
        assertEquals(128, rhalf.rt)
    }

    @Test
    fun wiiRemoteNunchukCoreBindsButtonsOnly() {
        val layout = resolved(TouchLayoutId.WII_NUNCHUK_RA)
        assertEquals(PadState.START, buttonTap(layout, "face", "b1").buttons)
        assertEquals(PadState.SELECT, buttonTap(layout, "face", "b2").buttons)
        assertEquals(PadState.B, buttonTap(layout, "face", "a").buttons)
        assertEquals(PadState.A, buttonTap(layout, "face", "b").buttons)
        assertEquals(PadState.Y, keyTap(layout, "c").buttons)
        assertEquals(PadState.X, keyTap(layout, "z").buttons)
        assertEquals(PadState.L1, keyTap(layout, "minus").buttons)
        assertEquals(PadState.R1, keyTap(layout, "plus").buttons)
        assertEquals(PadState.R3, keyTap(layout, "home").buttons)
        assertEquals(255, keyTap(layout, "shake_n").lt)
        assertEquals(255, keyTap(layout, "shake_r").rt)
        assertTrue(layout.sticks.none { it.right }) // No IR pointer without host bindings.
    }

    @Test
    fun wiiStandaloneOmitsUnboundControls() {
        val layout = resolved(TouchLayoutId.WII_NUNCHUK_STANDALONE)
        assertEquals(PadState.X, buttonTap(layout, "face", "b1").buttons)
        assertEquals(PadState.Y, buttonTap(layout, "face", "b2").buttons)
        assertEquals(PadState.A, buttonTap(layout, "face", "a").buttons)
        assertEquals(PadState.B, buttonTap(layout, "face", "b").buttons)
        assertEquals(PadState.L1, keyTap(layout, "c").buttons)
        assertEquals(255, keyTap(layout, "z").lt)
        assertEquals(PadState.SELECT, keyTap(layout, "minus").buttons)
        assertEquals(PadState.START, keyTap(layout, "plus").buttons)
        val ids = layout.keys.map { it.control.id }.toSet()
        assertFalse("home" in ids)
        assertFalse("shake_n" in ids)
        assertFalse("shake_r" in ids)
    }

    @Test
    fun saturnSixButtonRowsMapToShoulders() {
        val layout = resolved(TouchLayoutId.SATURN)
        assertEquals(PadState.X, buttonTap(layout, "six", "x").buttons)
        assertEquals(PadState.Y, buttonTap(layout, "six", "y").buttons)
        assertEquals(PadState.L1, buttonTap(layout, "six", "z").buttons)
        assertEquals(PadState.A, buttonTap(layout, "six", "a").buttons)
        assertEquals(PadState.B, buttonTap(layout, "six", "b").buttons)
        assertEquals(PadState.R1, buttonTap(layout, "six", "c").buttons)
        assertEquals(255, keyTap(layout, "l").lt)
        assertEquals(255, keyTap(layout, "r").rt)
    }

    @Test
    fun saturn3dAddsStickAndModeSwitch() {
        val layout = resolved(TouchLayoutId.SATURN_3D)
        assertEquals(1, layout.sticks.size)
        assertEquals(127, stickDrag(layout, right = false, 36f, 0f).lx)
        assertEquals(PadState.SELECT, keyTap(layout, "mode").buttons)
        assertEquals(PadState.A, buttonTap(layout, "six", "a").buttons)
    }

    @Test
    fun dreamcastDrivesTriggersWithFullPressBits() {
        val layout = resolved(TouchLayoutId.DREAMCAST)
        assertEquals(PadState.Y, buttonTap(layout, "face", "y").buttons)
        assertEquals(PadState.B, buttonTap(layout, "face", "b").buttons)
        assertEquals(PadState.A, buttonTap(layout, "face", "a").buttons)
        assertEquals(PadState.X, buttonTap(layout, "face", "x").buttons)
        val l = keyTap(layout, "l")
        assertEquals(PadState.L2, l.buttons)
        assertEquals(255, l.lt)
        val r = keyTap(layout, "r")
        assertEquals(PadState.R2, r.buttons)
        assertEquals(255, r.rt)
        assertTrue(layout.keys.none { it.control.id == "select" })
    }

    @Test
    fun naomiUsesGenericArcadeNumbering() {
        val layout = resolved(TouchLayoutId.NAOMI)
        assertEquals(PadState.A, buttonTap(layout, "six", "b1").buttons)
        assertEquals(PadState.B, buttonTap(layout, "six", "b2").buttons)
        assertEquals(PadState.X, buttonTap(layout, "six", "b3").buttons)
        assertEquals(PadState.Y, buttonTap(layout, "six", "b4").buttons)
        assertEquals(PadState.R1, buttonTap(layout, "six", "b5").buttons)
        assertEquals(PadState.L1, buttonTap(layout, "six", "b6").buttons)
        assertEquals(255, keyTap(layout, "b7").rt)
        assertEquals(PadState.R2, keyTap(layout, "b7").buttons)
        assertEquals(255, keyTap(layout, "b8").lt)
        assertEquals(PadState.SELECT, keyTap(layout, "coin").buttons)
        assertEquals(PadState.START, keyTap(layout, "start").buttons)
    }

    @Test
    fun threeDoHasNoSticks() {
        val layout = resolved(TouchLayoutId.TDO)
        assertTrue(layout.sticks.isEmpty())
        assertEquals(PadState.X, buttonTap(layout, "abc", "a").buttons)
        assertEquals(PadState.A, buttonTap(layout, "abc", "b").buttons)
        assertEquals(PadState.B, buttonTap(layout, "abc", "c").buttons)
        assertEquals(PadState.L1, keyTap(layout, "l").buttons)
        assertEquals(PadState.R1, keyTap(layout, "r").buttons)
        assertEquals(PadState.SELECT, keyTap(layout, "stop").buttons)
        assertEquals(PadState.START, keyTap(layout, "play").buttons)
    }

    private fun ResolvedTouchLayout.controlsIds(): Set<String> =
        dpads.map { it.control.id }.toSet() + sticks.map { it.control.id }.toSet() +
            clusters.map { it.control.id }.toSet() + keys.map { it.control.id }.toSet()

    @Test
    fun profileSelectionFallsBackToGenericWithoutGuessing() {
        fun profile(folder: String, backend: TouchBackend, mode: String = "") =
            TouchProfile(folder, backend, mode)
        assertEquals(TouchLayoutId.GENERIC, selectTouchLayout(null))
        assertEquals(TouchLayoutId.N64, selectTouchLayout(profile("n64", TouchBackend.RETROARCH)))
        assertEquals(TouchLayoutId.GENERIC, selectTouchLayout(profile("n64", TouchBackend.DOLPHIN)))
        assertEquals(
            TouchLayoutId.PSX_DUALSHOCK,
            selectTouchLayout(profile("psx", TouchBackend.RETROARCH, "psx_dualshock")),
        )
        assertEquals(TouchLayoutId.PSX_DIGITAL, selectTouchLayout(profile("psx", TouchBackend.RETROARCH)))
        assertEquals(TouchLayoutId.GENERIC, selectTouchLayout(profile("psx", TouchBackend.DOLPHIN)))
        assertEquals(TouchLayoutId.PS2, selectTouchLayout(profile("ps2", TouchBackend.NETHERSX2)))
        assertEquals(TouchLayoutId.PS2, selectTouchLayout(profile("ps2", TouchBackend.ARMSX2)))
        assertEquals(TouchLayoutId.GENERIC, selectTouchLayout(profile("ps2", TouchBackend.RETROARCH)))
        assertEquals(TouchLayoutId.GC_CORE, selectTouchLayout(profile("gc", TouchBackend.RETROARCH)))
        assertEquals(TouchLayoutId.GC_STANDALONE, selectTouchLayout(profile("gc", TouchBackend.DOLPHIN)))
        assertEquals(TouchLayoutId.GENERIC, selectTouchLayout(profile("gc", TouchBackend.UNKNOWN)))
        assertEquals(
            TouchLayoutId.WII_NUNCHUK_RA,
            selectTouchLayout(profile("wii", TouchBackend.RETROARCH, "wii_nunchuk")),
        )
        assertEquals(
            TouchLayoutId.WII_NUNCHUK_STANDALONE,
            selectTouchLayout(profile("wii", TouchBackend.DOLPHIN, "wii_nunchuk")),
        )
        assertEquals(TouchLayoutId.GENERIC, selectTouchLayout(profile("wii", TouchBackend.RETROARCH)))
        assertEquals(TouchLayoutId.SATURN_3D, selectTouchLayout(profile("saturn", TouchBackend.RETROARCH, "saturn_3d")))
        assertEquals(TouchLayoutId.SATURN, selectTouchLayout(profile("saturn", TouchBackend.RETROARCH)))
        assertEquals(TouchLayoutId.DREAMCAST, selectTouchLayout(profile("dreamcast", TouchBackend.RETROARCH)))
        assertEquals(TouchLayoutId.NAOMI, selectTouchLayout(profile("naomi", TouchBackend.RETROARCH)))
        assertEquals(TouchLayoutId.TDO, selectTouchLayout(profile("3do", TouchBackend.RETROARCH)))
        assertEquals(TouchLayoutId.GENERIC, selectTouchLayout(profile("snes", TouchBackend.RETROARCH)))
    }

    @Test
    fun phoneLandscapeUsesSideBarsBut169FallsWide() {
        TouchLayoutId.entries.forEach { id ->
            assertFalse("$id should fit beside 4:3 content", resolved(id).wide)
        }
        val wide = TouchPrefs(contentAspect = TouchContentAspect.SIXTEEN_NINE)
        assertTrue(resolved(TouchLayoutId.GENERIC, prefs = wide).wide)
        assertTrue(resolved(TouchLayoutId.N64, prefs = wide).wide)
        assertTrue(resolved(TouchLayoutId.GC_CORE, prefs = wide).wide)
    }

    @Test
    fun noLayoutHasUnintendedTargetOverlap() {
        // Reference phone in both branches; on small screens (800x360dp) Wii
        // Home/shake collide under fixed anchors.
        val prefSets = listOf(TouchPrefs(), TouchPrefs(sizePercent = 85), TouchPrefs(contentAspect = TouchContentAspect.SIXTEEN_NINE))
        TouchLayoutId.entries.forEach { id ->
            prefSets.forEach { p ->
                val layout = resolved(id, prefs = p, w = 915f, h = 411f)
                assertEquals("$id aspect=${p.contentAspect} size=${p.sizePercent}", emptyList<String>(), touchLayoutOverlaps(layout))
            }
        }
    }

    @Test
    fun swapSidesMirrorsPositionsButKeepsOutputs() {
        val plain = resolved(TouchLayoutId.N64)
        val swapped = resolved(TouchLayoutId.N64, prefs = TouchPrefs(swapSides = true))
        val w = 915f
        val origCup = plain.clusters.first { it.control.id == "c" }.buttons.first { it.def.id == "cup" }
        val swapCup = swapped.clusters.first { it.control.id == "c" }.buttons.first { it.def.id == "cup" }
        assertEquals(origCup.cx, w - swapCup.cx, 0.01f)
        assertEquals(origCup.cy, swapCup.cy, 0.01f)
        val t = TouchHitTester(swapped)
        assertEquals(-127, t.onDown(1, swapCup.cx, swapCup.cy).ry)
        t.onUp(1, swapCup.cx, swapCup.cy)
        assertEquals(PadState.A, buttonTap(swapped, "ab", "a").buttons)
        // Center utilities stay put.
        val plainStart = plain.keys.first { it.control.id == "start" }
        val swapStart = swapped.keys.first { it.control.id == "start" }
        assertEquals(plainStart.cx, swapStart.cx, 0.01f)
    }

    @Test
    fun sizeFloorNeverShrinksATargetBelow48dp() {
        val prefs = TouchPrefs(sizePercent = 85)
        TouchLayoutId.entries.forEach { id ->
            val layout = resolved(id, prefs = prefs)
            layout.dpads.forEach { assertTrue("$id dpad", it.radiusPx * 2f >= 48f - 0.01f) }
            layout.sticks.forEach { assertTrue("$id stick", it.baseRadiusPx * 2f >= 48f - 0.01f) }
            layout.clusters.forEach { cluster ->
                cluster.buttons.forEach { assertTrue("$id ${it.def.id}", it.hitRadiusPx * 2f >= 48f - 0.01f) }
            }
            layout.keys.forEach {
                assertTrue("$id ${it.control.id}", minOf(it.rect.width, it.rect.height) >= 48f - 0.01f)
            }
        }
    }

    @Test
    fun n64MatchesRetroArchOverlayGeometry() {
        // The fallback matches RA's n64.cfg (v3 APK assets): A/B level
        // at (0.875, 0.47), C cluster at (0.875, 0.778), Z at (0.80, 0.25).
        val w = 915f
        val h = 411f
        val layout = resolved(TouchLayoutId.N64, w = w, h = h)
        val ab = layout.clusters.first { it.control.id == "ab" }.buttons
        val a = ab.first { it.def.id == "a" }
        val b = ab.first { it.def.id == "b" }
        assertEquals(a.cy, b.cy, 0.01f) // Level, B left of A.
        assertTrue(b.cx < a.cx)
        assertEquals(0.875f * w, (a.cx + b.cx) / 2f, 2f)
        assertEquals(0.47f * h, (a.cy + b.cy) / 2f, 2f)
        val c = layout.clusters.first { it.control.id == "c" }.buttons
        val cup = c.first { it.def.id == "cup" }
        val cdown = c.first { it.def.id == "cdown" }
        assertEquals(0.875f * w, (cup.cx + cdown.cx) / 2f, 2f)
        assertEquals(0.778f * h, (cup.cy + cdown.cy) / 2f, 2f)
        val z = layout.keys.first { it.control.id == "z" }
        assertEquals(0.80f * w, z.cx, 2f)
        assertEquals(0.25f * h, z.cy, 2f)
        // Wide phones keep Z pinned (RA-anchor wide slot is mid-screen).
        val wide = resolved(TouchLayoutId.N64, prefs = TouchPrefs(contentAspect = TouchContentAspect.SIXTEEN_NINE), w = w, h = h)
        val zw = wide.keys.first { it.control.id == "z" }
        assertEquals(z.cx, zw.cx, 0.01f)
        assertEquals(z.cy, zw.cy, 0.01f)
    }

    @Test
    fun n64CStickPresetReplacesTheCButtons() {
        val layout = resolved(TouchLayoutId.N64, prefs = TouchPrefs(n64CStick = true))
        assertTrue(layout.clusters.none { it.control.id == "c" })
        val cstick = layout.sticks.first { it.control.id == "cstick" }
        assertTrue(cstick.right)
        val plain = resolved(TouchLayoutId.N64)
        val cup = plain.clusters.first { it.control.id == "c" }.buttons.first { it.def.id == "cup" }
        assertEquals(cup.cx, cstick.cx, 48f) // Same cluster neighborhood.
        assertEquals(-127, tap(layout, cstick.cx, cstick.cy - 36f).ry)
    }

    @Test
    fun analogDragAppliesOnlyWhereTheHostReadsIt() {
        val drag = TouchPrefs(analogDragTriggers = true)
        fun triggerOf(id: TouchLayoutId, keyId: String): TouchOutput.Trigger {
            val definition = touchLayoutSpec(id, drag)
            val key = definition.controls.filterIsInstance<KeyControl>().first { it.id == keyId }
            return key.output as TouchOutput.Trigger
        }
        assertTrue(triggerOf(TouchLayoutId.DREAMCAST, "l").analogDrag)
        assertTrue(triggerOf(TouchLayoutId.GC_STANDALONE, "l").analogDrag)
        assertFalse(triggerOf(TouchLayoutId.GC_CORE, "l").analogDrag)
        assertFalse(triggerOf(TouchLayoutId.PSX_DIGITAL, "l2").analogDrag)
        assertFalse(triggerOf(TouchLayoutId.N64, "z").analogDrag)
    }

    @Test
    fun contentRectFitsAspectAndNarrowsWithViewport() {
        val frame = touchContentRect(915f, 411f, TouchContentAspect.FOUR_THREE, null)
        assertEquals(183.5f, frame.left, 0.01f)
        assertEquals(731.5f, frame.right, 0.01f)
        assertEquals(0f, frame.top, 0.01f)
        assertEquals(411f, frame.bottom, 0.01f)
        val narrow = touchContentRect(915f, 411f, TouchContentAspect.FOUR_THREE, TouchViewport(0.25f, 0f, 0.75f, 1f))
        assertEquals(320.5f, narrow.left, 0.01f)
        assertEquals(594.5f, narrow.right, 0.01f)
    }

    @Test
    fun chordTablesMatchTheSpec() {
        fun chords(id: TouchLayoutId, clusterId: String): Set<Pair<String, String>> =
            touchLayoutSpec(id, TouchPrefs()).controls.filterIsInstance<FaceClusterControl>()
                .first { it.id == clusterId }.chords
        assertEquals(4, chords(TouchLayoutId.GENERIC, "face").size)
        assertEquals(setOf(touchChord("a", "b")), chords(TouchLayoutId.N64, "ab"))
        assertEquals(emptySet<Pair<String, String>>(), chords(TouchLayoutId.N64, "c"))
        assertEquals(
            setOf(touchChord("a", "b"), touchChord("a", "x"), touchChord("a", "y")),
            chords(TouchLayoutId.GC_CORE, "face"),
        )
        assertEquals(4, chords(TouchLayoutId.SATURN, "six").size)
        assertEquals(2, chords(TouchLayoutId.TDO, "abc").size)
    }

    @Test
    fun backendNamesMapToBackendsAndUnknownStaysGeneric() {
        assertEquals(TouchBackend.RETROARCH, touchBackendFromName("retroarch"))
        assertEquals(TouchBackend.DOLPHIN, touchBackendFromName("dolphin"))
        assertEquals(TouchBackend.NETHERSX2, touchBackendFromName("nethersx2"))
        assertEquals(TouchBackend.ARMSX2, touchBackendFromName("armsx2"))
        assertEquals(TouchBackend.UNKNOWN, touchBackendFromName(""))
        assertEquals(TouchBackend.UNKNOWN, touchBackendFromName("ppsspp"))
        assertEquals(TouchLayoutId.GENERIC, selectTouchLayout(TouchProfile("n64", TouchBackend.UNKNOWN)))
        assertEquals(TouchLayoutId.N64, selectTouchLayout(TouchProfile("n64", TouchBackend.RETROARCH)))
    }
}
