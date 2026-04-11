package com.example.workflowocr

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
            if (grid[midR][c] != null) { // TODO fix propagation to make first row bigger
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

            // Corrected: Use expectedRows and expectedCols
            if (nextR !in 0 until expectedRows || nextC !in 0 until expectedCols) break
            if (grid[nextR][nextC] != null) {
                currR = nextR
                currC = nextC
                continue
            }

            val prevPoint = grid[currR][currC]!!

            // Prediction Logic
            val prediction = if (currR - dr in 0 until expectedRows && currC - dc in 0 until expectedCols && grid[currR - dr][currC - dc] != null) {
                val p0 = grid[currR - dr][currC - dc]!!
                Point(
                    prevPoint.x + (prevPoint.x - p0.x),
                    prevPoint.y + (prevPoint.y - p0.y)
                )
            } else {
                if (dc != 0) {
                    val beltDist = belts[nextC] - belts[currC]
                    Point(prevPoint.x + beltDist, prevPoint.y)
                } else {
                    val beltDist = belts[nextR] - belts[currR]
                    Point(prevPoint.x, prevPoint.y + beltDist)
                }
            }

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
            aspect in 0.2..5.0 && Imgproc.contourArea(it) > 5.0
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