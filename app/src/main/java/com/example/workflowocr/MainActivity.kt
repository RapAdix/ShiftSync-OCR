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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.withContext
import org.opencv.core.Mat

// Define the different "Planes" of your application
enum class Screen {
    SAMPLE_DETECTION,
    GALLERY_OCR,
    SETTINGS
}

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // OpenCV Initialization
        System.loadLibrary("opencv_java4")
        if (!org.opencv.android.OpenCVLoader.initDebug()) {
            Log.e("OpenCV", "Failed to load OpenCV")
        }

        val originalBitmap = BitmapFactory.decodeResource(resources, R.drawable.secret_sample_1_180)

        setContent {
            // 1. Navigation State
            var currentScreen by remember { mutableStateOf(Screen.SAMPLE_DETECTION) }
            val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
            val scope = rememberCoroutineScope()

            // 2. The Navigation Drawer Wrapper
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ModalDrawerSheet {
                        Spacer(Modifier.height(12.dp))
                        Text("Table OCR App", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge)

                        NavigationDrawerItem(
                            label = { Text("Sample Detection") },
                            selected = currentScreen == Screen.SAMPLE_DETECTION,
                            onClick = {
                                currentScreen = Screen.SAMPLE_DETECTION
                                scope.launch { drawerState.close() }
                            },
                            icon = { Icon(Icons.Default.Build, contentDescription = null) }
                        )
                        NavigationDrawerItem(
                            label = { Text("Gallery OCR") },
                            selected = currentScreen == Screen.GALLERY_OCR,
                            onClick = {
                                currentScreen = Screen.GALLERY_OCR
                                scope.launch { drawerState.close() }
                            },
                            icon = { Icon(Icons.Default.PhotoLibrary, contentDescription = null) }
                        )
                    }
                }
            ) {
                // 3. The Main Screen Content Scaffolding
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(currentScreen.name.replace("_", " ")) },
                            navigationIcon = {
                                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                    Icon(Icons.Default.Menu, contentDescription = "Menu")
                                }
                            }
                        )
                    }
                ) { paddingValues ->
                    Box(modifier = Modifier.padding(paddingValues)) {
                        // 4. Switch between "Main Planes"
                        when (currentScreen) {
                            Screen.SAMPLE_DETECTION -> {
                                TableDetectionScreen(originalBitmap)
                            }
                            Screen.GALLERY_OCR -> {
                                // Your OCRFromGalleryScreen() goes here
                                Text("Gallery OCR Plane Coming Soon")
                            }
                            Screen.SETTINGS -> {
                                Text("Settings Plane")
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Your original logic moved into a dedicated Composable "Plane"
     */
    @Composable
    fun TableDetectionScreen(originalBitmap: Bitmap) {
        var displayedBitmap by remember { mutableStateOf(originalBitmap) }
        var threshBitmap by remember { mutableStateOf<Bitmap?>(null) }
        var maskBitmap by remember { mutableStateOf<Bitmap?>(null) }
        var linesBitmap by remember { mutableStateOf<Bitmap?>(null) }
        var logText by remember { mutableStateOf("") }
        val scope = rememberCoroutineScope()

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
                                grayMat = TableDetector.bitmapToGrayMat(originalBitmap)

                                // Note: deskewGrayMat should return a NEW Mat if it modifies it
                                deskewMat = TableDetector.deskewGrayMat(grayMat!!) ?: grayMat!!

                                val detection = TableDetector.detectTableCells(deskewMat!!)

                                // 2. Prepare Bitmaps for UI
                                val threshBmp = TableDetector.matToBitmap(detection.thresh)
                                val maskBmp = TableDetector.matToBitmap(detection.mask)
                                val linesBmp = TableDetector.matToBitmap(detection.lines)
                                // IMPORTANT: Release the internal Mats inside the Result object
                                // These were created inside detectTableCells
                                detection.thresh.release()
                                detection.mask.release()
                                detection.lines.release()

                                // Draw the "Boxed" debug image
                                boxedMat = TableDetector.drawCells(deskewMat!!, detection.cells)
                                val boxedBmp = TableDetector.matToBitmap(boxedMat!!)

                                // Convert deskewMat to bitmap now so we can release the Mat
                                val deskewedBmp = TableDetector.matToBitmap(deskewMat!!)

                                // Return everything as Bitmaps (Safe for JVM memory)
                                object {
                                    val boxed = boxedBmp
                                    val cells = detection.cells
                                    val deskewedBmp = deskewedBmp
                                    val thresh = threshBmp
                                    val mask = maskBmp
                                    val lines = linesBmp
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

                        // Update all UI state variables at once on the Main thread
                        threshBitmap = results.thresh
                        maskBitmap = results.mask
                        linesBitmap = results.lines
                        displayedBitmap = results.boxed

                        logText = withContext(Dispatchers.Default) {
                            // OCR (Text Extraction)
                            val rawTextGrid = TextProcessor.extractTextFromCells(results.cells, results.deskewedBmp)
                            val textGrid = TextProcessor.refineTableData(rawTextGrid)

                            // Build Log Text
                            val logBuilder = StringBuilder()
                            textGrid.forEachIndexed { r, row ->
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

            // UI Previews
            Image(bitmap = displayedBitmap.asImageBitmap(), contentDescription = "Result")

            threshBitmap?.let {
                Text("Adaptive Threshold", style = MaterialTheme.typography.labelSmall)
                Image(bitmap = it.asImageBitmap(), contentDescription = "thresh")
            }

            maskBitmap?.let {
                Text("Table Mask", style = MaterialTheme.typography.labelSmall)
                Image(bitmap = it.asImageBitmap(), contentDescription = "mask")
            }

            linesBitmap?.let {
                Image(bitmap = it.asImageBitmap(), contentDescription = "linesDebug")
            }

            Text("OCR log:", modifier = Modifier.padding(top = 16.dp))
            Text(logText, style = MaterialTheme.typography.bodySmall)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
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
