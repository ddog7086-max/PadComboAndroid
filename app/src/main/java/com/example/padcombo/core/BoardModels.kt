package com.example.padcombo.core

const val COLS = 6
const val ROWS = 5

/** board[row][col]，5 行 × 6 列 */
typealias Board = Array<Array<Orb>>

/** 格座標 (row, col) */
typealias Cell = Pair<Int, Int>

data class ClearResult(
    val clearedCells: List<Cell>,
    /** 連鎖序號，從 1 開始 */
    val combo: Int,
    /** 消除、下落、補珠之前的盤面（統計顏色用） */
    val beforeBoard: Board,
    /** 消除、下落、補珠之後的盤面 */
    val newBoard: Board,
)

data class ChainResolveOutput(
    val totalCombo: Int,
    val rounds: List<ClearResult>,
    val finalBoard: Board,
)
