package com.example.workflowocr

import android.util.Log
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

object TimeUtils {
    // Helper to convert "HH:mm" to minutes
    fun timeToMinutes(startTime: String, finishTime: String): Pair<Int, Int> {
        val startMins = parseTimeOrNull(startTime)
        val finishMins = parseTimeOrNull(finishTime)
        if (startMins == null || finishMins == null)
            return Pair(0, 0)

        return if (finishMins >= startMins) {
            Pair(startMins, finishMins)
        } else {
            // It's after midnight (e.g., start 18:00 and finish 01:00
            Pair(startMins, 24 * 60 + finishMins)
        }
    }

    fun parseTimeOrNull(time: String): Int? {
        // Strict format: "exactly 2 digits : exactly 2 digits"
        val regex = Regex("""^(\d{2}):(\d{2})$""")
        val match = regex.matchEntire(time) ?: return null

        val (hStr, mStr) = match.destructured
        val hours = hStr.toInt()
        val minutes = mStr.toInt()

        // Validate ranges
        if (hours !in 0..23 || minutes !in 0..59) return null

        return hours * 60 + minutes
    }

    fun minutesToTimeString(mins: Int): String {
        val h = (mins / 60) % 24
        val m = mins % 60
        return String.format("%02d:%02d", h, m)
    }

    fun currentTimeMinutes(): Int {
        val now = java.time.LocalTime.now()
        return now.hour * 60 + now.minute
    }

    fun round15(mins: Int): Int {
        return ((mins + 7) / 15) * 15
    }

    fun getClosestFullDate(dateStr: String, currentLocalDate: LocalDate = LocalDate.now()): LocalDate? {
        val parts = dateStr.split("-")
        val day = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val month = parts.getOrNull(1)?.toIntOrNull() ?: return null

        // 1. Resolve which absolute calendar year this string naturally belongs to
        val parsedDateInCurrentYear = try {
            LocalDate.of(currentLocalDate.year, month, day)
        } catch (e: Exception) {
            return null // Skip corrupt or impossible calendar combinations (e.g., 31-02)
        }

        // Project candidate options for boundaries calculation adjustments
        val relativePastYearDate = parsedDateInCurrentYear.minusYears(1)
        val relativeFutureYearDate = parsedDateInCurrentYear.plusYears(1)

        // Find which absolute year choice is closest to today's real target timeframe
        return listOf(relativePastYearDate, parsedDateInCurrentYear, relativeFutureYearDate)
            .minByOrNull { abs(ChronoUnit.DAYS.between(currentLocalDate, it)) } ?: run {
            Log.d("Storage", "purgeExpiredSensitiveData: bestFitTrueDate is null for $dateStr")
            parsedDateInCurrentYear
        }
    }
}
