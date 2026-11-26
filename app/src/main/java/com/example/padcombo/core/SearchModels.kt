package com.example.padcombo.core

enum class CalcMode { FAST, ACCURATE }

enum class MovesPref { SHORT, LONG }

data class SearchConfig(
    val orbWeights: Map<Orb, Double>,
    val calcMode: CalcMode,
    val maxSteps: Int,
    val movesPref: MovesPref,
    val targetHighCombo: Boolean,
    val targetMultiColor: Boolean,
    val beamWidth: Int,
    /** 取消旗標：搜尋迴圈每層檢查，中斷後回傳當前最優解。 */
    @Volatile var cancelled: Boolean = false,
)

data class SearchResult(
    val path: List<Cell>,
    val predictedCombo: Int,
    val score: Double,
    val clearStat: Map<Orb, Int>,
    val elapsedMs: Long,
    val aborted: Boolean,
)
