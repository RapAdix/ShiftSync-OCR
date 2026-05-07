package com.example.workflowocr

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.core.content.ContextCompat
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
    ATTENDANCE_COUNT, // How many people work at specific times
    SAMPLE_DETECTION, // OpenCV debug view
    SETTINGS
}

val PaperWhite = Color(0xFFF5F5F0)
val InkBlack = Color(0xFF1A1A1A)
val MutedGrey = Color(0xFF8A8A85)
val AccentOlive = Color(0xFF5A5A40)

val WorkplaceOpeningTime: Int = 6  // Hour of opening
val WorkplaceClosingTime: Int = 1 // Hour of closure

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {

    // Global scope and shared results state
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val snackbarHostState = SnackbarHostState()

    private val tableViewModel: TableViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // OpenCV Initialization
        System.loadLibrary("opencv_java4")
        if (!org.opencv.android.OpenCVLoader.initDebug()) {
            Log.e("OpenCV", "Failed to load OpenCV")
        }

        val originalBitmap = BitmapFactory.decodeResource(resources, R.drawable.secret_sample_4_nodpi)

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
                var schedulesExpanded by remember { mutableStateOf(false) } // Track unfolding

                val availableDates by tableViewModel.availableDates.collectAsState()

                // Refresh when the drawer opens to catch outside changes
                LaunchedEffect(drawerState.isOpen) {
                    if (drawerState.isOpen) {
                        tableViewModel.refreshAvailableDates()
                    }
                }

                val context = LocalContext.current
                var tempImageUri by remember { mutableStateOf<Uri?>(null) }

                // The "Launcher" that handles the result of the camera app
                val cameraLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.TakePicture()
                ) { success ->
                    if (success && tempImageUri != null) {
                        val bitmap = ImageUtils.uriToBitmap(context, tempImageUri!!)

                        executeFullExtractionFlow(bitmap) {
                            currentScreen = Screen.TABLE_RESULTS
                        }
                    }
                }

                val permissionLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.RequestPermission()
                        ) { isGranted ->
                    if (isGranted) {
                        val uri = ImageUtils.createTempImageUri(context)
                        tempImageUri = uri // Update state variable
                        cameraLauncher.launch(uri)
                    } else {
                        Toast.makeText(context, "Camera permission is required.", Toast.LENGTH_SHORT).show()
                    }
                }

                // The Click Handler (Logic for the Button)
                val onScanRequest = {
                    val hasPermission = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED

                    if (hasPermission) {
                        val uri = ImageUtils.createTempImageUri(context)
                        tempImageUri = uri
                        cameraLauncher.launch(uri)
                    } else {
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                }

                if (tableViewModel.onDateSupplied != null) {
                    var inputDate by remember { mutableStateOf("") }

                    AlertDialog(
                        title = { Text("Manual Date Entry") },
                        text = {
                            OutlinedTextField(
                                value = inputDate,
                                onValueChange = { inputDate = it },
                                label = { Text("Enter Date (MM-DD)") }
                            )
                        },
                        onDismissRequest = {
                            val action = tableViewModel.onDateSupplied
                            tableViewModel.onDateSupplied = null
                            action?.invoke(null) // Signal cleanup
                        },
                        confirmButton = {
                            Button(onClick = {
                                val action = tableViewModel.onDateSupplied
                                tableViewModel.onDateSupplied = null
                                action?.invoke(inputDate) // Execute the "frozen" logic
                            }) { Text("Process") }
                        }
                    )
                }

                ModalNavigationDrawer(
                    drawerState = drawerState,
                    drawerContent = {
                        ModalDrawerSheet {
                            // Header Row
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // This button closes the drawer
                                IconButton(onClick = { composeScope.launch { drawerState.close() } }) {
                                    Icon(
                                        imageVector = Icons.Default.Menu,
                                        contentDescription = "Close Menu",
                                        tint = InkBlack
                                    )
                                }

                                Text(
                                    text = "Extractor Hub",
                                    style = MaterialTheme.typography.titleLarge,
                                    modifier = Modifier.padding(start = 12.dp)
                                )
                            }

                            HorizontalDivider(color = InkBlack.copy(alpha = 0.05f))
                            Spacer(modifier = Modifier.height(8.dp))

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
                                label = { Text("Attendance Summary") },
                                selected = currentScreen == Screen.ATTENDANCE_COUNT,
                                onClick = { currentScreen = Screen.ATTENDANCE_COUNT; composeScope.launch { drawerState.close() } },
                                icon = { Icon(Icons.Filled.Calculate, null) }
                            )

                            // The Unfolding "Saved Schedules" Section
                            NavigationDrawerItem(
                                label = { Text("Saved Schedules") },
                                selected = false, // The parent itself isn't a "screen"
                                onClick = { schedulesExpanded = !schedulesExpanded },
                                icon = { Icon(Icons.Default.History, null) },
                                badge = {
                                    Icon(
                                        imageVector = if (schedulesExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                        contentDescription = null
                                    )
                                }
                            )

                            // Animated Sub-Items
                            AnimatedVisibility(
                                visible = schedulesExpanded,
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut()
                            ) {
                                Column(modifier = Modifier.padding(start = 24.dp)) {
                                    if (availableDates.isEmpty()) {
                                        Text(
                                            "No saves found",
                                            style = MaterialTheme.typography.labelMedium,
                                            modifier = Modifier.padding(16.dp),
                                            color = MutedGrey
                                        )
                                    }

                                    availableDates.forEach { date ->
                                        val isCurrent = tableViewModel.currentWorkingDate == date

                                        // Track if THIS specific item is showing its delete dialog
                                        var showConfirmForThisItem by remember { mutableStateOf(false) }

                                        NavigationDrawerItem(
                                            label = { Text(date, style = MaterialTheme.typography.bodyMedium) },
                                            selected = isCurrent,
                                            onClick = {
                                                tableViewModel.loadDate(date)
                                                currentScreen = Screen.TABLE_RESULTS
                                                composeScope.launch { drawerState.close() }
                                            },
                                            icon = {
                                                Icon(
                                                    Icons.Default.CalendarToday,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(18.dp),
                                                    tint = if (isCurrent) AccentOlive else MutedGrey
                                                )
                                            },
                                            // The badge is automatically pushed to the far right
                                            badge = {
                                                IconButton(onClick = { showConfirmForThisItem = true }) {
                                                    Icon(
                                                        imageVector = Icons.Default.Delete,
                                                        contentDescription = "Delete",
                                                        modifier = Modifier.size(20.dp),
                                                        tint = Color.Red.copy(alpha = 0.6f)
                                                    )
                                                }
                                            },
                                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                                        )

                                        // Confirmation Dialog specific to this loop iteration
                                        if (showConfirmForThisItem) {
                                            AlertDialog(
                                                onDismissRequest = { showConfirmForThisItem = false },
                                                title = { Text("Delete $date?") },
                                                text = { Text("All snippets and JSON for this day will be removed.") },
                                                confirmButton = {
                                                    TextButton(
                                                        onClick = {
                                                            // If we just deleted what we are looking at, go home
                                                            if (tableViewModel.currentWorkingDate == date) {
                                                                currentScreen = Screen.SCAN_HUB
                                                            }
                                                            showConfirmForThisItem = false
                                                            tableViewModel.deleteDate(date)
                                                        },
                                                        colors = ButtonDefaults.textButtonColors(contentColor = Color.Red)
                                                    ) {
                                                        Text("Delete")
                                                    }
                                                },
                                                dismissButton = {
                                                    TextButton(onClick = { showConfirmForThisItem = false }) {
                                                        Text("Cancel")
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                            }
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
                                },
                                actions = {
                                    // This block adds buttons to the RIGHT side of the bar
                                    if (currentScreen == Screen.TABLE_RESULTS) {
                                        IconButton(
                                            onClick = { currentScreen = Screen.ATTENDANCE_COUNT }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Calculate,
                                                contentDescription = "View Attendance Summary",
                                                tint = AccentOlive
                                            )
                                        }
                                    }
                                    if (currentScreen == Screen.ATTENDANCE_COUNT) {
                                        IconButton(
                                            onClick = { currentScreen = Screen.TABLE_RESULTS }
                                        ) {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.List,
                                                contentDescription = "View Table Results",
                                                tint = AccentOlive
                                            )
                                        }
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
                                    onStubRequest = {
                                        // This is the functional "Make Picture" trigger
                                        executeFullExtractionFlow(originalBitmap) {
                                            currentScreen = Screen.TABLE_RESULTS
                                        }
                                    },
                                    onScanRequest = onScanRequest
                                )
                                Screen.TABLE_RESULTS -> TableResultsScreen(
                                    tableViewModel
                                )
                                Screen.ATTENDANCE_COUNT -> AttendanceSummaryScreen(tableViewModel.extractedRows)
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
                val imageBitmap = TableDetector.matToBitmap(detection.gray)
                val date = try {
                    TextProcessor.determineDate(detection.cells, imageBitmap)
                } catch (e: TextProcessor.CouldNotDetermineDateException) {
                    tableViewModel.onDateSupplied = { manualDate ->
                        proceedWithExtraction(manualDate, detection, imageBitmap, onFinished)
                    }
                    return@launch
                }
                // If no exception, just run immediately
                proceedWithExtraction(date, detection, imageBitmap, onFinished)
            }
        }
    }

    private fun proceedWithExtraction(
        date: String?,
        detection: TableDetector.TableDetectionResult,
        imageBitmap: Bitmap,
        onFinished: () -> Unit
    ) {
        scope.launch(Dispatchers.IO) {
            if (date != null) {
                val rawTextGrid = withContext(Dispatchers.IO) {
                    TextProcessor.extractTextFromCells(
                        detection.cells,
                        imageBitmap,
                        listOf(0, 2, 3)
                    )
                }
                val (table, analysis, rowPaths) = withContext(Dispatchers.IO) {
                    val table = TextProcessor.refineTableData(rawTextGrid)
                    val analysis = CellAnalyzer.analyzeCells(detection.thresh, detection.cells)

                    // Fallback snippets cut out from the table image
                    val rowPaths = tableViewModel.storageManager.createSnippets(imageBitmap, detection.cells, date)

                    Triple(table, analysis, rowPaths)
                }
                tableViewModel.loadDate(date)
                val page = "e_p1" // TODO add choosing of the page (e-employee, m-manager, p1-page1)
                // TODO sanity check - check if the number of rows match between this page and previously captured page. If no - display warning

                for (row in 1 until table.size) {
                    val id = page + "_$row"
                    val paths = rowPaths[row] ?: emptyMap()

                    val existingRow = tableViewModel.extractedRows[id]
                    Log.d("DEBUG", "ROW: $row, name: ${table[row][0]}, start: ${table[row][2]}, finish: ${table[row][3]}, existed:${existingRow != null}")

                    if (existingRow != null) {
                        val namePath = StorageManager.rotateFile(existingRow.nameSnippetPath, paths["name"])
                        val startPath = StorageManager.rotateFile(existingRow.startTimeSnippetPath, paths["start"])
                        val finishPath = StorageManager.rotateFile(existingRow.finishTimeSnippetPath, paths["finish"])
                        val modificationPath = StorageManager.rotateFile(existingRow.newModificationsSnippetPath, paths["mods"])
                        tableViewModel.extractedRows[id] = existingRow.copy(
                            newAnalysis = analysis[row],
                            nameSnippetPath = namePath,
                            startTimeSnippetPath = startPath,
                            finishTimeSnippetPath = finishPath,
                            newModificationsSnippetPath = modificationPath
                        )
                    } else {
                        tableViewModel.extractedRows[id] = ProcessorRow(
                            id = id,
                            name = table[row][0],
                            startTime = table[row][2],
                            finishTime = table[row][3],
                            confirmedAnalysis = null,
                            newAnalysis = analysis[row],
                            // Linking the files we just created
                            nameSnippetPath = paths["name"],
                            startTimeSnippetPath = paths["start"],
                            finishTimeSnippetPath = paths["finish"],
                            oldModificationsSnippetPath = paths["mods"],
                            newModificationsSnippetPath = null
                        )
                    }
                }
                tableViewModel.saveToStorage()
            }

            detection.gray.release()
            detection.thresh.release()
            detection.mask.release()
            detection.lines.release()
            // Switch View Automatically
            onFinished()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}

// --- COMPOSE SCREENS ---

@Composable
fun ScanHubScreen(onScanRequest: () -> Unit, onStubRequest: () -> Unit) {
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
            onClick = onStubRequest,
            modifier = Modifier
                .fillMaxWidth()
                .height(70.dp)
        ) {
            Text("Use Stub")
        }
    }
}

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
                            Log.d("DEBUG", "detectTableCells exited")

                            // 2. Prepare Bitmaps for UI
                            val threshBmp = TableDetector.matToBitmap(detection.thresh)
                            Log.d("DEBUG", "threshBmp matToBitmap preparations finished")
                            val maskBmp = TableDetector.matToBitmap(detection.mask)
                            Log.d("DEBUG", "maskBmp matToBitmap preparations finished")
                            val linesBmp = TableDetector.matToBitmap(detection.lines)
                            Log.d("DEBUG", "linesBmp matToBitmap preparations finished")
//                            val deskewedBmp = TableDetector.matToBitmap(detection.gray)
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
                    Log.d("DEBUG", "summarized 'results' object obtained, memory released")

                    // Update all UI state variables at once on the Main thread
                    threshBitmap = results.thresh.scaleForPreview()
                    maskBitmap = results.mask.scaleForPreview()
                    linesBitmap = results.lines.scaleForPreview()
                    displayedBitmap = results.boxed.scaleForPreview()

                    logText = withContext(Dispatchers.Default) {
                        // OCR (Text Extraction)
                        val rawTextGrid = TextProcessor.extractTextFromCells(results.cells, results.deskewedBmp, listOf(0, 2, 3))
                        val textGrid = TextProcessor.refineTableData(rawTextGrid)

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

fun Bitmap.scaleForPreview(maxWidth: Int = 1000): Bitmap {
    if (this.width <= maxWidth) return this
    val aspectRatio = this.height.toFloat() / this.width.toFloat()
    val targetHeight = (maxWidth * aspectRatio).toInt()
    return Bitmap.createScaledBitmap(this, maxWidth, targetHeight, true)
}
