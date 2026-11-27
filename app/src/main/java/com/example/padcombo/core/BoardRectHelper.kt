package com.example.padcombo.core

/**
 * 統一盤面矩形：辨識（[BoardRecognizer]）與疊加繪圖（overlay）必須使用完全相同的
 * [left] / [top] / [cellSize]，避免取樣格子與畫線格子錯位。
 *
 * 座標為全螢幕像素座標（MediaProjection 截圖與懸浮窗同一座標系）。
 */
data class BoardRect(
    val left: Float,
    val top: Float,
    val cellSize: Float,
) {
    val right: Float get() = left + cellSize * COLS
    val bottom: Float get() = top + cellSize * ROWS

    /** 格心座標，col 0..5 / row 0..4 */
    fun center(col: Int, row: Int): Pair<Float, Float> =
        left + (col + 0.5f) * cellSize to top + (row + 0.5f) * cellSize
}

/**
 * 《パズドラ》直向螢幕 6×5 盤面計算：
 * - 盤面寬度佔滿螢幕，單格正方形，`cellSize = screenWidth / 6f`；
 * - 盤面高度 `boardHeight = cellSize * 5f`；
 * - 預設把整盤往上抬 `topShift` 格（實機舊版貼底會偏下、第 1 排碰不到線）；
 * - `boardTop = screenHeight - boardHeight - bottomInset - bottomOffset - topShift*cellSize`；
 * - `boardBottom = boardTop + 5f * cellSize`；[scale] 整體放大縮小。
 */
object BoardRectHelper {

    fun compute(
        screenWidth: Int,
        screenHeight: Int,
        scale: Float = 1f,
        bottomOffset: Float = 0f,
        bottomInset: Float = 0f,
        topShift: Float = 0.9f,
    ): BoardRect {
        val cell = screenWidth / COLS.toFloat() * scale
        val boardHeight = cell * ROWS
        val top = screenHeight - boardHeight - bottomInset - bottomOffset - topShift * cell
        // 滿寬時 left = 0；scale < 1 時置中保留左右邊距。
        val left = (screenWidth - cell * COLS) / 2f
        return BoardRect(left, top, cell)
    }
}
