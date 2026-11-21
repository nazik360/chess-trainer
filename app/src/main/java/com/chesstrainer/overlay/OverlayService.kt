package com.chesstrainer.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PointF
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.chesstrainer.R
import com.chesstrainer.ui.MainActivity
import java.io.File
import java.io.FileOutputStream
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Draws the floating move suggestion (an arrow from-square -> to-square) and
 * a small draggable control chip (pause/resume, side-to-move, stop) on top
 * of whatever app is in front. Pure overlay - this service never reads
 * anything on its own; it only draws what CaptureService publishes to
 * [AnalysisBus].
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var arrowView: ArrowView? = null
    private var chipView: View? = null

    private val listener: (AnalysisBus.State) -> Unit = { state ->
        arrowView?.post { arrowView?.render(state) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        addArrowOverlay()
        addControlChip()
        AnalysisBus.subscribe(listener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID, buildNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }
        return START_STICKY
    }

    private fun overlayType() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun addArrowOverlay() {
        // MATCH_PARENT is sized against the window manager's "available"
        // area, which on many OEM builds excludes the status bar (and
        // sometimes the nav bar) - a few dozen pixels the MediaProjection
        // capture (CaptureService, via getRealMetrics) does NOT exclude.
        // That mismatch is exactly a constant "arrow is close but not quite
        // on the piece" offset. Fix: size this window to the SAME real
        // pixel dimensions CaptureService captures, anchored at the true
        // (0,0) top-left corner, and tell it to ignore system bar/cutout
        // insets entirely, so canvas pixel (x,y) here always means the same
        // physical screen pixel as in the captured screenshot.
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        val params = WindowManager.LayoutParams(
            metrics.widthPixels,
            metrics.heightPixels,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        val view = ArrowView(this)
        @Suppress("DEPRECATION")
        view.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
        windowManager.addView(view, params)
        arrowView = view
    }

    private fun addControlChip() {
        val chip = ControlChipView(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 24
        params.y = 120

        chip.onDrag = { dx, dy ->
            params.x += dx
            params.y += dy
            windowManager.updateViewLayout(chip, params)
        }
        chip.onToggleSide = {
            SessionSettings.whiteToMove = !SessionSettings.whiteToMove
            chip.invalidate()
        }
        chip.onTogglePause = {
            SessionSettings.paused = !SessionSettings.paused
            chip.invalidate()
        }
        chip.onCalibrate = { launchCalibration() }
        chip.onStop = { stopSelf(); CaptureService.stop(this) }

        windowManager.addView(chip, params)
        chipView = chip
    }

    /**
     * Freezes the current live frame to a cache file and launches
     * CalibrationActivity on top of whatever app is in front, pointed at that
     * frozen screenshot (not a live feed - calibration works on one still
     * image so the corner handles don't jump around while you drag them).
     */
    private fun launchCalibration() {
        val frame = FrameHolder.latest
        if (frame == null) {
            android.widget.Toast.makeText(this, R.string.capture_timeout, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val file = File(cacheDir, "calibration_frame.png")
        try {
            FileOutputStream(file).use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (e: Exception) {
            return
        }
        val intent = Intent(this, com.chesstrainer.ui.CalibrationActivity::class.java)
            .putExtra(com.chesstrainer.ui.CalibrationActivity.EXTRA_FRAME_PATH, file.absolutePath)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    private fun buildNotification(): Notification {
        val channelId = "chesstrainer_overlay"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Chess Trainer overlay", NotificationManager.IMPORTANCE_MIN)
            )
        }
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.overlay_notif_title))
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        AnalysisBus.unsubscribe(listener)
        arrowView?.let { runCatching { windowManager.removeView(it) } }
        chipView?.let { runCatching { windowManager.removeView(it) } }
        super.onDestroy()
    }

    /** Full-screen, touch-transparent view that draws the suggested move as an arrow. */
    private class ArrowView(context: Context) : View(context) {
        private var state: AnalysisBus.State = AnalysisBus.State.Idle
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(220, 60, 220, 110)
            strokeWidth = 14f
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(230, 60, 220, 110)
            style = Paint.Style.FILL
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 36f
            setShadowLayer(6f, 0f, 0f, Color.BLACK)
        }

        fun render(newState: AnalysisBus.State) {
            state = newState
            invalidate()
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            when (val s = state) {
                is AnalysisBus.State.Ready -> drawArrow(canvas, s.suggestion)
                is AnalysisBus.State.Thinking -> canvas.drawText("Thinking…", 24f, 220f, textPaint)
                is AnalysisBus.State.Error -> drawWrappedError(canvas, "⚠ ${s.message}")
                AnalysisBus.State.Idle -> {}
            }
        }

        private fun drawArrow(canvas: android.graphics.Canvas, s: AnalysisBus.Suggestion) {
            val from: PointF = s.fromPoint
            val to: PointF = s.toPoint
            canvas.drawLine(from.x, from.y, to.x, to.y, linePaint)

            val angle = atan2((to.y - from.y).toDouble(), (to.x - from.x).toDouble())
            val headLen = 36f
            val a1 = angle + Math.PI - Math.PI / 7
            val a2 = angle + Math.PI + Math.PI / 7
            canvas.drawLine(to.x, to.y, (to.x + headLen * cos(a1)).toFloat(), (to.y + headLen * sin(a1)).toFloat(), linePaint)
            canvas.drawLine(to.x, to.y, (to.x + headLen * cos(a2)).toFloat(), (to.y + headLen * sin(a2)).toFloat(), linePaint)

            canvas.drawCircle(from.x, from.y, 16f, dotPaint)

            val evalText = when {
                s.scoreMate != null -> "Mate in ${s.scoreMate}"
                s.scoreCp != null -> String.format("%.2f", s.scoreCp / 100.0)
                else -> ""
            }
            canvas.drawText("${s.fromSquare}-${s.toSquare}  $evalText  (d${s.depth})", 24f, 220f, textPaint)
        }

        /** Error messages can be long (we now surface the real exception/reason -
         * see EngineProcess.lastError) - wrap them across multiple lines instead
         * of letting drawText run off the right edge of the screen. */
        private fun drawWrappedError(canvas: android.graphics.Canvas, message: String) {
            val maxWidth = (width - 48).coerceAtLeast(200)
            val words = message.split(" ")
            val lines = mutableListOf<String>()
            var current = StringBuilder()
            for (word in words) {
                val candidate = if (current.isEmpty()) word else "$current $word"
                if (textPaint.measureText(candidate) > maxWidth && current.isNotEmpty()) {
                    lines.add(current.toString())
                    current = StringBuilder(word)
                } else {
                    current = StringBuilder(candidate)
                }
            }
            if (current.isNotEmpty()) lines.add(current.toString())

            var y = 220f
            for (line in lines) {
                canvas.drawText(line, 24f, y, textPaint)
                y += textPaint.textSize + 8f
            }
        }
    }

    /** Small draggable chip: pause/resume, side-to-move, stop. Deliberately
     * plain Views (no XML inflate) to keep this file self-contained. */
    private class ControlChipView(context: Context) : android.widget.LinearLayout(context) {
        var onDrag: ((Int, Int) -> Unit)? = null
        var onToggleSide: (() -> Unit)? = null
        var onTogglePause: (() -> Unit)? = null
        var onCalibrate: (() -> Unit)? = null
        var onStop: (() -> Unit)? = null

        private var lastX = 0f
        private var lastY = 0f

        private val sideBtn: android.widget.Button
        private val pauseBtn: android.widget.Button
        private val calibrateBtn: android.widget.Button
        private val stopBtn: android.widget.Button

        init {
            orientation = HORIZONTAL
            setBackgroundColor(Color.argb(200, 30, 30, 30))
            setPadding(16, 12, 16, 12)

            sideBtn = android.widget.Button(context).apply { textSize = 11f }
            pauseBtn = android.widget.Button(context).apply { textSize = 11f }
            calibrateBtn = android.widget.Button(context).apply { text = "Calibrate"; textSize = 11f }
            stopBtn = android.widget.Button(context).apply { text = "Stop"; textSize = 11f }

            addView(sideBtn)
            addView(pauseBtn)
            addView(calibrateBtn)
            addView(stopBtn)

            sideBtn.setOnClickListener { onToggleSide?.invoke() }
            pauseBtn.setOnClickListener { onTogglePause?.invoke() }
            calibrateBtn.setOnClickListener { onCalibrate?.invoke() }
            stopBtn.setOnClickListener { onStop?.invoke() }

            refreshLabels()
        }

        fun refreshLabels() {
            sideBtn.text = if (SessionSettings.whiteToMove) "White to move" else "Black to move"
            pauseBtn.text = if (SessionSettings.paused) "Resume" else "Pause"
        }

        override fun invalidate() {
            refreshLabels()
            super.invalidate()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lastX = event.rawX; lastY = event.rawY }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - lastX).toInt()
                    val dy = (event.rawY - lastY).toInt()
                    if (hypot(dx.toDouble(), dy.toDouble()) > 3) {
                        onDrag?.invoke(dx, dy)
                        lastX = event.rawX; lastY = event.rawY
                    }
                }
            }
            return super.onTouchEvent(event)
        }
    }

    companion object {
        private const val NOTIF_ID = 43

        fun start(context: Context) {
            context.startForegroundService(Intent(context, OverlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }
}
