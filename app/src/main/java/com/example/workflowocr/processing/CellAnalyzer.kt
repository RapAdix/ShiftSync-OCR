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
    private const val BORDER_INSET_MAX_SCAN_PX = 12
    private const val BORDER_INSET_MAX_VERT_FRACTION = 0.16
    private const val BORDER_INSET_MAX_HORIZ_FRACTION = 0.06
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
        val penCoverage = detectPenStrokes(cleanedThresh, cells, settings.modificationColumns)
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
        val originalHeight = maxOf(
            hypot(cell.bottomLeft.x - cell.topLeft.x, cell.bottomLeft.y - cell.topLeft.y),
            hypot(cell.bottomRight.x - cell.topRight.x, cell.bottomRight.y - cell.topRight.y)
        )
        val inner = innerCell(crossingThresh, cell)
        val insetCell = shrinkCell(inner, horizontalPx = 2.0, verticalPx = 2.0)
        val sourceCorners = listOf(insetCell.topLeft, insetCell.topRight, insetCell.bottomRight, insetCell.bottomLeft)
        val warped = warpCell(crossingThresh, sourceCorners)

        val dp = directionalDp(warped)
        val longestLine = (1..9).maxOf { direction ->
            dp.maxOf { row -> row.maxOf { values -> values[direction] } }
        }
        val dpCrossed = longestLine >= originalHeight * CROSSING_LENGTH_FRACTION
        warped.release()
        // The DP detector is optimized for the common bottom-left to top-right
        // stroke. Use the older strip-area detector only as a fallback for the
        // opposite stroke direction; this avoids running another DP pass.
        val crossed = dpCrossed || detectPenCrossingFast(crossingThresh, cell, insetCell)
        val innerPoints = arrayOf(
            insetCell.topLeft,
            insetCell.topRight,
            insetCell.bottomRight,
            insetCell.bottomLeft
        )
        return Pair(crossed, innerPoints)
    }

    /**
     * Fast fallback detector for crossings that the directional DP can miss.
     * It reuses the original legacy top and bottom strip-area test instead of
     * running a second directional DP pass.
     */
    private fun detectPenCrossingFast(thresh: Mat, originalCell: TableCell, insetCell: TableCell): Boolean {
        val hExclusionPct = 0.14
        val topStripHeightPct = 0.19
        val btmStripHeightPct = 0.24
        val mask = Mat.zeros(thresh.size(), CvType.CV_8UC1)
        // Use insetCell for the outer cell edges (already cleared past any printed grid border),
        // while keeping the inner text-exclusion boundaries (topStripHeightPct / btmStripHeightPct
        // and horizontal margins) anchored to originalCell so border shifting never pushes the
        // strips into the printed time digits in the middle of the cell.
        val topQuad = arrayOf(
            getPointInCell(insetCell, hExclusionPct, 0.0),
            getPointInCell(insetCell, 1.0 - hExclusionPct, 0.0),
            getPointInCell(originalCell, 1.0 - hExclusionPct, topStripHeightPct),
            getPointInCell(originalCell, hExclusionPct, topStripHeightPct)
        )
        val bottomQuad = arrayOf(
            getPointInCell(originalCell, hExclusionPct, 1.0 - btmStripHeightPct),
            getPointInCell(originalCell, 1.0 - hExclusionPct, 1.0 - btmStripHeightPct),
            getPointInCell(insetCell, 1.0 - hExclusionPct, 1.0),
            getPointInCell(insetCell, hExclusionPct, 1.0)
        )

        val validQuads = listOf(topQuad, bottomQuad).filter { q ->
            q[0].y < q[3].y && q[1].y < q[2].y
        }
        if (validQuads.isEmpty()) {
            mask.release()
            return false
        }
        val matPolys = validQuads.map { MatOfPoint(*it) }
        Imgproc.fillPoly(mask, matPolys, Scalar(255.0))

        val evidence = Mat()
        Core.bitwise_and(thresh, mask, evidence)
        val inkPixelCount = Core.countNonZero(evidence)
        val quad2fs = validQuads.map { MatOfPoint2f(*it) }
        val area = quad2fs.sumOf { Imgproc.contourArea(it) }

        evidence.release()
        mask.release()
        matPolys.forEach { it.release() }
        quad2fs.forEach { it.release() }

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

    /**
     * Traces a discrete line segment from (row, col) to (endRow, endCol) in the binary [pixels]
     * image (where 255 = foreground ink/line and 0 = background) and measures how far it continues
     * without breaking.
     *
     * At each step along the segment, it checks the 3x3 neighborhood around (r, c) for any white
     * pixel (255). This 1-pixel tolerance prevents thin, anti-aliased, or slightly jagged strokes
     * from breaking mid-run. As soon as a step has no white neighbor, tracing stops.
     *
     * @return Pair of (consecutive white steps from start, covered Euclidean length in pixels).
     */
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
        val inner = innerCell(thresh, cell)
        val w = maxOf(
            hypot(inner.topRight.x - inner.topLeft.x, inner.topRight.y - inner.topLeft.y),
            hypot(inner.bottomRight.x - inner.bottomLeft.x, inner.bottomRight.y - inner.bottomLeft.y)
        )
        val h = maxOf(
            hypot(inner.bottomLeft.x - inner.topLeft.x, inner.bottomLeft.y - inner.topLeft.y),
            hypot(inner.bottomRight.x - inner.topRight.x, inner.bottomRight.y - inner.topRight.y)
        )
        val insetCell = shrinkCell(inner, horizontalPx = w * 0.03, verticalPx = h * 0.03)
        val mask = Mat.zeros(thresh.size(), CvType.CV_8UC1)
        val quad = arrayOf(insetCell.topLeft, insetCell.topRight, insetCell.bottomRight, insetCell.bottomLeft)

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
     * Dynamically moves each corner of [cell] inward until it clears any printed table border lines
     * present in the binary [thresh] image (where 255 = ink/border, 0 = paper), without applying any
     * caller-specific minimum margin.
     *
     * For each half-edge (e.g. top-left, top-right, left-top, etc.), we scan inward up to a bounded
     * pixel depth and measure how many points along that half-edge are ink pixels. A printed table
     * border spans most of the half-edge at a consistent depth, whereas diagonal crossings or
     * handwriting only cross it at isolated points. Each corner is pushed inward past the deepest
     * detected border line on its two adjacent half-edges (or stays at 0 if no border is present).
     */
    fun innerCell(
        thresh: Mat,
        cell: TableCell
    ): TableCell {
        val cellWidth = maxOf(
            hypot(cell.topRight.x - cell.topLeft.x, cell.topRight.y - cell.topLeft.y),
            hypot(cell.bottomRight.x - cell.bottomLeft.x, cell.bottomRight.y - cell.bottomLeft.y)
        )
        val cellHeight = maxOf(
            hypot(cell.bottomLeft.x - cell.topLeft.x, cell.bottomLeft.y - cell.topLeft.y),
            hypot(cell.bottomRight.x - cell.topRight.x, cell.bottomRight.y - cell.topRight.y)
        )
        if (cellWidth < 10.0 || cellHeight < 10.0 || thresh.empty()) return cell

        val corners = listOf(cell.topLeft, cell.topRight, cell.bottomRight, cell.bottomLeft)
        val minX = corners.minOf { it.x }.toInt().coerceIn(0, thresh.cols() - 1)
        val maxX = corners.maxOf { it.x }.toInt().coerceIn(minX, thresh.cols() - 1)
        val minY = corners.minOf { it.y }.toInt().coerceIn(0, thresh.rows() - 1)
        val maxY = corners.maxOf { it.y }.toInt().coerceIn(minY, thresh.rows() - 1)
        val roiW = maxX - minX + 1
        val roiH = maxY - minY + 1
        val roi = thresh.submat(minY, maxY + 1, minX, maxX + 1)
        val pixels = ByteArray(roiW * roiH)
        roi.get(0, 0, pixels)
        roi.release()

        fun isInk(px: Double, py: Double): Boolean {
            val ix = px.toInt().coerceIn(minX, maxX) - minX
            val iy = py.toInt().coerceIn(minY, maxY) - minY
            return (pixels[iy * roiW + ix].toInt() and 0xFF) != 0
        }

        fun edgeClearance(
            u0: Double, v0: Double, u1: Double, v1: Double,
            inU: Double, inV: Double,
            maxScanPx: Int
        ): Double {
            val samples = 20
            var deepestBorderDepth = -1
            for (d in 0..maxScanPx) {
                var hits = 0
                for (i in 0 until samples) {
                    val t = i.toDouble() / (samples - 1)
                    val u = u0 + (u1 - u0) * t
                    val v = v0 + (v1 - v0) * t
                    val pt0 = getPointInCell(cell, u + d * inU, v + d * inV)
                    val pt1 = getPointInCell(cell, u + (d + 1) * inU, v + (d + 1) * inV)
                    if (isInk(pt0.x, pt0.y) || isInk(pt1.x, pt1.y)) hits++
                }
                if (hits >= samples * 0.45) {
                    deepestBorderDepth = d
                }
            }
            return if (deepestBorderDepth >= 0) deepestBorderDepth + 2.0 else 0.0
        }

        val maxVertScan = minOf(BORDER_INSET_MAX_SCAN_PX, (cellHeight * BORDER_INSET_MAX_VERT_FRACTION).toInt())
        val maxHorizScan = minOf(BORDER_INSET_MAX_SCAN_PX, (cellWidth * BORDER_INSET_MAX_HORIZ_FRACTION).toInt())
        val invW = 1.0 / cellWidth
        val invH = 1.0 / cellHeight

        val topLeftV = edgeClearance(0.12, 0.0, 0.50, 0.0, 0.0, invH, maxVertScan)
        val topRightV = edgeClearance(0.50, 0.0, 0.88, 0.0, 0.0, invH, maxVertScan)
        val botLeftV = edgeClearance(0.12, 1.0, 0.50, 1.0, 0.0, -invH, maxVertScan)
        val botRightV = edgeClearance(0.50, 1.0, 0.88, 1.0, 0.0, -invH, maxVertScan)

        val leftTopU = edgeClearance(0.0, 0.12, 0.0, 0.50, invW, 0.0, maxHorizScan)
        val leftBotU = edgeClearance(0.0, 0.50, 0.0, 0.88, invW, 0.0, maxHorizScan)
        val rightTopU = edgeClearance(1.0, 0.12, 1.0, 0.50, -invW, 0.0, maxHorizScan)
        val rightBotU = edgeClearance(1.0, 0.50, 1.0, 0.88, -invW, 0.0, maxHorizScan)

        return TableCell(
            topLeft = getPointInCell(cell, (leftTopU * invW).coerceAtMost(0.4), (topLeftV * invH).coerceAtMost(0.4)),
            topRight = getPointInCell(cell, (1.0 - rightTopU * invW).coerceAtLeast(0.6), (topRightV * invH).coerceAtMost(0.4)),
            bottomLeft = getPointInCell(cell, (leftBotU * invW).coerceAtMost(0.4), (1.0 - botLeftV * invH).coerceAtLeast(0.6)),
            bottomRight = getPointInCell(cell, (1.0 - rightBotU * invW).coerceAtLeast(0.6), (1.0 - botRightV * invH).coerceAtLeast(0.6))
        )
    }

    /** Moves all four corners of [cell] inward by [horizontalPx] and [verticalPx]. */
    private fun shrinkCell(cell: TableCell, horizontalPx: Double, verticalPx: Double): TableCell {
        val cellWidth = maxOf(
            hypot(cell.topRight.x - cell.topLeft.x, cell.topRight.y - cell.topLeft.y),
            hypot(cell.bottomRight.x - cell.bottomLeft.x, cell.bottomRight.y - cell.bottomLeft.y)
        )
        val cellHeight = maxOf(
            hypot(cell.bottomLeft.x - cell.topLeft.x, cell.bottomLeft.y - cell.topLeft.y),
            hypot(cell.bottomRight.x - cell.topRight.x, cell.bottomRight.y - cell.topRight.y)
        )
        if (cellWidth <= 0.0 || cellHeight <= 0.0) return cell
        val uInset = (horizontalPx / cellWidth).coerceIn(0.0, 0.4)
        val vInset = (verticalPx / cellHeight).coerceIn(0.0, 0.4)
        return TableCell(
            topLeft = getPointInCell(cell, uInset, vInset),
            topRight = getPointInCell(cell, 1.0 - uInset, vInset),
            bottomLeft = getPointInCell(cell, uInset, 1.0 - vInset),
            bottomRight = getPointInCell(cell, 1.0 - uInset, 1.0 - vInset)
        )
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
}
