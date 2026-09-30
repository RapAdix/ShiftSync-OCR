package com.example.workflowocr

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

@Composable
fun ManualDatePickerDialog(
    onDateSelected: (LocalDate) -> Unit,
    onDismissRequest: () -> Unit
) {
    val today = remember { LocalDate.now() }
    val firstAllowedDate = remember(today) { today.minusDays(1) }
    val lastAllowedDate = remember(today) { today.plusMonths(3) }
    val firstMonth = remember(firstAllowedDate) { YearMonth.from(firstAllowedDate) }
    val lastMonth = remember(lastAllowedDate) { YearMonth.from(lastAllowedDate) }
    var visibleMonth by remember { mutableStateOf(firstMonth) }
    var selectedDate by remember { mutableStateOf(today) }
    val locale = Locale.getDefault()
    val firstWeekday = remember(locale) { WeekFields.of(locale).firstDayOfWeek }
    val firstDayOffset = (visibleMonth.atDay(1).dayOfWeek.value - firstWeekday.value + 7) % 7
    val weekCount = (firstDayOffset + visibleMonth.lengthOfMonth() + 6) / 7

    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Select date", style = MaterialTheme.typography.titleLarge)

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { visibleMonth = visibleMonth.minusMonths(1) },
                        enabled = visibleMonth > firstMonth
                    ) {
                        Icon(Icons.Default.ChevronLeft, contentDescription = "Previous month")
                    }
                    Text(
                        visibleMonth.month.getDisplayName(TextStyle.FULL_STANDALONE, locale),
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleMedium
                    )
                    IconButton(
                        onClick = { visibleMonth = visibleMonth.plusMonths(1) },
                        enabled = visibleMonth < lastMonth
                    ) {
                        Icon(Icons.Default.ChevronRight, contentDescription = "Next month")
                    }
                }

                Row(Modifier.fillMaxWidth()) {
                    repeat(7) { index ->
                        Text(
                            firstWeekday.plus(index.toLong()).getDisplayName(TextStyle.SHORT, locale),
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }

                repeat(weekCount) { week ->
                    Row(Modifier.fillMaxWidth()) {
                        repeat(7) { weekday ->
                            val day = week * 7 + weekday - firstDayOffset + 1
                            Box(
                                modifier = Modifier.weight(1f).aspectRatio(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                if (day in 1..visibleMonth.lengthOfMonth()) {
                                    val date = visibleMonth.atDay(day)
                                    val enabled = date >= firstAllowedDate && date <= lastAllowedDate
                                    val selected = date == selectedDate
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(2.dp)
                                            .clip(CircleShape)
                                            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
                                            .clickable(enabled = enabled) { selectedDate = date },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            day.toString(),
                                            color = when {
                                                selected -> MaterialTheme.colorScheme.onPrimary
                                                enabled -> MaterialTheme.colorScheme.onSurface
                                                else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Text(
                    "Selected: ${selectedDate.format(StorageManager.storageDateFormatter())}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismissRequest) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { onDateSelected(selectedDate) }) { Text("Use date") }
                }
            }
        }
    }
}
