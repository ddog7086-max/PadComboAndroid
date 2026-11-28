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

        val cornerRadius = rect.cellSize * 0.16f
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

        // 水平通過段（王字水平帶）：連續同 row 的水平邊歸為一條 Pass，帶內按次序編號。
        val edgeRank = IntArray(base.size - 1) { -1 }
        val bandN = HashMap<Int, Int>()
        run {
            val passes = mutableListOf<Triple<Int, Int, Int>>() // row, startEdge, endEdge
            var i = 0
            while (i < route.size - 1) {
                if (route[i].first == route[i + 1].first) {
                    val r = route[i].first
                    val start = i
                    while (i < route.size - 1 &&
                        route[i].first == route[i + 1].first &&
                        route[i].first == r
                    ) i++
                    passes.add(Triple(r, start, i - 1))
                } else {
                    i++
                }
            }
            passes.groupBy { it.first }.forEach { (r, ps) ->
                bandN[r] = ps.size
                ps.forEachIndexed { idx, p ->
                    for (e in p.second..p.third) edgeRank[e] = idx
                }
            }
        }

        // 每條線段的法向對稱偏移向量。
        val segOffset = Array(base.size - 1) { Pair(0f, 0f) }
        for (i in 0 until base.size - 1) {
            var tx = base[i + 1].first - base[i].first
            var ty = base[i + 1].second - base[i].second
            val tangentLen = sqrt(tx * tx + ty * ty)
            if (tangentLen < 1f) continue
            tx /= tangentLen
            ty /= tangentLen
            // 法向取固定朝向（與行走方向無關），避免去回程法向相反造成左右不對稱。
            var nx = -ty
            var ny = tx
            if (nx < 0f || (kotlin.math.abs(nx) < 1e-6f && ny < 0f)) {
                nx = -nx
                ny = -ny
            }

            val isHorizontal = route[i].first == route[i + 1].first
            if (isHorizontal) {
                val row = route[i].first
                val n = bandN[row] ?: 1
                if (n < 2) continue // 同 row 只有一條通過：留在中心線。
                // 水平帶沿垂直法向 Y 開槽；槽距格寬比例，單邊偏移封頂 0.28 cell。
                val active = minOf(n, 3)
                val spacing = if (active > 1) {
                    minOf(0.11f * rect.cellSize, 0.28f * rect.cellSize / (active - 1))
                } else 0f
                val rank = edgeRank[i].coerceAtMost(active - 1)
                val slot = when (active) {
                    1 -> 0
                    2 -> if (rank == 0) -1 else 1
                    else -> rank - 1
                }
                segOffset[i] = Pair(0f, ny * slot * spacing)
            } else {
                val k = edgeKey(route[i], route[i + 1])
                val repeat = edgeCount[k] ?: 1
                if (repeat < 2) continue // 單次路段：偏移 0，筆直穿珠心。

                val lane = edgeSeen[k] ?: 0
                edgeSeen[k] = lane + 1

                // 垂直重訪邊沿水平法向 X 開槽：N=2 → ±12dp；N=3 → -14dp, 0, +14dp。
                val spread = if (repeat == 2) 12f else 14f
                val half = (repeat - 1) / 2f
                val step = if (repeat == 1) 0f else (2f * spread / (repeat - 1))
                val position = ((lane - half) * step * density).toFloat()
                segOffset[i] = Pair(nx * position, 0f)
            }
        }

        val out = mutableListOf<Pair<Float, Float>>()
        for (i in base.indices) {
            val neighbors = listOfNotNull(
                if (i > 0) segOffset[i - 1] else null,
                if (i < segOffset.size) segOffset[i] else null,
            )
            // 分軸累積：水平段只貢獻 Y 偏移、垂直段只貢獻 X 偏移，
            // 避免兩段偏移向量混在一起平均，把水平端點拉成斜線。
            var sumX = 0f
            var cntX = 0
            var sumY = 0f
            var cntY = 0
            for (o in neighbors) {
                if (kotlin.math.abs(o.first) > 1e-6f) { sumX += o.first; cntX++ }
                if (kotlin.math.abs(o.second) > 1e-6f) { sumY += o.second; cntY++ }
            }
            val ox = if (cntX > 0) sumX / cntX else 0f
            var oy = if (cntY > 0) sumY / cntY else 0f
            // 終點箭頭保護：最後一點的垂直偏移回壓，避免箭頭被車道帶離珠心。
            if (i == base.size - 1) oy *= 0.35f
            out.add(clampPointToBoard(rect, base[i].first + ox, base[i].second + oy))
        }

        // 同向連鎖合併（只影響畫圖折點，不改變搜尋路徑/步數/起終點）：
        // 連續同向段只留第一與最後一個；轉折點、重訪車道邊界點一律保留。
        val kept = BooleanArray(out.size)
        kept[0] = true
        kept[out.size - 1] = true
        for (i in 1 until out.size - 1) {
            val prevDir = (route[i].first - route[i - 1].first) to
                (route[i].second - route[i - 1].second)
            val nextDir = (route[i + 1].first - route[i].first) to
                (route[i + 1].second - route[i].second)
            val isTurn = prevDir != nextDir
            val prevRepeat = (edgeCount[edgeKey(route[i - 1], route[i])] ?: 1) >= 2
            val nextRepeat = (edgeCount[edgeKey(route[i], route[i + 1])] ?: 1) >= 2
            kept[i] = isTurn || prevRepeat || nextRepeat
        }
        return out.filterIndexed { i, _ -> kept[i] }
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
