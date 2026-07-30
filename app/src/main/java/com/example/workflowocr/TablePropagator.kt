package com.example.workflowocr

import android.util.Log
import org.opencv.core.Point
import kotlin.math.abs
import kotlin.math.sqrt

object TablePropagator {
    /**
     * Builds a complete (R x C) grid of intersection points.
     * Uses the central row (midR) as a solid anchor spine, then propagates vertically
     * for every column starting outward from midR.
     */
    fun propagateRobustPolyLineGrid(
        sortedHorizontalLines: List<PolyLineSegment>,
        sortedVerticalLines: List<PolyLineSegment>,
        physicalJunctions: List<Point>
    ): Array<Array<Point>> {
        val rows = sortedHorizontalLines.size
        val cols = sortedVerticalLines.size
        if (rows == 0 || cols == 0) {
            Log.w("DEBUG", "Table propagation impossible, rows:$rows, cols:$cols")
            return emptyArray()
        }

        val grid = Array(rows) { arrayOfNulls<Point>(cols) }

        // Phase 1: Direct Polyline Intersections found naturally
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val directIntersection = sortedHorizontalLines[r].findIntersection(sortedVerticalLines[c])
                grid[r][c] = if (directIntersection != null) {
                    // If direct crossing exists, snap to physical centroid if within small radius (e.g. 8px)
                    findNearestPhysicalJunction(directIntersection, physicalJunctions, maxSearchRadiusPx = 8.0) ?: directIntersection
                } else {
                    null
                }
            }
        }

        // Phase 2: Anchor Seed (midR, midC)
        val midR = rows / 2
        val midC = cols / 2

        if (grid[midR][midC] == null) {
            grid[midR][midC] = estimateMissingPoint(sortedHorizontalLines, sortedVerticalLines, grid, midR, midC)
        }

        // Phase 3 & 4: Propagation
        // Right from midC
        propagateLine(sortedHorizontalLines, sortedVerticalLines, grid, physicalJunctions, startR = midR, startC = midC, dr = 0, dc = 1)
        // Left from midC
        propagateLine(sortedHorizontalLines, sortedVerticalLines, grid, physicalJunctions, startR = midR, startC = midC, dr = 0, dc = -1)

        // Propagate vertically for EVERY column, always starting from the midR anchor!
        val colOrder = buildColumnOrder(cols, midC) // Starts at midC, then midC+1, midC-1, etc.

        for (c in colOrder) {
            if (grid[midR][c] != null) {
                // Propagate DOWN from midR
                propagateLine(sortedHorizontalLines, sortedVerticalLines, grid, physicalJunctions, startR = midR, startC = c, dr = 1, dc = 0)
                // Propagate UP from midR
                propagateLine(sortedHorizontalLines, sortedVerticalLines, grid, physicalJunctions, startR = midR, startC = c, dr = -1, dc = 0)
            }
        }

        // Final safety pass to handle any unassigned edge points
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if (grid[r][c] == null) {
                    grid[r][c] = estimateMissingPoint(sortedHorizontalLines, sortedVerticalLines, grid, r, c)
                }
            }
        }

        @Suppress("UNCHECKED_CAST")
        return grid as Array<Array<Point>>
    }

    /**
     * Finds the nearest physical contour junction centroid near an estimated point within a given radius.
     */
    private fun findNearestPhysicalJunction(
        targetPoint: Point,
        junctions: List<Point>,
        maxSearchRadiusPx: Double
    ): Point? {
        if (junctions.isEmpty()) return null

        return junctions.minByOrNull { j ->
            val dx = j.x - targetPoint.x
            val dy = j.y - targetPoint.y
            dx * dx + dy * dy
        }?.takeIf { j ->
            val dx = j.x - targetPoint.x
            val dy = j.y - targetPoint.y
            sqrt(dx * dx + dy * dy) <= maxSearchRadiusPx
        }
    }

    private fun propagateLine(
        hLines: List<PolyLineSegment>,
        vLines: List<PolyLineSegment>,
        grid: Array<Array<Point?>>,
        junctions: List<Point>,
        startR: Int,
        startC: Int,
        dr: Int,
        dc: Int
    ) {
        val rows = grid.size
        val cols = grid[0].size
        var currR = startR
        var currC = startC

        while (true) {
            val nextR = currR + dr
            val nextC = currC + dc

            if (nextR !in 0 until rows || nextC !in 0 until cols) break

            // If next cell is missing, predict it from currR, currC
            if (grid[nextR][nextC] == null) {
                grid[nextR][nextC] = propagateStep(hLines, vLines, grid, junctions, currR, currC, nextR, nextC)
            }

            // Advance step
            currR = nextR
            currC = nextC
        }
    }

    /**
     * Predicts a missing grid point at (targetR, targetC) based on neighboring structural vectors.
     */
    private fun propagateStep(
        hLines: List<PolyLineSegment>,
        vLines: List<PolyLineSegment>,
        grid: Array<Array<Point?>>,
        junctions: List<Point>,
        currentR: Int,
        currentC: Int,
        targetR: Int,
        targetC: Int
    ): Point {
        val prevPoint = grid[currentR][currentC]!!
        val dr = targetR - currentR
        val dc = targetC - currentC

        val hLine = hLines.getOrNull(targetR)
        val vLine = vLines.getOrNull(targetC)

        // Strategy 1: Lateral neighbor displacement
        val lateralOffsets = listOf(-1, 1)
        for (offset in lateralOffsets) {
            val neighborR = if (dr != 0) targetR else currentR + offset
            val neighborC = if (dc != 0) targetC else currentC + offset
            val neighborPrevR = neighborR - dr
            val neighborPrevC = neighborC - dc

            if (
                neighborR in grid.indices && neighborC in grid[0].indices &&
                neighborPrevR in grid.indices && neighborPrevC in grid[0].indices &&
                grid[neighborR][neighborC] != null && grid[neighborPrevR][neighborPrevC] != null
            ) {
                val pNeighborTarget = grid[neighborR][neighborC]!!
                val pNeighborPrev = grid[neighborPrevR][neighborPrevC]!!

                // Delta vector from neighbor segment
                val dx = pNeighborTarget.x - pNeighborPrev.x
                val dy = pNeighborTarget.y - pNeighborPrev.y
                val candidatePoint = Point(prevPoint.x + dx, prevPoint.y + dy)

                // Snap to physical junction if close (radius: 12px)
                return findNearestPhysicalJunction(candidatePoint, junctions, maxSearchRadiusPx = 12.0) ?: candidatePoint
            }
        }

        // Strategy 2: Ray projection
        if (hLine != null && vLine != null) {
            val candidatePoint = estimateLineTrendCrossing(hLine, vLine)
            if (candidatePoint != null) {
                // Snap to physical junction if close (radius: 15px)
                return findNearestPhysicalJunction(candidatePoint, junctions, maxSearchRadiusPx = 15.0) ?: candidatePoint
            }
        }

        // Strategy 3: Momentum extrapolation
        val prev2R = currentR - dr
        val prev2C = currentC - dc
        if (prev2R in grid.indices && prev2C in grid[0].indices && grid[prev2R][prev2C] != null) {
            val pPrev2 = grid[prev2R][prev2C]!!
            val dx = prevPoint.x - pPrev2.x
            val dy = prevPoint.y - pPrev2.y
            val candidatePoint = Point(prevPoint.x + dx, prevPoint.y + dy)

            // Snap to physical junction if close (radius: 12px)
            return findNearestPhysicalJunction(candidatePoint, junctions, maxSearchRadiusPx = 12.0) ?: candidatePoint
        }

        // Strategy 4: Fallback estimation
        val fallbackPoint = estimateMissingPoint(hLines, vLines, grid, targetR, targetC)
        return findNearestPhysicalJunction(fallbackPoint, junctions, maxSearchRadiusPx = 20.0) ?: fallbackPoint
    }

    /**
     * Projects the general direction rays of two non-intersecting PolyLineSegments
     * and finds their line-line intersection point.
     */
    private fun estimateLineTrendCrossing(hLine: PolyLineSegment, vLine: PolyLineSegment): Point? {
        val hP1 = hLine.firstPoint
        val hP2 = hLine.lastPoint
        val vP1 = vLine.firstPoint
        val vP2 = vLine.lastPoint

        val d = (hP1.x - hP2.x) * (vP1.y - vP2.y) - (hP1.y - hP2.y) * (vP1.x - vP2.x)
        if (abs(d) < 1e-5) return null

        val t = ((hP1.x - vP1.x) * (vP1.y - vP2.y) - (hP1.y - vP1.y) * (vP1.x - vP2.x)) / d

        return Point(
            hP1.x + t * (hP2.x - hP1.x),
            hP1.y + t * (hP2.y - hP1.y)
        )
    }

    /**
     * Fallback point estimation based on bounding coordinates or line averages.
     */
    private fun estimateMissingPoint(
        hLines: List<PolyLineSegment>,
        vLines: List<PolyLineSegment>,
        grid: Array<Array<Point?>>,
        r: Int,
        c: Int
    ): Point {
        val hLine = hLines.getOrNull(r)
        val vLine = vLines.getOrNull(c)

        val y = hLine?.let { (it.firstPoint.y + it.lastPoint.y) / 2.0 }
            ?: (0 until grid[0].size).mapNotNull { grid[r][it]?.y }.average().takeIf { !it.isNaN() }
            ?: (r * 50.0)

        val x = vLine?.let { (it.firstPoint.x + it.lastPoint.x) / 2.0 }
            ?: (0 until grid.size).mapNotNull { grid[it][c]?.x }.average().takeIf { !it.isNaN() }
            ?: (c * 50.0)

        return Point(x, y)
    }

    /**
     * Helper to build column iteration order starting from middle column radiating outward.
     */
    private fun buildColumnOrder(cols: Int, midC: Int): List<Int> {
        val order = mutableListOf<Int>()
        order.add(midC)
        var step = 1
        while (midC + step < cols || midC - step >= 0) {
            if (midC + step < cols) order.add(midC + step)
            if (midC - step >= 0) order.add(midC - step)
            step++
        }
        return order
    }
}