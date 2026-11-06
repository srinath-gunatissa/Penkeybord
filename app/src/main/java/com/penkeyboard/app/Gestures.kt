package com.penkeyboard.app

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Pen gestures, checked on each finished stroke:
 *  - Scratch out (zig-zag) over writing  -> erase those strokes
 *  - Scratch out on an empty pad        -> delete the previous word
 *  - Long line left → right (empty pad) -> space
 *  - Long line right → left (empty pad) -> backspace
 *  - Line down, then long line left (↵) -> Enter
 */
object Gestures {

    sealed class G {
        object None : G()
        data class EraseStrokes(val strokes: List<List<InkView.Pt>>) : G()
        object DeleteWord : G()
        object Space : G()
        object Backspace : G()
        object Enter : G()
    }

    private data class Box(val l: Float, val t: Float, val r: Float, val b: Float) {
        val w get() = r - l
        val h get() = b - t
        fun overlaps(o: Box) = l < o.r && o.l < r && t < o.b && o.t < b
    }

    private fun box(s: List<InkView.Pt>): Box {
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (p in s) { l = min(l, p.x); t = min(t, p.y); r = max(r, p.x); b = max(b, p.y) }
        return Box(l, t, r, b)
    }

    /** [stroke] is the stroke just drawn; [others] are the strokes already on the pad. */
    fun classify(stroke: List<InkView.Pt>, others: List<List<InkView.Pt>>, padW: Float, dp: Float): G {
        if (stroke.size < 4) return G.None
        val bx = box(stroke)

        // Scratch-out: many left/right reversals across a decent width.
        if (reversals(stroke, 6f * dp) >= 4 && bx.w > 30f * dp && bx.h < bx.w * 1.2f) {
            if (others.isEmpty()) return G.DeleteWord
            val hit = others.filter { box(it).overlaps(bx) }
            return if (hit.isNotEmpty()) G.EraseStrokes(hit) else G.None
        }

        // The rest only count when written alone on an empty pad.
        if (others.isNotEmpty()) return G.None
        val first = stroke.first(); val last = stroke.last()
        val longLine = max(padW * 0.25f, 120f * dp)

        // Straight horizontal line.
        if (bx.w >= longLine && bx.h < bx.w * 0.15f) {
            return if (last.x > first.x) G.Space else G.Backspace
        }

        // Enter (↵): goes down first, then a long stretch to the left.
        val lowest = stroke.maxByOrNull { it.y } ?: return G.None
        val down = lowest.y - first.y
        val downDrift = abs(lowest.x - first.x)
        val left = lowest.x - last.x
        val rise = abs(last.y - lowest.y)
        if (down > 35f * dp && downDrift < down * 0.5f &&
            left > max(down * 1.2f, padW * 0.15f) && rise < left * 0.3f) {
            return G.Enter
        }
        return G.None
    }

    private fun reversals(s: List<InkView.Pt>, minStep: Float): Int {
        var count = 0; var dir = 0; var anchor = s.first().x
        for (p in s) {
            val dx = p.x - anchor
            if (abs(dx) < minStep) continue
            val d = if (dx > 0) 1 else -1
            if (dir != 0 && d != dir) count++
            dir = d; anchor = p.x
        }
        return count
    }
}
