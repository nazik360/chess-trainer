package com.chesstrainer.vision

/**
 * Cheap sanity checks on a recognized board before it's turned into a FEN and
 * handed to Stockfish. Recognition is template matching against a fixed board
 * skin (see BoardRecognizer) - it's reliable once calibrated well, but a
 * fresh/imperfect calibration, a screenshot taken mid-animation, or a board
 * skin that doesn't match the captured templates can produce nonsense (e.g.
 * zero or two white kings). Stockfish's own FEN parsing assumes a legal-ish
 * position and is built with NDEBUG (asserts compiled out) for speed, so
 * feeding it a sufficiently broken position is a real, if rare, way to make
 * the engine process crash outright rather than just answer badly - which
 * is confusing to debug from the UI side (it just looks like "the engine
 * died"). Catching the obviously-impossible cases here turns that into a
 * clear, actionable message instead.
 */
object BoardSanity {

    /** Returns null if the board looks plausible enough to analyze, or a
     * human-readable reason why it doesn't. */
    fun checkPlausible(board: Array<PieceClass>): String? {
        require(board.size == 64)

        val counts = HashMap<PieceClass, Int>()
        for (cls in board) counts[cls] = (counts[cls] ?: 0) + 1

        val whiteKings = counts[PieceClass.WHITE_KING] ?: 0
        val blackKings = counts[PieceClass.BLACK_KING] ?: 0
        if (whiteKings != 1 || blackKings != 1) {
            return "Recognized position has $whiteKings white king(s) and $blackKings black king(s) " +
                "(expected exactly 1 each) - recognition is likely misreading this board skin. " +
                "Try recalibrating (corners + piece templates) against a clear, static screenshot."
        }

        val whitePawns = counts[PieceClass.WHITE_PAWN] ?: 0
        val blackPawns = counts[PieceClass.BLACK_PAWN] ?: 0
        if (whitePawns > 8 || blackPawns > 8) {
            return "Recognized position has $whitePawns white pawn(s) / $blackPawns black pawn(s) " +
                "(max 8 each) - recognition is likely misreading this board skin. Try recalibrating."
        }

        // Pawns on the back ranks are impossible in any legal chess position.
        for (col in 0 until 8) {
            val top = board[col]        // row 0 = rank 8
            val bottom = board[56 + col] // row 7 = rank 1
            if (top == PieceClass.WHITE_PAWN || top == PieceClass.BLACK_PAWN ||
                bottom == PieceClass.WHITE_PAWN || bottom == PieceClass.BLACK_PAWN
            ) {
                return "Recognized position has a pawn on the back rank, which is impossible - " +
                    "recognition is likely misreading this board skin. Try recalibrating."
            }
        }

        return null
    }

    /**
     * Cross-checks the engine's suggested move against the SAME recognized
     * board we sent it, independent of Stockfish's own (necessarily-legal
     * relative to whatever FEN it was given) move generation. Stockfish can
     * never emit a move that's illegal for the position it was handed - so if
     * the suggested move looks physically impossible against what the board
     * actually shows (a sliding piece gliding through an occupied square, a
     * piece that belongs to the side NOT currently moving, a piece landing on
     * one of its own side's squares), that is conclusive proof the FEN we
     * built didn't match the real board - i.e. recognition drifted on at
     * least one relevant square, even though cruder checks like
     * [checkPlausible] didn't catch it (a uniform one-column/row misread, or
     * one file quietly read as all-empty from a calibration edge sitting just
     * outside the real board, both preserve piece counts while still
     * producing exactly this kind of impossible move).
     *
     * Returns null if the move looks consistent with [board], or a reason why
     * it doesn't.
     */
    fun checkMoveConsistency(
        board: Array<PieceClass>,
        fromSquare: String,
        toSquare: String,
        whiteToMove: Boolean,
        uncertainSquares: Set<Int> = emptySet(),
    ): String? {
        require(board.size == 64)

        fun squareIndex(sq: String): Int {
            val file = sq[0] - 'a'
            val rank = sq[1] - '1'
            return (7 - rank) * 8 + file
        }

        val fromIdx = squareIndex(fromSquare)
        val toIdx = squareIndex(toSquare)
        val mover = board[fromIdx]
        val target = board[toIdx]

        // A near-tie match (see BoardRecognizer.Recognition) means we can't
        // trust what this square actually shows - including whether it's
        // empty. Refuse the move rather than reason about possibly-wrong
        // data; this is what catches an occupied square that lost a close
        // call to "empty" and let an otherwise-impossible slide look legal
        // against our own (wrong) board.
        if (fromIdx in uncertainSquares || toIdx in uncertainSquares) {
            return "Recognition wasn't confident about $fromSquare or $toSquare (two piece templates matched " +
                "almost equally well there) - too risky to trust this move. Try recalibrating with a clear, " +
                "well-lit screenshot."
        }

        if (mover.isEmpty) {
            return "Engine's move starts from $fromSquare, but the recognized board has no piece there - " +
                "recognition and the engine's position have drifted apart. Try recalibrating."
        }

        val moverIsWhite = mover.fenChar?.isUpperCase() == true
        if (moverIsWhite != whiteToMove) {
            return "Engine's move ($fromSquare-$toSquare) moves a ${if (moverIsWhite) "white" else "black"} piece, " +
                "but it's set to ${if (whiteToMove) "White" else "Black"} to move - recognition likely misread " +
                "a piece's color, or the side-to-move toggle doesn't match the real board. Check the toggle " +
                "and/or try recalibrating."
        }

        if (!target.isEmpty) {
            val targetIsWhite = target.fenChar?.isUpperCase() == true
            if (targetIsWhite == moverIsWhite) {
                return "Engine's move ($fromSquare-$toSquare) would capture a piece of the SAME color on " +
                    "$toSquare, which is impossible - recognition has likely misread this board. Try recalibrating."
            }
        }

        // Sliding pieces (rook/bishop/queen): the real board must have a clear
        // path - a piece can never be recognized as legally "jumping" another
        // for Stockfish to have offered this move, so an occupied square in
        // between is conclusive evidence of a misread, not a quirky engine choice.
        val isSlider = mover == PieceClass.WHITE_ROOK || mover == PieceClass.BLACK_ROOK ||
            mover == PieceClass.WHITE_BISHOP || mover == PieceClass.BLACK_BISHOP ||
            mover == PieceClass.WHITE_QUEEN || mover == PieceClass.BLACK_QUEEN
        if (isSlider) {
            val fromRow = fromIdx / 8
            val fromCol = fromIdx % 8
            val toRow = toIdx / 8
            val toCol = toIdx % 8
            val dRow = Integer.signum(toRow - fromRow)
            val dCol = Integer.signum(toCol - fromCol)
            var row = fromRow + dRow
            var col = fromCol + dCol
            while (row != toRow || col != toCol) {
                if (row !in 0..7 || col !in 0..7) break // shouldn't happen for a real sliding move
                val idx = row * 8 + col
                if (idx in uncertainSquares) {
                    return "Recognition wasn't confident about a square on the $fromSquare-$toSquare path " +
                        "(two templates matched almost equally well there) - too risky to trust this slide. " +
                        "Try recalibrating with a clear, well-lit screenshot."
                }
                if (!board[idx].isEmpty) {
                    return "Engine's move ($fromSquare-$toSquare) would slide straight through an occupied " +
                        "square - impossible for a real position. Recognition has misread at least one square " +
                        "on that line (often an edge file/rank read as empty from an imprecise calibration). " +
                        "Try recalibrating with the corner grid lines snapped exactly to the board's edges."
                }
                row += dRow
                col += dCol
            }
        }

        return null
    }
}
