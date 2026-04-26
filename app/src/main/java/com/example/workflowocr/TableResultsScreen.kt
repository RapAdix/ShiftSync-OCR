package com.example.workflowocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.lifecycle.ViewModel
import coil.compose.AsyncImage
import org.opencv.core.Point
import java.io.File
import java.io.FileOutputStream

// Data Model
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
)

class TableViewModel : ViewModel() {
    val extractedRows = mutableStateMapOf<String, ProcessorRow>()

    // Track the "Edit" state
    var editingRowId by mutableStateOf<String?>(null)
        private set // Only the ViewModel can change the ID

    fun startEditing(id: String) { editingRowId = id }
    fun stopEditing() { editingRowId = null }
    fun saveRow(id: String, start: String, end: String) {
        val row = extractedRows[id] ?: return

        // Handle removal of no longer needed file
        val updatedOldModPath = rotateFile(
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
    }
}

@Composable
fun TableResultsScreen(viewModel: TableViewModel) {
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
                        !hasHoliday(it) || // people without holiday
                        currentlyHasWrittenModifications(it) || // people with modifications
                        hasValidTimes(it) // people who have proper time inserted(maybe someone erased modifications with eraser)
                    }
            }
        }
        LazyColumn(modifier = Modifier
            .fillMaxSize()
            .background(PaperWhite)) {
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
                ListItem(
                    headlineContent = {
                        // SNIPPET A: If OCR name is empty/short, show the image
                        if (row.name.length < 2 && row.nameSnippetPath != null) {
                            SnippetImage(row.nameSnippetPath, height = 40.dp)
                        } else {
                            Text(
                                row.name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (!hasTimeCrossed(row)) { // Only show text time badges if BOTH times are valid
                                TimeBadge(row.startTime)
                                Spacer(Modifier.width(8.dp))
                                TimeBadge(row.finishTime)
                            } else {
                                row.startTimeSnippetPath?.let { path ->
                                    SnippetImage(path, height = 24.dp, width = 64.dp)
                                }
                                // Add horizontal space between the two snippet images
                                Spacer(Modifier.width(8.dp))
                                row.finishTimeSnippetPath?.let { path ->
                                    SnippetImage(path, height = 24.dp, width = 64.dp)
                                }
                            }
                        }
                    },
                    modifier = Modifier.clickable { viewModel.startEditing(row.id) }
                )
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = InkBlack.copy(alpha = 0.05f)
                )
            }
        }
    }
}

@Composable
fun SnippetImage(path: String, height: androidx.compose.ui.unit.Dp, width: androidx.compose.ui.unit.Dp? = null) {
    AsyncImage(
        model = path, // Coil finds the file automatically from this path string
        contentDescription = "Handwritten snippet",
        modifier = Modifier
            .height(height)
            .then(if (width != null) Modifier.width(width) else Modifier.wrapContentWidth())
            .background(Color.White.copy(alpha = 0.5f)) // Visual paper backing
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
        mutableStateOf(round15(parseTimeOrNull(row.startTime) ?: currentTimeMinutes()))
    }
    var end by remember {
        mutableStateOf(
            round15(
                parseTimeOrNull(row.finishTime) ?:
                (parseTimeOrNull(row.startTime)?.let { it + 8 * 60 } ?: (currentTimeMinutes() + 8 * 60))
            )
        )
    }

    val hasModifications = currentlyHasWrittenModifications(row)

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
                            onSave(minutesToTimeString(start), minutesToTimeString(end))
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

fun minutesToTimeString(mins: Int): String {
    val h = (mins / 60) % 24
    val m = mins % 60
    return String.format("%02d:%02d", h, m)
}

fun currentTimeMinutes(): Int {
    val now = java.time.LocalTime.now()
    return now.hour * 60 + now.minute
}

fun round15(mins: Int): Int {
    return ((mins + 7) / 15) * 15
}

fun hasTimeCrossed(row: ProcessorRow): Boolean {
    val analysis = row.newAnalysis?: row.confirmedAnalysis
    if (analysis == null) return true // if analyzing failed we assume there were some modifications
    return analysis.startTimeCrossed || analysis.endTimeCrossed
}

fun hasTimeRecentlyCrossed(row: ProcessorRow): Boolean {
    if (row.newAnalysis == null) return false
    if (row.confirmedAnalysis == null) return hasTimeCrossed(row)
    return !row.confirmedAnalysis.startTimeCrossed && row.newAnalysis.startTimeCrossed ||
           !row.confirmedAnalysis.endTimeCrossed && row.newAnalysis.endTimeCrossed
}

fun hasValidTimes(row: ProcessorRow): Boolean {
    return parseTimeOrNull(row.startTime) != null && parseTimeOrNull(row.finishTime) != null
}

fun hasHoliday(row: ProcessorRow): Boolean {
    return row.startTime.any { it in "UW" } || row.finishTime.any { it in "UW" }
}

fun currentlyHasWrittenModifications(row: ProcessorRow): Boolean {
    val analysis = row.newAnalysis?: row.confirmedAnalysis
    if (analysis == null) return true // if analyzing failed we assume there were some modifications
    val emptinessThreshold = 0.05
    return analysis.penCoverage[8] > emptinessThreshold || analysis.penCoverage[9] > emptinessThreshold
}

fun hasRecentlyWrittenModifications(row: ProcessorRow): Boolean {
    if (row.confirmedAnalysis == null) return currentlyHasWrittenModifications(row)
    if (row.newAnalysis == null) return false // because the other changes were already ocnfirmed so are NOT recent
    val coverageDifferenceThreshold = 0.08
    val columnsChanged =
        MODIFICATION_COLUMNS.filter { row.newAnalysis.penCoverage[it] - row.confirmedAnalysis.penCoverage[it] > coverageDifferenceThreshold }
    return columnsChanged.any()
}

fun createSnippets(context: Context, bitmap: Bitmap, table: Array<Array<TableDetector.TableCell>>, date: String): Map<Int, Map<String, String?>>{
    val rowPaths = mutableMapOf<Int, Map<String, String?>>() // TODO change map keys into strings
    val timestamp = System.currentTimeMillis()

    // detection.cells holds the coordinates for every cell
    for (i in table.indices) {
        val cells = table[i]

        // 1. Name Snippet (Column 0)
        val namePath = saveSnippet(
            context = context,
            bitmap = bitmap,
            p1 = cells[0].topLeft, p2 = cells[0].topRight, p3 = cells[0].bottomRight, p4 = cells[0].bottomLeft,
            fileName = "name_${timestamp}_$i",
            subDir = date,
            paddingFactor = -0.05f
        )

        // 2. Start Time Snippet (Column 2)
        val startPath = saveSnippet(
            context = context,
            bitmap = bitmap,
            p1 = cells[2].topLeft, p2 = cells[2].topRight, p3 = cells[2].bottomRight, p4 = cells[2].bottomLeft,
            fileName = "start_${timestamp}_$i",
            subDir = date,
            paddingFactor = -0.05f
        )

        // 3. Finish Time Snippet (Column 3)
        val finishPath = saveSnippet(
            context = context,
            bitmap = bitmap,
            p1 = cells[3].topLeft, p2 = cells[3].topRight, p3 = cells[3].bottomRight, p4 = cells[3].bottomLeft,
            fileName = "finish_${timestamp}_$i",
            subDir = date,
            paddingFactor = -0.05f
        )

        // 4. Modifications Snippet (The full/wide row context)

        val modsPath = saveSnippet(
            context = context,
            bitmap = bitmap,
            p1 = cells[4].topLeft, p2 = cells[4].topRight.move(40.0, 0.0), p3 = cells[4].bottomRight.move(40.0, 0.0), p4 = cells[4].bottomLeft,
            fileName = "mods_${timestamp}_$i",
            subDir = date,
            paddingFactor = 0.1f
        )

        rowPaths[i] = mapOf(
            "name" to namePath,
            "start" to startPath,
            "finish" to finishPath,
            "mods" to modsPath
        )
    }

    return rowPaths
}

fun saveSnippet(
    context: Context,
    bitmap: Bitmap,
    p1: Point, p2: Point, p3: Point, p4: Point,
    fileName: String,
    subDir: String,
    paddingFactor: Float = 0f
): String? {
    return try {
        val points = listOf(p1, p2, p3, p4)

        // 1. Inflate to Rectangle and FORCE to Int immediately
        val minX = points.minOf { it.x }.toInt()
        val maxX = points.maxOf { it.x }.toInt()
        val minY = points.minOf { it.y }.toInt()
        val maxY = points.maxOf { it.y }.toInt()

        val originalW = maxX - minX
        val originalH = maxY - minY

        // 2. Apply Custom Padding (Math stays Float, then converts to Int)
        val dx = (originalW * paddingFactor).toInt()
        val dy = (originalH * paddingFactor).toInt()

        // 3. Distribution (Stay in Int land)
        val paddedX = minX - (dx / 2)
        val paddedY = minY - (dy / 2)
        val paddedW = originalW + dx
        val paddedH = originalH + dy

        // 4. Safety Bounds (Now everything is Int, so this works)
        val finalX = paddedX.coerceIn(0, bitmap.width - 1)
        val finalY = paddedY.coerceIn(0, bitmap.height - 1)

        // Final width/height cannot exceed remaining space and must be at least 1px
        val finalW = paddedW.coerceIn(1, bitmap.width - finalX)
        val finalH = paddedH.coerceIn(1, bitmap.height - finalY)

        // 5. Success! The types now match Int perfectly
        val crop = Bitmap.createBitmap(bitmap, finalX, finalY, finalW, finalH)

        val folder = File(context.filesDir, "extracted_snippets/$subDir")
        if (!folder.exists()) folder.mkdirs()
        val file = File(folder, "$fileName.jpg")

        FileOutputStream(file).use { out ->
            crop.compress(Bitmap.CompressFormat.JPEG, 85, out)
        }

        crop.recycle()
        file.absolutePath
    } catch (e: Exception) {
        Log.e("CROP_DEBUG", "Save failed for $fileName: ${e.message}", e)
        null
    }
}

/**
 * Deletes the file at [oldPath] if it exists, and returns [newPath]
 * to be shifted into the old slot.
 */
fun rotateFile(oldPath: String?, newPath: String?): String? {
    if (!oldPath.isNullOrEmpty() && oldPath != newPath) {
        val file = File(oldPath)
        if (file.exists()) {
            file.delete()
        }
    }
    return newPath
}

fun Point.move(dx: Double = 0.0, dy: Double = 0.0) = Point(this.x + dx, this.y + dy)
