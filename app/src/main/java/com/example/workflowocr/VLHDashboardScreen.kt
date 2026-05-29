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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedFilterChip
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

enum class DayType { WEEKDAY, WEEKEND }

data class VlhColumnConfig(
    val id: Int,                 // 0 to 5 (The 6 columns)
    var name: String = "",        // e.g., "05:00 - 10:30"
    val maxRows: Int = 25,
    val scannedRows: MutableList<String> = mutableStateListOf()
)

class VlhTableState(val type: DayType) {
    // Initialize the 6 core columns dynamically
    val columns = List(6) { id ->
        VlhColumnConfig(
            id = id,
            name = when(id) {
                0 -> "05:00 - 08:00"
                1 -> "08:00 - 12:00"
                else -> "Column ${id + 1}"
            }
        )
    }
}

class VlhWorkflowCoordinator {
    // 1. Core Persistent Storage State Structures
    val weekdayTable = VlhTableState(DayType.WEEKDAY)
    val weekendTable = VlhTableState(DayType.WEEKEND)

    // 2. UI View State Tracking
    var activeDisplayTab by mutableStateOf(DayType.WEEKDAY)

    // 3. Target State Configuration context (Used when clicking the Gear workflow setup)
    var targetingDayType by mutableStateOf<DayType?>(null)
    var targetingColumnId by mutableStateOf<Int?>(null)

    // Tracks if the camera capture session is processing a structural Master Table configuration,
    // or scanning an external standalone operational document block!
    var isConfiguringMasterTable by mutableStateOf(true)

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
     * Unified processor fed straight out of your Custom CameraX Viewfinder interface thread
     */
    fun handleCapturedImage(bitmap: Bitmap, onProcessingComplete: () -> Unit) {
        if (isConfiguringMasterTable) {
            val day = targetingDayType ?: return
            val colId = targetingColumnId ?: return
            val targetTable = if (day == DayType.WEEKDAY) weekdayTable else weekendTable

            // Execute OpenCV Single Column Line OCR Matrix logic on the bitmap sequence
            // val parsedDigitsList = DigitExtractor.extractColumnData(bitmap)
            val parsedDigitsList = listOf("12", "45", "7", "22") // Mocked engine extraction return

            targetTable.columns[colId].scannedRows.clear()
            targetTable.columns[colId].scannedRows.addAll(parsedDigitsList)
        } else {
            // BIG BUTTON WORKFLOW: Run deep parsing matrix routines using the active configuration maps
            val activeTemplate = if (activeDisplayTab == DayType.WEEKDAY) weekdayTable else weekendTable

            // Execute advanced structural calculations using activeTemplate timeframes and data matrix structures...
            // Processing Engine Pipeline invocation using (bitmap, activeTemplate)
        }

        // Reset targeting contexts gracefully
        targetingDayType = null
        targetingColumnId = null
        onProcessingComplete()
    }
}

@Composable
fun VlhDashboardScreen(
    coordinator: VlhWorkflowCoordinator,
    onNavigateToCamera: () -> Unit,
    onNavigateToSetup: () -> Unit
) {
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
                text = "${coordinator.activeDisplayTab.name} OVERVIEW CONFIG",
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
                            Text(
                                text = column.name,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Black,
                                maxLines = 1
                            )

                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                            // Render Data Rows + Placeholders
                            Column(
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                // Render Scanned operational strings
                                column.scannedRows.forEach { dataValue ->
                                    Box(modifier = Modifier.height(28.dp), contentAlignment = Alignment.Center) {
                                        Text(text = dataValue, style = MaterialTheme.typography.bodyLarge)
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

@Composable
fun VlhSetupConfigScreen(
    coordinator: VlhWorkflowCoordinator,
    onLaunchCamera: () -> Unit,
    onBack: () -> Unit
) {
    var selectedDay by remember { mutableStateOf(DayType.WEEKDAY) }
    var selectedColId by remember { mutableStateOf(0) }

    val targetTable = if (selectedDay == DayType.WEEKDAY) coordinator.weekdayTable else coordinator.weekendTable
    val activeCol = targetTable.columns[selectedColId]

    var timeSpanString by remember(selectedDay, selectedColId) { mutableStateOf(activeCol.name) }

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
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp) // Generous spacing looks great when scrollable
                ) {
                    List(6) { it }.forEach { idx ->
                        ElevatedFilterChip(
                            selected = selectedColId == idx,
                            onClick = { selectedColId = idx },
                            label = { Text("Col ${idx + 1}") }
                        )
                    }
                }

                Text(text = "3. Adjust Structural Time Bounds", fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = timeSpanString,
                    onValueChange = { timeSpanString = it },
                    label = { Text("Time Frame Span (e.g., 10:30 - 14:00)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                Text("Cancel")
            }
            Button(
                onClick = {
                    // Update the time parameters safely inside the data architecture
                    activeCol.name = timeSpanString

                    // Boot up the camera feed capture pipeline automatically
                    coordinator.initiateColumnUpdate(selectedDay, selectedColId, onLaunchCamera)
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Launch Scanner")
            }
        }
    }
}