package com.example.workflowocr

import android.util.Log
import com.example.workflowocr.TableDetector.TableCell
import kotlinx.serialization.Serializable
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

object CellAnalyzer {
    @Serializable
    data class RowAnalysis (
        val penCoverage: Array<Double>,
        val startTimeCrossed: Boolean,
        val endTimeCrossed: Boolean
    )

    fun analyzeCells(thresh: Mat, cells: Array<Array<TableCell>>): Array<RowAnalysis> {
        if (cells.isEmpty()) return emptyArray()
        val swollenThresh = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(2.0, 2.0))
        Imgproc.dilate(thresh, swollenThresh, kernel)
        val penCoverage = detectPenStrokes(swollenThresh, cells, MODIFICATION_COLUMNS + listOf(2, 3))

        val startTimeCrossed = Array(cells.size) {false}
        for (row in cells.indices) {
            val (isCrossed, _, _) = detectPenCrossing(swollenThresh, cells[row][TIME_START_COL])
            startTimeCrossed[row] = isCrossed
            if (isCrossed)
                Log.d("DEBUG", "Row: $row, col: $TIME_START_COL has a crossing over time")
        }
        val endTimeCrossed = Array(cells.size) {false}
        for (row in cells.indices) {
            val (isCrossed, _, _) = detectPenCrossing(swollenThresh, cells[row][TIME_END_COL])
            endTimeCrossed[row] = isCrossed
            if (isCrossed)
                Log.d("DEBUG", "Row: $row, col: $TIME_END_COL has a crossing over time")
        }

        val analysis = penCoverage.mapIndexed { i, coverage ->
            RowAnalysis(
                penCoverage = coverage,
                startTimeCrossed = startTimeCrossed[i],
                endTimeCrossed = endTimeCrossed[i]
                )
        }.toTypedArray()
        swollenThresh.release()
        kernel.release()
        return analysis
    }

    fun detectPenStrokes(thresh: Mat, cells: Array<Array<TableCell>>, cols: List<Int> = MODIFICATION_COLUMNS): Array<Array<Double>> {
        val penCoverage = Array(cells.size) {Array(cells[0].size) {0.0}}
        for (col in cols) {
            for (row in cells.indices) {
                penCoverage[row][col] = detectPenWriting(thresh, cells[row][col])
            }
        }
        return penCoverage
    }

    /**
     * Detects pen marks by looking at "Internal Safety Windows" within a distorted cell.
     * @param thresh: Inverted threshold Mat (255=ink, 0=paper)
     * @param cell: The 4-corner TableCell
     * @param hExclusionPct: Horizontal margin to skip on left/right (e.g., 0.10 for 10%)
     * @param vStripHeightPct: Vertical height of the strips (e.g., 0.20 for 20%)
     */
    fun detectPenCrossing(
        thresh: Mat,
        cell: TableCell,
        hExclusionPct: Double = 0.14,
        topStripHeightPct: Double = 0.19,
        btmStripHeightPct: Double = 0.24
    ): Triple<Boolean, Array<Point>, Array<Point>> {
        val mask = Mat.zeros(thresh.size(), CvType.CV_8UC1)

        // 1. Inset to definitively avoid the black physical cell borders (in pixels)
        val borderInset = (cell.bottomRight.y - cell.topLeft.y) * 0.1

        // 2. Define Top Safety Quad
        // Start at top (0.0), go down to strip height (vStripHeightPct)
        // Exclude horizontal margins (hExclusionPct to 1.0 - hExclusionPct)
        val topQuad = getSubQuad(
            cell,
            yStart = 0.0, yEnd = topStripHeightPct,
            xStart = hExclusionPct, xEnd = 1.0 - hExclusionPct,
            inset = borderInset, insetTop = true, insetBtm = false
        )

        // 3. Define Bottom Safety Quad
        // Start at bottom (1.0), go up by strip height
        val bottomQuad = getSubQuad(
            cell,
            yStart = 1.0 - btmStripHeightPct, yEnd = 1.0,
            xStart = hExclusionPct, xEnd = 1.0 - hExclusionPct,
            inset = borderInset, insetTop = false, insetBtm = true
        )

        // 4. Draw Polygons on Mask
        val listOfPolys = listOf(MatOfPoint(*topQuad), MatOfPoint(*bottomQuad))
        Imgproc.fillPoly(mask, listOfPolys, Scalar(255.0))

        // 5. Count intersection
        val evidence = Mat()
        Core.bitwise_and(thresh, mask, evidence)
        val inkPixelCount = Core.countNonZero(evidence)

        // 4. Calculate the Area of the Quad
        val topQuad2f = MatOfPoint2f(*topQuad)
        val btmQuad2f = MatOfPoint2f(*bottomQuad)
        val area = Imgproc.contourArea(topQuad2f) + Imgproc.contourArea(btmQuad2f)

        // Release temporary resources
        evidence.release()
        mask.release()
        topQuad2f.release()
        btmQuad2f.release()

        val pixelDetectionThreshold = area * 0.015
        return Triple(inkPixelCount > pixelDetectionThreshold, topQuad, bottomQuad)
    }

    fun detectPenWriting(
        thresh: Mat,
        cell: TableCell,
    ): Double {
        val mask = Mat.zeros(thresh.size(), CvType.CV_8UC1)

        // 1. Inset to definitively avoid the black physical cell borders (in pixels)
        val borderInset = (cell.bottomRight.y - cell.topLeft.y) * 0.1

        // Start at top (0.0), go down to strip height (vStripHeightPct)
        // Exclude horizontal margins (hExclusionPct to 1.0 - hExclusionPct)
        val quad = getSubQuad(
            cell,
            yStart = 0.0, yEnd = 1.0,
            xStart = 0.0, xEnd = 1.0,
            inset = borderInset, insetTop = true, insetBtm = true
        )

        // 4. Draw Polygons on Mask
        val matOfPoint = MatOfPoint(*quad)
        Imgproc.fillPoly(mask, listOf(matOfPoint), Scalar(255.0))

        // 5. Count intersection
        val evidence = Mat()
        Core.bitwise_and(thresh, mask, evidence)
        val inkPixelCount = Core.countNonZero(evidence)

        // 4. Calculate the Area of the Quad
        val quad2f = MatOfPoint2f(*quad)
        val area = Imgproc.contourArea(quad2f)

        evidence.release()
        mask.release()
        quad2f.release()
        matOfPoint.release()

        return if (area > 0) (inkPixelCount / area) else 0.0
    }

    /**
     * Calculates a point (u, v) inside a 4-point quad via bilinear interpolation.
     * @param u: Horizontal percentage (0.0 to 1.0)
     * @param v: Vertical percentage (0.0 to 1.0)
     */
    private fun getPointInCell(cell: TableCell, u: Double, v: Double): Point {
        // Top boundary at u%
        val topX = cell.topLeft.x + (cell.topRight.x - cell.topLeft.x) * u
        val topY = cell.topLeft.y + (cell.topRight.y - cell.topLeft.y) * u

        // Bottom boundary at u%
        val botX = cell.bottomLeft.x + (cell.bottomRight.x - cell.bottomLeft.x) * u
        val botY = cell.bottomLeft.y + (cell.bottomRight.y - cell.bottomLeft.y) * u

        // Interpolate vertically between top and bottom at v%
        val finalX = topX + (botX - topX) * v
        val finalY = topY + (botY - topY) * v

        return Point(finalX, finalY)
    }

    /**
     * Creates 4 points defining a specific percentage window within the cell.
     */
    private fun getSubQuad(
        cell: TableCell,
        yStart: Double, yEnd: Double,
        xStart: Double, xEnd: Double,
        inset: Double, insetTop: Boolean, insetBtm: Boolean
    ): Array<Point> {
        // Target 4 corners of our inner window
        val p1 = getPointInCell(cell, xStart, yStart)
        val p2 = getPointInCell(cell, xEnd, yStart)
        val p3 = getPointInCell(cell, xEnd, yEnd)
        val p4 = getPointInCell(cell, xStart, yEnd)

        // Apply safety insets towards the center of the cell to avoid border lines
        return arrayOf( // only do so from the side that is close to cell edge
            if (insetTop) p1.applyInset(direction = 1, amount = inset) else p1, // Push Down-Right
            if (insetTop) p2.applyInset(direction = 2, amount = inset) else p2, // Push Down-Left
            if (insetBtm) p3.applyInset(direction = 3, amount = inset) else p3, // Push Up-Left
            if (insetBtm) p4.applyInset(direction = 4, amount = inset) else p4  // Push Up-Right
        )
    }

    /**
     * Helper to push a point inward based on which corner of a quad it represents.
     */
    private fun Point.applyInset(direction: Int, amount: Double): Point {
        return when(direction) {
            1 -> Point(x + amount, y + amount) // Top-Left corner moves in
            2 -> Point(x - amount, y + amount) // Top-Right corner moves in
            3 -> Point(x - amount, y - amount) // Bottom-Right corner moves in
            4 -> Point(x + amount, y - amount) // Bottom-Left corner moves in
            else -> this
        }
    }
}