package com.example.workflowocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.workflowocr.processing.VlhTableExtractor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.apache.poi.xssf.usermodel.XSSFWorkbook
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

    @Serializable
    private data class IgnoredRegion(
        val column: Int,
        val startRow: Int,
        val endRow: Int
    )

    @Serializable
    private data class FixtureSpec(
        val name: String,
        val workbook: String,
        val image: String,
        val headerRowCount: Int,
        val expectedColumnCount: Int,
        val acceptedColumnCount: Int? = null,
        val acceptedEditDistance: Int,
        val ignoredRegions: List<IgnoredRegion> = emptyList()
    )

    private data class Fixture(
        val name: String,
        val workbookAsset: String,
        val imageAsset: String,
        val headerRowCount: Int,
        val expectedColumnCount: Int,
        val acceptedColumnCount: Int,
        val acceptedEditDistance: Int,
        val ignoredRegions: List<IgnoredRegion>
    )

    private data class ElementDiag(
        val text: String,
        val left: Int,
        val right: Int,
        val top: Int,
        val bottom: Int,
        val cx: Int,
        val cy: Int
    )

    private val json = Json { ignoreUnknownKeys = true }

    private fun loadFixtures(assets: android.content.res.AssetManager): List<Fixture> {
        val directories = listOf("test_fixtures_public/vlh_tests", "test_fixtures_secret/vlh_tests")
        return directories.flatMap { dir ->
            try {
                val jsonString = assets.open("$dir/vlh_fixtures.json").bufferedReader().use { it.readText() }
                val specs = json.decodeFromString<List<FixtureSpec>>(jsonString)
                specs.map { spec ->
                    Fixture(
                        name = spec.name,
                        workbookAsset = "$dir/${spec.workbook}",
                        imageAsset = "$dir/${spec.image}",
                        headerRowCount = spec.headerRowCount,
                        expectedColumnCount = spec.expectedColumnCount,
                        acceptedColumnCount = spec.acceptedColumnCount ?: spec.expectedColumnCount,
                        acceptedEditDistance = spec.acceptedEditDistance,
                        ignoredRegions = spec.ignoredRegions
                    )
                }
            } catch (_: FileNotFoundException) {
                emptyList()
            }
        }.filter { fixtureAssetsAreAvailable(it, assets) }
    }

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

    private fun writePublicFixtureDebugOutputs(
        debugDir: File,
        fixture: Fixture,
        bitmap: Bitmap,
        visionText: com.google.mlkit.vision.text.Text,
        extracted: VlhTableExtractor.ExtractedTable,
        diffs: List<String>
    ) {
        require(fixture.workbookAsset.startsWith("test_fixtures_public/") && !fixture.workbookAsset.contains("secret"))

        // 1. <fixture.name>_debug.jpg
        val debugBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(debugBitmap)
        val polyPaint = Paint().apply {
            color = Color.GREEN
            strokeWidth = 3f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }
        val textPaint = Paint().apply {
            color = Color.RED
            textSize = 22f
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        for (element in visionText.textBlocks.flatMap { it.lines }.flatMap { it.elements }) {
            val points = element.cornerPoints
            if (points != null && points.size == 4) {
                val path = Path().apply {
                    moveTo(points[0].x.toFloat(), points[0].y.toFloat())
                    lineTo(points[1].x.toFloat(), points[1].y.toFloat())
                    lineTo(points[2].x.toFloat(), points[2].y.toFloat())
                    lineTo(points[3].x.toFloat(), points[3].y.toFloat())
                    close()
                }
                canvas.drawPath(path, polyPaint)
                val minX = points.minOf { it.x }.toFloat()
                val minY = points.minOf { it.y }.toFloat()
                canvas.drawText(element.text, minX, (minY - 4f).coerceAtLeast(20f), textPaint)
            } else if (element.boundingBox != null) {
                val b = element.boundingBox!!
                canvas.drawRect(
                    b.left.toFloat(),
                    b.top.toFloat(),
                    b.right.toFloat(),
                    b.bottom.toFloat(),
                    polyPaint
                )
                canvas.drawText(element.text, b.left.toFloat(), (b.top.toFloat() - 4f).coerceAtLeast(20f), textPaint)
            }
        }

        val sepPaint = Paint().apply {
            color = Color.CYAN
            strokeWidth = 5f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }
        for ((startX, startY, endX, endY) in extracted.columnSeparatorLines) {
            canvas.drawLine(
                startX.toFloat(),
                startY.toFloat(),
                endX.toFloat(),
                endY.toFloat(),
                sepPaint
            )
        }

        File(debugDir, "${fixture.name}_debug.jpg").outputStream().use { out ->
            debugBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        debugBitmap.recycle()

        // 2. <fixture.name>_extracted.xlsx
        XSSFWorkbook().use { workbook ->
            val sheet = workbook.createSheet("Extracted")
            extracted.rows.forEachIndexed { rowIndex, rowData ->
                val row = sheet.createRow(rowIndex)
                rowData.forEachIndexed { colIndex, cellValue ->
                    val cell = row.createCell(colIndex)
                    cell.setCellValue(cellValue)
                }
            }
            File(debugDir, "${fixture.name}_extracted.xlsx").outputStream().use { out ->
                workbook.write(out)
            }
        }

        // 3. <fixture.name>_diag.txt
        val cutoffY = bitmap.height * 0.25
        val headerElements = visionText.textBlocks
            .asSequence()
            .flatMap { it.lines }
            .flatMap { it.elements }
            .mapNotNull { element ->
                val points = element.cornerPoints
                if (!points.isNullOrEmpty()) {
                    val left = points.minOf { it.x }
                    val right = points.maxOf { it.x }
                    val top = points.minOf { it.y }
                    val bottom = points.maxOf { it.y }
                    val cx = (left + right) / 2
                    val cy = (top + bottom) / 2
                    ElementDiag(element.text, left, right, top, bottom, cx, cy)
                } else {
                    val b = element.boundingBox
                    if (b != null) {
                        val cx = (b.left + b.right) / 2
                        val cy = (b.top + b.bottom) / 2
                        ElementDiag(element.text, b.left, b.right, b.top, b.bottom, cx, cy)
                    } else null
                }
            }
            .filter { it.cy < cutoffY }
            .sortedWith(compareBy({ it.cy }, { it.cx }))
            .toList()

        val diagText = buildString {
            appendLine("dimensions: ${bitmap.width}x${bitmap.height}")
            appendLine("deskewDegrees: ${extracted.deskewAngleDegrees}")
            appendLine("usedTiltedSeparators: ${extracted.usedTiltedSeparators}")
            appendLine("gcColumnIndices: ${extracted.gcColumnIndices}")
            appendLine("columnBounds: ${extracted.columnBounds}")
            appendLine()
            appendLine("--- Header OCR elements (centerY < ${bitmap.height * 0.25}) ---")
            for ((text, left, right, top, bottom, cx, cy) in headerElements) {
                appendLine("[$text] box=($left..$right, $top..$bottom) center=($cx, $cy)")
            }
            appendLine()
            appendLine("--- Cell diffs (${diffs.size}) ---")
            for (diff in diffs) {
                appendLine(diff)
            }
        }
        File(debugDir, "${fixture.name}_diag.txt").writeText(diagText)
    }

    @Test
    fun testVlhDetectionRegression() = runBlocking {
        assertTrue("OpenCV initialization failed", OpenCVLoader.initLocal())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val results = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val availableFixtures = loadFixtures(assets)
        val debugDir = File(instrumentation.targetContext.filesDir, "vlh_debug").apply { mkdirs() }
        val externalDebugDir = instrumentation.targetContext.getExternalFilesDir(null)?.let {
            File(it, "vlh_debug").apply { mkdirs() }
        }

        recognizer.use {
            assertTrue(
                "No public VLH regression fixtures were found",
                availableFixtures.any { it.workbookAsset.startsWith("test_fixtures_public/") }
            )
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
                    val recognizedHeaderMarkers = visionText.textBlocks.asSequence()
                        .flatMap { it.lines }
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
                    val cellDiffs = mutableListOf<String>()

                    for (row in 0 until maxRows) {
                        val expectedRow = expectedRows.getOrNull(row).orEmpty()
                        val actualRow = table.getOrNull(row) ?: emptyArray()
                        for (column in 0 until fixture.expectedColumnCount) {
                            if (fixture.ignoredRegions.any { it.column == column && row in it.startRow..it.endRow }) {
                                continue // This source region is known to be blurred/unreadable.
                            }
                            val expected = expectedRow.getOrElse(column) { "" }
                            val actual = actualRow.getOrNull(column) ?: ""
                            val normalizedExpected = normalize(expected)
                            val normalizedActual = normalize(actual)
                            val distance = levenshtein(normalizedExpected, normalizedActual)
                            if (normalizedExpected != normalizedActual) {
                                cellDiffs += "r=$row c=$column exp=[$expected] act=[$actual]"
                            }
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

                    if (fixture.workbookAsset.startsWith("test_fixtures_public/")) {
                        writePublicFixtureDebugOutputs(
                            debugDir = debugDir,
                            fixture = fixture,
                            bitmap = bitmap,
                            visionText = visionText,
                            extracted = extracted,
                            diffs = cellDiffs
                        )
                        if (externalDebugDir != null) {
                            writePublicFixtureDebugOutputs(
                                debugDir = externalDebugDir,
                                fixture = fixture,
                                bitmap = bitmap,
                                visionText = visionText,
                                extracted = extracted,
                                diffs = cellDiffs
                            )
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
                    Log.i("VLH_REGRESSION", result)
                    Log.i("VLH_HEADER_MARKERS", "${fixture.name}: $recognizedHeaderMarkers")
                    println("VLH_REGRESSION $result")
                    println("VLH_HEADER_MARKERS ${fixture.name}: $recognizedHeaderMarkers")

                    if (actualColumnCount != fixture.acceptedColumnCount) {
                        failures += "${fixture.name}: expected ${fixture.acceptedColumnCount} columns, detected $actualColumnCount"
                    }
                    if (editDistance > fixture.acceptedEditDistance) {
                        failures += "${fixture.name}: edit distance $editDistance exceeds its baseline ${fixture.acceptedEditDistance}"
                    }
                } finally {
                    bitmap.recycle()
                }
            }
        }

        val summary = results.joinToString("\n")
        File(instrumentation.targetContext.filesDir, "vlh_regression_summary.txt").writeText(summary)
        File(debugDir, "vlh_regression_summary.txt").writeText(summary)
        externalDebugDir?.let { File(it, "vlh_regression_summary.txt").writeText(summary) }
        assertTrue(
            "VLH regression failures:\n${failures.joinToString("\n")}\nResults:\n$summary",
            failures.isEmpty()
        )
    }
}
