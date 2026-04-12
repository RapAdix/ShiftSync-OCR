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
    private val headerRowHeightMultiplier = 3 // ratio of height between header_row / normal_row

    private val expectedYBelts = expectedRows + 1
    private val expectedXBelts = expectedCols + 1

    // Input: a grayscale Mat
    // Output: Array of rectangles representing detected cells
    fun detectTableCells(gray: Mat): TableDetectionResult {

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

        val (cells, linesDrawing) = findRefinedCorners(horizontal, vertical)
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
    fun findRefinedCorners(horizontal: Mat, vertical: Mat): Pair<Array<Array<TableCell>>, Mat> {
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
        val rawYBelts = getBeltCenters(rowSums, rowThresh, true)
        val rawXBelts = getBeltCenters(colSums, colThresh, false)

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

        Log.d("DEBUG", "counted ${rawYBelts.size} potential rows")
        Log.d("DEBUG", "counted ${rawXBelts.size} potential cols")

        // Find Local Intersections (Actual crossings)
        val intersections = Mat()
        Core.bitwise_and(horizontal, vertical, intersections)

        val jointContours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(intersections, jointContours, Mat(), Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        val rawGrid = gridFromClosestIntersections(jointContours, rawXBelts, rawYBelts)

        //Filter out fake belts(paper edges, pencil strokes..)
        val validXBelts = filterBeltsByDensity(rawGrid, rawXBelts, expectedXBelts, isHorizontal = false)
        val validYBelts = filterBeltsByDensity(rawGrid, rawYBelts, -1, isHorizontal = true)
        val propagatedYBelts = try {
            propagateHorizontalBeltsByStructure(rawYBelts, validYBelts, expectedYBelts)
        } catch (e: MissingTopRowException) {
            Log.d("DEBUG", "Warning: Couldn't detect top header row during propagation")
            validYBelts
        }
//        val validYBelts = filterBeltsByStructure(intersections, rawYBelts, expectedRows) // maybe algo that puts lines between numbers to fit my estimated size into the current one

        // Put it again through grid creation because we removed fake belts
        val cleanedGrid = gridFromClosestIntersections(jointContours, validXBelts, propagatedYBelts)

        val propagator = TablePropagator()
        val propagatedGrid = propagator.propagateRobustGrid(intersections, validXBelts, propagatedYBelts, cleanedGrid)
//        val finalGrid = filterUnrealBelts(grid, validXBelts, validYBelts)

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

        // Cleanup
        rowSums.release()
        colSums.release()
        intersections.release()

        return Pair(cells, linesDebugMat)
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

    private fun gridFromClosestIntersections(
        jointContours: List<MatOfPoint>,
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

        for (contour in jointContours) {
            val moments = Imgproc.moments(contour)
            if (moments.m00 > 0) {
                val cx = moments.m10 / moments.m00
                val cy = moments.m01 / moments.m00

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
        val tallRowHeight = rowHeight * headerRowHeightMultiplier // TODO put correct constant

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

    private fun isRotated() {}

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

    //Filter out falsely detected lines which didn't make any crossing on the actual table.
    fun filterUnrealBelts(grid: Array<Array<Point?>>, validXBelts: List<Int>, validYBelts: List<Int>) : Array<Array<Point>> {
        val rowCount = grid.size
        val colCount = grid[0].size
        // Threshold: at least 50% of intersections must exist to consider the line as real
        val minRequiredPointsX = rowCount * 0.5
        val minRequiredPointsY = colCount * 0.5

        // Identify which X Belts (columns) are valid
        val filteredXIndices = validXBelts.indices.filter { c ->
            var count = 0
            for (r in 0 until rowCount) {
                if (grid[r][c] != null) {
                    count++
                }
                else {
                    Log.d("DEBUG", "Missing intersection in row:$r col:$c   for col $c pixel:${validXBelts[c]}")
                }
            }
            count >= minRequiredPointsX
        }

        // Identify which Y Belts (rows) are valid
        val filteredYIndices = validYBelts.indices.filter { r ->
            var count = 0
            for (c in 0 until colCount) {
                if (grid[r][c] != null) {
                    count++
                    Log.d("DEBUG", "Missing intersection in row:$r col:$c   for row $r  pixel:${validYBelts[r]}")
                }
            }
            count >= minRequiredPointsY
        }

        //Create a new filtered grid based only on valid belts
        val filteredRowCount = filteredYIndices.size
        val filteredColCount = filteredXIndices.size

        var nullCounter = 0
        val finalGrid = Array(filteredRowCount) { r ->
            Array(filteredColCount) { c ->
                val oldR = filteredYIndices[r]
                val oldC = filteredXIndices[c]

                if (grid[oldR][oldC] == null) nullCounter++
                // If still null, use the mathematical intersection (Global Belt)
                grid[oldR][oldC] ?: Point(validXBelts[oldC].toDouble(), validYBelts[oldR].toDouble())
            }
        }
        Log.d("DEBUG", "counted $nullCounter missing table line crossings")
        return finalGrid
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

    fun fixOrientation(gray: Mat, cells: Array<Array<TableCell>>): Mat {
        if (cells.isEmpty() || cells[0].isEmpty()) return gray

        val avgW = (cells[0].last().topRight.x - cells[0][0].topLeft.x) / cells[0].size
        val avgH = (cells.last()[0].bottomLeft.y - cells[0][0].topLeft.y) / cells.size

        val rotated = Mat()
        var rotationPerformed = false
        if (avgH > avgW) { // If cells are taller than wider, the table is likely rotated 90 deg
            // First row should be wider than the last one so check which direction to rotate.
            if (cells[0][0].topRight.x - cells[0][0].topLeft.x >
                cells[0].last().topRight.x - cells[0].last().topLeft.x) {
                Core.rotate(gray, rotated, Core.ROTATE_90_CLOCKWISE)
                Log.d("DEBUG", "Table rotated 90 deg Clockwise based on cell proportions (W:$avgW < H:$avgH)")
            } else {
                Core.rotate(gray, rotated, Core.ROTATE_90_COUNTERCLOCKWISE)
                Log.d("DEBUG", "Table rotated 90 deg CounterClockwise based on cell proportions (W:$avgW < H:$avgH)")
            }
            rotationPerformed = true
        }
        else if (cells[0][0].bottomLeft.y - cells[0][0].topLeft.y <
                cells.last()[0].bottomLeft.y - cells.last()[0].topLeft.y) {
            // The table is upside down. First row should be wider. Rotating.
            Core.rotate(gray, rotated, Core.ROTATE_180)
            Log.d("DEBUG", "Table rotated 180 deg (W:$avgW > H:$avgH)")
            rotationPerformed = true
        }
        return if (rotationPerformed) {
            rotated
        } else {
            rotated.release()
            gray
        }
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
}
