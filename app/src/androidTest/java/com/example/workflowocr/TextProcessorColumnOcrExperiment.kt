package com.example.workflowocr

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.core.Point
import java.io.File
import kotlin.system.measureTimeMillis

@RunWith(AndroidJUnit4::class)
class TextProcessorColumnOcrExperiment {
    @Serializable private data class Fixture(val image: String, val layout: String, val cells: List<ExpectedCell>)
    @Serializable private data class ExpectedCell(val row: Int, val column: Int, val corners: List<List<Double>>)

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    @Ignore("Manual OCR approach benchmark; run explicitly when evaluating OCR changes.")
    fun comparePerCellAndColumnOcr() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        val reportRoot = File(instrumentation.targetContext.getExternalFilesDir(null), "ocr_comparison")
        val paths = listOf("test_fixtures_public", "test_fixtures_secret")
            .flatMap { findFiles(assets, it, "cells.json") }
        assertTrue("No public text fixtures were found", paths.any { it.startsWith("test_fixtures_public/") })

        val reports = mutableListOf<String>()
        paths.forEach { path ->
            val fixture = assets.open(path).use { json.decodeFromString<Fixture>(it.reader().readText()) }
            val settings = when (fixture.layout) {
                "12" -> PresetDefaults.layout12Col
                "13" -> PresetDefaults.layout13Col
                else -> error("Unsupported fixture layout: ${fixture.layout}")
            }
            val cells = fixture.toTableCells()
            val targetCols = listOf(settings.nameCol, settings.timeStartCol, settings.timeEndCol)
            val bitmap = assets.open("${path.substringBeforeLast('/')}/${fixture.image}").use(BitmapFactory::decodeStream)
            try {
                lateinit var perCell: Array<Array<String>>
                val perCellMillis = measureTimeMillis {
                    perCell = TextProcessor.extractTextFromCells(cells, bitmap, targetCols)
                }
                lateinit var columns: Array<Array<String>>
                val columnsMillis = measureTimeMillis {
                    columns = TextProcessor.extractTextFromColumns(cells, bitmap, targetCols)
                }

                var baselineNonBlank = 0
                var columnsNonBlank = 0
                cells.indices.forEach { row ->
                    targetCols.forEach { col ->
                        val baseline = perCell[row][col]
                        if (baseline.isNotBlank()) baselineNonBlank++
                        if (columns[row][col].isNotBlank()) columnsNonBlank++
                    }
                }
                reports += "TEXT_OCR_COLUMN_COMPARISON path=$path perCellMs=$perCellMillis " +
                    "columnsMs=$columnsMillis baselineNonBlank=$baselineNonBlank columnsNonBlank=$columnsNonBlank"
                writeComparisonReport(
                    File(reportRoot, "${path.substringBeforeLast('/')}/text_ocr_comparison.txt"), cells, targetCols,
                    perCell, columns
                )
            } finally {
                bitmap.recycle()
            }
        }
        reports.forEach(::println)
        assertTrue("No OCR fixtures were processed", reports.isNotEmpty())
    }

    private fun writeComparisonReport(
        file: File,
        cells: Array<Array<TableDetector.TableCell>>,
        targetCols: List<Int>,
        perCell: Array<Array<String>>,
        columns: Array<Array<String>>
    ) {
        file.parentFile?.mkdirs()
        file.writeText(buildString {
            appendLine("Per-cell OCR is the baseline. DIFF marks a result that differs beyond the allowed small lexical distance.")
            cells.indices.forEach { row ->
                targetCols.forEach { col ->
                    val baseline = perCell[row][col]
                    val columnText = columns[row][col]
                    appendLine()
                    appendLine("row=$row column=$col")
                    appendLine("per_cell: ${display(baseline)}")
                    appendLine("columns_1_5x_with_fallback [${comparisonLabel(baseline, columnText)}]: ${display(columnText)}")
                }
            }
        })
    }

    private fun comparisonLabel(baseline: String, candidate: String): String =
        if (equivalentText(baseline, candidate)) "MATCH" else "DIFF"

    private fun display(text: String): String = text.replace("\n", "\\n")

    private fun Fixture.toTableCells(): Array<Array<TableDetector.TableCell>> {
        val rows = cells.maxOf { it.row } + 1
        val cols = cells.maxOf { it.column } + 1
        return Array(rows) { row ->
            Array(cols) { col ->
                val expected = cells.first { it.row == row && it.column == col }
                val points = expected.corners.map { Point(it[0], it[1]) }
                TableDetector.TableCell(points[0], points[1], points[3], points[2])
            }
        }
    }

    private fun equivalentText(first: String, second: String): Boolean {
        val normalizedFirst = normalize(first)
        val normalizedSecond = normalize(second)
        if (normalizedFirst == normalizedSecond) return true
        if (normalizedFirst.isEmpty() || normalizedSecond.isEmpty()) return false
        return levenshtein(normalizedFirst, normalizedSecond) <= 1
    }

    private fun normalize(text: String): String = text
        .lowercase()
        .replace(Regex("\\s+"), "")
        .replace(';', ':')
        .replace('.', ':')
        .replace(',', ':')

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

    private fun findFiles(assets: android.content.res.AssetManager, path: String, fileName: String): List<String> =
        assets.list(path).orEmpty().flatMap { entry ->
            val child = "$path/$entry"
            if (entry == fileName) listOf(child) else findFiles(assets, child, fileName)
        }
}
