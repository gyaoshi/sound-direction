package com.gyaoshi.soundcompass

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/** 单通道电平条：显示瞬时电平、峰值保持，以及 dBFS 读数。 */
class LevelBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var label = "A"
    private var level = 0f
    private var peak = 0f
    private var dbText = "  -inf dBFS"
    private var accent = Color.parseColor("#22D3EE")
    private var dimmed = false

    private val density = resources.displayMetrics.density
    private val scaled = resources.displayMetrics.scaledDensity
    private fun dp(v: Float) = v * density
    private fun sp(v: Float) = v * scaled

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#132033")
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = dp(2f)
        color = Color.WHITE
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFakeBoldText = true
        textSize = sp(13f)
    }
    private val dbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.RIGHT
        textSize = sp(10.5f)
        typeface = android.graphics.Typeface.MONOSPACE
        color = Color.parseColor("#8FA6C0")
    }

    private val rect = RectF()

    fun set(
        label: String,
        level: Float,
        peak: Float,
        dbText: String,
        accent: Int,
        dimmed: Boolean
    ) {
        this.label = label
        this.level = level.coerceIn(0f, 1f)
        this.peak = peak.coerceIn(0f, 1f)
        this.dbText = dbText
        this.accent = accent
        this.dimmed = dimmed
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val alpha = if (dimmed) 90 else 255

        // 通道标签
        labelPaint.color = withAlpha(accent, alpha)
        canvas.drawText(label, dp(2f), h * 0.5f + sp(4.5f), labelPaint)

        val left = dp(20f)
        val right = w - dp(82f)
        val barW = (right - left).coerceAtLeast(dp(20f))
        val top = h * 0.5f - dp(5f)
        val bottom = h * 0.5f + dp(5f)

        rect.set(left, top, left + barW, bottom)
        canvas.drawRoundRect(rect, dp(5f), dp(5f), trackPaint)

        if (level > 0.005f) {
            val fillRight = left + barW * level
            rect.set(left, top, fillRight, bottom)
            fillPaint.shader = LinearGradient(
                left, 0f, fillRight.coerceAtLeast(left + 1f), 0f,
                intArrayOf(withAlpha(accent, (alpha * 0.55f).toInt()),
                    withAlpha(accent, alpha)),
                null,
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(rect, dp(5f), dp(5f), fillPaint)
            fillPaint.shader = null
        }

        if (peak > 0.01f) {
            val px = left + barW * peak
            peakPaint.alpha = alpha
            canvas.drawLine(px, top - dp(2f), px, bottom + dp(2f), peakPaint)
        }

        dbPaint.alpha = alpha
        canvas.drawText(dbText, w - dp(2f), h * 0.5f + sp(4f), dbPaint)
    }

    private fun withAlpha(color: Int, a: Int): Int =
        Color.argb(a.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
}
