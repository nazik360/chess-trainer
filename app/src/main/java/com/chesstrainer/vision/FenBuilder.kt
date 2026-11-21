package com.chesstrainer.vision

/**
 * Builds a FEN string from a recognized 64-square board (a8->h1 rank-major,
 * as produced by BoardRecognizer). Side-to-move isn't something we can read
 * reliably from pixels alone (no clock-highlight heuristic is robust across
 * apps), so it's supplied by the user via a quick toggle in the UI/overlay.
 *
 * Castling rights and en-passant are approximated: castling is granted for a
 * side only if that side's king and the relevant rook are still sitting on
 * their home squares (good enough for a training aid; it can occasionally
 * offer a castle that's technically no longer legal if a king/rook moved and
 * moved back - Stockfish will just treat it as illegal and pick another move
 * in that rare case). En-passant is never guessed and always omitted.
 */
object FenBuilder {

    fun build(board: Array<PieceClass>, whiteToMove: Boolean): String {
        require(board.size == 64) { "board must have 64 squares" }

        val rows = StringBuilder()
        for (row in 0 until 8) {
            var emptyRun = 0
            for (col in 0 until 8) {
                val cls = board[row * 8 + col]
                if (cls.isEmpty) {
                    emptyRun++
                } else {
                    if (emptyRun > 0) { rows.append(emptyRun); emptyRun = 0 }
                    rows.append(cls.fenChar)
                }
            }
            if (emptyRun > 0) rows.append(emptyRun)
            if (row != 7) rows.append('/')
        }

        fun at(square: String): PieceClass {
            val file = square[0] - 'a'
            val rank = square[1] - '1'
            val row = 7 - rank
            return board[row * 8 + file]
        }

        val castling = StringBuilder()
        if (at("e1") == PieceClass.WHITE_KING && at("h1") == PieceClass.WHITE_ROOK) castling.append('K')
        if (at("e1") == PieceClass.WHITE_KING && at("a1") == PieceClass.WHITE_ROOK) castling.append('Q')
        if (at("e8") == PieceClass.BLACK_KING && at("h8") == PieceClass.BLACK_ROOK) castling.append('k')
        if (at("e8") == PieceClass.BLACK_KING && at("a8") == PieceClass.BLACK_ROOK) castling.append('q')
        if (castling.isEmpty()) castling.append('-')

        val side = if (whiteToMove) "w" else "b"
        // halfmove clock / fullmove number aren't recoverable from a single
        // screenshot; 0 and 1 are harmless defaults for move search purposes.
        return "$rows $side $castling - 0 1"
    }
}
