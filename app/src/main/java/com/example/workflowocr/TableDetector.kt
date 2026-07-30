package com.example.workflowocr

import android.util.Log
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
import kotlin.math.max

object TableDetector {

    data class TableCell(
        val topLeft: Point,
        val topRight: Point,
        val bottomLeft: Point,
        val bottomRight: Point
    )

    sealed class TableDetectionResult {
        // 1. Shared fields accessible on both Success and Failure layouts
        abstract val cells: Array<Array<TableCell>>
        abstract val gray: Mat
        abstract val thresh: Mat
        abstract val mask: Mat
        abstract val lines: Mat

        data class Success(
            override val cells: Array<Array<TableCell>>,
            override val gray: Mat,
            override val thresh: Mat,
            override val mask: Mat,
            override val lines: Mat
        ) : TableDetectionResult()

        data class Failure(
            override val cells: Array<Array<TableCell>>,
            override val gray: Mat,
            override val thresh: Mat,
            override val mask: Mat,
            override val lines: Mat,
            val exception: Exception // 👈 Explains why text parsing shouldn't proceed
        ) : TableDetectionResult()
    }

    class MissingTopRowException(message: String) : Exception(message)

    private const val MIN_REQUIRED_INTERSECTIONS_COEFF : Double = 0.4 // Require at least 40% of the most intersected belt's points

    /**
     * Input: a grayscale Mat
     * Output:
     * - Array of rectangles representing detected cells
     * - grayscale Mat - rotated if incorrect table orientation detected
     */
    fun detectTableCellsByLines(gray: Mat, settings: TableLayout): TableDetectionResult {
        val expectedCols = settings.expectedCols
        val headerRowHeightMultiplier = settings.headerRowHeightMultiplier // ratio of height between header_row / normal_row
        val expectedVerticalLines = expectedCols + 1

        val thresh = ImageProcessor.createThresh(gray)

        val gridMask = createGridMask(thresh) // create a short horizontal/vertical grid mask

        // Combine it with more strictly horizontal and vertical lines that tried to breach the gaps
        val (horizontal, vertical) = createHorizontalVertical(thresh)
        Core.add(gridMask, horizontal, gridMask)
        Core.add(gridMask, vertical, gridMask)
        // Somehow this approach gives the best result

        val physicalJunctions = extractPhysicalJunctions(horizontal, vertical)

        horizontal.release()
        vertical.release()

        return try {
            val (horizontalLines, verticalLines) = LineDetector.extractTableLines(gridMask)
            findRefinedCorners(gray, thresh, horizontalLines, verticalLines, expectedVerticalLines, headerRowHeightMultiplier, gridMask, physicalJunctions)
        } catch (e: TooManyLinesException) {
            // Draw the noisy detected lines onto a preview image for the user
            val noisyLinesMat = gray.clone()
            if (noisyLinesMat.channels() == 1) {
                Imgproc.cvtColor(noisyLinesMat, noisyLinesMat, Imgproc.COLOR_GRAY2RGB)
            }
            for (seg in e.horizontal) {
                Imgproc.line(noisyLinesMat, seg.first, seg.second, Scalar(0.0, 0.0, 255.0), 2)
            }
            for (seg in e.vertical) {
                Imgproc.line(noisyLinesMat, seg.first, seg.second, Scalar(255.0,  0.0, 0.0), 2)
            }

            TableDetectionResult.Failure(
                cells = emptyArray(),
                gray = gray.clone(),
                thresh = thresh,
                mask = gridMask,
                lines = noisyLinesMat,
                exception = e
            )
        }
    }

    private fun createHorizontalVertical(thresh: Mat): Pair<Mat, Mat> {
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
        horizontalStructure.release()
        verticalStructure.release()

        return Pair(horizontal, vertical)
    }

    private fun createGridMask(thresh: Mat): Mat {
        val horizontal = Mat()
        val horizontalKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(15.0, 1.0))
        Imgproc.morphologyEx(thresh, horizontal, Imgproc.MORPH_OPEN, horizontalKernel)
        val vertical = Mat()
        val verticalKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(1.0, 15.0))
        Imgproc.morphologyEx(thresh, vertical, Imgproc.MORPH_OPEN, verticalKernel)

        val gridMask = Mat()
        Core.add(horizontal, vertical, gridMask)

        val squareKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        Imgproc.erode(gridMask, gridMask, squareKernel)
        Imgproc.dilate(gridMask, gridMask, squareKernel)

        horizontal.release()
        vertical.release()
        horizontalKernel.release()
        verticalKernel.release()
        squareKernel.release()

        return gridMask
    }

    /**
     * Extracts physical junction centroids from the mask where horizontal and vertical lines cross.
     */
    private fun extractPhysicalJunctions(vertical: Mat, horizontal: Mat): List<Point> {
        val jointContours = mutableListOf<MatOfPoint>()
        val intersections = Mat()
        Core.bitwise_and(horizontal, vertical, intersections)
        Imgproc.findContours(intersections, jointContours, Mat(), Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        intersections.release()

        return jointContours.mapNotNull { contour ->
            val moments = Imgproc.moments(contour)
            if (moments.m00 > 0) {
                Point(moments.m10 / moments.m00, moments.m01 / moments.m00)
            } else null
        }
    }

    /**
     * Detects table corners by combining polyline density filtering and spatial line intersections.
     */
    private fun findRefinedCorners(
        originalGray: Mat,
        thresh: Mat,
        rawHorizontalLines: List<PolyLineSegment>,
        rawVerticalLines: List<PolyLineSegment>,
        expectedVerticalLines: Int,
        headerRowHeightMultiplier: Double,
        gridMask: Mat,
        physicalJunctions: List<Point>
    ): TableDetectionResult {
        val gray = originalGray.clone()
        var structuralException: Exception? = null

        val lineExtensionRatio = 0.003 // 12px for 4000px image
        val extensionPx = max(originalGray.width(), originalGray.height()).toDouble() * lineExtensionRatio

        // Temporarily bridge gaps at the table borders to enable accurate intersection detections
        var extendedHorizontal = rawHorizontalLines.map { it.extendEndpoints(extensionPx) }
        var extendedVertical = rawVerticalLines.map {it.extendEndpoints(extensionPx) }

        Log.d("DEBUG", "counted ${extendedHorizontal.size} potential rows")
        Log.d("DEBUG", "counted ${extendedVertical.size} potential cols")

        // Initial density filtering
        val filteredHorizontal = filterLinesByIntersections(linesToFilter = extendedHorizontal, opposingLines = extendedVertical, targetCount = -1)
        val filteredVertical = filterLinesByIntersections(linesToFilter = extendedVertical, opposingLines = extendedHorizontal, targetCount = -1)

        Log.d("DEBUG", "filteredHorizontal ${filteredHorizontal.size} potential rows")
        Log.d("DEBUG", "filteredVertical ${filteredVertical.size} potential cols")

        // Track cumulative rotations
        var rotations = 0

        // 1. Orientation check (90° CCW rotation if lying on side)
        if (isLayingOnSide(filteredHorizontal, filteredVertical)) {
            Log.d("DEBUG", "Rotating 90 degrees CCW.")

            val imgWidth = gray.cols()
            val rotatedH = extendedHorizontal.map { it.rotate90CounterClockwise(imgWidth) }
            val rotatedV = extendedVertical.map { it.rotate90CounterClockwise(imgWidth) }

            extendedHorizontal = rotatedV // Vertical lines become new horizontal
            extendedVertical = rotatedH   // Horizontal lines become new vertical

            rotateMatNSteps(gray, 1)
            rotations++
        }

        // Secondary density pass for target counts
        var validHorizontal = filterLinesByIntersections(extendedHorizontal, extendedVertical, targetCount = -1)
        var validVertical = filterLinesByIntersections(extendedVertical, extendedHorizontal, targetCount = expectedVerticalLines)

        Log.d("DEBUG", "validHorizontal ${validHorizontal.size} potential rows")
        Log.d("DEBUG", "validVertical ${validVertical.size} potential cols")

        // 2. Header presence check & 180° rotation fallback
        try {
            checkHeaderRowPresence(validHorizontal, headerRowHeightMultiplier)
        } catch (_: MissingTopRowException) {
            Log.d("DEBUG", "Couldn't detect top header row. Rotating by 180 degrees.")

            val imgWidth = gray.cols()
            val imgHeight = gray.rows()

            // Rotate current polylines 180°
            extendedHorizontal = extendedHorizontal.map { it.rotate180(imgWidth, imgHeight) }
            extendedVertical = extendedVertical.map { it.rotate180(imgWidth, imgHeight) }

            validHorizontal = filterLinesByIntersections(extendedHorizontal, extendedVertical, targetCount = -1)
            validVertical = filterLinesByIntersections(extendedVertical, extendedHorizontal, targetCount = expectedVerticalLines)

            rotateMatNSteps(gray, 2)
            rotations += 2
            try {
                checkHeaderRowPresence(validHorizontal, headerRowHeightMultiplier)
            } catch (e: MissingTopRowException) {
                Log.d("DEBUG", "Error: Couldn't detect top header row even after 180° rotation!")
                structuralException = e
            }
        }

        validHorizontal = sortLinesByBestCrossSection(validHorizontal, true, 8)
        validVertical = sortLinesByBestCrossSection(validVertical, false, 8)
        // Build spatial intersection grid directly from polylines
        val propagatedGrid = TablePropagator.propagateRobustPolyLineGrid(validHorizontal, validVertical, physicalJunctions)

        // Build TableCell matrix
        val cells = if (propagatedGrid.size <= 1 || propagatedGrid[0].size <= 1) {
            emptyArray()
        } else {
            Array(propagatedGrid.size - 1) { r ->
                Array(propagatedGrid[0].size - 1) { c ->
                    TableCell(
                        topLeft = propagatedGrid[r][c],
                        topRight = propagatedGrid[r][c + 1],
                        bottomLeft = propagatedGrid[r + 1][c],
                        bottomRight = propagatedGrid[r + 1][c + 1]
                    )
                }
            }
        }

        Log.d("DEBUG", "Found ${cells.size} cell rows and ${if (cells.isEmpty()) 0 else cells[0].size} cell cols")

        // Rotate source Mats to match the cumulative rotations performed (90° CCW or 180°)
        if (rotations % 4 != 0) {
            rotateMatNSteps(thresh, rotations)
            rotateMatNSteps(gridMask, rotations)
        }

        // Render debug line overlay
        val trimmedHorizontal = validHorizontal.map { it.trimExtendedEndpoints() } // trim back the edges we extended previously
        val trimmedVertical = validVertical.map { it.trimExtendedEndpoints() }
        val linesOverlayImage = createLinesOverlayImage(trimmedHorizontal, trimmedVertical, gray)

        return if (structuralException != null) {
            TableDetectionResult.Failure(
                cells = cells,
                gray = gray,
                thresh = thresh,
                mask = gridMask,
                lines = linesOverlayImage,
                exception = structuralException
            )
        } else {
            TableDetectionResult.Success(
                cells = cells,
                gray = gray,
                thresh = thresh,
                mask = gridMask,
                lines = linesOverlayImage
            )
        }
    }

    /**
     * Sorts [lines] perpendicular to their main axis based on their off-axis coordinates
     * evaluated at the single best cross-section (the slice intersecting the most lines).
     *
     * @param isHorizontal true to sort top-to-bottom (by Y), false to sort left-to-right (by X)
     */
    private fun sortLinesByBestCrossSection(
        lines: List<PolyLineSegment>,
        isHorizontal: Boolean,
        sampleCount: Int = 5
    ): List<PolyLineSegment> {
        if (lines.size <= 1) return lines

        // 1. Find the min and max main-axis bounds across ALL polylines
        val allMainCoords = lines.flatMap { line ->
            if (isHorizontal) listOf(line.firstPoint.x, line.lastPoint.x)
            else listOf(line.firstPoint.y, line.lastPoint.y)
        }

        val minBound = allMainCoords.minOrNull() ?: return lines
        val maxBound = allMainCoords.maxOrNull() ?: return lines
        val totalSpan = maxBound - minBound

        if (totalSpan <= 0) return lines

        val step = totalSpan / (sampleCount + 1)

        // 2. Evaluate all sample cross-sections
        val samples = (1..sampleCount).map { i ->
            val samplePos = minBound + (step * i)
            val validCount = lines.count { line ->
                line.getOffAxisCoordinateAt(samplePos, isHorizontal) != null
            }
            Pair(samplePos, validCount)
        }

        // 3. Pick the slice where the most lines intersect
        val bestSamplePos = samples.maxByOrNull { it.second }?.first ?: return lines

        // 4. Sort lines by off-axis coordinate at bestSamplePos (fallback to mean endpoint coordinate if line doesn't reach slice)
        return lines.sortedBy { line ->
            line.getOffAxisCoordinateAt(bestSamplePos, isHorizontal)
                ?: if (isHorizontal) (line.firstPoint.y + line.lastPoint.y) / 2.0
                else (line.firstPoint.x + line.lastPoint.x) / 2.0
        }
    }

    /**
     * Creates an RGB overlay image highlighting horizontal polylines in RED
     * and vertical polylines in BLUE over the grayscale background.
     */
    private fun createLinesOverlayImage(
        horizontalLines: List<PolyLineSegment>,
        verticalLines: List<PolyLineSegment>,
        gray: Mat
    ): Mat {
        val linesDrawing = Mat.zeros(gray.size(), CvType.CV_8UC3)

        val redColor = Scalar(0.0, 0.0, 255.0)   // Horizontal lines -> RED (BGR)
        val blueColor = Scalar(255.0, 0.0, 0.0)  // Vertical lines -> BLUE (BGR)
        val lineThickness = 2

        // 1. Draw horizontal polylines (RED)
        for (line in horizontalLines) {
            val matOfPoint = MatOfPoint(*line.points.toTypedArray())
            Imgproc.polylines(
                linesDrawing,
                listOf(matOfPoint),
                false, // isClosed = false (open path)
                redColor,
                lineThickness,
                Imgproc.LINE_AA
            )
            matOfPoint.release()
        }

        // 2. Draw vertical polylines (BLUE)
        for (line in verticalLines) {
            val matOfPoint = MatOfPoint(*line.points.toTypedArray())
            Imgproc.polylines(
                linesDrawing,
                listOf(matOfPoint),
                false, // isClosed = false (open path)
                blueColor,
                lineThickness,
                Imgproc.LINE_AA
            )
            matOfPoint.release()
        }

        // 3. Prepare background image (convert gray to RGB)
        val linesOverlayImage = gray.clone()
        if (linesOverlayImage.channels() == 1) {
            Imgproc.cvtColor(linesOverlayImage, linesOverlayImage, Imgproc.COLOR_GRAY2RGB)
        }

        // 4. Mask and copy colored polylines over gray background
        val linesMask = Mat()
        Imgproc.cvtColor(linesDrawing, linesMask, Imgproc.COLOR_BGR2GRAY)
        Imgproc.threshold(linesMask, linesMask, 1.0, 255.0, Imgproc.THRESH_BINARY)
        linesDrawing.copyTo(linesOverlayImage, linesMask)

        // 5. Cleanup temporary Mats
        linesDrawing.release()
        linesMask.release()

        return linesOverlayImage
    }

    /**
     * Determines orientation using the PolyLineSegments
     */
    fun isLayingOnSide(
        horizontalLines: List<PolyLineSegment>,
        verticalLines: List<PolyLineSegment>,
        sampleCount: Int = 5
    ): Boolean {
        if (horizontalLines.size < 2 || verticalLines.size < 2) return false

        // Use the Median instead of Average to be robust against:
        // - The "One Tall Header Row"
        // - Massive gaps from missing rows in the middle
        // - Paper edge noise
        val medianRowHeight = calculateMedianLineGaps(
            lines = horizontalLines,
            isHorizontal = true,
            sampleCount = sampleCount
        )

        val medianColWidth = calculateMedianLineGaps(
            lines = verticalLines,
            isHorizontal = false,
            sampleCount = sampleCount
        )

        if (medianRowHeight == null || medianColWidth == null) return false

        Log.d("DEBUG", "Orientation check: medianColWidth=$medianColWidth, medianRowHeight=$medianRowHeight")

        // Usually, Column Width > Row Height in standard landscape cells.
        // We use a small threshold (1.2) to ensure it's a clear rotation, not just a square cell.
        return medianRowHeight > (medianColWidth * 1.2)
    }

    private fun calculateMedianLineGaps(
        lines: List<PolyLineSegment>,
        isHorizontal: Boolean,
        sampleCount: Int
    ): Double? {
        // 1. Find main-axis bounds across all lines
        val allMainCoords = lines.flatMap { line ->
            if (isHorizontal) listOf(line.firstPoint.x, line.lastPoint.x)
            else listOf(line.firstPoint.y, line.lastPoint.y)
        }

        val minBound = allMainCoords.minOrNull() ?: return null
        val maxBound = allMainCoords.maxOrNull() ?: return null
        val totalSpan = maxBound - minBound
        if (totalSpan <= 0) return null

        val step = totalSpan / (sampleCount + 1)

        // 2. Find the single best sample position (intersects the most lines)
        val bestSamplePos = (1..sampleCount)
            .map { minBound + (step * it) }
            .maxByOrNull { pos -> lines.count { it.getOffAxisCoordinateAt(pos, isHorizontal) != null } }
            ?: return null

        // 3. Extract and sort off-axis coordinates at bestSamplePos
        val positions = lines.mapNotNull { line ->
            line.getOffAxisCoordinateAt(bestSamplePos, isHorizontal)
        }.sorted()

        if (positions.size < 2) return null

        // 4. Calculate gaps between adjacent lines at this single best cross-section
        val gaps = mutableListOf<Double>()
        for (j in 0 until positions.size - 1) {
            val gap = positions[j + 1] - positions[j]
            gaps.add(gap)
        }

        if (gaps.isEmpty()) return null

        // 5. Return median gap size for the best cross-section
        val sortedGaps = gaps.sorted()
        return sortedGaps[sortedGaps.size / 2]
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
     * Validates whether the top detected row matches the tall header row height proportion.
     * Throws [MissingTopRowException] if the table appears upside down or missing its top row.
     */
    private fun checkHeaderRowPresence(
        horizontalLines: List<PolyLineSegment>,
        headerRowHeightMultiplier: Double
    ) {
        if (horizontalLines.size <= 1) return
        val sortedHorizontalLines = sortLinesByBestCrossSection(horizontalLines, isHorizontal = true, sampleCount = 8)

        val gaps = mutableListOf<Double>()

        // Find gaps directly between adjacent sorted lines
        for (i in 0 until sortedHorizontalLines.size - 1) {
            val lineA = sortedHorizontalLines[i]
            val lineB = sortedHorizontalLines[i + 1]

            // Measure gap using midpoints as a representative distance
            val yA = (lineA.firstPoint.y + lineA.lastPoint.y) / 2.0
            val yB = (lineB.firstPoint.y + lineB.lastPoint.y) / 2.0
            gaps.add(abs(yB - yA))
        }

        if (gaps.isEmpty()) return

        val gapBucketMargin = 10.0
        val detectionErrorCoeff = 0.3

        // Determine standard body row height using mode/clustering
        val bestGap = gaps.maxByOrNull { g ->
            gaps.count { abs(it - g) <= gapBucketMargin }
        } ?: gaps[0]

        val smallRowErrorMargin = bestGap * detectionErrorCoeff
        val cluster = gaps.filter { abs(it - bestGap) <= smallRowErrorMargin }
        val rowHeight = if (cluster.isNotEmpty()) cluster.average() else bestGap

        // Expected height of header row
        val expectedTallRowHeight = rowHeight * headerRowHeightMultiplier
        val tallRowErrorMargin = expectedTallRowHeight * detectionErrorCoeff

        // Check gap 0 (Top row)
        val firstGap = gaps[0]
        val isTopHeaderPresent = firstGap >= (expectedTallRowHeight - tallRowErrorMargin) && firstGap <= (expectedTallRowHeight + tallRowErrorMargin)

        if (!isTopHeaderPresent) {
            Log.w("DEBUG", "Header check failed: Top gap (${firstGap.toInt()} px) < expected header (${expectedTallRowHeight.toInt()} px).")
            throw MissingTopRowException("Header row missing at top of table structure.")
        } else {
            Log.d("DEBUG", "Header check passed: Top gap = ${firstGap.toInt()} px (Expected ~ ${expectedTallRowHeight.toInt()} px)")
        }
    }

    /**
     * Filters out weak/spurious lines based on how many real intersections
     * they share with the opposing set of structural lines (horizontal vs vertical).
     */
    fun filterLinesByIntersections(
        linesToFilter: List<PolyLineSegment>,
        opposingLines: List<PolyLineSegment>,
        targetCount: Int = -1
    ): List<PolyLineSegment> {
        if (linesToFilter.isEmpty()) return emptyList()
        if (targetCount != -1 && linesToFilter.size <= targetCount) return linesToFilter

        val scoredLines = linesToFilter.indices.map { i ->
            val candidate = linesToFilter[i]
            var intersectionCount = 0

            for (opposing in opposingLines) {
                if (candidate.intersects(opposing)) {
                    intersectionCount++
                }
            }
            Pair(linesToFilter[i], intersectionCount)
        }

        val maxIntersections = scoredLines.maxOfOrNull { it.second } ?: 0

        return if (targetCount != -1) {
            // Keep the top N lines with the highest intersection counts
            scoredLines
                .sortedByDescending { it.second }
                .take(targetCount)
                .map { it.first }
        } else {
            // Keep lines that have at least 40% of the maximum recorded intersection score
            val minRequiredPoints = maxIntersections * MIN_REQUIRED_INTERSECTIONS_COEFF
            scoredLines
                .filter { it.second >= minRequiredPoints }
                .map { it.first }
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

    // Create Debug image with marked rectangles.
    fun drawCells(gray: Mat, cells: Array<Array<TableCell>>): Mat {
        val debugImage = gray.clone() // Clone to draw on it
        if (debugImage.channels() == 1) {
            Imgproc.cvtColor(debugImage, debugImage, Imgproc.COLOR_GRAY2RGB)
        }
        if (cells.isEmpty()) return debugImage
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
