package com.example.workflowocr

import android.util.Log
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

typealias HoughSegment = Pair<Point, Point>

data class PolyLineSegment(val points: MutableList<Point> = mutableListOf()) {
    // Structural boundary endpoints
    val firstPoint: Point get() = points.first()
    val lastPoint: Point get() = points.last()

    // Calculated angle based on the true path delta trajectory
    val angle: Double
        get() = Math.toDegrees(atan2(lastPoint.y - firstPoint.y, lastPoint.x - firstPoint.x))

    val length: Double
        get() = hypot(lastPoint.x - firstPoint.x, lastPoint.y - firstPoint.y)

    /**
     * Interpolates the off-axis coordinate (Y for horizontal, X for vertical) at a specific main-axis position.
     */
    fun getOffAxisCoordinateAt(
        targetMainPos: Double,
        isHorizontal: Boolean
    ): Double? {
        val points = points
        for (i in 0 until points.size - 1) {
            val p1 = points[i]
            val p2 = points[i + 1]

            val m1 = if (isHorizontal) p1.x else p1.y
            val m2 = if (isHorizontal) p2.x else p2.y

            val minM = minOf(m1, m2)
            val maxM = maxOf(m1, m2)

            if (targetMainPos in minM..maxM) {
                val off1 = if (isHorizontal) p1.y else p1.x
                val off2 = if (isHorizontal) p2.y else p2.x

                if (minM == maxM) return off1 // Vertical step segment

                // Linear interpolation between point nodes
                val fraction = (targetMainPos - m1) / (m2 - m1)
                return off1 + fraction * (off2 - off1)
            }
        }
        return null
    }
}

object LineDetector {
    enum class OverlapType {
        NONE,               // Insufficient overlap (< 20% span)
        OVERLAP_DIVERGENCE,  // Shared axis span, but off-axis distance exceeds proximity limit (Suspicious)
        CONTAINED,          // One line is a complete sub-segment inside the other (Normal)
        ONE_ENDED_EXTENSION,// Same on one end, but one line extends further on the other end (Normal)
        MUTUAL_EXTENSION    // Lines overlap in middle, but BOTH extend past each other on opposite ends (Suspicious)
    }

    private const val ANGLE_THRESHOLD = 10.0 // (degrees)
    private const val ENDPOINT_MATCH_TOLERANCE_PX = 5.0 // Epsilon tolerance for endpoint alignment (px)
    private const val OVERLAP_SPAN_SAMPLE_COUNT = 10.0 // How often we take height checks across the overlap zone
    private const val MIN_PROXIMITY_MATCH_RATIO = 0.80 // Minimum ratio of sampled points that must fall into ENDPOINT_MATCH_TOLERANCE_PX

    fun extractTableBorders(grayMat: Mat): Mat {
        require(grayMat.channels() == 1) {
            "extractTableBorders expects a single-channel (grayscale) Mat, but received ${grayMat.channels()} channels."
        }
        val edgesMat = Mat()
        val linesMat = Mat()

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

        Imgproc.cvtColor(grayMat, matH, Imgproc.COLOR_GRAY2RGB)
        Imgproc.cvtColor(grayMat, matV, Imgproc.COLOR_GRAY2RGB)
        Imgproc.cvtColor(grayMat, matCombined, Imgproc.COLOR_GRAY2RGB)


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

        val proximityThreshold = kotlin.math.max(grayMat.width(), grayMat.height()) * 0.005
        val longestAllowedBacktrackRatio = 0.2 // Assume that the false line (e.g. user made) spans at most 20% of the paper

        // Process using proximity-sorted arrays
        val mergedHorizontal = mergeOrderedTracks(horizontalLines, proximityThreshold, isHorizontal = true, grayMat.width().toDouble() * longestAllowedBacktrackRatio)
        val mergedVertical = mergeOrderedTracks(verticalLines, proximityThreshold, isHorizontal = false, grayMat.height().toDouble() * longestAllowedBacktrackRatio)

        // Length Filtering (Keep components stretching across at least half the target field)
        val minHorizontalLength = grayMat.width() / 2.0
        val minVerticalLength = grayMat.height() / 2.0

        val maxParallelDistanceCoeff = 0.002 // For 4000px picture we allow 8px difference
        val minOverlapSpanCoeff = 0.05 // If two lines overlap over more than the 5% of the image's size we consider them to be the same
        val finalHorizontal = deduplicatePolylines(
            mergedHorizontal.filter { it.length >= minHorizontalLength },
            true,
            grayMat.height().toDouble() * maxParallelDistanceCoeff,
            grayMat.width().toDouble() * minOverlapSpanCoeff
        )
        val finalVertical = deduplicatePolylines(
            mergedVertical.filter { it.length >= minVerticalLength },
            false,
            grayMat.width().toDouble() * maxParallelDistanceCoeff,
            grayMat.height().toDouble() * minOverlapSpanCoeff
        )


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

    // Estimate total spatial length of a specific path array
    private fun getPathLength(path: List<Int>, segments: List<HoughSegment>): Double {
        if (path.isEmpty()) return 0.0
        val firstSeg = segments[path.first()]
        val lastSeg = segments[path.last()]
        return hypot(lastSeg.second.x - firstSeg.first.x, lastSeg.second.y - firstSeg.first.y)
    }

    // Strictly blends the start of a candidate to the end of our current active line and handles appending of what overextends
    private fun checkAndCombineBranch(
        active: PolyLineSegment,
        candidate: HoughSegment,
        maxGapThreshold: Double,
        isHorizontal: Boolean
    ): PolyLineSegment? {
        // 1. Angle verification check
        val candidateAngle = Math.toDegrees(atan2(candidate.second.y - candidate.first.y, candidate.second.x - candidate.first.x))
        val angleDifference = abs(active.angle - candidateAngle) % 180
        val normalizedAngleDiff = minOf(angleDifference, 180 - angleDifference)
        if (normalizedAngleDiff > ANGLE_THRESHOLD)
            return null

        // Extract the final segment of the current line to check overlapping
        val linePoints = active.points
        val lastSegStart = if (linePoints.size >= 2) linePoints[linePoints.size - 2] else active.firstPoint
        val lastSegEnd = active.lastPoint

        // Check if candidate starts near the end of our line (end-to-start gap)
        val endToStartDistance = hypot(candidate.first.x - lastSegEnd.x, candidate.first.y - lastSegEnd.y)
        val isTipToTailMatch = endToStartDistance <= maxGapThreshold

        // Check if candidate starts close enough to our line's last segment (overlapping/parallel lines)
        val overlapDistance = distanceToSegment(candidate.first, lastSegStart, lastSegEnd)
        val isOverlapMatch = overlapDistance <= maxGapThreshold

        if (!isTipToTailMatch && !isOverlapMatch)
            return null

        // Extension method: we will just append the furthest forward reach(Point)
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

    /**
     * Filters out duplicate polylines that overlap on at least [minOverlapSpan].
     * When duplicates are detected, the longer line is kept and the shorter is discarded.
     */
    fun deduplicatePolylines(
        lines: List<PolyLineSegment>,
        isHorizontal: Boolean,
        maxParallelDistance: Double,
        minOverlapSpan: Double
    ): List<PolyLineSegment> {
        if (lines.isEmpty()) return emptyList()

        // Sort by length descending so we always evaluate longer lines first
        val sortedByLength = lines.sortedByDescending { it.length }
        val keptLines = mutableListOf<PolyLineSegment>()

        for (candidate in sortedByLength) {
            var isDuplicate = false

            for (existing in keptLines) {
                val relation = evaluateOverlapRelation(
                    candidate = candidate,
                    existing = existing,
                    isHorizontal = isHorizontal,
                    maxParallelDistance = maxParallelDistance,
                    minOverlapSpan = minOverlapSpan
                )

                if (relation != OverlapType.NONE) {
                    // Log suspicious extension patterns for debugging/inspection
                    when (relation) {
                        OverlapType.OVERLAP_DIVERGENCE -> {
                            Log.w("LineDetector", "⚠️ [OVERLAP_DIVERGENCE] Lines overlap partially but drift off-axis! ")
                        }
                        OverlapType.MUTUAL_EXTENSION -> {
                            Log.w("LineDetector", "⚠️ [MUTUAL_EXTENSION] Lines overlap in middle but extend in opposite directions! Candidate length: ${candidate.length}, Existing length: ${existing.length}")
                        }
                        else -> { /* CONTAINED or ONE_ENDED_EXTENSION — completely expected */ }
                    }

                    // Since 'existing' is guaranteed to be longer (we sorted descending), drop candidate
                    isDuplicate = true
                    break
                }
            }

            if (!isDuplicate) {
                keptLines.add(candidate)
            }
        }

        return keptLines
    }

    private fun evaluateOverlapRelation(
        candidate: PolyLineSegment,
        existing: PolyLineSegment,
        isHorizontal: Boolean,
        maxParallelDistance: Double,
        minOverlapSpan: Double
    ): OverlapType {
        // Determine 1D bounding ranges along the primary trajectory axis
        val candStart = if (isHorizontal) minOf(candidate.firstPoint.x, candidate.lastPoint.x) else minOf(candidate.firstPoint.y, candidate.lastPoint.y)
        val candEnd   = if (isHorizontal) maxOf(candidate.firstPoint.x, candidate.lastPoint.x) else maxOf(candidate.firstPoint.y, candidate.lastPoint.y)

        val existStart = if (isHorizontal) minOf(existing.firstPoint.x, existing.lastPoint.x) else minOf(existing.firstPoint.y, existing.lastPoint.y)
        val existEnd   = if (isHorizontal) maxOf(existing.firstPoint.x, existing.lastPoint.x) else maxOf(existing.firstPoint.y, existing.lastPoint.y)

        // Calculate axis overlap boundary bounds
        val overlapStart = maxOf(candStart, existStart)
        val overlapEnd = minOf(candEnd, existEnd)
        val overlapSpan = overlapEnd - overlapStart

        // Must overlap along the main axis by at least minOverlapSpan of image size
        if (overlapSpan < minOverlapSpan) return OverlapType.NONE

        // Verify perpendicular proximity across the overlap span to make sure they run parallel
        val sampleStep = maxOf(1.0, overlapSpan / OVERLAP_SPAN_SAMPLE_COUNT)
        var sampledPoints = 0
        var withinProximityCount = 0

        var pos = overlapStart
        while (pos <= overlapEnd) {
            val candOffAxis = candidate.getOffAxisCoordinateAt(pos, isHorizontal)
            val existOffAxis = existing.getOffAxisCoordinateAt(pos, isHorizontal)

            if (candOffAxis != null && existOffAxis != null) {
                sampledPoints++
                if (abs(candOffAxis - existOffAxis) <= maxParallelDistance) {
                    withinProximityCount++
                }
            }
            pos += sampleStep
        }

        if (sampledPoints == 0) {
            return OverlapType.NONE
        }
        val proximityRatio = (withinProximityCount.toDouble() / sampledPoints)
        // If less than MIN_PROXIMITY_MATCH_RATIO of sampled points are within proximity, they diverged spatially
        if (proximityRatio < MIN_PROXIMITY_MATCH_RATIO) {
            // If those lines were similar on a span of MIN_PROXIMITY_MATCH_RATIO * minOverlapSpan but not all of it, then it is suspicious
            return if (withinProximityCount * sampleStep > MIN_PROXIMITY_MATCH_RATIO * minOverlapSpan)
                OverlapType.OVERLAP_DIVERGENCE
            else
                OverlapType.NONE
        }

        // --- CLASSIFY EXTENSION PATTERN ---
        val startDiff = candStart - existStart
        val endDiff = candEnd - existEnd

        return when {
            // Candidate is completely inside existing (e.g. cand = BC, exist = ABCD)
            candStart >= existStart - ENDPOINT_MATCH_TOLERANCE_PX && candEnd <= existEnd + ENDPOINT_MATCH_TOLERANCE_PX -> {
                OverlapType.CONTAINED
            }

            // Overhanging on opposite ends (e.g. cand = ABC, exist = BCD)
            (startDiff < -ENDPOINT_MATCH_TOLERANCE_PX && endDiff > ENDPOINT_MATCH_TOLERANCE_PX) ||
                    (startDiff > ENDPOINT_MATCH_TOLERANCE_PX && endDiff < -ENDPOINT_MATCH_TOLERANCE_PX) -> {
                OverlapType.MUTUAL_EXTENSION
            }

            // Aligned on one end, extended on the other (e.g. cand = BCF, exist = BCD)
            else -> {
                OverlapType.ONE_ENDED_EXTENSION
            }
        }
    }

    private fun distanceToSegment(p: Point, segA: Point, segB: Point): Double {
        val dx = segB.x - segA.x
        val dy = segB.y - segA.y

        if (dx == 0.0 && dy == 0.0) {
            return hypot(p.x - segA.x, p.y - segA.y)
        }

        val t = ((p.x - segA.x) * dx + (p.y - segA.y) * dy) / (dx * dx + dy * dy)

        return when {
            t < 0.0 -> hypot(p.x - segA.x, p.y - segA.y)
            t > 1.0 -> hypot(p.x - segB.x, p.y - segB.y)
            else -> {
                val projectionX = segA.x + t * dx
                val projectionY = segA.y + t * dy
                return hypot(p.x - projectionX, p.y - projectionY)
            }
        }
    }
}