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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

// Create a provider holder. It throws an error if a screen tries to use it without initialization.
val LocalTableViewModel = staticCompositionLocalOf<TableViewModel> {
    error("No TableViewModel provided! Wrap your content in CompositionLocalProvider.")
}

// Define the different "Planes" of application
enum class Screen(val displayName: String) {
    SCAN_HUB("Scan Hub"),                   // The main entry point with "Scan" and "Results" buttons
    PROCESSING_PREVIEW("Preview"),          // A waiting screen with debug info shown after user makes a picture
    TABLE_RESULTS("Table Results"),         // The interactive list of extracted rows
    ATTENDANCE_COUNT("Attendance"),         // How many people work at specific times
    SAMPLE_DETECTION("SAMPLE_DETECTION"),   // OpenCV debug view
    VLH_MANAGEMENT("VLH Management"),       // VLH table, GC's scanning, Crew required
    SETTINGS("Settings"),
    ABOUT("About")
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

    private val tableViewModel: TableViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // OpenCV Initialization
        System.loadLibrary("opencv_java4")
        if (!org.opencv.android.OpenCVLoader.initDebug()) {
            Log.e("OpenCV", "Failed to load OpenCV")
        }

        val originalBitmap = BitmapFactory.decodeResource(resources, R.drawable.secret_sample_7_nodpi)

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
            CompositionLocalProvider(LocalTableViewModel provides tableViewModel) {
                MaterialTheme(colorScheme = paperColorScheme) {
                    var currentScreen by remember { mutableStateOf(Screen.SCAN_HUB) } // Starts here now
                    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
                    val composeScope = rememberCoroutineScope()
                    var schedulesExpanded by remember { mutableStateOf(false) } // Track unfolding

                    var isDebugCapture by remember { mutableStateOf(false) }
                    var capturedBitmap by remember { mutableStateOf<Bitmap?>(null) }
                    var cellPreviewBitmap by remember { mutableStateOf<Bitmap?>(null) }
                    var diagnosticBitmap by remember { mutableStateOf<Bitmap?>(null) }
                    var processingErrorMsg by remember { mutableStateOf<String?>(null) }

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
                            val bitmap = StorageManager.ImageUtils.uriToBitmap(context, tempImageUri!!)

                            if (isDebugCapture) {
                                capturedBitmap = bitmap
                                currentScreen = Screen.SAMPLE_DETECTION
                            } else {
                                // 1. Immediately cache the raw photo and show it on screen
                                capturedBitmap = bitmap
                                cellPreviewBitmap = null
                                diagnosticBitmap = null
                                processingErrorMsg = null
                                currentScreen = Screen.PROCESSING_PREVIEW

                                // 2. Fire off background operations while user views the preview
                                executeFullExtractionFlow(
                                    bitmap = bitmap,
                                    onSuccess = {
                                        currentScreen = Screen.TABLE_RESULTS
                                    },
                                    setPreview = { cellPreview, debugImage, errorMsg ->
                                        // Instead of navigating away, we simply supply the error artifacts
                                        // to update the preview screen dynamically!
                                        cellPreviewBitmap = cellPreview
                                        diagnosticBitmap = debugImage
                                        processingErrorMsg = errorMsg
                                    }
                                )
                            }
                        }
                        isDebugCapture = false
                    }

                    val permissionLauncher = rememberLauncherForActivityResult(
                            contract = ActivityResultContracts.RequestPermission()
                            ) { isGranted ->
                        if (isGranted) {
                            val uri = StorageManager.ImageUtils.createTempImageUri(context)
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
                            val uri = StorageManager.ImageUtils.createTempImageUri(context)
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
                                    label = { Text("VLH Dashboard") },
                                    selected = currentScreen == Screen.VLH_MANAGEMENT,
                                    onClick = {
                                        currentScreen = Screen.VLH_MANAGEMENT
                                        composeScope.launch { drawerState.close() }
                                    },
                                    icon = { Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = "VLH Guidelines Matrix") }
                                )
                                NavigationDrawerItem(
                                    label = { Text("Settings (Hub)") },
                                    selected = currentScreen == Screen.SETTINGS,
                                    onClick = { currentScreen = Screen.SETTINGS; composeScope.launch { drawerState.close() } },
                                    icon = { Icon(Icons.Default.Settings, null) }
                                )
                                NavigationDrawerItem(
                                    label = { Text("About & License") },
                                    selected = currentScreen == Screen.ABOUT,
                                    onClick = { currentScreen = Screen.ABOUT; composeScope.launch { drawerState.close() } },
                                    icon = { Icon(Icons.Default.Info, contentDescription = "About App") }
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
                                    title = {
                                        val baseTitle = currentScreen.displayName

                                        // Conditionally append the current working date if on a table/summary screen
                                        val fullTitle = if ((currentScreen == Screen.TABLE_RESULTS || currentScreen == Screen.ATTENDANCE_COUNT) && !tableViewModel.currentWorkingDate.isNullOrBlank()) {
                                            "$baseTitle (${tableViewModel.currentWorkingDate})"
                                        } else {
                                            baseTitle
                                        }

                                        Text(text = fullTitle)
                                    },
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
                                    val isError = data.visuals.message.startsWith("Extraction aborted")

                                    Snackbar(
                                        snackbarData = data,
                                        containerColor = if (isError) MaterialTheme.colorScheme.errorContainer else Color(0xFF2E7D32), // Emerald Green
                                        contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else Color.White
                                    )
                                }
                            }
                        ) { paddingValues ->
                            Box(modifier = Modifier.padding(paddingValues)) {
                                when (currentScreen) {
                                    Screen.SCAN_HUB -> ScanHubScreen(
                                        onStubRequest = {
                                            // For the stub button, we can simulate the immediate transition
                                            capturedBitmap = originalBitmap
                                            cellPreviewBitmap = null
                                            diagnosticBitmap = null
                                            processingErrorMsg = null
                                            currentScreen = Screen.PROCESSING_PREVIEW

                                            executeFullExtractionFlow(
                                                bitmap = originalBitmap,
                                                setPreview = { cellPreview, debugImage, errorMsg ->
                                                    // If the stub triggers a Failure branch, capture everything
                                                    // so the ProcessingPreviewScreen swaps from loading to debug views!
                                                    cellPreviewBitmap = cellPreview
                                                    diagnosticBitmap = debugImage
                                                    processingErrorMsg = errorMsg
                                                },
                                                onSuccess = { currentScreen = Screen.TABLE_RESULTS }
                                            )
                                        },
                                        onScanRequest = onScanRequest,
                                        onDebugScanRequest = {
                                            isDebugCapture = true
                                            onScanRequest()
                                        }
                                    )
                                    Screen.PROCESSING_PREVIEW -> ProcessingPreviewScreen(
                                        rawBitmap = cellPreviewBitmap?: capturedBitmap ?: originalBitmap,
                                        diagnosticBitmap = diagnosticBitmap,
                                        errorMessage = processingErrorMsg,
                                        onRedoClicked = {
                                            // Clear state configurations and boot back to launcher hub
                                            cellPreviewBitmap = null
                                            diagnosticBitmap = null
                                            processingErrorMsg = null
                                            currentScreen = Screen.SCAN_HUB
                                            onScanRequest() // Directly re-trigger the camera app launcher!
                                        }
                                    )
                                    Screen.VLH_MANAGEMENT -> {
                                        VlhManagementScreen(
                                            backgroundScope = scope,
                                            onBackToMainHub = { currentScreen = Screen.SCAN_HUB }
                                        )
                                    }
                                    Screen.TABLE_RESULTS -> TableResultsScreen(
                                        tableViewModel
                                    )
                                    Screen.ATTENDANCE_COUNT -> AttendanceSummaryScreen()
                                    Screen.SAMPLE_DETECTION -> TableDetectionDebugScreen(capturedBitmap?: originalBitmap)
                                    Screen.SETTINGS -> SettingsScreen(tableViewModel)
                                    Screen.ABOUT -> AboutScreen()
                                }
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
    private fun executeFullExtractionFlow(bitmap: Bitmap, onSuccess: () -> Unit, setPreview: (Bitmap?, Bitmap?, String?) -> Unit) {
        scope.launch {
            val detection = withContext(Dispatchers.Default) {
                // We will collect Mats here to ensure we release them all
                var grayMat: Mat? = null
                var deskewMat: Mat? = null

                try {

                    grayMat = ImageProcessor.bitmapToGrayMat(bitmap)

                    // Note: deskewGrayMat should return a NEW Mat if it modifies it
                    deskewMat = TableDetector.deskewGrayMat(grayMat!!) ?: grayMat!!

                    val detection = TableDetector.detectTableCells(deskewMat!!, tableViewModel.activeLayout)

                    detection
                } finally {
                    grayMat?.release()
                    // Only release deskewMat if it's a different object than grayMat
                    if (deskewMat != grayMat) {
                        deskewMat?.release()
                    }
                }
            }

            val boxedMat = TableDetector.drawCells(detection.gray, detection.cells)
            val cellPreview = ImageProcessor.matToBitmap(boxedMat)
            boxedMat.release()
            when (detection) {
                is TableDetector.TableDetectionResult.Success -> {
                    // 1. Handle Successful Path
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            message = "Table detection success! Extracting text...",
                            duration = SnackbarDuration.Short
                        )
                    }

                    setPreview(cellPreview.scaleForPreview(1000), null, null)

                    scope.launch {
                        val imageBitmap = ImageProcessor.matToBitmap(detection.gray)
                        val date = try {
                            TextProcessor.determineDate(detection.cells, imageBitmap, tableViewModel.activeLayout)
                        } catch (e: TextProcessor.CouldNotDetermineDateException) {
                            tableViewModel.onDateSupplied = { manualDate ->
                                proceedWithExtraction(manualDate, detection, imageBitmap, onSuccess)
                            }
                            return@launch
                        }
                        // If no exception, just run immediately
                        proceedWithExtraction(date, detection, imageBitmap, onSuccess)
                    }
                }

                is TableDetector.TableDetectionResult.Failure -> {
                    // 2. Handle Structural Error Path
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            message = "Extraction aborted: Cannot find table header layout!",
                            duration = SnackbarDuration.Long
                        )
                    }

                    val linesBitmap = ImageProcessor.matToBitmap(detection.lines)
                    setPreview(cellPreview.scaleForPreview(1000), linesBitmap.scaleForPreview(1000), "Cannot find table header row")

                    detection.gray.release()
                    detection.thresh.release()
                    detection.mask.release()
                    detection.lines.release()
                }
            }
        }
    }

    private fun proceedWithExtraction(
        date: String?,
        detection: TableDetector.TableDetectionResult,
        imageBitmap: Bitmap,
        onFinished: () -> Unit
    ) {
        val settings = tableViewModel.activeLayout
        scope.launch(Dispatchers.IO) {
            if (date != null) {
                val rawTextGrid = withContext(Dispatchers.IO) {
                    TextProcessor.extractTextFromCells(
                        detection.cells,
                        imageBitmap,
                        listOf(settings.nameCol, settings.timeStartCol, settings.timeEndCol)
                    )
                }
                val (table, analysis, rowPaths) = withContext(Dispatchers.IO) {
                    val table = TextProcessor.refineTableData(rawTextGrid, settings)
                    val analysis = CellAnalyzer.analyzeCells(detection.thresh, detection.cells, settings)

                    // Fallback snippets cut out from the table image
                    val rowPaths = tableViewModel.storageManager.createSnippets(imageBitmap, detection.cells, date, settings)

                    Triple(table, analysis, rowPaths)
                }
                tableViewModel.loadDate(date)
                val page = "e_p1" // TODO add choosing of the page (e-employee, m-manager, p1-page1)
                // TODO sanity check - check if the number of rows match between this page and previously captured page. If no - display warning

                for (row in 1 until table.size) {
                    val id = page + "_$row"
                    val paths = rowPaths[row] ?: emptyMap()

                    val existingRow = tableViewModel.extractedRows[id]
                    Log.d("DEBUG", "ROW: $row, name: ${table[row][settings.nameCol]}, start: ${table[row][settings.timeStartCol]}, finish: ${table[row][settings.timeEndCol]}, existed:${existingRow != null}")

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
                            name = table[row][settings.nameCol],
                            startTime = table[row][settings.timeStartCol],
                            finishTime = table[row][settings.timeEndCol],
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
                launch {
                    SpreadSheetDownloader.fetchAndSaveProjection(
                        settings = tableViewModel.universalSettings,
                        viewModel = tableViewModel
                    )
                }
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
fun ScanHubScreen(onScanRequest: () -> Unit, onStubRequest: () -> Unit, onDebugScanRequest: () -> Unit) {
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

        OutlinedButton(
            onClick = onStubRequest,
            modifier = Modifier
                .fillMaxWidth()
                .height(70.dp)
        ) {
            Text("Use Stub")
        }

        Spacer(Modifier.height(24.dp))

        OutlinedButton(
            onClick = onDebugScanRequest,
            modifier = Modifier
                .fillMaxWidth()
                .height(70.dp)
        ) {
            Text("Put it into debug")
        }
    }
}

@Composable
fun ProcessingPreviewScreen(
    rawBitmap: Bitmap,
    diagnosticBitmap: Bitmap?,
    errorMessage: String?,
    onRedoClicked: () -> Unit
) {
    val isFailed = diagnosticBitmap != null

    // We wrap the main container column in a vertical scroll state.
    // This prevents layout overflows when two massive images + buttons are rendered simultaneously.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Dynamic Status Title block
        Text(
            text = if (isFailed) "Table Detection Failed" else "Analyzing Document...",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = if (isFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        )

        if (isFailed) {
            // Primary Redo Action Button
            Button(
                onClick = onRedoClicked,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("REDO / RETAKE SHEET PICTURE", style = MaterialTheme.typography.titleMedium)
            }
        }

        // 1. PRIMARY CANVAS: Displays the main photo (or photo with processed cells)
        Text(
            text = "Captured Sheet / Cell Preview",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.align(Alignment.Start)
        )
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp), // Fixed height so both fit on screen comfortably
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = rawBitmap.asImageBitmap(),
                    contentDescription = "Main Raw/Cell Preview Image Canvas",
                    modifier = Modifier.fillMaxSize()
                )

                // LOADING STATE: Show loading overlay placeholder while thread works
                if (!isFailed) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.3f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.primary,
                                strokeWidth = 4.dp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Running OpenCV Grid Tiling...",
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }

        // 2. ADDITIONAL CONDITIONAL INFRASTRUCTURE (Only renders on failure)
        if (isFailed) {
            // Separator Title for clarity
            Text(
                text = "Computed Alignment Grid (Debug)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.align(Alignment.Start)
            )

            // ADDITIONAL DIAGNOSTIC IMAGE CARD
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Image(
                        bitmap = diagnosticBitmap.asImageBitmap(),
                        contentDescription = "Diagnostic Line Grid Matrix Layer",
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // Explanatory Error Callout Card
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = errorMessage ?: "Unknown structural table parsing layout anomaly.",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp)
                )
            }
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
                            deskewMat = TableDetector.deskewGrayMat(grayMat!!) ?: grayMat!!

                            val detection = TableDetector.detectTableCells(deskewMat!!, settings)
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
                            val cellsAnalysis = CellAnalyzer.analyzeCells(detection.thresh, detection.cells, settings)

                            val marginsDrawn = detection.gray.clone()
                            if (marginsDrawn.channels() == 1) {
                                Imgproc.cvtColor(marginsDrawn, marginsDrawn, Imgproc.COLOR_GRAY2RGB)
                            }
                            val red = Scalar(255.0, 0.0, 0.0)
                            for (row in detection.cells.indices) {
                                for (col in listOf(settings.timeStartCol, settings.timeEndCol)) {
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
                            val marginsBmp = ImageProcessor.matToBitmap(marginsDrawn)
                            marginsDrawn.release()
                            val boxedBmp = ImageProcessor.matToBitmap(boxedMat!!)

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
                    maskBitmap = results.mask.scaleForPreview()
                    linesBitmap = results.lines.scaleForPreview()
                    displayedBitmap = results.boxed.scaleForPreview()

                    logText = withContext(Dispatchers.Default) {
                        // OCR (Text Extraction)
                        val rawTextGrid = TextProcessor.extractTextFromCells(
                            results.cells,
                            results.deskewedBmp,
                            listOf(settings.nameCol, settings.timeStartCol, settings.timeEndCol)
                        )
                        val textGrid = TextProcessor.refineTableData(rawTextGrid, settings)

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

@Composable
fun AboutScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. App Header/Branding
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "ShiftSync",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "v1.0.0 (Production Stable)",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // 2. Core Copyright & Attribution Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Ownership & Copyright",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "© 2026 Adrian. All Rights Reserved.",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "This application is independent software researched, written, and maintained exclusively by the author. Source code and final distributions are hosted securely via private version control architectures.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 3. The Strict Legal Restriction Card (The Shield)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.VerifiedUser,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Strict Usage Restrictions",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Licensed strictly for Non-Commercial personal use under CC BY-NC 4.0.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Commercial enterprise distribution, field deployment, or institutional use to optimize corporate operations, reduce overhead, or automate labor frameworks across external corporate facilities is STRICTLY PROHIBITED without explicit, direct written authorization from the copyright holder.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                    lineHeight = 18.sp
                )
            }
        }
    }
}

fun Bitmap.scaleForPreview(maxWidth: Int = 1000): Bitmap {
    if (this.width <= maxWidth) return this
    val aspectRatio = this.height.toFloat() / this.width.toFloat()
    val targetHeight = (maxWidth * aspectRatio).toInt()
    return Bitmap.createScaledBitmap(this, maxWidth, targetHeight, true)
}
