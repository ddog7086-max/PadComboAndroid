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
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 遊戲內懸浮疊加層。
 *
 * 繪製規則：
 * - 基礎格點嚴格對齊 6×5 珠子圓心；單次經過的邊段偏移為 0，橫平豎直穿過珠心。
 * - 只有重訪／折返的無向邊段，沿垂直方向對稱分配到中心線兩側（平行車道）。
 * - 轉彎處用統一半徑的平滑小圓角，不做大弧度亂扭。
 * - 所有頂點與貝塞爾控制點送入 Path 前都過 [clampPointToBoard]，確保不突出盤面邊界。
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

        // 最底排（row 4）珠心 = bottom - 0.5cell；座標夾取上限 bottom - 0.52cell，
        // clipRect 兜底上限 bottom - 0.45cell，都不進入珠子下方深啡邊界。
        val clipBottom = rect.bottom - rect.cellSize * 0.45f

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

        val cornerRadius = rect.cellSize * 0.12f
        val curve = buildRoundedPath(pts, cornerRadius, rect)

        // 雙重保險：座標已夾取，這裡再用 clipRect 把整塊繪製區域硬性鎖在盤面內。
        canvas.save()
        canvas.clipRect(rect.left, rect.top, rect.right, clipBottom)

        canvas.drawPath(curve, blackPaint)
        canvas.drawPath(curve, whitePaint)

        // 起點小圓點：黑底描邊 + 白點（座標同樣夾回盤面內框）。
        val start = clampPointToBoard(rect, pts[0].first, pts[0].second)
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

        // 終點小箭頭：黑底三角 + 白三角，沿最後一段方向，頂點夾回盤面內框。
        val prev = pts[pts.size - 2]
        val end = clampPointToBoard(rect, pts.last().first, pts.last().second)
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
                val tip = end
                val left = clampPointToBoard(
                    rect,
                    end.first - ux * sp + nx * sp * 0.6f,
                    end.second - uy * sp + ny * sp * 0.6f,
                )
                val right = clampPointToBoard(
                    rect,
                    end.first - ux * sp - nx * sp * 0.6f,
                    end.second - uy * sp - ny * sp * 0.6f,
                )
                a.moveTo(tip.first, tip.second)
                a.lineTo(left.first, left.second)
                a.lineTo(right.first, right.second)
                a.close()
                return a
            }
            canvas.drawPath(arrowPath(1.35f), blackFill)
            canvas.drawPath(arrowPath(1.0f), whiteFill)
        }
        canvas.restore()
    }

    /**
     * 統一邊界夾取：所有頂點、偏移點、貝塞爾控制點送入 Path 前都先過這裡。
     * - 水平：`[left + 0.18cell, right - 0.18cell]`
     * - 垂直：`[top + 0.18cell, bottom - 0.52cell]`；clipRect 另以 bottom - 0.45cell 兜底。
     */
    private fun clampPointToBoard(
        rect: BoardRect,
        x: Float,
        y: Float,
    ): Pair<Float, Float> {
        val insetX = rect.cellSize * 0.18f
        val insetTop = rect.cellSize * 0.18f
        val maxAllowedY = rect.bottom - rect.cellSize * 0.52f
        val cx = x.coerceIn(rect.left + insetX, rect.right - insetX)
        val cy = y.coerceIn(rect.top + insetTop, maxAllowedY)
        return cx to cy
    }

    /**
     * 基礎格點對齊珠心；單次邊段偏移 0，重複邊段依序號對稱分到兩側。
     * 所有座標最後經 [clampPointToBoard] 夾回盤面內框。
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
            val half = (repeat - 1) / 2f
            val step = if (repeat == 1) 0f else (2f * spread / (repeat - 1))
            val position = ((lane - half) * step * density).toFloat()

            segOffset[i] = Pair(nx * position, ny * position)
        }

        val out = mutableListOf<Pair<Float, Float>>()
        for (i in base.indices) {
            val inc = if (i > 0) segOffset[i - 1] else null
            val outg = if (i < segOffset.size) segOffset[i] else null
            val ox = listOfNotNull(inc?.first, outg?.first).averageOrZero()
            val oy = listOfNotNull(inc?.second, outg?.second).averageOrZero()
            out.add(clampPointToBoard(rect, base[i].first + ox, base[i].second + oy))
        }
        return out
    }

    /** 橫平豎直折線 + 統一半徑圓角；控制點與端點全部先過 [clampPointToBoard]。 */
    private fun buildRoundedPath(
        pts: List<Pair<Float, Float>>,
        radius: Float,
        rect: BoardRect,
    ): Path {
        val path = Path()
        val start = clampPointToBoard(rect, pts[0].first, pts[0].second)
        path.moveTo(start.first, start.second)
        if (pts.size == 2) {
            val end = clampPointToBoard(rect, pts[1].first, pts[1].second)
            path.lineTo(end.first, end.second)
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
                val pc = clampPointToBoard(rect, p.first, p.second)
                path.lineTo(pc.first, pc.second)
                continue
            }

            val r = min(radius, min(l1, l2) / 2f)
            val pA = clampPointToBoard(
                rect,
                p.first - d1x / l1 * r,
                p.second - d1y / l1 * r,
            )
            // 轉彎控制點（quadTo 的 cx, cy）同樣納入邊界限制。
            val pCtrl = clampPointToBoard(rect, p.first, p.second)
            val pB = clampPointToBoard(
                rect,
                p.first + d2x / l2 * r,
                p.second + d2y / l2 * r,
            )

            path.lineTo(pA.first, pA.second)
            path.quadTo(pCtrl.first, pCtrl.second, pB.first, pB.second)
        }
        val last = clampPointToBoard(rect, pts.last().first, pts.last().second)
        path.lineTo(last.first, last.second)
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
