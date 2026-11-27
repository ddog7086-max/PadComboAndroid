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
 * 路徑層次錯開：重複路段依線段級法向偏移平行分道，去回程落在不同側；
 * 車道偏移 [laneFraction] 以格寬為基準（單層 0.18、封頂 0.28）。
 */
class RouteBoardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** 與 BoardRecognizer 共用的盤面矩形（全螢幕像素座標）。 */
    var boardRect: BoardRect? = null

    /** 是否顯示淡色 6×5 格網輔助線。 */
    var showGrid: Boolean = false

    /** 重複路段平行錯開比例（相對格寬），單層 0.30，封頂 0.36。 */
    var laneFraction: Float = 0.30f

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

        val density = resources.displayMetrics.density
        val cell = rect.cellSize
        val whiteWidth = 2.5f * density
        val blackWidth = whiteWidth * 1.8f

        val blackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = blackWidth
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            style = Paint.Style.STROKE
            color = Color.BLACK
        }
        val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = whiteWidth
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
        // 雙層描邊：先黑框底層，再疊細白線。
        canvas.drawPath(curve, blackPaint)
        canvas.drawPath(curve, whitePaint)

        // 起點白圓點（先畫較大黑底再疊白點）
        val start = pts[0]
        val dotR = cell * 0.14f
        val blackFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }
        val whiteFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawCircle(start.first, start.second, dotR * 1.5f, blackFill)
        canvas.drawCircle(start.first, start.second, dotR, whiteFill)

        // 終點白箭頭（先畫較大黑底三角再疊白三角，沿最後一段方向）
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

            fun arrowPath(scale: Float): Path {
                val sp = s * scale
                val a = Path()
                a.moveTo(end.first, end.second)
                a.lineTo(
                    end.first - ux * sp + px * sp * 0.6f,
                    end.second - uy * sp + py * sp * 0.6f,
                )
                a.lineTo(
                    end.first - ux * sp - px * sp * 0.6f,
                    end.second - uy * sp - py * sp * 0.6f,
                )
                a.close()
                return a
            }
            canvas.drawPath(arrowPath(1.4f), blackFill)
            canvas.drawPath(arrowPath(1.0f), whiteFill)
        }
    }

    /**
     * 線段級車道錯開：對每一步有向線段 `P_i -> P_{i+1}` 計算切線與法向量，
     * 依「第幾次經過」給階梯式法向偏移，去程/回程交錯落在法向量兩側，
     * 再把相鄰線段在共用頂點處的偏移取平均，維持平滑貝塞爾連接。
     */
    private fun offsetRepeatedCells(
        route: List<Cell>,
        rect: BoardRect,
    ): List<Pair<Float, Float>> {
        val base = route.map { (r, c) -> rect.center(c, r) }
        if (base.size < 2) return base

        // 每格造訪次序：第一次 = 0，第二次 = 1，依此類推。
        val seen = HashMap<Cell, Int>()
        val occurrence = IntArray(route.size)
        for (i in route.indices) {
            val k = seen[route[i]] ?: 0
            occurrence[i] = k
            seen[route[i]] = k + 1
        }

        // 每條線段的法向偏移向量
        val segOffset = Array(base.size - 1) { Pair(0f, 0f) }
        for (i in 0 until base.size - 1) {
            var tx = base[i + 1].first - base[i].first
            var ty = base[i + 1].second - base[i].second
            val tangentLen = Math.hypot(tx.toDouble(), ty.toDouble()).toFloat()
            if (tangentLen < 1f) continue
            tx /= tangentLen
            ty /= tangentLen
            val nx = -ty
            val ny = tx

            // 以線段兩端的最大造訪次數作為該段重複級別。
            val repeat = max(occurrence[i], occurrence[i + 1])
            if (repeat == 0) continue

            // 奇數次往 + 側、偶數次往 - 側，去回程分開；幅度隨次數階梯放大。
            val side = if (repeat % 2 == 1) 1f else -1f
            // 單層 0.30 格寬，雙層封頂 0.36 格寬，偏移不超出格邊太多。
            val frac = (laneFraction * min(repeat, 2)).coerceAtMost(0.36f)
            val magnitude = rect.cellSize * frac
            segOffset[i] = Pair(nx * side * magnitude, ny * side * magnitude)
        }

        // 頂點偏移 = 進入線段與離開線段偏移的平均，避免接縫處折線。
        val out = mutableListOf<Pair<Float, Float>>()
        for (i in base.indices) {
            val inc = if (i > 0) segOffset[i - 1] else null
            val outg = if (i < segOffset.size) segOffset[i] else null
            val ox = listOfNotNull(inc?.first, outg?.first).averageOrZero()
            val oy = listOfNotNull(inc?.second, outg?.second).averageOrZero()
            out.add(base[i].first + ox to base[i].second + oy)
        }
        return out
    }

    private fun List<Float>.averageOrZero(): Float =
        if (isEmpty()) 0f else sum() / size

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
