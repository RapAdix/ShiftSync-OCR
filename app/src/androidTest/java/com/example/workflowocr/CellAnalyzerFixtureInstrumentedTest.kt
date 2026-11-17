package com.example.workflowocr

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import org.opencv.core.Point

@RunWith(AndroidJUnit4::class)
/** Instrumentation runtime is required for Android assets/bitmaps; no app UI or hardware is needed. */
class CellAnalyzerFixtureInstrumentedTest {
    private val fixtureJson = Json { ignoreUnknownKeys = true }
    @Serializable private data class Fixture(val image: String, val layout: String, val cells: List<ExpectedCell>)
    @Serializable private data class ExpectedCell(val row: Int, val column: Int, val corners: List<List<Double>>, val expected: String)
    @Serializable private data class KnownFailure(val path: String, val row: Int, val column: Int, val expected: String, val actual: String)

    @Test
    fun allAvailableCellAnalyzerFixturesMatchExpectedCrossing() {
        assert(OpenCVLoader.initDebug())
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val paths = listOf("test_fixtures_public", "test_fixtures_secret")
            .flatMap { findFiles(assets, it, "cells.json") }
        assertFalse("No public cell fixtures were found", paths.none { it.startsWith("test_fixtures_public/") })
        var falsePositives = 0
        var falseNegatives = 0
        val mismatches = mutableListOf<String>()
        val knownFailures = listOf("test_fixtures_public", "test_fixtures_secret")
            .flatMap { root -> findFiles(assets, root, "cell_analyzer_known_failures.json") }
            .flatMap { path ->
                assets.open(path).use {
                    fixtureJson.decodeFromString<List<KnownFailure>>(it.reader().readText())
                }
            }
        val observedKnownFailures = mutableSetOf<KnownFailure>()
        paths.forEach { path ->
                val fixture = assets.open(path).use {
                    fixtureJson.decodeFromString<Fixture>(it.reader().readText())
                }
            val directory = path.substringBeforeLast('/')
            val bitmap = assets.open("$directory/${fixture.image}").use(BitmapFactory::decodeStream)
            val gray = ImageProcessor.bitmapToGrayMat(bitmap)
            val crossingThresh = ImageProcessor.createCrossingThresh(gray)
            try {
                val targetColumns = when (fixture.layout) {
                    "12" -> listOf(PresetDefaults.layout12Col.timeStartCol, PresetDefaults.layout12Col.timeEndCol)
                    "13" -> listOf(PresetDefaults.layout13Col.timeStartCol, PresetDefaults.layout13Col.timeEndCol)
                    else -> error("Unsupported fixture layout: ${fixture.layout}")
                }
                fixture.cells.forEach { expected ->
                    // Row 0 is the table header. Keep it in fixture data for future tests,
                    // but crossing detection is intentionally not required to work there.
                    if (expected.row == 0) return@forEach
                    if (expected.column !in targetColumns) return@forEach
                    val p = expected.corners.map { Point(it[0], it[1]) }
                    require(p.size == 4) { "$path row ${expected.row} col ${expected.column} must have four corners" }
                    val cell = TableDetector.TableCell(p[0], p[1], p[3], p[2])
                    val (crossed, _) = CellAnalyzer.detectPenCrossing(crossingThresh, cell)
                    assertFalse("target fixture cells must encode crossed/not_crossed", expected.expected !in setOf("crossed", "not_crossed"))
                    val expectedCrossed = expected.expected == "crossed"
                    if (crossed != expectedCrossed) {
                        val knownFailure = knownFailures.firstOrNull {
                            it.path == path && it.row == expected.row && it.column == expected.column &&
                                it.expected == expected.expected && it.actual == if (crossed) "crossed" else "not_crossed"
                        }
                        if (knownFailure != null) {
                            observedKnownFailures += knownFailure
                            return@forEach
                        }
                        val kind = if (!expectedCrossed) "false_positive_not_crossed_as_crossed" else "false_negative_crossed_as_not_crossed"
                        if (!expectedCrossed) falsePositives++ else falseNegatives++
                        val mismatch = "$kind: $path row=${expected.row}, column=${expected.column}, expected=$expected.expected, actual=${if (crossed) "crossed" else "not_crossed"}"
                        mismatches += mismatch
                        println("CELL_ANALYZER_MISMATCH $mismatch")
                    }
                }
            } finally {
                gray.release()
                crossingThresh.release()
                bitmap.recycle()
            }
        }
        knownFailures.filter { known -> paths.contains(known.path) }.forEach { known ->
            if (known in observedKnownFailures) {
                println("CELL_ANALYZER_KNOWN_FAILURE_PRESENT path=${known.path} row=${known.row} column=${known.column}")
            } else {
                println("CELL_ANALYZER_IMPROVEMENT_KNOWN_FAILURE_FIXED path=${known.path} row=${known.row} column=${known.column}")
            }
        }
        val summary = "CellAnalyzer crossing mismatches: total=${mismatches.size}, falsePositives=$falsePositives, falseNegatives=$falseNegatives"
        println("CELL_ANALYZER_SUMMARY $summary")
        assertTrue("$summary\n${mismatches.joinToString("\n")}", mismatches.isEmpty())
    }

    @Test
    fun allAvailableCellAnalyzerFixturesMatchExpectedPenWriting() {
        assert(OpenCVLoader.initDebug())
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val paths = listOf("test_fixtures_public", "test_fixtures_secret")
            .flatMap { findFiles(assets, it, "cells.json") }
        assertFalse("No public cell fixtures were found", paths.none { it.startsWith("test_fixtures_public/") })
        var falsePositives = 0
        var falseNegatives = 0
        val mismatches = mutableListOf<String>()
        paths.forEach { path ->
            val fixture = assets.open(path).use {
                fixtureJson.decodeFromString<Fixture>(it.reader().readText())
            }
            val layout = when (fixture.layout) {
                "12" -> PresetDefaults.layout12Col
                "13" -> PresetDefaults.layout13Col
                else -> error("Unsupported fixture layout: ${fixture.layout}")
            }
            val labeledCells = fixture.cells.filter {
                it.row != 0 && it.column in layout.modificationColumns &&
                    it.expected !in setOf("", "ambiguous")
            }
            if (labeledCells.isEmpty()) return@forEach

            val directory = path.substringBeforeLast('/')
            val bitmap = assets.open("$directory/${fixture.image}").use(BitmapFactory::decodeStream)
            val gray = ImageProcessor.bitmapToGrayMat(bitmap)
            val thresh = ImageProcessor.createThresh(gray)
            val cleanedThresh = org.opencv.core.Mat()
            val kernel = org.opencv.imgproc.Imgproc.getStructuringElement(org.opencv.imgproc.Imgproc.MORPH_RECT, org.opencv.core.Size(2.0, 2.0))
            org.opencv.imgproc.Imgproc.erode(thresh, cleanedThresh, kernel)
            org.opencv.imgproc.Imgproc.dilate(cleanedThresh, cleanedThresh, kernel)
            try {
                labeledCells.forEach { expected ->
                    assertTrue(
                        "$path row=${expected.row} col=${expected.column} invalid penWriting expected='${expected.expected}'",
                        expected.expected in setOf("hasWritings", "noWritings")
                    )
                    val p = expected.corners.map { Point(it[0], it[1]) }
                    require(p.size == 4) { "$path row ${expected.row} col ${expected.column} must have four corners" }
                    val cell = TableDetector.TableCell(p[0], p[1], p[3], p[2])
                    val coverage = CellAnalyzer.detectPenWriting(cleanedThresh, cell)
                    val hasWritings = coverage > 0.05
                    val expectedHasWritings = expected.expected == "hasWritings"
                    if (hasWritings != expectedHasWritings) {
                        if (expectedHasWritings) falseNegatives++ else falsePositives++
                        val mismatch = "$path row=${expected.row}, column=${expected.column}, expected=${expected.expected}, actual=${if (hasWritings) "hasWritings" else "noWritings"} (cov=${"%.3f".format(coverage)})"
                        mismatches += mismatch
                        println("PEN_WRITING_MISMATCH $mismatch")
                    }
                }
            } finally {
                kernel.release()
                cleanedThresh.release()
                thresh.release()
                gray.release()
                bitmap.recycle()
            }
        }
        val summary = "CellAnalyzer penWriting mismatches: total=${mismatches.size}, falsePositives=$falsePositives, falseNegatives=$falseNegatives"
        println("PEN_WRITING_SUMMARY $summary")
        assertTrue("$summary\n${mismatches.joinToString("\n")}", mismatches.isEmpty())
    }

    private fun findFiles(assets: android.content.res.AssetManager, path: String, fileName: String): List<String> =
        assets.list(path).orEmpty().flatMap { entry ->
            val child = "$path/$entry"
            if (entry == fileName) listOf(child) else findFiles(assets, child, fileName)
        }
}
