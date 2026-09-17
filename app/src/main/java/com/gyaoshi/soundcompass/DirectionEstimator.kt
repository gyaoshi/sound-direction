package com.gyaoshi.soundcompass

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 单帧方位估计结果。
 *
 * 方位角 azimuthDeg 的取值范围是 [-90, +90]：
 *   +90 表示声源正对「A 端」（默认对应通道 0，纵向摆放时通常是手机顶部）
 *   -90 表示声源正对「B 端」（通道 1）
 *     0 表示声源位于麦克风轴线的垂直方向（正侧），此时无法区分正前 / 正后。
 */
data class DirectionFrame(
    val ok: Boolean = false,
    val azimuthDeg: Float = 0f,
    val delayUs: Float = 0f,
    val ildDb: Float = 0f,
    val levelDbfs: Float = -120f,
    val rmsA: Float = 0f,
    val rmsB: Float = 0f,
    val confidence: Float = 0f,
    val reliability: Float = 0f,
    val spreadDeg: Float = 90f
)

/**
 * 双麦克风声源方位估计器。
 *
 * 核心思路：
 *  1. 对左右两个通道分别加汉宁窗做 FFT；
 *  2. 计算互功率谱并做 PHAT 归一化（|R| → 1），削弱幅度谱、强化相位差，
 *     再逆变换得到广义互相关（GCC-PHAT）函数；
 *  3. 在物理可达的时延范围 [-d/c, +d/c] 内找峰值，抛物线插值得到亚样本精度时延 τ；
 *  4. 由 τ 反解方位：u = τ / (d·fs/c)，方位角 = arcsin(u)；
 *  5. 峰值相对搜索区的 z 分数作为置信度；置信度低时退化为用通道间电平差（ILD）兜底；
 *  6. 在 u 空间做加权滑动平均（权重 = 置信度 × 电平），得到稳定的方位与离散度。
 */
class DirectionEstimator(private val fftSize: Int = 2048) {

    companion object {
        /** 声速 m/s（20℃ 空气） */
        const val SPEED_OF_SOUND = 343f

        /** 由麦克风间距可推得的最大可测时差（微秒），用于 UI 展示 */
        fun maxDelayUs(sampleRate: Int, micDistanceM: Float): Float =
            micDistanceM / SPEED_OF_SOUND * 1e6f
    }

    private val fft = Fft(fftSize)

    private val hann = FloatArray(fftSize) {
        (0.5 - 0.5 * cos(2.0 * PI * it / (fftSize - 1))).toFloat()
    }

    private val re = FloatArray(fftSize)
    private val im = FloatArray(fftSize)
    private val specARe = FloatArray(fftSize)
    private val specAIm = FloatArray(fftSize)

    private val cap = 64
    private val histU = FloatArray(cap)
    private val histW = FloatArray(cap)
    private var histHead = 0
    private var histCount = 0

    fun reset() {
        histHead = 0
        histCount = 0
    }

    /**
     * 分析一帧数据。a / b 为长度 fftSize 的两通道样本（建议已归一化到 ±1）。
     * 本方法不做任何堆分配，可在音频线程直接调用。
     */
    fun analyze(
        a: FloatArray,
        b: FloatArray,
        sampleRate: Int,
        micDistanceM: Float,
        swap: Boolean,
        gateDbfs: Float,
        smoothFrames: Int
    ): DirectionFrame {
        val n = fftSize

        // ---------- 1. 电平（去直流后计算 RMS） ----------
        var meanA = 0f
        var meanB = 0f
        for (i in 0 until n) {
            meanA += a[i]
            meanB += b[i]
        }
        meanA /= n
        meanB /= n

        var sqA = 0.0
        var sqB = 0.0
        for (i in 0 until n) {
            val da = (a[i] - meanA).toDouble()
            val db = (b[i] - meanB).toDouble()
            sqA += da * da
            sqB += db * db
        }
        val rmsA = sqrt(sqA / n).toFloat()
        val rmsB = sqrt(sqB / n).toFloat()
        val levelDbfs = 20f * log10(max(rmsA, rmsB).coerceAtLeast(1e-7f))

        if (levelDbfs < gateDbfs) {
            return DirectionFrame(ok = false, levelDbfs = levelDbfs, rmsA = rmsA, rmsB = rmsB)
        }

        // ---------- 2. 双通道频谱 ----------
        for (i in 0 until n) {
            re[i] = (a[i] - meanA) * hann[i]
            im[i] = 0f
        }
        fft.transform(re, im)
        System.arraycopy(re, 0, specARe, 0, n)
        System.arraycopy(im, 0, specAIm, 0, n)

        for (i in 0 until n) {
            re[i] = (b[i] - meanB) * hann[i]
            im[i] = 0f
        }
        fft.transform(re, im)

        // ---------- 3. 互功率谱 + PHAT 归一化 ----------
        // R = A · conj(B)
        var maxMag = 0f
        for (k in 0 until n) {
            val ar = specARe[k]
            val ai = specAIm[k]
            val br = re[k]
            val bi = im[k]
            val rr = ar * br + ai * bi
            val ii = ai * br - ar * bi
            specARe[k] = rr
            specAIm[k] = ii
            val m = sqrt(rr * rr + ii * ii)
            if (m > maxMag) maxMag = m
        }
        if (maxMag <= 0f) {
            return DirectionFrame(ok = false, levelDbfs = levelDbfs, rmsA = rmsA, rmsB = rmsB)
        }
        val thr = maxMag * 1e-5f
        for (k in 0 until n) {
            val rr = specARe[k]
            val ii = specAIm[k]
            val m = sqrt(rr * rr + ii * ii)
            if (m > thr) {
                specARe[k] = rr / m
                specAIm[k] = ii / m
            } else {
                specARe[k] = 0f
                specAIm[k] = 0f
            }
        }

        // ---------- 4. 逆变换得到广义互相关 ----------
        fft.inverse(specARe, specAIm)
        val corr = specARe

        // ---------- 5. 在物理可达时延范围内搜索峰值 ----------
        val maxLagReal = micDistanceM * sampleRate / SPEED_OF_SOUND
        val lagLimit = min(ceil(maxLagReal.toDouble()).toInt() + 2, n / 2 - 2)
        if (lagLimit < 2) {
            return DirectionFrame(ok = false, levelDbfs = levelDbfs, rmsA = rmsA, rmsB = rmsB)
        }

        var peakIdx = 0
        var peakVal = -Float.MAX_VALUE
        var sum = 0.0
        var sumSq = 0.0
        for (lag in -lagLimit..lagLimit) {
            val idx = if (lag < 0) lag + n else lag
            val v = corr[idx]
            if (v > peakVal) {
                peakVal = v
                peakIdx = idx
            }
            val d = v.toDouble()
            sum += d
            sumSq += d * d
        }
        val cnt = 2 * lagLimit + 1
        val mean = sum / cnt
        val variance = max(0.0, sumSq / cnt - mean * mean)
        val std = sqrt(variance)
        val z = if (std > 1e-12) ((peakVal - mean) / std).toFloat() else 0f

        // 抛物线插值，取得亚样本精度
        val iPrev = if (peakIdx == 0) n - 1 else peakIdx - 1
        val iNext = if (peakIdx == n - 1) 0 else peakIdx + 1
        val y0 = corr[iPrev]
        val y1 = corr[peakIdx]
        val y2 = corr[iNext]
        val denom = y0 - 2f * y1 + y2
        var delta = if (abs(denom) > 1e-12f) 0.5f * (y0 - y2) / denom else 0f
        delta = delta.coerceIn(-1f, 1f)
        val peakPos = peakIdx + delta
        val lag = if (peakPos > n / 2f) peakPos - n else peakPos

        // ---------- 6. 时延 → 方位，并用电平差兜底 ----------
        var uGcc = (lag / maxLagReal).coerceIn(-1f, 1f)
        val ildDb = 20f * log10((rmsA + 1e-9f) / (rmsB + 1e-9f))
        var uIld = (ildDb / 6f).coerceIn(-1f, 1f)
        if (swap) {
            uGcc = -uGcc
            uIld = -uIld
        }
        val wGcc = smoothstep(z, 2.5f, 8f)
        val u = wGcc * uGcc + (1f - wGcc) * uIld

        // ---------- 7. 加权滑动平均 ----------
        val levelW = ((levelDbfs - gateDbfs) / 25f).coerceIn(0.05f, 1f)
        val w = (0.15f + 0.85f * wGcc) * levelW
        histU[histHead] = u
        histW[histHead] = w
        histHead = (histHead + 1) % cap
        if (histCount < cap) histCount++

        val m = min(histCount, smoothFrames.coerceAtLeast(1))
        var sw = 0f
        var su = 0f
        for (i in 0 until m) {
            val idx = (histHead - 1 - i + cap * 2) % cap
            sw += histW[idx]
            su += histU[idx] * histW[idx]
        }
        val uSmooth = if (sw > 1e-6f) su / sw else u

        var sv = 0f
        for (i in 0 until m) {
            val idx = (histHead - 1 - i + cap * 2) % cap
            val d = histU[idx] - uSmooth
            sv += histW[idx] * d * d
        }
        val varU = if (sw > 1e-6f) sv / sw else 0f
        val stdU = sqrt(varU).coerceIn(0f, 1f)
        val spreadDeg = Math.toDegrees(asin(stdU.toDouble())).toFloat()

        val azimuthDeg = Math.toDegrees(asin(uSmooth.toDouble())).toFloat()
        val delayUs = uSmooth * maxLagReal / sampleRate * 1e6f

        // ---------- 8. 可靠度 ----------
        val confNorm = smoothstep(z, 3f, 12f)
        val spreadNorm = (1f - spreadDeg / 25f).coerceIn(0f, 1f)
        val levelNorm = ((levelDbfs - gateDbfs) / 20f).coerceIn(0f, 1f)
        val reliability =
            (0.55f * confNorm + 0.25f * spreadNorm + 0.20f * levelNorm).coerceIn(0f, 1f)

        return DirectionFrame(
            ok = true,
            azimuthDeg = azimuthDeg,
            delayUs = delayUs,
            ildDb = ildDb,
            levelDbfs = levelDbfs,
            rmsA = rmsA,
            rmsB = rmsB,
            confidence = z,
            reliability = reliability,
            spreadDeg = spreadDeg
        )
    }

    private fun smoothstep(x: Float, e0: Float, e1: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
