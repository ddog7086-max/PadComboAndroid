package com.example.padcombo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.padcombo.capture.PadService

class MainActivity : AppCompatActivity(), PadService.Listener {

    private lateinit var statusText: TextView
    private lateinit var toggleBtn: Button
    private var versionName = ""

    private val notifPermLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startHelper()
        else Toast.makeText(this, "未授予通知權限，錄屏服務可能無法啟動", Toast.LENGTH_LONG).show()
    }

    private val projectionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val intent = Intent(this, PadService::class.java).apply {
                putExtra(PadService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(PadService.EXTRA_DATA, result.data)
            }
            ContextCompat.startForegroundService(this, intent)
            setRunning(true)
        } else {
            Toast.makeText(this, "未授權螢幕錄製，助手未啟動", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        toggleBtn = findViewById(R.id.toggleBtn)

        versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }

        toggleBtn.setOnClickListener {
            if (PadService.instance != null) {
                stopService(Intent(this, PadService::class.java))
                setRunning(false)
            } else {
                ensureNotificationPermissionThenStart()
            }
        }
    }

    private fun ensureNotificationPermissionThenStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        startHelper()
    }

    private fun startHelper() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "請先授予懸浮窗權限", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
            return
        }
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun setRunning(running: Boolean) {
        val ver = if (versionName.isNotEmpty()) "v$versionName  " else ""
        toggleBtn.text = if (running) "停止助手" else "開啟助手"
        statusText.text = if (running) {
            "${ver}運行中：點右側「分析」抓幀辨識盤面，再照路線拖珠。"
        } else {
            "${ver}未開啟。先授予通知與懸浮窗權限，再點「開啟助手」允許螢幕錄製。"
        }
    }

    override fun onResume() {
        super.onResume()
        PadService.instance?.setListener(this)
        setRunning(PadService.instance != null)
    }

    override fun onDestroy() {
        PadService.instance?.setListener(null)
        super.onDestroy()
    }

    override fun onStarted() = setRunning(true)
    override fun onStopped() = setRunning(false)
}
