package com.example.workflowocr

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.opencv.core.Rect
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToInt

object TextProcessor {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    class CouldNotDetermineDateException(message: String) : Exception(message)

    suspend fun extractTextFromCells(
        cells: Array<Array<TableDetector.TableCell>>,
        bitmap: Bitmap,
        specificCols: List<Int>? = null
    ): Array<Array<String>> = withContext(Dispatchers.IO) {
        if (cells.isEmpty()) return@withContext emptyArray()
        val results = Array<Array<String>>(cells.size) {Array<String>(cells[0].size) {""} }
        val matrix = Matrix().apply { postScale(2f, 2f) } // 2x Zoom

        val cols = specificCols ?: cells[0].indices
        for (row in cells.indices) {
            for (col in cols) {
                results[row][col] = extractTextFromCell(cells[row][col], bitmap, matrix)
            }
        }

        return@withContext results
    }

    /**
     * Recognizes each requested table column once at 1.5x scale, then falls back to
     * the established 2x per-cell OCR only when the column pass found no text for a cell.
     * A 2x column scale was another promising candidate during evaluation.
     */
    suspend fun extractTextFromColumns(
        cells: Array<Array<TableDetector.TableCell>>,
        bitmap: Bitmap,
        specificCols: List<Int>? = null
    ): Array<Array<String>> = withContext(Dispatchers.IO) {
        // First do the inexpensive pass: ML Kit is invoked once per requested column.
        val results = extractTextFromColumnsAtScale(cells, bitmap, specificCols, 1.5f)
        if (cells.isEmpty()) return@withContext results

        val cols = specificCols ?: cells[0].indices
        val fallbackMatrix = Matrix().apply { postScale(2f, 2f) }
        cells.indices.forEach { row ->
            cols.forEach { col ->
                // A blank result means that the column OCR found no element whose center
                // fell inside this cell. Try to recognize only that cell.
                if (results[row][col].isBlank()) {
                    results[row][col] = extractTextFromCell(cells[row][col], bitmap, fallbackMatrix)
                }
            }
        }
        return@withContext results
    }

    /**
     * Performs the column part of [extractTextFromColumns]. The cells keep coordinates
     * relative to the original bitmap throughout; only the temporary column crop is scaled.
     */
    private suspend fun extractTextFromColumnsAtScale(
        cells: Array<Array<TableDetector.TableCell>>,
        bitmap: Bitmap,
        specificCols: List<Int>?,
        scale: Float
    ): Array<Array<String>> = withContext(Dispatchers.IO) {
        if (cells.isEmpty()) return@withContext emptyArray()
        val results = Array(cells.size) { Array(cells[0].size) { "" } }
        val cols = (specificCols ?: cells[0].indices).toSet()
        val detectionsByCell = mutableMapOf<Pair<Int, Int>, MutableList<PositionedText>>()
        val upscaleMatrix = Matrix().apply { postScale(scale, scale) }

        cols.forEach { col ->
            // One rectangular crop covers every row in this column. The small inset keeps
            // the surrounding table borders out of the OCR input.
            val bounds = cells.map { getRectForCell(it[col]) }
            val padding = 3
            val left = (bounds.minOf { it.x } + padding).coerceIn(0, bitmap.width - 1)
            val top = (bounds.minOf { it.y } + padding).coerceIn(0, bitmap.height - 1)
            val right = (bounds.maxOf { it.x + it.width } - padding).coerceIn(left + 1, bitmap.width)
            val bottom = (bounds.maxOf { it.y + it.height } - padding).coerceIn(top + 1, bitmap.height)
            val columnBitmap = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top, upscaleMatrix, true)
            try {
                recognize(InputImage.fromBitmap(columnBitmap, 0))?.let { visionText ->
                    // ML Kit reports positions inside the scaled crop. Convert them back to
                    // original-bitmap coordinates before deciding which table cell owns them.
                    addRecognizedElements(
                        visionText, cells, setOf(col), detectionsByCell,
                        xOffset = left.toDouble(), yOffset = top.toDouble(), scale = scale.toDouble()
                    )
                }
            } finally {
                columnBitmap.recycle()
            }
        }

        // A cell may receive several words. Reassemble them into the same reading order
        // in which they appeared within its ML Kit lines.
        detectionsByCell.forEach { (position, detections) ->
            val (row, col) = position
            results[row][col] = joinDetectedText(detections)
        }
        return@withContext results
    }

    private suspend fun extractTextFromCell(
        cell: TableDetector.TableCell,
        bitmap: Bitmap,
        upscaleMatrix: Matrix
    ): String = withContext(Dispatchers.IO) {
        val rect = getRectForCell(cell)

        // Inset to avoid table lines
        val padding = 3
        val x = (rect.x + padding).coerceIn(0, bitmap.width - 1)
        val y = (rect.y + padding).coerceIn(0, bitmap.height - 1)
        val w = (rect.width - 2 * padding).coerceIn(1, bitmap.width - x)
        val h = (rect.height - 2 * padding).coerceIn(1, bitmap.height - y)

        try {
            // Upscale for better recognition
            val upscaled = Bitmap.createBitmap(bitmap, x, y, w, h, upscaleMatrix, true)

            val inputImage = InputImage.fromBitmap(upscaled, 0)
            val ocrText = recognize(inputImage)?.text ?: ""

            // Clean up temporary bitmaps!
            upscaled.recycle()
            return@withContext ocrText
        } catch (_: Exception) {
            return@withContext ""
        }
    }

    /** A recognized word plus enough original-bitmap position data to rebuild its text order. */
    private data class PositionedText(
        val text: String,
        val lineId: Int,
        val lineTop: Int,
        val lineLeft: Int,
        val elementLeft: Int
    )

    /**
     * Keep words detected in one ML Kit line together, then order lines top-to-bottom
     * and words left-to-right. Sorting each word solely by Y would mix words from one
     * line when ML Kit gives their boxes slightly different vertical positions.
     */
    private fun joinDetectedText(detections: List<PositionedText>): String = detections
        .groupBy { it.lineId }
        .values
        .sortedWith(compareBy<List<PositionedText>> { it.first().lineTop }.thenBy { it.first().lineLeft })
        .joinToString("\n") { line ->
            line.sortedBy { it.elementLeft }.joinToString(" ") { it.text }
        }

    private fun addRecognizedElements(
        visionText: Text,
        cells: Array<Array<TableDetector.TableCell>>,
        targetCols: Set<Int>,
        detectionsByCell: MutableMap<Pair<Int, Int>, MutableList<PositionedText>>,
        xOffset: Double = 0.0,
        yOffset: Double = 0.0,
        scale: Double = 1.0
    ) {
        // ML Kit's Elements are used rather than whole Lines because one detected line can
        // extend across adjacent table cells, while individual elements have separate boxes.
        fun assignText(text: String, box: android.graphics.Rect, lineId: Int, lineBox: android.graphics.Rect) {
            // The element centre is a stable rule for assigning text that touches a cell edge.
            val centerX = xOffset + (box.left + box.width() / 2.0) / scale
            val centerY = yOffset + (box.top + box.height() / 2.0) / scale
            val target = findContainingCell(cells, targetCols, centerX, centerY) ?: return
            detectionsByCell.getOrPut(target) { mutableListOf() } += PositionedText(
                text,
                lineId,
                (yOffset + lineBox.top / scale).roundToInt(),
                (xOffset + lineBox.left / scale).roundToInt(),
                (xOffset + box.left / scale).roundToInt()
            )
        }

        visionText.textBlocks.flatMap { it.lines }.forEachIndexed { lineId, line ->
            val lineBox = line.boundingBox ?: return@forEachIndexed
            if (line.elements.isEmpty()) {
                assignText(line.text, lineBox, lineId, lineBox)
            } else {
                line.elements.forEach { element ->
                    element.boundingBox?.let { assignText(element.text, it, lineId, lineBox) }
                }
            }
        }
    }

    private suspend fun recognize(inputImage: InputImage): Text? =
        suspendCancellableCoroutine { cont ->
            recognizer.process(inputImage)
                .addOnSuccessListener { cont.resume(it) {} }
                .addOnFailureListener { error ->
                    Log.e("TextProcessor", "OCR processing failed", error)
                    cont.resume(null) {}
                }
        }

    private fun findContainingCell(
        cells: Array<Array<TableDetector.TableCell>>,
        targetCols: Set<Int>,
        x: Double,
        y: Double
    ): Pair<Int, Int>? {
        // Only requested columns participate, so text in other columns cannot be assigned here.
        cells.forEachIndexed { row, rowCells ->
            rowCells.forEachIndexed { col, cell ->
                if (col in targetCols && isPointInsideCell(x, y, cell)) return row to col
            }
        }
        return null
    }

    private fun isPointInsideCell(x: Double, y: Double, cell: TableDetector.TableCell): Boolean {
        // A point is inside a convex four-corner cell when it stays on the same side of
        // every edge. This also works for a slightly skewed table cell.
        val corners = arrayOf(cell.topLeft, cell.topRight, cell.bottomRight, cell.bottomLeft)
        var hasPositive = false
        var hasNegative = false
        corners.indices.forEach { index ->
            val first = corners[index]
            val second = corners[(index + 1) % corners.size]
            val cross = (second.x - first.x) * (y - first.y) - (second.y - first.y) * (x - first.x)
            if (cross > 0.0) hasPositive = true
            if (cross < 0.0) hasNegative = true
        }
        return !(hasPositive && hasNegative)
    }

    // A lightweight helper class to pair data with physical image coordinates
    private data class SpatialDetection(
        val value: Int,
        val centerY: Float,
        val boxHeight: Int
    )

    /**
     * Extracts text from a cropped bitmap column using ML Kit, calculates the true
     * structural row spacing using median distance analysis, fills missing intermediate
     * rows with 0s, and pads empty space at the top of the image with leading zeros.
     */
    suspend fun extractAndExtrapolateIntRows(
        bitmap: Bitmap,
        x: Int, y: Int, w: Int, h: Int,
        totalConfiguredMaxRows: Int = 100 // Safeguard limit to avoid over-allocating lines
    ): List<Int> = withContext(Dispatchers.IO) {

        val targetX = x.coerceIn(0, bitmap.width - 1)
        val targetY = y.coerceIn(0, bitmap.height - 1)
        val targetW = w.coerceIn(1, bitmap.width - targetX)
        val targetH = h.coerceIn(1, bitmap.height - targetY)

        val croppedColumn = Bitmap.createBitmap(bitmap, targetX, targetY, targetW, targetH)
        val inputImage = InputImage.fromBitmap(croppedColumn, 0)

        val visionText = suspendCancellableCoroutine<Text?> { cont ->
            recognizer.process(inputImage)
                .addOnSuccessListener { textStructure -> cont.resume(textStructure) { } }
                .addOnFailureListener { e ->
                    Log.e("TextProcessor", "OCR Processing crashed", e)
                    cont.resume(null) { }
                }
        }

        if (croppedColumn != bitmap) {
            croppedColumn.recycle()
        }
        if (visionText == null || visionText.textBlocks.isEmpty()) {
            Log.d("DEBUG", "extractAndExtrapolateIntRows: visionText is empty, exiting early")
            return@withContext emptyList()
        }

        // Collect digits and harvest their absolute central Y pixel coordinates
        val rawDetections = mutableListOf<SpatialDetection>()

        visionText.textBlocks.flatMap { it.lines }.forEach { visionLine ->
            val rawLineText = visionLine.text.trim()
            val neutralizedText = rawLineText.coerceDigits()
                .filter { it.isDigit() }

            val parsedInt = neutralizedText.toIntOrNull()
            val boundingBox = visionLine.boundingBox

            if (parsedInt != null && boundingBox != null) {
                val centerY = boundingBox.top + (boundingBox.height() / 2f)
                rawDetections.add(SpatialDetection(parsedInt, centerY, boundingBox.height()))
            }
        }

        // Sort rows strictly from the top of the image to the bottom
        val sortedDetections = rawDetections.sortedBy { it.centerY }
        if (sortedDetections.isEmpty()) return@withContext emptyList()

        // Calculate the MEDIAN distance between sequential rows
        val distances = mutableListOf<Float>()
        for (i in 0 until sortedDetections.size - 1) {
            val deltaY = sortedDetections[i + 1].centerY - sortedDetections[i].centerY
            if (deltaY > 5f) { // Ignore overlapping detections of the same line text bubble
                distances.add(deltaY)
            }
        }

        // Establish the calculated baseline stride height of a single cell grid row
        val medianRowStrideHeight = if (distances.isNotEmpty()) {
            distances.sorted()[distances.size / 2]
        } else {
            Log.d("TextProcessor", "Cannot extrapolate IntRows because there is only one number in the whole column")
            return@withContext List(1) {sortedDetections[0].value}
        }

        Log.d("DEBUG", "Calculated Median Row Stride Height: $medianRowStrideHeight px")

        // Filling Part One: Calculate how many missing 0s fit between top of image (Y=0) and first detected item
        val firstDetection = sortedDetections.first()

        // Image should be cropped tightly, dividing the space by the row stride reveals skipped leading lines
        val leadingZerosCount = (firstDetection.centerY / medianRowStrideHeight).toInt()

        val finalExtrapolatedRows = mutableListOf<Int>()
        repeat(leadingZerosCount) {
            finalExtrapolatedRows.add(0)
        }

        // Filling Part Two: Reconstruct intermediate gaps down the column chain sequentially
        var expectedVirtualY = firstDetection.centerY

        for (i in sortedDetections.indices) {
            val currentDetection = sortedDetections[i]

            // Calculate if a gap exists between our rolling virtual position tracker and the current detection
            val pixelGap = currentDetection.centerY - expectedVirtualY

            if (pixelGap >= (medianRowStrideHeight * 1.5f)) {
                // Determine exactly how many zero slots were skipped over
                val missingRowsCount = (pixelGap / medianRowStrideHeight).roundToInt()
                repeat(missingRowsCount) {
                    if (finalExtrapolatedRows.size < totalConfiguredMaxRows) {
                        finalExtrapolatedRows.add(0)
                    }
                }
            }

            // Append the actual number ML Kit read successfully
            if (finalExtrapolatedRows.size < totalConfiguredMaxRows) {
                finalExtrapolatedRows.add(currentDetection.value)
            }

            // Lock tracking center directly onto the item we just processed
            expectedVirtualY = currentDetection.centerY + medianRowStrideHeight
        }

        Log.d("DEBUG", "Final Processed Column List: $finalExtrapolatedRows")
        return@withContext finalExtrapolatedRows
    }

    private fun getRectForCell(cell: TableDetector.TableCell) : Rect {
        val cellW = (Math.abs(cell.topRight.x - cell.topLeft.x) +
                Math.abs(cell.bottomRight.x - cell.bottomLeft.x)) / 2.0
        val cellH = (Math.abs(cell.bottomLeft.y - cell.topLeft.y) +
                Math.abs(cell.bottomRight.y - cell.topRight.y)) / 2.0
        return Rect(
            cell.topLeft.x.toInt(),
            cell.topLeft.y.toInt(),
            cellW.toInt(),
            cellH.toInt()
        )
    }

    fun refineTableData(rawGrid: Array<Array<String>>, settings: TableLayout): Array<Array<String>> {
        if (rawGrid.isEmpty()) return emptyArray()
        val refinedGrid = Array(rawGrid.size) { row ->
            Array(rawGrid[row].size) { col ->
                rawGrid[row][col]
            }
        }

        for (row in refinedGrid.indices) {
            // Skip header row
            if (row == 0) {
                continue
            }
            else {
                for (col in refinedGrid[row].indices) {
                    val ocrText = refinedGrid[row][col]
                    if (col == settings.timeStartCol || col == settings.timeEndCol) {
                        val time = repairTimeCols(ocrText)
                        val minutes = TimeUtils.parseTimeOrNull(time)
                        if (minutes != null && TimeUtils.round15(minutes) != minutes)
                            refinedGrid[row][col] = "X"
                        else
                            refinedGrid[row][col] = time
                    }
                }
            }
        }

        return refinedGrid
    }

    private fun String.coerceDigits(): String {
        return this.replace("S", "5")
            .replace("s", "5")
            .replace("G", "6")
            .replace("p", "0")
            .replace("O", "0")
            .replace("o", "0")
            .replace("Q", "0")
            .replace("D", "0")
            .replace("B", "8")
    }

    private fun repairTimeCols(text: String): String {
        // 1. Clean whitespace
        var cleaned = text.replace(" ", "").trim()

        // 2. Common hallucinations
        cleaned = cleaned.coerceDigits().replace(".", ":") // Common for ':' to be seen as '.'

        // 3. Force format logic (example: 12:00)
        val digits = cleaned.filter { it.isDigit() }
        if (digits.length == 4) { // the time is always written as 07:05 so anything other than 4 digits is not time
            val hh = digits.substring(0, 2).toInt()
            val mm = digits.substring(2, 4).toInt()
            if (hh in 0..23 && mm in 0..59) {
                return "${digits.substring(0, 2)}:${digits.substring(2)}"
            } else {
                return "X"
            }
        }

        // Check if UW/UWP/UBP/W/4N was written in this cell instead of the time
        val holidayLetters = "UWPMN"
        if (cleaned.any { it.uppercaseChar() in holidayLetters })
            return "W"
        return "X"
    }

    suspend fun determineDate(
        cells: Array<Array<TableDetector.TableCell>>,
        bitmap: Bitmap,
        settings: TableLayout
    ): String = withContext(Dispatchers.IO) {
        if (cells.isEmpty() || cells[0].size <= 4) {
            Log.w("DEBUG", "determineDate: Date detection impossible. cells array is (nearly) empty")
            throw CouldNotDetermineDateException("Cells array is empty")
        }
        val matrix = Matrix().apply { postScale(2f, 2f) } // 2x Zoom
        val text1 = extractTextFromCell(cells[0][settings.timeStartCol], bitmap, matrix)
        val text2 = extractTextFromCell(cells[0][settings.timeEndCol], bitmap, matrix)
        val text3 = extractTextFromCell(cells[0][settings.timeEndCol + 1], bitmap, matrix)
        val d1 = text1.take(6).filter { it.isDigit() }.take(4)
        val d2 = text2.take(6).filter { it.isDigit() }.take(4)
        val d3 = text3.take(6).filter { it.isDigit() }.take(4)

        val candidates = listOf(d1, d2, d3)

        val winner = candidates.groupBy { it }
            .entries
            .firstOrNull { it.value.size >= 2 }
            ?.key

        if (winner == null)
            throw CouldNotDetermineDateException("Date unknown. The candidates were [$d1] [$d2] [$d3]")

        val date = when (winner.length) {
            2, 4 -> winner.take(winner.length / 2) + "-" + winner.substring(winner.length / 2, winner.length)
            3 -> resolveThreeDigitDate(winner)
            else -> throw CouldNotDetermineDateException("Date voting malfunction. The winner was $winner")
        }
        return@withContext date
    }

    /**
     * Solves the "ABC" ambiguity: Is it AB-C or A-BC?
     */
    private fun resolveThreeDigitDate(winner: String): String {
        val now = LocalDate.now()
        val currentYear = now.year

        // Option A: Split 2-1 (Day AB, Month C)
        val dayA = winner.substring(0, 2).toInt()
        val monthA = winner.substring(2, 3).toInt()

        // Option B: Split 1-2 (Day A, Month BC)
        val dayB = winner.substring(0, 1).toInt()
        val monthB = winner.substring(1, 3).toInt()

        /** Helper to check if a (day, month) combo is a real calendar date */
        fun tryParse(d: Int, m: Int): LocalDate? {
            return try {
                if (m !in 1..12) return null
                LocalDate.of(currentYear, m, d)
            } catch (_: Exception) {
                null
            }
        }

        val dateA = tryParse(dayA, monthA)
        val dateB = tryParse(dayB, monthB)

        return when {
            // Only one is a valid calendar date
            dateA != null && dateB == null -> "$dayA-$monthA"
            dateA == null && dateB != null -> "$dayB-$monthB"

            // Both are valid (e.g. "112" -> 11-Feb or 1-Dec). Pick the one closer to today.
            dateA != null && dateB != null -> {
                val diffA = abs(ChronoUnit.DAYS.between(now, dateA))
                val diffB = abs(ChronoUnit.DAYS.between(now, dateB))

                if (diffA <= diffB) "$dayA-$monthA" else "$dayB-$monthB"
            }

            // Neither is a valid date (e.g. "329" -> 32-9 is impossible, 3-29 is impossible)
            else -> throw CouldNotDetermineDateException("Ambiguous 3-digit date $winner is invalid as both DD-M and D-MM scenarios.")
        }
    }
}
