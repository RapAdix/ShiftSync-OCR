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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.workflowocr.ui.theme.ShiftSyncTheme
import com.example.workflowocr.ui.theme.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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

        setContent {
            CompositionLocalProvider(LocalTableViewModel provides tableViewModel) {
                ShiftSyncTheme {
                    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
                    val composeScope = rememberCoroutineScope()
                    val availableDates by tableViewModel.availableDates.collectAsState()

                    var flowState by remember { mutableStateOf(MainNavigationState()) }

                    LaunchedEffect(drawerState.isOpen) {
                        if (drawerState.isOpen) {
                            tableViewModel.refreshAvailableDates()
                        }
                    }

                    OcrLauncherBridge(
                        onNavigate = { flowState = flowState.copy(currentScreen = it) },
                        originalBitmap = originalBitmap,
                        snackbarHostState = snackbarHostState
                    ) { coordinator ->
                        AppNavigationDrawer(
                            drawerState = drawerState,
                            state = flowState,
                            coordinator = coordinator,
                            availableDates = availableDates,
                            tableViewModel = tableViewModel,
                            composeScope = composeScope,
                            onToggleSchedules = { flowState = flowState.copy(schedulesExpanded = !flowState.schedulesExpanded) },
                            snackbarHostState = snackbarHostState,
                            originalBitmap = originalBitmap,
                            scope = scope
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}

data class MainNavigationState(
    val currentScreen: Screen = Screen.SCAN_HUB,
    val schedulesExpanded: Boolean = false
)


@Composable
fun OcrLauncherBridge(
    onNavigate: (Screen) -> Unit,
    originalBitmap: Bitmap,
    snackbarHostState: SnackbarHostState,
    content: @Composable (OcrFlowCoordinator) -> Unit
) {
    val context = LocalContext.current
    val tableViewModel = LocalTableViewModel.current
    val scope = rememberCoroutineScope()
    var tempImageUri by remember { mutableStateOf<Uri?>(null) }

    // 1. Declare the camera activation logic ahead of time
    var triggerCameraLaunch: (() -> Unit)? by remember { mutableStateOf(null) }

    // 2. Create the stable, self-contained Coordinator instance
    val coordinator = remember(originalBitmap, snackbarHostState, tableViewModel) {
        OcrFlowCoordinator(
            onNavigate = onNavigate,
            onTriggerCameraLaunch = { triggerCameraLaunch?.invoke() }, // Safely routes to the assigned hardware trigger
            originalBitmap = originalBitmap,
            scope = scope,
            tableViewModel = tableViewModel,
            snackbarHostState = snackbarHostState
        )
    }

    if (coordinator.isManualDateDialogVisible) {
        ManualDatePickerDialog(
            onDateSelected = { date ->
                coordinator.submitManualDate(date.format(StorageManager.storageDateFormatter()))
            },
            onDismissRequest = coordinator::dismissManualDateDialog
        )
    }

    // The "Launcher" that handles the result of the camera app
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && tempImageUri != null) {
            val bitmap = StorageManager.ImageUtils.uriToBitmap(context, tempImageUri!!)
            coordinator.handleCameraResult(bitmap)
        } else {
            // Cancel case fallback path
            if (coordinator.isDebugCapture) {
                // Instantly clean up flag state if debug execution was canceled
                coordinator.prepareForScan(debugMode = false)
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            val uri = StorageManager.ImageUtils.createTempImageUri(context)
            tempImageUri = uri
            cameraLauncher.launch(uri)
        } else {
            Toast.makeText(context, "Camera permission is required.", Toast.LENGTH_SHORT).show()
        }
    }

    // 3. Assign the actual hardware interaction trigger implementation block
    triggerCameraLaunch = {
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

    content(coordinator)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppNavigationDrawer(
    drawerState: DrawerState,
    state: MainNavigationState,
    coordinator: OcrFlowCoordinator,
    availableDates: List<String>,
    tableViewModel: TableViewModel,
    composeScope: CoroutineScope,
    onToggleSchedules: () -> Unit,
    snackbarHostState: SnackbarHostState,
    originalBitmap: Bitmap,
    scope: CoroutineScope
) {
    var pendingDate by remember { mutableStateOf<String?>(null) }
    // Ignore drawer navigation while a saved date is being loaded or deleted.
    fun selectDrawerScreen(screen: Screen) {
        if (pendingDate == null) {
            coordinator.navigateTo(screen)
            composeScope.launch { drawerState.close() }
        }
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
                    selected = state.currentScreen == Screen.SCAN_HUB,
                    onClick = { selectDrawerScreen(Screen.SCAN_HUB) },
                    icon = { Icon(Icons.Default.Home, null) }
                )
                NavigationDrawerItem(
                    label = { Text("Last Results") },
                    selected = state.currentScreen == Screen.TABLE_RESULTS,
                    onClick = { selectDrawerScreen(Screen.TABLE_RESULTS) },
                    icon = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.List,
                            contentDescription = null
                        )
                    }
                )
                NavigationDrawerItem(
                    label = { Text("Attendance Summary") },
                    selected = state.currentScreen == Screen.ATTENDANCE_COUNT,
                    onClick = { selectDrawerScreen(Screen.ATTENDANCE_COUNT) },
                    icon = { Icon(Icons.Filled.Calculate, null) }
                )

                // The Unfolding "Saved Schedules" Section
                NavigationDrawerItem(
                    label = { Text("Saved Schedules") },
                    selected = false, // The parent itself isn't a "screen"
                    onClick = onToggleSchedules,
                    icon = { Icon(Icons.Default.History, null) },
                    badge = {
                        Icon(
                            imageVector = if (state.schedulesExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                            contentDescription = null
                        )
                    }
                )

                // Animated Sub-Items
                AnimatedVisibility(
                    visible = state.schedulesExpanded,
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
                                label = {
                                    Text(
                                        if (pendingDate == date) "Loading $date…" else date,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                },
                                selected = isCurrent,
                                onClick = {
                                    if (pendingDate != null) return@NavigationDrawerItem
                                    if (state.currentScreen == Screen.PROCESSING_PREVIEW) {
                                        coordinator.abandonProcessing()
                                    }
                                    pendingDate = date
                                    composeScope.launch {
                                        try {
                                            tableViewModel.loadDate(date)
                                            coordinator.navigateTo(Screen.TABLE_RESULTS)
                                            drawerState.close()
                                        } finally {
                                            pendingDate = null
                                        }
                                    }
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
                                    IconButton(
                                        onClick = {
                                            if (pendingDate == null) showConfirmForThisItem = true
                                        },
                                        enabled = pendingDate == null
                                    ) {
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
                                                if (pendingDate != null) return@TextButton
                                                showConfirmForThisItem = false
                                                pendingDate = date
                                                composeScope.launch {
                                                    try {
                                                        if (tableViewModel.deleteDate(date)) {
                                                            coordinator.navigateTo(Screen.SCAN_HUB)
                                                        }
                                                    } finally {
                                                        pendingDate = null
                                                    }
                                                }
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
                    selected = state.currentScreen == Screen.VLH_MANAGEMENT,
                    onClick = { selectDrawerScreen(Screen.VLH_MANAGEMENT) },
                    icon = { Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = "VLH Guidelines Matrix") }
                )
                NavigationDrawerItem(
                    label = { Text("Settings (Hub)") },
                    selected = state.currentScreen == Screen.SETTINGS,
                    onClick = { selectDrawerScreen(Screen.SETTINGS) },
                    icon = { Icon(Icons.Default.Settings, null) }
                )
                NavigationDrawerItem(
                    label = { Text("About & License") },
                    selected = state.currentScreen == Screen.ABOUT,
                    onClick = { selectDrawerScreen(Screen.ABOUT) },
                    icon = { Icon(Icons.Default.Info, contentDescription = "About App") }
                )
                NavigationDrawerItem(
                    label = { Text("Sample Detection") },
                    selected = state.currentScreen == Screen.SAMPLE_DETECTION,
                    onClick = { selectDrawerScreen(Screen.SAMPLE_DETECTION) },
                    icon = { Icon(Icons.Default.Build, null) }
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        val baseTitle = state.currentScreen.displayName

                        // Conditionally append the current working date if on a table/summary screen
                        val fullTitle = if ((state.currentScreen == Screen.TABLE_RESULTS || state.currentScreen == Screen.ATTENDANCE_COUNT) && !tableViewModel.currentWorkingDate.isNullOrBlank()) {
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
                        if (state.currentScreen == Screen.PROCESSING_PREVIEW) {
                            FilledIconButton(
                                onClick = coordinator::onCancelProcessing,
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = Color.White
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Cancel processing"
                                )
                            }
                        }
                        if (state.currentScreen == Screen.TABLE_RESULTS) {
                            IconButton(
                                onClick = { coordinator.navigateTo(Screen.ATTENDANCE_COUNT) }
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Calculate,
                                    contentDescription = "View Attendance Summary",
                                    tint = AccentOlive
                                )
                            }
                        }
                        if (state.currentScreen == Screen.ATTENDANCE_COUNT) {
                            IconButton(
                                onClick = { coordinator.navigateTo(Screen.TABLE_RESULTS) }
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
                when (state.currentScreen) {
                    Screen.SCAN_HUB -> {
                        ScanHubScreen(
                            onStubRequest = coordinator::onStubRequest,
                            onScanRequest = coordinator::onScanRequest,
                            onDebugScanRequest = coordinator::onDebugScanRequest
                        )

                        if (coordinator.showPagePicker) {
                            ScanPagePickerDialog(
                                coordinator = coordinator,
                                enabledPages = tableViewModel.universalSettings.enabledScanPages
                            )
                        }
                    }
                    Screen.PROCESSING_PREVIEW -> ProcessingPreviewScreen(
                        rawBitmap = coordinator.cellPreviewBitmap ?: coordinator.capturedBitmap ?: originalBitmap,
                        diagnosticBitmap = coordinator.diagnosticBitmap,
                        errorMessage = coordinator.processingErrorMsg,
                        isDateDetectionFinished = coordinator.isDateDetectionFinished,
                        manualDateRequired = coordinator.onDateSupplied != null,
                        onSpecifyDateClicked = coordinator::openManualDateDialog,
                        onRedoClicked = coordinator::onRedoClicked
                    )
                    Screen.VLH_MANAGEMENT -> {
                        VlhManagementScreen(
                            backgroundScope = scope,
                            onBackToMainHub = { coordinator.navigateTo(Screen.SCAN_HUB) }
                        )
                    }
                    Screen.TABLE_RESULTS -> TableResultsScreen(
                        tableViewModel
                    )
                    Screen.ATTENDANCE_COUNT -> AttendanceSummaryScreen()
                    Screen.SAMPLE_DETECTION -> TableDetectionDebugScreen(
                        coordinator.capturedBitmap ?: originalBitmap
                    )
                    Screen.SETTINGS -> SettingsScreen(tableViewModel)
                    Screen.ABOUT -> AboutScreen()
                }
            }
        }
    }
}
