package com.example.workflowocr

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import kotlin.math.hypot

@RunWith(AndroidJUnit4::class)
class TableCellDetectionFixtureInstrumentedTest {
    private companion object {
        const val CORNER_TOLERANCE_FRACTION = 0.00125 // 0.125%
    }
    @Serializable private data class Fixture(val image: String, val layout: String, val cells: List<ExpectedCell>)
    @Serializable private data class ExpectedCell(val row: Int, val column: Int, val corners: List<List<Double>>, val expected: String)

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun allAvailableFixturesMatchDetectedCellCountsAndCorners() {
        assert(OpenCVLoader.initDebug())
        var cornerFailures = 0
        var totalCornerDifference = 0.0
        val cornerFailureDetails = StringBuilder()
        var structuralFailures = 0
        val structuralFailureDetails = StringBuilder()
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val paths = listOf("test_fixtures_public", "test_fixtures_secret").flatMap { findFixtures(assets, it) }
        assertTrue("No public cell fixtures were found", paths.any { it.startsWith("test_fixtures_public/") })

        paths.forEach { path ->
            val fixture = assets.open(path).use { json.decodeFromString<Fixture>(it.reader().readText()) }
            val layout = when (fixture.layout) {
                "12" -> PresetDefaults.layout12Col
                "13" -> PresetDefaults.layout13Col
                else -> error("Unsupported fixture layout: ${fixture.layout}")
            }
            val directory = path.substringBeforeLast('/')
            val bitmap = assets.open("$directory/${fixture.image}").use(BitmapFactory::decodeStream)
            val gray = ImageProcessor.bitmapToGrayMat(bitmap)
            try {
                val result = TableDetector.detectTableCellsByLines(gray, layout)
                val expectedRowCount = fixture.cells.maxOf { it.row } + 1
                val expectedColumnCount = fixture.cells.maxOf { it.column } + 1
                val actualColumnCount = result.cells.firstOrNull()?.size ?: 0
                if (result.cells.size != expectedRowCount) {
                    structuralFailures++
                    structuralFailureDetails.appendLine(
                        "TABLE_STRUCTURE path=$path row count expected=$expectedRowCount actual=${result.cells.size}"
                    )
                }
                if (actualColumnCount != expectedColumnCount || result.cells.any { it.size != expectedColumnCount }) {
                    structuralFailures++
                    structuralFailureDetails.appendLine(
                        "TABLE_STRUCTURE path=$path column count expected=$expectedColumnCount " +
                            "actualFirstRow=$actualColumnCount rowSizes=${result.cells.map { it.size }}"
                    )
                }

                if (result.cells.size < expectedRowCount || result.cells.any { it.size < expectedColumnCount }) {
                    structuralFailureDetails.appendLine(
                        "TABLE_CORNERS_SKIPPED path=$path because detected cells do not contain all expected positions"
                    )
                    return@forEach
                }
                fixture.cells.forEach { expected ->
                    val actual = result.cells[expected.row][expected.column]
                    val actualPoints = listOf(actual.topLeft, actual.topRight, actual.bottomRight, actual.bottomLeft)
                    expected.corners.zip(actualPoints).forEachIndexed { corner, (expectedPoint, actualPoint) ->
                        val tolerance = maxOf(bitmap.width, bitmap.height) * CORNER_TOLERANCE_FRACTION
                        val difference = hypot(expectedPoint[0] - actualPoint.x, expectedPoint[1] - actualPoint.y)
                        if (difference > tolerance) {
                            cornerFailures++
                            totalCornerDifference += difference
                            cornerFailureDetails.appendLine(
                                "TABLE_CORNER_DISTANCE path=$path row=${expected.row}, " +
                                    "column=${expected.column}, corner=$corner, difference=$difference, tolerance=$tolerance"
                            )
                        }
                    }
                }
            } finally {
                gray.release()
                bitmap.recycle()
            }
        }
        val report = buildString {
            appendLine("TABLE_STRUCTURE_SUMMARY failures=$structuralFailures")
            if (structuralFailureDetails.isNotEmpty()) {
                appendLine(structuralFailureDetails)
            }
            appendLine("TABLE_CORNER_SUMMARY failures=$cornerFailures, summedDifference=$totalCornerDifference, toleranceFraction=$CORNER_TOLERANCE_FRACTION")
            if (cornerFailureDetails.isNotEmpty()) {
                appendLine("TABLE_CORNER_FAILURE_DETAILS")
                append(cornerFailureDetails)
            }
        }
        println(report)
        assertTrue(report, structuralFailures == 0 && cornerFailures == 0)
    }

    private fun findFixtures(assets: android.content.res.AssetManager, path: String): List<String> =
        assets.list(path).orEmpty().flatMap { entry ->
            val child = "$path/$entry"
            if (entry == "cells.json") listOf(child) else findFixtures(assets, child)
        }
}
