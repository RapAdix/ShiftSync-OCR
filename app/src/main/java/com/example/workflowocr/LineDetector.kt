package com.example.workflowocr

import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.atan2

typealias HoughSegment = Pair<Point, Point>

data class PolyLineSegment(val points: MutableList<Point> = mutableListOf()) {
    // Structural boundary endpoints
    val firstPoint: Point get() = points.first()
    val lastPoint: Point get() = points.last()

    // Calculated angle based on the true path delta trajectory
    val angle: Double
        get() = Math.toDegrees(atan2(lastPoint.y - firstPoint.y, lastPoint.x - firstPoint.x))

    val length: Double
        get() = Math.hypot(lastPoint.x - firstPoint.x, lastPoint.y - firstPoint.y)

    fun addPointToEnd(pt: Point) { points.add(pt) }
    fun addPointToStart(pt: Point) { points.add(0, pt) }
}

object LineDetector {
    val angleThreshold = 10.0

    fun extractTableBorders(srcMat: Mat): Mat {
        val edgesMat = Mat()
        val linesMat = Mat()

        val grayMat = Mat()
        if (srcMat.channels() == 1) {
            // Bitmap is already grayscale (1 channel) - Just copy rgba directly into gray
            srcMat.copyTo(grayMat)
        } else if (srcMat.channels() == 3) {
            // Bitmap is 3 channels (RGB)
            Imgproc.cvtColor(srcMat, grayMat, Imgproc.COLOR_RGB2GRAY)
        } else {
            // Bitmap is 4 channels (RGBA)
            Imgproc.cvtColor(srcMat, grayMat, Imgproc.COLOR_RGBA2GRAY)
        }

        // Probabilistic Hough Transform (Extracting structural fragments)
        Imgproc.HoughLinesP(grayMat, linesMat, 1.0, Math.PI / 180.0, 40, 20.0, 5.0)

        val horizontalLines = mutableListOf<HoughSegment>()
        val verticalLines = mutableListOf<HoughSegment>()
        val angleTolerance = 15.0

        for (i in 0 until linesMat.rows()) {
            val data = IntArray(4)
            linesMat.get(i, 0, data)

            val pt1 = Point(data[0].toDouble(), data[1].toDouble())
            val pt2 = Point(data[2].toDouble(), data[3].toDouble())

            // Calculated angle based on the true path delta trajectory
            val angle = Math.toDegrees(atan2(pt2.y - pt1.y, pt2.x - pt1.x))
            var deg = abs(angle)
            if (deg > 90.0) deg = 180.0 - deg

            // Separate and normalize vectors by spatial category orientation rules
            if (deg <= angleTolerance || abs(deg - 180.0) <= angleTolerance) {
                // Ensure horizontal lines flow cleanly from Left to Right natively
                val segment = if (pt1.x > pt2.x) Pair(pt2, pt1) else Pair(pt1, pt2)
                horizontalLines.add(segment)
            } else if (abs(deg - 90.0) <= angleTolerance) {
                // Ensure vertical lines flow cleanly from Top to Bottom natively
                val segment = if (pt1.y > pt2.y) Pair(pt2, pt1) else Pair(pt1, pt2)
                verticalLines.add(segment)
            }
        }

// =========================================================================
// 🟢 FILE-SAVING JVM DEBUG BLOCK: SAFE FOR BREAKPOINTS
// =========================================================================
        val matH = Mat()
        val matV = Mat()
        val matCombined = Mat()

        if (srcMat.channels() == 1) {
            Imgproc.cvtColor(srcMat, matH, Imgproc.COLOR_GRAY2RGB)
            Imgproc.cvtColor(srcMat, matV, Imgproc.COLOR_GRAY2RGB)
            Imgproc.cvtColor(srcMat, matCombined, Imgproc.COLOR_GRAY2RGB)
        } else {
            srcMat.copyTo(matH)
            srcMat.copyTo(matV)
            srcMat.copyTo(matCombined)
        }

        // Draw separated horizontal line fragments (Yellow)
        for (line in horizontalLines) {
            Imgproc.line(matH, line.first, line.second, Scalar(255.0, 255.0, 0.0, 255.0), 2)
            Imgproc.line(matCombined, line.first, line.second, Scalar(255.0, 255.0, 0.0, 255.0), 2)
        }

        // Draw separated vertical line fragments (Yellow)
        for (line in verticalLines) {
            Imgproc.line(matV, line.first, line.second, Scalar(255.0, 255.0, 0.0, 255.0), 2)
            Imgproc.line(matCombined, line.first, line.second, Scalar(255.0, 255.0, 0.0, 255.0), 2)
        }

        // Automatically target the project's build directory so it's easy to find and clean
        val outputDir = java.io.File("build/outputs/debug/lines")
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }

        val pathH = outputDir.absolutePath + "/1_horizontal.png"
        val pathV = outputDir.absolutePath + "/2_vertical.png"
        val pathC = outputDir.absolutePath + "/3_combined.png"

        // Save the files directly to disk (this finishes immediately before the breakpoint)
        org.opencv.imgcodecs.Imgcodecs.imwrite(pathH, matH)
        org.opencv.imgcodecs.Imgcodecs.imwrite(pathV, matV)
        org.opencv.imgcodecs.Imgcodecs.imwrite(pathC, matCombined)

        println("\n📸 [DEBUG IMAGES WRITTEN SUCCESSFULLY]")
        println("Horizontal:  file://$pathH")
        println("Vertical:    file://$pathV")
        println("Combined:    file://$pathC\n")

        matH.release()
        matV.release()
        matCombined.release()
// =========================================================================

        val proximityThreshold = kotlin.math.max(srcMat.width(), srcMat.height()) * 0.005
        val longestAllowedBacktrackRatio = 0.2 // Assume that the false line (e.g. user made) spans at most 20% of the paper

        // STAGE 1 & 2: Process using proximity-sorted arrays
        val mergedHorizontal = mergeOrderedTracks(horizontalLines, proximityThreshold, isHorizontal = true, srcMat.width().toDouble() * longestAllowedBacktrackRatio)
        val mergedVertical = mergeOrderedTracks(verticalLines, proximityThreshold, isHorizontal = false, srcMat.height().toDouble() * longestAllowedBacktrackRatio)

        // Length Filtering (Keep components stretching across at least half the target field)
        val minHorizontalLength = srcMat.width() / 2.0
        val minVerticalLength = srcMat.height() / 2.0

        val finalHorizontal = mergedHorizontal.filter { it.length >= minHorizontalLength }
        val finalVertical = mergedVertical.filter { it.length >= minVerticalLength }

        val drawMat = Mat()
        Imgproc.cvtColor(grayMat, drawMat, Imgproc.COLOR_GRAY2RGB)

        // Draw lines across points to view structural tracking path details
        for (line in finalHorizontal) {
            for (idx in 0 until line.points.size - 1) {
                Imgproc.line(drawMat, line.points[idx], line.points[idx + 1], Scalar(0.0, 255.0, 0.0, 255.0), 3)
            }
        }
        for (line in finalVertical) {
            for (idx in 0 until line.points.size - 1) {
                Imgproc.line(drawMat, line.points[idx], line.points[idx + 1], Scalar(255.0, 0.0, 0.0, 255.0), 3)
            }
        }

        // Garbage collection manual release
        grayMat.release()
        edgesMat.release()
        linesMat.release()

        return drawMat
    }

    // Merging logic using branching (backtracking) to find the longest combined lines
    private fun mergeOrderedTracks(
        lines: List<HoughSegment>,
        distanceThreshold: Double,
        isHorizontal: Boolean,
        longestAllowedBacktrack: Double
    ): List<PolyLineSegment> {
        if (lines.isEmpty()) return emptyList()

        // Spatial Pre-Sort Sweep: Organize items sequentially by position to prevent out-of-order merging anomalies
        // Sticking left-to-right for horizontal, and top-to-bottom for vertical
        val sortedWorkingList = if (isHorizontal) {
            lines.sortedBy { it.first.x }
        } else {
            lines.sortedBy { it.first.y }
        }

        val completedPool = mutableListOf<PolyLineSegment>()
        val usedIndices = HashSet<Int>()

        for (i in 0 until sortedWorkingList.size) {
            if (usedIndices.contains(i)) continue

            val startSegment = sortedWorkingList[i]
            // Expand active tracking container using the initial segment properties cleanly
            val startPolySegment = PolyLineSegment(mutableListOf(startSegment.first, startSegment.second))

            // Collect the indices of the path segments used in the longest branching solution
            val bestPathIndices = mutableListOf<Int>()
            val bestMergedLine = findLongestBranch(
                activeLine = startPolySegment,
                currentIndex = i,
                previousIndex = i, // Initially anchored to itself
                availableSegments = sortedWorkingList,
                usedIndices = usedIndices,
                currentPath = mutableListOf(i),
                bestPath = bestPathIndices,
                distanceThreshold = distanceThreshold,
                isHorizontal = isHorizontal,
                longestAllowedBacktrack = longestAllowedBacktrack
            )

            completedPool.add(bestMergedLine)
            // Permanently consume the winning path components from the pool
            usedIndices.addAll(bestPathIndices)
        }

        return completedPool
    }

    // Depth-First Search with backtracking to find the path combination yielding the longest line
    private fun findLongestBranch(
        activeLine: PolyLineSegment,
        currentIndex: Int,
        previousIndex: Int,
        availableSegments: List<HoughSegment>,
        usedIndices: Set<Int>,
        currentPath: MutableList<Int>, // track indices to permanently consume them after merging whole line
        bestPath: MutableList<Int>,
        distanceThreshold: Double,
        isHorizontal: Boolean,
        longestAllowedBacktrack: Double
    ): PolyLineSegment {

        var longestLine = activeLine

        val bestPathLength = getPathLength(bestPath, availableSegments)
        // If this is the first execution or we found a path that beats our previous global maximum length, record it
        if (bestPath.isEmpty() || activeLine.length > bestPathLength) {
            bestPath.clear()
            bestPath.addAll(currentPath)
        }

        // Early termination
        if (bestPathLength - activeLine.length > longestAllowedBacktrack)
            return longestLine

        // Scan from the index of the previously attached segment
        // to count for segments which became available because of recently filled gap.
        // Skip previous segments cause they will be considered in parallel dfs.
        val searchStartIndex = previousIndex

        for (nextIdx in searchStartIndex until availableSegments.size) {
            if (usedIndices.contains(nextIdx) || currentPath.contains(nextIdx)) continue

            val candidate = availableSegments[nextIdx]

            // Forward early-termination check using activeLine bounds
            if (isHorizontal) {
                if (candidate.first.x > activeLine.lastPoint.x + distanceThreshold) break
            } else {
                if (candidate.first.y > activeLine.lastPoint.y + distanceThreshold) break
            }

            // Verify connection compatibility at the boundary endpoint or overlapping trajectory tracks
            val mergedResult = checkAndCombineBranch(activeLine, candidate, distanceThreshold, isHorizontal)
            if (mergedResult != null) {
                currentPath.add(nextIdx)

                val branchResult = findLongestBranch(
                    activeLine = mergedResult,
                    currentIndex = nextIdx,
                    previousIndex = currentIndex, // Updates lookback to be anchored to the index of the segment we just attached
                    availableSegments = availableSegments,
                    usedIndices = usedIndices,
                    currentPath = currentPath,
                    bestPath = bestPath,
                    distanceThreshold = distanceThreshold,
                    isHorizontal = isHorizontal,
                    longestAllowedBacktrack = longestAllowedBacktrack
                )

                if (branchResult.length > longestLine.length) {
                    longestLine = branchResult
                }

                // Backtrack to try alternative branches
                currentPath.removeAt(currentPath.size - 1)
            }
        }

        return longestLine
    }

    // Helper to estimate total spatial length of a specific path array
    private fun getPathLength(path: List<Int>, segments: List<HoughSegment>): Double {
        if (path.isEmpty()) return 0.0
        val firstSeg = segments[path.first()]
        val lastSeg = segments[path.last()]
        return Math.hypot(lastSeg.second.x - firstSeg.first.x, lastSeg.second.y - firstSeg.first.y)
    }

    // Strictly connects the start of a candidate to the end of our current active line or handles spatial overlaps
    private fun checkAndCombineBranch(
        active: PolyLineSegment,
        candidate: HoughSegment,
        maxGapThreshold: Double,
        isHorizontal: Boolean
    ): PolyLineSegment? {
        // 1. Angle verification check (tightened tolerance to ~10 degrees)
        val candidateAngle = Math.toDegrees(atan2(candidate.second.y - candidate.first.y, candidate.second.x - candidate.first.x))
        val angleDifference = abs(active.angle - candidateAngle) % 180
        val normalizedAngleDiff = minOf(angleDifference, 180 - angleDifference)
        if (normalizedAngleDiff > angleThreshold)
            return null

        // Extract the final segment tracking details of the current line to check overlap proximity
        val linePoints = active.points
        val lastSegStart = if (linePoints.size >= 2) linePoints[linePoints.size - 2] else active.firstPoint
        val lastSegEnd = active.lastPoint

        // 2. Dual Connection Match Validation Gate
        // CRITERIA A: Tip-to-Tail sequential tracking link match
        val endToStartDistance = Math.hypot(candidate.first.x - lastSegEnd.x, candidate.first.y - lastSegEnd.y)
        val isTipToTailMatch = endToStartDistance <= maxGapThreshold

        // CRITERIA B: Parallel track overlap match (within 5 pixels boundary of the current line tracking footprint)
        val overlapDistance = distanceToSegment(candidate.first, lastSegStart, lastSegEnd)
        val isOverlapMatch = overlapDistance <= maxGapThreshold

        if (!isTipToTailMatch && !isOverlapMatch)
            return null

        // 🟢 UNIFIED EXTENSION METHOD:
        // Isolate the true terminal endpoint asset that represents the furthest forward reach
        val furthestCandidatePoint = if (isHorizontal) {
            if (candidate.first.x > candidate.second.x) candidate.first else candidate.second
        } else {
            if (candidate.first.y > candidate.second.y) candidate.first else candidate.second
        }

        // Verify if this point actually extends our line forward past our current maximum reach boundary
        val extendsLine = if (isHorizontal) {
            furthestCandidatePoint.x > lastSegEnd.x
        } else {
            furthestCandidatePoint.y > lastSegEnd.y
        }
        if (!extendsLine) return null

        // Construct unified dynamic path chain extension mapping layout
        val combinedPoints = mutableListOf<Point>().apply {
            addAll(active.points)
            add(furthestCandidatePoint) // Snap directly to the new furthest forward boundary position point
        }

        return PolyLineSegment(combinedPoints)
    }

    private fun distanceToSegment(p: Point, segA: Point, segB: Point): Double {
        val dx = segB.x - segA.x
        val dy = segB.y - segA.y

        if (dx == 0.0 && dy == 0.0) {
            return Math.hypot(p.x - segA.x, p.y - segA.y)
        }

        val t = ((p.x - segA.x) * dx + (p.y - segA.y) * dy) / (dx * dx + dy * dy)

        return when {
            t < 0.0 -> Math.hypot(p.x - segA.x, p.y - segA.y)
            t > 1.0 -> Math.hypot(p.x - segB.x, p.y - segB.y)
            else -> {
                val projectionX = segA.x + t * dx
                val projectionY = segA.y + t * dy
                return Math.hypot(p.x - projectionX, p.y - projectionY)
            }
        }
    }
}