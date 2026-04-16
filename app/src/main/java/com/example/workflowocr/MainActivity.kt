package com.example.workflowocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.opencv.core.Rect

class MainActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        System.loadLibrary("opencv_java4")
        if (!org.opencv.android.OpenCVLoader.initDebug()) {
            Log.e("OpenCV", "Failed to load OpenCV")
        } else {
            Log.i("OpenCV", "OpenCV loaded successfully")
        }
        val originalBitmap = BitmapFactory.decodeResource(resources, R.drawable.secret_sample_1_180)

        setContent {
            var displayedBitmap by remember { mutableStateOf(originalBitmap) }
            var threshBitmap by remember { mutableStateOf<Bitmap?>(null) }
            var maskBitmap by remember { mutableStateOf<Bitmap?>(null) }
            var linesBitmap by remember { mutableStateOf<Bitmap?>(null) }
            var logText by remember { mutableStateOf("") }

            val scope = rememberCoroutineScope()

            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color(0xFFB71C1C)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Button(onClick = {
                        scope.launch {

                            val (deskewMat, result) = withContext(Dispatchers.Default) {
                                // Convert to grayscale Mat
                                //TODO Add filtering out pen colors. time change detection can be detected as marks around the middle.
                                val grayMat = TableDetector.bitmapToGrayMat(originalBitmap)

                                val deskewMat = TableDetector.deskewGrayMat(grayMat) ?: grayMat

                                // Detect cells
                                val result = TableDetector.detectTableCells(deskewMat)
                                Pair(deskewMat, result)
                            }

                            Log.d("DEBUG", "deskewMat = ${deskewMat.rows()} x ${deskewMat.cols()}")
                            Log.d(
                                "DEBUG",
                                "thresh  = ${result.thresh.rows()} x ${result.thresh.cols()}"
                            )
                            Log.d(
                                "DEBUG",
                                "mask    = ${result.mask.rows()} x ${result.mask.cols()}"
                            )
                            Log.d(
                                "DEBUG",
                                "cells detected = ${result.cells.size} x ${result.cells[0].size}"
                            )

                            threshBitmap = withContext(Dispatchers.Default) {
                                TableDetector.matToBitmap(result.thresh)
                            }

                            maskBitmap = withContext(Dispatchers.Default) {
                                TableDetector.matToBitmap(result.mask)
                            }

                            // Draw rectangles on the bitmap
                            val boxed = withContext(Dispatchers.Default) {
                                TableDetector.drawCells(deskewMat, result.cells)
                            }

                            displayedBitmap = withContext(Dispatchers.Default) { TableDetector.matToBitmap(boxed)}
                            linesBitmap = withContext(Dispatchers.Default) { TableDetector.matToBitmap(result.lines)}

                            // OCR each cell
                            val textGrid: Array<Array<String>> = extractTextFromCells(
                                result.cells,
                                TableDetector.matToBitmap(deskewMat)
                            )

                            val builder = StringBuilder()
                            textGrid.forEachIndexed { r, row ->
                                builder.append("Row $r: ")
                                row.forEachIndexed { c, text ->
                                    val cleanText = text.replace("\n", " ")
                                    builder.append("[$cleanText] ")
                                    Log.d("CELL_OCR", "Cell [$r][$c]: $text")
                                }
                                builder.append("\n")
                            }

                            logText = builder.toString()
                        }
                    }) {
                        Text("Run Table Detection + OCR")
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Image(bitmap = displayedBitmap.asImageBitmap(), contentDescription = null,)
                    threshBitmap?.let {
                        Image(bitmap = it.asImageBitmap(), contentDescription = "thresh")
                    }

                    maskBitmap?.let {
                        Image(bitmap = it.asImageBitmap(), contentDescription = "mask")
                    }

                    linesBitmap?.let {
                        Image(bitmap = it.asImageBitmap(), contentDescription = "linesDebug")
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text("OCR log:")
                    Text(logText)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    fun getRectForCell(cell: TableDetector.TableCell) : Rect{
        val cellW = (Math.abs(cell.topRight.x - cell.topLeft.x) +
                Math.abs(cell.bottomRight.x - cell.bottomLeft.x)) / 2.0
        val cellH = (Math.abs(cell.bottomLeft.y - cell.topLeft.y) +
                Math.abs(cell.bottomRight.y - cell.topRight.y)) / 2.0
        return Rect(
            cell.topLeft.x.toInt(),
            cell.topLeft.y.toInt(),
            cellW.toInt(),
            cellH.toInt()
        )
    }

    suspend fun extractTextFromCells(
        cells: Array<Array<TableDetector.TableCell>>,
        originalBitmap: Bitmap
    ): Array<Array<String>> = withContext(Dispatchers.IO) {

        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val results = Array<Array<String>>(cells.size) {Array<String>(cells[0].size) {""} }

        for (row in 0 until cells.size) {
            for (col in 0 until cells[0].size) {
                val rect = getRectForCell(cells[row][col])
                try {
                    val cellBitmap = Bitmap.createBitmap(
                        originalBitmap,
                        rect.x, rect.y, rect.width, rect.height
                    )

                    val inputImage = InputImage.fromBitmap(cellBitmap, 0)

                    val text = suspendCancellableCoroutine<String> { cont ->
                        recognizer.process(inputImage)
                            .addOnSuccessListener { cont.resume(it.text) {} }
                            .addOnFailureListener { e -> cont.resume("ERROR: ${e.message}") {} }
                    }

                    results[row][col] = text
                } catch (e: Exception) {
                    results[row][col]="EXCEPTION: ${e.message}"
                }
            }
        }

        recognizer.close()
        return@withContext results
    }

}

@Composable
fun OCRFromGalleryScreen() {
    val context = LocalContext.current
    var pickedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var pickedUri by remember { mutableStateOf<Uri?>(null) }
    var ocrText by remember { mutableStateOf("No text recognized yet.") }
    var isProcessing by remember { mutableStateOf(false) }

    // Launcher updates the state
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        pickedUri = uri  // just store URI
    }

    // Composable-level effect: triggers whenever pickedUri changes
    LaunchedEffect(pickedUri) {
        pickedUri?.let { uri ->
            isProcessing = true
            val bmp = loadBitmapFromUri(context, uri)
            pickedBitmap = bmp
            ocrText = runMlKitTextRecognition(context, uri, bmp)
            isProcessing = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        // Pick image button
        Button(onClick = { launcher.launch("image/*") }) {
            Text("Pick image from gallery")
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Show image preview if available
        pickedBitmap?.let { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Picked image",
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 150.dp, max = 420.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Processing indicator
        if (isProcessing) {
            Text("Processing image...", style = MaterialTheme.typography.bodyLarge)
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Spacer(modifier = Modifier.height(12.dp))

        // OCR result box
        Text(
            text = "Recognized text:",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            tonalElevation = 2.dp,
            shape = MaterialTheme.shapes.medium
        ) {
            Text(
                text = ocrText,
                modifier = Modifier.padding(12.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Button placeholder for next step: table extraction
        Button(onClick = { /* later: run OpenCV table detection and map text to cells */ }) {
            Text("Next: detect table (coming soon)")
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

/** Loads a Bitmap from a Uri. Uses ImageDecoder for API >= 28, otherwise MediaStore. */
suspend fun loadBitmapFromUri(context: android.content.Context, uri: Uri): Bitmap =
    withContext(Dispatchers.IO) {
        return@withContext if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val src = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(src) { decoder, _, _ ->
                // Optional: scale or configure decoder here
                decoder.isMutableRequired = true
            }
        } else {
            MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
        }
    }

/** Runs ML Kit on-device text recognition.
 * We accept both the Uri and optional preloaded bitmap for convenience.
 */
suspend fun runMlKitTextRecognition(
    context: android.content.Context,
    uri: Uri,
    bitmap: Bitmap? = null
): String = withContext(Dispatchers.IO) {
    try {
        // Build InputImage. Prefer file path/URI for correct rotation metadata.
        val inputImage = if (bitmap == null) {
            InputImage.fromFilePath(context, uri)
        } else {
            // If you already loaded the bitmap, you may also use InputImage.fromBitmap(bitmap, 0)
            InputImage.fromBitmap(bitmap, 0)
        }

        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        // ML Kit uses Task-based API; suspend until complete
        val task = recognizer.process(inputImage)
        val result = kotlinx.coroutines.suspendCancellableCoroutine<String> { cont ->
            task.addOnSuccessListener { visionText ->
                cont.resume(visionText.text) {}
            }
            task.addOnFailureListener { e ->
                Log.e("OCR", "ML Kit error", e)
                cont.resumeWith(Result.success("OCR failed: ${e.message}"))
            }
        }

        recognizer.close()
        return@withContext result
    } catch (e: Exception) {
        Log.e("OCR", "Exception in runMlKitTextRecognition", e)
        return@withContext "OCR exception: ${e.localizedMessage}"
    }
}
