package com.gamebex.client.stream.guest

import android.util.Log
import com.gamebex.client.stream.StreamDebug
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * Debug-only (`debug.gamebex.overlay_boxes=1`): logs each completed host
 * overlay (RA cfg-derived layout plus settings, or the emu bundle) as
 * chunked logcat JSON so a pixel-diff tool can rebuild RetroArch's geometry
 * independently. Art is logged only as sha256, to check transfer integrity
 * against the host's own files.
 *
 * Logcat, not files: without the all-files access grant (a restricted
 * setting), shared-storage writes fail with EPERM.
 */
internal object OverlayMatchDump {
    private const val TAG = "GameBEXStream"
    private const val CHUNK = 3000

    fun maybeDumpRa(stored: StoredOverlay) {
        if (!stored.complete || !StreamDebug.flag("overlay_boxes")) return
        runCatching {
            val images = JSONObject()
            stored.images.forEach { (ref, bytes) ->
                images.put(ref, JSONObject().put("sha256", sha16(bytes)).put("size", bytes.size))
            }
            val s = stored.settings
            val manifest = JSONObject()
                .put("type", "ra")
                .put("round", stored.round)
                .put("hostScreen", JSONArray().put(stored.hostScreenW).put(stored.hostScreenH))
                .put("viewport", JSONArray().put(stored.hostScreenX).put(stored.hostScreenY).put(stored.hostScreenW).put(stored.hostScreenH))
                .put("hostDisplay", JSONArray().put(stored.hostDisplayW).put(stored.hostDisplayH))
                .put("layout", stored.layout.name)
                .put("truncated", stored.truncated)
                .put(
                    "settings", JSONObject()
                        .put("opacity", s.opacity.toDouble())
                        .put("autoScale", s.autoScale)
                        .put("showInputs", s.showInputs)
                        .put("scaleLandscape", s.scaleLandscape.toDouble())
                        .put("aspectAdjustLandscape", s.aspectAdjustLandscape.toDouble())
                        .put("xSeparationLandscape", s.xSeparationLandscape.toDouble())
                        .put("ySeparationLandscape", s.ySeparationLandscape.toDouble())
                        .put("xOffsetLandscape", s.xOffsetLandscape.toDouble())
                        .put("yOffsetLandscape", s.yOffsetLandscape.toDouble())
                        .put("scalePortrait", s.scalePortrait.toDouble())
                        .put("aspectAdjustPortrait", s.aspectAdjustPortrait.toDouble())
                        .put("xSeparationPortrait", s.xSeparationPortrait.toDouble())
                        .put("ySeparationPortrait", s.ySeparationPortrait.toDouble())
                        .put("xOffsetPortrait", s.xOffsetPortrait.toDouble())
                        .put("yOffsetPortrait", s.yOffsetPortrait.toDouble()),
                )
                .put(
                    "pages", JSONArray().apply {
                        stored.layout.pages.forEach { page ->
                            put(
                                JSONObject()
                                    .put("index", page.index)
                                    .put("name", page.name)
                                    .put("image", page.image)
                                    .put("normalized", page.normalized)
                                    .put("fullScreen", page.fullScreen)
                                    .put("alphaMod", page.alphaMod.toDouble())
                                    .put("rangeMod", page.rangeMod.toDouble())
                                    .put("aspectRatio", page.aspectRatio.toDouble())
                                    .put("rect", JSONArray().put(page.rectX.toDouble()).put(page.rectY.toDouble()).put(page.rectW.toDouble()).put(page.rectH.toDouble()))
                                    .put("blockXSeparation", page.blockXSeparation)
                                    .put("blockYSeparation", page.blockYSeparation)
                                    .put("autoXSeparation", page.autoXSeparation)
                                    .put("autoYSeparation", page.autoYSeparation)
                                    .put(
                                        "descs", JSONArray().apply {
                                            page.descs.forEach { d ->
                                                put(
                                                    JSONObject()
                                                        .put("input", d.input)
                                                        .put("x", d.x.toDouble())
                                                        .put("y", d.y.toDouble())
                                                        .put("w", d.w.toDouble())
                                                        .put("h", d.h.toDouble())
                                                        .put("shape", d.shape.name)
                                                        .put("image", d.image)
                                                        .put("overlayIndex", d.overlayIndex)
                                                        .put("rangeMod", d.rangeMod.toDouble())
                                                        .put("alphaMod", d.alphaMod.toDouble())
                                                        .put("reach", JSONArray().put(d.reachUp.toDouble()).put(d.reachDown.toDouble()).put(d.reachLeft.toDouble()).put(d.reachRight.toDouble()))
                                                        .put("movable", d.movable)
                                                        .put("exclusive", d.exclusive)
                                                        .put("rangeModExclusive", d.rangeModExclusive)
                                                        .put("analogSaturatePct", d.analogSaturatePct.toDouble())
                                                        .put("nextTarget", d.nextTarget),
                                                )
                                            }
                                        },
                                    ),
                            )
                        }
                    },
                )
                .put("images", images)
            logChunks("ra", stored.round, manifest.toString())
        }.onFailure { Log.w(TAG, "OVERLAY_DUMP failed", it) }
    }

    fun maybeDumpEmu(stored: StoredEmuOverlay) {
        if (stored.partsReceived < stored.partCount && !stored.truncated) return
        if (!StreamDebug.flag("overlay_boxes")) return
        runCatching {
            val images = JSONObject()
            stored.art.forEach { (key, bytes) ->
                images.put(key, JSONObject().put("sha256", sha16(bytes)).put("size", bytes.size))
            }
            val manifest = JSONObject()
                .put("type", "emu")
                .put("round", stored.round)
                .put("backend", stored.backend)
                .put("hostScreen", JSONArray().put(stored.hostScreenW).put(stored.hostScreenH))
                .put("viewport", JSONArray().put(stored.hostScreenX).put(stored.hostScreenY).put(stored.hostScreenW).put(stored.hostScreenH))
                .put("hostDisplay", JSONArray().put(stored.hostDisplayW).put(stored.hostDisplayH))
                .put("truncated", stored.truncated)
                .put(
                    "controls", JSONArray().apply {
                        stored.controls.forEach { c ->
                            put(
                                JSONObject()
                                    .put("id", c.id)
                                    .put("kind", c.kind)
                                    .put("rect", JSONArray().put(c.x).put(c.y).put(c.w).put(c.h))
                                    .put("alpha", c.alpha)
                                    .put("knobSize", c.knobSize)
                                    .put("pressedGrowPx", c.pressedGrowPx)
                                    .put("dpadGap", c.dpadGap.toDouble())
                                    .put("padPx", c.padPx)
                                    .put("noArt", c.noArt)
                                    .put("hasArt", c.art.isNotEmpty())
                                    .put("cells", JSONArray().apply {
                                        c.cells.forEach { cell ->
                                            put(
                                                JSONObject()
                                                    .put("rect", JSONArray().put(cell.x).put(cell.y).put(cell.w).put(cell.h))
                                                    .put("hasArt", cell.art.isNotEmpty()),
                                            )
                                        }
                                    }),
                            )
                        }
                    },
                )
                .put("images", images)
            logChunks("emu", stored.round, manifest.toString())
        }.onFailure { Log.w(TAG, "OVERLAY_DUMP failed", it) }
    }

    private fun logChunks(kind: String, round: Int, json: String) {
        val total = (json.length + CHUNK - 1) / CHUNK
        json.chunked(CHUNK).forEachIndexed { i, part ->
            Log.i(TAG, "OVERLAY_DUMP_JSON $kind round=$round seq=${i + 1}/$total $part")
        }
    }

    private fun sha16(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .take(8).joinToString("") { "%02x".format(it) }
}
