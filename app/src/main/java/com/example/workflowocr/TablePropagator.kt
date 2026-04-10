package com.example.workflowocr
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.imgproc.Imgproc

class TablePropagator(
    private val rowCount: Int,
    private val colCount: Int,
    private val searchWindowSize: Int = 25
) {

    fun propagateGrid(
        intersections: Mat,
        validXBelts: List<Int>,
        validYBelts: List<Int>
    ): Array<Array<Point?>> {
        val grid = Array(rowCount) { arrayOfNulls<Point>(colCount) }
        val imgCenter = Point(intersections.cols() / 2.0, intersections.rows() / 2.0)

        // 1. Find the Seed (Closest to center)
        val midR = rowCount / 2
        val midC = colCount / 2

        // Find actual intersection near the "ideal" middle belt crossing
        val seedPoint = findLocalIntersection(
            intersections,
            Point(validXBelts[midC].toDouble(), validYBelts[midR].toDouble())
        ) ?: Point(validXBelts[midC].toDouble(), validYBelts[midR].toDouble())

        grid[midR][midC] = seedPoint

        // 2. Propagate in 4 directions
        // We use a queue-like approach or simple loops starting from center

        // Propagate the middle row first (Left and Right)
        propagateLine(grid, intersections, midR, midC, 0, 1, validXBelts)  // Right
        propagateLine(grid, intersections, midR, midC, 0, -1, validXBelts) // Left

        // Now propagate every column Up and Down from the middle row
        for (c in 0 until colCount) {
            if (grid[midR][c] != null) {
                propagateLine(grid, intersections, midR, c, 1, 0, validYBelts)  // Down
                propagateLine(grid, intersections, midR, c, -1, 0, validYBelts) // Up
            }
        }

        return grid
    }

    /**
     * Propagates from a starting point in a specific direction (dr, dc)
     */
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

            if (nextR !in 0 until rowCount || nextC !in 0 until colCount) break
            if (grid[nextR][nextC] != null) {
                // Already found, move to next
                currR = nextR
                currC = nextC
                continue
            }

            val prevPoint = grid[currR][currC]!!

            // Prediction Logic:
            // If we have 2 points already, use their local distance (slope/width)
            // Otherwise, use the distance between global belts as a fallback
            val prediction = if (currR - dr in 0 until rowCount && currC - dc in 0 until colCount && grid[currR - dr][currC - dc] != null) {
                val p0 = grid[currR - dr][currC - dc]!!
                Point(
                    prevPoint.x + (prevPoint.x - p0.x),
                    prevPoint.y + (prevPoint.y - p0.y)
                )
            } else {
                // Fallback to global belt spacing
                if (dc != 0) {
                    val beltDist = belts[nextC] - belts[currC]
                    Point(prevPoint.x + beltDist, prevPoint.y)
                } else {
                    val beltDist = belts[nextR] - belts[currR]
                    Point(prevPoint.x, prevPoint.y + beltDist)
                }
            }

            // Search in local ROI
            val found = findLocalIntersection(intersections, prediction)

            // If not found, we "trust" the prediction but mark it as less reliable
            // Or stop propagation if the image is too noisy
            grid[nextR][nextC] = found ?: prediction

            currR = nextR
            currC = nextC
        }
    }

    private fun findLocalIntersection(intersections: Mat, target: Point): Point? {
        val x = target.x.toInt()
        val y = target.y.toInt()
        val half = searchWindowSize / 2

        // Boundary check
        if (x - half < 0 || y - half < 0 || x + half >= intersections.cols() || y + half >= intersections.rows()) {
            return null
        }

        val roiRect = Rect(x - half, y - half, searchWindowSize, searchWindowSize)
        val roi = intersections.submat(roiRect)

        val contours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(roi, contours, Mat(), Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)

        var bestPoint: Point? = null
        var maxArea = -1.0

        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            if (area > maxArea) {
                val moments = Imgproc.moments(contour)
                if (moments.m00 > 0) {
                    val cx = moments.m10 / moments.m00
                    val cy = moments.m01 / moments.m00
                    // Convert ROI coordinates back to global
                    bestPoint = Point(cx + roiRect.x, cy + roiRect.y)
                    maxArea = area
                }
            }
        }

        roi.release()
        return bestPoint
    }
}