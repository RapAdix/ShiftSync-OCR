package com.example.workflowocr

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.workflowocr.processing.VlhTableExtractor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.runBlocking
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import java.io.File
import java.io.FileNotFoundException
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@RunWith(AndroidJUnit4::class)
class VlhRegressionTest {

    private data class Fixture(
        val name: String,
        val workbookAsset: String,
        val imageAsset: String,
        val headerRowCount: Int,
        val expectedColumnCount: Int,
        val acceptedEditDistance: Int
    )

    private val fixtures = listOf(
        Fixture(
            name = "vlh_original",
            workbookAsset = "test_fixtures_secret/vlh_tests/vlh table.xlsx",
            imageAsset = "test_fixtures_secret/vlh_tests/secret_vlh_nodpi.jpg",
            headerRowCount = 1,
            expectedColumnCount = 14,
            acceptedEditDistance = 101
        ),
        Fixture(
            name = "vlh_ba_weekday",
            workbookAsset = "test_fixtures_secret/vlh_tests/vlh_ba_weekday.xlsx",
            imageAsset = "test_fixtures_secret/vlh_tests/vlh_ba_weekday.jpg",
            headerRowCount = 2,
            expectedColumnCount = 18,
            // Independent baseline for this fixture, measured on the reference Huawei.
            acceptedEditDistance = 76
        ),
        Fixture(
            name = "vlh_ba_weekend",
            workbookAsset = "test_fixtures_secret/vlh_tests/vlh_ba_weekend.xlsx",
            imageAsset = "test_fixtures_secret/vlh_tests/vlh_ba_weekend.jpg",
            headerRowCount = 2,
            expectedColumnCount = 18,
            // Independent baseline for this fixture, measured on the reference Huawei.
            acceptedEditDistance = 53
        )
    )

    private fun normalize(text: String): String =
        text.lowercase(Locale.ROOT).replace(Regex("\\s+"), "").replace("o", "0")

    private fun levenshtein(first: String, second: String): Int {
        var previous = IntArray(second.length + 1) { it }
        first.forEachIndexed { row, firstChar ->
            val current = IntArray(second.length + 1)
            current[0] = row + 1
            second.forEachIndexed { column, secondChar ->
                current[column + 1] = minOf(
                    current[column] + 1,
                    previous[column + 1] + 1,
                    previous[column] + if (firstChar == secondChar) 0 else 1
                )
            }
            previous = current
        }
        return previous.last()
    }

    private fun readExpectedRows(fixture: Fixture, assets: android.content.res.AssetManager): List<List<String>> =
        assets.open(fixture.workbookAsset).use { stream ->
            WorkbookFactory.create(stream).use { workbook ->
                val sheet = workbook.getSheetAt(0)
                (fixture.headerRowCount until sheet.lastRowNum + 1)
                    .mapNotNull { rowIndex -> sheet.getRow(rowIndex) }
                    .map { row ->
                        (0 until fixture.expectedColumnCount).map { column ->
                            row.getCell(column)?.let { cell ->
                                if (cell.cellType == CellType.NUMERIC && cell.numericCellValue % 1.0 == 0.0) {
                                    cell.numericCellValue.toLong().toString()
                                } else {
                                    cell.toString().trim()
                                }
                            }.orEmpty()
                        }
                    }
                    .dropLastWhile { row -> row.all(String::isBlank) }
            }
        }

    private fun fixtureAssetsAreAvailable(fixture: Fixture, assets: android.content.res.AssetManager): Boolean =
        try {
            assets.open(fixture.workbookAsset).use { }
            assets.open(fixture.imageAsset).use { }
            true
        } catch (_: FileNotFoundException) {
            false
        }

    @Test
    fun testVlhDetectionRegression() = runBlocking {
        assertTrue("OpenCV initialization failed", OpenCVLoader.initLocal())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val results = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val availableFixtures = fixtures.filter { fixtureAssetsAreAvailable(it, assets) }
        if (availableFixtures.isEmpty()) {
            recognizer.close()
            println("VLH regression fixtures are not installed locally; skipping the secret-fixture test.")
            return@runBlocking
        }

        try {
            for (fixture in availableFixtures) {
                val expectedRows = readExpectedRows(fixture, assets)
                check(expectedRows.isNotEmpty()) { "${fixture.name}: workbook contains no data rows" }

                val bitmap = assets.open(fixture.imageAsset).use(BitmapFactory::decodeStream)
                    ?: error("${fixture.name}: could not decode fixture image")
                try {
                    val visionText = suspendCancellableCoroutine { continuation ->
                        recognizer.process(InputImage.fromBitmap(bitmap, 0))
                            .addOnSuccessListener { continuation.resume(it) }
                            .addOnFailureListener { continuation.resumeWithException(it) }
                    }

                    val extracted = VlhTableExtractor.extract(visionText)
                    val table = extracted.rows
                    val actualColumnCount = table.firstOrNull()?.size
                        ?: (extracted.gcColumnIndices.maxOrNull()?.plus(1) ?: 0)
                    val actualRowCount = table.size
                    val recognizedHeaderMarkers = visionText.textBlocks.flatMap { it.lines }
                        .flatMap { it.elements }
                        .filter { element ->
                            val normalized = element.text.uppercase(Locale.ROOT)
                            normalized.contains("LP") || normalized.contains("GC") ||
                                normalized.contains("STANOWISKO") || normalized.contains("POSITIONS")
                        }
                        .joinToString { element ->
                            val x = element.cornerPoints?.map { it.x }?.average()?.toInt() ?: -1
                            "${element.text}@$x"
                        }
                    val maxRows = maxOf(expectedRows.size, actualRowCount)
                    var editDistance = 0
                    var differentCellCount = 0
                    var expectedCharacterCount = 0
                    val distanceByColumn = IntArray(fixture.expectedColumnCount)
                    val differentCellsByColumn = IntArray(fixture.expectedColumnCount)
                    val nonemptyExpectedByColumn = IntArray(fixture.expectedColumnCount)
                    val nonemptyActualByColumn = IntArray(fixture.expectedColumnCount)

                    for (row in 0 until maxRows) {
                        val expectedRow = expectedRows.getOrNull(row).orEmpty()
                        val actualRow = table.getOrNull(row) ?: emptyArray()
                        for (column in 0 until fixture.expectedColumnCount) {
                            if (fixture.name == "vlh_original" && column == 7 && row in 27..50) {
                                continue // This source region is known to be blurred/unreadable.
                            }
                            val expected = expectedRow.getOrElse(column) { "" }
                            val actual = actualRow.getOrNull(column) ?: ""
                            val normalizedExpected = normalize(expected)
                            val normalizedActual = normalize(actual)
                            val distance = levenshtein(normalizedExpected, normalizedActual)
                            editDistance += distance
                            distanceByColumn[column] += distance
                            expectedCharacterCount += normalizedExpected.length
                            if (normalizedExpected.isNotEmpty()) nonemptyExpectedByColumn[column]++
                            if (normalizedActual.isNotEmpty()) nonemptyActualByColumn[column]++
                            if (distance > 0) {
                                differentCellCount++
                                differentCellsByColumn[column]++
                            }
                        }
                    }

                    val result = "${fixture.name}: expected=${expectedRows.size}x${fixture.expectedColumnCount}, " +
                        "actual=${actualRowCount}x$actualColumnCount, gcColumns=${extracted.gcColumnIndices}, " +
                        "deskewDegrees=${"%.3f".format(Locale.ROOT, extracted.deskewAngleDegrees)}, " +
                        "tiltedRetry=${extracted.usedTiltedSeparators}, " +
                        "editDistance=$editDistance, differingCells=$differentCellCount, " +
                        "expectedCharacters=$expectedCharacterCount, " +
                        "distanceByColumn=${distanceByColumn.toList()}, " +
                        "differentCellsByColumn=${differentCellsByColumn.toList()}, " +
                        "expectedNonemptyByColumn=${nonemptyExpectedByColumn.toList()}, " +
                        "actualNonemptyByColumn=${nonemptyActualByColumn.toList()}, " +
                        "accepted=${fixture.acceptedEditDistance}"
                    results += result
                    println("VLH_REGRESSION $result")
                    println("VLH_HEADER_MARKERS ${fixture.name}: $recognizedHeaderMarkers")

                    if (actualColumnCount != fixture.expectedColumnCount) {
                        failures += "${fixture.name}: expected ${fixture.expectedColumnCount} columns, detected $actualColumnCount"
                    }
                    if (editDistance > fixture.acceptedEditDistance) {
                        failures += "${fixture.name}: edit distance $editDistance exceeds its baseline ${fixture.acceptedEditDistance}"
                    }
                } finally {
                    bitmap.recycle()
                }
            }
        } finally {
            recognizer.close()
        }

        val summary = results.joinToString("\n")
        File(instrumentation.targetContext.filesDir, "vlh_regression_summary.txt").writeText(summary)
        assertTrue(
            "VLH regression failures:\n${failures.joinToString("\n")}\nResults:\n$summary",
            failures.isEmpty()
        )
    }
}
