package com.example.padcombo.core

import kotlin.random.Random

object BoardEngine {

    fun cloneBoard(board: Board): Board = board.map { it.copyOf() }.toTypedArray()

    fun randomBoard(rng: Random = Random.Default): Board =
        Array(ROWS) { Array(COLS) { Orb.ALL[rng.nextInt(Orb.ALL.size)] } }

    /**
     * 真實轉珠拖曳軌跡平移（パズドラ / PuzzCombo 標準邏輯）：
     * 起點珠子被手持（heldOrb），沿路徑每步把下一格珠子覆寫當前格，
     * 走完整條軌跡後終點填入手持珠；其餘珠子依次後退一格。
     * 不是逐段兩格交換（swap）。
     */
    fun applyDragPath(board: Board, path: List<Cell>): Board {
        val b = cloneBoard(board)
        if (path.size <= 1) return b

        val heldOrb = b[path.first().first][path.first().second]
        for (i in 0 until path.size - 1) {
            val (curR, curC) = path[i]
            val (nextR, nextC) = path[i + 1]
            b[curR][curC] = b[nextR][nextC]
        }
        val (endR, endC) = path.last()
        b[endR][endC] = heldOrb
        return b
    }

    private fun findClears(board: Board): List<Cell> {
        val cleared = HashSet<String>()

        // 橫向
        for (r in 0 until ROWS) {
            var run = 1
            for (c in 1 until COLS) {
                if (board[r][c] == board[r][c - 1]) {
                    run++
                } else {
                    if (run >= 3) for (k in c - run until c) cleared.add("$r,$k")
                    run = 1
                }
            }
            if (run >= 3) for (k in COLS - run until COLS) cleared.add("$r,$k")
        }

        // 縱向
        for (c in 0 until COLS) {
            var run = 1
            for (r in 1 until ROWS) {
                if (board[r][c] == board[r - 1][c]) {
                    run++
                } else {
                    if (run >= 3) for (k in r - run until r) cleared.add("$k,$c")
                    run = 1
                }
            }
            if (run >= 3) for (k in ROWS - run until ROWS) cleared.add("$k,$c")
        }

        return cleared.map {
            val (r, c) = it.split(",").map(String::toInt)
            Cell(r, c)
        }
    }

    private fun applyGravityAndRefill(board: Board, cleared: List<Cell>, rng: Random): Board {
        val b = cloneBoard(board)
        val clearSet = cleared.map { "${it.first},${it.second}" }.toSet()

        for (c in 0 until COLS) {
            val col = mutableListOf<Orb>()
            for (r in ROWS - 1 downTo 0) {
                if ("$r,$c" !in clearSet) col.add(b[r][c])
            }
            while (col.size < ROWS) col.add(Orb.ALL[rng.nextInt(Orb.ALL.size)])
            for (r in 0 until ROWS) {
                b[r][c] = col[ROWS - 1 - r]
            }
        }
        return b
    }

    /**
     * 連鎖結算：反覆「消除 → 重力下落 → 頂部補珠 → 再判定」，
     * 每成功一輪 combo +1，直到無可消除。
     */
    fun resolveChain(board: Board, rng: Random = Random.Default): ChainResolveOutput {
        var current = cloneBoard(board)
        val rounds = mutableListOf<ClearResult>()
        var totalCombo = 0

        while (true) {
            val cleared = findClears(current)
            if (cleared.isEmpty()) break
            totalCombo++
            val beforeBoard = cloneBoard(current)
            val nextBoard = applyGravityAndRefill(current, cleared, rng)
            rounds.add(ClearResult(cleared, totalCombo, beforeBoard, nextBoard))
            current = nextBoard
        }

        return ChainResolveOutput(totalCombo, rounds, current)
    }
}
