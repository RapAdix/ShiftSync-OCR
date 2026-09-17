package com.example.workflowocr

import org.opencv.core.Point

/** Native, behavior-preserving port of the ordered Hough-track merger and its data conversion. */
object LineDetectorNative {
    init {
        System.loadLibrary("line_detector_native")
    }

    /** Merges normalized Hough segments using the native implementation. */
    fun mergeOrderedTracks(
        lines: List<HoughSegment>,
        distanceThreshold: Double,
        isHorizontal: Boolean,
        longestAllowedBacktrack: Double
    ): List<PolyLineSegment> {
        if (lines.isEmpty()) return emptyList()
        val input = lines.flatMap { (first, second) ->
            listOf(first.x, first.y, second.x, second.y)
        }.toDoubleArray()
        val encoded = mergeOrderedTracksRaw(
            input,
            distanceThreshold,
            isHorizontal,
            longestAllowedBacktrack
        )
        var index = 0
        val lineCount = encoded[index++].toInt()
        return List(lineCount) {
            val pointCount = encoded[index++].toInt()
            val points = MutableList(pointCount) {
                Point(encoded[index++], encoded[index++])
            }
            PolyLineSegment(points)
        }
    }

    /**
     * [segments] is flattened as x1,y1,x2,y2 for each segment.
     * The result is encoded as lineCount, pointCount, x,y... for each line.
     */
    private external fun mergeOrderedTracksRaw(
        segments: DoubleArray,
        distanceThreshold: Double,
        isHorizontal: Boolean,
        longestAllowedBacktrack: Double
    ): DoubleArray
}
