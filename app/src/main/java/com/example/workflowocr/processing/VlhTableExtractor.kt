package com.example.workflowocr.processing

import android.graphics.PointF
import com.example.workflowocr.TextProcessor
import com.google.mlkit.vision.text.Text
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * High-precision, borderless VLH Table Extraction Engine.
 *
 * Utilizes ML Kit corner-point quadrilateral geometry, local column trajectory tracking,
 * and 2-pass dynamic row cutoff detection to extract 2D tabular data from skewed or curved photos.
 */
object VlhTableExtractor {

    private const val HEADER_ANGLE_TOLERANCE_RADIANS = 0.22
    private const val HEADER_BAND_HEIGHT_MULTIPLIER = 2.0
    private const val MIN_COLUMN_GAP_PX = 50.0
    private const val CONSECUTIVE_EMPTY_ROWS_CUTOFF = 5
    private const val INITIAL_GC_ROWS_TO_SKIP = 5

    // Boundary-line crossing penalties added when a candidate column separator intersects a detected text box.
    // CROSSING_NUMERIC_PENALTY (1000.0) is the base cost for crossing a numeric box (e.g. tightly spaced adjacent numbers).
    // CROSSING_LETTER_PENALTY (3000.0) is 3x higher to heavily penalize slicing through alphabetic position words
    // (e.g. "Kierownik", "Lider"), keeping multi-word job titles intact in the position column.
    // Note: These penalties outweigh normalized geometric distance terms (which lie in 0.0..1.0 range), ensuring
    // that a boundary line will always prefer any available text-free whitespace over slicing through text.
    private const val CROSSING_LETTER_PENALTY = 3000.0
    private const val CROSSING_NUMERIC_PENALTY = 1000.0
    private const val INITIAL_ROW_HEIGHT_SAMPLE_FRACTION = 0.30
    // Preserve the header-derived row phase when early cells are blank; body text may otherwise
    // fit equally well one whole row away because the row spacing is repetitive.
    private const val MAX_LOCAL_TRACK_ORIGIN_SHIFT_FRACTION = 0.20
    private val TILT_RETRY_SLOPES = doubleArrayOf(-0.035, -0.025, -0.015, 0.0, 0.015, 0.025, 0.035)
    // Perspective fit range for separator slopes (dx/dy after deskew). 0.25 is ~14 degrees,
    // enough for photos taken noticeably from below/above the page.
    private const val PERSPECTIVE_MAX_SLOPE = 0.25
    private const val PERSPECTIVE_SLOPE_STEP = 0.005
    private const val PERSPECTIVE_SLOPE_STEPS = 50 // PERSPECTIVE_MAX_SLOPE / PERSPECTIVE_SLOPE_STEP
    private const val SYNTHETIC_INDEX_BAND_HEADER_HEIGHTS = 8.0
    private const val SYNTHETIC_INDEX_MAX_ROW = 5

    /**
     * Represents a single OCR text element box with exact quadrilateral corner points.
     */
    data class TextBox(val text: String, val corners: Array<PointF>) {
        val centerX get() = corners.map { it.x }.average()
        val centerY get() = corners.map { it.y }.average()
        val left get() = corners.minOf { it.x }.toDouble()
        val right get() = corners.maxOf { it.x }.toDouble()
        val top get() = corners.minOf { it.y }.toDouble()
        val bottom get() = corners.maxOf { it.y }.toDouble()

        /**
         * Cleaned alphanumeric tokens for robust header matching (e.g. "POSITIONS", "GC", "START").
         */
        val tokens get() = text.uppercase(Locale.ROOT).split(Regex("[^A-Z0-9]+")).filter(String::isNotBlank)

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as TextBox
            if (text != other.text) return false
            if (!corners.contentEquals(other.corners)) return false
            return true
        }

        override fun hashCode(): Int {
            var result = text.hashCode()
            result = (31 * result) + corners.contentHashCode()
            return result
        }
    }

    private data class GcHeader(val left: Double, val right: Double, val bottom: Double)
    private enum class ColumnKind { INDEX, POSITION, GC, OTHER }
    private data class Column(
        val index: Int,
        val left: ColumnBoundary,
        val right: ColumnBoundary,
        val headerBottom: Double?,
        val kind: ColumnKind = ColumnKind.OTHER
    ) {
        fun contains(x: Double, y: Double): Boolean = x >= left.xAt(y) && x < right.xAt(y)
    }
    private data class ColumnBoundary(val xAtReferenceY: Double, val slope: Double = 0.0, val referenceY: Double = 0.0) {
        fun xAt(y: Double): Double = xAtReferenceY + slope * (y - referenceY)
    }
    private data class HeaderAnchor(val kind: ColumnKind, val left: Double, val right: Double, val top: Double, val bottom: Double) {
        val centerX get() = (left + right) / 2.0
    }
    data class SeparatorLine(val startX: Double, val startY: Double, val endX: Double, val endY: Double)
    /** Whitespace between two neighboring header anchors, where a column separator must start. */
    private data class HeaderGap(val left: Double, val right: Double, val referenceY: Double, val fallbackX: Double) {
        val isOpen get() = right > left
        val center get() = (left + right) / 2.0
    }
    data class PlacedCellBox(
        val row: Int,
        val col: Int,
        val x: Double,
        val y: Double,
        val boxTop: Double,
        val boxLeft: Double,
        val text: String
    )

    data class ExtractedTable(
        val rows: Array<Array<String>>,
        val gcColumnIndices: List<Int>,
        val columnBounds: List<Pair<Double, Double>> = emptyList(),
        val columnSeparatorLines: List<SeparatorLine> = emptyList(),
        val deskewAngleDegrees: Double = 0.0,
        val usedTiltedSeparators: Boolean = false,
        val placedCellBoxes: List<PlacedCellBox> = emptyList()
    )
    private data class PlacedText(val row: Int, val y: Double, val text: String, val boxTop: Double = 0.0, val boxLeft: Double = 0.0)
    private data class LocalTrack(val rowOneY: Double, val rowHeightScale: Double)

    private data class HeaderInfo(
        val positionHeaders: List<TextBox>,
        val gcHeaders: List<GcHeader>,
        val gcMarkerHeaders: List<TextBox>,
        val indexHeaders: List<TextBox>
    )

    private data class DeskewTransform(val angleRadians: Double, val centerX: Double, val centerY: Double) {
        fun toDeskewed(point: PointF): PointF {
            val dx = point.x - centerX
            val dy = point.y - centerY
            val cosine = cos(angleRadians)
            val sine = sin(angleRadians)
            return PointF(
                (centerX + cosine * dx + sine * dy).toFloat(),
                (centerY - sine * dx + cosine * dy).toFloat()
            )
        }

        fun toOriginal(point: PointF): PointF {
            val dx = point.x - centerX
            val dy = point.y - centerY
            val cosine = cos(angleRadians)
            val sine = sin(angleRadians)
            return PointF(
                (centerX + cosine * dx - sine * dy).toFloat(),
                (centerY + sine * dx + cosine * dy).toFloat()
            )
        }
    }

    /**
     * Encapsulates vertical row trajectories across the image width.
     */
    private data class RowTrajectory(
        val rowCenters: DoubleArray,
        val indexRowCenters: Map<Int, DoubleArray>,
        val rowHeight: Double
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as RowTrajectory
            if (!rowCenters.contentEquals(other.rowCenters)) return false
            if (indexRowCenters.keys != other.indexRowCenters.keys) return false
            if (indexRowCenters.any { (column, centers) -> !centers.contentEquals(other.indexRowCenters[column]) }) return false
            if (rowHeight != other.rowHeight) return false
            return true
        }

        override fun hashCode(): Int {
            var result = rowCenters.contentHashCode()
            indexRowCenters.forEach { (column, centers) ->
                result = 31 * result + column
                result = 31 * result + centers.contentHashCode()
            }
            result = 31 * result + rowHeight.hashCode()
            return result
        }
    }

    /**
     * Main entry point: Extracts a table from the given ML Kit Text output based on local column trajectory tracking.
     *
     * @param visionText The recognized text output from ML Kit Text Recognition.
     * Header markers and the resulting column layout are inferred from OCR text-box geometry.
     */
    fun extract(
        visionText: Text
    ): ExtractedTable {
        /*
         * Extraction in big steps:
         * 1. Read ML Kit's text boxes, estimate page rotation, and deskew their corner geometry.
         * 2. Find header anchors and preliminary index numbers, then build a first column layout.
         * 3. Estimate local row trajectories from the index numbers and place each text box into
         *    a row/column cell; trim trailing empty rows to get the table's current height.
         * 4. If later GC cells are missing, try a perspective-tilted layout. Compare layouts with
         *    a GC-occupancy score over rows after the first five (which may legitimately be blank):
         *    each populated GC column adds (rowCount + 1), and each numeric GC cell adds 1. This
         *    favors broad GC-column coverage while still rewarding layouts that fill more GC
         *    cells, instead of selecting tilt from appearance alone.
         * 5. If GC scores tie, prefer the layout whose separators cross fewer body text boxes.
         *    Finally, return the selected grid and draw-ready separator lines in original-image
         *    coordinates.
         */
        val maxSearchRows = 150

        val originalBoxes = extractTextBoxes(visionText)
        val deskew = estimateDeskew(originalBoxes)
        val boxes = originalBoxes.map { box -> box.copy(corners = Array(box.corners.size) { index -> deskew.toDeskewed(box.corners[index]) }) }
        val imageWidth = boxes.maxOfOrNull { it.right } ?: throw IllegalStateException("Cannot determine image width")

        val preliminaryNumbers = parsePreliminaryNumbers(boxes, maxSearchRows)
        val headers = detectHeaders(boxes)
        val usesHeaderDrivenLayout = headers.indexHeaders.size > 1 &&
            headers.positionHeaders.isNotEmpty() && headers.gcMarkerHeaders.isNotEmpty()
        // When OCR misses the LP labels, the legacy (vertical-only) layout remains the baseline,
        // but inferred LP anchors still allow a header-driven tilted retry below.
        val tiltHeaders = if (usesHeaderDrivenLayout) headers else synthesizeMissingIndexHeaders(headers, preliminaryNumbers)
        val canRetryWithTilt = tiltHeaders.indexHeaders.size > 1 &&
            tiltHeaders.positionHeaders.isNotEmpty() && tiltHeaders.gcMarkerHeaders.isNotEmpty()
        fun buildColumns(allowTilt: Boolean): List<Column> = if (usesHeaderDrivenLayout) {
            buildHeaderDrivenLayout(boxes, headers, allowTilt)
        } else {
            check(headers.gcHeaders.isNotEmpty()) { "Could not recognize any GC/value headers in the detected header row." }
            buildColumnLayout(headers.positionHeaders, headers.gcHeaders, preliminaryNumbers, imageWidth)
        }

        fun populate(columns: List<Column>): Array<Array<MutableList<PlacedText>>> {
            val trajectory = computeRowTrajectories(preliminaryNumbers, columns, maxSearchRows)
            return populateRawGrid(boxes, columns, trajectory, maxSearchRows)
        }

        var columns = buildColumns(allowTilt = false)
        var rawGrid = populate(columns)
        var finalRowCount = determineDynamicRowCount(rawGrid, emptyRowTolerance = CONSECUTIVE_EMPTY_ROWS_CUTOFF)
        var usedTiltedSeparators = false

        // Retry with slanted separators only when later GC rows still have gaps;
        // the first few rows are excluded because their GC cells may legitimately be blank.
        if (canRetryWithTilt && hasEmptyGcAfterInitialRows(rawGrid, columns, finalRowCount)) {
            val tiltedColumns = buildHeaderDrivenLayout(boxes, tiltHeaders, allowTilt = true)
            val sameStructure = usesHeaderDrivenLayout || (
                tiltedColumns.size == columns.size &&
                    tiltedColumns.map { it.kind == ColumnKind.GC } == columns.map { it.kind == ColumnKind.GC }
                )
            if (sameStructure) {
                val tiltedGrid = populate(tiltedColumns)
                val tiltedRowCount = determineDynamicRowCount(tiltedGrid, emptyRowTolerance = CONSECUTIVE_EMPTY_ROWS_CUTOFF)
                val tiltedScore = gcCoverageScore(tiltedGrid, tiltedColumns, tiltedRowCount)
                val baselineScore = gcCoverageScore(rawGrid, columns, finalRowCount)
                val headerBottom = tiltedColumns.mapNotNull { it.headerBottom }.maxOrNull() ?: boxes.minOf { it.top }
                val bodyBoxes = boxes.filter { it.top > headerBottom }
                val tiltedReducesTextCrossings = separatorCrossingPenalty(tiltedColumns, bodyBoxes) <
                    separatorCrossingPenalty(columns, bodyBoxes)

                // Keep the slanted layout when it preserves GC coverage and places separators
                // through fewer body text boxes; GC occupancy alone can tie even when a vertical
                // boundary visibly cuts off the start of a position such as "FC Runner".
                if (tiltedScore > baselineScore ||
                    (tiltedScore == baselineScore && tiltedReducesTextCrossings)
                ) {
                    columns = tiltedColumns
                    rawGrid = tiltedGrid
                    finalRowCount = tiltedRowCount
                    usedTiltedSeparators = true
                }
            }
        }

        val gcColumnIndices = columns.filter { it.kind == ColumnKind.GC }.map { it.index }
        val columnBounds = columns.map { it.left.xAt(it.left.referenceY) to it.right.xAt(it.right.referenceY) }
        val separatorHeaderBottom = columns.mapNotNull { it.headerBottom }.maxOrNull()
            ?: boxes.minOf { it.top }
        val separatorLines = createSeparatorLines(columns, boxes, deskew, separatorHeaderBottom)
        val deskewDegrees = Math.toDegrees(deskew.angleRadians)

        val placedCellBoxes = mutableListOf<PlacedCellBox>()
        for (r in 0 until finalRowCount) {
            for (c in columns.indices) {
                rawGrid[r][c].forEach { pt ->
                    val origDrawPoint = deskew.toOriginal(PointF(pt.boxLeft.toFloat(), (pt.boxTop - 15.0).toFloat()))
                    placedCellBoxes.add(
                        PlacedCellBox(
                            row = r,
                            col = c,
                            x = origDrawPoint.x.toDouble(),
                            y = origDrawPoint.y.toDouble(),
                            boxTop = pt.boxTop,
                            boxLeft = pt.boxLeft,
                            text = pt.text
                        )
                    )
                }
            }
        }

        if (finalRowCount == 0) {
            return ExtractedTable(emptyArray(), gcColumnIndices, columnBounds, separatorLines, deskewDegrees, usedTiltedSeparators, placedCellBoxes)
        }

        return ExtractedTable(
            buildFinalTable(rawGrid, finalRowCount, columns.size),
            gcColumnIndices,
            columnBounds,
            separatorLines,
            deskewDegrees,
            usedTiltedSeparators,
            placedCellBoxes
        )
    }

    /**
     * Extracts and validates ML Kit text elements into structured [TextBox] quadrilaterals.
     */
    private fun extractTextBoxes(visionText: Text): List<TextBox> {
        val boxes = visionText.textBlocks.flatMap { it.lines }.flatMap { it.elements }
            .mapNotNull { element ->
                val points = element.cornerPoints ?: return@mapNotNull null
                if (points.size != 4) return@mapNotNull null
                TextBox(element.text.trim(), Array(4) { i -> PointF(points[i].x.toFloat(), points[i].y.toFloat()) })
            }

        if (boxes.isEmpty()) {
            throw IllegalStateException("No text detected in the image. Please ensure the camera is focused and lighting is sufficient.")
        }
        return boxes
    }

    /** Uses the median text top-edge angle, then rotates boxes by its negative to normalize the page. */
    private fun estimateDeskew(boxes: List<TextBox>): DeskewTransform {
        val minX = boxes.minOf { it.left }
        val maxX = boxes.maxOf { it.right }
        val minY = boxes.minOf { it.top }
        val maxY = boxes.maxOf { it.bottom }
        val angles = boxes.mapNotNull { box ->
            val first = box.corners[0]
            val second = box.corners[1]
            var angle = atan2((second.y - first.y).toDouble(), (second.x - first.x).toDouble())
            if (angle > Math.PI / 2.0) angle -= Math.PI
            if (angle < -Math.PI / 2.0) angle += Math.PI
            angle.takeIf { it.isFinite() }
        }
        return DeskewTransform(
            angleRadians = if (angles.isEmpty()) 0.0 else median(angles),
            centerX = (minX + maxX) / 2.0,
            centerY = (minY + maxY) / 2.0
        )
    }

    /** Finds the strongest aligned group of known header labels; no fixed image-Y band is assumed. */
    private fun detectHeaders(boxes: List<TextBox>): HeaderInfo {
        val candidates = boxes.filter { isPositionHeader(it) || isGcHeader(it) || isStartHeader(it) || isIndexHeader(it) }
        check(candidates.isNotEmpty()) { "Could not find a recognizable table header marker." }

        val medianHeight = median(candidates.map { (it.bottom - it.top).coerceAtLeast(1.0) })
        val perpendicularTolerance = medianHeight * HEADER_BAND_HEIGHT_MULTIPLIER
        val groups = candidates.map { seed ->
            val angle = textAngle(seed)
            val normalX = -sin(angle)
            val normalY = cos(angle)
            val seedProjection = seed.centerX * normalX + seed.centerY * normalY
            candidates.filter { candidate ->
                angleDistance(textAngle(candidate), angle) <= HEADER_ANGLE_TOLERANCE_RADIANS &&
                    abs(candidate.centerX * normalX + candidate.centerY * normalY - seedProjection) <= perpendicularTolerance
            }
        }
        val headerRow = groups.maxWithOrNull(
            compareBy<List<TextBox>> { it.size }
                .thenBy { group -> (group.maxOfOrNull { it.centerX } ?: 0.0) - (group.minOfOrNull { it.centerX } ?: 0.0) }
                .thenBy { group -> -(group.map { it.centerY }.average()) }
        ).orEmpty()

        check(headerRow.isNotEmpty()) { "Could not establish a header row from the recognized markers." }
        val gcMarkerHeaders = headerRow.filter(::isGcHeader)
        return HeaderInfo(
            positionHeaders = headerRow.filter(::isPositionHeader),
            gcHeaders = findGcHeaders(headerRow.filter { isGcHeader(it) || isStartHeader(it) }),
            gcMarkerHeaders = gcMarkerHeaders,
            indexHeaders = headerRow.filter(::isIndexHeader)
        )
    }

    /**
     * OCR sometimes misses the short "LP" labels (e.g. on angled photos). Without them the
     * header-driven layout, which is the only one that supports slanted separators, cannot be
     * used. Infer an LP anchor from the first row-index numbers just below the header that lie
     * left of the first labeled column or right of the last one.
     */
    private fun synthesizeMissingIndexHeaders(headers: HeaderInfo, numbers: List<Pair<Int, TextBox>>): HeaderInfo {
        if (headers.indexHeaders.size > 1 || headers.positionHeaders.isEmpty() || headers.gcMarkerHeaders.isEmpty()) return headers
        val labelled = headers.positionHeaders + headers.gcMarkerHeaders
        val headerTop = labelled.minOf { it.top }
        val headerHeight = median(labelled.map { (it.bottom - it.top).coerceAtLeast(1.0) })
        val bandBottom = labelled.maxOf { it.bottom } + headerHeight * SYNTHETIC_INDEX_BAND_HEADER_HEIGHTS
        val leftEdge = labelled.minOf { it.left }
        val rightEdge = labelled.maxOf { it.right }
        val nearHeader = numbers.filter { (number, box) ->
            number <= SYNTHETIC_INDEX_MAX_ROW && box.top > headerTop && box.centerY < bandBottom
        }.map { it.second }

        fun synthesize(candidates: List<TextBox>): TextBox? {
            if (candidates.isEmpty()) return null
            val left = candidates.minOf { it.left }.toFloat()
            val right = candidates.maxOf { it.right }.toFloat()
            // Place the anchor just above its first number so that number stays in the body.
            val bottom = (candidates.minOf { it.top } - 1.0).toFloat()
            val top = (bottom - headerHeight).toFloat()
            return TextBox("LP", arrayOf(PointF(left, top), PointF(right, top), PointF(right, bottom), PointF(left, bottom)))
        }

        val existing = headers.indexHeaders
        val left = if (existing.none { it.centerX < leftEdge }) synthesize(nearHeader.filter { it.right < leftEdge }) else null
        val right = if (existing.none { it.centerX > rightEdge }) synthesize(nearHeader.filter { it.left > rightEdge }) else null
        val merged = existing + listOfNotNull(left, right)
        return if (merged.size > 1) headers.copy(indexHeaders = merged) else headers
    }

    private fun isPositionHeader(box: TextBox): Boolean = box.tokens.any { token ->
        token.startsWith("POS") || (token.length >= 8 && editDistance(token, "STANOWISKO") <= 2)
    }

    private fun isGcHeader(box: TextBox): Boolean = "GC" in box.tokens
    private fun isStartHeader(box: TextBox): Boolean = "START" in box.tokens

    /** LP is accepted only as a complete OCR element, not when embedded in other text. */
    private fun isIndexHeader(box: TextBox): Boolean =
        box.text.trim().uppercase(Locale.ROOT).removeSuffix(".").removeSuffix(",") == "LP"

    private fun textAngle(box: TextBox): Double {
        val first = box.corners[0]
        val second = box.corners[1]
        return atan2((second.y - first.y).toDouble(), (second.x - first.x).toDouble())
    }

    private fun angleDistance(first: Double, second: Double): Double {
        val difference = abs(first - second) % Math.PI
        return minOf(difference, Math.PI - difference)
    }

    private fun editDistance(first: String, second: String): Int {
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

    /**
     * Filters all numeric OCR boxes in the image for candidate row index numbers (1..maxSearchRows).
     */
    private fun parsePreliminaryNumbers(boxes: List<TextBox>, maxSearchRows: Int): List<Pair<Int, TextBox>> {
        return boxes.mapNotNull { box ->
            val number = box.text.toIntOrNull()?.takeIf { it in 1..maxSearchRows } ?: return@mapNotNull null
            number to box
        }
    }

    /**
     * Dynamically establishes horizontal column bounds for:
     * - Column 0: Left Index Column
     * - Columns 1..2N: Interleaved (Positions, GC) pairs
     * - Column 2N+1: Right Index Column (if present)
     */
    private fun buildColumnLayout(
        positionHeaders: List<TextBox>,
        gcHeaders: List<GcHeader>,
        preliminaryNumbers: List<Pair<Int, TextBox>>,
        imageWidth: Double
    ): List<Column> {
        val columns = mutableListOf<Column>()

        // Find the left-most edge of row numbers to define the start of the table margin.
        val leftMargin = preliminaryNumbers.filter { it.second.centerX < imageWidth * 0.2 }
            .minOfOrNull { it.second.left }
            ?: throw IllegalStateException("Could not find any row index numbers on the far-left side of the page.")

        // Use the left side of the first Positions header as the end of the Index column, or extrapolate via GC column width.
        val fallbackIndexWidth = gcHeaders.firstOrNull()?.let { it.right - it.left }
            ?: throw IllegalStateException("Cannot determine index column width because no GC headers were found.")

        val firstPosLeft = positionHeaders.firstOrNull()?.left ?: (leftMargin + fallbackIndexWidth)
        val leftIndexRight = if (firstPosLeft > leftMargin + MIN_COLUMN_GAP_PX) firstPosLeft else (leftMargin + fallbackIndexWidth)

        columns += Column(0, ColumnBoundary(leftMargin), ColumnBoundary(leftIndexRight), null, ColumnKind.INDEX)
        var currentLeftX = leftIndexRight

        // Interleave Positions and GC columns based on detected GC header locations
        gcHeaders.forEach { (left, right, bottom) ->
            if (left > currentLeftX) {
                val positionHeader = positionHeaders
                    .filter { it.centerX in currentLeftX..<left }
                    .minByOrNull { abs(it.centerX - (currentLeftX + left) / 2.0) }
                columns += Column(columns.size, ColumnBoundary(currentLeftX), ColumnBoundary(left), positionHeader?.bottom, if (positionHeader != null) ColumnKind.POSITION else ColumnKind.OTHER)
            }
            columns += Column(columns.size, ColumnBoundary(left), ColumnBoundary(right), bottom, ColumnKind.GC)
            currentLeftX = right
        }

        val rightIndexStart = preliminaryNumbers
            .filter { it.second.centerX > currentLeftX + 10.0 }
            .minOfOrNull { it.second.left } ?: currentLeftX

        if (currentLeftX < rightIndexStart) {
            val positionHeader = positionHeaders
                .filter { it.centerX in currentLeftX..<rightIndexStart }
                .minByOrNull { abs(it.centerX - (currentLeftX + rightIndexStart) / 2.0) }
            if (positionHeader != null) {
                columns += Column(columns.size, ColumnBoundary(currentLeftX), ColumnBoundary(rightIndexStart), positionHeader.bottom, ColumnKind.POSITION)
            }
        }

        // Add the far-right index column
        columns += Column(columns.size, ColumnBoundary(rightIndexStart), ColumnBoundary(Double.MAX_VALUE), null, ColumnKind.INDEX)

        if (columns.isNotEmpty()) {
            val last = columns.removeAt(columns.lastIndex)
            columns += last.copy(right = ColumnBoundary(Double.MAX_VALUE))
        }

        return columns
    }

    /**
     * Builds one band for each recognized LP, position, or GC header. The gap between adjacent
     * header boxes is refined by choosing the X location crossed by the fewest body text boxes.
     */
    private fun buildHeaderDrivenLayout(boxes: List<TextBox>, headers: HeaderInfo, allowTilt: Boolean): List<Column> {
        val gcAnchors = completeRepeatedGcHeaders(headers)
        val anchors = buildList {
            headers.indexHeaders.forEach { add(HeaderAnchor(ColumnKind.INDEX, it.left, it.right, it.top, it.bottom)) }
            headers.positionHeaders.forEach { add(HeaderAnchor(ColumnKind.POSITION, it.left, it.right, it.top, it.bottom)) }
            addAll(gcAnchors)
        }.sortedBy { it.centerX }

        check(anchors.size >= 3) { "Too few recognized header cells to infer the columns." }
        val typicalHeaderHeight = median(anchors.map { (it.bottom - it.top).coerceAtLeast(1.0) })
        val uniqueAnchors = mutableListOf<HeaderAnchor>()
        anchors.forEach { anchor ->
            val previous = uniqueAnchors.lastOrNull()
            if (previous != null && previous.kind == anchor.kind && abs(previous.centerX - anchor.centerX) < typicalHeaderHeight * 0.6) {
                if (anchor.right - anchor.left > previous.right - previous.left) uniqueAnchors[uniqueAnchors.lastIndex] = anchor
            } else {
                uniqueAnchors += anchor
            }
        }

        val headerBottom = uniqueAnchors.maxOf { it.bottom }
        val bodyBoxes = boxes.filter { it.top > headerBottom }
        check(bodyBoxes.isNotEmpty()) { "No text boxes were found below the recognized header row." }

        val gaps = uniqueAnchors.zipWithNext().map { (left, right) ->
            HeaderGap(left.right, right.left, (left.bottom + right.top) / 2.0, (left.centerX + right.centerX) / 2.0)
        }
        // A photo taken at an angle makes the vertical separators converge towards a vanishing
        // point; their slopes then change linearly across the page. Fit that model globally first,
        // then let each separator refine its own slope in a small window around the prediction.
        val predictedSlopes = if (allowTilt) fitPerspectiveSlopes(gaps, bodyBoxes) else DoubleArray(gaps.size)

        val cuts = gaps.mapIndexed { index, gap ->
            if (gap.isOpen) {
                val predicted = predictedSlopes[index]
                val leftKind = uniqueAnchors[index].kind
                val rightKind = uniqueAnchors[index + 1].kind
                findTextFreeBoundary(
                    gap.left,
                    gap.right,
                    referenceY = gap.referenceY,
                    bodyBoxes = bodyBoxes,
                    slopes = if (allowTilt) TILT_RETRY_SLOPES.map { it + predicted }.toDoubleArray() else doubleArrayOf(0.0),
                    predictedSlope = predicted,
                    leftKind = leftKind,
                    rightKind = rightKind
                )
            } else {
                ColumnBoundary(gap.fallbackX, referenceY = gap.referenceY)
            }
        }

        val firstLeft = bodyBoxes.minOfOrNull { it.left } ?: uniqueAnchors.first().left
        val lastRight = bodyBoxes.maxOfOrNull { it.right } ?: uniqueAnchors.last().right
        val boundaries = listOf(ColumnBoundary(firstLeft)) + cuts + listOf(ColumnBoundary(lastRight))

        return uniqueAnchors.mapIndexed { index, anchor ->
            Column(index, boundaries[index], boundaries[index + 1], anchor.bottom, anchor.kind)
        }
    }

    /** Searches the header gap for a separator that crosses the fewest body boxes. */
    private fun findTextFreeBoundary(
        left: Double,
        right: Double,
        referenceY: Double,
        bodyBoxes: List<TextBox>,
        slopes: DoubleArray,
        predictedSlope: Double = 0.0,
        leftKind: ColumnKind = ColumnKind.OTHER,
        rightKind: ColumnKind = ColumnKind.OTHER
    ): ColumnBoundary {
        if (right <= left) return ColumnBoundary((left + right) / 2.0, referenceY = referenceY)

        val preferredFraction = when {
            leftKind == ColumnKind.POSITION && rightKind == ColumnKind.GC -> 0.80
            leftKind == ColumnKind.GC && rightKind == ColumnKind.POSITION -> 0.20
            else -> 0.50
        }
        val preferredX = left + (right - left) * preferredFraction

        val xSamples = 0..32
        return slopes.flatMap { slope ->
            xSamples.map { sample ->
                val x = left + (right - left) * sample / xSamples.last
                val boundary = ColumnBoundary(x, slope, referenceY)
                val crossingPenalty = bodyBoxes.sumOf { box ->
                    if (boundaryCrossesBox(boundary, box)) {
                        if (box.text.any { it.isLetter() }) CROSSING_LETTER_PENALTY else CROSSING_NUMERIC_PENALTY
                    } else 0.0
                }
                val positionPenalty = abs(x - preferredX) / (right - left)
                val tiltPenalty = abs(slope - predictedSlope) * 0.25
                boundary to (crossingPenalty + positionPenalty + tiltPenalty)
            }
        }.minByOrNull { it.second }!!.first
    }

    /**
     * Fits a perspective (keystone) model in which separator slope varies linearly with the
     * separator's X position: `slope(x) = slopeLeft + (slopeRight - slopeLeft) * t`. Each header
     * gap's best text-free cost is tabulated per slope once, then all (slopeLeft, slopeRight)
     * pairs are scored jointly so a single noisy gap cannot pick an inconsistent angle.
     */
    private fun fitPerspectiveSlopes(gaps: List<HeaderGap>, bodyBoxes: List<TextBox>): DoubleArray {
        val openIndices = gaps.indices.filter { gaps[it].isOpen }
        if (openIndices.size < 2) return DoubleArray(gaps.size)

        val slopeGrid = DoubleArray(2 * PERSPECTIVE_SLOPE_STEPS + 1) { (it - PERSPECTIVE_SLOPE_STEPS) * PERSPECTIVE_SLOPE_STEP }
        val bodyTop = bodyBoxes.minOf { it.top }
        val bodyBottom = bodyBoxes.maxOf { it.bottom }
        val xSamples = 0..32
        val costs = openIndices.associateWith { gapIndex ->
            val gap = gaps[gapIndex]
            val reach = PERSPECTIVE_MAX_SLOPE * (maxOf(abs(bodyTop - gap.referenceY), abs(bodyBottom - gap.referenceY)) + 1.0)
            val nearbyBoxes = bodyBoxes.filter { it.right >= gap.left - reach && it.left <= gap.right + reach }
            DoubleArray(slopeGrid.size) { slopeIndex ->
                xSamples.minOf { sample ->
                    val x = gap.left + (gap.right - gap.left) * sample / xSamples.last
                    val boundary = ColumnBoundary(x, slopeGrid[slopeIndex], gap.referenceY)
                    val crossingPenalty = nearbyBoxes.sumOf { box ->
                        if (boundaryCrossesBox(boundary, box)) {
                            if (box.text.any { it.isLetter() }) CROSSING_LETTER_PENALTY else CROSSING_NUMERIC_PENALTY
                        } else 0.0
                    }
                    crossingPenalty + abs(x - gap.center) / (gap.right - gap.left)
                }
            }
        }

        val leftX = gaps[openIndices.first()].center
        val rightX = gaps[openIndices.last()].center
        if (rightX <= leftX) return DoubleArray(gaps.size)
        fun slopeAt(gap: HeaderGap, leftSlope: Double, rightSlope: Double): Double =
            leftSlope + (rightSlope - leftSlope) * (gap.center - leftX) / (rightX - leftX)

        var bestLeft = 0.0
        var bestRight = 0.0
        var bestScore = Double.POSITIVE_INFINITY
        for (leftSlope in slopeGrid) {
            for (rightSlope in slopeGrid) {
                var score = 0.0
                for (gapIndex in openIndices) {
                    val slope = slopeAt(gaps[gapIndex], leftSlope, rightSlope)
                    val gridIndex = (slope / PERSPECTIVE_SLOPE_STEP).roundToInt() + PERSPECTIVE_SLOPE_STEPS
                    score += costs.getValue(gapIndex)[gridIndex.coerceIn(0, slopeGrid.lastIndex)] + abs(slope) * 0.25
                }
                if (score < bestScore) {
                    bestScore = score
                    bestLeft = leftSlope
                    bestRight = rightSlope
                }
            }
        }
        return DoubleArray(gaps.size) { index -> slopeAt(gaps[index], bestLeft, bestRight) }
    }

    private fun boundaryCrossesBox(boundary: ColumnBoundary, box: TextBox): Boolean {
        val atTop = boundary.xAt(box.top)
        val atBottom = boundary.xAt(box.bottom)
        val lineLeft = minOf(atTop, atBottom)
        val lineRight = maxOf(atTop, atBottom)
        return lineRight >= box.left && lineLeft <= box.right
    }

    /** Sums the existing text-crossing penalties for a layout's unique column boundaries. */
    private fun separatorCrossingPenalty(columns: List<Column>, bodyBoxes: List<TextBox>): Double =
        columns.flatMap { listOf(it.left, it.right) }
            .distinct()
            .sumOf { boundary ->
                bodyBoxes.sumOf { box ->
                    if (!boundaryCrossesBox(boundary, box)) 0.0
                    else if (box.text.any(Char::isLetter)) CROSSING_LETTER_PENALTY else CROSSING_NUMERIC_PENALTY
                }
            }

    private fun hasEmptyGcAfterInitialRows(
        rawGrid: Array<Array<MutableList<PlacedText>>>,
        columns: List<Column>,
        rowCount: Int
    ): Boolean {
        return rowCount > INITIAL_GC_ROWS_TO_SKIP && columns.filter { it.kind == ColumnKind.GC }.any { column ->
            (INITIAL_GC_ROWS_TO_SKIP until rowCount).any { row -> rawGrid[row][column.index].isEmpty() }
        }
    }

    private fun isNumericGcValue(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.toIntOrNull() != null) return true
        val coerced = TextProcessor.coerceDigits(trimmed)
        return coerced.toIntOrNull() != null
    }

    /** Gives priority to making each GC column receive numeric content, then to filling more GC rows. */
    private fun gcCoverageScore(
        rawGrid: Array<Array<MutableList<PlacedText>>>,
        columns: List<Column>,
        rowCount: Int
    ): Int {
        val gcColumns = columns.filter { it.kind == ColumnKind.GC }
        val occupiedRows = gcColumns.sumOf { column ->
            (INITIAL_GC_ROWS_TO_SKIP until rowCount).count { row ->
                rawGrid[row][column.index].any { isNumericGcValue(it.text) }
            }
        }
        val nonemptyColumns = gcColumns.count { column ->
            (INITIAL_GC_ROWS_TO_SKIP until rowCount).any { row ->
                rawGrid[row][column.index].any { isNumericGcValue(it.text) }
            }
        }
        return nonemptyColumns * (rowCount + 1) + occupiedRows
    }

    private fun createSeparatorLines(
        columns: List<Column>,
        boxes: List<TextBox>,
        deskew: DeskewTransform,
        headerBottom: Double
    ): List<SeparatorLine> {
        if (boxes.isEmpty()) return emptyList()
        // Only extend the displayed separators through the table body, not unrelated text above it.
        val bodyBoxes = boxes.filter { it.top > headerBottom }
        val firstY = bodyBoxes.minOfOrNull { it.top } ?: headerBottom
        val lastY = bodyBoxes.maxOfOrNull { it.bottom } ?: firstY
        return columns.flatMap { listOf(it.left, it.right) }
            .distinct()
            .filter { it.xAtReferenceY.isFinite() && it.xAtReferenceY != Double.MAX_VALUE }
            .map { boundary ->
                val start = deskew.toOriginal(PointF(boundary.xAt(firstY).toFloat(), firstY.toFloat()))
                val end = deskew.toOriginal(PointF(boundary.xAt(lastY).toFloat(), lastY.toFloat()))
                SeparatorLine(start.x.toDouble(), start.y.toDouble(), end.x.toDouble(), end.y.toDouble())
            }
    }

    /**
     * Repeated VLH headers follow LP -> position -> GC. If OCR misses a GC label, infer that
     * header's location from the median position-to-GC offset of the other repeated groups.
     * This restores a column anchor without assuming a fixed number of groups or table width.
     */
    private fun completeRepeatedGcHeaders(headers: HeaderInfo): List<HeaderAnchor> {
        val indexHeaders = headers.indexHeaders.sortedBy { it.centerX }
        val positionHeaders = headers.positionHeaders.sortedBy { it.centerX }
        val detectedGcHeaders = headers.gcMarkerHeaders.sortedBy { it.centerX }
        if (indexHeaders.size < 2 || positionHeaders.isEmpty() || detectedGcHeaders.isEmpty()) {
            return detectedGcHeaders.map { HeaderAnchor(ColumnKind.GC, it.left, it.right, it.top, it.bottom) }
        }

        data class Group(val position: TextBox, val gc: TextBox?)

        val groups = indexHeaders.mapIndexedNotNull { index, lpHeader ->
            val nextLpX = indexHeaders.getOrNull(index + 1)?.centerX ?: Double.POSITIVE_INFINITY
            val position = positionHeaders
                .filter { it.centerX > lpHeader.centerX && it.centerX < nextLpX }
                .minByOrNull { it.centerX }
                ?: return@mapIndexedNotNull null
            val gc = detectedGcHeaders
                .filter { it.centerX > position.centerX && it.centerX < nextLpX }
                .minByOrNull { it.centerX }
            Group(position, gc)
        }
        val knownOffsets = groups.mapNotNull { group ->
            group.gc?.let { it.centerX - group.position.centerX }
        }
        if (knownOffsets.isEmpty()) {
            return detectedGcHeaders.map { HeaderAnchor(ColumnKind.GC, it.left, it.right, it.top, it.bottom) }
        }

        val typicalOffset = median(knownOffsets)
        val knownBottomOffsets = groups.mapNotNull { group ->
            group.gc?.let { it.bottom - group.position.bottom }
        }
        val typicalWidth = median(detectedGcHeaders.map { it.right - it.left })
        val typicalHeight = median(detectedGcHeaders.map { it.bottom - it.top })
        val detectedAnchors = detectedGcHeaders.map {
            HeaderAnchor(ColumnKind.GC, it.left, it.right, it.top, it.bottom)
        }.toMutableList()

        groups.filter { it.gc == null }.forEach { group ->
            val centerX = group.position.centerX + typicalOffset
            // Infer the missing header's vertical position relative to its own group. A global
            // GC-header Y median ignores the photographed curvature and can push headerBottom
            // below several real body rows, which then get filtered out as if they were headers.
            val inferredBottom = group.position.bottom + median(knownBottomOffsets)
            detectedAnchors += HeaderAnchor(
                ColumnKind.GC,
                left = centerX - typicalWidth / 2.0,
                right = centerX + typicalWidth / 2.0,
                top = inferredBottom - typicalHeight,
                bottom = inferredBottom
            )
        }
        return detectedAnchors
    }

    /**
     * Computes primary and secondary vertical row trajectories across the image.
     * Prefers the Right Index as master when present (>= 25 anchors) to avoid left margin smudges,
     * otherwise smoothly falls back to the Left Index as master.
     */
    private fun computeRowTrajectories(
        preliminaryNumbers: List<Pair<Int, TextBox>>,
        columns: List<Column>,
        maxSearchRows: Int
    ): RowTrajectory {
        val indexAnchors = columns.filter { it.kind == ColumnKind.INDEX }.associate { column ->
            val anchors = preliminaryNumbers.filter { (_, box) ->
                column.contains(box.centerX, box.centerY)
            }.groupBy({ it.first }, { it.second.centerY }).mapValues { (_, ys) -> median(ys) }
            column.index to anchors
        }
        check(indexAnchors.isNotEmpty()) { "Could not find an index column to anchor row positions." }

        // Prefer the rightmost readable index sequence to avoid the common left-margin noise.
        val masterColumn = indexAnchors.entries.lastOrNull { it.value.size >= 25 }
            ?: indexAnchors.maxByOrNull { it.value.size }!!
        val rowAnchors = masterColumn.value
        val rowCenters = estimateLocalRowCenters(rowAnchors, maxSearchRows)
        val indexRowCenters = indexAnchors.mapValues { (column, anchors) ->
            if (column == masterColumn.key) rowCenters
            else estimateSecondaryTrajectory(rowCenters, anchors, rowAnchors)
        }

        val rowHeights = DoubleArray(rowCenters.size) { row ->
            if (row == rowCenters.lastIndex) rowCenters[row] - rowCenters[row - 1]
            else rowCenters[row + 1] - rowCenters[row]
        }
        val rawGaps = rowHeights.filter { it > 0.0 }
        val rawMedian = if (rawGaps.isNotEmpty()) median(rawGaps) else 41.0
        val rowHeight = median(rowHeights.filter { it in (rawMedian * 0.5)..(rawMedian * 1.5) }.toList().ifEmpty { listOf(rawMedian) })

        return RowTrajectory(rowCenters, indexRowCenters, rowHeight)
    }

    /**
     * Walks down each column independently, fitting local origin/scale tracks and assigning OCR text boxes to matrix rows.
     */
    private fun populateRawGrid(
        boxes: List<TextBox>,
        columns: List<Column>,
        trajectory: RowTrajectory,
        maxSearchRows: Int
    ): Array<Array<MutableList<PlacedText>>> {
        val rawGrid = Array(maxSearchRows) { Array(columns.size) { mutableListOf<PlacedText>() } }
        val leftHeaderBottom = columns.firstOrNull { it.index == 1 }?.headerBottom

        columns.forEach { column ->
            val indexedColumn = column.kind == ColumnKind.INDEX
            val indexRowCenters = trajectory.indexRowCenters[column.index] ?: trajectory.rowCenters
            val rowCenters = if (indexedColumn) indexRowCenters else trajectory.rowCenters
            val localRowOneY = if (indexedColumn) {
                indexRowCenters.first()
            } else {
                (column.headerBottom ?: leftHeaderBottom ?: 0.0) + trajectory.rowHeight * 0.8
            }
            val localRowHeight = trajectory.rowHeight
            val columnBoxes = boxes.filter { box ->
                column.contains(box.centerX, box.centerY) &&
                    (column.headerBottom == null || box.top > column.headerBottom)
            }.sortedWith(compareBy<TextBox> { it.centerY }.thenBy { it.left })
            val mergedBoxes = mergeSameLineBoxes(columnBoxes, localRowHeight)
            val track = if (indexedColumn) LocalTrack(localRowOneY, 1.0) else fitLocalTrack(
                mergedBoxes, localRowOneY, localRowHeight, rowCenters
            )
            val placed = if (indexedColumn) {
                // Use local Y geometry to place index labels. Their recognized number is only
                // a sanity check, so one OCR mistake cannot shift this or subsequent rows.
                columnBoxes.mapNotNull { box ->
                    val number = box.text.toIntOrNull()?.takeIf { it in 1..maxSearchRows }
                        ?: return@mapNotNull null
                    val row = nearestTrajectoryRow(box.centerY, rowCenters, localRowHeight)
                        ?: return@mapNotNull null
                    if (abs(number - (row + 1)) > 1) return@mapNotNull null
                    PlacedText(row, box.centerY, box.text, box.top, box.left)
                }.groupBy { it.row }.mapNotNull { (row, candidates) ->
                    candidates.minByOrNull { abs(it.y - rowCenters[row]) }
                }
            } else {
                mergedBoxes.mapNotNull { box ->
                    val targetRow = nearestLocalRow(box.centerY, track, rowCenters, localRowHeight) ?: return@mapNotNull null
                    PlacedText(targetRow, box.centerY, box.text, box.top, box.left)
                }
            }
            placed.groupBy { it.row }.forEach { (row, values) ->
                rawGrid[row][column.index].addAll(values)
            }
        }
        return rawGrid
    }

    /**
     * Identifies where the dynamic table ends.
     * We track the last row with valid content for each column independently, breaking after N consecutive empty rows.
     * The final table height is the absolute maximum of these valid boundaries across all columns.
     */
    private fun determineDynamicRowCount(rawGrid: Array<Array<MutableList<PlacedText>>>, emptyRowTolerance: Int): Int {
        val numCols = rawGrid[0].size
        val maxSearchRows = rawGrid.size
        var globalMaxRow = -1

        for (c in 0 until numCols) {
            var emptyCount = 0
            var localMaxRow = -1
            for (r in 0 until maxSearchRows) {
                if (rawGrid[r][c].isNotEmpty()) {
                    emptyCount = 0
                    localMaxRow = r
                } else {
                    emptyCount++
                    if (emptyCount >= emptyRowTolerance) {
                        break // We found a gap large enough to signify the end of this column
                    }
                }
            }
            if (localMaxRow > globalMaxRow) {
                globalMaxRow = localMaxRow
            }
        }
        return if (globalMaxRow >= 0) globalMaxRow + 1 else 0
    }

    /**
     * Assembles the populated raw grid into the final 2D String Array table output.
     */
    private fun buildFinalTable(
        rawGrid: Array<Array<MutableList<PlacedText>>>,
        finalRowCount: Int,
        numColumns: Int
    ): Array<Array<String>> {
        return Array(finalRowCount) { r ->
            Array(numColumns) { c ->
                rawGrid[r][c].sortedBy { it.y }.joinToString(" ") { it.text }
            }
        }
    }

    /**
     * Locates GC headers ("GC", "GC START") and builds bounding boxes around them.
     */
    private fun findGcHeaders(headers: List<TextBox>): List<GcHeader> {
        val sorted = headers.sortedBy { it.centerX }
        val result = mutableListOf<GcHeader>()
        val used = mutableSetOf<Int>()
        sorted.forEachIndexed { index, box ->
            if ("GC" !in box.tokens || index in used) return@forEachIndexed
            val startIndex = (index + 1 until sorted.size).firstOrNull { next ->
                "START" in sorted[next].tokens &&
                    sorted[next].left >= box.right - 5.0 &&
                    sorted[next].left - box.right < 140.0 &&
                    abs(sorted[next].centerY - box.centerY) < 90.0
            }
            val start = startIndex?.let(sorted::get)
            if (start != null) {
                used += startIndex
                result += GcHeader(
                    left = box.left,
                    right = start.right,
                    bottom = maxOf(box.bottom, start.bottom)
                )
            } else {
                val gcWidth = box.right - box.left
                result += GcHeader(
                    left = box.left - gcWidth * 0.5,
                    right = box.right + gcWidth * 0.5,
                    bottom = box.bottom
                )
            }
        }
        return result.sortedBy { it.left }
    }

    /**
     * Interpolates and extrapolates Y-centers for rows based on detected index number anchors.
     */
    private fun estimateLocalRowCenters(anchors: Map<Int, Double>, count: Int): DoubleArray {
        val centers = DoubleArray(count) { Double.NaN }
        anchors.forEach { (row, y) -> if (row in 1..count) centers[row - 1] = y }
        val observed = centers.indices.filter { centers[it].isFinite() }
        val observedGaps = observed.zipWithNext().map { (a, b) ->
            Triple(a, b, (centers[b] - centers[a]) / (b - a))
        }.filter { it.third > 0.0 }
        val allGaps = observedGaps.map { it.third }
        val rawMedian = if (allGaps.isNotEmpty()) median(allGaps) else 41.0
        val plausibleGaps = allGaps.filter { it in (rawMedian * 0.5)..(rawMedian * 1.5) }
        val earlyRowLimit = (count * INITIAL_ROW_HEIGHT_SAMPLE_FRACTION).toInt().coerceAtLeast(2)
        val earlyGaps = observedGaps
            .filter { (_, endRow, _) -> endRow < earlyRowLimit }
            .map { it.third }
            .filter { it in (rawMedian * 0.5)..(rawMedian * 1.5) }
        val fallback = when {
            earlyGaps.isNotEmpty() -> median(earlyGaps)
            plausibleGaps.isNotEmpty() -> median(plausibleGaps)
            else -> rawMedian
        }

        if (observed.isEmpty()) return DoubleArray(count) { it * fallback }
        for ((a, b) in observed.zipWithNext()) {
            for (i in a + 1 until b) centers[i] = centers[a] + (centers[b] - centers[a]) * (i - a) / (b - a)
        }
        val first = observed.first()
        for (i in first - 1 downTo 0) centers[i] = centers[i + 1] - fallback
        val last = observed.last()
        for (i in last + 1 until count) centers[i] = centers[i - 1] + fallback

        val rawHeights = DoubleArray(count - 1) { i ->
            val h = centers[i + 1] - centers[i]
            if (h in (fallback * 0.5)..(fallback * 1.5)) h else fallback
        }
        val smoothedHeights = DoubleArray(rawHeights.size) { i ->
            val from = maxOf(0, i - 4)
            rawHeights.slice(from..i).average()
        }
        for (i in 1 until count) centers[i] = centers[i - 1] + smoothedHeights[i - 1]
        return centers
    }

    /**
     * Builds a secondary row track from the master centers plus a robust local offset.
     * The left and right printed indices experience different vertical displacement because the
     * photographed rows are curved. A five-row median rejects bad OCR pairings; a trailing
     * five-gap mean smooths the resulting local row heights.
     */
    private fun estimateSecondaryTrajectory(
        masterCenters: DoubleArray,
        targetAnchors: Map<Int, Double>,
        masterAnchors: Map<Int, Double>
    ): DoubleArray {
        val rawOffsets = arrayOfNulls<Double>(masterCenters.size)
        masterCenters.indices.forEach { row ->
            val number = row + 1
            val targetY = targetAnchors[number]
            val masterY = masterAnchors[number]
            if (targetY != null && masterY != null) rawOffsets[row] = targetY - masterY
        }

        val knownRows = rawOffsets.indices.filter { rawOffsets[it] != null }
        val locallyFilteredOffsets = DoubleArray(masterCenters.size)

        if (knownRows.isNotEmpty()) {
            val firstKnown = knownRows.first()
            val lastKnown = knownRows.last()

            for (row in masterCenters.indices) {
                if (rawOffsets[row] != null) {
                    val from = (row - 2).coerceAtLeast(0)
                    val to = (row + 2).coerceAtMost(rawOffsets.lastIndex)
                    val nearby = (from..to).mapNotNull { rawOffsets[it] }
                    locallyFilteredOffsets[row] = median(nearby)
                } else if (row < firstKnown) {
                    locallyFilteredOffsets[row] = rawOffsets[firstKnown]!!
                } else if (row > lastKnown) {
                    locallyFilteredOffsets[row] = rawOffsets[lastKnown]!!
                } else {
                    val prevKnown = knownRows.last { it < row }
                    val nextKnown = knownRows.first { it > row }
                    locallyFilteredOffsets[row] = rawOffsets[prevKnown]!! +
                        (rawOffsets[nextKnown]!! - rawOffsets[prevKnown]!!) *
                        (row - prevKnown).toDouble() / (nextKnown - prevKnown)
                }
            }
        }

        val unsmoothedCenters = DoubleArray(masterCenters.size) { row ->
            masterCenters[row] + locallyFilteredOffsets[row]
        }
        if (unsmoothedCenters.size < 2) return unsmoothedCenters

        val allGaps = DoubleArray(unsmoothedCenters.size - 1) { row ->
            unsmoothedCenters[row + 1] - unsmoothedCenters[row]
        }.filter { it > 0.0 }
        val rawMedian = if (allGaps.isNotEmpty()) median(allGaps) else 41.0
        val minGap = rawMedian * 0.5
        val maxGap = rawMedian * 1.5

        val gaps = DoubleArray(unsmoothedCenters.size - 1) { row ->
            (unsmoothedCenters[row + 1] - unsmoothedCenters[row]).coerceIn(minGap, maxGap)
        }
        val smoothedGaps = DoubleArray(gaps.size) { row ->
            val from = (row - 4).coerceAtLeast(0)
            gaps.slice(from..row).average()
        }
        return DoubleArray(unsmoothedCenters.size).also { centers ->
            centers[0] = unsmoothedCenters[0]
            for (row in 1 until centers.size) centers[row] = centers[row - 1] + smoothedGaps[row - 1]
        }
    }

    private fun nearestTrajectoryRow(y: Double, centers: DoubleArray, fallbackRowHeight: Double): Int? {
        val row = centers.indices.minByOrNull { index -> abs(y - centers[index]) } ?: return null
        val localHeight = if (row < centers.lastIndex) centers[row + 1] - centers[row]
        else centers[row] - centers[row - 1].coerceAtLeast(0.0)

        val usableHeight = if (localHeight in (fallbackRowHeight * 0.5)..(fallbackRowHeight * 1.5)) localHeight else fallbackRowHeight
        return row.takeIf { abs(y - centers[row]) <= usableHeight * 0.55 }
    }

    private fun fitLocalTrack(boxes: List<TextBox>, expectedOrigin: Double, rowHeight: Double, rowCenters: DoubleArray): LocalTrack {
        if (boxes.isEmpty()) return LocalTrack(expectedOrigin, 1.0)
        val relativeCenters = DoubleArray(rowCenters.size) { rowCenters[it] - rowCenters[0] }
        var bestOrigin = expectedOrigin
        var bestScale = 1.0
        var bestScore = Double.POSITIVE_INFINITY
        var scale = 0.80
        val maxOriginShift = rowHeight * MAX_LOCAL_TRACK_ORIGIN_SHIFT_FRACTION
        val originShifts = buildList {
            var shift = -maxOriginShift
            while (shift <= maxOriginShift) {
                add(shift)
                shift += 4.0
            }
            add(0.0)
        }.distinct()

        while (scale <= 1.2001) {
            for (shift in originShifts) {
                val candidateOrigin = expectedOrigin + shift
                val residual = boxes.sumOf { box ->
                    nearestTrackResidual(box.centerY - candidateOrigin, relativeCenters, scale)
                        .coerceAtMost(rowHeight * 0.65)
                } / boxes.size
                val score = residual + abs(shift) * 0.015 + abs(scale - 1.0) * rowHeight * 0.08
                if (score < bestScore) {
                    bestScore = score
                    bestOrigin = candidateOrigin
                    bestScale = scale
                }
            }
            scale += 0.02
        }
        return LocalTrack(bestOrigin, bestScale)
    }

    private fun nearestTrackResidual(y: Double, relativeCenters: DoubleArray, scale: Double): Double {
        var low = 0
        var high = relativeCenters.lastIndex
        while (low < high) {
            val middle = (low + high) ushr 1
            if (relativeCenters[middle] * scale < y) low = middle + 1 else high = middle
        }
        val upper = relativeCenters[low] * scale
        val lower = relativeCenters[(low - 1).coerceAtLeast(0)] * scale
        return minOf(abs(y - upper), abs(y - lower))
    }

    private fun nearestLocalRow(y: Double, track: LocalTrack, rowCenters: DoubleArray, medianHeight: Double): Int? {
        val rowY: (Int) -> Double = { row -> track.rowOneY + (rowCenters[row] - rowCenters[0]) * track.rowHeightScale }
        val row = rowCenters.indices.minByOrNull { index -> abs(y - rowY(index)) } ?: return null
        val localHeight = if (row < rowCenters.lastIndex) {
            (rowCenters[row + 1] - rowCenters[row]) * track.rowHeightScale
        } else medianHeight * track.rowHeightScale
        return row.takeIf { abs(y - rowY(row)) <= localHeight * 0.55 }
    }

    /**
     * Merges horizontally adjacent text boxes within the same column line that belong to the same text fragment.
     */
    private fun mergeSameLineBoxes(boxes: List<TextBox>, rowHeight: Double): List<TextBox> {
        val result = mutableListOf<TextBox>()
        // Scan left-to-right first: small vertical offsets from skew should not reverse the
        // natural reading order of fragments that belong to the same horizontal text line.
        boxes.sortedBy { it.left }.forEach { next ->
            val mergeIndex = result.indices
                .filter { index ->
                    val candidate = result[index]
                    val gap = next.left - candidate.right
                    abs(next.centerY - candidate.centerY) < minOf(22.0, rowHeight * 0.48) &&
                        gap < 130.0 && gap > -45.0
                }
                .minByOrNull { index ->
                    val candidate = result[index]
                    abs(next.centerY - candidate.centerY) + abs(next.left - candidate.right) * 0.05
                }
            if (mergeIndex != null) {
                val previous = result[mergeIndex]
                val points = previous.corners + next.corners
                val minX = points.minOf { it.x }
                val maxX = points.maxOf { it.x }
                val minY = points.minOf { it.y }
                val maxY = points.maxOf { it.y }
                result[mergeIndex] = TextBox(
                    "${previous.text} ${next.text}",
                    arrayOf(
                        PointF(minX, minY),
                        PointF(maxX, minY),
                        PointF(maxX, maxY),
                        PointF(minX, maxY)
                    )
                )
            } else result += next
        }
        return result
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }
}
