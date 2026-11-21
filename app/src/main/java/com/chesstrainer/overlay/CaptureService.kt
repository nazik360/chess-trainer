package com.chesstrainer.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
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
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import androidx.core.app.NotificationCompat
import com.chesstrainer.R
import com.chesstrainer.engine.EngineProcess
import com.chesstrainer.engine.NetworkManager
import com.chesstrainer.ui.MainActivity
import com.chesstrainer.vision.BoardRecognizer
import com.chesstrainer.vision.Calibration
import com.chesstrainer.vision.FenBuilder

/**
 * Foreground service that owns the MediaProjection screen capture, grabs a
 * frame every [SessionSettings.captureIntervalMs], and - when a calibration
 * and piece templates are available and the session isn't paused - runs
 * recognition + a short Stockfish search on a background thread, publishing
 * the result to [AnalysisBus] for OverlayService to draw.
 */
class CaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private lateinit var captureThread: HandlerThread
    private lateinit var captureHandler: Handler
    private lateinit var workerThread: HandlerThread
    private lateinit var workerHandler: Handler

    private var engine: EngineProcess? = null
    private var engineReady = false

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    private val captureTick = object : Runnable {
        override fun run() {
            captureFrame()
            captureHandler.postDelayed(this, SessionSettings.captureIntervalMs)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        CaptureState.running = true
        captureThread = HandlerThread("capture").apply { start() }
        captureHandler = Handler(captureThread.looper)
        workerThread = HandlerThread("analysis", android.os.Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        workerHandler = Handler(workerThread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, buildNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }

        if (mediaProjection == null) {
            val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
            val data = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
            if (data == null) {
                Log.e(TAG, "No projection data supplied - stopping")
                stopSelf()
                return START_NOT_STICKY
            }
            val mgr = getSystemService(MediaProjectionManager::class.java)
            mediaProjection = mgr.getMediaProjection(resultCode, data)
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopSelf()
                }
            }, captureHandler)
            setUpVirtualDisplay()
            captureHandler.post(captureTick)
            startEngineAsync()
        }
        return START_STICKY
    }

    private fun setUpVirtualDisplay() {
        val metrics = DisplayMetrics()
        val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "chesstrainer-capture",
            screenWidth, screenHeight, screenDensity,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, captureHandler,
        )
    }

    private fun startEngineAsync() {
        workerHandler.post {
            if (!NetworkManager.isDownloaded(this)) {
                AnalysisBus.publish(AnalysisBus.State.Error("NNUE network not downloaded yet - open the app and finish setup first"))
                return@post
            }
            val e = EngineProcess(this)
            if (e.start()) {
                engine = e
                engineReady = true
            } else {
                AnalysisBus.publish(AnalysisBus.State.Error(e.lastError ?: "Failed to start the engine process (no further detail available)"))
            }
        }
    }

    private var restartAttempts = 0

    /** Called from maybeAnalyze's catch block when the engine process has died
     * mid-session (e.g. OOM-killed, or crashed on a bad position). Restarts it
     * once per failure (capped, so a persistently broken setup doesn't loop
     * forever) rather than requiring the user to fully restart the app. */
    private fun restartEngineAsync(reason: String) {
        engineReady = false
        if (restartAttempts >= MAX_RESTART_ATTEMPTS) {
            AnalysisBus.publish(AnalysisBus.State.Error(
                "$reason\n\nGave up restarting the engine after $MAX_RESTART_ATTEMPTS attempts - stop and restart the session from the app."
            ))
            return
        }
        restartAttempts++
        workerHandler.post {
            engine?.stop()
            val e = EngineProcess(this)
            if (e.start()) {
                engine = e
                engineReady = true
                restartAttempts = 0
                Log.i(TAG, "Engine restarted after: $reason")
            } else {
                AnalysisBus.publish(AnalysisBus.State.Error(
                    "$reason\n\nRestart also failed: ${e.lastError ?: "unknown reason"}"
                ))
            }
        }
    }

    private fun captureFrame() {
        val reader = imageReader ?: return
        val image = try { reader.acquireLatestImage() } catch (e: Exception) { null } ?: return
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val bitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride, screenHeight, Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            val cropped = if (bitmap.width != screenWidth) {
                Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
            } else bitmap

            FrameHolder.latest = cropped
            maybeAnalyze(cropped)
        } finally {
            image.close()
        }
    }

    private fun maybeAnalyze(frame: Bitmap) {
        if (SessionSettings.paused || !engineReady) return
        val calib = Calibration.load(this) ?: return
        if (!com.chesstrainer.vision.TemplateStore.exists(this)) return

        workerHandler.post {
            try {
                AnalysisBus.publish(AnalysisBus.State.Thinking)
                val board = BoardRecognizer.recognizeBoard(this, frame, calib) ?: return@post

                val implausibleReason = com.chesstrainer.vision.BoardSanity.checkPlausible(board)
                if (implausibleReason != null) {
                    AnalysisBus.publish(AnalysisBus.State.Error(implausibleReason))
                    return@post
                }

                val fen = FenBuilder.build(board, SessionSettings.whiteToMove)
                val result = engine?.analyzeFen(fen, SessionSettings.moveTimeMs)
                if (result == null) {
                    AnalysisBus.publish(AnalysisBus.State.Error("Engine returned no move for this position (it may have crashed - check the app's earlier setup steps, or try recalibrating)"))
                    return@post
                }

                val from = result.bestMoveUci.substring(0, 2)
                val to = result.bestMoveUci.substring(2, 4)

                val inconsistencyReason = com.chesstrainer.vision.BoardSanity.checkMoveConsistency(
                    board, from, to, SessionSettings.whiteToMove
                )
                if (inconsistencyReason != null) {
                    AnalysisBus.publish(AnalysisBus.State.Error(inconsistencyReason))
                    return@post
                }

                val fromPt = BoardRecognizer.squareCenterOnScreen(from, calib)
                val toPt = BoardRecognizer.squareCenterOnScreen(to, calib)

                AnalysisBus.publish(
                    AnalysisBus.State.Ready(
                        AnalysisBus.Suggestion(from, to, fromPt, toPt, result.scoreCp, result.scoreMate, result.depth)
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Analysis failed", e)
                val message = e.message ?: "analysis failed"
                if (engine?.isRunning != true) {
                    // The engine process itself died (crash, OOM-kill, etc.) -
                    // try to bring it back instead of just reporting the error
                    // forever on every future frame.
                    restartEngineAsync(message)
                } else {
                    AnalysisBus.publish(AnalysisBus.State.Error(message))
                }
            }
        }
    }

    private fun buildNotification(): Notification {
        val channelId = "chesstrainer_capture"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Chess Trainer capture", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.capture_notif_title))
            .setContentText(getString(R.string.capture_notif_text))
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        CaptureState.running = false
        captureHandler.removeCallbacksAndMessages(null)
        imageReader?.close()
        virtualDisplay?.release()
        mediaProjection?.stop()
        engine?.stop()
        captureThread.quitSafely()
        workerThread.quitSafely()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CaptureService"
        private const val NOTIF_ID = 42
        private const val MAX_RESTART_ATTEMPTS = 3
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, CaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CaptureService::class.java))
        }
    }
}
