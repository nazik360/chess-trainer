package com.chesstrainer.ui

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.chesstrainer.R
import com.chesstrainer.databinding.ActivityMainBinding
import com.chesstrainer.engine.NetworkManager
import com.chesstrainer.overlay.CaptureService
import com.chesstrainer.overlay.CaptureState
import com.chesstrainer.overlay.OverlayService
import com.chesstrainer.vision.Calibration
import com.chesstrainer.vision.TemplateStore

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var sessionRunning = false

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshStatus() }

    private val overlaySettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshStatus() }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            CaptureService.start(this, result.resultCode, result.data!!)
            OverlayService.start(this)
            sessionRunning = true
            refreshStatus()
            Toast.makeText(this, R.string.session_started, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, R.string.projection_denied, Toast.LENGTH_SHORT).show()
        }
    }

    private val startCaptureForCalibrationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            CaptureService.start(this, result.resultCode, result.data!!)
            OverlayService.start(this)
            sessionRunning = true
            refreshStatus()
            Toast.makeText(this, R.string.calibrate_instructions, Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, R.string.projection_denied, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnOverlayPermission.setOnClickListener { requestOverlayPermission() }
        binding.btnDownloadNet.setOnClickListener { downloadNetwork() }
        binding.btnCalibrate.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, R.string.need_overlay_permission, Toast.LENGTH_SHORT).show()
            } else if (CaptureState.running) {
                // Capture is already live (a session is running) - the floating
                // chip's "Calibrate" button works directly off the current frame.
                Toast.makeText(this, R.string.calibrate_instructions, Toast.LENGTH_LONG).show()
            } else {
                val mgr = getSystemService(MediaProjectionManager::class.java)
                startCaptureForCalibrationLauncher.launch(mgr.createScreenCaptureIntent())
            }
        }
        binding.btnStartSession.setOnClickListener { onStartSessionClicked() }
        binding.btnStopSession.setOnClickListener { onStopSessionClicked() }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        // CaptureService may already be running - e.g. the user started it from
        // the "Calibrate" flow, or it survived this Activity being recreated.
        sessionRunning = CaptureState.running
        refreshStatus()
    }

    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            )
            overlaySettingsLauncher.launch(intent)
        }
    }

    private fun downloadNetwork() {
        if (NetworkManager.isDownloaded(this)) {
            Toast.makeText(this, R.string.net_already_downloaded, Toast.LENGTH_SHORT).show()
            return
        }
        binding.btnDownloadNet.isEnabled = false
        binding.downloadProgress.visibility = android.view.View.VISIBLE
        Thread {
            NetworkManager.download(
                this,
                onProgress = { pct -> runOnUiThread { binding.downloadProgress.progress = pct } },
                onDone = { error ->
                    runOnUiThread {
                        binding.btnDownloadNet.isEnabled = true
                        if (error == null) {
                            Toast.makeText(this, R.string.net_download_done, Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, getString(R.string.net_download_failed, error), Toast.LENGTH_LONG).show()
                        }
                        refreshStatus()
                    }
                },
            )
        }.start()
    }

    private fun onStartSessionClicked() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.need_overlay_permission, Toast.LENGTH_SHORT).show()
            return
        }
        if (!NetworkManager.isDownloaded(this)) {
            Toast.makeText(this, R.string.need_network_download, Toast.LENGTH_SHORT).show()
            return
        }
        if (Calibration.load(this) == null || !TemplateStore.exists(this)) {
            Toast.makeText(this, R.string.need_calibration, Toast.LENGTH_SHORT).show()
            return
        }
        if (CaptureState.running) {
            // Capture was already started during calibration - just make sure
            // the overlay is up and we're good to go.
            OverlayService.start(this)
            sessionRunning = true
            refreshStatus()
            return
        }
        val mgr = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(mgr.createScreenCaptureIntent())
    }

    private fun onStopSessionClicked() {
        CaptureService.stop(this)
        OverlayService.stop(this)
        sessionRunning = false
        refreshStatus()
    }

    private fun refreshStatus() {
        val overlayOk = Settings.canDrawOverlays(this)
        val netOk = NetworkManager.isDownloaded(this)
        val calibOk = Calibration.load(this) != null && TemplateStore.exists(this)

        binding.statusOverlay.text = statusLine(getString(R.string.status_overlay), overlayOk)
        binding.statusNet.text = statusLine(getString(R.string.status_network), netOk)
        binding.statusCalib.text = statusLine(getString(R.string.status_calibration), calibOk)

        binding.btnStartSession.isEnabled = overlayOk && netOk && calibOk && !sessionRunning
        binding.btnStopSession.isEnabled = sessionRunning
        binding.btnCalibrate.isEnabled = overlayOk
    }

    private fun statusLine(label: String, ok: Boolean) = "${if (ok) "✅" else "⬜"} $label"
}
