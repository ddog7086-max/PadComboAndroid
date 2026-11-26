package com.example.padcombo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreLogicTest {

    private fun boardOf(vararg rows: List<Orb>): Board =
        rows.map { it.toTypedArray() }.toTypedArray()

    @Test
    fun dragPath_shiftsTrailAndHoldsOrbAtEnd() {
        val board = boardOf(
            listOf(Orb.FIRE, Orb.WATER, Orb.WOOD, Orb.LIGHT, Orb.DARK, Orb.HEART),
            listOf(Orb.WATER, Orb.WOOD, Orb.LIGHT, Orb.DARK, Orb.HEART, Orb.FIRE),
            listOf(Orb.WOOD, Orb.LIGHT, Orb.DARK, Orb.HEART, Orb.FIRE, Orb.WATER),
            listOf(Orb.LIGHT, Orb.DARK, Orb.HEART, Orb.FIRE, Orb.WATER, Orb.WOOD),
            listOf(Orb.DARK, Orb.HEART, Orb.FIRE, Orb.WATER, Orb.WOOD, Orb.LIGHT),
        )
        val path = listOf(Cell(0, 0), Cell(0, 1), Cell(0, 2))
        val out = BoardEngine.applyDragPath(board, path)
        assertEquals(Orb.WATER, out[0][0])
        assertEquals(Orb.WOOD, out[0][1])
        assertEquals(Orb.FIRE, out[0][2])
    }

    @Test
    fun resolveChain_detectsHorizontalClear() {
        val board = boardOf(
            listOf(Orb.FIRE, Orb.FIRE, Orb.FIRE, Orb.WATER, Orb.WOOD, Orb.LIGHT),
            listOf(Orb.WATER, Orb.WOOD, Orb.LIGHT, Orb.DARK, Orb.HEART, Orb.FIRE),
            listOf(Orb.WOOD, Orb.LIGHT, Orb.DARK, Orb.HEART, Orb.FIRE, Orb.WATER),
            listOf(Orb.LIGHT, Orb.DARK, Orb.HEART, Orb.FIRE, Orb.WATER, Orb.WOOD),
            listOf(Orb.DARK, Orb.HEART, Orb.FIRE, Orb.WATER, Orb.WOOD, Orb.LIGHT),
        )
        val res = BoardEngine.resolveChain(board)
        assertTrue(res.totalCombo >= 1)
    }

    @Test
    fun beamSearch_returnsPathOnRandomBoard() {
        val board = BoardEngine.randomBoard()
        val cfg = SearchConfig(
            orbWeights = BeamSearchSolver.defaultWeights(),
            calcMode = CalcMode.FAST,
            maxSteps = 8,
            movesPref = MovesPref.LONG,
            targetHighCombo = true,
            targetMultiColor = true,
            beamWidth = 16,
        )
        val res = BeamSearchSolver.beamSearch(board, cfg)
        assertTrue(res.path.size > 1)
        assertTrue(res.predictedCombo >= 0)
        assertEquals(false, res.aborted)
    }

    @Test
    fun cancelledSearch_returnsAbortedFlag() {
        val board = BoardEngine.randomBoard()
        val cfg = SearchConfig(
            orbWeights = BeamSearchSolver.defaultWeights(),
            calcMode = CalcMode.FAST,
            maxSteps = 20,
            movesPref = MovesPref.LONG,
            targetHighCombo = true,
            targetMultiColor = true,
            beamWidth = 16,
            cancelled = true,
        )
        val res = BeamSearchSolver.beamSearch(board, cfg)
        assertTrue(res.aborted)
        assertTrue(res.path.isNotEmpty())
    }
}
