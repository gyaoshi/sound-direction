package com.gyaoshi.soundcompass

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import java.util.Locale
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

class MainActivity : AppCompatActivity(), AudioEngine.Listener {

    private companion object {
        const val REQ_AUDIO = 1001
        const val UI_INTERVAL_MS = 33L
        const val SPAN_DB = 35f
        const val SILENT_RMS = 1e-5f
    }

    private lateinit var prefs: Prefs
    private lateinit var engine: AudioEngine
    private val estimator = DirectionEstimator(AudioEngine.FFT_SIZE)

    private lateinit var compass: CompassView
    private lateinit var barA: LevelBarView
    private lateinit var barB: LevelBarView
    private lateinit var tvStatus: TextView
    private lateinit var tvWarn: TextView
    private lateinit var tvAzimuth: TextView
    private lateinit var tvLevel: TextView
    private lateinit var tvConf: TextView
    private lateinit var tvDirection: TextView
    private lateinit var tvDiag: TextView
    private lateinit var btnToggle: Button
    private lateinit var panelSettings: View
    private lateinit var slMicCm: Slider
    private lateinit var tvMicCm: TextView
    private lateinit var slGate: Slider
    private lateinit var tvGate: TextView
    private lateinit var slSmooth: Slider
    private lateinit var tvSmooth: TextView
    private lateinit var swSwap: SwitchMaterial
    private lateinit var swAxis: SwitchMaterial
    private lateinit var swAgc: SwitchMaterial
    private lateinit var swKeepOn: SwitchMaterial

    /** 音频线程写入，UI 线程读取 */
    @Volatile
    private var frame: DirectionFrame = DirectionFrame()

    @Volatile
    private var pMicM = 0.15f

    @Volatile
    private var pSwap = false

    @Volatile
    private var pGate = -54f

    @Volatile
    private var pSmooth = 12

    private var audioStatus: AudioEngine.Status? = null

    private var peakA = 0f
    private var peakB = 0f
    private var refDb = -30f
    private var lastAz = 0f
    private var hasAz = false

    private val uiHandler = Handler(Looper.getMainLooper())
    private val renderLoop = object : Runnable {
        override fun run() {
            render()
            uiHandler.postDelayed(this, UI_INTERVAL_MS)
        }
    }

    // ------------------------------------------------------------------ 生命周期

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = Prefs(this)
        engine = AudioEngine(this)

        compass = findViewById(R.id.compass)
        barA = findViewById(R.id.barA)
        barB = findViewById(R.id.barB)
        tvStatus = findViewById(R.id.tvStatus)
        tvWarn = findViewById(R.id.tvWarn)
        tvAzimuth = findViewById(R.id.tvAzimuth)
        tvLevel = findViewById(R.id.tvLevel)
        tvConf = findViewById(R.id.tvConf)
        tvDirection = findViewById(R.id.tvDirection)
        tvDiag = findViewById(R.id.tvDiag)
        btnToggle = findViewById(R.id.btnToggle)
        panelSettings = findViewById(R.id.panelSettings)
        slMicCm = findViewById(R.id.slMicCm)
        tvMicCm = findViewById(R.id.tvMicCm)
        slGate = findViewById(R.id.slGate)
        tvGate = findViewById(R.id.tvGate)
        slSmooth = findViewById(R.id.slSmooth)
        tvSmooth = findViewById(R.id.tvSmooth)
        swSwap = findViewById(R.id.swSwap)
        swAxis = findViewById(R.id.swAxis)
        swAgc = findViewById(R.id.swAgc)
        swKeepOn = findViewById(R.id.swKeepOn)

        btnToggle.setOnClickListener {
            if (engine.isRunning) stopDetection() else requestAndStart()
        }

        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            panelSettings.visibility =
                if (panelSettings.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        findViewById<Button>(R.id.btnCalibrate).setOnClickListener { calibrate() }
        findViewById<Button>(R.id.btnReset).setOnClickListener {
            prefs.reset()
            applyPrefsToUi()
            syncParams()
            estimator.reset()
            compass.clearTrail()
            toast("已恢复默认设置")
        }

        setupSliders()
        setupSwitches()
        applyPrefsToUi()
        syncParams()
        applyKeepScreenOn()
        render()
    }

    override fun onStop() {
        super.onStop()
        if (engine.isRunning) stopDetection()
    }

    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacks(renderLoop)
        engine.stop()
    }

    // ------------------------------------------------------------------ 设置面板

    private fun setupSliders() {
        slMicCm.addOnChangeListener { _, value, _ ->
            prefs.micDistanceCm = value.toInt()
            tvMicCm.text = String.format(Locale.US, "%d cm", value.toInt())
            syncParams()
        }
        slGate.addOnChangeListener { _, value, _ ->
            prefs.gateDbfs = value.toInt()
            tvGate.text = String.format(Locale.US, "%d dBFS", value.toInt())
            syncParams()
        }
        slSmooth.addOnChangeListener { _, value, _ ->
            prefs.smoothFrames = value.toInt()
            tvSmooth.text = String.format(Locale.US, "%d", value.toInt())
            syncParams()
        }
    }

    private fun setupSwitches() {
        swSwap.setOnCheckedChangeListener { _, checked ->
            prefs.swapChannels = checked
            syncParams()
        }
        swAxis.setOnCheckedChangeListener { _, checked ->
            prefs.axisHorizontal = checked
            compass.axisHorizontal = checked
        }
        swAgc.setOnCheckedChangeListener { _, checked -> prefs.agc = checked }
        swKeepOn.setOnCheckedChangeListener { _, checked ->
            prefs.keepScreenOn = checked
            applyKeepScreenOn()
        }
    }

    private fun applyPrefsToUi() {
        slMicCm.value = prefs.micDistanceCm.toFloat().coerceIn(slMicCm.valueFrom, slMicCm.valueTo)
        slGate.value = prefs.gateDbfs.toFloat().coerceIn(slGate.valueFrom, slGate.valueTo)
        slSmooth.value = prefs.smoothFrames.toFloat().coerceIn(slSmooth.valueFrom, slSmooth.valueTo)
        tvMicCm.text = String.format(Locale.US, "%d cm", prefs.micDistanceCm)
        tvGate.text = String.format(Locale.US, "%d dBFS", prefs.gateDbfs)
        tvSmooth.text = String.format(Locale.US, "%d", prefs.smoothFrames)
        swSwap.isChecked = prefs.swapChannels
        swAxis.isChecked = prefs.axisHorizontal
        swAgc.isChecked = prefs.agc
        swKeepOn.isChecked = prefs.keepScreenOn
        compass.axisHorizontal = prefs.axisHorizontal
    }

    private fun syncParams() {
        pMicM = prefs.micDistanceCm / 100f
        pSwap = prefs.swapChannels
        pGate = prefs.gateDbfs.toFloat()
        pSmooth = prefs.smoothFrames
    }

    private fun applyKeepScreenOn() {
        if (prefs.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun calibrate() {
        if (!engine.isRunning) {
            toast("请先开始检测")
            return
        }
        val f = frame
        if (!f.ok) {
            toast("当前没有可用的方位读数，请先在 B 端一侧发出声音")
            return
        }
        if (f.azimuthDeg > 0f) {
            prefs.swapChannels = !prefs.swapChannels
            swSwap.isChecked = prefs.swapChannels
            syncParams()
            estimator.reset()
            compass.clearTrail()
            toast("已自动互换通道，指示已对齐到 B 端")
        } else {
            toast("当前指示已经在 B 端一侧，无需调整")
        }
    }

    // ------------------------------------------------------------------ 检测控制

    private fun requestAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO
            )
            return
        }
        startDetection()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_AUDIO) return
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startDetection()
        } else {
            showWarn("未授予麦克风权限，无法检测声音方向。")
        }
    }

    private fun startDetection() {
        syncParams()
        estimator.reset()
        compass.clearTrail()
        peakA = 0f
        peakB = 0f
        refDb = -30f
        hasAz = false
        lastAz = 0f
        frame = DirectionFrame()
        audioStatus = null
        tvWarn.visibility = View.GONE

        engine.start()

        if (!engine.isRunning) {
            btnToggle.text = "开始检测"
            return
        }
        btnToggle.text = "停止检测"
        uiHandler.removeCallbacks(renderLoop)
        uiHandler.post(renderLoop)
    }

    private fun stopDetection() {
        uiHandler.removeCallbacks(renderLoop)
        engine.stop()
        audioStatus = null
        frame = DirectionFrame()
        hasAz = false
        peakA = 0f
        peakB = 0f
        btnToggle.text = "开始检测"
        tvStatus.text = "未启动"
        render()
    }

    // ------------------------------------------------------------------ 音频回调（音频线程）

    override fun onBlock(a: FloatArray, b: FloatArray) {
        frame = estimator.analyze(
            a, b, engine.sampleRate, pMicM, pSwap, pGate, pSmooth
        )
    }

    override fun onAudioReady(status: AudioEngine.Status) {
        audioStatus = status
    }

    override fun onAudioError(message: String) {
        runOnUiThread { showWarn(message) }
    }

    // ------------------------------------------------------------------ 渲染（UI 线程，约 30 fps）

    private fun render() {
        val running = engine.isRunning
        val f = if (running) frame else DirectionFrame()
        val ok = running && f.ok

        if (f.ok) {
            lastAz = f.azimuthDeg
            hasAz = true
        }
        val az = if (hasAz) lastAz else 0f

        // ---- 电平归一化（可选自动增益） ----
        val db = f.levelDbfs
        if (prefs.agc) {
            if (db > refDb) refDb = db else refDb += (db - refDb) * 0.002f
        } else {
            refDb = -5f
        }
        val normOverall = if (running) normOf(db, refDb) else 0f
        val normA = if (running) normOf(dbOf(f.rmsA), refDb) else 0f
        val normB = if (running) normOf(dbOf(f.rmsB), refDb) else 0f

        if (running) {
            peakA = max(normA, peakA - 0.018f)
            peakB = max(normB, peakB - 0.018f)
        } else {
            peakA = 0f
            peakB = 0f
        }

        // ---- 罗盘 ----
        compass.axisHorizontal = prefs.axisHorizontal
        compass.setDirection(az, if (ok) f.reliability else 0f, normOverall, running)

        // ---- 读数 ----
        tvAzimuth.text = if (ok) String.format(Locale.US, "%+.0f°", f.azimuthDeg) else "--"
        tvLevel.text = if (running) String.format(Locale.US, "%.0f", f.levelDbfs) else "--"
        tvConf.text = if (ok) String.format(Locale.US, "%.2f", f.reliability) else "--"

        tvDirection.text = when {
            !running -> "等待开始…"
            !ok -> "等待声音…（门限 ${prefs.gateDbfs} dBFS）"
            az >= 55f -> "声音来自 A 端方向"
            az >= 18f -> "声音偏向 A 端"
            az <= -55f -> "声音来自 B 端方向"
            az <= -18f -> "声音偏向 B 端"
            else -> "声音来自侧面（正前 / 正后不可辨）"
        }

        val colA = Color.parseColor("#22D3EE")
        val colB = Color.parseColor("#FB7185")
        barA.set("A", normA, peakA, fmtDb(dbOf(f.rmsA)), colA, !running)
        barB.set("B", normB, peakB, fmtDb(dbOf(f.rmsB)), colB, !running)

        // ---- 状态与诊断 ----
        tvStatus.text = if (running) {
            val st = audioStatus
            if (st == null) "启动中…" else String.format(
                Locale.US,
                "采样 %d Hz · 通道 %d · 音频源 %s",
                st.sampleRate, st.channels, st.sourceName
            )
        } else {
            "未启动"
        }

        if (running) {
            val st = audioStatus
            if (st != null && !st.stereo) {
                showWarn("本机未提供双通道录音（当前 ${st.channels} 通道），只能显示响度、无法判断方向。")
            } else if (tvWarn.visibility == View.VISIBLE && st != null) {
                tvWarn.visibility = View.GONE
            }
        }

        val micCm = prefs.micDistanceCm
        val maxUs = DirectionEstimator.maxDelayUs(engine.sampleRate, micCm / 100f)
        tvDiag.text = StringBuilder()
            .append(
                String.format(
                    Locale.US,
                    "间距 %d cm → 最大可测时差 ±%.0f µs（对应 ±90°）\n",
                    micCm, maxUs
                )
            )
            .append(
                String.format(
                    Locale.US,
                    "本帧 时差 %+.0f µs | 电平差 %+.1f dB | 相关峰 z = %.1f\n",
                    f.delayUs, f.ildDb, f.confidence
                )
            )
            .append(
                String.format(
                    Locale.US,
                    "方位 %.1f° | 离散度 %.1f° | 总电平 %.1f dBFS\n",
                    f.azimuthDeg, f.spreadDeg, f.levelDbfs
                )
            )
            .append(
                String.format(
                    Locale.US,
                    "平滑 %d 帧 | 门限 %d dBFS | AGC %s | 轴线 %s",
                    prefs.smoothFrames, prefs.gateDbfs,
                    if (prefs.agc) "开" else "关",
                    if (prefs.axisHorizontal) "横向" else "纵向"
                )
            )
    }

    private fun dbOf(rms: Float): Float =
        if (rms <= SILENT_RMS) -120f else 20f * log10(rms)

    private fun normOf(db: Float, ref: Float): Float {
        if (db <= -110f) return 0f
        return ((db - (ref - SPAN_DB)) / SPAN_DB).coerceIn(0f, 1f).pow(0.72f)
    }

    private fun fmtDb(db: Float): String =
        if (db <= -110f) "  -inf dBFS" else String.format(Locale.US, "%6.1f dB", db)

    private fun showWarn(msg: String) {
        tvWarn.text = msg
        tvWarn.visibility = View.VISIBLE
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
