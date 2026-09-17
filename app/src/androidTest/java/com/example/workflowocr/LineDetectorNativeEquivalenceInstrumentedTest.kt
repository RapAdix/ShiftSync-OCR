package com.example.workflowocr

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import org.opencv.android.OpenCVLoader
import org.opencv.core.Point

/** Compares the native merger with the unchanged Kotlin implementation on saved Hough output. */
class LineDetectorNativeEquivalenceInstrumentedTest {
    @Test
    fun nativeMergerMatchesKotlinMergerForPublicAndSecretFixtures() {
        check(OpenCVLoader.initLocal()) { "Could not initialize OpenCV" }
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val json = Json { ignoreUnknownKeys = true }
        val fixturePaths = listOf(
            "test_fixtures_public/sample6/hough_segments.json",
            "test_fixtures_secret/sample5/hough_segments.json"
        )

        fixturePaths.forEach { path ->
            val fixture = assets.open(path).use { json.decodeFromString<Fixture>(it.reader().readText()) }
            val maxDimension = maxOf(fixture.imageWidth, fixture.imageHeight).toDouble()
            val cases = listOf(
                "horizontal" to (fixture.horizontal to true),
                "vertical" to (fixture.vertical to false)
            )
            cases.forEach { (axis, data) ->
                val (encodedSegments, horizontal) = data
                val segments = encodedSegments.map { pair ->
                    val first = Point(pair[0][0], pair[0][1])
                    val second = Point(pair[1][0], pair[1][1])
                    first to second
                }
                val distanceThreshold = maxDimension * 0.005
                val longestAllowedBacktrack = if (horizontal) fixture.imageWidth * 0.1 else fixture.imageHeight * 0.1
                val kotlin = LineDetector.mergeOrderedTracksForTesting(
                    segments,
                    distanceThreshold,
                    horizontal,
                    longestAllowedBacktrack
                )
                val native = LineDetectorNative.mergeOrderedTracks(
                    segments,
                    distanceThreshold,
                    horizontal,
                    longestAllowedBacktrack
                )
                assertEquals("$path axis=$axis line count", kotlin.size, native.size)
                kotlin.zip(native).forEachIndexed { index, (expected, actual) ->
                    assertEquals("$path axis=$axis line=$index point count", expected.points.size, actual.points.size)
                    expected.points.zip(actual.points).forEachIndexed { pointIndex, (expectedPoint, actualPoint) ->
                        assertEquals("$path axis=$axis line=$index point=$pointIndex x", expectedPoint.x, actualPoint.x, 0.0)
                        assertEquals("$path axis=$axis line=$index point=$pointIndex y", expectedPoint.y, actualPoint.y, 0.0)
                    }
                }
            }
        }
    }

    /**
     * Hough fixture schema:
     * {
     *   "imageWidth": 3000,
     *   "imageHeight": 4000,
     *   "hough": {
     *     "rho": 1.0,
     *     "thetaDegrees": 1.0,
     *     "threshold": 20,
     *     "minLineLength": 35.0,
     *     "maxLineGap": 20.0
     *   },
     *   "raw": [[x1, y1, x2, y2], ...],
     *   "horizontal": [[[x1, y1], [x2, y2]], ...],
     *   "vertical": [[[x1, y1], [x2, y2]], ...]
     * }
     *
     * The normalized horizontal/vertical arrays are the exact segment lists
     * consumed by the merger; endpoints are ordered along their main axis.
     */
    @Serializable
    private data class Fixture(
        val imageWidth: Int,
        val imageHeight: Int,
        val horizontal: List<List<List<Double>>>,
        val vertical: List<List<List<Double>>>
    )
}
