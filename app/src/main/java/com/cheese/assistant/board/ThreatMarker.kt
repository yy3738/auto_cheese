package com.cheese.assistant.board

/**
 * 打吃/将军判定：模拟走推荐着法后，扫描对方棋子是否处于我方攻击范围。
 * 定义（用户确认）：能吃到对方任意子 = 打吃；吃王 = 将军（优先显示）。
 */
object ThreatMarker {

    /** 着法威胁级别 */
    enum class Level { NONE, CAPTURE, CHECK }

    /**
     * 模拟走 move（ICCS 如 "h2e2"）后判定威胁。
     * 威胁只归属刚走的那颗子（否则开局炮本来就隔山吃马，每步都成打吃）；
     * 将军按全盘检测（含发现式将军）。
     * 返回 NONE / CAPTURE（该子吃掉或下一步可吃子）/ CHECK（将军）。
     */
    fun of(fen: String, move: String): Level {
        if (move.length < 4) return Level.NONE
        val pos = Position.fromFen(fen) ?: return Level.NONE
        val fromIdx = squareIndex(move.substring(0, 2)) ?: return Level.NONE
        val toIdx = squareIndex(move.substring(2, 4)) ?: return Level.NONE
        val mover = pos.board[fromIdx] ?: return Level.NONE
        if (mover.isRed != pos.redToMove) return Level.NONE
        if (toIdx !in pseudoMoves(pos, fromIdx)) return Level.NONE
        val captured = pos.board[toIdx]
        val board = pos.board.copyOf()
        board[fromIdx] = null
        board[toIdx] = mover
        // 直接吃王 = 将军（吃掉后棋盘无王，isKingAttacked 检测不到）
        if (captured == (if (mover.isRed) Piece.B_KING else Piece.R_KING)) return Level.CHECK
        val after = Position(board, !pos.redToMove)
        // 再判走后将军（含发现式）
        if (isKingAttacked(after, mover.isRed)) return Level.CHECK
        val threatensCapture = pseudoMoves(after, toIdx).any { j ->
            val target = after.board[j]
            target != null && target.isRed != mover.isRed
        }
        val justCaptured = captured != null && captured.isRed != mover.isRed
        return if (justCaptured || threatensCapture) Level.CAPTURE else Level.NONE
    }

    private fun squareIndex(sq: String): Int? {
        if (sq.length < 2 || !sq[1].isDigit()) return null
        val file = sq[0] - 'a'
        val rank = sq[1].digitToInt()
        if (file !in 0..8 || rank !in 0..9) return null
        // FEN row0 = 黑方底线，ICCS rank0 = 黑方底线，一致：row = 9 - rank
        return (9 - rank) * 9 + file
    }

    /** side 走完后，对方王是否被攻击（将军） */
    private fun isKingAttacked(pos: Position, byRed: Boolean): Boolean {
        val king = if (byRed) Piece.B_KING else Piece.R_KING
        val kingIdx = pos.board.indexOfFirst { it == king }
        if (kingIdx < 0) return false
        for (i in 0 until 90) {
            val p = pos.board[i] ?: continue
            if (p.isRed != byRed) continue
            if (pseudoMoves(pos, i).contains(kingIdx)) return true
        }
        return false
    }

    /** 伪合法走法：不检查走后己方王是否被将（打吃判定够用，简单为王） */
    private fun pseudoMoves(pos: Position, from: Int): List<Int> {
        val moves = ArrayList<Int>()
        val p = pos.board[from] ?: return moves
        val r = from / 9; val c = from % 9
        val red = p.isRed

        fun push(row: Int, col: Int) {
            if (row !in 0..9 || col !in 0..8) return
            val target = pos.board[row * 9 + col]
            if (target == null || target.isRed != red) moves.add(row * 9 + col)
        }
        fun ray(dr: Int, dc: Int) {
            var row = r + dr; var col = c + dc
            while (row in 0..9 && col in 0..8) {
                val target = pos.board[row * 9 + col]
                if (target == null) {
                    moves.add(row * 9 + col)
                } else {
                    if (target.isRed != red) moves.add(row * 9 + col)
                    return
                }
                row += dr; col += dc
            }
        }

        when (p) {
            Piece.R_ROOK, Piece.B_ROOK -> {
                ray(1, 0); ray(-1, 0); ray(0, 1); ray(0, -1)
            }
            Piece.R_CANNON, Piece.B_CANNON -> {
                // 炮：平移越空格，隔一子后第一个敌子可吃
                for ((dr, dc) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                    var row = r + dr; var col = c + dc
                    var jumped = false
                    while (row in 0..9 && col in 0..8) {
                        val target = pos.board[row * 9 + col]
                        if (!jumped) {
                            if (target == null) moves.add(row * 9 + col) else jumped = true
                        } else if (target != null) {
                            if (target.isRed != red) moves.add(row * 9 + col)
                            break
                        }
                        row += dr; col += dc
                    }
                }
            }
            Piece.R_KNIGHT, Piece.B_KNIGHT -> {
                val leg = arrayOf( // 马腿方向 -> (legDelta, targetDelta)
                    intArrayOf(1, 2), intArrayOf(-1, 2), intArrayOf(1, -2), intArrayOf(-1, -2),
                    intArrayOf(2, 1), intArrayOf(2, -1), intArrayOf(-2, 1), intArrayOf(-2, -1))
                for (t in leg) {
                    val dr = t[0]; val dc = t[1]
                    // 马腿：先直一步再拐。走 (dr,dc) 需 (r + dr/2, c + dc/2) 无子
                    val blockRow = r + dr / 2
                    val blockCol = c + dc / 2
                    if (blockRow !in 0..9 || blockCol !in 0..8) continue
                    if (pos.board[blockRow * 9 + blockCol] != null) continue
                    push(r + dr, c + dc)
                }
            }
            Piece.R_KING, Piece.B_KING -> {
                // 九宫内直走一步
                val palaceRows = if (red) 7..9 else 0..2
                if (r in palaceRows && c in 3..5) {
                    push(r + 1, c); push(r - 1, c); push(r, c + 1); push(r, c - 1)
                }
            }
            Piece.R_ADVISOR, Piece.B_ADVISOR -> {
                val palaceRows = if (red) 7..9 else 0..2
                if (r in palaceRows && c in 3..5) {
                    push(r + 1, c + 1); push(r + 1, c - 1); push(r - 1, c + 1); push(r - 1, c - 1)
                }
            }
            Piece.R_BISHOP, Piece.B_BISHOP -> {
                // 相/象：田字，象眼无子；不能过河
                val homeRows = if (red) 5..9 else 0..4
                for ((dr, dc) in listOf(2 to 2, 2 to -2, -2 to 2, -2 to -2)) {
                    val eyeRow = r + dr / 2; val eyeCol = c + dc / 2
                    if (eyeRow in 0..9 && eyeCol in 0..8 &&
                        pos.board[eyeRow * 9 + eyeCol] == null &&
                        (r + dr) in homeRows) push(r + dr, c + dc)
                }
            }
            Piece.R_PAWN -> {
                if (r <= 4) { // 过河后可横走
                    push(r, c + 1); push(r, c - 1)
                }
                push(r - 1, c) // 红兵向上（row 减小）
            }
            Piece.B_PAWN -> {
                if (r >= 5) {
                    push(r, c + 1); push(r, c - 1)
                }
                push(r + 1, c)
            }
        }
        return moves
    }
}
