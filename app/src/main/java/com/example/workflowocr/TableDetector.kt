package com.example.workflowocr

import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

object TableDetector {

    data class TableCell(
        val topLeft: Point,
        val topRight: Point,
        val bottomLeft: Point,
        val bottomRight: Point
    )

    data class TableDetectionResult(
        val cells: Array<Array<TableCell>>,
        val thresh: Mat,
        val mask: Mat,
        val lines: Mat
    )

    class MissingTopRowException(message: String) : Exception(message)

    private val minRequiredIntersectionsCoeff : Double = 0.5

    private val expectedRows = 38
    private val expectedCols = 12
    private val headerRowHeightMultiplier = 3 // ratio of height between header_row / normal_row  // TODO put correct constant

    private val expectedYBelts = expectedRows + 1
    private val expectedXBelts = expectedCols + 1

    /**
     * Input: a grayscale Mat - rotates it if incorrect table orientation detected
     * Output: Array of rectangles representing detected cells
     */
    fun detectTableCells(gray: Mat): TableDetectionResult {

        // 1. Create a mask of the "Paper" area,
        // // Background is pure black (0) after rotation
        val validMask = Mat()
        Imgproc.threshold(gray, validMask, 1.0, 255.0, Imgproc.THRESH_BINARY)

        // Erode the mask to "shrink" the valid area away from the edges
        // This ensures that after rotation the high-contrast transition at the image edge is ignored.
        val maskErosionSize = 6.0 // px
        val maskElement = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(maskErosionSize, maskErosionSize))
        Imgproc.erode(validMask, validMask, maskElement)

        val thresh = Mat()
        // Apply adaptive threshold to get binary image
        Imgproc.adaptiveThreshold(
            gray, thresh, 255.0,
            Imgproc.ADAPTIVE_THRESH_MEAN_C,
            Imgproc.THRESH_BINARY_INV,
            15, 10.0
        )
        Log.d("DEBUG", ">> thresh size = ${thresh.rows()} x ${thresh.cols()}")
        Log.d("DEBUG", "thresh nonZero = ${Core.countNonZero(thresh)}")

        Core.bitwise_and(thresh, validMask, thresh)

        // Cleanup mask
        validMask.release()
        maskElement.release()

        // Detect horizontal and vertical lines separately
        val horizontal = Mat(thresh.size(), CvType.CV_8UC1)
        val vertical = Mat(thresh.size(), CvType.CV_8UC1)
        horizontal.setTo(Scalar(0.0))
        vertical.setTo(Scalar(0.0))

        val lineThickness = 1.0 // approximate line thickness in pixels
        val lineLength = 30.0

        // Horizontal lines
        val horizontalStructure = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(lineLength, lineThickness))
        Imgproc.erode(thresh, horizontal, horizontalStructure)
        Imgproc.dilate(horizontal, horizontal, horizontalStructure)
//        Imgproc.morphologyEx(horizontal, horizontal, Imgproc.MORPH_CLOSE, horizontalStructure)

        // Vertical lines
        val verticalStructure = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(lineThickness, lineLength))
        Imgproc.erode(thresh, vertical, verticalStructure)
        Imgproc.dilate(vertical, vertical, verticalStructure)
//        Imgproc.morphologyEx(vertical, vertical, Imgproc.MORPH_CLOSE, verticalStructure)

        // Now close small gaps created at the intersection of lines by threshold // TODO I think it creates false text/pen intersections with table lines
        val structure = Mat()
        Core.bitwise_or(horizontal, vertical, structure)
        val closelineLength = lineLength / 2
        val closeHorizontalStructure = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(closelineLength, lineThickness))
        val closeVerticalStructure = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(lineThickness, closelineLength))
        Imgproc.morphologyEx(structure, structure, Imgproc.MORPH_CLOSE, closeVerticalStructure)
        Imgproc.morphologyEx(structure, structure, Imgproc.MORPH_CLOSE, closeHorizontalStructure)

        // Detect lines again
        Imgproc.erode(structure, horizontal, horizontalStructure)
        Imgproc.dilate(horizontal, horizontal, horizontalStructure)
        Imgproc.erode(structure, vertical, verticalStructure)
        Imgproc.dilate(vertical, vertical, verticalStructure)

        structure.release()

        val (cells, linesDrawing, rotations) = findRefinedCorners(horizontal, vertical)
        // Rotate source based on what rotations were performed during table detection.
        if (rotations % 4 != 0) {
            rotateMatNSteps(gray, rotations)
            rotateMatNSteps(thresh, rotations)
            rotateMatNSteps(horizontal, rotations)
            rotateMatNSteps(vertical, rotations)
        }

        val linesOverlayImage = gray.clone()
        if (linesOverlayImage.channels() == 1) {
            Imgproc.cvtColor(linesOverlayImage, linesOverlayImage, Imgproc.COLOR_GRAY2RGB)
        }
        val linesMask = Mat()
        Imgproc.cvtColor(linesDrawing, linesMask, Imgproc.COLOR_BGR2GRAY)
        Imgproc.threshold(linesMask, linesMask, 1.0, 255.0, Imgproc.THRESH_BINARY)
        linesDrawing.copyTo(linesOverlayImage, linesMask)

        linesDrawing.release()
        linesMask.release()

        // Combine horizontal and vertical lines to get table mask
        val mask = Mat()
        Core.add(horizontal, vertical, mask)
        Log.d("DEBUG", ">> mask size = ${mask.rows()} x ${mask.cols()}")
        Log.d("DEBUG", "mask nonZero = ${Core.countNonZero(mask)}")

        horizontal.release()
        vertical.release()

        horizontalStructure.release()
        verticalStructure.release()

        // Sort cells by row, then column
        return TableDetectionResult(
            cells,
            thresh,
            mask,
            linesOverlayImage
        )
    }

    /**
     * Detects table corners by combining global projections with local intersections
     * to filter out noise (like pen strokes).
     */
    fun findRefinedCorners(horizontal: Mat, vertical: Mat): Triple<Array<Array<TableCell>>, Mat, Int> {
        // Get Global Projections to find "Line Belts"
        val rowSums = Mat()
        val colSums = Mat()
        Core.reduce(horizontal, rowSums, 1, Core.REDUCE_SUM, CvType.CV_32F)
        Core.reduce(vertical, colSums, 0, Core.REDUCE_SUM, CvType.CV_32F)

        // Determine Thresholds (3x Average)
        val avgRow = Core.mean(rowSums).`val`[0]
        val avgCol = Core.mean(colSums).`val`[0]
        val rowThresh = avgRow * 1.5
        val colThresh = avgCol * 1.5
        Log.d("DEBUG", "avgRow = $avgRow")
        Log.d("DEBUG", "avgCol = $avgCol")

        // Identify Candidate X and Y positions (Line Belts)
        // rawYBelts[0] has Y pixel position of the horizontal table line.
        var rawYBelts = getBeltCenters(rowSums, rowThresh, true)
        var rawXBelts = getBeltCenters(colSums, colThresh, false)

        Log.d("DEBUG", "counted ${rawYBelts.size} potential rows")
        Log.d("DEBUG", "counted ${rawXBelts.size} potential cols")

        // Find Local Intersections (Actual crossings)
        val intersections = Mat()
        Core.bitwise_and(horizontal, vertical, intersections)

        var intersectionsList = listOfIntersections(intersections)
        var rawGrid = gridFromClosestIntersections(intersectionsList, rawXBelts, rawYBelts)

        // First, orientation check
        var rotations = 0 // we will pass the number of 90 degree counter-clockwise rotations up the call stack
        // Filter out fake belts(paper edges, pencil strokes..)
        val filteredXBelts = filterBeltsByDensity(rawGrid, rawXBelts, -1, isHorizontal = false)
        val filteredYBelts = filterBeltsByDensity(rawGrid, rawYBelts, -1, isHorizontal = true)
        if (isLayingOnSide(filteredXBelts, filteredYBelts)) {
            Log.d("DEBUG", "Rotating 90 degrees.")
            rotations++
            val rotatedBelts = rotateBelts90(rawXBelts, rawYBelts, horizontal.cols())
            rawXBelts = rotatedBelts.first
            rawYBelts = rotatedBelts.second
            intersectionsList = rotatePoints90(intersectionsList, horizontal.cols())
            Core.rotate(intersections, intersections, Core.ROTATE_90_COUNTERCLOCKWISE) // needed in TablePropagator
            rawGrid = gridFromClosestIntersections(intersectionsList, rawXBelts, rawYBelts)
        }
        var validXBelts = filterBeltsByDensity(rawGrid, rawXBelts, expectedXBelts, isHorizontal = false)
        var validYBelts = filterBeltsByDensity(rawGrid, rawYBelts, -1, isHorizontal = true)
        val propagatedYBelts = try {
            propagateHorizontalBeltsByStructure(rawYBelts, validYBelts, expectedYBelts)
        } catch (e: MissingTopRowException) {
            Log.d("DEBUG", "Couldn't detect top header row during propagation. Rotating by 180 degrees.")
            rotations += 2

            val rotatedValid = rotateBelts180(validXBelts, validYBelts, horizontal.cols(), horizontal.rows())
            validXBelts = rotatedValid.first
            validYBelts = rotatedValid.second
            val rotatedRaw = rotateBelts180(rawXBelts, rawYBelts, horizontal.cols(), horizontal.rows())
            rawXBelts = rotatedRaw.first
            rawYBelts = rotatedRaw.second
            intersectionsList = rotatePoints180(intersectionsList, horizontal.cols(), horizontal.rows())
            Core.rotate(intersections, intersections, Core.ROTATE_180) // needed in TablePropagator
            try {
                propagateHorizontalBeltsByStructure(rawYBelts, validYBelts, expectedYBelts)
            } catch (e: MissingTopRowException) {
                Log.d("DEBUG", "Error: Couldn't detect top header row during propagation even after rotating!")
                validYBelts
            }
        }

        // Put it again through grid creation because we removed fake belts
        val cleanedGrid = gridFromClosestIntersections(intersectionsList, validXBelts, propagatedYBelts)

        val propagator = TablePropagator()
        val propagatedGrid = propagator.propagateRobustGrid(intersections, validXBelts, propagatedYBelts, cleanedGrid)

        val cells = Array(propagatedGrid.size - 1) { r ->
            Array(propagatedGrid[0].size - 1) { c ->
                TableCell(
                    topLeft = propagatedGrid[r][c],
                    topRight = propagatedGrid[r][c + 1],
                    bottomLeft = propagatedGrid[r + 1][c],
                    bottomRight = propagatedGrid[r + 1][c + 1]
                )
            }
        }

        val linesDebugMat = Mat.zeros(horizontal.size(), CvType.CV_8UC3)

        // 2. horizontal on RED
        for (y in rawYBelts) {
            val pt1 = Point(0.0, y.toDouble())
            val pt2 = Point(horizontal.cols().toDouble(), y.toDouble())
            Imgproc.line(linesDebugMat, pt1, pt2, Scalar(0.0, 0.0, 255.0), 2)
        }

        // vertical BLUE
        for (x in rawXBelts) {
            val pt1 = Point(x.toDouble(), 0.0)
            val pt2 = Point(x.toDouble(), horizontal.rows().toDouble())
            Imgproc.line(linesDebugMat, pt1, pt2, Scalar(255.0, 0.0, 0.0), 2)
        }

        // Cleanup
        rowSums.release()
        colSums.release()
        intersections.release()

        return Triple(cells, linesDebugMat, rotations)
    }

    fun getBeltCenters(sums: Mat, threshold: Double, isRow: Boolean): List<Int> {
        val size = if (isRow) sums.rows() else sums.cols()
        val activeIndices = mutableListOf<Int>()

        // 1. Collect all indices above threshold
        for (i in 0 until size) {
            val value = if (isRow) sums.get(i, 0)[0] else sums.get(0, i)[0]
            if (value > threshold) activeIndices.add(i)
        }

        if (activeIndices.isEmpty()) return emptyList()

        // 2. Group consecutive indices and find their middle
        val centers = mutableListOf<Int>()
        var group = mutableListOf<Int>()
        group.add(activeIndices[0])

        for (i in 1 until activeIndices.size) {
            // If current index is continuous (difference of 1 or 2 pixels)
            if (activeIndices[i] - activeIndices[i - 1] <= 2) {
                group.add(activeIndices[i])
            } else {
                // End of group, take the middle index
                centers.add(group[group.size / 2])
                group = mutableListOf(activeIndices[i])
            }
        }
        // Don't forget the last group
        centers.add(group[group.size / 2])

        return centers
    }

    private fun isLayingOnSide(xBelts: List<Int>, yBelts: List<Int>): Boolean {
        if (xBelts.size < 2 || yBelts.size < 2) return false

        // Calculate all gap sizes
        val xGaps = xBelts.sorted().zipWithNext { a, b -> b - a }
        val yGaps = yBelts.sorted().zipWithNext { a, b -> b - a }

        // Use the Median instead of Average to be robust against:
        // - The "One Tall Header Row"
        // - Massive gaps from missing rows in the middle
        // - Paper edge noise
        val medianColWidth = xGaps.sorted()[xGaps.size / 2].toDouble()
        val medianRowHeight = yGaps.sorted()[yGaps.size / 2].toDouble()

        Log.d("DEBUG", "Orientation check: medianColWidth=$medianColWidth, medianRowHeight=$medianRowHeight")

        // Usually, Column Width > Row Height in standard landscape cells.
        // We use a small threshold (1.2) to ensure it's a clear rotation, not just a square cell.
        return medianRowHeight > (medianColWidth * 1.2)
    }

    private fun rotateMatNSteps(mat: Mat, steps: Int) {
        val count = steps % 4

        val code = when (count) {
            1 -> Core.ROTATE_90_COUNTERCLOCKWISE
            2 -> Core.ROTATE_180
            3 -> Core.ROTATE_90_CLOCKWISE
            else -> -1
        }
        if (code != -1) {
            Core.rotate(mat, mat, code)
        }
    }

    /**
     * Rotates belt coordinates 90 degrees Counter-Clockwise.
     * newX = oldY
     * newY = imageWidth - oldX
     */
    private fun rotateBelts90(xBelts: List<Int>, yBelts: List<Int>, imgWidth: Int): Pair<List<Int>, List<Int>> {
        val newX = yBelts.sorted()
        val newY = xBelts.map { imgWidth - it }.sorted()
        return Pair(newX, newY)
    }

    /**
     * Rotates belt coordinates 180 degrees.
     * newX = imgWidth - oldX
     * newY = imgHeight - oldY
     */
    private fun rotateBelts180(xBelts: List<Int>, yBelts: List<Int>, imgWidth: Int, imgHeight: Int): Pair<List<Int>, List<Int>> {
        val newX = xBelts.map { imgWidth - it }.sorted()
        val newY = yBelts.map { imgHeight - it }.sorted()
        return Pair(newX, newY)
    }

    // Transforms points based on a 90-degree Counter-Clockwise rotation.
    fun rotatePoints90(points: List<Point>, imgWidth: Int): List<Point> {
        return points.map { Point(it.y, imgWidth.toDouble() - it.x) }
    }

    // Transforms points for a 180-degree rotation.
    fun rotatePoints180(points: List<Point>, imgWidth: Int, imgHeight: Int): List<Point> {
        return points.map { Point(imgWidth.toDouble() - it.x, imgHeight.toDouble() - it.y) }
    }

    private fun listOfIntersections(intersections: Mat): List<Point> {
        val jointContours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(intersections, jointContours, Mat(), Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)

        return jointContours.mapNotNull { contour ->
            val moments = Imgproc.moments(contour)
            if (moments.m00 > 0) {
                val cx = moments.m10 / moments.m00
                val cy = moments.m01 / moments.m00
                Point(cx, cy)
            } else {
                null
            }
        }
    }

    private fun gridFromClosestIntersections(
        intersections: List<Point>,
        validXBelts: List<Int>,
        validYBelts: List<Int>,
    ): Array<Array<Point?>> {
        val rowEdgeCount = validYBelts.size
        val colEdgeCount = validXBelts.size

        // Create a 2D Array to store the best point for each intersection
        // Points are nullable because some intersections might be missing
        val grid = Array(rowEdgeCount) { arrayOfNulls<Point>(colEdgeCount) }

        // Tolerance for perspective/noise
        val tolerance = 15.0

        for (intersection in intersections) {
            val cx = intersection.x
            val cy = intersection.y
            // Find the closest index and check if it's within tolerance
            val rowIndex = validYBelts.indices
                .minByOrNull { Math.abs(validYBelts[it] - cy) }
                ?.takeIf { Math.abs(validYBelts[it] - cy) < tolerance } ?: -1

            val colIndex = validXBelts.indices
                .minByOrNull { Math.abs(validXBelts[it] - cx) }
                ?.takeIf { Math.abs(validXBelts[it] - cx) < tolerance } ?: -1

            if (rowIndex != -1 && colIndex != -1) {
                val point = Point(cx, cy)
                val currentBest = grid[rowIndex][colIndex]

                if (currentBest == null) {
                    grid[rowIndex][colIndex] = point
                } else {
                    // Compare distances to the ideal "Global Belt" intersection
                    val distNew = Math.hypot(cx - validXBelts[colIndex], cy - validYBelts[rowIndex])
                    val distOld = Math.hypot(currentBest.x - validXBelts[colIndex], currentBest.y - validYBelts[rowIndex])

                    if (distNew < distOld) {
                        grid[rowIndex][colIndex] = point
                    }
                }
            }
        }
        return grid
    }

    // Assumes that the image is properly fully rotated
    private fun propagateHorizontalBeltsByStructure(
        rawYBelts: List<Int>,
        validYBelts: List<Int>,
        expectedRows: Int
    ): List<Int> {
        val detectionErrorMargin = 10 // px of difference between rows height is considered sane
        val gaps = mutableListOf<Int>()
        for (i in 0 until validYBelts.size - 1) gaps.add(validYBelts[i+1] - validYBelts[i])

        // For each gap, count how many other gaps are within tolerance 'detectionErrorMargin' pixels
        // The gap with the highest count is our "Mode"
        val bestGap = gaps.maxByOrNull { g ->
            gaps.count { abs(it - g) <= detectionErrorMargin }
        } ?: gaps[0]

        // To get a more precise value, average all gaps that fell into this cluster
        val cluster = gaps.filter { Math.abs(it - bestGap) <= detectionErrorMargin }
        val rowHeight = cluster.average().toInt()
        if (cluster.size * 2 < gaps.size)
            Log.d(
                "DEBUG",
                "Warning: Only ${cluster.size} rows has similar heights of $rowHeight +- $detectionErrorMargin out of all initially detected ${gaps.size} rows"
            )

        val propagatedBelts = mutableListOf<Int>()
        val tallRowHeight = rowHeight * headerRowHeightMultiplier

        // ---- Adding rows before ----
        if (gaps[0] < tallRowHeight - detectionErrorMargin) { // first row is missing
            // We will add any possible normal size rows above of what was found
            // Until we manage to add the Tall Header Row
            var currentTop = validYBelts[0]
            // Look for rows above until we find tall Header or reach limit
            while (propagatedBelts.size < expectedRows) {

                // Try to find a standard small row above the current top
                val predictedSmall = currentTop - rowHeight
                val rawSmall = rawYBelts
                    .filter { Math.abs(it - predictedSmall) < detectionErrorMargin }
                    .minByOrNull { Math.abs(it - predictedSmall) }

                if (rawSmall != null) {
                    // Found a small row! Add to beginning and update currentTop to keep climbing
                    propagatedBelts.add(0, rawSmall)
                    currentTop = rawSmall
                    Log.d("DEBUG", "Found missing small row above validBelts, at $rawSmall")
                } else {
                    // No small row found in raw data try the Tall Top Row (the header).
                    val predictedTall = currentTop - tallRowHeight
                    val rawTall = rawYBelts
                        .filter { Math.abs(it - predictedTall) < detectionErrorMargin }
                        .minByOrNull { Math.abs(it - predictedTall) }

                    if (rawTall != null) {
                        propagatedBelts.add(0, rawTall)
                        Log.d("DEBUG", "Found missing top header row at $rawTall")
                    } else {
                        throw MissingTopRowException("Couldn't detect Header row while climbing up from detected table part")
                    }

                    break // After adding header row we are at the absolute top of the table.
                }
            }
            propagatedBelts.add(validYBelts[0])
        }
        val startIndex = if (propagatedBelts.isEmpty()) 1 else 0 // if first row was missing we start from 0

        if (startIndex == 1)
            propagatedBelts.add(validYBelts[0])

        // ---- Adding rows between ----
        // Now we add any missing rows in the between of what was found
        propagatedBelts.add(validYBelts[startIndex]) // adding first anhor at the start
        for (i in startIndex until gaps.size) {
            val currentAnchor = validYBelts[i]
            val nextAnchor = validYBelts[i+1]
            val gapSize = nextAnchor - currentAnchor

            // Determine how many rows are actually in this gap
            val numRowsInGap = Math.round(gapSize.toDouble() / rowHeight).toInt()
            if (numRowsInGap == 0)
                Log.d("DEBUG", "Error: ${i}th detected gap is very small")

            // If more than 1 row exists in this gap, fill the missing ones
            if (numRowsInGap > 1) {
                val predictedRowHeight = gapSize / numRowsInGap
                for (j in 1 until numRowsInGap) {
                    val predictedPos = currentAnchor + (j * predictedRowHeight)

                    val rawBeltExists = rawYBelts.any { abs(it - predictedPos) < detectionErrorMargin }
                    if (!rawBeltExists)
                        Log.d("DEBUG", "${j}th raw belt between validBelts $i and ${i+1} not found")

                    propagatedBelts.add(predictedPos)
                }
            }
            propagatedBelts.add(nextAnchor)
        }

        // ---- Adding rows after ----
        var currentBottom = propagatedBelts.last()
        while (propagatedBelts.size < expectedRows) {
            val predictedPos = currentBottom + rowHeight

            // Try to find a raw belt at the bottom
            val rawBelt = rawYBelts
                .filter { Math.abs(it - predictedPos) < detectionErrorMargin }
                .minByOrNull { Math.abs(it - predictedPos) }

            val nextPos = rawBelt ?: predictedPos
            propagatedBelts.add(nextPos)
            currentBottom = nextPos

            if (rowHeight <= 0) throw IllegalStateException("Horizontal row propagation couldn't determine rowHeight correctly")
        }
        return propagatedBelts
    }

    private fun filterBeltsByDensity(
        grid: Array<Array<Point?>>,
        belts: List<Int>,
        targetCount: Int,
        isHorizontal: Boolean
    ): List<Int> {
        if (belts.size <= targetCount) return belts

        val scores = belts.indices.map { index ->
            var validIntersectionCount = 0

            if (isHorizontal) {
                for (col in grid[index].indices) {
                    if (grid[index][col] != null) validIntersectionCount++
                }
            } else { // XBelts[0] has X pixel position of first table vertical line
                for (row in grid.indices) {
                    if (grid[row][index] != null) validIntersectionCount++
                }
            }
            validIntersectionCount
        }
        val beltScorePairs = belts.zip(scores)

        if (targetCount != -1) {
            return beltScorePairs
                .sortedByDescending { it.second }
                .take(targetCount)
                .map { it.first }
                .sorted()
        } else {
            // Calculate threshold based on grid dimensions
            val maxPoints = if (isHorizontal) (grid.firstOrNull()?.size ?: 0) else grid.size
            val minRequiredPoints = maxPoints * minRequiredIntersectionsCoeff

            return beltScorePairs
                .filter { it.second >= minRequiredPoints }
                .map { it.first }
                .sorted()
        }

    }

    fun deskewGrayMat(gray: Mat) : Mat? {
        val thresh = Mat()
        // Apply adaptive threshold to get binary image
        Imgproc.adaptiveThreshold(
            gray, thresh, 255.0,
            Imgproc.ADAPTIVE_THRESH_MEAN_C,
            Imgproc.THRESH_BINARY_INV,
            15, 10.0
        )

        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(thresh, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        // Find the largest contour (the table boundary)
        val largestContour = contours.maxByOrNull { Imgproc.contourArea(it) }

        if (largestContour != null) {
            // Convert MatOfPoint to MatOfPoint2f for minAreaRect
            val contour2f = MatOfPoint2f(*largestContour.toArray())

            // Get the rotated bounding box
            val rotatedRect = Imgproc.minAreaRect(contour2f)

            // Normalize to nearest axis (0, 90, 180, 270)
            var angle = rotatedRect.angle
            while (angle > 45) angle -= 90
            while (angle < -45) angle += 90

            // Rotate just to align with axes
            val rotationMatrix = Imgproc.getRotationMatrix2D(rotatedRect.center, angle, 1.0)
            val deskewed = Mat()
            Imgproc.warpAffine(gray, deskewed, rotationMatrix, gray.size(), Imgproc.INTER_CUBIC)

            thresh.release()
            hierarchy.release()
            contour2f.release()
            rotationMatrix.release()

            return deskewed
        }
        Log.d("ERROR", "Deskewing the image failed")
        return null
    }

    /** Convert an Android Bitmap -> OpenCV grayscale Mat */
    fun bitmapToGrayMat(bitmap: Bitmap): Mat {
        Log.d("DEBUG", "bitmapToGratMay size before: ${bitmap.width} width, ${bitmap.height} height")
        val rgba = Mat()
        Utils.bitmapToMat(bitmap, rgba)

        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        Log.d("DEBUG", "bitmapToGratMay size after: ${gray.width()} width, ${gray.height()} height, ${gray.rows()} rows, ${gray.cols()} cols")

        rgba.release()
        return gray
    }

    /** Convert OpenCV Mat -> Android Bitmap (ARGB_8888) for display */
    fun matToBitmap(mat: Mat): Bitmap {
        // Null or empty Mat? — return safe 1×1 bitmap
        if (mat.empty() || mat.cols() <= 0 || mat.rows() <= 0) {
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }

        val bmp = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(mat, bmp)
        return bmp
    }

    // Create Debug image with marked rectangles.
    fun drawCells(gray: Mat, cells: Array<Array<TableCell>>): Mat {
        val debugImage = gray.clone() // Clone to draw on it
        if (debugImage.channels() == 1) {
            Imgproc.cvtColor(debugImage, debugImage, Imgproc.COLOR_GRAY2RGB)
        }
        val green = Scalar(0.0, 255.0, 0.0)

        // Iterate through the grid to create cells (N rows/cols give N-1 cells)
        for (r in 0 until cells.size) {
            for (c in 0 until cells[0].size) {
                // Get the 4 corners for the current cell
                val p1 = cells[r][c].topLeft
                val p2 = cells[r][c].topRight
                val p3 = cells[r][c].bottomLeft
                val p4 = cells[r][c].bottomRight

                // Draw the cell boundaries for visual verification
                // Using polylines handles non-perfect rectangles (perspective)
                val corners = MatOfPoint(p1, p2, p4, p3) // Order: TL -> TR -> BR -> BL
                Imgproc.polylines(debugImage, listOf(corners), true, green, 2)
            }
        }
        return debugImage
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

        // Release temporary resources
        evidence.release()
        mask.release()

        val pixelDetectionThreshold = 7 // TODO calculate 1.5% of the cell area
        return Triple(inkPixelCount > pixelDetectionThreshold, topQuad, bottomQuad)
    }

    fun detectPenWriting(
        thresh: Mat,
        cell: TableCell,
    ): Int {
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
        val listOfPolys = listOf(MatOfPoint(*quad))
        Imgproc.fillPoly(mask, listOfPolys, Scalar(255.0))

        // 5. Count intersection
        val evidence = Mat()
        Core.bitwise_and(thresh, mask, evidence)
        val inkPixelCount = Core.countNonZero(evidence)

        // Release temporary resources
        evidence.release()
        mask.release()

        return inkPixelCount
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
