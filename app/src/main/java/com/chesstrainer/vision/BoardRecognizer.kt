package com.chesstrainer.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.PointF
import kotlin.math.sqrt

/**
 * Turns a full-screen screenshot + the saved [Calibration] into a recognized
 * 8x8 board (an array of 64 [PieceClass] values, in a8->h1 rank-major order),
 * using simple nearest-neighbour template matching. No network, no ML model,
 * no third-party CV library - this works for one board skin at a time (the
 * one you calibrated against) and is meant to be good enough for a personal
 * study/analysis tool, not a universal "screenshot of any chess app" solver.
 */
object BoardRecognizer {

    private const val BOARD_PX = 512          // warped square board size
    private const val CELL_PX = BOARD_PX / 8
    private const val GRID = 8                 // luminance sub-grid per cell -> 64 features
    const val FEATURE_SIZE = GRID * GRID + 3    // + avg R,G,B

    /** Perspective-warps the calibrated board quad out of a full screenshot into a BOARD_PX square. */
    fun warpToSquare(screenshot: Bitmap, calib: Calibration): Bitmap {
        val src = floatArrayOf(
            calib.topLeft.x, calib.topLeft.y,
            calib.topRight.x, calib.topRight.y,
            calib.bottomRight.x, calib.bottomRight.y,
            calib.bottomLeft.x, calib.bottomLeft.y,
        )
        val dst = floatArrayOf(
            0f, 0f,
            BOARD_PX.toFloat(), 0f,
            BOARD_PX.toFloat(), BOARD_PX.toFloat(),
            0f, BOARD_PX.toFloat(),
        )
        val m = Matrix()
        m.setPolyToPoly(src, 0, dst, 0, 4)

        val out = Bitmap.createBitmap(BOARD_PX, BOARD_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        // Canvas.drawBitmap(bitmap, matrix, paint) maps *bitmap-local* coords
        // through `matrix` into canvas coords - which is exactly `m` above
        // (screenshot-pixel -> board-square-pixel), applied to the whole
        // screenshot bitmap (matrix also implicitly crops to the board quad
        // since everything outside maps outside the BOARD_PX canvas).
        canvas.drawBitmap(screenshot, m, null)
        return out
    }

    /** Slices the warped board into 64 cells (row-major, visual top-left to bottom-right)
     *  and converts each cell to a small feature vector for matching. Deflips
     *  to canonical a8->h1 order when [flipped] is true (black shown at the bottom). */
    fun extractCellFeatures(boardBitmap: Bitmap, flipped: Boolean): Array<FloatArray> {
        val raw = Array(64) { FloatArray(FEATURE_SIZE) }
        for (row in 0 until 8) {
            for (col in 0 until 8) {
                val idx = row * 8 + col
                raw[idx] = cellFeatures(boardBitmap, row, col)
            }
        }
        return if (flipped) raw.reversedArray() else raw
    }

    private fun cellFeatures(board: Bitmap, row: Int, col: Int): FloatArray {
        val x0 = col * CELL_PX
        val y0 = row * CELL_PX
        // Ignore a margin around each cell edge - piece art is centered and
        // this avoids grid-line/board-border bleed from neighbouring cells.
        val margin = CELL_PX / 8
        val inner = CELL_PX - 2 * margin
        val sub = inner / GRID

        val feat = FloatArray(FEATURE_SIZE)
        var sumR = 0f; var sumG = 0f; var sumB = 0f; var n = 0

        for (gy in 0 until GRID) {
            for (gx in 0 until GRID) {
                val px = x0 + margin + gx * sub + sub / 2
                val py = y0 + margin + gy * sub + sub / 2
                val clampedX = px.coerceIn(0, board.width - 1)
                val clampedY = py.coerceIn(0, board.height - 1)
                val pixel = board.getPixel(clampedX, clampedY)
                val r = ((pixel shr 16) and 0xFF) / 255f
                val g = ((pixel shr 8) and 0xFF) / 255f
                val b = (pixel and 0xFF) / 255f
                val lum = 0.299f * r + 0.587f * g + 0.114f * b
                feat[gy * GRID + gx] = lum
                sumR += r; sumG += g; sumB += b; n++
            }
        }
        feat[GRID * GRID] = sumR / n
        feat[GRID * GRID + 1] = sumG / n
        feat[GRID * GRID + 2] = sumB / n
        return feat
    }

    private fun distance(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) {
            val d = a[i] - b[i]
            sum += d * d
        }
        return sqrt(sum)
    }

    /**
     * Call this once during calibration, with a screenshot of the STANDARD
     * STARTING POSITION correctly aligned to the calibrated corners. Builds
     * and persists one averaged template per piece class.
     */
    fun captureTemplates(context: Context, screenshot: Bitmap, calib: Calibration) {
        val board = warpToSquare(screenshot, calib)
        val cells = extractCellFeatures(board, calib.flipped)

        val sums = HashMap<PieceClass, FloatArray>()
        val counts = HashMap<PieceClass, Int>()
        for (i in 0 until 64) {
            val cls = PieceClass.STANDARD_START[i]
            val acc = sums.getOrPut(cls) { FloatArray(FEATURE_SIZE) }
            val feat = cells[i]
            for (k in feat.indices) acc[k] += feat[k]
            counts[cls] = (counts[cls] ?: 0) + 1
        }
        val templates = HashMap<PieceClass, FloatArray>()
        for ((cls, acc) in sums) {
            val c = counts[cls] ?: 1
            templates[cls] = FloatArray(acc.size) { acc[it] / c }
        }
        TemplateStore.save(context, templates)
    }

    /** Recognizes the live board using the stored templates. Returns null if
     * templates haven't been captured yet. */
    fun recognizeBoard(context: Context, screenshot: Bitmap, calib: Calibration): Array<PieceClass>? {
        val templates = TemplateStore.load(context) ?: return null
        val board = warpToSquare(screenshot, calib)
        val cells = extractCellFeatures(board, calib.flipped)

        return Array(64) { i ->
            templates.entries.minByOrNull { (_, vec) -> distance(vec, cells[i]) }?.key
                ?: PieceClass.EMPTY_LIGHT
        }
    }

    /**
     * Maps a UCI square like "e4" to a point on the ORIGINAL screenshot
     * (useful for drawing the overlay arrow at the right spot on the real
     * screen, not the warped square image).
     */
    fun squareCenterOnScreen(square: String, calib: Calibration): PointF {
        val file = square[0] - 'a'   // 0..7
        val rank = square[1] - '1'   // 0..7 (0 = rank1)
        // canonical row/col in our a8->h1, row-major grid:
        var col = file
        var row = 7 - rank
        if (calib.flipped) {
            col = 7 - col
            row = 7 - row
        }
        val cx = (col + 0.5f) * CELL_PX
        val cy = (row + 0.5f) * CELL_PX

        val src = floatArrayOf(
            calib.topLeft.x, calib.topLeft.y,
            calib.topRight.x, calib.topRight.y,
            calib.bottomRight.x, calib.bottomRight.y,
            calib.bottomLeft.x, calib.bottomLeft.y,
        )
        val dst = floatArrayOf(
            0f, 0f, BOARD_PX.toFloat(), 0f, BOARD_PX.toFloat(), BOARD_PX.toFloat(), 0f, BOARD_PX.toFloat(),
        )
        val m = Matrix()
        m.setPolyToPoly(src, 0, dst, 0, 4)
        val inv = Matrix()
        m.invert(inv)
        val pts = floatArrayOf(cx, cy)
        inv.mapPoints(pts)
        return PointF(pts[0], pts[1])
    }
}
