package com.example.padcombo.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.example.padcombo.core.Board
import com.example.padcombo.core.BoardRect
import com.example.padcombo.core.Cell
import com.example.padcombo.core.COLS
import com.example.padcombo.core.ROWS

/**
 * 遊戲內懸浮疊加層：細白二次貝塞爾平滑曲線，起點白圓點、終點白箭頭，
 * 中段不畫分段箭頭；折返處自然繞行。盤面矩形 [boardRect] 與辨識共用同一套計算，
 * 格心座標 cx = left + (col + 0.5f) * cellSize、cy = top + (row + 0.5f) * cellSize。
 */
class RouteBoardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** 與 BoardRecognizer 共用的盤面矩形（全螢幕像素座標）。 */
    var boardRect: BoardRect? = null

    /** 是否顯示淡色 6×5 格網輔助線。 */
    var showGrid: Boolean = false

    private var route: List<Cell> = emptyList()

    fun show(board: Board, path: List<Cell>) {
        route = path
        invalidate()
    }

    fun clearRoute() {
        route = emptyList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rect = boardRect ?: return

        if (showGrid) drawGrid(canvas, rect)
        if (route.size < 2) return

        val pts = route.map { (r, c) -> rect.center(c, r) }

        val cell = rect.cellSize
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = cell * 0.085f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            style = Paint.Style.STROKE
            color = Color.WHITE
        }

        val curve = Path()
        curve.moveTo(pts[0].first, pts[0].second)
        for (i in 1 until pts.size - 1) {
            val mx = (pts[i].first + pts[i + 1].first) / 2f
            val my = (pts[i].second + pts[i + 1].second) / 2f
            curve.quadTo(pts[i].first, pts[i].second, mx, my)
        }
        curve.lineTo(pts.last().first, pts.last().second)
        canvas.drawPath(curve, linePaint)

        // 起點白圓點
        val start = pts[0]
        val startPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawCircle(start.first, start.second, cell * 0.14f, startPaint)

        // 終點白箭頭（沿最後一段方向）
        val prev = pts[pts.size - 2]
        val end = pts.last()
        val dx = end.first - prev.first
        val dy = end.second - prev.second
        val len = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (len > 1f) {
            val ux = dx / len
            val uy = dy / len
            val px = -uy
            val py = ux
            val s = cell * 0.18f
            val arrow = Path()
            arrow.moveTo(end.first, end.second)
            arrow.lineTo(
                end.first - ux * s + px * s * 0.6f,
                end.second - uy * s + py * s * 0.6f,
            )
            arrow.lineTo(
                end.first - ux * s - px * s * 0.6f,
                end.second - uy * s - py * s * 0.6f,
            )
            arrow.close()
            canvas.drawPath(arrow, startPaint)
        }
    }

    private fun drawGrid(canvas: Canvas, rect: BoardRect) {
        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x55FFFFFF
            strokeWidth = 1f
            style = Paint.Style.STROKE
        }
        // 垂直線
        for (c in 0..COLS) {
            val x = rect.left + c * rect.cellSize
            canvas.drawLine(x, rect.top, x, rect.bottom, gridPaint)
        }
        // 水平線
        for (r in 0..ROWS) {
            val y = rect.top + r * rect.cellSize
            canvas.drawLine(rect.left, y, rect.right, y, gridPaint)
        }
    }
}
