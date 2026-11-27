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
import kotlin.math.sqrt

/**
 * 遊戲內懸浮疊加層。
 *
 * 繪製規則：
 * - 基礎格點嚴格對齊 6×5 珠子圓心；單次經過的邊段偏移為 0，橫平豎直穿過珠心。
 * - 只有重訪／折返的無向邊段，沿垂直方向對稱分配到中心線兩側（平行車道）。
 * - 轉彎處用統一半徑的平滑小圓角，不做大弧度亂扭。
 * - 細白線 + 黑色外描邊雙層；起點小圓點、終點小箭頭皆帶細黑邊。
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

        val density = resources.displayMetrics.density
        val pts = buildOffsetPoints(route, rect, density)

        val whiteWidth = 2.2f * density
        val blackWidth = 4.2f * density

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

        val cornerRadius = rect.cellSize * 0.22f
        val curve = buildRoundedPath(pts, cornerRadius)
        canvas.drawPath(curve, blackPaint)
        canvas.drawPath(curve, whitePaint)

        // 起點小圓點：黑底描邊 + 白點。
        val start = pts[0]
        val dotR = 5.5f * density
        val outline = 1.5f * density
        val blackFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }
        val whiteFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawCircle(start.first, start.second, dotR + outline, blackFill)
        canvas.drawCircle(start.first, start.second, dotR, whiteFill)

        // 終點小箭頭：黑底三角 + 白三角，沿最後一段方向。
        val prev = pts[pts.size - 2]
        val end = pts.last()
        val dx = end.first - prev.first
        val dy = end.second - prev.second
        val len = sqrt(dx * dx + dy * dy)
        if (len > 1f) {
            val ux = dx / len
            val uy = dy / len
            val nx = -uy
            val ny = ux
            val s = rect.cellSize * 0.16f

            fun arrowPath(scale: Float): Path {
                val sp = s * scale
                val a = Path()
                a.moveTo(end.first, end.second)
                a.lineTo(end.first - ux * sp + nx * sp * 0.6f,
                    end.second - uy * sp + ny * sp * 0.6f)
                a.lineTo(end.first - ux * sp - nx * sp * 0.6f,
                    end.second - uy * sp - ny * sp * 0.6f)
                a.close()
                return a
            }
            canvas.drawPath(arrowPath(1.35f), blackFill)
            canvas.drawPath(arrowPath(1.0f), whiteFill)
        }
    }

    /**
     * 基礎格點對齊珠心；單次邊段偏移 0，重複邊段依序號對稱分到兩側。
     * 所有座標最後夾回盤面內框，避免掉出邊界。
     */
    private fun buildOffsetPoints(
        route: List<Cell>,
        rect: BoardRect,
        density: Float,
    ): List<Pair<Float, Float>> {
        val base = route.map { (r, c) -> rect.center(c, r) }
        if (base.size < 2) return base

        // 統計每個無向邊被經過的次數。
        val edgeKey = { a: Cell, b: Cell ->
            val la = "${a.first},${a.second}"
            val lb = "${b.first},${b.second}"
            if (la <= lb) "$la|$lb" else "$lb|$la"
        }
        val edgeCount = HashMap<String, Int>()
        for (i in 0 until base.size - 1) {
            val k = edgeKey(route[i], route[i + 1])
            edgeCount[k] = (edgeCount[k] ?: 0) + 1
        }
        val edgeSeen = HashMap<String, Int>()

        // 每條線段的法向對稱偏移向量。
        val segOffset = Array(base.size - 1) { Pair(0f, 0f) }
        for (i in 0 until base.size - 1) {
            var tx = base[i + 1].first - base[i].first
            var ty = base[i + 1].second - base[i].second
            val tangentLen = sqrt(tx * tx + ty * ty)
            if (tangentLen < 1f) continue
            tx /= tangentLen
            ty /= tangentLen
            val nx = -ty
            val ny = tx

            val k = edgeKey(route[i], route[i + 1])
            val repeat = edgeCount[k] ?: 1
            if (repeat < 2) continue // 單次路段：偏移 0，筆直穿珠心。

            val lane = edgeSeen[k] ?: 0
            edgeSeen[k] = lane + 1

            // 對稱位置：N=2 → ±9dp；N=3 → -12dp, 0, +12dp；更多條均勻展開。
            val spread = if (repeat == 2) 9f else 12f
            val half = (repeat - 1) / 2.0
            val step = if (repeat == 1) 0f else (2f * spread / (repeat - 1))
            val position = (lane - half) * step * density

            segOffset[i] = Pair(nx * position, ny * position)
        }

        // 內框邊距 0.25 格，任何偏移都不允許掉出盤面。
        val inset = rect.cellSize * 0.25f
        val minX = rect.left + inset
        val maxX = rect.right - inset
        val minY = rect.top + inset
        val maxY = rect.bottom - inset

        val out = mutableListOf<Pair<Float, Float>>()
        for (i in base.indices) {
            val inc = if (i > 0) segOffset[i - 1] else null
            val outg = if (i < segOffset.size) segOffset[i] else null
            val ox = listOfNotNull(inc?.first, outg?.first).averageOrZero()
            val oy = listOfNotNull(inc?.second, outg?.second).averageOrZero()
            val x = (base[i].first + ox).coerceIn(minX, maxX)
            val y = (base[i].second + oy).coerceIn(minY, maxY)
            out.add(x to y)
        }
        return out
    }

    /** 橫平豎直折線 + 統一半徑圓角，轉彎處用二次貝塞爾過渡。 */
    private fun buildRoundedPath(
        pts: List<Pair<Float, Float>>,
        radius: Float,
    ): Path {
        val path = Path()
        path.moveTo(pts[0].first, pts[0].second)
        if (pts.size == 2) {
            path.lineTo(pts[1].first, pts[1].second)
            return path
        }

        for (i in 1 until pts.size - 1) {
            val prev = pts[i - 1]
            val p = pts[i]
            val next = pts[i + 1]

            val d1x = p.first - prev.first
            val d1y = p.second - prev.second
            val l1 = sqrt(d1x * d1x + d1y * d1y)
            val d2x = next.first - p.first
            val d2y = next.second - p.second
            val l2 = sqrt(d2x * d2x + d2y * d2y)
            if (l1 < 1f || l2 < 1f) {
                path.lineTo(p.first, p.second)
                continue
            }

            val r = min(radius, min(l1, l2) / 2f)
            val pA = Pair(p.first - d1x / l1 * r, p.second - d1y / l1 * r)
            val pB = Pair(p.first + d2x / l2 * r, p.second + d2y / l2 * r)

            path.lineTo(pA.first, pA.second)
            path.quadTo(p.first, p.second, pB.first, pB.second)
        }
        path.lineTo(pts.last().first, pts.last().second)
        return path
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
