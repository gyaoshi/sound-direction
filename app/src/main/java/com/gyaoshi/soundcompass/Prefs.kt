package com.gyaoshi.soundcompass

import android.content.Context

class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("sound_compass", Context.MODE_PRIVATE)

    /** 两麦克风间距，单位 cm */
    var micDistanceCm: Int
        get() = sp.getInt(K_MIC_CM, 15)
        set(v) = sp.edit().putInt(K_MIC_CM, v.coerceIn(3, 30)).apply()

    /** 噪声门限，dBFS */
    var gateDbfs: Int
        get() = sp.getInt(K_GATE, -54)
        set(v) = sp.edit().putInt(K_GATE, v.coerceIn(-90, -10)).apply()

    /** 平滑帧数 */
    var smoothFrames: Int
        get() = sp.getInt(K_SMOOTH, 12)
        set(v) = sp.edit().putInt(K_SMOOTH, v.coerceIn(1, 60)).apply()

    /** 左右通道互换 */
    var swapChannels: Boolean
        get() = sp.getBoolean(K_SWAP, false)
        set(v) = sp.edit().putBoolean(K_SWAP, v).apply()

    /** 麦克风轴线是否为横向（手机左右两侧） */
    var axisHorizontal: Boolean
        get() = sp.getBoolean(K_AXIS, false)
        set(v) = sp.edit().putBoolean(K_AXIS, v).apply()

    /** 自动增益 */
    var agc: Boolean
        get() = sp.getBoolean(K_AGC, true)
        set(v) = sp.edit().putBoolean(K_AGC, v).apply()

    /** 保持屏幕常亮 */
    var keepScreenOn: Boolean
        get() = sp.getBoolean(K_KEEP, true)
        set(v) = sp.edit().putBoolean(K_KEEP, v).apply()

    fun reset() {
        sp.edit().clear().apply()
    }

    private companion object {
        const val K_MIC_CM = "mic_cm"
        const val K_GATE = "gate_db"
        const val K_SMOOTH = "smooth"
        const val K_SWAP = "swap"
        const val K_AXIS = "axis_h"
        const val K_AGC = "agc"
        const val K_KEEP = "keep_on"
    }
}
