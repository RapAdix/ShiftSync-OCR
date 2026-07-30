package com.example.workflowocr

import android.util.Log
import org.opencv.core.Mat
import org.opencv.core.Point
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
}

class TooManyLinesException(
    message: String,
    val horizontal: List<HoughSegment>,
    val vertical: List<HoughSegment>
) : Exception(message)

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
    const val MAX_MERGE_SET_SIZE = 6000 // Maximum size of the Hough segments to allow computing MergeLines in a reasonable time.

    fun extractTableLines(grayMat: Mat): Pair<List<PolyLineSegment>, List<PolyLineSegment>> {
        require(grayMat.channels() == 1) {
            "extractTableBorders expects a single-channel (grayscale) Mat, but received ${grayMat.channels()} channels."
        }
        val linesMat = Mat()

        // Probabilistic Hough Transform (Extracting structural fragments)
        Imgproc.HoughLinesP(grayMat, linesMat, 1.0, Math.PI / 180.0, 20, 35.0, 20.0)

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

        // Check for too complex(time consuming) computation and terminate early based on the lists size
        if (horizontalLines.size > MAX_MERGE_SET_SIZE || verticalLines.size > MAX_MERGE_SET_SIZE) {
            linesMat.release()
            throw TooManyLinesException(
                "Background is too noisy (${horizontalLines.size} horizontal lines, ${verticalLines.size} vertical lines). Please take picture on a plain background closer to the paper sheet.",
                horizontalLines,
                verticalLines
            )
        }

        val proximityThreshold = kotlin.math.max(grayMat.width(), grayMat.height()) * 0.005
        val longestAllowedBacktrackRatio = 0.1 // Assume that the false line (e.g. user made) spans at most 20% of the paper

        // Process using proximity-sorted arrays
        val mergedHorizontal = mergeOrderedTracks(horizontalLines, proximityThreshold, isHorizontal = true, grayMat.width().toDouble() * longestAllowedBacktrackRatio)
        val mergedVertical = mergeOrderedTracks(verticalLines, proximityThreshold, isHorizontal = false, grayMat.height().toDouble() * longestAllowedBacktrackRatio)

        // Length Filtering (Keep components stretching across at least 10% the target field)
        val minHorizontalLength = grayMat.width() * 0.1
        val minVerticalLength = grayMat.height() * 0.1
        val filteredHorizontal = mergedHorizontal.filter { it.length >= minHorizontalLength }
        val filteredVertical = mergedVertical.filter { it.length >= minVerticalLength }

        val maxParallelDistanceCoeff = 0.002 // For 4000px picture we allow 8px difference
        val minOverlapSpanCoeff = 0.05 // If two lines overlap over more than the 5% of the image's size we consider them to be the same
        val finalHorizontal = deduplicatePolylines(
            filteredHorizontal,
            true,
            grayMat.height().toDouble() * maxParallelDistanceCoeff,
            grayMat.width().toDouble() * minOverlapSpanCoeff
        )
        val finalVertical = deduplicatePolylines(
            filteredVertical,
            false,
            grayMat.width().toDouble() * maxParallelDistanceCoeff,
            grayMat.height().toDouble() * minOverlapSpanCoeff
        )
        //TODO Add connecting of lines that seem to be the same line separated by a gap

        // Garbage collection manual release
        linesMat.release()

        return Pair(finalHorizontal, finalVertical)
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

            // Collect the indices of the path segments used in the longest branching solution
            val bestPathIndices = mutableListOf<Int>()

            // Cache to store visited tail configurations for this specific root line branch traversal
            val visitedTails = HashSet<Pair<Point, Point>>()

            findLongestBranch(
                currentIndex = i,
                previousIndex = i, // Initially anchored to itself
                availableSegments = sortedWorkingList,
                usedIndices = usedIndices,
                currentPath = mutableListOf(i),
                bestPath = bestPathIndices,
                visitedTails = visitedTails,
                distanceThreshold = distanceThreshold,
                isHorizontal = isHorizontal,
                longestAllowedBacktrack = longestAllowedBacktrack
            )

            if (bestPathIndices.isEmpty()) bestPathIndices.add(i)
            val bestMergedPoints = buildList {
                add(sortedWorkingList[bestPathIndices.first()].first)
                addAll(bestPathIndices.map { sortedWorkingList[it].second })
            }

            val bestMergedLine = PolyLineSegment(bestMergedPoints.toMutableList())

            completedPool.add(bestMergedLine)
            // Permanently consume the winning path components from the pool
            usedIndices.addAll(bestPathIndices)
        }

        return completedPool
    }

    // Depth-First Search with backtracking to find the path combination yielding the longest line
    private fun findLongestBranch(
        currentIndex: Int,
        previousIndex: Int,
        availableSegments: List<HoughSegment>,
        usedIndices: Set<Int>,
        currentPath: MutableList<Int>, // track indices to permanently consume them after merging whole line
        bestPath: MutableList<Int>,
        visitedTails: HashSet<Pair<Point, Point>>, // track last sections for each started merge to prune what we already checked
        distanceThreshold: Double,
        isHorizontal: Boolean,
        longestAllowedBacktrack: Double
    ) {

        val bestPathLength = getPathLength(bestPath, availableSegments)
        val currentPathLength = getPathLength(currentPath, availableSegments)
        // If this is the first execution or we found a path that beats our previous global maximum length, record it
        if (bestPath.isEmpty() || currentPathLength > bestPathLength) {
            bestPath.clear()
            bestPath.addAll(currentPath)
        }

        // Early termination
        if (bestPathLength - currentPathLength > longestAllowedBacktrack &&
            bestPath[bestPath.size - 2] != currentPath.last()) // If we are one step behind best then allow backtrack even if distance breached
            return

        // Extract the tail trajectory of the current path
        val lastSeg = availableSegments[currentPath.last()]
        val lastSegStart = if (currentPath.size >= 2) {
            availableSegments[currentPath[currentPath.size - 2]].second
        } else {
            lastSeg.first
        }
        val lastSegEnd = lastSeg.second

        // Prune branch if this exact tail trajectory was already evaluated under this root search
        // Because all the logic is dependant at most at the last section of PolyLine so if it was visited before
        // for the current merge then there is no need to check it again since nothing changed
        val tailKey = Pair(lastSegStart, lastSegEnd)
        if (!visitedTails.add(tailKey)) {
            return
        }

        // Scan from the index of the previously attached segment
        // to count for segments which became available because of recently filled gap.
        // Skip previous segments cause they will be considered in parallel dfs.
        val searchStartIndex = previousIndex

        for (nextIdx in searchStartIndex until availableSegments.size) {
            if (usedIndices.contains(nextIdx) || currentPath.contains(nextIdx)) continue

            val candidate = availableSegments[nextIdx]

            // Forward early-termination check using activeLine bounds
            if (isHorizontal) {
                if (candidate.first.x > lastSegEnd.x + distanceThreshold) break
            } else {
                if (candidate.first.y > lastSegEnd.y + distanceThreshold) break
            }

            // Verify connection compatibility at the boundary endpoint or overlapping trajectory tracks
            if (checkAndCombineBranch(currentPath, candidate, availableSegments, distanceThreshold, isHorizontal)) {
                currentPath.add(nextIdx)

                findLongestBranch(
                    currentIndex = nextIdx,
                    previousIndex = currentIndex, // Updates lookback to be anchored to the index of the segment we just attached
                    availableSegments = availableSegments,
                    usedIndices = usedIndices,
                    currentPath = currentPath,
                    bestPath = bestPath,
                    visitedTails = visitedTails,
                    distanceThreshold = distanceThreshold,
                    isHorizontal = isHorizontal,
                    longestAllowedBacktrack = longestAllowedBacktrack
                )

                // Backtrack to try alternative branches
                currentPath.removeAt(currentPath.size - 1)
            }
        }

        return
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
        currentPath: List<Int>,
        candidate: HoughSegment,
        availableSegments: List<HoughSegment>,
        maxGapThreshold: Double,
        isHorizontal: Boolean
    ): Boolean {
        val lastSeg = availableSegments[currentPath.last()]

        if (isHorizontal && candidate.second.x <= lastSeg.second.x)
            return false
        if (!isHorizontal && candidate.second.y <= lastSeg.second.y)
            return false

        // Extract the final segment of the current line to check overlapping
        val lastSegStart = if (currentPath.size >= 2) {
            availableSegments[currentPath[currentPath.size - 2]].second
        } else {
            lastSeg.first
        }
        val lastSegEnd = lastSeg.second

        // 1. Angle verification check
        val activeAngle = Math.toDegrees(atan2(lastSegEnd.y - lastSegStart.y, lastSegEnd.x - lastSegStart.x))
        val candidateAngle = Math.toDegrees(atan2(candidate.second.y - candidate.first.y, candidate.second.x - candidate.first.x))
        val angleDifference = abs(activeAngle - candidateAngle) % 180
        val normalizedAngleDiff = minOf(angleDifference, 180 - angleDifference)
        if (normalizedAngleDiff > ANGLE_THRESHOLD)
            return false

        // Check if candidate starts near the end of our line (end-to-start gap)
        val endToStartDistance = hypot(candidate.first.x - lastSegEnd.x, candidate.first.y - lastSegEnd.y)
        val isTipToTailMatch = endToStartDistance <= maxGapThreshold

        // Check if candidate starts close enough to our line's last segment (overlapping/parallel lines)
        val overlapDistance = distanceToSegment(candidate.first, lastSegStart, lastSegEnd)
        val isOverlapMatch = overlapDistance <= maxGapThreshold

        return isTipToTailMatch || isOverlapMatch
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

/**
 * Interpolates the off-axis coordinate (Y for horizontal, X for vertical) at a specific main-axis position.
 */
fun PolyLineSegment.getOffAxisCoordinateAt(
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

/**
 * Returns the first 2D intersection point between two [PolyLineSegment]s,
 * or `null` if they do not intersect.
 */
fun PolyLineSegment.findIntersection(other: PolyLineSegment): Point? {
    val pts1 = this.points
    val pts2 = other.points

    for (i in 0 until pts1.size - 1) {
        for (j in 0 until pts2.size - 1) {
            val intersection = segmentIntersection(
                pts1[i], pts1[i + 1],
                pts2[j], pts2[j + 1]
            )
            if (intersection != null) {
                return intersection
            }
        }
    }
    return null
}

/**
 * Returns `true` if this [PolyLineSegment] intersects with [other].
 */
fun PolyLineSegment.intersects(other: PolyLineSegment): Boolean {
    return findIntersection(other) != null
}

/**
 * Returns a new PolyLineSegment extended at both ends by adding 2 new boundary points
 * pushed outwards by [extensionPx] along the true trajectory of the line's ends.
 */
fun PolyLineSegment.extendEndpoints(extensionPx: Double): PolyLineSegment {
    if (points.size < 2) return this

    val extendedPoints = points.toMutableList()
    val minVectorLength = 15.0 // Minimum distance in pixels to compute a reliable direction vector

    // 1. Extend Start Endpoint
    val p0 = points[0]
    var startReferencePoint = points[1]

    // Find a point far enough from p0 to avoid micro-jitter/noise
    for (i in 1 until points.size) {
        if (hypot(p0.x - points[i].x, p0.y - points[i].y) >= minVectorLength) {
            startReferencePoint = points[i]
            break
        }
    }

    val dxStart = p0.x - startReferencePoint.x
    val dyStart = p0.y - startReferencePoint.y
    val lenStart = hypot(dxStart, dyStart)

    if (lenStart > 1e-5) {
        val startExtensionPoint = Point(
            p0.x + (dxStart / lenStart) * extensionPx,
            p0.y + (dyStart / lenStart) * extensionPx
        )
        extendedPoints.add(0, startExtensionPoint) // Prepend at start
    }

    // 2. Extend End Endpoint
    val pN = points.last()
    var endReferencePoint = points[points.size - 2]

    // Find a point far enough from pN to avoid micro-jitter/noise
    for (i in points.size - 2 downTo 0) {
        if (hypot(pN.x - points[i].x, pN.y - points[i].y) >= minVectorLength) {
            endReferencePoint = points[i]
            break
        }
    }

    val dxEnd = pN.x - endReferencePoint.x
    val dyEnd = pN.y - endReferencePoint.y
    val lenEnd = hypot(dxEnd, dyEnd)

    if (lenEnd > 1e-5) {
        val endExtensionPoint = Point(
            pN.x + (dxEnd / lenEnd) * extensionPx,
            pN.y + (dyEnd / lenEnd) * extensionPx
        )
        extendedPoints.add(endExtensionPoint) // Append at end
    }

    return PolyLineSegment(extendedPoints)
}

/**
 * Reverts an extended PolyLineSegment back to its original form by removing
 * the newly added outer extension points at the start and end.
 */
fun PolyLineSegment.trimExtendedEndpoints(): PolyLineSegment {
    if (points.size <= 2) return this
    return PolyLineSegment(points.subList(1, points.size - 1))
}

/**
 * 2D Line Segment Intersection using cross products.
 */
private const val INTERSECTION_EPSILON = 1e-7

/**
 * 2D Line Segment Intersection with epsilon boundary checks for exact endpoint crossings.
 */
private fun segmentIntersection(p1: Point, p2: Point, p3: Point, p4: Point): Point? {
    val dx12 = p2.x - p1.x
    val dy12 = p2.y - p1.y
    val dx34 = p4.x - p3.x
    val dy34 = p4.y - p3.y

    // 2D Cross product determinant
    val denominator = dx12 * dy34 - dy12 * dx34

    // Parallel or collinear segments
    if (abs(denominator) < 1e-9) return null

    val dx31 = p1.x - p3.x
    val dy31 = p1.y - p3.y

    // Parametric ratios along segment 1 (t) and segment 2 (u)
    val t = (dx34 * dy31 - dy34 * dx31) / denominator
    val u = (dx12 * dy31 - dy12 * dx31) / denominator

    // Include EPSILON tolerance to handle floating-point precision at section endpoints
    val minBound = -INTERSECTION_EPSILON
    val maxBound = 1.0 + INTERSECTION_EPSILON

    return if (t in minBound..maxBound && u in minBound..maxBound) {
        // Clamp t to [0, 1] to prevent sub-pixel drift outside endpoint coordinates
        val clampedT = t.coerceIn(0.0, 1.0)
        Point(
            p1.x + clampedT * dx12,
            p1.y + clampedT * dy12
        )
    } else {
        null
    }
}

/**
 * Rotates a PolyLineSegment 90 degrees counter-clockwise.
 *
 * Target transformation: (x, y) -> (y, imgWidth - 1 - x)
 * @param imgWidth Width of the image BEFORE rotation.
 */
fun PolyLineSegment.rotate90CounterClockwise(imgWidth: Int): PolyLineSegment {
    val rotatedPoints = points.map { pt ->
        Point(pt.y, (imgWidth - 1).toDouble() - pt.x)
    }.toMutableList()
    return PolyLineSegment(rotatedPoints)
}

/**
 * Rotates a PolyLineSegment 180 degrees.
 *
 * Target transformation: (x, y) -> (imgWidth - 1 - x, imgHeight - 1 - y)
 * @param imgWidth Width of the image BEFORE rotation.
 * @param imgHeight Height of the image BEFORE rotation.
 */
fun PolyLineSegment.rotate180(imgWidth: Int, imgHeight: Int): PolyLineSegment {
    val rotatedPoints = points.map { pt ->
        Point((imgWidth - 1).toDouble() - pt.x, (imgHeight - 1).toDouble() - pt.y)
    }.toMutableList()
    return PolyLineSegment(rotatedPoints)
}