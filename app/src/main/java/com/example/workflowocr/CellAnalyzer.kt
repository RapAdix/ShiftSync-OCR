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
import kotlin.math.hypot

object CellAnalyzer {
    private const val CROSSING_LENGTH_FRACTION = 0.70
    private const val LEGACY_CROSSING_AREA_FRACTION = 0.03
    @Serializable
    data class RowAnalysis (
        val penCoverage: Array<Double>,
        val startTimeCrossed: Boolean,
        val endTimeCrossed: Boolean
    )

    fun analyzeCells(gray: Mat, thresh: Mat, cells: Array<Array<TableCell>>, settings: TableLayout): Array<RowAnalysis> {
        if (cells.isEmpty()) return emptyArray()
        val cleanedThresh = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(2.0, 2.0))
        Imgproc.erode(thresh, cleanedThresh, kernel)
        Imgproc.dilate(cleanedThresh, cleanedThresh, kernel)
        val penCoverage = detectPenStrokes(cleanedThresh, cells, settings.modificationColumns + listOf(settings.timeStartCol, settings.timeEndCol))
        val crossingThresh = ImageProcessor.createCrossingThresh(gray)

        val startTimeCrossed = Array(cells.size) {false}
        for (row in cells.indices) {
            val (isCrossed, _) = detectPenCrossing(crossingThresh, cells[row][settings.timeStartCol])
            startTimeCrossed[row] = isCrossed
            if (isCrossed)
                Log.d("DEBUG", "Row: $row, col: ${settings.timeStartCol} has a crossing over time")
        }
        val endTimeCrossed = Array(cells.size) {false}
        for (row in cells.indices) {
            val (isCrossed, _) = detectPenCrossing(crossingThresh, cells[row][settings.timeEndCol])
            endTimeCrossed[row] = isCrossed
            if (isCrossed)
                Log.d("DEBUG", "Row: $row, col: ${settings.timeEndCol} has a crossing over time")
        }

        val analysis = penCoverage.mapIndexed { i, coverage ->
            RowAnalysis(
                penCoverage = coverage,
                startTimeCrossed = startTimeCrossed[i],
                endTimeCrossed = endTimeCrossed[i]
                )
        }.toTypedArray()
        cleanedThresh.release()
        crossingThresh.release()
        kernel.release()
        return analysis
    }

    fun detectPenStrokes(thresh: Mat, cells: Array<Array<TableCell>>, cols: List<Int>): Array<Array<Double>> {
        val penCoverage = Array(cells.size) {Array(cells[0].size) {0.0}}
        for (col in cols) {
            for (row in cells.indices) {
                penCoverage[row][col] = detectPenWriting(thresh, cells[row][col])
            }
        }
        return penCoverage
    }

    /**
     * Detects a pen crossing in a cell using a precomputed crossing threshold.
     * The cell is perspective-warped, masked with a 4% margin on every edge, and
     * evaluated using the directional DP crossing detector.
     *
     * @param crossingThresh binary threshold created from the source grayscale image
     * @param cell the four-corner cell geometry in [crossingThresh]'s coordinates
     * @return whether a crossing was detected and the four inset cell corners for debugging
     */
    fun detectPenCrossing(
        crossingThresh: Mat,
        cell: TableCell
    ): Pair<Boolean, Array<Point>> {
        val sourceCorners = listOf(cell.topLeft, cell.topRight, cell.bottomRight, cell.bottomLeft)
        val warped = warpCell(crossingThresh, sourceCorners)
        val innerMask = Mat.zeros(warped.size(), CvType.CV_8UC1)
        val xInset = warped.width() * 0.04
        val yInset = warped.height() * 0.04
        Imgproc.rectangle(
            innerMask,
            Point(xInset, yInset),
            Point(warped.width() - xInset, warped.height() - yInset),
            Scalar(255.0),
            -1
        )
        Core.bitwise_and(warped, innerMask, warped)
        innerMask.release()

        val dp = directionalDp(warped)
        val longestLine = (1..9).maxOf { direction ->
            dp.maxOf { row -> row.maxOf { values -> values[direction] } }
        }
        val dpCrossed = longestLine >= warped.height() * CROSSING_LENGTH_FRACTION
        warped.release()
        // The DP detector is optimized for the common bottom-left to top-right
        // stroke. Use the older strip-area detector only as a fallback for the
        // opposite stroke direction; this avoids running another DP pass.
        val crossed = dpCrossed || detectPenCrossingFast(crossingThresh, cell)
        val margin = 0.04
        val innerPoints = arrayOf(
            getPointInCell(cell, margin, margin),
            getPointInCell(cell, 1.0 - margin, margin),
            getPointInCell(cell, 1.0 - margin, 1.0 - margin),
            getPointInCell(cell, margin, 1.0 - margin)
        )
        return Pair(crossed, innerPoints)
    }

    /**
     * Fast fallback detector for crossings that the directional DP can miss.
     * It reuses the original legacy top and bottom strip-area test instead of
     * running a second directional DP pass.
     */
    private fun detectPenCrossingFast(thresh: Mat, cell: TableCell): Boolean {
        val hExclusionPct = 0.14
        val topStripHeightPct = 0.19
        val btmStripHeightPct = 0.24
        val mask = Mat.zeros(thresh.size(), CvType.CV_8UC1)
        val borderInset = (cell.bottomRight.y - cell.topLeft.y) * 0.1
        val topQuad = getSubQuad(
            cell,
            yStart = 0.0, yEnd = topStripHeightPct,
            xStart = hExclusionPct, xEnd = 1.0 - hExclusionPct,
            inset = borderInset, insetTop = true, insetBtm = false
        )
        val bottomQuad = getSubQuad(
            cell,
            yStart = 1.0 - btmStripHeightPct, yEnd = 1.0,
            xStart = hExclusionPct, xEnd = 1.0 - hExclusionPct,
            inset = borderInset, insetTop = false, insetBtm = true
        )

        val topMaskPoints = MatOfPoint(*topQuad)
        val bottomMaskPoints = MatOfPoint(*bottomQuad)
        Imgproc.fillPoly(mask, listOf(topMaskPoints, bottomMaskPoints), Scalar(255.0))

        val evidence = Mat()
        Core.bitwise_and(thresh, mask, evidence)
        val inkPixelCount = Core.countNonZero(evidence)
        val topQuadPoints = MatOfPoint2f(*topQuad)
        val bottomQuadPoints = MatOfPoint2f(*bottomQuad)
        val area = Imgproc.contourArea(topQuadPoints) + Imgproc.contourArea(bottomQuadPoints)

        evidence.release()
        mask.release()
        topMaskPoints.release()
        bottomMaskPoints.release()
        topQuadPoints.release()
        bottomQuadPoints.release()

        return area > 0.0 && inkPixelCount > area * LEGACY_CROSSING_AREA_FRACTION
    }

    private fun warpCell(source: Mat, corners: List<Point>): Mat {
        val width = maxOf(
            hypot(corners[1].x - corners[0].x, corners[1].y - corners[0].y),
            hypot(corners[2].x - corners[3].x, corners[2].y - corners[3].y)
        ).toInt()
        val height = maxOf(
            hypot(corners[3].x - corners[0].x, corners[3].y - corners[0].y),
            hypot(corners[2].x - corners[1].x, corners[2].y - corners[1].y)
        ).toInt()
        val sourcePoints = MatOfPoint2f(*corners.toTypedArray())
        val destinationPoints = MatOfPoint2f(
            Point(0.0, 0.0), Point(width - 1.0, 0.0),
            Point(width - 1.0, height - 1.0), Point(0.0, height - 1.0)
        )
        val transform = Imgproc.getPerspectiveTransform(sourcePoints, destinationPoints)
        val result = Mat()
        Imgproc.warpPerspective(source, result, transform, Size(width.toDouble(), height.toDouble()))
        sourcePoints.release()
        destinationPoints.release()
        transform.release()
        return result
    }

    /**
     * Calculates the longest white line beginning at every pixel.
     *
     * Each direction is represented by one of the eleven points on the far
     * edges of a 6x6 square around the starting pixel:
     *
     *   0=(up 5, right 0), 1=(up 5, right 1), ... 5=(up 5, right 5),
     *   6=(up 4, right 5), ... 9=(up 1, right 5), 10=(up 0, right 5).
     *
     * Direction 0 is vertical and is intentionally not calculated or used as
     * continuation support. Direction 10 is horizontal; the crossing decision
     * considers only directions 1 through 9.
     *
     * The DP is evaluated from the top row downward because an upward path at
     * (row, column) depends on values at (row - deltaRow, column + deltaColumn),
     * which have already been calculated in the rows above.
     */
    private fun directionalDp(binary: Mat): Array<Array<DoubleArray>> {
        val pixels = ByteArray(binary.rows() * binary.cols())
        binary.get(0, 0, pixels)
        val dp = Array(binary.rows()) { Array(binary.cols()) { DoubleArray(11) } }
        // Each state first checks its local 5-pixel jump, then optionally joins
        // one of the neighboring directions at the jump's destination.
        // Skip vertical(0) and horizontal(10) directions because they are mostly invalid.
        for (row in 0 until binary.rows()) for (col in 0 until binary.cols()) for (direction in 1 until 11) {
            val (dr, dc) = if (direction <= 5) 5 to direction else 4 - (direction - 6) to 5
            val endRow = row - dr
            val endCol = col + dc
            if (endRow < 0 || endCol >= binary.cols()) continue
            val (whitePixels, directLength) = whiteRun(pixels, binary.rows(), binary.cols(), row, col, endRow, endCol)
            val stepCount = maxOf(kotlin.math.abs(dr), kotlin.math.abs(dc))
            val stepLength = hypot(dr.toDouble(), dc.toDouble())
            val continued = if (whitePixels == stepCount + 1) {
                when (direction) {
                    1 -> maxOf(dp[endRow][endCol][1], dp[endRow][endCol][2])
                    10 -> maxOf(dp[endRow][endCol][9], dp[endRow][endCol][10])
                    else -> maxOf(
                        dp[endRow][endCol][direction - 1],
                        dp[endRow][endCol][direction],
                        dp[endRow][endCol][direction + 1]
                    )
                }
            } else 0.0
            dp[row][col][direction] = maxOf(directLength, continued + stepLength)
        }
        return dp
    }

    private fun whiteRun(pixels: ByteArray, rows: Int, cols: Int, row: Int, col: Int, endRow: Int, endCol: Int): Pair<Int, Double> {
        val steps = maxOf(kotlin.math.abs(endRow - row), kotlin.math.abs(endCol - col))
        var count = 0
        for (step in 0..steps) {
            val r = row + (endRow - row) * step / steps.coerceAtLeast(1)
            val c = col + (endCol - col) * step / steps.coerceAtLeast(1)
            var isWhite = false
            for (neighborRow in (r - 1).coerceAtLeast(0)..(r + 1).coerceAtMost(rows - 1)) {
                for (neighborCol in (c - 1).coerceAtLeast(0)..(c + 1).coerceAtMost(cols - 1)) {
                    if ((pixels[neighborRow * cols + neighborCol].toInt() and 0xFF) == 255) isWhite = true
                }
            }
            if (isWhite) count++ else break
        }
        val geometricStep = hypot((endRow - row).toDouble(), (endCol - col).toDouble()) / steps.coerceAtLeast(1)
        return count to (count - 1).coerceAtLeast(0) * geometricStep
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
     * Searches for string patterns indicating that the person is shift manager (kz1, kz2, kzn)
     */
    fun isKZ(team: String?): Boolean{
        if (team == null) return false
        return team.contains("kz", ignoreCase = true) && !team.contains("zkz", ignoreCase = true)
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
