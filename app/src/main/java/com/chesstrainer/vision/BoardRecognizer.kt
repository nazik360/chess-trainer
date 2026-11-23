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

    // Center-weighting for the 8x8 luminance sub-grid: a cell's outer ring of
    // sample points sits closest to the grid lines and to whatever is on the
    // NEIGHBOURING cell, so it's the most exposed to a slightly-off
    // calibration, anti-aliasing, or a piece glyph that slightly overflows
    // its own square. Down-weighting (not discarding - a hard margin-crop is
    // already applied in cellFeatures) those outer points before computing a
    // distance makes matching noticeably more forgiving of exactly the kind
    // of small misalignment that's been behind several earlier bugs, without
    // throwing away real signal from a piece that happens to sit off-center.
    private val gridWeights: FloatArray = FloatArray(GRID * GRID).also { arr ->
        val center = (GRID - 1) / 2f
        val sigma = GRID / 2.5f
        for (gy in 0 until GRID) {
            for (gx in 0 until GRID) {
                val dx = gx - center
                val dy = gy - center
                arr[gy * GRID + gx] = kotlin.math.exp(-(dx * dx + dy * dy) / (2f * sigma * sigma))
            }
        }
    }

    private fun distance(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in 0 until GRID * GRID) {
            val d = a[i] - b[i]
            sum += gridWeights[i] * d * d
        }
        // The trailing avg-R/G/B features describe the whole cell, not a
        // specific sub-position, so there's nothing to weight there.
        for (i in GRID * GRID until FEATURE_SIZE) {
            val d = a[i] - b[i]
            sum += d * d
        }
        return sqrt(sum)
    }

    /**
     * Call this once during calibration, with a screenshot of the STANDARD
     * STARTING POSITION correctly aligned to the calibrated corners. Builds
     * and persists one averaged template per piece class.
     *
     * Returns true if the captured templates look sane, false if they look
     * color-inverted (see [looksColorInverted]) - almost always because the
     * "Black at bottom" toggle didn't match the screenshot's real
     * orientation. That specific mistake is dangerous because it doesn't
     * just swap black/white: since the queen and king aren't mirror images
     * of each other across the board's center, a 180-degree mislabeling
     * scrambles each template to an almost arbitrary OTHER piece class, not
     * simply the opposite color. The result still "looks" calibrated - no
     * crash, a normal-looking grid - but every move suggested afterwards can
     * be confident, self-consistent nonsense (a pawn "capturing" a queen
     * with promotion on move 2, etc.), which is exactly the class of bug
     * none of the live move-validation checks can catch, since they can only
     * check a move against what recognition itself believes.
     */
    fun captureTemplates(context: Context, screenshot: Bitmap, calib: Calibration): Boolean {
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
        return !looksColorInverted(templates)
    }

    /**
     * On virtually every board skin, white piece glyphs render visibly
     * lighter (on average) than black piece glyphs, regardless of the
     * skin's particular color choices - it's the one thing "white" and
     * "black" pieces reliably agree on. If the captured WHITE_* templates
     * come out darker on average than the BLACK_* templates, that's a
     * strong, skin-independent signal that templates got scrambled during
     * capture (see [captureTemplates]).
     */
    private fun looksColorInverted(templates: Map<PieceClass, FloatArray>): Boolean {
        fun avgLuminance(vararg classes: PieceClass): Float {
            val vecs = classes.mapNotNull { templates[it] }
            if (vecs.isEmpty()) return 0f
            var sum = 0f
            var n = 0
            for (vec in vecs) {
                for (i in 0 until GRID * GRID) { sum += vec[i]; n++ }
            }
            return if (n > 0) sum / n else 0f
        }
        val blackLum = avgLuminance(
            PieceClass.BLACK_PAWN, PieceClass.BLACK_KNIGHT, PieceClass.BLACK_BISHOP,
            PieceClass.BLACK_ROOK, PieceClass.BLACK_QUEEN, PieceClass.BLACK_KING,
        )
        val whiteLum = avgLuminance(
            PieceClass.WHITE_PAWN, PieceClass.WHITE_KNIGHT, PieceClass.WHITE_BISHOP,
            PieceClass.WHITE_ROOK, PieceClass.WHITE_QUEEN, PieceClass.WHITE_KING,
        )
        return blackLum > whiteLum
    }

    /** Recognizes the live board using the stored templates. Returns null if
     * templates haven't been captured yet. */
    fun recognizeBoard(context: Context, screenshot: Bitmap, calib: Calibration): Array<PieceClass>? =
        recognizeBoardDetailed(context, screenshot, calib)?.board

    /**
     * Full recognition result: the 64-square board, plus the set of square
     * indices (same a8->h1 row-major convention as the board array) where
     * the empty-vs-occupied call for that specific square was too close to
     * trust.
     *
     * This matters because [BoardSanity.checkMoveConsistency] can only ever
     * validate an engine move against what WE recognized - if a genuinely
     * occupied square (say a2) gets misread as empty, the move-consistency
     * check sees a self-consistent (but wrong) board and waves an otherwise
     * impossible slide through. Surfacing which squares were a close call on
     * occupancy lets the caller refuse to trust a suggestion that hinges on
     * one of them, instead of silently guessing.
     *
     * See [recognizeBoardDetailed] for how the occupied/empty call itself is
     * made - deliberately NOT "whichever of the ~14 templates is nearest",
     * since on some skins a flat, light-colored piece glyph (a cream-white
     * queen, say) can end up closer in plain feature-distance to a plain
     * light EMPTY square than to its own piece template, which a flat
     * nearest-of-all-14 search has no way to catch.
     */
    data class Recognition(val board: Array<PieceClass>, val uncertainSquares: Set<Int>)

    /** True for a square that is a light square on a real chess board, given
     * its position in our row-major (row 0 = rank 8) board array - i.e. the
     * same light/dark pattern [PieceClass.STANDARD_START] already encodes
     * for ranks 3-6, generalized to every square so occupied ranks 1/2/7/8
     * can be reasoned about too (every square needs a "what color should
     * this be if empty" answer, not just the ones that start empty). */
    private fun isLightSquare(row: Int, col: Int): Boolean = (row + col) % 2 == 0

    // How much closer a cell must be to a PIECE template than to its OWN
    // (correct light/dark) empty template before we'll call it occupied,
    // instead of the ordinary "closest template wins, whatever that means"
    // rule. Biases the empty/occupied call toward "empty" unless a piece
    // convincingly wins - the one-sided version of the exact mistake that
    // kept recurring (a truly empty square losing a close, unbiased contest
    // to a similarly-colored piece template). A real piece's glyph (distinct
    // shape, an outline, usually a different color from the bare square)
    // should clear this bar easily; only a borderline look-alike gets
    // reclassified as empty instead of guessed as a piece.
    private const val OCCUPANCY_MARGIN = 1.25f

    // If the piece/empty distances land within this fraction of the decision
    // boundary above, neither side convincingly won - mark the square
    // uncertain (see [Recognition]) rather than confidently picking one.
    private const val OCCUPANCY_UNCERTAIN_BAND = 0.08f

    fun recognizeBoardDetailed(context: Context, screenshot: Bitmap, calib: Calibration): Recognition? {
        val templates = TemplateStore.load(context) ?: return null
        val board = warpToSquare(screenshot, calib)
        val cells = extractCellFeatures(board, calib.flipped)

        val pieceEntries = templates.entries.filter { !it.key.isEmpty }

        val resultBoard = Array(64) { PieceClass.EMPTY_LIGHT }
        val uncertain = mutableSetOf<Int>()
        for (i in 0 until 64) {
            val row = i / 8
            val col = i % 8
            val ownEmptyClass = if (isLightSquare(row, col)) PieceClass.EMPTY_LIGHT else PieceClass.EMPTY_DARK
            val ownEmptyTemplate = templates[ownEmptyClass]

            val bestPiece = pieceEntries.minByOrNull { (_, vec) -> distance(vec, cells[i]) }
            val pieceDist = bestPiece?.let { distance(it.value, cells[i]) }
            val emptyDist = ownEmptyTemplate?.let { distance(it, cells[i]) }

            if (pieceDist == null || emptyDist == null) {
                // Missing templates entirely (shouldn't happen once
                // calibrated) - fall back to the plain nearest-of-whatever's-
                // available, better than crashing.
                val fallback = templates.entries.minByOrNull { (_, vec) -> distance(vec, cells[i]) }
                resultBoard[i] = fallback?.key ?: ownEmptyClass
                continue
            }

            val boundary = pieceDist * OCCUPANCY_MARGIN
            if (kotlin.math.abs(emptyDist - boundary) < boundary * OCCUPANCY_UNCERTAIN_BAND) {
                uncertain.add(i)
            }
            resultBoard[i] = if (emptyDist > boundary) bestPiece.key else ownEmptyClass
        }
        return Recognition(resultBoard, uncertain)
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
