package com.example.workflowocr

import android.graphics.Bitmap
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
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedFilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

enum class DayType { WEEKDAY, WEEKEND }

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
)

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

    // Tracks if the camera capture session is processing a structural Master Table configuration,
    // or scanning an external standalone operational document block
    var isConfiguringMasterTable by mutableStateOf(true)

    // Temporary holders for the standalone operational scan document workflow
    val scannedGcsList: SnapshotStateList<Int> = mutableStateListOf()
    var selectedStartHour by mutableStateOf(viewModel.universalSettings.workplaceOpeningTime) // Default starting alignment offset

    /**
     * Prepares configuration tracking states before opening up the camera overlay target screen
     */
    fun initiateColumnUpdate(dayType: DayType, columnId: Int, openCameraAction: () -> Unit) {
        targetingDayType = dayType
        targetingColumnId = columnId
        isConfiguringMasterTable = true
        openCameraAction()
    }

    /**
     * Triggers the independent execution scanning pipeline context
     */
    fun initiateExternalTableProcessing(openCameraAction: () -> Unit) {
        isConfiguringMasterTable = false
        openCameraAction()
    }

    /**
     * Differentiate for VLH table update and for calculating people needed depending on the GC's and the VLH
     */
    fun handleCapturedImage(bitmap: Bitmap, onProcessingComplete: () -> Unit) {
        // Calculate coordinates relative to the resolution dimensions of the incoming bitmap image proxy
        val x = (bitmap.width * CameraTargetGeometry.LEFT_RATIO).toInt()
        val y = (bitmap.height * CameraTargetGeometry.TOP_RATIO).toInt()
        val w = (bitmap.width * CameraTargetGeometry.WIDTH_RATIO).toInt()
        val h = (bitmap.height * CameraTargetGeometry.HEIGHT_RATIO).toInt()

        if (isConfiguringMasterTable) {
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
        } else {
            backgroundScope.launch {
                val extractedDigits = withContext(Dispatchers.IO) {
                    TextProcessor.extractAndExtrapolateIntRows(bitmap, x, y, w, h)
                }

                withContext(Dispatchers.Main) {
                    // Save the raw text numbers into memory
                    scannedGcsList.clear()
                    scannedGcsList.addAll(extractedDigits)

                    targetingDayType = null
                    targetingColumnId = null
                    onProcessingComplete()
                }
            }
        }
    }

    /**
     * Calculates the biggest index that has GC value smaller/equal than the provided gcValue, for a specific hour frame against our saved Master Tables
     */
    fun calculateResultIndex(hour: Int, gcValue: Int): Int? {
        val activeMasterTable = if (activeDisplayTab == DayType.WEEKDAY) weekdayTable else weekendTable

        // Find which column configuration contains this physical hour segment
        val matchedColumn = activeMasterTable.columns.find { column ->
            TimeframeValidator.containsHour(column.name, hour)
        } ?: return null // No matching timeline configuration found on disk

        // Scan the column's rows backwards to find the highest number smaller than/equal to GC
        // Rows are assumed to be sorted natively [12, 22, 45, 76...]
        val rows = matchedColumn.scannedRows
        for (i in rows.indices.reversed()) {
            if (rows[i] <= gcValue) {
                return i
            }
        }

        return null // Returns null if the GC is smaller than even the lowest entry
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

    private object TimeframeValidator {
        // Matches 24-hour formats with :00 or :30 precision (e.g., "05:00 - 08:30" or "14:30 - 22:00")
        private val timeSpanRegex = Regex("""^(0[0-9]|1[0-9]|2[0-3]):(00|30)\s*-\s*(0[0-9]|1[0-9]|2[0-3]):(00|30)$""")

        fun isValidTimeframe(input: String): Boolean {
            return timeSpanRegex.matches(input.trim())
        }

        /**
         * Optional: Parses a string like "05:00 - 08:30" into a manageable range of absolute integers
         * representing hours to easily check which hour falls into which column config loop.
         */
        fun containsHour(timeframe: String, hour: Int): Boolean {
            if (!isValidTimeframe(timeframe)) return false
            return try {
                val parts = timeframe.split("-").map { it.trim() }
                val startHour = parts[0].split(":")[0].toInt()
                val endHour = parts[1].split(":")[0].toInt()

                // Handle standard wrapping or linear ranges
                if (startHour <= endHour) {
                    hour in startHour until endHour
                } else {
                    // If it crosses midnight (e.g., 22:00 - 04:00)
                    hour >= startHour || hour < endHour
                }
            } catch (e: Exception) {
                false
            }
        }
    }
}

private enum class VlhSubScreen {
    COLUMN_CAMERA_SCANNER, // Custom camera screen with a highlighted rectangle for fitting one table column
    DASHBOARD,             // Screen with VLH's tables and a button to read projected GCs
    SETUP_UTILITY,
    OPERATIONAL_REPORTS,   // Crew Required screen
    COLUMN_EDITOR          // Edit VLH column manually
}

@Composable
fun VlhManagementScreen(
    backgroundScope: CoroutineScope,
    onBackToMainHub: () -> Unit
) {
    val viewModel = LocalTableViewModel.current
    val coordinator = remember { VlhWorkflowCoordinator(viewModel, backgroundScope) }

    var internalScreen by remember { mutableStateOf(VlhSubScreen.DASHBOARD) }

    // Keep safe lifecycle listener active here
    LaunchedEffect(coordinator) {
        coordinator.collectStart()
    }

    // Local Routing Switch Matrix
    when (internalScreen) {
        VlhSubScreen.DASHBOARD -> {
            VlhDashboardScreen(
                coordinator = coordinator,
                onNavigateToCamera = { internalScreen = VlhSubScreen.COLUMN_CAMERA_SCANNER },
                onNavigateToSetup = { internalScreen = VlhSubScreen.SETUP_UTILITY },
                onColumnClicked = { dayType, columnId ->
                    coordinator.editingDayType = dayType
                    coordinator.editingColumnId = columnId
                    internalScreen = VlhSubScreen.COLUMN_EDITOR
                }
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
                        // Execution callback: Redirect UI paths based on active processing engine selection
                        if (coordinator.isConfiguringMasterTable) {
                            internalScreen = VlhSubScreen.DASHBOARD
                        } else {
                            internalScreen = VlhSubScreen.OPERATIONAL_REPORTS
                        }
                    }
                },
                onBackClicked = { internalScreen = VlhSubScreen.DASHBOARD }
            )
        }
        VlhSubScreen.OPERATIONAL_REPORTS -> {
            OperationalScanResultsView(
                coordinator = coordinator,
                onDismiss = {
                    internalScreen = VlhSubScreen.DASHBOARD
                    coordinator.scannedGcsList.clear()
                }
            )
        }
        VlhSubScreen.COLUMN_EDITOR -> {
            VlhColumnEditorScreen(
                coordinator = coordinator,
                onDismiss = {
                    coordinator.editingColumnId = null
                    coordinator.editingDayType = null
                    internalScreen = VlhSubScreen.DASHBOARD
                }
            )
        }
    }
}

@Composable
fun VlhDashboardScreen(
    coordinator: VlhWorkflowCoordinator,
    onNavigateToCamera: () -> Unit,
    onNavigateToSetup: () -> Unit,
    onColumnClicked: (DayType, Int) -> Unit
) {
    LaunchedEffect(coordinator) {
        coordinator.collectStart()
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
                    text = { Text(dayType.name, fontWeight = FontWeight.Bold) }
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
                text = coordinator.activeDisplayTab.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold
            )

            // The Setup "Gear" Button
            IconButton(onClick = onNavigateToSetup) {
                Icon(Icons.Default.Settings, contentDescription = "Configure Layout Definitions", tint = MaterialTheme.colorScheme.primary)
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
        // Process outside sheets using the active structural template metrics
        Button(
            onClick = { coordinator.initiateExternalTableProcessing(onNavigateToCamera) },
            modifier = Modifier.fillMaxWidth().padding(16.dp).height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
        ) {
            Icon(Icons.Default.CameraAlt, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("SCAN TABLE VIA ACTIVE ${coordinator.activeDisplayTab.name} TEMPLATE")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VlhSetupConfigScreen(
    coordinator: VlhWorkflowCoordinator,
    onLaunchCamera: () -> Unit,
    onBack: () -> Unit
) {
    var selectedDay by remember { mutableStateOf(coordinator.activeDisplayTab) }
    var selectedColId by remember { mutableStateOf(0) }

    val targetTable = if (selectedDay == DayType.WEEKDAY) coordinator.weekdayTable else coordinator.weekendTable
    val activeCol = targetTable.columns[selectedColId]

    // Generate pre-baked 30-minute intervals: ["00:00", "00:30", "01:00" ... "24:00"]
    val timeOptions = remember {
        List(49) { index ->
            val hour = index / 2
            val minute = if (index % 2 == 0) "00" else "30"
            String.format("%02d:%s", hour, minute)
        }
    }

    // Parse the existing string context safely to populate the initial dropdown selection states
    var startTime by remember(selectedDay, selectedColId) {
        mutableStateOf(activeCol.name.split("-").firstOrNull()?.trim() ?: "05:00")
    }
    var endTime by remember(selectedDay, selectedColId) {
        mutableStateOf(activeCol.name.split("-").lastOrNull()?.trim() ?: "08:00")
    }

    // Dropdown expansion UI states
    var startExpanded by remember { mutableStateOf(false) }
    var endExpanded by remember { mutableStateOf(false) }

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
        Text(text = "Modify Template Configurations", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        // Configuration Options Block
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = "1. Target Base Definition Group", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DayType.entries.forEach { type ->
                        FilterChip(
                            selected = selectedDay == type,
                            onClick = { selectedDay = type },
                            label = { Text(type.name) }
                        )
                    }
                }

                Text(text = "2. Target Matrix Column Allocation", fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    List(6) { it }.forEach { idx ->
                        ElevatedFilterChip(
                            selected = selectedColId == idx,
                            onClick = { selectedColId = idx },
                            label = { Text("Col ${idx + 1}") }
                        )
                    }
                }

                Text(text = "3. Adjust Structural Time Bounds (30-min precision)", fontWeight = FontWeight.Bold)

                // DROPDOWN FIELDS ROW
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Start Time Selection Dropdown Box
                    ExposedDropdownMenuBox(
                        expanded = startExpanded,
                        onExpandedChange = { startExpanded = !startExpanded },
                        modifier = Modifier.weight(1f)
                    ) {
                        OutlinedTextField(
                            readOnly = true,
                            value = startTime,
                            onValueChange = {},
                            label = { Text("Start") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = startExpanded) },
                            modifier = Modifier.menuAnchor()
                        )
                        ExposedDropdownMenu(
                            expanded = startExpanded,
                            onDismissRequest = { startExpanded = false }
                        ) {
                            timeOptions.forEach { selection ->
                                DropdownMenuItem(
                                    text = { Text(selection) },
                                    onClick = {
                                        startTime = selection
                                        startExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    // End Time Selection Dropdown Box
                    ExposedDropdownMenuBox(
                        expanded = endExpanded,
                        onExpandedChange = { endExpanded = !endExpanded },
                        modifier = Modifier.weight(1f)
                    ) {
                        OutlinedTextField(
                            readOnly = true,
                            value = endTime,
                            onValueChange = {},
                            label = { Text("End") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = endExpanded) },
                            isError = !isValidRange, // Visual cue if range is inverted
                            modifier = Modifier.menuAnchor()
                        )
                        ExposedDropdownMenu(
                            expanded = endExpanded,
                            onDismissRequest = { endExpanded = false }
                        ) {
                            timeOptions.forEach { selection ->
                                DropdownMenuItem(
                                    text = { Text(selection) },
                                    onClick = {
                                        endTime = selection
                                        endExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                if (!isValidRange) {
                    Text(
                        text = "End time must be later than start time.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                Text("Cancel")
            }
            Button(
                onClick = {
                    val combinedTimeSpan = "$startTime- $endTime"
                    activeCol.name = combinedTimeSpan

                    coordinator.initiateColumnUpdate(selectedDay, selectedColId, onLaunchCamera)
                },
                enabled = isValidRange, // 🟢 Button locks if validation parameters fail
                modifier = Modifier.weight(1f)
            ) {
                Text("Launch Scanner")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OperationalScanResultsView(
    coordinator: VlhWorkflowCoordinator,
    onDismiss: () -> Unit
) {
    val openingTime = LocalTableViewModel.current.universalSettings.workplaceOpeningTime

    Card(
        modifier = Modifier.fillMaxSize().padding(16.dp, 0.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(8.dp, 0.dp)) {
            Text("Operational Document Results", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "Use the buttons below to align the starting time if the first row doesn't match $openingTime:00.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )

            // TIME OFFSET SCROLLER: Let's users shift hours forward or backward
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { coordinator.selectedStartHour = (coordinator.selectedStartHour - 1).coerceAtLeast(0) }) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Shift Time Down")
                }
                Text(
                    text = "Starting Hour: ${coordinator.selectedStartHour}:00",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyLarge
                )
                IconButton(onClick = { coordinator.selectedStartHour = (coordinator.selectedStartHour + 1).coerceAtMost(23) }) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Shift Time Up")
                }
            }

            HorizontalDivider()

            // THE THREE-COLUMN DATA GRID MATRIX
            LazyColumn(modifier = Modifier.weight(1f)) {
                // Header Titles
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = "Time Frame",
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
                            Text("Scanned GC", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                            IconButton(
                                onClick = { coordinator.scannedGcsList.add(0, 0) },
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "Insert at Front",
                                    tint = Color(0xFF2E7D32),
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        }

                        Text(
                            text = "Crew required",
                            modifier = Modifier.weight(1.0f),
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.End
                        )
                    }
                }

                // Dynamic Data Output Columns
                itemsIndexed(coordinator.scannedGcsList) { index, gcValue ->
                    // Calculate the shifting hour slot based on the index position offset
                    val currentHour = (coordinator.selectedStartHour + index) % 24
                    val timeString = String.format("%02d:00 - %02d:00", currentHour, (currentHour + 1) % 24)
                    val crewNeeded = coordinator.calculateResultIndex(currentHour, gcValue)?.let { it + 1 }

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
                                        coordinator.scannedGcsList[index] = cleanedNumber
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
                                    ) { coordinator.scannedGcsList.add(index + 1, 0) },
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
                                        contentDescription = "Insert Below",
                                        tint = Color(0xFF2E7D32),
                                        modifier = Modifier.size(22.dp).offset(x = 3.dp)
                                    )
                                }
                            }

                            IconButton(
                                onClick = { coordinator.scannedGcsList.removeAt(index) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Delete Row",
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
                                text = crewNeeded?.toString() ?: "N/A",
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
                TextButton(onClick = onDismiss) { Text("Discard") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VlhColumnEditorScreen(
    coordinator: VlhWorkflowCoordinator,
    onDismiss: () -> Unit
) {
    val column = coordinator.getActiveEditingColumn() ?: return

    val localRowsList = remember<SnapshotStateList<Int>>(column.scannedRows) {
        mutableStateListOf<Int>().apply { addAll(column.scannedRows) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit Column ${column.id + 1} (${column.name})", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Discard changes")
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
                    Text("Insert at Row 1 (Front)", fontSize = 13.sp)
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
                                            contentDescription = "Insert Row Below",
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
                                        contentDescription = "Delete Row",
                                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Save confirmation panel scaled to match the layout grid width rules
            Button(
                onClick = {
                    coordinator.updateFullColumnData(localRowsList.toList())
                    onDismiss()
                },
                modifier = Modifier
                    .fillMaxWidth(0.6f) // Locks bottom button size to the 60% frame context
                    .height(48.dp)
                    .padding(bottom = 8.dp)
            ) {
                Text("Confirm & Save", fontWeight = FontWeight.Bold)
            }
        }
    }
}