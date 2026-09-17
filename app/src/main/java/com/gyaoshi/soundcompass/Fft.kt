package com.gyaoshi.soundcompass

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 原地迭代式 radix-2 复数 FFT（Cooley-Tukey）。
 * 长度必须是 2 的整数次幂。
 */
class Fft(val n: Int) {

    private val levels: Int
    private val cosTable: FloatArray
    private val sinTable: FloatArray

    init {
        require(n > 1 && (n and (n - 1)) == 0) { "FFT 长度必须是 2 的幂" }
        levels = Integer.numberOfTrailingZeros(n)
        cosTable = FloatArray(n / 2)
        sinTable = FloatArray(n / 2)
        for (i in 0 until n / 2) {
            val a = 2.0 * PI * i / n
            cosTable[i] = cos(a).toFloat()
            sinTable[i] = sin(a).toFloat()
        }
    }

    /** 正变换（原地） */
    fun transform(re: FloatArray, im: FloatArray) {
        // 位反转置换
        for (i in 0 until n) {
            val j = Integer.reverse(i) ushr (32 - levels)
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var size = 2
        while (size <= n) {
            val half = size shr 1
            val step = n / size
            var i = 0
            while (i < n) {
                var j = i
                var k = 0
                val end = i + half
                while (j < end) {
                    val l = j + half
                    val c = cosTable[k]
                    val s = sinTable[k]
                    val tre = re[l] * c + im[l] * s
                    val tim = -re[l] * s + im[l] * c
                    re[l] = re[j] - tre
                    im[l] = im[j] - tim
                    re[j] += tre
                    im[j] += tim
                    j++
                    k += step
                }
                i += size
            }
            size = size shl 1
        }
    }

    /** 逆变换（原地），含 1/N 归一化 */
    fun inverse(re: FloatArray, im: FloatArray) {
        for (i in 0 until n) im[i] = -im[i]
        transform(re, im)
        val inv = 1f / n
        for (i in 0 until n) {
            re[i] *= inv
            im[i] = -im[i] * inv
        }
    }
}
