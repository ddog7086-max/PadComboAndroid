package com.example.padcombo.core

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** 盤面 ROI，歸一化 0..1（相對於整張截圖） */
data class RoiRect(val x: Double, val y: Double, val width: Double, val height: Double) {
    companion object {
        val FULL = RoiRect(0.0, 0.0, 1.0, 1.0)
    }
}

data class CellInfo(
    val orb: Orb,
    /** 0..1，越接近 1 越有把握 */
    val confidence: Double,
    val warnings: List<String>,
)

data class RecognizeResult(
    val board: Board,
    val cells: List<List<CellInfo>>,
)

private data class Hsv(val h: Double, val s: Double, val v: Double)

private data class Prototype(val orb: Orb, val h: Double, val s: Double, val v: Double)

object BoardRecognizer {

    private const val CONFIDENCE_THRESHOLD = 0.55

    private val PROTOTYPES = listOf(
        Prototype(Orb.FIRE, 8.0, 0.85, 0.95),
        Prototype(Orb.WATER, 210.0, 0.80, 0.95),
        Prototype(Orb.WOOD, 135.0, 0.70, 0.85),
        Prototype(Orb.LIGHT, 52.0, 0.70, 0.98),
        Prototype(Orb.DARK, 275.0, 0.60, 0.60),
        Prototype(Orb.HEART, 335.0, 0.75, 0.92),
    )

    private fun rgbToHsv(r: Int, g: Int, b: Int): Hsv {
        val rn = r / 255.0
        val gn = g / 255.0
        val bn = b / 255.0
        val mx = maxOf(rn, gn, bn)
        val mn = minOf(rn, gn, bn)
        val d = mx - mn
        val v = mx
        val s = if (mx == 0.0) 0.0 else d / mx
        var h = 0.0
        if (d != 0.0) {
            h = when (mx) {
                rn -> 60 * (((gn - bn) / d) % 6)
                gn -> 60 * ((bn - rn) / d + 2)
                else -> 60 * ((rn - gn) / d + 4)
            }
        }
        if (h < 0) h += 360.0
        return Hsv(h, s, v)
    }

    private fun hueDistance(a: Double, b: Double): Double {
        var d = abs(a - b) % 360.0
        if (d > 180) d = 360.0 - d
        return d
    }

    private fun median(values: List<Int>): Int {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2 else sorted[mid]
    }

    fun classifySample(samples: List<Triple<Int, Int, Int>>): CellInfo {
        if (samples.isEmpty()) {
            return CellInfo(PROTOTYPES[0].orb, 0.0, listOf("UNCERTAIN", "EDGE_BLUR"))
        }
        val r = median(samples.map { it.first })
        val g = median(samples.map { it.second })
        val b = median(samples.map { it.third })
        val (h, s, v) = rgbToHsv(r, g, b)

        var best = PROTOTYPES[0]
        var bestScore = Double.MAX_VALUE
        for (p in PROTOTYPES) {
            val hd = hueDistance(h, p.h) / 180.0
            val sd = abs(s - p.s)
            val vd = abs(v - p.v)
            val score = hd * 0.6 + sd * 0.25 + vd * 0.15
            if (score < bestScore) {
                bestScore = score
                best = p
            }
        }

        val confidence = (1.0 - bestScore * 1.4).coerceIn(0.0, 1.0)
        val warnings = mutableListOf<String>()
        if (v < 0.3) warnings.add("LOW_BRIGHTNESS")
        if (best.orb == Orb.DARK || v < 0.45) warnings.add("DARK_ORB")
        if (s < 0.25) warnings.add("LOW_SATURATION")
        if (confidence < CONFIDENCE_THRESHOLD) warnings.add("UNCERTAIN")

        return CellInfo(best.orb, confidence, warnings)
    }

    private fun sampleCell(bitmap: Bitmap, roi: RoiRect, row: Int, col: Int): List<Triple<Int, Int, Int>> {
        val w = bitmap.width
        val h = bitmap.height
        val roiX = roi.x * w
        val roiY = roi.y * h
        val roiW = roi.width * w
        val roiH = roi.height * h
        val cellW = roiW / COLS
        val cellH = roiH / ROWS

        val samples = mutableListOf<Triple<Int, Int, Int>>()
        val inner = 0.2
        for (iy in 0 until 3) {
            for (ix in 0 until 3) {
                val fx = inner + (ix / 2.0) * (1 - inner * 2)
                val fy = inner + (iy / 2.0) * (1 - inner * 2)
                val px = Math.round(roiX + (col + fx) * cellW).toInt()
                val py = Math.round(roiY + (row + fy) * cellH).toInt()
                if (px < 0 || px >= w || py < 0 || py >= h) continue
                val pixel = bitmap.getPixel(px, py)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                samples.add(Triple(r, g, b))
            }
        }
        return samples
    }

    /** 從 Bitmap 依歸一化 ROI 均分 6×5 格，辨識六色並輸出 Board。 */
    fun recognizeBitmap(bitmap: Bitmap, roi: RoiRect = RoiRect.FULL): RecognizeResult {
        val board = Array(ROWS) { arrayOf(Orb.FIRE) }
        val cells = mutableListOf<List<CellInfo>>()
        for (row in 0 until ROWS) {
            val boardRow = mutableListOf<Orb>()
            val cellRow = mutableListOf<CellInfo>()
            for (col in 0 until COLS) {
                val info = classifySample(sampleCell(bitmap, roi, row, col))
                boardRow.add(info.orb)
                cellRow.add(info)
            }
            board[row] = boardRow.toTypedArray()
            cells.add(cellRow)
        }
        return RecognizeResult(board, cells)
    }
}
