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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import kotlinx.coroutines.withContext
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

// Define the different "Planes" of application
enum class Screen {
    SCAN_HUB,         // The main entry point with "Scan" and "Results" buttons
    TABLE_RESULTS,    // The interactive list of extracted rows
    SAMPLE_DETECTION, // OpenCV debug view
    SETTINGS
}

val PaperWhite = Color(0xFFF5F5F0)
val InkBlack = Color(0xFF1A1A1A)
val MutedGrey = Color(0xFF8A8A85)
val AccentOlive = Color(0xFF5A5A40)

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {

    // Global scope and shared results state
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val snackbarHostState = SnackbarHostState()
    // Use snapshotStateMapOf for automatic UI updates when values in the map change
    private val lastExtractedRows = mutableStateMapOf<String, ProcessorRow>()

    // Track the "Edit" state
    private var editingRowId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // OpenCV Initialization
        System.loadLibrary("opencv_java4")
        if (!org.opencv.android.OpenCVLoader.initDebug()) {
            Log.e("OpenCV", "Failed to load OpenCV")
        }

        val originalBitmap = BitmapFactory.decodeResource(resources, R.drawable.secret_sample_1_180)

        val paperColorScheme = lightColorScheme(
            primary = AccentOlive,
            onPrimary = Color.White,
            surface = PaperWhite,
            onSurface = InkBlack,
            background = PaperWhite,
            onBackground = InkBlack,
            outline = InkBlack.copy(alpha = 0.1f)
        )

        setContent {
            MaterialTheme(colorScheme = paperColorScheme) {
                var currentScreen by remember { mutableStateOf(Screen.SCAN_HUB) } // Starts here now
                val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
                val composeScope = rememberCoroutineScope()

                editingRowId?.let { id ->
                    lastExtractedRows[id]?.let { row ->
                        EditTimeDialog(
                            row = row,
                            onDismiss = { editingRowId = null },
                            onSave = { start, end ->
                                // Directly update the Map - UI will reflect this instantly
                                lastExtractedRows[id] = row.copy(startTime = start, finishTime = end)
                                editingRowId = null
                            }
                        )
                    }
                }

                ModalNavigationDrawer(
                    drawerState = drawerState,
                    drawerContent = {
                        ModalDrawerSheet {
                            Text("Extractor Hub", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge)

                            // Navigation items
                            NavigationDrawerItem(
                                label = { Text("Scan Hub") },
                                selected = currentScreen == Screen.SCAN_HUB,
                                onClick = { currentScreen = Screen.SCAN_HUB; composeScope.launch { drawerState.close() } },
                                icon = { Icon(Icons.Default.Home, null) }
                            )
                            NavigationDrawerItem(
                                label = { Text("Last Results") },
                                selected = currentScreen == Screen.TABLE_RESULTS,
                                onClick = { currentScreen = Screen.TABLE_RESULTS; composeScope.launch { drawerState.close() } },
                                icon = {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.List,
                                        contentDescription = null
                                    )
                                }
                            )
                            NavigationDrawerItem(
                                label = { Text("Settings (Hub)") },
                                selected = currentScreen == Screen.SETTINGS,
                                onClick = { currentScreen = Screen.SETTINGS; composeScope.launch { drawerState.close() } },
                                icon = { Icon(Icons.Default.Settings, null) }
                            )
                            NavigationDrawerItem(
                                label = { Text("Sample Detection") },
                                selected = currentScreen == Screen.SAMPLE_DETECTION,
                                onClick = { currentScreen = Screen.SAMPLE_DETECTION; composeScope.launch { drawerState.close() } },
                                icon = { Icon(Icons.Default.Build, null) }
                            )
                        }
                    }
                ) {
                    Scaffold(
                        topBar = {
                            TopAppBar(
                                title = { Text(currentScreen.name.replace("_", " ")) },
                                navigationIcon = {
                                    IconButton(onClick = { composeScope.launch { drawerState.open() } }) {
                                        Icon(Icons.Default.Menu, contentDescription = "Menu")
                                    }
                                }
                            )
                        },
                        snackbarHost = {
                            SnackbarHost(hostState = snackbarHostState) { data ->
                                Snackbar(
                                    containerColor = Color(0xFF2E7D32), // Emerald Green
                                    contentColor = Color.White,
                                    snackbarData = data
                                )
                            }
                        }
                    ) { paddingValues ->
                        Box(modifier = Modifier.padding(paddingValues)) {
                            when (currentScreen) {
                                Screen.SCAN_HUB -> ScanHubScreen(
                                    onScanRequest = {
                                        // This is the functional "Make Picture" trigger
                                        executeFullExtractionFlow(originalBitmap) {
                                            currentScreen = Screen.TABLE_RESULTS
                                        }
                                    },
                                    onViewResults = { currentScreen = Screen.TABLE_RESULTS }
                                )
                                Screen.TABLE_RESULTS -> TableResultsScreen(
                                    rowsMap = lastExtractedRows,
                                    onRowClick = { id -> editingRowId = id }
                                )
                                Screen.SAMPLE_DETECTION -> TableDetectionDebugScreen(originalBitmap)
                                Screen.SETTINGS -> Text("Settings view")
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * This is the "Full Flow" function:
     * 1. Runs Fast Detection (Pre-OCR)
     * 2. Shows UI Confirmation Dialog
     * 3. Runs Heavy OCR in Background
     * 4. Auto-Redirects on success
     */
    private fun executeFullExtractionFlow(bitmap: Bitmap, onFinished: () -> Unit) {
        scope.launch {
            val detection = withContext(Dispatchers.Default) {
                // We will collect Mats here to ensure we release them all
                var grayMat: Mat? = null
                var deskewMat: Mat? = null

                try {

                    grayMat = TableDetector.bitmapToGrayMat(bitmap)

                    // Note: deskewGrayMat should return a NEW Mat if it modifies it
                    deskewMat = TableDetector.deskewGrayMat(grayMat!!) ?: grayMat!!

                    val detection = TableDetector.detectTableCells(deskewMat!!)

                    detection
                } finally {
                    grayMat?.release()
                    // Only release deskewMat if it's a different object than grayMat
                    if (deskewMat != grayMat) {
                        deskewMat?.release()
                    }
                }
            }

            scope.launch {
                snackbarHostState.showSnackbar(
                    message = "Table detection success! Extracting text...",
                    duration = SnackbarDuration.Short
                )
            }

            scope.launch {
                val (table, analysis) = withContext(Dispatchers.IO) {
                    val imageBitmap = TableDetector.matToBitmap(detection.gray)
                    val rawTextGrid = TextProcessor.extractTextFromCells(detection.cells, imageBitmap, listOf(0, 1, 2, 3))
                    val table = TextProcessor.refineTableData(rawTextGrid)
                    val analysis = CellAnalyzer.analyzeCells(detection.thresh, detection.cells)
                    Pair(table, analysis)
                }

                lastExtractedRows.clear()
                for (row in table.indices) {
                    val id = "$row"
                    lastExtractedRows[id] = ProcessorRow(id, table[row][0], table[row][2], table[row][3])
                }

                detection.gray.release()
                detection.thresh.release()
                detection.mask.release()
                detection.lines.release()
                // Switch View Automatically
                onFinished()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}

// --- COMPOSE SCREENS ---

@Composable
fun ScanHubScreen(onScanRequest: () -> Unit, onViewResults: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Main Action: Make Picture / Scan
        Button(
            onClick = onScanRequest,
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp),
            contentPadding = PaddingValues(16.dp)
        ) {
            Icon(Icons.Default.CameraAlt, null, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(16.dp))
            Column {
                Text("SCAN NEW SHEET", style = MaterialTheme.typography.titleMedium)
                Text("Run OpenCV + ML Kit", style = MaterialTheme.typography.labelSmall)
            }
        }

        Spacer(Modifier.height(24.dp))

        // Secondary Action: Review
        OutlinedButton(
            onClick = onViewResults,
            modifier = Modifier
                .fillMaxWidth()
                .height(70.dp)
        ) {
            Text("REVIEW RECENT DATA")
        }
    }
}

    /**
     * Debug image showing for table detection
     */
    @Composable
    fun TableDetectionDebugScreen(originalBitmap: Bitmap) {
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

                                // Draw the "Boxed" debug image
                                boxedMat = TableDetector.drawCells(detection.gray, detection.cells)
                                val cellsAnalysis = CellAnalyzer.analyzeCells(detection.thresh, detection.cells)

                                val marginsDrawn = detection.gray.clone()
                                if (marginsDrawn.channels() == 1) {
                                    Imgproc.cvtColor(marginsDrawn, marginsDrawn, Imgproc.COLOR_GRAY2RGB)
                                }
                                val red = Scalar(255.0, 0.0, 0.0)
                                for (row in detection.cells.indices) {
                                    for (col in listOf(2, 3)) {
                                        val (isCrossed, pointsTop, pointsBtm) = CellAnalyzer.detectPenCrossing(detection.thresh, detection.cells[row][col])
                                        if (isCrossed)
                                            Log.d("DEBUG", "Row: $row, col: $col has a crossing over time")
                                        val matTop = MatOfPoint(*pointsTop)
                                        val matBtm = MatOfPoint(*pointsBtm)
                                        Imgproc.polylines(marginsDrawn, listOf(matTop), true, red, 2)
                                        Imgproc.polylines(marginsDrawn, listOf(matBtm), true, red, 2)
                                        matTop.release()
                                        matBtm.release()
                                    }
                                }
                                val marginsBmp = TableDetector.matToBitmap(marginsDrawn)
                                marginsDrawn.release()
                                val boxedBmp = TableDetector.matToBitmap(boxedMat!!)

                                // Convert deskewMat to bitmap now so we can release the Mat
                                val deskewedBmp = TableDetector.matToBitmap(detection.gray)

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

                        // Update all UI state variables at once on the Main thread
                        threshBitmap = results.thresh
                        maskBitmap = results.margins
                        linesBitmap = results.lines
                        displayedBitmap = results.boxed

                        logText = withContext(Dispatchers.Default) {
                            // OCR (Text Extraction)
                            val rawTextGrid = TextProcessor.extractTextFromCells(results.cells, results.deskewedBmp, listOf(0, 2, 3))
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
