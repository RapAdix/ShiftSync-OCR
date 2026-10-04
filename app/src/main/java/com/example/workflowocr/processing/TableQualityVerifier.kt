package com.example.workflowocr

import androidx.annotation.StringRes

/** Checks whether a detected table has plausible content and structure. */
object TableQualityVerifier {
    private const val MIN_RECOGNIZED_TIME_CELL_FRACTION = 0.65
    private const val FULL_TIME_DIGITS = 4
    private const val CROSSED_TIME_DIGITS = 2
    private val fixedTimeMarkers = setOf("U", "W", "N4", "4N", "DW")

    /** Returns the retake message resource when time columns look implausible, or null when they pass. */
    @StringRes
    fun verifyTimeColumns(
        textGrid: Array<Array<String>>,
        startCol: Int,
        endCol: Int,
        isCrossed: (row: Int, column: Int) -> Boolean
    ): Int? {
        if (textGrid.size < 2) return R.string.scan_schedule_rows_not_recognized

        // The header has dates rather than shift entries. Every body row counts,
        // including lower rows where a marker may appear instead of a time.
        val timeCols = listOf(startCol, endCol)
        val totalCells = (textGrid.size - 1) * timeCols.size
        var recognizedCells = 0
        for (row in 1 until textGrid.size) {
            for (col in timeCols) {
                val text = textGrid[row].getOrNull(col)
                    ?: return R.string.scan_time_columns_missing
                val marker = text.filter(Char::isLetterOrDigit).uppercase()
                val digits = text.count(Char::isDigit)
                val isTimeMarker = marker in fixedTimeMarkers ||
                    (marker.length in 2..3 && marker.first() in "UW")
                if (isTimeMarker || digits >= FULL_TIME_DIGITS ||
                    (digits >= CROSSED_TIME_DIGITS && isCrossed(row, col))
                ) {
                    recognizedCells++
                }
            }
        }

        return if (recognizedCells < totalCells * MIN_RECOGNIZED_TIME_CELL_FRACTION) {
            R.string.scan_time_cells_unreadable
        } else {
            null
        }
    }
}
