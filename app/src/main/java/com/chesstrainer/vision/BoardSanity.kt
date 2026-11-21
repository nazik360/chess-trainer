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
}
