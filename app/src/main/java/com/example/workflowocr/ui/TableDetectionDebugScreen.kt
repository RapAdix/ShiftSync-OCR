package com.example.workflowocr

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

// --- COMPOSE SCREENS ---

/**
 * Debug image showing for table detection
 */
@Composable
fun TableDetectionDebugScreen(originalBitmap: Bitmap) {
    var displayedBitmap by remember { mutableStateOf(originalBitmap.scaleForPreview()) }
    var threshBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var maskBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var linesBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var logText by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val settings = LocalTableViewModel.current.activeLayout

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                scope.launch {
                    val results = withContext(Dispatchers.Default) {
                        // We will collect Mats here to ensure we release them all
                        var grayMat: Mat? = null
                        var boxedMat: Mat? = null
                        var deskewMat: Mat? = null

                        try {
                            // TODO Add filtering out pen colors. time change detection can be detected as marks around the middle.
                            // 1. Image Processing & Detection
                            grayMat = ImageProcessor.bitmapToGrayMat(originalBitmap)

                            // Note: deskewGrayMat should return a NEW Mat if it modifies it
                            deskewMat = TableDetector.deskewGrayMat(grayMat) ?: grayMat

                            val detection = TableDetector.detectTableCellsByLines(deskewMat, settings)
                            Log.d("DEBUG", "detectTableCells exited")

                            // 2. Prepare Bitmaps for UI
                            val threshBmp = ImageProcessor.matToBitmap(detection.thresh)
                            Log.d("DEBUG", "threshBmp matToBitmap preparations finished")
                            val maskBmp = ImageProcessor.matToBitmap(detection.mask)
                            Log.d("DEBUG", "maskBmp matToBitmap preparations finished")
                            val linesBmp = ImageProcessor.matToBitmap(detection.lines)
                            Log.d("DEBUG", "linesBmp matToBitmap preparations finished")
//                            val deskewedBmp = ImageProcessor.matToBitmap(detection.gray)
//                            Log.d("DEBUG", "matToBitmaps preparations finished")
//                            object {
//                                val boxed = null
//                                val cells = detection.cells
//                                val cellsAnalysis = null
//                                val deskewedBmp = deskewedBmp
//                                val thresh = threshBmp
//                                val mask = maskBmp
//                                val lines = linesBmp
//                                val margins = null
//                            }

                            // Draw the "Boxed" debug image
                            boxedMat = TableDetector.drawCells(detection.gray, detection.cells)
                            val cellsAnalysis = CellAnalyzer.analyzeCells(detection.gray, detection.thresh, detection.cells, settings)

                            val marginsDrawn = detection.gray.clone()
                            if (marginsDrawn.channels() == 1) {
                                Imgproc.cvtColor(marginsDrawn, marginsDrawn, Imgproc.COLOR_GRAY2RGB)
                            }
                            val red = Scalar(255.0, 0.0, 0.0)
                            val crossingThresh = ImageProcessor.createCrossingThresh(detection.gray)
                            for (row in detection.cells.indices) {
                                for (col in listOf(settings.timeStartCol, settings.timeEndCol)) {
                                    val (isCrossed, points) = CellAnalyzer.detectPenCrossing(crossingThresh, detection.cells[row][col])
                                    if (isCrossed)
                                        Log.d("DEBUG", "Row: $row, col: $col has a crossing over time")
                                    val marginMat = MatOfPoint(*points)
                                    Imgproc.polylines(marginsDrawn, listOf(marginMat), true, red, 2)
                                    marginMat.release()
                                }
                            }
                            crossingThresh.release()
                            val marginsBmp = ImageProcessor.matToBitmap(marginsDrawn)
                            marginsDrawn.release()
                            val boxedBmp = ImageProcessor.matToBitmap(boxedMat)

                            // Convert deskewMat to bitmap now so we can release the Mat
                            val deskewedBmp = ImageProcessor.matToBitmap(detection.gray)

                            // IMPORTANT: Release the internal Mats inside the Result object
                            // These were created inside detectTableCells
                            detection.thresh.release()
                            detection.mask.release()
                            detection.lines.release()
                            detection.gray.release()

                            // Return everything as Bitmaps (Safe for JVM memory)
                            object {
                                val boxed = boxedBmp
                                val cells = detection.cells
                                val cellsAnalysis = cellsAnalysis
                                val deskewedBmp = deskewedBmp
                                val thresh = threshBmp
                                val mask = maskBmp
                                val lines = linesBmp
                                val margins = marginsBmp
                            }
                        } finally {
                            // Final Cleanup of local Mats
                            grayMat?.release()
                            boxedMat?.release()
                            // Only release deskewMat if it's a different object than grayMat
                            if (deskewMat != grayMat) {
                                deskewMat?.release()
                            }
                        }
                    }
                    Log.d("DEBUG", "summarized 'results' object obtained, memory released")

                    // Update all UI state variables at once on the Main thread
                    threshBitmap = results.thresh.scaleForPreview()
                    maskBitmap = results.margins.scaleForPreview()
                    linesBitmap = results.lines.scaleForPreview()
                    displayedBitmap = results.boxed.scaleForPreview()

                    logText = withContext(Dispatchers.Default) {
                        // OCR (Text Extraction)
                        val rawTextGrid = TextProcessor.extractTextFromColumns(
                            results.cells,
                            results.deskewedBmp,
                            listOf(settings.nameCol, settings.timeStartCol, settings.timeEndCol)
                        )

                        // Build Log Text
                        val logBuilder = StringBuilder()
                        rawTextGrid.forEachIndexed { r, row ->
                            logBuilder.append("Row $r: ")
                            row.forEach { text ->
                                logBuilder.append("[${text.replace("\n", " ")}] ")
                            }
                            logBuilder.append("\n")
                        }
                        logBuilder.toString()
                    }
                    Log.d("DEBUG", logText)
                }
            }) {
            Text("Run Table Detection + OCR")
        }

        Spacer(modifier = Modifier.height(16.dp))

        // UI Previews - zoomable!
        ZoomableImage(
            bitmap = displayedBitmap.asImageBitmap(),
            contentDescription = "Result"
        )

        threshBitmap?.let {
            Text("Adaptive Threshold", style = MaterialTheme.typography.labelSmall)
            ZoomableImage(
                bitmap = it.asImageBitmap(),
                contentDescription = "thresh"
            )
        }

        maskBitmap?.let {
            Text("Table Mask", style = MaterialTheme.typography.labelSmall)
            ZoomableImage(
                bitmap = it.asImageBitmap(),
                contentDescription = "mask"
            )
        }

        linesBitmap?.let {
            ZoomableImage(
                bitmap = it.asImageBitmap(),
                contentDescription = "linesDebug"
            )
        }

        Text("OCR log:", modifier = Modifier.padding(top = 16.dp))
        Text(logText, style = MaterialTheme.typography.bodySmall)
    }
}
