package com.leneo.ipdevices.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import kotlin.math.exp
import kotlin.math.min
import kotlin.random.Random

/** Two white eyes on a black field. Gaze snaps, then holds, like a robot. */
class RobotEyesView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val pupilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val glintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF }
    private val eye = RectF()

    private var gazeX = 0f
    private var gazeY = 0f
    private var targetX = 0f
    private var targetY = 0f
    private var nextGazeNs = 0L
    private var nextBlinkNs = 0L
    private var blinkStartNs = 0L
    private var blinking = false
    private var lastFrameNs = 0L
    private var running = false

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            step(frameTimeNanos)
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        setBackgroundColor(Color.BLACK)
        start()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && isShown) start() else if (changedView === this) stop()
    }

    private fun start() {
        if (running || !isShown) return
        running = true
        lastFrameNs = 0L
        val now = System.nanoTime()
        nextGazeNs = now + 400_000_000L
        nextBlinkNs = now + 1_600_000_000L
        Choreographer.getInstance().postFrameCallback(frame)
    }

    private fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frame)
    }

    private fun step(now: Long) {
        val dt = if (lastFrameNs == 0L) 0.016f else ((now - lastFrameNs) / 1_000_000_000f).coerceIn(0f, 0.05f)
        lastFrameNs = now
        if (now >= nextGazeNs) {
            val spot = GAZE.random()
            targetX = spot.first
            targetY = spot.second
            nextGazeNs = now + Random.nextLong(500, 1700) * 1_000_000L
        }
        val k = 1f - exp(-dt * 18f)
        gazeX += (targetX - gazeX) * k
        gazeY += (targetY - gazeY) * k
        if (!blinking && now >= nextBlinkNs) {
            blinking = true
            blinkStartNs = now
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w < 2f || h < 2f) return
        val eyeW = min(w * 0.22f, h * 0.28f) * 1.3f
        val eyeH = eyeW * 0.56f
        val gap = eyeW * 1.15f
        val cy = h * 0.5f
        val open = blinkOpen()
        drawEye(canvas, w * 0.5f - gap * 0.5f - eyeW * 0.5f, cy, eyeW, eyeH, open)
        drawEye(canvas, w * 0.5f + gap * 0.5f + eyeW * 0.5f, cy, eyeW, eyeH, open)
    }

    private fun blinkOpen(): Float {
        if (!blinking) return 1f
        val t = (System.nanoTime() - blinkStartNs) / 280_000_000f
        if (t >= 1f) {
            blinking = false
            nextBlinkNs = System.nanoTime() + Random.nextLong(1800, 4200) * 1_000_000L
            return 1f
        }
        val shut = if (t < 0.45f) t / 0.45f else (1f - t) / 0.55f
        return (1f - shut).coerceIn(0.06f, 1f)
    }

    private fun drawEye(canvas: Canvas, cx: Float, cy: Float, eyeW: Float, eyeH: Float, open: Float) {
        val shiftX = gazeX * eyeW * 0.06f
        val shiftY = gazeY * eyeH * 0.08f
        val eh = eyeH * open
        val x = cx + shiftX
        val y = cy + shiftY
        eye.set(x - eyeW * 0.5f, y - eh * 0.5f, x + eyeW * 0.5f, y + eh * 0.5f)
        val radius = eh * 0.5f
        canvas.drawRoundRect(eye, radius, radius, eyePaint)
        if (open < 0.45f) return
        val px = x + gazeX * eyeW * 0.18f
        val py = y + gazeY * eh * 0.16f
        canvas.drawCircle(px, py, eh * 0.22f, pupilPaint)
        canvas.drawCircle(px - eh * 0.08f, py - eh * 0.08f, eh * 0.06f, glintPaint)
    }

    companion object {
        private val GAZE = arrayOf(
            0f to 0f,
            -0.85f to 0f,
            0.85f to 0f,
            0f to -0.55f,
            0f to 0.45f,
            -0.55f to -0.35f,
            0.55f to -0.3f,
            -0.4f to 0.35f,
            0.4f to 0.3f,
        )
    }
}
