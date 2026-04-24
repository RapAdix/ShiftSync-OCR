package com.example.workflowocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
    val nameSnippetPath: String? = null,
    val startTimeSnippetPath: String? = null,
    val finishTimeSnippetPath: String? = null,
    val modificationsSnippetPath: String? = null // the remaining part of the row
)

@Composable
fun TableResultsScreen(rowsMap: Map<String, ProcessorRow>, onRowClick: (String) -> Unit) {
    if (rowsMap.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No data recorded.", color = MutedGrey)
        }
    } else {
        val rowsList = rowsMap.values.sortedBy { it.id.toIntOrNull() ?: 0 }

        LazyColumn(modifier = Modifier.fillMaxSize().background(PaperWhite)) {
            item {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
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
                        // Logic check: only show badges if BOTH times are valid
                        val timeAmbiguity = row.startTime.any { it in "XUW" } || row.finishTime.any { it in "XUW" }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (!timeAmbiguity) {
                                // SUCCESS: Text badges side-by-side
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
                    modifier = Modifier.clickable { onRowClick(row.id) }
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
    // Local state for the pickers
    var startH by remember { mutableStateOf(row.startTime.substringBefore(":", "08")) }
    var startM by remember { mutableStateOf(row.startTime.substringAfter(":", "00")) }
    var endH by remember { mutableStateOf(row.finishTime.substringBefore(":", "17")) }
    var endM by remember { mutableStateOf(row.finishTime.substringAfter(":", "00")) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Times: ${row.name}", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TimeColumn("START", startH, startM) { h, m -> startH = h; startM = m }
                    VerticalDivider(modifier = Modifier.height(100.dp))
                    TimeColumn("FINISH", endH, endM) { h, m -> endH = h; endM = m }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave("$startH:$startM", "$endH:$endM") }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun TimeColumn(label: String, h: String, m: String, onUpdate: (String, String) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        Row(verticalAlignment = Alignment.CenterVertically) {
            NumberPicker(value = h, range = 0..23) { onUpdate(it, m) }
            Text(":", style = MaterialTheme.typography.titleLarge)
            NumberPicker(value = m, range = 0..59, step = 5) { onUpdate(h, it) }
        }
    }
}

@Composable
fun NumberPicker(value: String, range: IntRange, step: Int = 1, onValueChange: (String) -> Unit) {
    val items = remember { range.filter { it % step == 0 }.map { it.toString().padStart(2, '0') } }

    // Simple scrollable list acting as a "Wheel"
    Box(modifier = Modifier.height(120.dp).width(50.dp)) {
        LazyColumn(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            items(items) { item ->
                val isSelected = item == value
                Text(
                    text = item,
                    modifier = Modifier
                        .clickable { onValueChange(item) }
                        .padding(vertical = 8.dp),
                    style = if (isSelected) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyMedium,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray
                )
            }
        }
    }
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

fun Point.move(dx: Double = 0.0, dy: Double = 0.0) = Point(this.x + dx, this.y + dy)
