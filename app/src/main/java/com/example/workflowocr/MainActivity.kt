package com.example.workflowocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.text.Text
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.*
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
        val originalBitmap = BitmapFactory.decodeResource(resources, R.drawable.test_image)

        // Run detection + OCR off UI thread
        scope.launch {
            val grayMat = withContext(Dispatchers.Default) { TableDetector.bitmapToGrayMat(originalBitmap) }
            val detectedCells = withContext(Dispatchers.Default) { TableDetector.detectTableCells(grayMat) }

            // For display: convert gray mat back to bitmap
            val displayBitmap = withContext(Dispatchers.Default) { TableDetector.matToBitmap(grayMat) }

            // Run OCR on cells (do not block main thread)
            val cellTexts = withContext(Dispatchers.IO) {
                extractTextFromCells(detectedCells, originalBitmap) // make this suspend-friendly (see below)
            }

            // log results on main thread
            for ((index, text) in cellTexts.withIndex()) {
                Log.d("TABLE_CELL", "Cell $index: $text")
            }

            // update UI via setContent or a state variable...
        }

        setContent {
//            MaterialTheme {
//                Surface(modifier = Modifier.fillMaxSize()) {
//                    OCRFromGalleryScreen()
//                }
//            }
            var grayBitmap by remember { mutableStateOf(testBitmap) }

            Column(modifier = Modifier.padding(16.dp)) {
                Button(onClick = {
                    grayBitmap = TableDetector.convertBitmapToGray(testBitmap)
                }) {
                    Text("Convert to Grayscale (OpenCV)")
                }

                Spacer(modifier = Modifier.height(16.dp))

                Image(
                    bitmap = grayBitmap.asImageBitmap(),
                    contentDescription = "OpenCV Test Image",
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    suspend fun extractTextFromCells(
        cells: List<Rect>,
        originalBitmap: Bitmap
    ): List<String> = withContext(Dispatchers.IO) {

        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val results = mutableListOf<String>()

        for (rect in cells) {
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

                results.add(text)
            } catch (e: Exception) {
                results.add("EXCEPTION: ${e.message}")
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
