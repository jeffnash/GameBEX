package com.gamebex.client.stream.guest

import kotlin.math.roundToInt

internal data class ResourceOverlayNode(val id: String, val attributes: Map<String, String>)

internal fun resolveResourceOverlayBounds(nodes: List<ResourceOverlayNode>, width: Int, height: Int, density: Float): Map<String, IntArray> {
    val resolved = linkedMapOf<String, IntArray>()
    val parent = intArrayOf(0, 0, width, height)
    val pending = nodes.toMutableList()
    fun dimension(raw: String?): Int {
        if (raw == null) return 0
        val number = raw.removeSuffix("dp").removeSuffix("px").toFloatOrNull()?.takeIf(Float::isFinite) ?: return 0
        return (number * if (raw.endsWith("dp")) density else 1f).roundToInt()
    }
    while (pending.isNotEmpty()) {
        var progress = false
        val iterator = pending.iterator()
        while (iterator.hasNext()) {
            val node = iterator.next()
            val a = node.attributes
            val w = dimension(a["layout_width"])
            val h = dimension(a["layout_height"])
            if (w <= 0 || h <= 0) { iterator.remove(); continue }
            var missing = false
            fun constraint(key: String, axis: Int, end: Boolean): Int? {
                val reference = a[key] ?: return null
                val box = if (reference == "0" || reference == "parent") parent else resolved[reference.removePrefix("id/")]
                if (box == null) { missing = true; return null }
                return box[axis] + if (end) box[axis + 2] else 0
            }
            val ml = dimension(a["layout_marginLeft"] ?: a["layout_marginStart"] ?: a["layout_margin"])
            val mr = dimension(a["layout_marginRight"] ?: a["layout_marginEnd"] ?: a["layout_margin"])
            val mt = dimension(a["layout_marginTop"] ?: a["layout_margin"])
            val mb = dimension(a["layout_marginBottom"] ?: a["layout_margin"])
            val left = constraint("layout_constraintLeft_toLeftOf", 0, false) ?: constraint("layout_constraintStart_toStartOf", 0, false) ?: constraint("layout_constraintLeft_toRightOf", 0, true)
            val right = constraint("layout_constraintRight_toRightOf", 0, true) ?: constraint("layout_constraintEnd_toEndOf", 0, true) ?: constraint("layout_constraintRight_toLeftOf", 0, false)
            val top = constraint("layout_constraintTop_toTopOf", 1, false) ?: constraint("layout_constraintTop_toBottomOf", 1, true)
            val bottom = constraint("layout_constraintBottom_toBottomOf", 1, true) ?: constraint("layout_constraintBottom_toTopOf", 1, false)
            if (missing) continue
            if (left == null && right == null || top == null && bottom == null) { iterator.remove(); continue }
            val bx = a["layout_constraintHorizontal_bias"]?.toFloatOrNull()?.takeIf(Float::isFinite) ?: 0.5f
            val by = a["layout_constraintVertical_bias"]?.toFloatOrNull()?.takeIf(Float::isFinite) ?: 0.5f
            val x = if (left != null && right != null) (left + ml + (right - mr - left - ml - w) * bx).roundToInt() else left?.plus(ml) ?: (right!! - mr - w)
            val y = if (top != null && bottom != null) (top + mt + (bottom - mb - top - mt - h) * by).roundToInt() else top?.plus(mt) ?: (bottom!! - mb - h)
            resolved[node.id] = intArrayOf(x, y, w, h)
            iterator.remove()
            progress = true
        }
        if (!progress) break
    }
    return resolved
}
