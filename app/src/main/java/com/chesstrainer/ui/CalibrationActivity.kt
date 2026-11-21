package com.chesstrainer.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.chesstrainer.R
import com.chesstrainer.databinding.ActivityCalibrationBinding
import com.chesstrainer.vision.BoardRecognizer
import com.chesstrainer.vision.Calibration
import kotlin.math.hypot

/**
 * Lets the user align four draggable handles to the board's corners over a
 * frozen screenshot (see OverlayService.launchCalibration), then - once the
 * target app is showing the STANDARD STARTING POSITION - captures per-piece
 * templates from that same frame.
 *
 * Two-step flow, both on the same screenshot:
 *  1. Drag the four corner handles onto the board's actual corners, toggle
 *     "Black at bottom" if needed, tap Next.
 *  2. Confirm the screenshot shows the standard starting position, tap
 *     "Capture piece templates".
 */
class CalibrationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCalibrationBinding
    private lateinit var frame: Bitmap
    private lateinit var boardView: CornerOverlayView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCalibrationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val path = intent.getStringExtra(EXTRA_FRAME_PATH)
        val loaded = path?.let { BitmapFactory.decodeFile(it) }
        if (loaded == null) {
            Toast.makeText(this, R.string.calibration_frame_missing, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        frame = loaded
        binding.screenshotImage.setImageBitmap(frame)

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        boardView = CornerOverlayView(this, frame.width, frame.height)
        binding.overlayContainer.addView(boardView)

        binding.switchFlipped.setOnCheckedChangeListener { _, _ -> }

        binding.btnSaveCorners.setOnClickListener { onCornersConfirmed() }
        binding.btnCaptureTemplates.setOnClickListener { onCaptureTemplatesClicked() }
        binding.btnCaptureTemplates.isEnabled = false
    }

    private fun onCornersConfirmed() {
        val corners = boardView.cornersInImageSpace()
        val calib = Calibration(
            topLeft = corners[0],
            topRight = corners[1],
            bottomRight = corners[2],
            bottomLeft = corners[3],
            flipped = binding.switchFlipped.isChecked,
            screenWidth = frame.width,
            screenHeight = frame.height,
        )
        Calibration.save(this, calib)
        Toast.makeText(this, R.string.corners_saved, Toast.LENGTH_SHORT).show()
        binding.btnCaptureTemplates.isEnabled = true
        binding.stepTemplatesLabel.visibility = View.VISIBLE
    }

    private fun onCaptureTemplatesClicked() {
        val calib = Calibration.load(this) ?: return
        try {
            val looksSane = BoardRecognizer.captureTemplates(this, frame, calib)
            if (looksSane) {
                Toast.makeText(this, R.string.templates_captured, Toast.LENGTH_SHORT).show()
            } else {
                // Don't just finish() silently here - this specific mistake
                // produces a calibration that LOOKS successful (no crash, a
                // normal grid) but will make every later move suggestion
                // confident nonsense. Keep the user on this screen so they
                // actually see it instead of only finding out mid-game.
                Toast.makeText(this, R.string.templates_inverted_warning, Toast.LENGTH_LONG).show()
                return
            }
            finish()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.templates_capture_failed, e.message), Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Draws the frozen screenshot's four corner handles and lets the user
     * drag each one independently. Coordinates are tracked in *view* space
     * and converted to *image* space (frame.width x frame.height) via the
     * ImageView's displayed matrix, since the screenshot is shown scaled to
     * fit the activity's bounds.
     */
    private inner class CornerOverlayView(context: android.content.Context, val imgW: Int, val imgH: Int) : View(context) {
        // Default to a centered inset rectangle - close enough for the user to
        // drag the final few pixels on most boards that fill most of the screen.
        private val points = arrayOf(
            PointF(imgW * 0.1f, imgH * 0.15f),  // top-left
            PointF(imgW * 0.9f, imgH * 0.15f),  // top-right
            PointF(imgW * 0.9f, imgH * 0.85f),  // bottom-right
            PointF(imgW * 0.1f, imgH * 0.85f),  // bottom-left
        )
        private var dragIndex = -1

        private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(230, 255, 80, 60); style = Paint.Style.FILL
        }
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(200, 255, 220, 60); style = Paint.Style.STROKE; strokeWidth = 6f
        }
        // Internal 8x8 grid preview - this is the whole point: the four corner
        // handles alone give no feedback on whether each of the 64 cells
        // actually lines up with a real square until it's too late (a
        // misaligned calibration still LOOKS fine as 4 dots on the corners,
        // but produces a board reading that's off by a row/column - see the
        // "arrow points at the wrong piece" class of bug). Drawing the grid
        // lines these corners imply lets you see that misalignment directly,
        // as you drag, instead of discovering it later as a nonsense move.
        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(170, 50, 220, 255); style = Paint.Style.STROKE; strokeWidth = 2.5f
        }

        init {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }

        /** Image is laid out with fitCenter scaling inside this (match_parent) view. */
        private fun imageToViewScale(): Triple<Float, Float, Float> {
            val scale = minOf(width.toFloat() / imgW, height.toFloat() / imgH)
            val offX = (width - imgW * scale) / 2f
            val offY = (height - imgH * scale) / 2f
            return Triple(scale, offX, offY)
        }

        private fun imageToView(p: PointF): PointF {
            val (s, ox, oy) = imageToViewScale()
            return PointF(p.x * s + ox, p.y * s + oy)
        }

        private fun viewToImage(x: Float, y: Float): PointF {
            val (s, ox, oy) = imageToViewScale()
            return PointF((x - ox) / s, (y - oy) / s)
        }

        fun cornersInImageSpace(): Array<PointF> = points

        /** Maps the unit board space (file/rank 0..8 on each axis) onto the
         * four current corner handles, in IMAGE pixel coordinates - the exact
         * same perspective transform BoardRecognizer.warpToSquare uses (src =
         * screen quad, dst = unit square), just inverted (src = unit square,
         * dst = screen quad) since here we go the other direction: from a
         * board-space grid line to where it falls on the screenshot. */
        private fun boardToImageMatrix(): Matrix {
            val src = floatArrayOf(0f, 0f, 8f, 0f, 8f, 8f, 0f, 8f)
            val dst = floatArrayOf(
                points[0].x, points[0].y,
                points[1].x, points[1].y,
                points[2].x, points[2].y,
                points[3].x, points[3].y,
            )
            return Matrix().apply { setPolyToPoly(src, 0, dst, 0, 4) }
        }

        private fun drawGrid(canvas: Canvas) {
            val m = boardToImageMatrix()
            for (i in 0..8) {
                val vPts = floatArrayOf(i.toFloat(), 0f, i.toFloat(), 8f)
                m.mapPoints(vPts)
                val v1 = imageToView(PointF(vPts[0], vPts[1]))
                val v2 = imageToView(PointF(vPts[2], vPts[3]))
                canvas.drawLine(v1.x, v1.y, v2.x, v2.y, gridPaint)

                val hPts = floatArrayOf(0f, i.toFloat(), 8f, i.toFloat())
                m.mapPoints(hPts)
                val h1 = imageToView(PointF(hPts[0], hPts[1]))
                val h2 = imageToView(PointF(hPts[2], hPts[3]))
                canvas.drawLine(h1.x, h1.y, h2.x, h2.y, gridPaint)
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            drawGrid(canvas)

            val vp = points.map { imageToView(it) }
            for (i in vp.indices) {
                val a = vp[i]
                val b = vp[(i + 1) % vp.size]
                canvas.drawLine(a.x, a.y, b.x, b.y, linePaint)
            }
            for (p in vp) {
                canvas.drawCircle(p.x, p.y, 28f, handlePaint)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val vp = points.map { imageToView(it) }
                    dragIndex = vp.indices.minByOrNull { i ->
                        hypot((vp[i].x - event.x).toDouble(), (vp[i].y - event.y).toDouble())
                    }?.takeIf { i -> hypot((vp[i].x - event.x).toDouble(), (vp[i].y - event.y).toDouble()) < 90 } ?: -1
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragIndex >= 0) {
                        val img = viewToImage(event.x, event.y)
                        points[dragIndex] = PointF(
                            img.x.coerceIn(0f, imgW.toFloat()),
                            img.y.coerceIn(0f, imgH.toFloat()),
                        )
                        invalidate()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragIndex = -1
            }
            return true
        }
    }

    companion object {
        const val EXTRA_FRAME_PATH = "frame_path"
    }
}
