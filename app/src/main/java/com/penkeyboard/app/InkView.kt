package com.penkeyboard.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import com.google.mlkit.vision.digitalink.Ink
import kotlin.math.hypot

/** The writing pad. Records strokes (with time and pressure) and draws them smoothly. */
class InkView(context: Context, private val listener: Listener) : View(context) {

    interface Listener {
        fun onStrokeStart()
        fun onStrokeEnd()
        fun onErase()
    }

    data class Pt(val x: Float, val y: Float, val t: Long, val p: Float)

    val strokes = mutableListOf<MutableList<Pt>>()
    var penOnly = true

    private var activeId = -1
    private var current: MutableList<Pt>? = null
    private var erasing = false
    private val dp = resources.displayMetrics.density

    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1F2A37.toInt()
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1F2A37.toInt() }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFD5DCE6.toInt()
        strokeWidth = 1.5f * dp
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(10f * dp, 8f * dp), 0f)
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFB4ACA0.toInt()
        textSize = 16f * dp
        textAlign = Paint.Align.CENTER
    }

    val isEmpty get() = strokes.isEmpty()

    private fun accepts(tool: Int) = when (tool) {
        MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER -> true
        MotionEvent.TOOL_TYPE_FINGER -> !penOnly
        else -> true
    }

    private fun wantsErase(e: MotionEvent, idx: Int) =
        e.getToolType(idx) == MotionEvent.TOOL_TYPE_ERASER ||
            (e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = e.actionIndex
                if (activeId == -1 && accepts(e.getToolType(idx))) {
                    activeId = e.getPointerId(idx)
                    parent?.requestDisallowInterceptTouchEvent(true)
                    if (wantsErase(e, idx)) {
                        erasing = true
                        eraseAt(e.getX(idx), e.getY(idx))
                    } else {
                        erasing = false
                        val s = mutableListOf(pt(e, idx))
                        current = s
                        strokes.add(s)
                        listener.onStrokeStart()
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val idx = e.findPointerIndex(activeId)
                if (idx < 0) return true
                if (erasing) {
                    eraseAt(e.getX(idx), e.getY(idx))
                    return true
                }
                val s = current ?: return true
                for (h in 0 until e.historySize) {
                    s.add(Pt(e.getHistoricalX(idx, h), e.getHistoricalY(idx, h),
                        e.getHistoricalEventTime(h), e.getHistoricalPressure(idx, h)))
                }
                s.add(pt(e, idx))
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (e.getPointerId(e.actionIndex) == activeId) finish()
            }
            MotionEvent.ACTION_CANCEL -> {
                current?.let { strokes.remove(it) }
                current = null
                activeId = -1
                erasing = false
                invalidate()
            }
        }
        return true
    }

    private fun finish() {
        activeId = -1
        if (erasing) {
            erasing = false
            listener.onErase()
            return
        }
        if (current != null) {
            current = null
            listener.onStrokeEnd()
        }
    }

    private fun pt(e: MotionEvent, idx: Int) =
        Pt(e.getX(idx), e.getY(idx), e.eventTime, e.getPressure(idx))

    private fun eraseAt(x: Float, y: Float) {
        val r = 18f * dp
        if (strokes.removeAll { s -> s.any { hypot(it.x - x, it.y - y) < r } }) invalidate()
    }

    fun clear() {
        strokes.clear(); current = null; activeId = -1; invalidate()
    }

    fun undoStroke() {
        if (strokes.isNotEmpty()) strokes.removeAt(strokes.size - 1)
        invalidate()
    }

    /** Remove the strokes that were just recognized, keeping anything written since. */
    fun removeFirst(n: Int) {
        repeat(minOf(n, strokes.size)) { strokes.removeAt(0) }
        invalidate()
    }

    fun toInk(count: Int = strokes.size): Ink {
        val b = Ink.builder()
        for (s in strokes.take(count)) {
            val sb = Ink.Stroke.builder()
            for (p in s) sb.addPoint(Ink.Point.create(p.x, p.y, p.t))
            b.addStroke(sb.build())
        }
        return b.build()
    }

    private fun widthFor(pressure: Float): Float {
        val p = if (pressure <= 0f || pressure > 1.5f) 0.5f else pressure
        return 2.6f * dp * (0.45f + 1.1f * p)
    }

    override fun onDraw(c: Canvas) {
        val base = height * 0.68f
        c.drawLine(24f * dp, base, width - 24f * dp, base, guidePaint)
        if (strokes.isEmpty()) {
            c.drawText(if (penOnly) "Write here with your S Pen" else "Write here",
                width / 2f, height * 0.45f, hintPaint)
        }
        for (s in strokes) {
            if (s.size == 1) {
                c.drawCircle(s[0].x, s[0].y, widthFor(s[0].p) / 2f, dotPaint); continue
            }
            for (i in 1 until s.size) {
                val a = s[i - 1]; val b = s[i]
                inkPaint.strokeWidth = widthFor((a.p + b.p) / 2f)
                c.drawLine(a.x, a.y, b.x, b.y, inkPaint)
            }
        }
    }
}
