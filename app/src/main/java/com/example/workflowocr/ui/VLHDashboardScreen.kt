package com.example.workflowocr

import java.util.Locale

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedFilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.workflowocr.ui.theme.SoftEmeraldGreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class DayType(@androidx.annotation.StringRes val displayNameRes: Int) {
    WEEKDAY(R.string.vlh_daytype_weekday),
    WEEKEND(R.string.vlh_daytype_weekend)
}

@Serializable
data class VlhColumnConfig(
    val id: Int,                 // 0 to 5 (The 6 columns)
    var name: String = "",        // e.g., "05:00 - 10:30"
    val scannedRows: List<Int> = emptyList()
)

@Serializable
data class VlhTableState(
    val type: DayType,
    val columns: List<VlhColumnConfig> = List(6) { id ->
        VlhColumnConfig(
            id = id,
            name = when(id) {
                0 -> "05:00-10:30"
                1 -> "10:30-14:00"
                2 -> "14:00-17:00"
                3 -> "17:00-20:00"
                4 -> "20:00-24:00"
                5 -> "00:00-05:00"
                else -> "Column ${id + 1}"
            }
        )
    }
) {
    companion object TimeframeValidator {
        // Matches 24-hour formats with :00 or :30 precision (e.g., "05:00 - 08:30" or "14:30 - 22:00")
        private val timeSpanRegex =
            Regex("""^(0[0-9]|1[0-9]|2[0-3]):(00|30)\s*-\s*((?:0[0-9]|1[0-9]|2[0-3]):(?:00|30)|24:00)$""")

        fun isValidTimeframe(input: String): Boolean {
            return timeSpanRegex.matches(input.trim())
        }

        /**
         * Parses a string like "05:00 - 08:30" into a manageable range of absolute integers
         * representing hours to easily check which hour falls into which column config loop.
         */
        fun containsHour(timeframe: String, hour: Int): Boolean {
            return isValidTimeframe(timeframe) && try {
                val parts = timeframe.split("-").map { it.trim() }
                val startHour = parts[0].split(":")[0].toInt()
                val endHour = parts[1].split(":")[0].toInt()
                val endHourMinutes = parts[1].split(":")[1].toInt()

                // Handle standard wrapping or linear ranges
                if (startHour <= endHour) {
                    hour in startHour until endHour ||
                            (hour == endHour && endHourMinutes > 0) // Safe because for hour==0 & endHour==24 the minutes need to be 0
                } else {
                    // If it crosses midnight (e.g., 22:00 - 04:00)
                    hour >= startHour || hour < endHour || (hour == endHour && endHourMinutes > 0)
                }
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * Calculates the biggest index that has GC value smaller/equal than the provided gcValue, for a specific hour frame against our saved Master Tables
     */
    fun calculateResultIndex(hour: Int, gcValue: Int): Int? {
        // Find which column configuration contains this physical hour segment
        val matchedColumns = columns.filter { column ->
            containsHour(column.name, hour)
        }
        if (matchedColumns.isEmpty()) return null // No matching timeline configuration found on disk

        val possibleIndices = matchedColumns.mapNotNull { (_, _, scannedRows) ->
            // Scan the column's rows backwards to find the highest number smaller than/equal to GC
            // Rows are assumed to be sorted natively [12, 22, 45, 76...]
            for (i in scannedRows.indices.reversed()) {
                if (scannedRows[i] <= gcValue) {
                    return@mapNotNull i
                }
            }
            return@mapNotNull null  // Returns null if the GC is smaller than even the lowest entry
        }
        return possibleIndices.maxOrNull()
    }

    /**
     * Checks all physical hours between [openingHour] and [closingHour].
     * Returns true if EVERY hour in that range hits a column configuration that actually contains rows.
     * Returns false if any hour lands in an unconfigured slot or an empty master data column.
     */
    fun hasDataForTimeRange(openingHour: Int, closingHour: Int): Boolean {
        // Generate the list of hours to check based on whether the shift crosses midnight
        val hoursToCheck = mutableListOf<Int>()
        if (openingHour < closingHour) {
            // Normal shift during the same day (e.g., 08:00 to 16:00)
            for (h in openingHour until closingHour) {
                hoursToCheck.add(h)
            }
        } else {
            // Night shift crossing midnight (e.g., 22:00 to 05:00)
            for (h in openingHour until 24) {
                hoursToCheck.add(h)
            }
            for (h in 0 until closingHour) {
                hoursToCheck.add(h)
            }
        }

        // Verify every hour lands in a column that is populated
        return hoursToCheck.all { hour ->
            val matchedColumns = columns.filter { column ->
                containsHour(column.name, hour)
            }
            // The hour is covered only if a matching column exists AND its data row list is not empty
            matchedColumns.isNotEmpty() && matchedColumns.any { it.scannedRows.isNotEmpty() }
        }
    }
}

class VlhWorkflowCoordinator(
    private val viewModel: TableViewModel,
    private val backgroundScope: CoroutineScope
) {
    // Core Persistent Storage State Structures
    var weekdayTable by mutableStateOf(VlhTableState(DayType.WEEKDAY))
    var weekendTable by mutableStateOf(VlhTableState(DayType.WEEKEND))

    // UI View State Tracking
    var activeDisplayTab by mutableStateOf(DayType.WEEKDAY)

    /**
     * Managed by Screen's LaunchedEffect coroutine scope context.
     * Auto-cancels background flow listening cycles instantly when screen leaves view.
     */
    suspend fun collectStart() {
        viewModel.storageManager.vlhTablesFlow.collect { (weekdayData, weekendData) ->
            weekdayTable = weekdayData
            weekendTable = weekendData
        }
    }

    var targetingDayType by mutableStateOf<DayType?>(null)
    var targetingColumnId by mutableStateOf<Int?>(null)

    var lastCapturedBitmap by mutableStateOf<Bitmap?>(null)

    /**
     * Prepares configuration tracking states before opening up the camera overlay target screen
     */
    fun initiateColumnUpdate(dayType: DayType, columnId: Int, openCameraAction: () -> Unit) {
        targetingDayType = dayType
        targetingColumnId = columnId
        openCameraAction()
    }

    /**
     * Processes single-column cropping and OCR row extraction to update the targeted VLH column.
     */
    fun handleCapturedImage(bitmap: Bitmap, onProcessingComplete: () -> Unit) {
        // Calculate coordinates relative to the resolution dimensions of the incoming bitmap image proxy
        val x = (bitmap.width * CameraTargetGeometry.LEFT_RATIO).toInt()
        val y = (bitmap.height * CameraTargetGeometry.TOP_RATIO).toInt()
        val w = (bitmap.width * CameraTargetGeometry.WIDTH_RATIO).toInt()
        val h = (bitmap.height * CameraTargetGeometry.HEIGHT_RATIO).toInt()

        val targetX = x.coerceIn(0, bitmap.width - 1)
        val targetY = y.coerceIn(0, bitmap.height - 1)
        val targetW = w.coerceIn(1, bitmap.width - targetX)
        val targetH = h.coerceIn(1, bitmap.height - targetY)

        val croppedColumn = Bitmap.createBitmap(bitmap, targetX, targetY, targetW, targetH)
        lastCapturedBitmap = croppedColumn

        val day = targetingDayType ?: return
        val colId = targetingColumnId ?: return

        // Use the long-lived background scope so this block survives screen swaps safely
        backgroundScope.launch {
            val integerRowsList = withContext(Dispatchers.IO) {
                TextProcessor.extractAndExtrapolateIntRows(bitmap, x, y, w, h)
            }

            val currentTable = if (day == DayType.WEEKDAY) weekdayTable else weekendTable

            // Unidirectional updates: Update column configurations inside our immutable list data model
            val updatedColumns = currentTable.columns.map { column ->
                if (column.id == colId) column.copy(scannedRows = integerRowsList) else column
            }
            val updatedTable = currentTable.copy(columns = updatedColumns)

            viewModel.storageManager.saveVlhTable(updatedTable)

            // Reset tracking context safely on the main thread after processing yields results
            withContext(Dispatchers.Main) {
                targetingDayType = null
                targetingColumnId = null
                onProcessingComplete()
            }
        }
    }

    // Context state for the column editing view overlay
    var editingDayType by mutableStateOf<DayType?>(null)
    var editingColumnId by mutableStateOf<Int?>(null)

    /**
     * Returns the column configuration currently being updated in the editor screen
     */
    fun getActiveEditingColumn(): VlhColumnConfig? {
        val day = editingDayType ?: return null
        val colId = editingColumnId ?: return null
        val table = if (day == DayType.WEEKDAY) weekdayTable else weekendTable
        return table.columns.getOrNull(colId)
    }

    /**
     * Overwrites the target column data structure entirely with a locally modified list,
     * flushing the elements out onto disk storage synchronously.
     */
    fun updateFullColumnData(updatedList: List<Int>) {
        val day = editingDayType ?: return
        val colId = editingColumnId ?: return
        val currentTable = if (day == DayType.WEEKDAY) weekdayTable else weekendTable

        val updatedColumns = currentTable.columns.map { column ->
            if (column.id == colId) {
                column.copy(scannedRows = updatedList.toMutableList())
            } else column
        }

        backgroundScope.launch {
            viewModel.storageManager.saveVlhTable(currentTable.copy(columns = updatedColumns))
        }
    }

    /**
     * Updates the name (timeframe string, e.g. "14:00- 17:00") of the active editing column
     * and persists the updated table state to storage.
     */
    fun updateColumnName(newName: String) {
        val day = editingDayType ?: return
        val colId = editingColumnId ?: return
        val currentTable = if (day == DayType.WEEKDAY) weekdayTable else weekendTable

        val updatedColumns = currentTable.columns.map { column ->
            if (column.id == colId) {
                column.copy(name = newName)
            } else column
        }

        val updatedTable = currentTable.copy(columns = updatedColumns)
        if (day == DayType.WEEKDAY) {
            weekdayTable = updatedTable
        } else {
            weekendTable = updatedTable
        }

        backgroundScope.launch {
            viewModel.storageManager.saveVlhTable(updatedTable)
        }
    }

    // Full VLH Table Scanning State
    var showScanVlhDialog by mutableStateOf(false)
    var scanTargetDayType by mutableStateOf(DayType.WEEKDAY)
    var scanErrorMessage by mutableStateOf<String?>(null)

    fun processFullVlhScan(
        bitmap: Bitmap,
        context: Context,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val dayType = scanTargetDayType
        lastCapturedBitmap = bitmap

        backgroundScope.launch {
            try {
                val recognizer = com.google.mlkit.vision.text.TextRecognition.getClient(
                    com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS
                )
                val visionText = suspendCancellableCoroutine { continuation ->
                    recognizer.process(com.google.mlkit.vision.common.InputImage.fromBitmap(bitmap, 0))
                        .addOnSuccessListener { continuation.resume(it) }
                        .addOnFailureListener { continuation.resumeWithException(it) }
                }
                recognizer.close()

                val extraction = com.example.workflowocr.processing.VlhTableExtractor.extract(visionText)
                val table = extraction.rows

                // Use detected GC-column positions; the table may contain index columns between data columns.
                val newColumns = extraction.gcColumnIndices.mapIndexed { colIdx, gcTableCol ->
                    val scannedRows = (0 until table.size).mapNotNull { rowIdx ->
                        table[rowIdx].getOrNull(gcTableCol)?.trim()?.toIntOrNull()
                    }

                    val defaultName = if (extraction.gcColumnIndices.size == 6) {
                        when (colIdx) {
                            0 -> "05:00-10:30"
                            1 -> "10:30-14:00"
                            2 -> "14:00-17:00"
                            3 -> "17:00-20:00"
                            4 -> "20:00-24:00"
                            5 -> "00:00-05:00"
                            else -> "Column ${colIdx + 1}"
                        }
                    } else {
                        "Column ${colIdx + 1}"
                    }

                    VlhColumnConfig(
                        id = colIdx,
                        name = defaultName,
                        scannedRows = scannedRows
                    )
                }

                if (newColumns.all { it.scannedRows.isEmpty() }) {
                    throw IllegalStateException("No numeric values could be extracted from the detected GC columns. Please check image quality and table headings.")
                }

                val updatedTable = VlhTableState(type = dayType, columns = newColumns)
                viewModel.storageManager.saveVlhTable(updatedTable)

                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.vlh_scan_success_toast),
                        Toast.LENGTH_LONG
                    ).show()
                    scanErrorMessage = null
                    onSuccess()
                }

            } catch (e: Throwable) {
                android.util.Log.e("VLH_SCAN", "Full VLH Scan extraction failed", e)
                val errMsg = e.message ?: "Unknown extraction error"
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.vlh_scan_failed_toast, errMsg),
                        Toast.LENGTH_LONG
                    ).show()
                    scanErrorMessage = errMsg
                    onError(errMsg)
                }
            }
        }
    }
}

private enum class VlhSubScreen {
    COLUMN_CAMERA_SCANNER, // Custom camera screen with a highlighted rectangle for fitting one table column
    DASHBOARD,             // Screen with VLH's tables and a button to read projected GCs
    SETUP_UTILITY,
    COLUMN_EDITOR,         // Edit VLH column manually
    IMAGE_DISPLAY,         // Show recently captured image for debug purposes
    PROJECTION_HISTORY     // Display all stored projections and let user edit them
}

@Composable
fun VlhManagementScreen(
    backgroundScope: CoroutineScope
) {
    val viewModel = LocalTableViewModel.current
    val coordinator = remember { VlhWorkflowCoordinator(viewModel, backgroundScope) }
    val context = LocalContext.current

    var internalScreen by remember { mutableStateOf(VlhSubScreen.DASHBOARD) }

    // System camera launcher for full VLH table scan (reusing the normal system camera pattern from OcrFlowCoordinator)
    var tempImageUri by remember { mutableStateOf<Uri?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && tempImageUri != null) {
            val bitmap = StorageManager.ImageUtils.uriToBitmap(context, tempImageUri!!)
            coordinator.processFullVlhScan(
                bitmap = bitmap,
                context = context,
                onSuccess = {
                    internalScreen = VlhSubScreen.DASHBOARD
                },
                onError = { _ ->
                    coordinator.showScanVlhDialog = true
                    internalScreen = VlhSubScreen.DASHBOARD
                }
            )
        }
        tempImageUri = null
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            val uri = StorageManager.ImageUtils.createTempImageUri(context)
            tempImageUri = uri
            cameraLauncher.launch(uri)
        } else {
            Toast.makeText(context, context.getString(R.string.camera_permission_required), Toast.LENGTH_SHORT).show()
        }
    }

    fun launchSystemCameraForVlh() {
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

    // Keep safe lifecycle listener active here
    LaunchedEffect(coordinator) {
        coordinator.collectStart()
    }

    // Local Routing Switch Matrix
    when (internalScreen) {
        VlhSubScreen.DASHBOARD -> {
            VlhDashboardScreen(
                coordinator = coordinator,
                onNavigateToFullVlhCamera = { launchSystemCameraForVlh() },
                onNavigateToSetup = { internalScreen = VlhSubScreen.SETUP_UTILITY },
                onColumnClicked = { dayType, columnId ->
                    coordinator.editingDayType = dayType
                    coordinator.editingColumnId = columnId
                    internalScreen = VlhSubScreen.COLUMN_EDITOR
                },
                onNavigateToImageInspection = { internalScreen = VlhSubScreen.IMAGE_DISPLAY },
                onNavigateToProjectionHistory = { internalScreen = VlhSubScreen.PROJECTION_HISTORY }
            )
        }
        VlhSubScreen.SETUP_UTILITY -> VlhSetupConfigScreen(
            coordinator = coordinator,
            onLaunchCamera = { internalScreen = VlhSubScreen.COLUMN_CAMERA_SCANNER },
            onBack = { internalScreen = VlhSubScreen.DASHBOARD }
        )
        VlhSubScreen.COLUMN_CAMERA_SCANNER -> {
            CameraScreen(
                onImageCaptured = { bitmap ->
                    coordinator.handleCapturedImage(bitmap) {
                        internalScreen = VlhSubScreen.DASHBOARD
                    }
                },
                onBackClicked = { internalScreen = VlhSubScreen.DASHBOARD }
            )
        }
        VlhSubScreen.COLUMN_EDITOR -> {
            VlhColumnEditorScreen(
                coordinator = coordinator,
                onDismiss = {
                    coordinator.editingColumnId = null
                    coordinator.editingDayType = null
                    internalScreen = VlhSubScreen.DASHBOARD
                },
                onLaunchCamera = {
                    internalScreen = VlhSubScreen.COLUMN_CAMERA_SCANNER
                }
            )
        }
        VlhSubScreen.PROJECTION_HISTORY -> {
            ProjectionHistoryReviewScreen(coordinator, onBackDismiss = {internalScreen = VlhSubScreen.DASHBOARD})
        }

        VlhSubScreen.IMAGE_DISPLAY -> {
            VlhImageInspectionScreen(
                coordinator = coordinator,
                onBackToDashboard = { internalScreen = VlhSubScreen.DASHBOARD }
            )
        }
    }
}

@Composable
fun VlhDashboardScreen(
    coordinator: VlhWorkflowCoordinator,
    onNavigateToFullVlhCamera: () -> Unit,
    onNavigateToSetup: () -> Unit,
    onColumnClicked: (DayType, Int) -> Unit,
    onNavigateToImageInspection: () -> Unit,
    onNavigateToProjectionHistory: () -> Unit
) {
    LaunchedEffect(coordinator) {
        coordinator.collectStart()
    }

    if (coordinator.showScanVlhDialog) {
        ScanVlhSetupDialog(
            initialDayType = coordinator.scanTargetDayType,
            errorMessage = coordinator.scanErrorMessage,
            onDismiss = {
                coordinator.showScanVlhDialog = false
                coordinator.scanErrorMessage = null
            },
            onConfirmScan = { dayType ->
                coordinator.scanTargetDayType = dayType
                coordinator.showScanVlhDialog = false
                onNavigateToFullVlhCamera()
            }
        )
    }

    val tabs = listOf(DayType.WEEKDAY, DayType.WEEKEND)
    val minRows = 20

    Column(modifier = Modifier.fillMaxSize()) {
        // 1. Tabbed Header row matching active selection matrices
        TabRow(selectedTabIndex = tabs.indexOf(coordinator.activeDisplayTab)) {
            tabs.forEach { dayType ->
                Tab(
                    selected = coordinator.activeDisplayTab == dayType,
                    onClick = { coordinator.activeDisplayTab = dayType },
                    text = { Text(stringResource(dayType.displayNameRes), fontWeight = FontWeight.Bold) }
                )
            }
        }

        // 2. Action Utility Strip
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(coordinator.activeDisplayTab.displayNameRes),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                // History / Document Search Button
                IconButton(onClick = onNavigateToProjectionHistory) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.TrendingUp,
                        contentDescription = stringResource(R.string.vlh_review_projection_history),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                // The Image Quality Verification Eye Button (Only visible if an image actually exists)
                if (coordinator.lastCapturedBitmap != null) {
                    IconButton(onClick = onNavigateToImageInspection) {
                        Icon(
                            imageVector = Icons.Default.Visibility,
                            contentDescription = stringResource(R.string.vlh_review_last_scan),
                            tint = MaterialTheme.colorScheme.secondary
                        )
                    }
                }

                // The Setup "Gear" Button
                IconButton(onClick = onNavigateToSetup) {
                    Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.vlh_configure_layout), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }

        // 3. THE MIDDLE MATRIX ENGINE (Protected with weight so button stays visible)
        val activeTable = if (coordinator.activeDisplayTab == DayType.WEEKDAY) coordinator.weekdayTable else coordinator.weekendTable
        val columnsData = remember(activeTable) { activeTable.columns }

        // Dynamic Row Finder: Looks across all 6 columns to find the maximum configured size defined in data models
        val totalRowsCount = remember(columnsData) {
            (columnsData.maxOfOrNull { it.scannedRows.size } ?: 0).coerceAtLeast(minRows)
        }

        val indexColumnWidth = 24.dp
        val tableScrollState = rememberScrollState()

        Box(
            modifier = Modifier
                .weight(1f) // Keeps the spreadsheet contained so the bottom button isn't clipped
                .fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
            ) {
                // COLUMN A: THE INDEPENDENT INDEX COLUMN (Left Side)
                Column(
                    modifier = Modifier
                        .width(indexColumnWidth)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(tableScrollState), // Linked to main scroll engine
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.9.dp)
                    ) {
                        // Place an invisible spacer inside the scroll view.
                        // This pushes the numbers down initially but lets them scroll up seamlessly
                        Spacer(modifier = Modifier.height(40.dp))

                        repeat(totalRowsCount) { index ->
                            Box(
                                modifier = Modifier
                                    .height(28.dp) // Height matches data cells exactly
                                    .fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "${index + 1}.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Gray
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.width(4.dp))

                // COLUMNS B-G: THE 6 EQUAL-WIDTH DATA COLUMNS
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(tableScrollState), // Shared scroll engine makes them slide together perfectly
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    columnsData.forEach { column ->
                        Column(
                            modifier = Modifier
                                .weight(1f) // Forces every single column to occupy identical widths
                                .border(1.dp, Color.LightGray.copy(alpha = 0.5f))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null // Quiet, un-indented click interactions
                                ) {
                                    onColumnClicked(coordinator.activeDisplayTab, column.id)
                                }
                                .padding(4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Sticky Header Span Title
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.Center
                            ) {
                                // Safely break up the stored string ("10:30 - 14:00" or fallback defaults)
                                val timeParts = remember(column.name) { column.name.split("-") }
                                val startTimeStr = timeParts.getOrNull(0)?.trim() ?: ""
                                val endTimeStr = timeParts.getOrNull(1)?.trim() ?: ""

                                // Line 1: Start time aligned to the left side of the column container
                                Text(
                                    text = if (startTimeStr.isNotEmpty()) "$startTimeStr-" else column.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Black,
                                    maxLines = 1,
                                    modifier = Modifier.align(Alignment.Start)
                                )

                                // Line 2: End time cleanly staggered and pushed over to the right side
                                if (endTimeStr.isNotEmpty()) {
                                    Text(
                                        text = endTimeStr,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Black,
                                        maxLines = 1,
                                        modifier = Modifier
                                            .align(Alignment.End)
                                    )
                                } else {
                                    // Fallback row balancing layout if the name isn't a split range
                                    Spacer(modifier = Modifier.height(12.dp))
                                }
                            }

                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                            // Render Data Rows + Placeholders
                            Column(
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                column.scannedRows.forEachIndexed { rowIndex, dataValue ->
                                    // Check if the previous item in this column is larger
                                    val isValueDropping = rowIndex > 0 && column.scannedRows[rowIndex - 1] > dataValue

                                    val cellBackgroundColor = if (isValueDropping) {
                                        Color(0xFFFFF3E0) // Richer, warm pastel orange (Material Orange 50)
                                    } else {
                                        Color.Transparent
                                    }

                                    val cellBorderColor = if (isValueDropping) {
                                        Color(0xFFFFB74D) // Sharp, vibrant amber border outline (Material Orange 300)
                                    } else {
                                        Color.Transparent
                                    }

                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(28.dp)
                                            .background(color = cellBackgroundColor, shape = RoundedCornerShape(4.dp))
                                            .border(
                                                width = if (isValueDropping) 1.5.dp else 0.dp,
                                                color = cellBorderColor,
                                                shape = RoundedCornerShape(4.dp)
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(text = dataValue.toString(), style = MaterialTheme.typography.bodyLarge)
                                    }
                                }

                                // DYNAMIC PLACEHOLDERS: Calculated safely per individual column data boundaries
                                val missingSlots = minRows - column.scannedRows.size
                                if (missingSlots > 0) {
                                    repeat(missingSlots) {
                                        Box(modifier = Modifier.height(28.dp), contentAlignment = Alignment.Center) {
                                            Text(text = "-", color = Color.LightGray.copy(alpha = 0.7f))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 4. THE BIG BUTTON
        Button(
            onClick = {
                coordinator.scanTargetDayType = coordinator.activeDisplayTab
                coordinator.scanErrorMessage = null
                coordinator.showScanVlhDialog = true
            },
            modifier = Modifier.fillMaxWidth().padding(16.dp).height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
        ) {
            Icon(Icons.Default.DocumentScanner, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.vlh_scan_button))
        }
    }
}

/**
 * Reusable dropdown selector for 30-minute interval time picking.
 * Features auto-scrolling to the currently selected time option upon expansion.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VlhTimePickerDropdown(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    options: List<String>,
    modifier: Modifier = Modifier,
    isError: Boolean = false
) {
    var expanded by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    val itemHeightPx = remember(density) { with(density) { 48.dp.roundToPx() } }

    LaunchedEffect(expanded) {
        if (expanded) {
            val selectedIdx = options.indexOf(value).coerceAtLeast(0)
            scrollState.scrollTo(selectedIdx * itemHeightPx)
        }
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier
    ) {
        OutlinedTextField(
            readOnly = true,
            value = value,
            onValueChange = {},
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            isError = isError,
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true)
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            scrollState = scrollState
        ) {
            options.forEach { selection ->
                DropdownMenuItem(
                    text = { Text(selection) },
                    onClick = {
                        onValueChange(selection)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun VlhSetupConfigScreen(
    coordinator: VlhWorkflowCoordinator,
    onLaunchCamera: () -> Unit,
    onBack: () -> Unit
) {
    var selectedDay by remember { mutableStateOf(coordinator.activeDisplayTab) }
    var selectedColId by remember { mutableIntStateOf(0) }

    val targetTable = if (selectedDay == DayType.WEEKDAY) coordinator.weekdayTable else coordinator.weekendTable
    val activeCol = targetTable.columns[selectedColId]

    // Generate pre-baked 30-minute intervals: ["00:00", "00:30", "01:00" ... "24:00"]
    val timeOptions = remember {
        List(49) { index ->
            val hour = index / 2
            val minute = if (index % 2 == 0) "00" else "30"
            String.format(Locale.ROOT, "%02d:%s", hour, minute)
        }
    }

    // Parse the existing string context safely to populate the initial dropdown selection states
    var startTime by remember(selectedDay, selectedColId) {
        mutableStateOf(activeCol.name.split("-").firstOrNull()?.trim() ?: "05:00")
    }
    var endTime by remember(selectedDay, selectedColId) {
        mutableStateOf(activeCol.name.split("-").lastOrNull()?.trim() ?: "08:00")
    }

    // Dynamic validation condition: Ensure the end time happens after the start time
    val isValidRange = remember(startTime, endTime) {
        val startIdx = timeOptions.indexOf(startTime)
        val endIdx = timeOptions.indexOf(endTime)
        startIdx < endIdx // Chronological check
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(text = stringResource(R.string.vlh_modify_template_configuration), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        // Configuration Options Block
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = stringResource(R.string.vlh_target_definition_group), fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DayType.entries.forEach { type ->
                        FilterChip(
                            selected = selectedDay == type,
                            onClick = { selectedDay = type },
                            label = { Text(stringResource(type.displayNameRes)) }
                        )
                    }
                }

                Text(text = stringResource(R.string.vlh_target_column_allocation), fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    List(6) { it }.forEach { idx ->
                        ElevatedFilterChip(
                            selected = selectedColId == idx,
                            onClick = { selectedColId = idx },
                            label = { Text(stringResource(R.string.vlh_column_short, idx + 1)) }
                        )
                    }
                }

                Text(text = stringResource(R.string.vlh_adjust_time_bounds), fontWeight = FontWeight.Bold)

                // DROPDOWN FIELDS ROW
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    VlhTimePickerDropdown(
                        value = startTime,
                        onValueChange = { startTime = it },
                        label = stringResource(R.string.common_start),
                        options = timeOptions,
                        modifier = Modifier.weight(1f)
                    )

                    VlhTimePickerDropdown(
                        value = endTime,
                        onValueChange = { endTime = it },
                        label = stringResource(R.string.common_end),
                        options = timeOptions,
                        isError = !isValidRange,
                        modifier = Modifier.weight(1f)
                    )
                }

                if (!isValidRange) {
                    Text(
                        text = stringResource(R.string.vlh_invalid_time_range),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.common_cancel))
            }
            Button(
                onClick = {
                    val combinedTimeSpan = "$startTime- $endTime"
                    activeCol.name = combinedTimeSpan

                    coordinator.initiateColumnUpdate(selectedDay, selectedColId, onLaunchCamera)
                },
                enabled = isValidRange, // Button locks if validation parameters fail
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.vlh_launch_scanner))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectionHistoryReviewScreen(
    coordinator: VlhWorkflowCoordinator,
    onBackDismiss: () -> Unit
) {
    val viewModel = LocalTableViewModel.current
    val coroutineScope = rememberCoroutineScope()

    // Sync pipeline variables
    var isSyncing by remember { mutableStateOf(false) }

    // 1. Prefetch DayProjectionData records from storage on initialization
    // wrapped in a key counter state so we can trigger a soft recomposition UI refresh on download success
    var cacheRefreshTrigger by remember { mutableIntStateOf(0) }
    val historicProjectionsCache = remember(cacheRefreshTrigger) {
        viewModel.storageManager.prefetchAllAvailableProjections()
    }

    // 2. Extract keys directly. Since the storage pipeline sorts them, insertion order is preserved.
    val availableDates = remember(historicProjectionsCache) {
        historicProjectionsCache.keys.toList()
    }

    // Active selection tracking indexes
    var selectedDateIndex by remember { mutableIntStateOf(0) }
    val activeDateKey = availableDates.getOrNull(selectedDateIndex)

    // Track dynamic DayType (isWeekend) modifications using a mutable snapshot state map
    val isWeekendOverrides = remember(historicProjectionsCache) {
        mutableStateMapOf<String, Boolean>().apply {
            putAll(historicProjectionsCache.mapValues { (_, data) -> data.isWeekend })
        }
    }

    // Build the isolated mutable snapshot state array whenever the focused date context pivots
    val editableGcsList = remember(activeDateKey, cacheRefreshTrigger) {
        val openingHour = viewModel.universalSettings.workplaceOpeningTime
        val closingHour = viewModel.universalSettings.workplaceClosingTime

        val totalHours = if (closingHour >= openingHour) {
            closingHour - openingHour
        } else {
            (24 - openingHour) + closingHour
        }

        val initialList = mutableListOf<Int>()
        val activeProjection = historicProjectionsCache[activeDateKey]
        val activeMap = activeProjection?.hourlyGcs ?: emptyMap()

        for (step in 0 until totalHours) {
            val currentHour = (openingHour + step) % 24
            initialList.add(activeMap[currentHour] ?: 0)
        }
        initialList.toMutableStateList()
    }

    var dropdownExpanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {

        // TOP CONTROL BAR NAVIGATION
        Surface(
            modifier = Modifier.fillMaxWidth(),
            tonalElevation = 4.dp,
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Left Step Navigation Button (Older dates)
                IconButton(
                    onClick = { selectedDateIndex = (selectedDateIndex + 1).coerceAtMost(availableDates.lastIndex) },
                    enabled = selectedDateIndex < availableDates.lastIndex
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.vlh_older_date))
                }

                // Middle Container grouping Selector Dropdown and the Day Toggle
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (activeDateKey != null) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            // The Toggle sits directly on top
                            val currentDayOverride = isWeekendOverrides[activeDateKey] ?: false
                            DayTypeSlidingToggle(
                                isWeekend = currentDayOverride,
                                onDayTypeChange = { isWeekend ->
                                    isWeekendOverrides[activeDateKey] = isWeekend
                                }
                            )

                            // Dropdown sits underneath it
                            ExposedDropdownMenuBox(
                                expanded = dropdownExpanded,
                                onExpandedChange = { dropdownExpanded = !dropdownExpanded }
                            ) {
                                // BasicTextField allows bypassing standard Material form constraints
                                BasicTextField(
                                    value = activeDateKey,
                                    onValueChange = {},
                                    readOnly = true,
                                    textStyle = TextStyle(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp,
                                        textAlign = TextAlign.Center,
                                        color = MaterialTheme.colorScheme.onSurface
                                    ),
                                    modifier = Modifier
                                        .width(150.dp)
                                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true),
                                    // The DecorationBox lets us manually reconstruct the Outlined look with custom tight paddings
                                    decorationBox = { innerTextField ->
                                        OutlinedTextFieldDefaults.DecorationBox(
                                            value = activeDateKey,
                                            innerTextField = innerTextField,
                                            enabled = true,
                                            singleLine = true,
                                            visualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
                                            interactionSource = remember { MutableInteractionSource() },
                                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded) },
                                            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                                            contentPadding = PaddingValues(
                                                start = 4.dp,
                                                end = 3.dp,
                                                top = 0.dp,
                                                bottom = 0.dp
                                            ),
                                            container = {
                                                OutlinedTextFieldDefaults.ContainerBox(
                                                    enabled = true,
                                                    isError = false,
                                                    interactionSource = remember { MutableInteractionSource() },
                                                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                                                    shape = OutlinedTextFieldDefaults.shape
                                                )
                                            }
                                        )
                                    }
                                )

                                ExposedDropdownMenu(
                                    expanded = dropdownExpanded,
                                    onDismissRequest = { dropdownExpanded = false }
                                ) {
                                    availableDates.forEachIndexed { index, dateStr ->
                                        DropdownMenuItem(
                                            text = { Text(text = dateStr, fontWeight = FontWeight.SemiBold) },
                                            onClick = {
                                                selectedDateIndex = index
                                                dropdownExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        Text(text = stringResource(R.string.vlh_no_document_projections), style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
                    }
                }

                IconButton(
                    onClick = {
                        downloadAllProjections(
                            settings = viewModel.universalSettings,
                            viewModel = viewModel,
                            scope = coroutineScope,
                            onSyncStateChange = { loading ->
                                isSyncing = loading
                                // When finished, trigger a soft UI reload to populate all newly discovered day files
                                if (!loading) cacheRefreshTrigger++
                            },
                            onSyncError = {}
                        )
                    },
                    enabled = !isSyncing,
                    modifier = Modifier.size(40.dp)
                ) {
                    if (isSyncing) {
                        CircularProgressIndicator(modifier = Modifier.size(34.dp), strokeWidth = 4.dp)
                    } else {
                        Icon(
                            imageVector = Icons.Default.CloudDownload,
                            contentDescription = stringResource(R.string.vlh_sync_future_projections),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(34.dp)
                        )
                    }
                }

                // Right Step Navigation Button (Newer dates)
                IconButton(
                    onClick = { selectedDateIndex = (selectedDateIndex - 1).coerceAtLeast(0) },
                    enabled = selectedDateIndex > 0
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = stringResource(R.string.vlh_newer_date))
                }
            }
        }

        HorizontalDivider()

        val isWeekend = isWeekendOverrides[activeDateKey] ?: false
        if (activeDateKey != null) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                OperationalScanResultsView(
                    scannedGcsList = editableGcsList,
                    selectedStartHour = viewModel.universalSettings.workplaceOpeningTime,
                    onStartHourChange = null, // Drops arrow scrollers per conditional rule
                    activeVlhState = if (isWeekend) coordinator.weekendTable else coordinator.weekdayTable,
                    onDismiss = onBackDismiss,
                    onSave = {
                        val updatedMap = mutableMapOf<Int, Int?>()
                        val openingHour = viewModel.universalSettings.workplaceOpeningTime
                        val closingHour = viewModel.universalSettings.workplaceClosingTime

                        val totalHours = if (closingHour >= openingHour) {
                            closingHour - openingHour
                        } else {
                            (24 - openingHour) + closingHour
                        }

                        editableGcsList.take(totalHours).forEachIndexed { index, gcValue ->
                            val targetHour = (openingHour + index) % 24
                            updatedMap[targetHour] = gcValue
                        }

                        val finalDayTypeState = isWeekendOverrides[activeDateKey] ?: false

                        val finalProjection = DayProjectionData(
                            isWeekend = finalDayTypeState,
                            hourlyGcs = updatedMap
                        )
                        viewModel.storageManager.saveProjectionToDisk(finalProjection, activeDateKey)

                        onBackDismiss()
                    }
                )
            }
        } else {
            Box(modifier = Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.vlh_no_projection_stored),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray
                )
            }
        }
    }
}

private fun downloadAllProjections(
    settings: UniversalSettings,
    viewModel: TableViewModel,
    scope: CoroutineScope,
    onSyncStateChange: (Boolean) -> Unit,
    onSyncError: (ProjectionResult.Failure?) -> Unit
) {
    scope.launch {
        onSyncStateChange(true)
        onSyncError(null)

        val result = SpreadSheetDownloader.downloadAndSaveAllProjections(settings, viewModel)

        if (result is ProjectionResult.Failure) {
            onSyncError(result)
        }

        onSyncStateChange(false)
    }
}

@Composable
fun OperationalScanResultsView(
    scannedGcsList: SnapshotStateList<Int>,
    selectedStartHour: Int,
    onStartHourChange: ((Int) -> Unit)? = null,
    activeVlhState: VlhTableState,
    onDismiss: () -> Unit,
    onSave: (() -> Unit)? = null
) {
    val openingTime = LocalTableViewModel.current.universalSettings.workplaceOpeningTime

    Card(
        modifier = Modifier.fillMaxSize().padding(16.dp, 0.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(8.dp, 0.dp)) {
            // Hides Starting Time control mechanics completely if shifting capabilities are omitted
            if (onStartHourChange != null) {
                Text(
                    text = stringResource(R.string.vlh_align_starting_time, openingTime),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                // TIME OFFSET SCROLLER: Lets users shift hours forward or backward
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { onStartHourChange((selectedStartHour - 1).coerceAtLeast(0)) }) {
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            contentDescription = stringResource(R.string.vlh_shift_time_down)
                        )
                    }
                    Text(
                        text = stringResource(R.string.vlh_starting_hour, selectedStartHour),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    IconButton(onClick = { onStartHourChange((selectedStartHour + 1).coerceAtMost(23)) }) {
                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.vlh_shift_time_up))
                    }
                }

                HorizontalDivider()
            }

            // THE THREE-COLUMN DATA GRID MATRIX
            LazyColumn(modifier = Modifier.weight(1f)) {
                // Header Titles
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = stringResource(R.string.vlh_time_frame),
                            modifier = Modifier.width(96.dp), // Fixed explicit width forces single-row layout
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )

                        // Split layout into a Column so the button sits on a new line underneath the label
                        Column(
                            modifier = Modifier.weight(1.6f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(stringResource(R.string.vlh_scanned_gc), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                            IconButton(
                                onClick = { if (scannedGcsList.size < 24) scannedGcsList.add(0, 0) },
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = stringResource(R.string.vlh_insert_at_front),
                                    tint = Color(0xFF2E7D32),
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        }

                        Text(
                            text = stringResource(R.string.vlh_crew_required),
                            modifier = Modifier.weight(1.0f),
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.End
                        )
                    }
                }

                // Dynamic Data Output Columns
                itemsIndexed(scannedGcsList) { index, gcValue ->
                    // Calculate the shifting hour slot based on the index position offset
                    val currentHour = (selectedStartHour + index) % 24
                    val timeString = String.format(Locale.ROOT, "%02d:00 - %02d:00", currentHour, (currentHour + 1) % 24)

                    val crewNeeded = activeVlhState.calculateResultIndex(currentHour, gcValue)?.let { it + 1 }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(38.dp), // Fixed structural height matching the layout grid
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Left Column: Subdued Time Frame
                        Text(
                            text = timeString,
                            modifier = Modifier.width(96.dp), // Matches fixed width layout matrix rules perfectly
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )

                        // Middle Column: In-Place Editable Input Field Box + Combined Actions
                        Row(
                            modifier = Modifier.weight(1.6f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.End
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(52.dp) // Narrowed down: comfortably fits a 3-digit entry
                                    .fillMaxHeight()
                                    .padding(vertical = 2.dp, horizontal = 4.dp)
                                    .border(
                                        width = 1.dp,
                                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                        shape = RoundedCornerShape(4.dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                BasicTextField(
                                    value = if (gcValue == 0) "" else gcValue.toString(),
                                    onValueChange = { inputString ->
                                        val cleanedNumber = inputString.filter { it.isDigit() }.toIntOrNull() ?: 0
                                        scannedGcsList[index] = cleanedNumber
                                    },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    textStyle = TextStyle(
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Center,
                                        color = MaterialTheme.colorScheme.onSurface
                                    ),
                                    singleLine = true,
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    modifier = Modifier.fillMaxWidth(),
                                    decorationBox = { innerTextField ->
                                        if (gcValue == 0) {
                                            Text(
                                                text = "0",
                                                style = TextStyle(
                                                    fontSize = 16.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    textAlign = TextAlign.Center,
                                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                                                ),
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                        innerTextField()
                                    }
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) { if (scannedGcsList.size < 24) scannedGcsList.add(index + 1, 0) },
                                contentAlignment = Alignment.Center
                            ) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.Reply,
                                        contentDescription = null,
                                        tint = Color(0xFF2E7D32),
                                        modifier = Modifier
                                            .size(12.dp)
                                            .rotate(270f)
                                            .offset(x = (-5).dp, y = (-1).dp)
                                    )
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = stringResource(R.string.vlh_insert_below),
                                        tint = Color(0xFF2E7D32),
                                        modifier = Modifier.size(22.dp).offset(x = 3.dp)
                                    )
                                }
                            }

                            IconButton(
                                onClick = { scannedGcsList.removeAt(index) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = stringResource(R.string.vlh_delete_row),
                                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        // Right Column: Crew Output
                        Row(
                            modifier = Modifier.weight(1.0f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.End
                        ) {
                            Text(
                                text = crewNeeded?.toString() ?: stringResource(R.string.common_not_available),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                color = if (crewNeeded != null) MaterialTheme.colorScheme.primary else Color.Red,
                                textAlign = TextAlign.End
                            )
                        }
                    }
                }
            }

            // Bottom Action Controls
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_discard)) }
                if (onSave != null) {
                    TextButton(
                        onClick = onSave,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = SoftEmeraldGreen
                        )
                    ) {
                        Text(stringResource(R.string.common_save), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VlhColumnEditorScreen(
    coordinator: VlhWorkflowCoordinator,
    onDismiss: () -> Unit,
    onLaunchCamera: () -> Unit
) {
    val column = coordinator.getActiveEditingColumn() ?: return

    val localRowsList = remember<SnapshotStateList<Int>>(column.scannedRows) {
        mutableStateListOf<Int>().apply { addAll(column.scannedRows) }
    }

    var showTimePickerDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clickable { showTimePickerDialog = true }
                            .padding(vertical = 4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.vlh_edit_column_title, column.id + 1, column.name),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = stringResource(R.string.vlh_adjust_time_bounds),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.vlh_discard_changes))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally // Keep the overall page structure centered
        ) {

            // Group the content in a container limited to 60% width
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {

                OutlinedButton(
                    onClick = { localRowsList.add(0, 0) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.vlh_insert_first_row), fontSize = 13.sp)
                }

                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    itemsIndexed(localRowsList) { index, value ->
                        // Check if previous row has a bigger value than the current row
                        val isValueDropping = index > 0 && localRowsList[index - 1] > value

                        val boxBackgroundColor = if (isValueDropping) {
                            Color(0xFFFFF9C4) // Soft Pastel Yellow (Material Yellow 100)
                        } else {
                            Color.Transparent
                        }

                        val boxBorderColor = if (isValueDropping) {
                            Color(0xFFFBC02D).copy(alpha = 0.6f) // Darker Amber border accent
                        } else {
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(38.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Left index tracking number label
                            Text(
                                text = "${index + 1}.",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.Gray,
                                modifier = Modifier.width(32.dp)
                            )

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .padding(vertical = 2.dp)
                                    .background(color = boxBackgroundColor, shape = RoundedCornerShape(4.dp))
                                    .border(
                                        width = 1.dp,
                                        color = boxBorderColor,
                                        shape = RoundedCornerShape(4.dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                BasicTextField(
                                    value = if (value == 0) "" else value.toString(),
                                    onValueChange = { inputString ->
                                        val cleanedNumber = inputString.filter { it.isDigit() }.toIntOrNull() ?: 0
                                        localRowsList[index] = cleanedNumber
                                    },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    textStyle = TextStyle(
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Center,
                                        color = MaterialTheme.colorScheme.onSurface
                                    ),
                                    singleLine = true,
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    modifier = Modifier.fillMaxWidth(),
                                    decorationBox = { innerTextField ->
                                        if (value == 0) {
                                            Text(
                                                text = "0",
                                                style = TextStyle(
                                                    fontSize = 16.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    textAlign = TextAlign.Center,
                                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                                                ),
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                        innerTextField()
                                    }
                                )
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            // Action Elements Matrix
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp) // Slightly wider box container to prevent clipping during offsets
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null
                                        ) { localRowsList.add(index + 1, 0) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        // The Curved Arrow (Placed first so it can sit slightly under/behind the plus if overlapping)
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.Reply,
                                            contentDescription = null,
                                            tint = Color(0xFF2E7D32),
                                            modifier = Modifier
                                                .size(14.dp)
                                                .rotate(270f) // Points downward
                                                .offset(
                                                    x = (-6).dp,  // Pulls the arrow downward so its beginning sits near the plus's vertical midpoint
                                                    y = (-1).dp   // Pushes the arrow horizontally farther left from the plus sign
                                                )
                                        )
                                        // The plus icon
                                        Icon(
                                            imageVector = Icons.Default.Add,
                                            contentDescription = stringResource(R.string.vlh_insert_row_below),
                                            tint = Color(0xFF2E7D32),
                                            modifier = Modifier
                                                .size(26.dp) // Large prominent touch target
                                                .offset(x = 4.dp) // Balances out the layout within the parent 40.dp container
                                        )
                                    }
                                }

                                // Delete Trash Row Button
                                IconButton(
                                    onClick = { localRowsList.removeAt(index) },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = stringResource(R.string.vlh_delete_row),
                                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Save confirmation panel centered at 60% width, with camera scan button right-aligned with a visible gap
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            ) {
                Button(
                    onClick = {
                        coordinator.updateFullColumnData(localRowsList.toList())
                        onDismiss()
                    },
                    modifier = Modifier
                        .fillMaxWidth(0.6f)
                        .height(48.dp)
                        .align(Alignment.Center)
                ) {
                    Text(
                        stringResource(R.string.vlh_confirm_and_save),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelLarge
                    )
                }

                Button(
                    onClick = {
                        val day = coordinator.editingDayType
                        val colId = coordinator.editingColumnId
                        if (day != null && colId != null) {
                            coordinator.initiateColumnUpdate(day, colId, onLaunchCamera)
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 0.dp)
                        .height(48.dp)
                        .width(48.dp),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = stringResource(R.string.vlh_launch_scanner),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        if (showTimePickerDialog) {
            val timeOptions = remember {
                List(49) { index ->
                    val hour = index / 2
                    val minute = if (index % 2 == 0) "00" else "30"
                    String.format(Locale.ROOT, "%02d:%s", hour, minute)
                }
            }

            var startTime by remember(column.name) {
                mutableStateOf(column.name.split("-").firstOrNull()?.trim() ?: "05:00")
            }
            var endTime by remember(column.name) {
                mutableStateOf(column.name.split("-").lastOrNull()?.trim() ?: "08:00")
            }

            val isValidRange = remember(startTime, endTime) {
                val startIdx = timeOptions.indexOf(startTime)
                val endIdx = timeOptions.indexOf(endTime)
                startIdx >= 0 && endIdx >= 0 && startIdx < endIdx
            }

            AlertDialog(
                onDismissRequest = { showTimePickerDialog = false },
                title = {
                    Text(
                        text = stringResource(R.string.vlh_adjust_time_bounds),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            VlhTimePickerDropdown(
                                value = startTime,
                                onValueChange = { startTime = it },
                                label = stringResource(R.string.common_start),
                                options = timeOptions,
                                modifier = Modifier.weight(1f)
                            )

                            VlhTimePickerDropdown(
                                value = endTime,
                                onValueChange = { endTime = it },
                                label = stringResource(R.string.common_end),
                                options = timeOptions,
                                isError = !isValidRange,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        if (!isValidRange) {
                            Text(
                                text = stringResource(R.string.vlh_invalid_time_range),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val newTimeSpan = "$startTime- $endTime"
                            coordinator.updateColumnName(newTimeSpan)
                            showTimePickerDialog = false
                        },
                        enabled = isValidRange
                    ) {
                        Text(stringResource(R.string.common_save))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showTimePickerDialog = false }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }
            )
        }
    }
}

// -----------------
// ----- DEBUG -----
// -----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VlhImageInspectionScreen(
    coordinator: VlhWorkflowCoordinator,
    onBackToDashboard: () -> Unit
) {
    var debugExtractedResults by remember { mutableStateOf<Pair<List<Int>, List<Int>>?>(null) }
    val scrollState = rememberScrollState()

    // Automatically trigger the debug process when the screen first loads
    LaunchedEffect(Unit) {
        val bitmap = coordinator.lastCapturedBitmap
        if (bitmap != null) {
            val integerRowsListPairs = withContext(Dispatchers.IO) {
                val optimizedBitmap = ImageProcessor.reduceMoireNoise(bitmap, true)
                Pair (TextProcessor.extractAndExtrapolateIntRows(optimizedBitmap, 0, 0, optimizedBitmap.width, optimizedBitmap.height),
                TextProcessor.extractAndExtrapolateIntRows(bitmap, 0, 0, bitmap.width, bitmap.height))
            }
            debugExtractedResults = integerRowsListPairs
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.vlh_verify_document_scan), style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBackToDashboard) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.vlh_return_to_dashboard))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color.Black) // Dark canvas helps see text contrast issues clearly
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val bitmapSnapshot = coordinator.lastCapturedBitmap

            if (bitmapSnapshot != null) {
                Image(
                    bitmap = bitmapSnapshot.asImageBitmap(),
                    contentDescription = stringResource(R.string.vlh_raw_scan_description),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 500.dp),
                    contentScale = ContentScale.Fit
                )
            } else {
                Text(
                    text = stringResource(R.string.vlh_no_scan_snapshot),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge
                )
            }

            HorizontalDivider(color = Color.DarkGray, thickness = 1.dp, modifier = Modifier.padding(vertical = 16.dp))

            // DEBUG VALUES DATA BOARD SECTION
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.debug_extraction_title),
                    color = Color.Green,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(12.dp))

                val results = debugExtractedResults
                if (results == null) {
                    // Running state indicator spinner trace frame
                    CircularProgressIndicator(
                        color = Color.Green,
                        modifier = Modifier.size(24.dp)
                    )
                } else if (results.first.isEmpty() && results.second.isEmpty()) {
                    Text(
                        text = stringResource(R.string.debug_no_numeric_patterns),
                        color = Color.LightGray,
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    // Unpack both lists from the Pair data model
                    val optimizedList = results.first
                    val originalList = results.second

                    // Grid Sub-Header Tracker
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.debug_row_index), color = Color.Gray, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(0.8f))
                        Text(stringResource(R.string.debug_optimized_opencv), color = Color.Cyan, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1.1f), textAlign = TextAlign.Center)
                        Text(stringResource(R.string.debug_original_raw), color = Color.Magenta, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1.1f), textAlign = TextAlign.End)
                    }

                    // Loop up to the maximum total length to catch missing entries safely
                    val maxRowsCount = maxOf(optimizedList.size, originalList.size)

                    repeat(maxRowsCount) { index ->
                        val optimizedVal = optimizedList.getOrNull(index)?.toString() ?: "-"
                        val originalVal = originalList.getOrNull(index)?.toString() ?: "-"

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            // Left Column: Row Index
                            Text(
                                text = stringResource(R.string.debug_row_label, index + 1),
                                color = Color.Green,
                                style = MaterialTheme.typography.bodyLarge,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(0.8f)
                            )

                            // Middle Column: OpenCV Extraction results
                            Text(
                                text = optimizedVal,
                                color = if (optimizedVal == "-") Color.Red else Color.Cyan,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.weight(1.1f)
                            )

                            // Right Column: Original Image results
                            Text(
                                text = originalVal,
                                color = if (originalVal == "-") Color.Red else Color.Magenta,
                                style = MaterialTheme.typography.bodyLarge,
                                fontFamily = FontFamily.Monospace,
                                textAlign = TextAlign.End,
                                modifier = Modifier.weight(1.1f)
                            )
                        }
                    }
                }

                // Extra breathing buffer at the absolute bottom of the scroll container
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
fun ScanVlhSetupDialog(
    initialDayType: DayType,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onConfirmScan: (dayType: DayType) -> Unit
) {
    var selectedDayType by remember { mutableStateOf(initialDayType) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.vlh_dialog_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Error Message Banner (if retrying after failed extraction)
                if (!errorMessage.isNullOrBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = errorMessage,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                // 1. Day Type Selection
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.vlh_dialog_day_type_label),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        val options = listOf(DayType.WEEKDAY, DayType.WEEKEND)
                        options.forEachIndexed { index, dayType ->
                            SegmentedButton(
                                selected = selectedDayType == dayType,
                                onClick = { selectedDayType = dayType },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size)
                            ) {
                                Text(stringResource(dayType.displayNameRes))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirmScan(selectedDayType)
                }
            ) {
                Text(stringResource(R.string.vlh_dialog_scan_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.vlh_dialog_cancel_action))
            }
        }
    )
}
