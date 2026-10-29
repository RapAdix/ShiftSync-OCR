package com.example.workflowocr

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.workflowocr.licensing.DeviceSlotReplacementDialog
import com.example.workflowocr.licensing.LicenseLockoutScreen
import com.example.workflowocr.licensing.LicenseManager
import com.example.workflowocr.licensing.OrganizationSignInDialog
import com.example.workflowocr.licensing.RollbackToDemoConfirmDialog
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

val LocalLicenseManager = staticCompositionLocalOf<LicenseManager> {
    error("No LicenseManager provided! Wrap your content in CompositionLocalProvider.")
}
// Define the different "Planes" of application
enum class Screen(@StringRes val titleRes: Int) {
    SCAN_HUB(R.string.nav_screen_scan_hub),                   // The main entry point with "Scan" and "Results" buttons
    PROCESSING_PREVIEW(R.string.nav_screen_preview),          // A waiting screen with debug info shown after user makes a picture
    TABLE_RESULTS(R.string.nav_screen_table_results),         // The interactive list of extracted rows
    ATTENDANCE_COUNT(R.string.nav_screen_attendance),          // How many people work at specific times
    SAMPLE_DETECTION(R.string.nav_screen_debug),              // OpenCV debug view
    VLH_MANAGEMENT(R.string.nav_screen_vlh),                  // VLH table, GC's scanning, Crew required
    SETTINGS(R.string.nav_screen_settings),
    ABOUT(R.string.nav_screen_about)
}

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : AppCompatActivity() {

    // Global scope and shared results state
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val snackbarHostState = SnackbarHostState()

    private val tableViewModel: TableViewModel by viewModels()
    private lateinit var licenseManager: LicenseManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLanguage.initializeDefault()
        licenseManager = LicenseManager(applicationContext, scope)

        // OpenCV Initialization
        System.loadLibrary("opencv_java4")
        if (!org.opencv.android.OpenCVLoader.initDebug()) {
            Log.e("OpenCV", "Failed to load OpenCV")
        }

        val originalBitmap = BitmapFactory.decodeResource(resources, R.drawable.secret_sample_7_nodpi)

        setContent {
            CompositionLocalProvider(
                LocalTableViewModel provides tableViewModel,
                LocalLicenseManager provides licenseManager
            ) {
                ShiftSyncTheme {
                    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
                    val composeScope = rememberCoroutineScope()
                    val availableDates by tableViewModel.availableDates.collectAsState()

                    // Locale changes recreate the activity. Keep Settings open, but do not
                    // restore an in-progress scan whose coordinator was destroyed.
                    var flowState by rememberSaveable(stateSaver = MainNavigationState.languageChangeSaver) {
                        mutableStateOf(MainNavigationState())
                    }

                    LaunchedEffect(drawerState.isOpen) {
                        if (drawerState.isOpen) {
                            tableViewModel.refreshAvailableDates()
                        }
                    }

                    // Global License Dialogs (Sign-In, Device Slot #1..#N Replacement Picker, Rollback Confirmation)
                    if (licenseManager.showSignInDialog) {
                        OrganizationSignInDialog(
                            licenseManager = licenseManager,
                            onDismiss = { licenseManager.showSignInDialog = false }
                        )
                    }

                    val slotsFull = licenseManager.pendingSlotsFull
                    if (slotsFull != null) {
                        DeviceSlotReplacementDialog(
                            slotsFull = slotsFull,
                            licenseManager = licenseManager,
                            onDismiss = { licenseManager.pendingSlotsFull = null }
                        )
                    }

                    if (licenseManager.showRollbackConfirmDialog) {
                        RollbackToDemoConfirmDialog(
                            licenseManager = licenseManager,
                            onConfirmWipeAndRollback = {
                                tableViewModel.resetUserDataPreservingSettings()
                                flowState = MainNavigationState(currentScreen = Screen.SCAN_HUB)
                            },
                            onDismiss = { licenseManager.showRollbackConfirmDialog = false }
                        )
                    }

                    // Root Access Gate: if signed in with an expired (>3d) or unverified (>3d) subscription,
                    // hasDemoAccess() is false and the user cannot use any part of the app until resolved.
                    if (licenseManager.isLoaded && !licenseManager.hasDemoAccess()) {
                        LicenseLockoutScreen(licenseManager = licenseManager)
                    } else {
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
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}

data class MainNavigationState(
    val currentScreen: Screen = Screen.SCAN_HUB,
    val schedulesExpanded: Boolean = false
) {
    companion object {
        val languageChangeSaver = Saver<MainNavigationState, Boolean>(
            save = { it.currentScreen == Screen.SETTINGS },
            restore = { MainNavigationState(currentScreen = if (it) Screen.SETTINGS else Screen.SCAN_HUB) }
        )
    }
}


@Composable
fun OcrLauncherBridge(
    onNavigate: (Screen) -> Unit,
    originalBitmap: Bitmap,
    snackbarHostState: SnackbarHostState,
    content: @Composable (OcrFlowCoordinator) -> Unit
) {
    val context = LocalContext.current
    val tableViewModel = LocalTableViewModel.current
    val licenseManager = LocalLicenseManager.current
    val scope = rememberCoroutineScope()
    var tempImageUri by remember { mutableStateOf<Uri?>(null) }

    var coordinatorRef: OcrFlowCoordinator? = null

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val uriToDispose = tempImageUri
        tempImageUri = null
        try {
            if (success && uriToDispose != null) {
                val bitmap = StorageManager.ImageUtils.uriToBitmap(context, uriToDispose)
                coordinatorRef?.handleCameraResult(bitmap)
            } else {
                coordinatorRef?.onCameraCancelled()
            }
        } finally {
            StorageManager.ImageUtils.deleteTempImageUri(context, uriToDispose)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            StorageManager.ImageUtils.deleteTempImageUri(context, tempImageUri)
            val uri = StorageManager.ImageUtils.createTempImageUri(context)
            tempImageUri = uri
            cameraLauncher.launch(uri)
        } else {
            coordinatorRef?.onCameraCancelled()
            Toast.makeText(context, context.getString(R.string.camera_permission_required), Toast.LENGTH_SHORT).show()
        }
    }

    // Keep the coordinator's long-lived trigger callback pointed at the latest camera launchers.
    val currentLaunchCamera by rememberUpdatedState {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            StorageManager.ImageUtils.deleteTempImageUri(context, tempImageUri)
            val uri = StorageManager.ImageUtils.createTempImageUri(context)
            tempImageUri = uri
            cameraLauncher.launch(uri)
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // 1. Create the stable, self-contained Coordinator instance
    val coordinator = remember(context, originalBitmap, snackbarHostState, tableViewModel, licenseManager) {
        OcrFlowCoordinator(
            context = context,
            onNavigate = onNavigate,
            onTriggerCameraLaunch = { currentLaunchCamera() },
            originalBitmap = originalBitmap,
            scope = scope,
            tableViewModel = tableViewModel,
            snackbarHostState = snackbarHostState,
            licenseManager = licenseManager
        )
    }
    coordinatorRef = coordinator

    if (coordinator.isManualDateDialogVisible) {
        ManualDatePickerDialog(
            onDateSelected = { date ->
                coordinator.submitManualDate(date.format(StorageManager.storageDateFormatter()))
            },
            onDismissRequest = coordinator::dismissManualDateDialog
        )
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
    var settingsInitialTab by remember { mutableStateOf(SettingsTab.WORKFLOW) }
    // Ignore drawer navigation while a saved date is being loaded or deleted.
    fun selectDrawerScreen(screen: Screen) {
        if (pendingDate == null) {
            if (screen == Screen.SETTINGS) settingsInitialTab = SettingsTab.WORKFLOW
            coordinator.navigateTo(screen)
            composeScope.launch { drawerState.close() }
        }
    }
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                // Header Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // This button closes the drawer
                    IconButton(onClick = { composeScope.launch { drawerState.close() } }) {
                        Icon(
                            imageVector = Icons.Default.Menu,
                            contentDescription = stringResource(R.string.nav_close_menu),
                            tint = InkBlack
                        )
                    }

                    Text(
                        text = stringResource(R.string.nav_extractor_hub),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(start = 12.dp)
                    )
                }

                HorizontalDivider(color = InkBlack.copy(alpha = 0.05f))
                Spacer(modifier = Modifier.height(8.dp))

                // Navigation items
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.nav_screen_scan_hub)) },
                    selected = state.currentScreen == Screen.SCAN_HUB,
                    onClick = { selectDrawerScreen(Screen.SCAN_HUB) },
                    icon = { Icon(Icons.Default.Home, null) }
                )
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.nav_last_results)) },
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
                    label = { Text(stringResource(R.string.nav_attendance_summary)) },
                    selected = state.currentScreen == Screen.ATTENDANCE_COUNT,
                    onClick = { selectDrawerScreen(Screen.ATTENDANCE_COUNT) },
                    icon = { Icon(Icons.Filled.Calculate, null) }
                )

                // The Unfolding "Saved Schedules" Section
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.nav_saved_schedules)) },
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
                                stringResource(R.string.nav_no_saves_found),
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
                                        if (pendingDate == date) stringResource(R.string.nav_loading_date, date) else date,
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
                                            contentDescription = stringResource(R.string.common_delete),
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
                                    title = { Text(stringResource(R.string.nav_delete_date_title, date)) },
                                    text = { Text(stringResource(R.string.nav_delete_saved_date_message)) },
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
                                            Text(stringResource(R.string.common_delete))
                                        }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showConfirmForThisItem = false }) {
                                            Text(stringResource(R.string.common_cancel))
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.nav_vlh_dashboard)) },
                    selected = state.currentScreen == Screen.VLH_MANAGEMENT,
                    onClick = { selectDrawerScreen(Screen.VLH_MANAGEMENT) },
                    icon = { Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = stringResource(R.string.nav_vlh_guidelines_description)) }
                )
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.nav_settings_hub)) },
                    selected = state.currentScreen == Screen.SETTINGS,
                    onClick = { selectDrawerScreen(Screen.SETTINGS) },
                    icon = { Icon(Icons.Default.Settings, null) }
                )
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.nav_about_license)) },
                    selected = state.currentScreen == Screen.ABOUT,
                    onClick = { selectDrawerScreen(Screen.ABOUT) },
                    icon = { Icon(Icons.Default.Info, contentDescription = stringResource(R.string.nav_about_description)) }
                )
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.nav_sample_detection)) },
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
                    expandedHeight = 52.dp,
                    title = {
                        val baseTitle = stringResource(state.currentScreen.titleRes)

                        // Conditionally append the current working date if on a table/summary screen
                        val fullTitle = if ((state.currentScreen == Screen.TABLE_RESULTS || state.currentScreen == Screen.ATTENDANCE_COUNT) && !tableViewModel.currentWorkingDate.isNullOrBlank()) {
                            stringResource(R.string.nav_screen_title_with_date, baseTitle, tableViewModel.currentWorkingDate.orEmpty())
                        } else {
                            baseTitle
                        }

                        Text(text = fullTitle, modifier = Modifier.offset(y = 2.dp))
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = { composeScope.launch { drawerState.open() } },
                            modifier = Modifier.offset(y = 2.dp)
                        ) {
                            Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.nav_menu))
                        }
                    },
                    actions = {
                        // This block adds buttons to the RIGHT side of the bar
                        if (state.currentScreen == Screen.PROCESSING_PREVIEW) {
                            FilledIconButton(
                                onClick = coordinator::onCancelProcessing,
                                modifier = Modifier.offset(y = 2.dp),
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = Color.White
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(R.string.preview_cancel_processing)
                                )
                            }
                        }
                        if (state.currentScreen == Screen.TABLE_RESULTS) {
                            IconButton(
                                onClick = { coordinator.navigateTo(Screen.ATTENDANCE_COUNT) },
                                modifier = Modifier.offset(y = 2.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Calculate,
                                    contentDescription = stringResource(R.string.nav_view_attendance),
                                    tint = AccentOlive
                                )
                            }
                        }
                        if (state.currentScreen == Screen.ATTENDANCE_COUNT) {
                            IconButton(
                                onClick = { coordinator.navigateTo(Screen.TABLE_RESULTS) },
                                modifier = Modifier.offset(y = 2.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.List,
                                    contentDescription = stringResource(R.string.nav_view_table_results),
                                    tint = AccentOlive
                                )
                            }
                        }
                    }
                )
            },
            snackbarHost = {
                SnackbarHost(hostState = snackbarHostState) { data ->
                    val isError = (data.visuals as? AppSnackbarVisuals)?.isError == true

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
                            backgroundScope = scope
                        )
                    }
                    Screen.TABLE_RESULTS -> TableResultsScreen(
                        tableViewModel
                    )
                    Screen.ATTENDANCE_COUNT -> AttendanceSummaryScreen()
                    Screen.SAMPLE_DETECTION -> TableDetectionDebugScreen(
                        coordinator.capturedBitmap ?: originalBitmap
                    )
                    Screen.SETTINGS -> SettingsScreen(tableViewModel, settingsInitialTab)
                    Screen.ABOUT -> AboutScreen(
                        licenseManager = LocalLicenseManager.current,
                        onNavigateToSettings = {
                            settingsInitialTab = SettingsTab.LICENSE
                            coordinator.navigateTo(Screen.SETTINGS)
                        }
                    )
                }
            }
        }
    }
}
