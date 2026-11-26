package com.example.padcombo.core

import kotlin.system.measureTimeMillis

object BeamSearchSolver {

    private val DIRS = listOf(
        -1 to 0, 1 to 0, 0 to -1, 0 to 1,
    )

    /** 預設六色權重（心珠偏低），0 = 停用該色 */
    fun defaultWeights(): Map<Orb, Double> = mapOf(
        Orb.FIRE to 5.0,
        Orb.WATER to 5.0,
        Orb.WOOD to 5.0,
        Orb.LIGHT to 5.0,
        Orb.DARK to 5.0,
        Orb.HEART to 3.0,
    )

    /**
     * 評估一條拖拽軌跡：先平移盤面，再跑連鎖結算。
     * 消除屬性數從每輪消除前盤面讀取，避免被下落補珠覆蓋。
     */
    fun evaluatePath(originalBoard: Board, path: List<Cell>, cfg: SearchConfig): SearchResult {
        val movedBoard = BoardEngine.applyDragPath(originalBoard, path)
        val chain = BoardEngine.resolveChain(movedBoard)
        val predictedCombo = chain.totalCombo

        val clearStat = mutableMapOf<Orb, Int>()
        for (round in chain.rounds) {
            for ((r, c) in round.clearedCells) {
                val orb = round.beforeBoard[r][c]
                clearStat[orb] = (clearStat[orb] ?: 0) + 1
            }
        }

        var score = predictedCombo * 100.0
        if (cfg.targetHighCombo) score += predictedCombo * 60.0
        for ((orb, count) in clearStat) {
            score += count * (cfg.orbWeights[orb] ?: 0.0)
        }
        if (cfg.targetMultiColor && clearStat.size >= 4) score += 250.0
        if (cfg.movesPref == MovesPref.SHORT) score -= (path.size - 1) * 2.0

        return SearchResult(path, predictedCombo, score, clearStat, 0L, false)
    }

    /** 輕量啟發式預估：不跑連鎖，只看同色相鄰對數、權重總和與多色潛力。 */
    private fun heuristicScore(originalBoard: Board, path: List<Cell>, cfg: SearchConfig): Double {
        val moved = BoardEngine.applyDragPath(originalBoard, path)
        var samePairs = 0
        var weightSum = 0.0
        val seen = HashSet<Orb>()

        for (r in 0 until ROWS) {
            for (c in 0 until COLS) {
                val orb = moved[r][c]
                seen.add(orb)
                weightSum += cfg.orbWeights[orb] ?: 0.0
                if (c + 1 < COLS && moved[r][c + 1] == orb) samePairs++
                if (r + 1 < ROWS && moved[r + 1][c] == orb) samePairs++
            }
        }

        var h = samePairs * 10.0 + weightSum * 0.5
        if (cfg.targetMultiColor && seen.size >= 4) h += 30.0
        if (cfg.movesPref == MovesPref.SHORT) h -= (path.size - 1) * 2.0
        return h
    }

    private data class BeamState(val path: List<Cell>, val score: Double)

    /**
     * Beam Search：從全盘每格出發，四方向擴展；允許重複經過格子，
     * 只禁止即時掉頭。每層先按啟發式取短名單，再對前列候選跑完整結算評分。
     */
    fun beamSearch(originalBoard: Board, cfg: SearchConfig): SearchResult {
        val beamW = if (cfg.calcMode == CalcMode.ACCURATE) cfg.beamWidth else cfg.beamWidth / 2
        val shortlist = maxOf(beamW, beamW * 2)

        var beam: List<BeamState> = buildList {
            for (r in 0 until ROWS) for (c in 0 until COLS) add(BeamState(listOf(Cell(r, c)), 0.0))
        }

        var aborted = false
        val elapsed = measureTimeMillis {
            for (step in 0 until cfg.maxSteps) {
                if (cfg.cancelled) {
                    aborted = true
                    break
                }

                val rough = mutableListOf<Pair<List<Cell>, Double>>()
                for (state in beam) {
                    val (cr, cc) = state.path.last()
                    val (pr, pc) = state.path.getOrNull(state.path.size - 2) ?: (-99 to -99)
                    for ((dr, dc) in DIRS) {
                        val nr = cr + dr
                        val nc = cc + dc
                        if (nr !in 0 until ROWS || nc !in 0 until COLS) continue
                        if (nr == pr && nc == pc) continue // 禁止即時回退
                        val newPath = state.path + Cell(nr, nc)
                        rough.add(newPath to heuristicScore(originalBoard, newPath, cfg))
                    }
                }

                rough.sortByDescending { it.second }
                val evaluated = rough.take(shortlist).map { (p, _) ->
                    val full = evaluatePath(originalBoard, p, cfg)
                    BeamState(p, full.score)
                }
                val ranked = evaluated.sortedByDescending { it.score }
                beam = ranked.take(beamW)
            }
        }

        val best = beam.first()
        val finalRes = evaluatePath(originalBoard, best.path, cfg)
        return finalRes.copy(elapsedMs = elapsed, aborted = aborted || cfg.cancelled)
    }
}
