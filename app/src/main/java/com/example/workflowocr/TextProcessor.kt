package com.example.workflowocr

import android.graphics.Bitmap
import android.graphics.Matrix
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.opencv.core.Rect
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

object TextProcessor {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    class CouldNotDetermineDateException(message: String) : Exception(message)

    suspend fun extractTextFromCells(
        cells: Array<Array<TableDetector.TableCell>>,
        bitmap: Bitmap,
        specificCols: List<Int>? = null
    ): Array<Array<String>> = withContext(Dispatchers.IO) {

        val results = Array<Array<String>>(cells.size) {Array<String>(cells[0].size) {""} }
        val matrix = Matrix().apply { postScale(2f, 2f) } // 2x Zoom

        val cols = specificCols ?: cells[0].indices
        for (row in cells.indices) {
            for (col in cols) {
                val rect = getRectForCell(cells[row][col])

                // 1. Inset to avoid table lines
                val padding = 3
                val x = (rect.x + padding).coerceIn(0, bitmap.width - 1)
                val y = (rect.y + padding).coerceIn(0, bitmap.height - 1)
                val w = (rect.width - 2 * padding).coerceIn(1, bitmap.width - x)
                val h = (rect.height - 2 * padding).coerceIn(1, bitmap.height - y)

                try {
                    val cellBmp = Bitmap.createBitmap(bitmap, x, y, w, h)

                    // 2. Upscale for better recognition
                    val upscaled = Bitmap.createBitmap(cellBmp, 0, 0, cellBmp.width, cellBmp.height, matrix, true)

                    val inputImage = InputImage.fromBitmap(upscaled, 0)
                    val ocrText = suspendCancellableCoroutine<String> { cont ->
                        recognizer.process(inputImage)
                            .addOnSuccessListener { cont.resume(it.text) {} }
                            .addOnFailureListener { e -> cont.resume("ERROR: ${e.message}") {} }
                    }

                    results[row][col] = ocrText

                    // Clean up temporary bitmaps!
                    cellBmp.recycle()
                    upscaled.recycle()

                } catch (e: Exception) {
                    results[row][col] = ""
                }
            }
        }

        return@withContext results
    }

    fun getRectForCell(cell: TableDetector.TableCell) : Rect {
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

    fun refineTableData(rawGrid: Array<Array<String>>): Array<Array<String>> {
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
                    if (col == TIME_START_COL || col == TIME_END_COL) {
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

    private fun repairTimeCols(text: String): String {
        // 1. Clean whitespace
        var cleaned = text.replace(" ", "").trim()

        // 2. Common hallucinations
        cleaned = cleaned
            .replace("S", "5")
            .replace("s", "5")
            .replace("G", "6")
            .replace("p", "0")
            .replace("O", "0")
            .replace("o", "0")
            .replace("B", "8")
            .replace(".", ":") // Common for ':' to be seen as '.'

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

        // Check if UW/UWP/UBP/W was written in this cell instead of the time
        val holidayLetters = "UWPM"
        if (cleaned.any { it.uppercaseChar() in holidayLetters })
            return "W"
        return "X"
    }

    fun determineDate(grid: Array<Array<String>>): String {
        val d1 = grid[0][2].take( 6).filter { it.isDigit() }.take(4)
        val d2 = grid[0][3].take( 6).filter { it.isDigit() }.take(4)
        val d3 = grid[0][4].take( 6).filter { it.isDigit() }.take(4)

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
        return date
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
            } catch (e: Exception) {
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