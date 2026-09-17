package com.gyaoshi.soundcompass

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 方位罗盘。
 *
 * 屏幕约定：圆盘上「上」= 方位角 0°（声源位于麦克风轴线的垂直方向），
 * 「右」= +90°（A 端），「左」= -90°（B 端）。麦克风轴线在屏幕上始终水平绘制，
 * 只是两端的物理含义随设置变化（手机顶部/底部 或 手机左/右）。
 * 圆盘下半部分为不可分辨区（双麦克风无法区分正前与正后）。
 */
class CompassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var azimuthDeg: Float = 0f
        private set
    var reliability: Float = 0f
        private set
    var levelNorm: Float = 0f
        private set
    var active: Boolean = false
        private set

    var axisHorizontal: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val density = resources.displayMetrics.density
    private val scaled = resources.displayMetrics.scaledDensity

    private fun dp(v: Float) = v * density
    private fun sp(v: Float) = v * scaled

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#334C6B8A")
        strokeWidth = dp(1f)
        strokeCap = Paint.Cap.ROUND
    }
    private val majorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7FA9CCEA")
        strokeWidth = dp(1.6f)
        strokeCap = Paint.Cap.ROUND
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = Color.parseColor("#2A3E5C")
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(10f)
        color = Color.parseColor("#8FA6C0")
    }
    private val endLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(12f)
        color = Color.parseColor("#C9DAF0")
        isFakeBoldText = true
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(11f)
        color = Color.parseColor("#5F7492")
    }
    private val hatchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#16233A")
        strokeWidth = dp(1f)
    }
    private val beamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeWidth = dp(5f)
    }
    private val needleCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeWidth = dp(2f)
        color = Color.WHITE
    }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arcRect = RectF()
    private val clipPath = Path()
    private val beamPath = Path()

    private val trailCap = 48
    private val trail = FloatArray(trailCap)
    private var trailHead = 0
    private var trailCount = 0

    private var accent = Color.parseColor("#22D3EE")
    private var accentAz = Float.NaN

    private val cLeft = Color.parseColor("#22D3EE")
    private val cMid = Color.parseColor("#A3E635")
    private val cRight = Color.parseColor("#FB7185")

    /** 由 MainActivity 每帧调用。 */
    fun setDirection(azimuthDeg: Float, reliability: Float, levelNorm: Float, active: Boolean) {
        this.azimuthDeg = azimuthDeg
        this.reliability = reliability
        this.levelNorm = levelNorm
        this.active = active

        if (active && reliability > 0.30f) {
            trail[trailHead] = azimuthDeg
            trailHead = (trailHead + 1) % trailCap
            if (trailCount < trailCap) trailCount++
        } else if (!active) {
            trailCount = 0
            trailHead = 0
        }
        invalidate()
    }

    fun clearTrail() {
        trailCount = 0
        trailHead = 0
        invalidate()
    }

    private fun screenAngle(az: Float) = 270f + az

    private fun accentFor(az: Float): Int {
        if (!accentAz.isNaN() && kotlin.math.abs(accentAz - az) < 1f) return accent
        val t = ((az + 90f) / 180f).coerceIn(0f, 1f)
        accent = if (t < 0.5f) {
            lerpColor(cLeft, cMid, t * 2f)
        } else {
            lerpColor(cMid, cRight, (t - 0.5f) * 2f)
        }
        accentAz = az
        return accent
    }

    private fun lerpColor(a: Int, b: Int, t: Float): Int {
        val r = (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt().coerceIn(0, 255)
        val g = (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt().coerceIn(0, 255)
        val bl = (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, bl)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val cx = w / 2f
        val cy = h * 0.52f
        val r = min(w, h) * 0.40f
        val dim = if (active) 1f else 0.42f
        val level = if (active) levelNorm.coerceIn(0f, 1f) else 0f
        val acc = accentFor(azimuthDeg)

        drawBackdrop(canvas, cx, cy, r, acc, level, dim)
        drawUnreadableZone(canvas, cx, cy, r, dim)
        drawScale(canvas, cx, cy, r, dim)
        drawTrail(canvas, cx, cy, r, dim)
        drawBeam(canvas, cx, cy, r, acc, level, dim)
        drawNeedle(canvas, cx, cy, r, acc, level, dim)
        drawEndLabels(canvas, cx, cy, r, dim)
    }

    private fun drawBackdrop(
        canvas: Canvas, cx: Float, cy: Float, r: Float,
        acc: Int, level: Float, dim: Float
    ) {
        val glowR = r * (0.30f + 0.85f * level)
        bgPaint.shader = RadialGradient(
            cx, cy, glowR.coerceAtLeast(dp(8f)),
            intArrayOf(
                withAlpha(acc, (70 * dim * (0.35f + 0.65f * level)).toInt()),
                withAlpha(acc, (18 * dim).toInt()),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, glowR, bgPaint)
        bgPaint.shader = null
    }

    private fun drawUnreadableZone(canvas: Canvas, cx: Float, cy: Float, r: Float, dim: Float) {
        arcRect.set(cx - r, cy - r, cx + r, cy + r)

        bgPaint.shader = null
        bgPaint.color = withAlpha(Color.parseColor("#0B1424"), (255 * dim).toInt())
        canvas.drawArc(arcRect, 0f, 180f, true, bgPaint)

        canvas.save()
        clipPath.reset()
        clipPath.addArc(arcRect, 0f, 180f)
        clipPath.close()
        canvas.clipPath(clipPath)
        hatchPaint.alpha = (150 * dim).toInt()
        var x = cx - 2f * r
        while (x < cx + r) {
            canvas.drawLine(x, cy, x + r, cy + r, hatchPaint)
            x += dp(9f)
        }
        canvas.restore()

        hintPaint.alpha = (255 * dim).toInt()
        canvas.drawText("正前 / 正后 不可分辨", cx, cy + r * 0.45f, hintPaint)
        hintPaint.textSize = sp(9.5f)
        hintPaint.color = Color.parseColor("#4E6280")
        canvas.drawText("双麦克风只能确定一条轴线上的方向", cx, cy + r * 0.45f + sp(14f), hintPaint)
        hintPaint.textSize = sp(11f)
        hintPaint.color = Color.parseColor("#5F7492")
    }

    private fun drawScale(canvas: Canvas, cx: Float, cy: Float, r: Float, dim: Float) {
        arcRect.set(cx - r, cy - r, cx + r, cy + r)

        // 上半圆高亮弧
        ringPaint.shader = LinearGradient(
            cx - r, cy, cx + r, cy,
            intArrayOf(withAlpha(cLeft, (170 * dim).toInt()), withAlpha(cMid, (200 * dim).toInt()), withAlpha(cRight, (170 * dim).toInt())),
            null,
            Shader.TileMode.CLAMP
        )
        ringPaint.strokeWidth = dp(2f)
        canvas.drawArc(arcRect, 180f, 180f, false, ringPaint)
        ringPaint.shader = null

        // 下半圆暗环
        ringPaint.strokeWidth = dp(1.5f)
        ringPaint.color = withAlpha(Color.parseColor("#22344F"), (255 * dim).toInt())
        canvas.drawArc(arcRect, 0f, 180f, false, ringPaint)

        // 刻度
        var az = -90
        while (az <= 90) {
            val rad = Math.toRadians(screenAngle(az.toFloat()).toDouble())
            val ca = cos(rad).toFloat()
            val sa = sin(rad).toFloat()
            val major = az % 30 == 0
            val mid = az % 10 == 0
            val inner = when {
                major -> r - dp(15f)
                mid -> r - dp(9f)
                else -> r - dp(5f)
            }
            val p = if (major) majorTickPaint else tickPaint
            p.alpha = ((if (major) 210 else 120) * dim).toInt()
            canvas.drawLine(
                cx + inner * ca, cy + inner * sa,
                cx + r * ca, cy + r * sa, p
            )
            az += 5
        }

        // 度数标签（±90 由两端端标承担）
        labelPaint.alpha = (230 * dim).toInt()
        for (a in intArrayOf(-60, -30, 0, 30, 60)) {
            val rad = Math.toRadians(screenAngle(a.toFloat()).toDouble())
            val ca = cos(rad).toFloat()
            val sa = sin(rad).toFloat()
            val lx = cx + (r + dp(15f)) * ca
            val ly = cy + (r + dp(15f)) * sa + sp(3.5f)
            canvas.drawText(if (a > 0) "+$a°" else "$a°", lx, ly, labelPaint)
        }

        // 麦克风轴线
        ringPaint.strokeWidth = dp(1.5f)
        ringPaint.color = withAlpha(Color.parseColor("#3A5478"), (200 * dim).toInt())
        canvas.drawLine(cx - r, cy, cx + r, cy, ringPaint)
    }

    private fun drawTrail(canvas: Canvas, cx: Float, cy: Float, r: Float, dim: Float) {
        if (trailCount <= 1) return
        val tr = r - dp(4f)
        for (i in 0 until trailCount) {
            val idx = (trailHead - 1 - i + trailCap * 2) % trailCap
            val age = i / trailCount.toFloat()
            val rad = Math.toRadians(screenAngle(trail[idx]).toDouble())
            val a = ((1f - age) * 130f * dim).toInt().coerceIn(0, 255)
            if (a <= 3) continue
            glowPaint.color = withAlpha(accentFor(trail[idx]), a)
            val rr = dp(1.2f + 1.8f * (1f - age))
            canvas.drawCircle(
                cx + tr * cos(rad).toFloat(),
                cy + tr * sin(rad).toFloat(),
                rr, glowPaint
            )
        }
    }

    private fun drawBeam(
        canvas: Canvas, cx: Float, cy: Float, r: Float,
        acc: Int, level: Float, dim: Float
    ) {
        val beamHalf = (30f - 25f * reliability.coerceIn(0f, 1f)).coerceIn(4f, 32f)
        val beamR = r * (0.22f + 0.74f * level)
        if (beamR < dp(6f)) return

        val center = screenAngle(azimuthDeg)
        beamPath.reset()
        beamPath.moveTo(cx, cy)
        arcRect.set(cx - beamR, cy - beamR, cx + beamR, cy + beamR)
        beamPath.arcTo(arcRect, center - beamHalf, beamHalf * 2f)
        beamPath.close()

        beamPaint.shader = RadialGradient(
            cx, cy, beamR,
            intArrayOf(
                withAlpha(acc, (150 * dim).toInt()),
                withAlpha(acc, (60 * dim).toInt()),
                withAlpha(acc, 0)
            ),
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(beamPath, beamPaint)
        beamPaint.shader = null
    }

    private fun drawNeedle(
        canvas: Canvas, cx: Float, cy: Float, r: Float,
        acc: Int, level: Float, dim: Float
    ) {
        val len = r * (0.26f + 0.66f * level)
        val rad = Math.toRadians(screenAngle(azimuthDeg).toDouble())
        val ca = cos(rad).toFloat()
        val sa = sin(rad).toFloat()
        val ex = cx + len * ca
        val ey = cy + len * sa

        // 外发光
        glowPaint.shader = RadialGradient(
            cx, cy, len.coerceAtLeast(dp(10f)),
            intArrayOf(withAlpha(acc, (90 * dim).toInt()), Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, len, glowPaint)
        glowPaint.shader = null

        needlePaint.strokeWidth = dp(5f)
        needlePaint.color = withAlpha(acc, (230 * dim).toInt())
        canvas.drawLine(cx, cy, ex, ey, needlePaint)

        needleCorePaint.alpha = (235 * dim).toInt()
        canvas.drawLine(cx, cy, ex, ey, needleCorePaint)

        // 箭头
        val head = dp(11f)
        val nx = -sa
        val ny = ca
        val path = Path()
        path.moveTo(ex + ca * head, ey + sa * head)
        path.lineTo(ex - ca * head * 0.4f + nx * head * 0.75f, ey - sa * head * 0.4f + ny * head * 0.75f)
        path.lineTo(ex - ca * head * 0.4f - nx * head * 0.75f, ey - sa * head * 0.4f - ny * head * 0.75f)
        path.close()
        glowPaint.shader = null
        glowPaint.color = withAlpha(acc, (245 * dim).toInt())
        canvas.drawPath(path, glowPaint)

        // 圆心
        centerPaint.color = withAlpha(acc, (255 * dim).toInt())
        canvas.drawCircle(cx, cy, dp(6f), centerPaint)
        centerPaint.color = withAlpha(Color.WHITE, (230 * dim).toInt())
        canvas.drawCircle(cx, cy, dp(2.5f), centerPaint)
    }

    private fun drawEndLabels(canvas: Canvas, cx: Float, cy: Float, r: Float, dim: Float) {
        endLabelPaint.alpha = (255 * dim).toInt()
        endLabelPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(
            if (axisHorizontal) "B 端·左" else "B 端·底",
            cx - r - dp(6f), cy + sp(4f), endLabelPaint
        )
        endLabelPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(
            if (axisHorizontal) "A 端·右" else "A 端·顶",
            cx + r + dp(6f), cy + sp(4f), endLabelPaint
        )
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
}
