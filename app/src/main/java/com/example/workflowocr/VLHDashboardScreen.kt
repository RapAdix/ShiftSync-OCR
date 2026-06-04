package com.example.workflowocr

import android.graphics.Bitmap
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
    val maxRows: Int = 25,
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
    var scannedGcsList by mutableStateOf<List<Int>>(emptyList())
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
                    TextProcessor.extractIntRows(bitmap, x, y, w, h)
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
                    TextProcessor.extractIntRows(bitmap, x, y, w, h)
                }

                withContext(Dispatchers.Main) {
                    // Save the raw text numbers into memory
                    scannedGcsList = extractedDigits

                    targetingDayType = null
                    targetingColumnId = null
                    onProcessingComplete()
                }
            }
        }
    }

    /**
     * Calculates the output index mapping for a specific hour frame against our saved Master Tables
     */
    fun calculateResultIndex(hour: Int, gcValue: Int): Int? {
        val activeMasterTable = if (activeDisplayTab == DayType.WEEKDAY) weekdayTable else weekendTable

        // Find which column configuration contains this physical hour segment
        val matchedColumn = activeMasterTable.columns.find { column ->
            TimeframeValidator.containsHour(column.name, hour)
        } ?: return null // No matching timeline configuration found on disk

        // 3Scan the column's rows backwards to find the highest number smaller than/equal to GC
        // Rows are safely assumed to be sorted natively [12, 22, 45, 76...]
        val rows = matchedColumn.scannedRows
        for (i in rows.indices.reversed()) {
            if (rows[i] <= gcValue) {
                return i // Returns the exact index matching your rules
            }
        }

        return null // Returns null if the GC is smaller than even the lowest entry
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
    OPERATIONAL_REPORTS    // Crew Required screen
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
                onNavigateToSetup = { internalScreen = VlhSubScreen.SETUP_UTILITY }
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
                    coordinator.scannedGcsList = emptyList()
                }
            )
        }
    }
}

@Composable
fun VlhDashboardScreen(
    coordinator: VlhWorkflowCoordinator,
    onNavigateToCamera: () -> Unit,
    onNavigateToSetup: () -> Unit
) {
    LaunchedEffect(coordinator) {
        coordinator.collectStart()
    }

    val tabs = listOf(DayType.WEEKDAY, DayType.WEEKEND)

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
        val totalRowsCount = remember(columnsData) { columnsData.maxOfOrNull { it.maxRows } ?: 30 }

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
                    // 🟢 FIX 1: Removed the top padding from here so the container occupies full height
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(tableScrollState), // Linked to main scroll engine
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Place an invisible spacer inside the scroll view.
                        // This pushes the numbers down initially but lets them scroll up seamlessly
                        Spacer(modifier = Modifier.height(30.dp))

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
                                // Render Scanned operational strings
                                column.scannedRows.forEach { dataValue ->
                                    Box(modifier = Modifier.height(28.dp), contentAlignment = Alignment.Center) {
                                        Text(text = dataValue.toString(), style = MaterialTheme.typography.bodyLarge)
                                    }
                                }

                                // DYNAMIC PLACEHOLDERS: Calculated safely per individual column data boundaries
                                val missingSlots = column.maxRows - column.scannedRows.size
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

            // 🟢 TIME OFFSET SCROLLER: Let's users shift hours forward or backward
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
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Time Frame", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                        Text("Scanned GC", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                        Text("Crew required", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
                    }
                }

                // Dynamic Data Output Columns
                itemsIndexed(coordinator.scannedGcsList) { index, gcValue ->
                    // Calculate the shifting hour slot based on the index position offset
                    val currentHour = (coordinator.selectedStartHour + index) % 24
                    val timeString = String.format("%02d:00 - %02d:00", currentHour, (currentHour + 1) % 24)

                    // Execute our math engine lookup
                    val resultIndex = coordinator.calculateResultIndex(currentHour, gcValue)

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Left Column: Dim, less visible time marker frames
                        Text(
                            text = timeString,
                            modifier = Modifier.weight(1.2f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f) // Subdued visibility accent
                        )

                        // Middle Column (Matches header weight 0.8f)
                        Text(
                            text = gcValue.toString(),
                            modifier = Modifier.weight(0.8f),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center
                        )

                        // Right Column (Matches header weight 1.0f)
                        Text(
                            text = resultIndex?.toString() ?: "N/A",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (resultIndex != null) MaterialTheme.colorScheme.primary else Color.Red,
                            textAlign = TextAlign.End
                        )
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