package com.chesstrainer.vision

/** The 12 piece/color combinations, plus the two empty-square "colors" (we
 * template-match empty light and empty dark squares separately because they
 * look nothing alike on most board skins). */
enum class PieceClass(val fenChar: Char?) {
    WHITE_PAWN('P'), WHITE_KNIGHT('N'), WHITE_BISHOP('B'), WHITE_ROOK('R'), WHITE_QUEEN('Q'), WHITE_KING('K'),
    BLACK_PAWN('p'), BLACK_KNIGHT('n'), BLACK_BISHOP('b'), BLACK_ROOK('r'), BLACK_QUEEN('q'), BLACK_KING('k'),
    EMPTY_LIGHT(null), EMPTY_DARK(null);

    val isEmpty get() = fenChar == null

    companion object {
        /** Standard chess starting position, used to auto-label calibration
         * templates: index 0 = a8 ... index 63 = h1 (rank-major, as FEN reads). */
        val STANDARD_START: Array<PieceClass> = arrayOf(
            BLACK_ROOK, BLACK_KNIGHT, BLACK_BISHOP, BLACK_QUEEN, BLACK_KING, BLACK_BISHOP, BLACK_KNIGHT, BLACK_ROOK,
            BLACK_PAWN, BLACK_PAWN, BLACK_PAWN, BLACK_PAWN, BLACK_PAWN, BLACK_PAWN, BLACK_PAWN, BLACK_PAWN,
            EMPTY_LIGHT, EMPTY_DARK, EMPTY_LIGHT, EMPTY_DARK, EMPTY_LIGHT, EMPTY_DARK, EMPTY_LIGHT, EMPTY_DARK,
            EMPTY_DARK, EMPTY_LIGHT, EMPTY_DARK, EMPTY_LIGHT, EMPTY_DARK, EMPTY_LIGHT, EMPTY_DARK, EMPTY_LIGHT,
            EMPTY_LIGHT, EMPTY_DARK, EMPTY_LIGHT, EMPTY_DARK, EMPTY_LIGHT, EMPTY_DARK, EMPTY_LIGHT, EMPTY_DARK,
            EMPTY_DARK, EMPTY_LIGHT, EMPTY_DARK, EMPTY_LIGHT, EMPTY_DARK, EMPTY_LIGHT, EMPTY_DARK, EMPTY_LIGHT,
            WHITE_PAWN, WHITE_PAWN, WHITE_PAWN, WHITE_PAWN, WHITE_PAWN, WHITE_PAWN, WHITE_PAWN, WHITE_PAWN,
            WHITE_ROOK, WHITE_KNIGHT, WHITE_BISHOP, WHITE_QUEEN, WHITE_KING, WHITE_BISHOP, WHITE_KNIGHT, WHITE_ROOK,
        )
    }
}
