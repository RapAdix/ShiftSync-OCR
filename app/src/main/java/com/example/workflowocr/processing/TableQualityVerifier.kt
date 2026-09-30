package com.example.workflowocr

/** Checks whether a detected table has plausible content and structure. */
object TableQualityVerifier {
    private const val MIN_RECOGNIZED_TIME_CELL_FRACTION = 0.65
    private const val FULL_TIME_DIGITS = 4
    private const val CROSSED_TIME_DIGITS = 2
    private val fixedTimeMarkers = setOf("U", "W", "N4", "4N", "DW")

    /** Returns a retake message when the time columns look implausible, or null when they pass. */
    fun verifyTimeColumns(
        textGrid: Array<Array<String>>,
        startCol: Int,
        endCol: Int,
        isCrossed: (row: Int, column: Int) -> Boolean
    ): String? {
        if (textGrid.size < 2) return "No schedule rows were recognized. Retake the sheet picture."

        // The header has dates rather than shift entries. Every body row counts,
        // including lower rows where a marker may appear instead of a time.
        val timeCols = listOf(startCol, endCol)
        val totalCells = (textGrid.size - 1) * timeCols.size
        var recognizedCells = 0
        for (row in 1 until textGrid.size) {
            for (col in timeCols) {
                val text = textGrid[row].getOrNull(col)
                    ?: return "Time columns are missing. Retake the sheet picture."
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
            "Too many time cells were unreadable. Check the grid and retake the sheet picture."
        } else {
            null
        }
    }
}
