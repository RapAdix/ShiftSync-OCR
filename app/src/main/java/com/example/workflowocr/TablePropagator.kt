package com.example.workflowocr

import android.util.Log
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.imgproc.Imgproc

class TablePropagator(
    private val searchWindowSize: Int = 30
) {
    private var expectedRows: Int = 0
    private var expectedCols: Int = 0

    fun propagateRobustGrid(
        intersections: Mat,
        validX: List<Int>,
        validY: List<Int>,
        grid: Array<Array<Point?>>
    ): Array<Array<Point>> {
        expectedRows = validY.size
        expectedCols = validX.size
        if (expectedRows == 0 || expectedCols == 0) {
            Log.w("DEBUG", "Table propagation impossible, belts amount is 0, rows:$expectedRows, cols:$expectedCols")
            return emptyArray()
        }

        val midR = expectedRows / 2
        val midC = expectedCols / 2

        val idealSeed = Point(validX[midC].toDouble(), validY[midR].toDouble())
        val actualSeed = findLocalIntersection(intersections, idealSeed) ?: idealSeed
        grid[midR][midC] = actualSeed

        // Propagate horizontally from center
        propagateLine(grid, intersections, midR, midC, 0, 1, validX)  // Right
        propagateLine(grid, intersections, midR, midC, 0, -1, validX) // Left

        // Propagate vertically for every column
        for (c in 0 until expectedCols) {
            if (grid[midR][c] != null) {
                propagateLine(grid, intersections, midR, c, 1, 0, validY)  // Down
                propagateLine(grid, intersections, midR, c, -1, 0, validY) // Up
            }
        }

        return grid as Array<Array<Point>>
    }

    private fun propagateLine(
        grid: Array<Array<Point?>>,
        intersections: Mat,
        startR: Int,
        startC: Int,
        dr: Int,
        dc: Int,
        belts: List<Int>
    ) {
        var currR = startR
        var currC = startC

        while (true) {
            val nextR = currR + dr
            val nextC = currC + dc

            if (nextR !in 0 until expectedRows || nextC !in 0 until expectedCols) break
            if (grid[nextR][nextC] != null) {
                currR = nextR
                currC = nextC
                continue
            }

            val prevPoint = grid[currR][currC]!!

            // 1. Get the "Ideal" distance for the NEXT step and the PREVIOUS step from belts
            val idealDist = if (dc != 0) belts[nextC] - belts[currC] else belts[nextR] - belts[currR]

            val prediction = if (currR - dr in 0 until expectedRows && currC - dc in 0 until expectedCols && grid[currR - dr][currC - dc] != null) {
                // We have a previous segment to calculate local slope/skew
                val p0 = grid[currR - dr][currC - dc]!!

                // Calculate the local vector
                val localDX = prevPoint.x - p0.x
                val localDY = prevPoint.y - p0.y

                // 2. Check if the model (belts) expects a size change here
                val prevIdealDist = if (dc != 0) belts[currC] - belts[currC - dc] else belts[currR] - belts[currR - dr]
                val expectedRatio = if (Math.abs(prevIdealDist.toDouble()) > 0.1) idealDist.toDouble() / prevIdealDist else 1.0

                // 3. HYBRID DECISION:
                // If the ratio is close to 1.0 (e.g., 0.85 to 1.15), the model thinks rows are equal.
                // In this case, IGNORE the belt noise at the bottom and use Pure Momentum (scale = 1.0).
                // If the ratio is large (e.g., 2.0 for Header), use the scale.
                val scale = if (Math.abs(expectedRatio - 1.0) < 0.15) 1.0 else expectedRatio

                Point(
                    prevPoint.x + (localDX * scale),
                    prevPoint.y + (localDY * scale)
                )
            } else {
                // Fallback for the very first step of propagation, Use belts
                if (dc != 0) Point(prevPoint.x + idealDist, prevPoint.y)
                else Point(prevPoint.x, prevPoint.y + idealDist)
            }

            // Search for actual intersection near the scaled prediction
            val found = findLocalIntersection(intersections, prediction)
            grid[nextR][nextC] = found ?: prediction

            currR = nextR
            currC = nextC
        }
    }

    private fun findLocalIntersection(intersections: Mat, target: Point): Point? {
        val x = target.x.toInt()
        val y = target.y.toInt()
        val half = searchWindowSize / 2

        if (x - half < 0 || y - half < 0 || x + half >= intersections.cols() || y + half >= intersections.rows()) return null

        val roiRect = Rect(x - half, y - half, searchWindowSize, searchWindowSize)
        val roi = intersections.submat(roiRect)

        val contours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(roi, contours, Mat(), Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)

        val validContours = contours.filter {
            val rect = Imgproc.boundingRect(it)
            val aspect = rect.width.toDouble() / rect.height.toDouble()
            aspect in 0.2..6.0
        }

        val bestContour = validContours.maxByOrNull { Imgproc.contourArea(it) }
        var result: Point? = null
        if (bestContour != null) {
            val moments = Imgproc.moments(bestContour)
            result = Point(moments.m10 / moments.m00 + roiRect.x, moments.m01 / moments.m00 + roiRect.y)
        }

        roi.release()
        return result
    }
}