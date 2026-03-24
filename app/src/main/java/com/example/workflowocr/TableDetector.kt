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
        val mask: Mat
    )

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

        // Horizontal lines
        val lineThickness = 1.0 // approximate line thickness in pixels
        val lineLength = 30.0
        val horizontalStructure = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(lineLength, lineThickness))

        Imgproc.erode(thresh, horizontal, horizontalStructure)
        Imgproc.dilate(horizontal, horizontal, horizontalStructure)
        Log.d("DEBUG", ">> horizontal after erode/dilate = ${horizontal.rows()} x ${horizontal.cols()}")
        Log.d("DEBUG", "horizontal nonZero = ${Core.countNonZero(horizontal)}")

        // Vertical lines
        val verticalStructure = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(lineThickness, lineLength))
        Imgproc.erode(thresh, vertical, verticalStructure)
        Imgproc.dilate(vertical, vertical, verticalStructure)
        Log.d("DEBUG", ">> vertical after erode/dilate = ${vertical.rows()} x ${vertical.cols()}")
        Log.d("DEBUG", "vertical nonZero = ${Core.countNonZero(vertical)}")

        val cells = findRefinedCorners(horizontal, vertical)

        // Combine horizontal and vertical lines to get table mask
        val mask = Mat()
        Core.add(horizontal, vertical, mask)
        Log.d("DEBUG", ">> mask size = ${mask.rows()} x ${mask.cols()}")
        Log.d("DEBUG", "mask nonZero = ${Core.countNonZero(mask)}")

        //TODO should I clean?

        // Sort cells by row, then column
        return TableDetectionResult(
            cells,
            thresh,
            mask
        )
    }

    /**
     * Detects table corners by combining global projections with local intersections
     * to filter out noise (like pen strokes).
     */
    fun findRefinedCorners(horizontal: Mat, vertical: Mat): Array<Array<TableCell>> {
        // Get Global Projections to find "Line Belts"
        val rowSums = Mat()
        val colSums = Mat()
        Core.reduce(horizontal, rowSums, 1, Core.REDUCE_SUM, CvType.CV_32F)
        Core.reduce(vertical, colSums, 0, Core.REDUCE_SUM, CvType.CV_32F)

        // Determine Thresholds (3x Average)
        val avgRow = Core.mean(rowSums).`val`[0]
        val avgCol = Core.mean(colSums).`val`[0]
        val rowThresh = avgRow * 4.0
        val colThresh = avgCol * 4.0
        Log.d("DEBUG", "avgRow = $avgRow")
        Log.d("DEBUG", "avgCol = $avgCol")

        // Identify Candidate X and Y positions (Line Belts)
        val validYBelts = getBeltCenters(rowSums, rowThresh, true)
        val validXBelts = getBeltCenters(colSums, colThresh, false)

        val rowEdgeCount = validYBelts.size
        val colEdgeCount = validXBelts.size

        Log.d("DEBUG", "counted $rowEdgeCount rows")
        Log.d("DEBUG", "counted $colEdgeCount cols")

        // Find Local Intersections (Actual crossings)
        val intersections = Mat()
        Core.bitwise_and(horizontal, vertical, intersections)

        val jointContours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(intersections, jointContours, Mat(), Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)

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

        val finalGrid = filterUnrealBelts(grid, validXBelts, validYBelts)

        val cells = Array(finalGrid.size - 1) { r ->
            Array(finalGrid[0].size - 1) { c ->
                TableCell(
                    topLeft = finalGrid[r][c],
                    topRight = finalGrid[r][c + 1],
                    bottomLeft = finalGrid[r + 1][c],
                    bottomRight = finalGrid[r + 1][c + 1]
                )
            }
        }

        // Cleanup
        rowSums.release()
        colSums.release()
        intersections.release()

        return cells
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
                if (grid[r][c] != null) count++
            }
            count >= minRequiredPointsX
        }

        // Identify which Y Belts (rows) are valid
        val filteredYIndices = validYBelts.indices.filter { r ->
            var count = 0
            for (c in 0 until colCount) {
                if (grid[r][c] != null) count++
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
        }
        else if (cells[0][0].bottomLeft.y - cells[0][0].topLeft.y <
            cells.last()[0].bottomLeft.y - cells.last()[0].topLeft.y) {
            // The table is upside down. First row should be wider. Rotating.
            Core.rotate(gray, rotated, Core.ROTATE_180)
            Log.d("DEBUG", "Table rotated 180 deg (W:$avgW > H:$avgH)")
        }
        return rotated
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
