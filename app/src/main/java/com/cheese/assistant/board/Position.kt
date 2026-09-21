package com.cheese.assistant.board

/**
 * 棋子类型。side: 'w'=红(大写), 'b'=黑(小写)，与 FEN 约定一致。
 */
enum class Piece(val fenChar: Char, val cnName: String, val side: Char) {
    R_KING('K', "帅", 'w'), R_ADVISOR('A', "仕", 'w'), R_BISHOP('B', "相", 'w'),
    R_KNIGHT('N', "马", 'w'), R_ROOK('R', "车", 'w'), R_CANNON('C', "炮", 'w'), R_PAWN('P', "兵", 'w'),
    B_KING('k', "将", 'b'), B_ADVISOR('a', "士", 'b'), B_BISHOP('b', "象", 'b'),
    B_KNIGHT('n', "马", 'b'), B_ROOK('r', "车", 'b'), B_CANNON('c', "炮", 'b'), B_PAWN('p', "卒", 'b');

    val isRed: Boolean get() = side == 'w'

    companion object {
        fun fromFen(c: Char): Piece? = entries.firstOrNull { it.fenChar == c }
    }
}

/**
 * 局面：90 交叉点，row 0 = 黑方底线（FEN 第一行），col 0 = 纵线 a（左侧）。
 * ICCS 坐标: 列 a-i，行 0-9（0 = 黑底，9 = 红底）。
 */
data class Position(
    val board: Array<Piece?>, // 长度 90，index = row * 9 + col
    val redToMove: Boolean,
) {
    fun pieceAt(row: Int, col: Int): Piece? = board[row * 9 + col]
    fun pieceAt(sq: String): Piece? = pieceAt(9 - sq[1].digitToInt(), sq[0] - 'a')

    fun toFen(): String {
        val sb = StringBuilder()
        for (row in 0 until 10) {
            var empty = 0
            for (col in 0 until 9) {
                val p = pieceAt(row, col)
                if (p == null) empty++
                else {
                    if (empty > 0) { sb.append(empty); empty = 0 }
                    sb.append(p.fenChar)
                }
            }
            if (empty > 0) sb.append(empty)
            if (row < 9) sb.append('/')
        }
        return "$sb ${if (redToMove) "w" else "b"} - - 0 1"
    }

    companion object {
        val START = Position(Array(90) { null }, true).apply {
            val back = listOf(
                Piece.B_ROOK, Piece.B_KNIGHT, Piece.B_BISHOP, Piece.B_ADVISOR, Piece.B_KING,
                Piece.B_ADVISOR, Piece.B_BISHOP, Piece.B_KNIGHT, Piece.B_ROOK)
            back.forEachIndexed { col, p -> board[col] = p }
            board[2 * 9 + 1] = Piece.B_CANNON; board[2 * 9 + 7] = Piece.B_CANNON
            for (col in 0..8 step 2) board[3 * 9 + col] = Piece.B_PAWN
            val redBack = listOf(
                Piece.R_ROOK, Piece.R_KNIGHT, Piece.R_BISHOP, Piece.R_ADVISOR, Piece.R_KING,
                Piece.R_ADVISOR, Piece.R_BISHOP, Piece.R_KNIGHT, Piece.R_ROOK)
            redBack.forEachIndexed { col, p -> board[9 * 9 + col] = p }
            board[7 * 9 + 1] = Piece.R_CANNON; board[7 * 9 + 7] = Piece.R_CANNON
            for (col in 0..8 step 2) board[6 * 9 + col] = Piece.R_PAWN
        }
    }
}

/**
 * ICCS 着法转中文纵线格式（如 "h2e2" -> "炮二平五"）。
 * 红方用汉字数字（一到九，从红方右侧数），黑方用阿拉伯数字。
 */
object MoveFormatter {

    private val CN_NUM = listOf("一", "二", "三", "四", "五", "六", "七", "八", "九")

    fun toChinese(fen: String, move: String): String {
        val from = move.substring(0, 2)
        val to = move.substring(2, 4)
        val pos = fenToPosition(fen) ?: return move
        val piece = pos.pieceAt(from) ?: return move

        val red = piece.isRed
        val fileOf = { sq: String -> sq[0] - 'a' }          // 0..8
        val rankOf = { sq: String -> sq[1].digitToInt() }  // 0..9

        // 纵线号：红方视角 file 8 -> "一"，黑方视角 file 0 -> "1"
        fun fileLabel(file: Int): String =
            if (red) CN_NUM[8 - file] else "${file + 1}"

        // 步进数：红方 rank 递减方向（向黑方）为进
        fun stepLabel(sq: String): String {
            val rank = rankOf(sq)
            return if (red) CN_NUM[9 - rank - 1] else "$rank"
        }

        val name = piece.cnName
        val fromFile = fileOf(from)
        val toFile = fileOf(to)

        return when {
            fromFile == toFile -> "$name${fileLabel(fromFile)}进${stepLabel(to)}"
            else -> {
                // 平移或斜走（马象仕）：进/平 + 目标纵线
                val isDiagonal = piece != Piece.R_PAWN && piece != Piece.B_PAWN &&
                        (rankOf(from) != rankOf(to))
                val verb = if (isDiagonal) {
                    val advancing = if (red) rankOf(to) < rankOf(from) else rankOf(to) > rankOf(from)
                    if (advancing) "进" else "退"
                } else "平"
                "$name${fileLabel(fromFile)}$verb${fileLabel(toFile)}"
            }
        }
    }

    private fun fenToPosition(fen: String): Position? {
        val parts = fen.split(" ")
        if (parts.isEmpty()) return null
        val board = arrayOfNulls<Piece>(90)
        var row = 0; var col = 0
        for (c in parts[0]) when (c) {
            '/' -> { row++; col = 0 }
            in '1'..'9' -> col += c.digitToInt()
            else -> board[row * 9 + col] = Piece.fromFen(c).also { col++ }
        }
        return Position(board, parts.getOrNull(1) != "b")
    }
}
