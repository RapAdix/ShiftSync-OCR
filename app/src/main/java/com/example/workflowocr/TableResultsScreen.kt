package com.example.workflowocr

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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

// Data Model
data class ProcessorRow(
    val id: String,
    val name: String,
    var startTime: String,
    var finishTime: String
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
                    headlineContent = { Text(row.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold) },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TimeBadge(row.startTime)
                            Spacer(Modifier.width(8.dp))
                            TimeBadge(row.finishTime)
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { onRowClick(row.id) }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = InkBlack.copy(alpha = 0.05f))
            }
        }
    }
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
