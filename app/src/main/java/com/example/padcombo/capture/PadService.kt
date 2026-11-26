package com.example.padcombo.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.example.padcombo.R
import com.example.padcombo.core.Board
import com.example.padcombo.core.BoardRecognizer
import com.example.padcombo.core.BeamSearchSolver
import com.example.padcombo.core.CalcMode
import com.example.padcombo.core.MovesPref
import com.example.padcombo.core.RoiRect
import com.example.padcombo.core.SearchConfig
import com.example.padcombo.overlay.RouteBoardView
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * 前景服務：MediaProjection 抓幀 → 辨識 6×5 盤面 → Beam Search 算路徑 →
 * 懸浮層繪製白線疊加於遊戲之上。
 */
class PadService : Service() {

    interface Listener {
        fun onStarted() {}
        fun onStopped() {}
    }

    @Volatile
    private var listener: Listener? = null

    fun setListener(l: Listener?) {
        listener = l
    }

    private lateinit var windowManager: WindowManager
    private var overlay: RouteBoardView? = null
    private var routeRoot: FrameLayout? = null
    private var controlBar: FrameLayout? = null
    private var analyzeBtn: ImageButton? = null
    private var resultView: TextView? = null

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var solverExecutor = Executors.newSingleThreadExecutor()
    @Volatile
    private var searchConfig: SearchConfig = BeamSearchSolver.defaultWeights().let { weights ->
        SearchConfig(
            orbWeights = weights,
            calcMode = CalcMode.FAST,
            maxSteps = 18,
            movesPref = MovesPref.LONG,
            targetHighCombo = true,
            targetMultiColor = true,
            beamWidth = 32,
        )
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        resetBeforeStart()

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, buildNotification(), FOREGROUND_TYPE)
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIF_ID, buildNotification())
        }

        if (resultCode != 0 && data != null && projection == null) {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, data)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() = teardown()
            }, mainHandler)
            startCapture()
            addOverlay()
            listener?.onStarted()
        }
        return START_NOT_STICKY
    }

    private fun startCapture() {
        val metrics: DisplayMetrics = resources.displayMetrics
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection?.createVirtualDisplay(
            "padcombo-capture",
            w, h, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, null,
        )
    }

    private fun restoreAnalyzeBtn() { analyzeBtn?.isEnabled = true }

    private fun analyze() {
        analyzeBtn?.isEnabled = false
        val reader = imageReader ?: run { restoreAnalyzeBtn(); return }
        val image = reader.acquireLatestImage() ?: run { restoreAnalyzeBtn(); return }

        val bitmap = try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width
            val padded = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride, image.height,
                Bitmap.Config.ARGB_8888,
            )
            padded.copyPixelsFromBuffer(buffer)
            val scale = min(1.0, 900.0 / padded.width)
            if (scale < 1.0) {
                Bitmap.createScaledBitmap(
                    padded, (padded.width * scale).toInt(),
                    (padded.height * scale).toInt(), true,
                ).also { if (it != padded) padded.recycle() }
            } else padded
        } catch (e: Exception) {
            null
        } finally {
            image.close()
        } ?: run { restoreAnalyzeBtn(); return }

        val recognized = BoardRecognizer.recognizeBitmap(bitmap, RoiRect.FULL)
        val board: Board = recognized.board
        bitmap.recycle()

        solverExecutor.execute {
            val result = BeamSearchSolver.beamSearch(board, searchConfig)
            mainHandler.post {
                overlay?.show(board, result.path)
                val cleared = result.clearStat.values.sum()
                resultView?.text =
                    "${result.predictedCombo}c · ${result.path.size}步 · ${cleared}消"
                restoreAnalyzeBtn()
            }
        }
    }

    private fun addOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val root = FrameLayout(this)
        val view = RouteBoardView(this)
        root.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        )
        windowManager.addView(root, params)

        val bar = FrameLayout(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val kd = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        analyzeBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_refresh)
            setBackgroundResource(R.drawable.btn_gray)
            stateListAnimator = null
            val pad = (10 * kd).toInt()
            setPadding(pad, pad, pad, pad)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            val s = (46 * kd).toInt()
            layoutParams = LinearLayout.LayoutParams(s, s)
            setOnClickListener { analyze() }
        }
        row.addView(analyzeBtn)

        val closeBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_power)
            setBackgroundResource(R.drawable.btn_gray)
            stateListAnimator = null
            val pad = (9 * kd).toInt()
            setPadding(pad, pad, pad, pad)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            val s = (42 * kd).toInt()
            layoutParams = LinearLayout.LayoutParams(s, s).apply { marginStart = (8 * kd).toInt() }
            setOnClickListener { teardown() }
        }
        row.addView(closeBtn)
        col.addView(row)

        resultView = TextView(this).apply {
            text = "—"
            textSize = 9f
            setTextColor(0xFFEEEEEE.toInt())
            setBackgroundColor(0xAA000000.toInt())
            setPadding(6, 3, 6, 3)
        }
        col.addView(resultView)
        bar.addView(col)

        val barParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        )
        barParams.gravity = Gravity.TOP or Gravity.END
        barParams.x = 8
        barParams.y = 48
        windowManager.addView(bar, barParams)

        overlay = view
        routeRoot = root
        controlBar = bar
    }

    private fun buildNotification(): Notification {
        val channelId = "padcombo"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Pad Combo", NotificationManager.IMPORTANCE_LOW),
            )
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("PadCombo 运行中")
            .setContentText("正在识别盘面并绘制路线")
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .build()
    }

    private fun resetBeforeStart() {
        solverExecutor.shutdownNow()
        solverExecutor = Executors.newSingleThreadExecutor()
        detachOverlays()
    }

    private fun detachOverlays() {
        routeRoot?.let { runCatching { windowManager.removeView(it) } }
        controlBar?.let { runCatching { windowManager.removeView(it) } }
        routeRoot = null
        controlBar = null
        overlay = null
    }

    private fun teardown() {
        solverExecutor.shutdownNow()
        detachOverlays()
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        projection?.stop()
        projection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        listener?.onStopped()
        stopSelf()
    }

    override fun onDestroy() {
        instance = null
        teardown()
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: PadService? = null
            private set

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        private const val NOTIF_ID = 43
        private val FOREGROUND_TYPE =
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
    }
}
