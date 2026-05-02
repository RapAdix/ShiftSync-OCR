package com.example.workflowocr

import android.app.Application
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.AndroidViewModel
import coil.compose.AsyncImage
import kotlinx.serialization.Serializable
import org.opencv.core.Point

// Data Model
@Serializable
data class ProcessorRow(
    val id: String,
    val name: String,
    var startTime: String,
    var finishTime: String,
    val confirmedAnalysis: CellAnalyzer.RowAnalysis? = null,
    val newAnalysis: CellAnalyzer.RowAnalysis? = null,
    val nameSnippetPath: String? = null,
    val startTimeSnippetPath: String? = null,
    val finishTimeSnippetPath: String? = null,
    val oldModificationsSnippetPath: String? = null, // the remaining part of the row
    val newModificationsSnippetPath: String? = null,
) {
    fun isManualEntry(): Boolean = id.startsWith(MANUAL_ID_PREFIX)

    companion object {
        private const val MANUAL_ID_PREFIX = "MANUAL_"

        fun generateManualId(): String {
            return "${MANUAL_ID_PREFIX}${System.currentTimeMillis()}"
        }
    }
}

class TableViewModel(application: Application) : AndroidViewModel(application) {
    val storageManager = StorageManager(application)
    val extractedRows = mutableStateMapOf<String, ProcessorRow>()

    // Track which date we are currently looking at
    var currentWorkingDate by mutableStateOf<String?>(null)
        private set

    /**
     * Only loads if dates differ. Stores old data.
     */
    fun loadDate(newDate: String) {
        if (newDate == currentWorkingDate) return
        // 1. Save current work before switching
        if (extractedRows.isNotEmpty()) {
            storageManager.saveRowsToDisk(extractedRows, currentWorkingDate)
        }

        // 2. Clear and load new data
        val loadedData = storageManager.loadRowsFromDisk(newDate)
        extractedRows.clear()
        extractedRows.putAll(loadedData)

        // 3. Update current date state
        currentWorkingDate = newDate
    }

    fun saveToStorage() {
        storageManager.saveRowsToDisk(extractedRows, currentWorkingDate)
    }

    // Track the "Edit" state
    var editingRowId by mutableStateOf<String?>(null)
        private set // Only the ViewModel can change the ID

    fun startEditing(id: String) { editingRowId = id }
    fun stopEditing() { editingRowId = null }
    fun saveRow(id: String, start: String, end: String) {
        val row = extractedRows[id] ?: return

        // Handle removal of no longer needed file
        val updatedOldModPath = StorageManager.rotateFile(
            oldPath = row.oldModificationsSnippetPath,
            newPath = row.newModificationsSnippetPath
        )

        // 2. Perform the update
        extractedRows[id] = row.copy(
            startTime = start,
            finishTime = end,
            confirmedAnalysis = row.newAnalysis,
            newAnalysis = null,
            oldModificationsSnippetPath = updatedOldModPath,
            newModificationsSnippetPath = null
        )
        stopEditing()
        storageManager.saveRowsToDisk(extractedRows, currentWorkingDate)
    }

    fun addManualRow(name: String, startTime: String, finishTime: String) {
        val manualId = ProcessorRow.generateManualId()
        val newRow = ProcessorRow(
            id = manualId,
            name = name,
            startTime = startTime,
            finishTime = finishTime,
            confirmedAnalysis = null,
            newAnalysis = null,
            nameSnippetPath = null,
            startTimeSnippetPath = null,
            finishTimeSnippetPath = null,
            oldModificationsSnippetPath = null,
            newModificationsSnippetPath = null
        )

        // Add to the map and save
        extractedRows[manualId] = newRow
        currentWorkingDate?.let { date ->
            storageManager.saveRowsToDisk(extractedRows, date)
        }
    }
}

enum class RowStatus(
    val backgroundColor: Color,
    val contentColor: Color
) {
    DANGER(Color(0xFFFFEBEE), Color(0xFFB71C1C)),     // Red theme
    WARNING(Color(0xFFFFFDE7), Color(0xFFF57F17)),    // Yellow theme
    NEUTRAL(Color.Transparent, Color.Unspecified)     // Default
}

@Composable
fun TableResultsScreen(viewModel: TableViewModel) {
    var showAddDialog by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf(false) }

    // The UI stays clean and just reacts to the ViewModel
    viewModel.editingRowId?.let { id ->
        val row = viewModel.extractedRows[id] ?: return@let
        EditTimeDialog(
            row = row,
            onDismiss = { viewModel.stopEditing() },
            onSave = { start, end ->
                viewModel.saveRow(id, start, end)
            }
        )
    }
    if (showAddDialog) {
        AddEmployeeDialog(
            onDismiss = { showAddDialog = false },
            onSave = { name, start, end ->
                viewModel.addManualRow(name, start, end)
                showAddDialog = false
            }
        )
    }
    if (viewModel.extractedRows.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No data recorded.", color = MutedGrey)
        }
    } else {
        val rowsList by remember(viewModel.extractedRows) {
            derivedStateOf {
                viewModel.extractedRows.values
                    .sortedWith(
                        compareBy<ProcessorRow> { it.id.substringBeforeLast('_') }
                            .thenBy { it.id.substringAfterLast('_').toIntOrNull() ?: 0 }
                    )
                    .filter {
                        showAll ||
                        !it.hasHoliday() || // people without holiday
                        it.currentlyHasWrittenModifications() || // people with modifications
                        it.hasValidTimes() // people who have proper time inserted(maybe someone erased modifications with eraser)
                    }
            }
        }
        LazyColumn(modifier = Modifier
            .fillMaxSize()
            .background(PaperWhite)) {
            // The Toggle Filter Row
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (showAll) "Showing all employees" else "Showing only working employees",
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedGrey
                    )

                    // A simple M3 Switch or a TextButton toggle
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Show All",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Switch(
                            checked = showAll,
                            onCheckedChange = { showAll = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = AccentOlive,
                                checkedTrackColor = AccentOlive.copy(alpha = 0.3f)
                            )
                        )
                    }
                }
                HorizontalDivider(thickness = 1.dp, color = InkBlack.copy(alpha = 0.05f))
            }

            item {
                Row(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("ENTITY", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MutedGrey)
                    Text("START", modifier = Modifier.width(80.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MutedGrey, textAlign = TextAlign.Center)
                    Text("FINISH", modifier = Modifier.width(80.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MutedGrey, textAlign = TextAlign.Center)
                }
                // The "Dark Line" separator
                HorizontalDivider(thickness = 1.dp, color = InkBlack.copy(alpha = 0.1f))
            }

            items(rowsList, key = { it.id }) { row ->
                val status = row.getRowStatus()

                ListItem(
                    modifier = Modifier.clickable { viewModel.startEditing(row.id) },
                    colors = ListItemDefaults.colors(
                        containerColor = status.backgroundColor,
                        headlineColor = status.contentColor
                    ),
                    headlineContent = {
                        if (row.name.length < 2 && row.nameSnippetPath != null) {
                            SnippetImage(row.nameSnippetPath, height = 40.dp)
                        } else {
                            Text(
                                text = row.name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            displayTextTimeOrSnippet(row, row.startTimeSnippetPath, row.startTime)
                            Spacer(Modifier.width(8.dp))
                            displayTextTimeOrSnippet(row, row.finishTimeSnippetPath, row.finishTime)
                        }
                    }
                )
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = InkBlack.copy(alpha = 0.05f)
                )
            }

            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp), // Space it out from the last row
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        onClick = { showAddDialog = true },
                        color = AccentOlive.copy(alpha = 0.85f),
                        shape = RoundedCornerShape(12.dp),
                        shadowElevation = 4.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "Add Employee",
                                color = Color.White,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun displayTextTimeOrSnippet(row: ProcessorRow, path: String?, time: String) {
    if (row.hasTimeRecentlyCrossed()) path?.let {SnippetImage(path, height = 32.dp, width = 60.dp)} ?: TimeBadge("X")
    else if (TimeUtils.parseTimeOrNull(time) == null) path?.let {SnippetImage(path, height = 32.dp, width = 60.dp)} ?: TimeBadge("X")
    else TimeBadge(time)
}

@Composable
fun SnippetImage(path: String, height: androidx.compose.ui.unit.Dp, width: androidx.compose.ui.unit.Dp? = null) {
    AsyncImage(
        model = path, // Coil finds the file automatically from this path string
        contentDescription = "Handwritten snippet",
        modifier = Modifier
            .height(height)
            .then(if (width != null) Modifier.width(width) else Modifier.wrapContentWidth())
    )
}

@Composable
fun TimeBadge(time: String) {
    val isError = time.isEmpty() || time.split(":").let { if(it.size == 2) it[1].toInt() > 59 else true }

    Surface(
        // Slightly darker off-white for the badge background
        color = if (isError) Color(0xFFFFEBEE) else Color(0xFFEBEBE6),
        shape = MaterialTheme.shapes.small,
        border = if (isError) BorderStroke(1.dp, Color.Red) else BorderStroke(0.5.dp, InkBlack.copy(alpha = 0.1f))
    ) {
        Text(
            text = if (time.isEmpty()) "??:??" else time,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black // Bold "Ink" look
            ),
            color = if (isError) Color.Red else InkBlack
        )
    }
}

@Composable
fun EditTimeDialog(
    row: ProcessorRow,
    onDismiss: () -> Unit,
    onSave: (startTime: String, finishTime: String) -> Unit
) {
    var start by remember {
        mutableStateOf(TimeUtils.round15(TimeUtils.parseTimeOrNull(row.startTime) ?: TimeUtils.currentTimeMinutes()))
    }
    var end by remember {
        mutableStateOf(
            TimeUtils.round15(
                TimeUtils.parseTimeOrNull(row.finishTime) ?:
                (TimeUtils.parseTimeOrNull(row.startTime)?.let { it + 8 * 60 } ?: (TimeUtils.currentTimeMinutes() + 8 * 60))
            )
        )
    }

    val hasModifications = row.currentlyHasWrittenModifications()

    Dialog(
        onDismissRequest = { }, // Managed manually via scrim
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false // Stops closing while hovering a finger outside dialog
        )
    ) {
        // Manual Scrim: Detects ONLY actual taps on the background
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.4f)) // Manual dimming
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.95f) // Use fraction instead of IntrinsicSize.Max so it doesn't push off-screen
                    .padding(24.dp)
                    // This block "catches" all touches so they don't fall through to the background scrim.
                    .pointerInput(Unit) {
                        detectTapGestures { /* Do nothing, just consume the tap */ }
                    },
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp
            ) {
                Column(modifier = Modifier.padding(24.dp)) {

                    // --- HEADER: Name Text vs. Name Snippet ---
                    if (row.name.length < 2 && row.nameSnippetPath != null) { //TODO add better logic
                        Column {
                            SnippetImage(row.nameSnippetPath, height = 48.dp)
                        }
                    } else {
                        Text(text = "${row.name}:", style = MaterialTheme.typography.headlineSmall)
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // --- BODY: Time Pickers ---
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Start Column
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            row.startTimeSnippetPath?.let { SnippetImage(it, height = 32.dp) }
                            Spacer(modifier = Modifier.height(8.dp))
                            TimePicker15("START", start) { start = it }
                        }

                        VerticalDivider(modifier = Modifier
                            .height(110.dp)
                            .padding(horizontal = 12.dp))

                        // Finish Column
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            row.finishTimeSnippetPath?.let { SnippetImage(it, height = 32.dp) }
                            Spacer(modifier = Modifier.height(8.dp))
                            TimePicker15("FINISH", end) { end = it }
                        }
                    }

                    // --- BOTTOM: Modifications Snippet ---
                    if (hasModifications) {
                        Spacer(modifier = Modifier.height(24.dp))
                        HorizontalDivider(modifier = Modifier.alpha(0.1f))
                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            "Detected Modifications" + if (row.newModificationsSnippetPath != null && row.oldModificationsSnippetPath != null) ", Before:" else ":",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        if (row.oldModificationsSnippetPath != null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                            ) {
                                SnippetImage(path = row.oldModificationsSnippetPath, height = 50.dp)
                            }
                        }

                        if (row.oldModificationsSnippetPath != null && row.newModificationsSnippetPath != null) {
                            Text(
                                "After:",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        if (row.newModificationsSnippetPath != null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                            ) {
                                SnippetImage(path = row.newModificationsSnippetPath, height = 50.dp)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // --- ACTIONS ---
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = {
                            onSave(TimeUtils.minutesToTimeString(start), TimeUtils.minutesToTimeString(end))
                        }) {
                            Text("Save")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AddEmployeeDialog(
    onDismiss: () -> Unit,
    onSave: (name: String, startTime: String, finishTime: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    // Initial values: 10:00 (600 min) and 18:00 (1080 min)
    var startMinutes by remember { mutableStateOf(600) }
    var endMinutes by remember { mutableStateOf(1080) }

    Dialog(
        onDismissRequest = { },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.4f))
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
            contentAlignment = Alignment.Center
        ) {
            // Dialog Card
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .pointerInput(Unit) { /* Stop tap propagation to scrim */ },
                colors = CardDefaults.cardColors(containerColor = PaperWhite),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    Text(
                        text = "Add Employee",
                        style = MaterialTheme.typography.headlineSmall,
                        color = InkBlack
                    )

                    // Upper Row: Name
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentOlive,
                            focusedLabelColor = AccentOlive
                        )
                    )

                    // Lower Row: Time Pickers
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            TimePicker15(
                                label = "Start",
                                value = startMinutes,
                                onChange = { startMinutes = it }
                            )
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            TimePicker15(
                                label = "Finish",
                                value = endMinutes,
                                onChange = { endMinutes = it }
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text("Cancel", color = MutedGrey)
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (name.isNotBlank()) {
                                    onSave(
                                        name,
                                        TimeUtils.minutesToTimeString(startMinutes),
                                        TimeUtils.minutesToTimeString(endMinutes)
                                    )
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentOlive),
                            enabled = name.isNotBlank()
                        ) {
                            Text("Add")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TimePicker15(
    label: String,
    value: Int,                // minutes (e.g. 480 = 08:00)
    onChange: (Int) -> Unit
) {
    val times = remember {
        (0 until 24 * 60 step 15).toList()
    }

    val initialIndex = times.indexOf(value).coerceAtLeast(0)

    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = initialIndex
    )

    LaunchedEffect(value) {
        val index = times.indexOf(value)
        if (index >= 0) {
            if (index == 0) listState.animateScrollToItem(index)
            else listState.animateScrollToItem(index - 1)
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)

        Box(
            modifier = Modifier
                .height(120.dp)
                .width(80.dp)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                items(times) { minutes ->
                    val isSelected = minutes == value

                    val styledText = buildAnnotatedString {
                        // HOUR
                        withStyle(
                            SpanStyle(
                                fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
                                fontSize = if (isSelected)
                                    MaterialTheme.typography.titleLarge.fontSize
                                else
                                    MaterialTheme.typography.bodyLarge.fontSize
                            )
                        ) {
                            append(String.format("%02d", (minutes / 60) % 24))
                        }

                        append(":")

                        // MINUTES
                        withStyle(
                            SpanStyle(
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                fontSize = if (isSelected)
                                    MaterialTheme.typography.titleMedium.fontSize
                                else
                                    MaterialTheme.typography.bodyMedium.fontSize
                            )
                        ) {
                            append(String.format("%02d", minutes % 60))
                        }
                    }

                    Text(
                        text = styledText,
                        modifier = Modifier
                            .clickable { onChange(minutes) }
                            .padding(vertical = 8.dp),
                        color = if (isSelected)
                            MaterialTheme.colorScheme.primary
                        else
                            Color.Gray
                    )
                }
            }
        }
    }
}

// if newAnalysis has the time crossed but the confirmed one does not, then mark that time in red and full row in red, dont display any text times - only images
// if there is XUW in time but no crossing(which can happen) then mark that time red and display it as image and whole row red.
// if hasHoliday && hasRecentModifications then
//      if !hasValidTimes display red row
//      else display yellow row
// if hasRecentModifications then only mark as warning if the times are crossed. otherwise it is probably employee signature
fun ProcessorRow.getRowStatus(): RowStatus {
    val isExtraEmployee = hasHoliday() && hasRecentlyWrittenModifications()

    return when {
        !hasValidTimes() -> RowStatus.DANGER
        hasTimeRecentlyCrossed() -> RowStatus.DANGER
        isExtraEmployee && hasValidTimes() -> RowStatus.NEUTRAL // !hasValidTimes(row) is already covered
        isManualEntry() -> RowStatus.NEUTRAL
        hasRecentlyWrittenModifications() && hasTimeCrossed() -> RowStatus.WARNING
        else -> RowStatus.NEUTRAL
    }
}

fun ProcessorRow.hasTimeCrossed(): Boolean {
    val analysis = newAnalysis?: confirmedAnalysis
    if (analysis == null) return true // if analyzing failed we assume there were some modifications
    return analysis.startTimeCrossed || analysis.endTimeCrossed
}

fun ProcessorRow.hasTimeRecentlyCrossed(): Boolean {
    if (newAnalysis == null) return false
    if (confirmedAnalysis == null) return hasTimeCrossed()
    return !confirmedAnalysis.startTimeCrossed && newAnalysis.startTimeCrossed ||
           !confirmedAnalysis.endTimeCrossed && newAnalysis.endTimeCrossed
}

fun ProcessorRow.hasValidTimes(): Boolean {
    return TimeUtils.parseTimeOrNull(startTime) != null && TimeUtils.parseTimeOrNull(finishTime) != null
}

fun ProcessorRow.hasHoliday(): Boolean {
    return startTime.any { it in "UW" } || finishTime.any { it in "UW" }
}

fun ProcessorRow.currentlyHasWrittenModifications(): Boolean {
    val analysis = newAnalysis?: confirmedAnalysis
    if (analysis == null) return true // if analyzing failed we assume there were some modifications
    val emptinessThreshold = 0.05
    return analysis.penCoverage[8] > emptinessThreshold || analysis.penCoverage[9] > emptinessThreshold
}

fun ProcessorRow.hasRecentlyWrittenModifications(): Boolean {
    if (confirmedAnalysis == null) return currentlyHasWrittenModifications()
    if (newAnalysis == null) return false // because the other changes were already confirmed so are NOT recent
    val coverageDifferenceThreshold = 0.08
    val columnsChanged =
        MODIFICATION_COLUMNS.filter { newAnalysis.penCoverage[it] - confirmedAnalysis.penCoverage[it] > coverageDifferenceThreshold }
    return columnsChanged.any()
}

fun Point.move(dx: Double = 0.0, dy: Double = 0.0) = Point(this.x + dx, this.y + dy)
