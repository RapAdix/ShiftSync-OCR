package com.example.workflowocr

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp


data class TimeBlock(val label: String, val count: Int)

data class HourGroup(
    val hourLabel: String,
    val slots: List<TimeBlock>,
    val min: Int,
    val max: Int,
    val secondSmallest: Int
)

@Composable
fun AttendanceSummaryScreen(rowsMap: Map<String, ProcessorRow>) {
    val employees by remember {
        derivedStateOf { rowsMap.values.filterNot { it.isAbsent }.sortedBy { it.id } }
    }
    val settings = LocalTableViewModel.current.universalSettings
    val summary = remember(employees) { calculateHourlySummary(employees, settings.workplaceOpeningTime, settings.workplaceClosingTime) }
    var expandedHour by remember { mutableStateOf<String?>(null) }

    LazyColumn(Modifier.fillMaxSize().background(PaperWhite)) {
        items(summary) { group ->
            Column {
                ListItem(
                    modifier = Modifier.clickable {
                        expandedHour = if (expandedHour == group.hourLabel) null else group.hourLabel
                    },
                    headlineContent = { Text(group.hourLabel, fontWeight = FontWeight.Bold) },
                    supportingContent = {
                        if (expandedHour == group.hourLabel) {
                            // Detailed 15-min blocks
                            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                group.slots.forEach { slot ->
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(slot.label, style = MaterialTheme.typography.labelSmall)
                                        Text("${slot.count}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }
                    },
                    trailingContent = {
                        // The *X*(min-max) format
                        Text(
                            text = buildAnnotatedString {
                                withStyle(
                                    style = SpanStyle(
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface, // black-ish
                                        fontSize = MaterialTheme.typography.titleMedium.fontSize // match headline-ish
                                    )
                                ) {
                                    append(group.secondSmallest.toString())
                                }

                                append(" ")

                                withStyle(
                                    style = SpanStyle(
                                        color = MutedGrey,
                                        fontSize = MaterialTheme.typography.bodyMedium.fontSize
                                    )
                                ) {
                                    append("(${group.min}-${group.max})")
                                }
                            }
                        )
                    }
                )
                HorizontalDivider(color = MutedGrey.copy(alpha = 0.1f))
            }
        }
    }
}

// Logic: Sorting starts/ends and walking
fun calculateHourlySummary(employees: List<ProcessorRow>, openingTime: Int, closingTime: Int): List<HourGroup> {
    val events = mutableListOf<Pair<Int, Int>>() // Time to Delta
    employees.forEach { emp ->
        if (emp.hasValidTimes()) {
            val (s, e) = TimeUtils.timeToMinutes(emp.startTime, emp.finishTime)
            events.add(s to 1)
            events.add(e to -1)
        }
    }

    // Sort events by time
    val sortedEvents = events.sortedBy { it.first }

    val hourlyGroups = mutableListOf<HourGroup>()

    val endHour = if (closingTime > openingTime) closingTime else closingTime + 24
    // Walk through openingTime to closingTime in 15-min blocks
    for (hour in openingTime until endHour) {
        val slotsInHour = mutableListOf<TimeBlock>()
        for (q in 0..3) {
            val slotStartTime = hour * 60 + q * 15
            val midpoint = slotStartTime + 7 // Check count at middle of slot

            var count = 0
            for (event in sortedEvents) {
                if (event.first <= midpoint) count += event.second
                else break
            }

            val label = String.format("%02d:%02d", hour % 24, (q * 15))
            slotsInHour.add(TimeBlock(label, count))
        }

        val counts = slotsInHour.map { it.count }.sorted()
        hourlyGroups.add(HourGroup(
            hourLabel = String.format("%02d:00", hour % 24),
            slots = slotsInHour,
            min = counts.first(),
            max = counts.last(),
            secondSmallest = if (counts.size > 1) counts[1] else counts[0]
        ))
    }
    return hourlyGroups
}