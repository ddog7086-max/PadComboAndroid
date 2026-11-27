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
import kotlin.math.max
import kotlin.math.min

/**
 * 遊戲內懸浮疊加層：細白二次貝塞爾平滑曲線，起點白圓點、終點白箭頭。
 * 盤面矩形 [boardRect] 與辨識共用同一套計算。
 *
 * 路徑層次錯開：重複經過的格子沿「垂直於行進方向」左右偏移，避免折返/重疊
 * 時線條壓在同一條上；偏移量 [laneOffsetPx] 可調（約 5～8px）。
 */
class RouteBoardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** 與 BoardRecognizer 共用的盤面矩形（全螢幕像素座標）。 */
    var boardRect: BoardRect? = null

    /** 是否顯示淡色 6×5 格網輔助線。 */
    var showGrid: Boolean = false

    /** 重複路段垂直錯開偏移（px），約 5～8px。 */
    var laneOffsetPx: Float = 6f

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

        val pts = offsetRepeatedCells(route, rect)

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

    /**
     * 統計每格造訪次序；重複造訪的頂點沿垂直於當地切線方向左右錯開。
     * 第一次造訪不偏移，之後依造訪次數在正負側交替展開。
     */
    private fun offsetRepeatedCells(
        route: List<Cell>,
        rect: BoardRect,
    ): List<Pair<Float, Float>> {
        val base = route.map { (r, c) -> rect.center(c, r) }
        if (base.size < 2) return base

        val seen = HashMap<Cell, Int>()
        val occurrence = IntArray(route.size)
        for (i in route.indices) {
            val k = seen[route[i]] ?: 0
            occurrence[i] = k
            seen[route[i]] = k + 1
        }

        val out = base.toMutableList()
        for (i in route.indices) {
            val repeat = occurrence[i]
            if (repeat == 0) continue

            val prev = base[max(0, i - 1)]
            val next = base[min(base.size - 1, i + 1)]
            var tx = next.first - prev.first
            var ty = next.second - prev.second
            val tangentLen = Math.hypot(tx.toDouble(), ty.toDouble()).toFloat()
            if (tangentLen < 1f) continue
            tx /= tangentLen
            ty /= tangentLen

            // 垂直於行進方向
            val nx = -ty
            val ny = tx
            val side = if (repeat % 2 == 1) 1f else -1f
            val magnitude = laneOffsetPx * min(repeat, 2)

            val (bx, by) = base[i]
            out[i] = (bx + nx * side * magnitude) to (by + ny * side * magnitude)
        }
        return out
    }

    private fun drawGrid(canvas: Canvas, rect: BoardRect) {
        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x55FFFFFF
            strokeWidth = 1f
            style = Paint.Style.STROKE
        }
        for (c in 0..COLS) {
            val x = rect.left + c * rect.cellSize
            canvas.drawLine(x, rect.top, x, rect.bottom, gridPaint)
        }
        for (r in 0..ROWS) {
            val y = rect.top + r * rect.cellSize
            canvas.drawLine(rect.left, y, rect.right, y, gridPaint)
        }
    }
}
