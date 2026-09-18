package com.example.workflowocr

import android.content.res.AssetManager
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import org.opencv.android.OpenCVLoader
import org.opencv.core.Point

/** Verifies native merged lines against checked-in outputs from the former Kotlin implementation. */
class LineDetectorNativeRegressionInstrumentedTest {
    @Test
    fun nativeMergerMatchesGoldenOutputsForAvailableFixtures() {
        check(OpenCVLoader.initLocal()) { "Could not initialize OpenCV" }
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val json = Json { ignoreUnknownKeys = true }
        val fixturePaths = listOf("test_fixtures_public", "test_fixtures_secret")
            .flatMap { findFiles(assets, it, "hough_segments.json") }

        check(fixturePaths.isNotEmpty()) { "No Hough segment fixtures were found" }
        fixturePaths.forEach { fixturePath ->
            val directory = fixturePath.substringBeforeLast('/')
            val goldenPath = "$directory/merged_lines_golden.json"
            val fixture = assets.open(fixturePath).use { json.decodeFromString<Fixture>(it.reader().readText()) }
            val golden = assets.open(goldenPath).use { json.decodeFromString<Golden>(it.reader().readText()) }
            val maxDimension = maxOf(fixture.imageWidth, fixture.imageHeight).toDouble()

            verifyAxis(
                fixturePath,
                "horizontal",
                fixture.horizontal,
                golden.horizontal,
                horizontal = true,
                distanceThreshold = maxDimension * 0.005,
                longestAllowedBacktrack = fixture.imageWidth * 0.1
            )
            verifyAxis(
                fixturePath,
                "vertical",
                fixture.vertical,
                golden.vertical,
                horizontal = false,
                distanceThreshold = maxDimension * 0.005,
                longestAllowedBacktrack = fixture.imageHeight * 0.1
            )
        }
    }

    private fun verifyAxis(
        fixturePath: String,
        axis: String,
        encodedSegments: List<List<List<Double>>>,
        expectedLines: List<List<List<Double>>>,
        horizontal: Boolean,
        distanceThreshold: Double,
        longestAllowedBacktrack: Double
    ) {
        val segments = encodedSegments.map { pair ->
            Point(pair[0][0], pair[0][1]) to Point(pair[1][0], pair[1][1])
        }
        val actualLines = LineDetectorNative.mergeOrderedTracks(
            segments,
            distanceThreshold,
            horizontal,
            longestAllowedBacktrack
        )

        assertEquals("$fixturePath axis=$axis line count", expectedLines.size, actualLines.size)
        expectedLines.zip(actualLines).forEachIndexed { lineIndex, (expected, actual) ->
            assertEquals("$fixturePath axis=$axis line=$lineIndex point count", expected.size, actual.points.size)
            expected.zip(actual.points).forEachIndexed { pointIndex, (expectedPoint, actualPoint) ->
                assertEquals("$fixturePath axis=$axis line=$lineIndex point=$pointIndex x", expectedPoint[0], actualPoint.x, 0.0)
                assertEquals("$fixturePath axis=$axis line=$lineIndex point=$pointIndex y", expectedPoint[1], actualPoint.y, 0.0)
            }
        }
    }

    private fun findFiles(assets: AssetManager, path: String, fileName: String): List<String> =
        assets.list(path).orEmpty().flatMap { entry ->
            val child = "$path/$entry"
            if (entry == fileName) listOf(child) else findFiles(assets, child, fileName)
        }

    @Serializable
    private data class Fixture(
        val imageWidth: Int,
        val imageHeight: Int,
        val horizontal: List<List<List<Double>>>,
        val vertical: List<List<List<Double>>>
    )

    @Serializable
    private data class Golden(
        val horizontal: List<List<List<Double>>>,
        val vertical: List<List<List<Double>>>
    )
}
