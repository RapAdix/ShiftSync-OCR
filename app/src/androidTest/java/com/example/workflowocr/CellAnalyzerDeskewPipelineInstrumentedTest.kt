package com.example.workflowocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.Point

/**
 * Production-pipeline crossing test: RAW (tilted) photo -> gray -> deskewGrayMat -> detectTableCellsByLines
 * -> createCrossingThresh(detection.gray) -> detectPenCrossing, compared with the ground truth of the
 * sample9 fixture (whose cell corners live in the deskewed image's coordinates).
 *
 * Three variants are reported so a mismatch can be attributed:
 *  A. [PROD]     detected cells on the freshly deskewed in-memory Mat (exactly what production does)
 *  B. [GT_CELLS] fixture GT corners on the freshly deskewed in-memory Mat (isolates deskew/threshold effects)
 *  C. [FILE]     fixture GT corners on the saved input_deskewed.jpg (what CellAnalyzerFixtureInstrumentedTest does)
 */
@RunWith(AndroidJUnit4::class)
class CellAnalyzerDeskewPipelineInstrumentedTest {
    private val json = Json { ignoreUnknownKeys = true }
    @Serializable private data class Fixture(val image: String, val source: String? = null, val layout: String, val cells: List<ExpectedCell>)
    @Serializable private data class ExpectedCell(val row: Int, val column: Int, val corners: List<List<Double>>, val expected: String)

    private val dir = "test_fixtures_public/schedule_tests/sample9"

    @Test
    fun rawSample9ThroughProductionDeskewMatchesGroundTruthCrossing() {
        assert(OpenCVLoader.initDebug())
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val fixture = assets.open("$dir/cells.json").use { json.decodeFromString<Fixture>(it.reader().readText()) }
        val layout = PresetDefaults.layout12Col
        val targetColumns = listOf(layout.timeStartCol, layout.timeEndCol)
        val expectedCells = fixture.cells.filter { it.row != 0 && it.column in targetColumns && it.expected in setOf("crossed", "not_crossed") }

        val rawBitmap = assets.open("$dir/sample9.jpg").use(BitmapFactory::decodeStream)
        val fileBitmap = assets.open("$dir/${fixture.image}").use(BitmapFactory::decodeStream)
        val rawGray = ImageProcessor.bitmapToGrayMat(rawBitmap)
        val fileGray = ImageProcessor.bitmapToGrayMat(fileBitmap)
        val deskewed = TableDetector.deskewGrayMat(rawGray) ?: rawGray
        val report = StringBuilder()
        var totalMismatches = 0
        try {
            fun evaluate(label: String, thresh: Mat, countInTotal: Boolean = false, cellOf: (ExpectedCell) -> TableDetector.TableCell?) {
                var fp = 0; var fn = 0; var skipped = 0
                val details = StringBuilder()
                expectedCells.forEach { expected ->
                    val cell = cellOf(expected)
                    if (cell == null) { skipped++; return@forEach }
                    val (crossed, _) = CellAnalyzer.detectPenCrossing(thresh, cell)
                    val expectedCrossed = expected.expected == "crossed"
                    if (crossed != expectedCrossed) {
                        if (expectedCrossed) fn++ else fp++
                                val gt = gtCell(expected)
                        fun d(a: Point, b: Point) = "(%.1f,%.1f)".format(a.x - b.x, a.y - b.y)
                        details.appendLine("  $label row=${expected.row} col=${expected.column} expected=${expected.expected} actual=${if (crossed) "crossed" else "not_crossed"} cellSize=%.0fx%.0f offsets[TL,TR,BR,BL]=${d(cell.topLeft, gt.topLeft)} ${d(cell.topRight, gt.topRight)} ${d(cell.bottomRight, gt.bottomRight)} ${d(cell.bottomLeft, gt.bottomLeft)}".format(gt.topRight.x - gt.topLeft.x, gt.bottomLeft.y - gt.topLeft.y))
                    }
                }
                if (countInTotal) totalMismatches += fp + fn
                report.appendLine("CROSSING_$label total=${expectedCells.size} falsePositives=$fp falseNegatives=$fn skipped=$skipped")
                report.append(details)
            }

            val fileThresh = ImageProcessor.createCrossingThresh(fileGray)
            val detection = TableDetector.detectTableCellsByLines(deskewed, layout)
            try {
                report.appendLine("RAW=${rawGray.size()} DESKEWED=${deskewed.size()} FILE=${fileGray.size()} DETECTION_GRAY=${detection.gray.size()}")
                report.appendLine("PROD detection=${detection::class.simpleName} rows=${detection.cells.size} cols=${detection.cells.firstOrNull()?.size}")
                if (detection.gray.size() == fileGray.size()) {
                    val diff = Mat()
                    Core.absdiff(detection.gray, fileGray, diff)
                    val mean = MatOfDouble(); val std = MatOfDouble()
                    Core.meanStdDev(diff, mean, std)
                    report.appendLine("DETECTION_GRAY_VS_FILE meanAbsDiff=${mean.toArray()[0]} std=${std.toArray()[0]}")
                    diff.release(); mean.release(); std.release()
                }
                val prodThresh = ImageProcessor.createCrossingThresh(detection.gray)
                try {
                    evaluate("FILE_GT_CELLS", fileThresh) { gtCell(it) }
                    evaluate("PROD", prodThresh, countInTotal = true) { e -> detection.cells.getOrNull(e.row)?.getOrNull(e.column) }
                    evaluate("FILE_GRAY_DETECTED_CELLS", fileThresh) { e -> detection.cells.getOrNull(e.row)?.getOrNull(e.column) }
                    evaluate("PROD_GRAY_GT_CELLS", prodThresh) { gtCell(it) }
                    saveBorderInsetDebugImages(detection.gray, prodThresh, detection.cells, targetColumns.toSet())
                } finally {
                    prodThresh.release(); fileThresh.release()
                }
                val analysis = CellAnalyzer.analyzeCells(detection.gray, detection.thresh, detection.cells, layout)
                report.appendLine("PEN_WRITING_COLS_6_11 (>0.05):")
                for (r in 1 until analysis.size) {
                    val flagged = layout.modificationColumns
                        .map { c -> c to analysis[r].penCoverage[c] }
                        .filter { (_, cov) -> cov > 0.05 }
                    if (flagged.isNotEmpty()) {
                        report.appendLine("  row=$r: " + flagged.joinToString { (c, cov) -> "col$c=${"%.3f".format(cov)}" })
                    }
                }
            } finally {
                releaseDetection(detection)
            }
        } finally {
            if (deskewed !== rawGray) deskewed.release()
            rawGray.release(); fileGray.release()
            rawBitmap.recycle(); fileBitmap.recycle()
        }
        println("CROSSING_DESKEW_REPORT\n$report")
        assertTrue("sample9 production-pipeline crossing mismatches=$totalMismatches\n$report", totalMismatches == 0)
    }

    private fun saveBorderInsetDebugImages(
        gray: Mat,
        thresh: Mat,
        cells: Array<Array<TableDetector.TableCell>>,
        highlightedColumns: Set<Int>
    ) {
        val debugDir = File(
            InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
            "cell_analyzer_debug"
        ).apply { mkdirs() }

        val insetCells = Array(cells.size) { r ->
            Array(cells[r].size) { c ->
                CellAnalyzer.borderInset(thresh, cells[r][c], minHorizontalFraction = 0.0, minVerticalFraction = 0.0)
            }
        }
        val detectedMat = TableDetector.drawCells(gray, cells, highlightedColumns)
        val insetMat = TableDetector.drawCells(gray, insetCells, highlightedColumns)
        val insetOnThreshMat = TableDetector.drawCells(thresh, insetCells, highlightedColumns)
        try {
            saveMatJpg(detectedMat, File(debugDir, "sample9_detected_cells.jpg"))
            saveMatJpg(insetMat, File(debugDir, "sample9_border_inset_cells.jpg"))
            saveMatJpg(insetOnThreshMat, File(debugDir, "sample9_border_inset_cells_on_thresh.jpg"))
        } finally {
            detectedMat.release()
            insetMat.release()
            insetOnThreshMat.release()
        }
    }

    private fun saveMatJpg(mat: Mat, outFile: File) {
        val bmp = ImageProcessor.matToBitmap(mat)
        try {
            FileOutputStream(outFile).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        } finally {
            bmp.recycle()
        }
    }

    private fun gtCell(e: ExpectedCell): TableDetector.TableCell {
        val p = e.corners.map { Point(it[0], it[1]) }
        return TableDetector.TableCell(p[0], p[1], p[3], p[2])
    }

    private fun releaseDetection(d: TableDetector.TableDetectionResult) {
        d.gray.release(); d.thresh.release(); d.mask.release(); d.lines.release()
    }
}
