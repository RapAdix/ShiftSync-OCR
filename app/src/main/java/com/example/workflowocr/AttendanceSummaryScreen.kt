package com.example.workflowocr

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.DayOfWeek
import java.time.LocalDate

// Color guidelines for the tracking statuses
val SoftEmeraldGreen = Color(0xFF2E7D32)
val SoftCrimsonRed = Color(0xFFC62828)
val MutedSlateGrey = Color(0xFF757575)

data class TimeBlock(val label: String, val count: Int)

data class HourGroup(
    val hourLabel: String,
    val slots: List<TimeBlock>,
    val min: Int,
    val max: Int,
    val secondSmallest: Int,
    val requiredCrew: Int? = null,
    val isDataIncomplete: Boolean = false
) {
    val crewDelta: Int? get() = requiredCrew?.let { secondSmallest - it }
}

// Fixed structural column weights layout distribution
private const val HOUR_LABEL_WEIGHT = 0.45f  // Gives 45% width to the time string (e.g., "05:00")
private const val REQ_COLUMN_WEIGHT = 0.2f  // 20% width for Required Column
private const val COMBINED_RIGHT_WEIGHT = 0.35f // 35% for Actual and Badge stuck side-by-side

@Composable
fun AttendanceSummaryScreen(
    rowsMap: Map<String, ProcessorRow>,
    liveGcValue: List<Int>?
) {
    val employees by remember {
        derivedStateOf { rowsMap.values.filterNot { it.isAbsent }.sortedBy { it.id } }
    }
    val viewModel = LocalTableViewModel.current
    val settings = viewModel.universalSettings

    var vlhWeekday by remember { mutableStateOf(VlhTableState(DayType.WEEKDAY)) }
    var vlhWeekend by remember { mutableStateOf(VlhTableState(DayType.WEEKEND)) }

    LaunchedEffect(Unit) {
        viewModel.storageManager.vlhTablesFlow.collect { (weekdayData, weekendData) ->
            vlhWeekday = weekdayData
            vlhWeekend = weekendData
        }
    }

    // TODO load from driver what user chose
    val activeVlhState = remember(vlhWeekday, vlhWeekend, viewModel.currentWorkingDate) {
        val workingDateStr = viewModel.currentWorkingDate
        val resolvedLocalDate = if (workingDateStr != null) {
            TimeUtils.getClosestFullDate(workingDateStr) ?: LocalDate.now()
        } else {
            LocalDate.now()
        }
        val isWeekend = resolvedLocalDate.dayOfWeek == DayOfWeek.SATURDAY ||
                resolvedLocalDate.dayOfWeek == DayOfWeek.SUNDAY

        if (isWeekend) vlhWeekend else vlhWeekday
    }

    val summary = remember(employees, activeVlhState, liveGcValue) {
        calculateHourlySummary(
            employees,
            settings.workplaceOpeningTime,
            settings.workplaceClosingTime,
            activeVlhState,
            liveGcValue
        )
    }

    var expandedHour by remember { mutableStateOf<String?>(null) }
    val vlhNotFilled = !activeVlhState.hasDataForTimeRange(viewModel.universalSettings.workplaceOpeningTime, viewModel.universalSettings.workplaceClosingTime)
    val projectedGcNotFilled = liveGcValue.isNullOrEmpty()

    Column(Modifier.fillMaxSize().background(PaperWhite)) {

        // TOP DATA WARNING BANNER
        if (vlhNotFilled || projectedGcNotFilled) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.errorContainer,
                tonalElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = when {
                            vlhNotFilled && projectedGcNotFilled -> "Missing: Projected GC Value & Master VLH Configurations Table"
                            vlhNotFilled -> "Missing: Master VLH Configuration Table"
                            else -> "Missing: Synchronized Spreadsheet GC Values Data"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            Spacer(Modifier.weight(HOUR_LABEL_WEIGHT))

            Text(
                text = "Required",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MutedSlateGrey,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(REQ_COLUMN_WEIGHT)
            )

            Text(
                text = "Actual",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MutedSlateGrey,
                modifier = Modifier.weight(COMBINED_RIGHT_WEIGHT)
            )
        }

        HorizontalDivider(color = MutedSlateGrey.copy(alpha = 0.15f))

        LazyColumn(Modifier.fillMaxSize()) {
            items(summary) { group ->
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                expandedHour = if (expandedHour == group.hourLabel) null else group.hourLabel
                            }
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 1. Hour Label Column
                        Column(Modifier.weight(HOUR_LABEL_WEIGHT)) {
                            Text(group.hourLabel, fontWeight = FontWeight.Bold)

                            if (expandedHour == group.hourLabel) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, end = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    group.slots.forEach { slot ->
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(slot.label, style = MaterialTheme.typography.labelSmall)
                                            Text("${slot.count}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }
                            }
                        }

                        // 2. 🟢 REQUIRED COLUMN: Centered Numbers
                        Box(
                            modifier = Modifier.weight(REQ_COLUMN_WEIGHT),
                            contentAlignment = Alignment.Center
                        ) {
                            if (group.requiredCrew != null) {
                                Text(
                                    text = group.requiredCrew.toString(),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            } else {
                                Text("-", color = MutedSlateGrey)
                            }
                        }

                        // 3. COMBINED ACTUAL & DELTA ROW (No Distance)
                        Box(
                            modifier = Modifier.weight(COMBINED_RIGHT_WEIGHT),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Text(
                                text = buildAnnotatedString {
                                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = MaterialTheme.typography.titleMedium.fontSize, baselineShift = BaselineShift(-0.1f))) {
                                        append(group.secondSmallest.toString())
                                    }
                                    append(" ")
                                    withStyle(SpanStyle(color = MutedSlateGrey, fontSize = 12.sp, baselineShift = BaselineShift(-0.2f))) {
                                        append("(${group.min}~${group.max})")
                                    }
                                },
                                modifier = Modifier.align(Alignment.CenterStart)
                            )

                            // Badge - Anchored to the far right edge of the cell
                            if (group.crewDelta != null) {
                                val delta = group.crewDelta!!
                                val (badgeText, badgeColor) = when {
                                    delta > 0 -> "+$delta" to SoftEmeraldGreen
                                    delta < 0 -> "$delta" to SoftCrimsonRed
                                    else -> "0" to SoftEmeraldGreen
                                }

                                Surface(
                                    color = badgeColor.copy(alpha = 0.12f),
                                    shape = MaterialTheme.shapes.small,
                                    modifier = Modifier.align(Alignment.CenterEnd)
                                ) {
                                    Text(
                                        text = badgeText,
                                        color = badgeColor,
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = MutedSlateGrey.copy(alpha = 0.1f))
                }
            }
        }
    }
}

// Logic: Sorting starts/ends and walking
private fun calculateHourlySummary(
    employees: List<ProcessorRow>,
    openingTime: Int,
    closingTime: Int,
    vlhState: VlhTableState?,
    liveGcValue: List<Int>?
): List<HourGroup> {
    val events = mutableListOf<Pair<Int, Int>>()
    employees.forEach { emp ->
        if (emp.hasValidTimes()) {
            val (s, e) = TimeUtils.timeToMinutes(emp.startTime, emp.finishTime)
            events.add(s to 1)
            events.add(e to -1)
        }
    }

    val sortedEvents = events.sortedBy { it.first }
    val hourlyGroups = mutableListOf<HourGroup>()
    val endHour = if (closingTime > openingTime) closingTime else closingTime + 24

    // Walk through openingTime to closingTime in 15-min blocks
    for (hour in openingTime until endHour) {
        val slotsInHour = mutableListOf<TimeBlock>()
        val normalizedHour = hour % 24

        for (q in 0..3) {
            val slotStartTime = hour * 60 + q * 15
            val midpoint = slotStartTime + 7 // Check count at middle of slot

            var count = 0
            for (event in sortedEvents) {
                if (event.first <= midpoint) count += event.second
                else break
            }

            val label = String.format("%02d:%02d", normalizedHour, (q * 15))
            slotsInHour.add(TimeBlock(label, count))
        }

        val counts = slotsInHour.map { it.count }.sorted()

        var requiredCrewTarget: Int? = null
        var incompleteData = false

        val listIndex = hour - openingTime

        // Check if we have a valid GC entry in the list for this specific hour block
        val hourlyGc = liveGcValue?.getOrNull(listIndex)

        if (vlhState != null && hourlyGc != null) {
            // Run matrix index evaluation against the specific GC value for THIS hour
            val targetIndex = vlhState.calculateResultIndex(normalizedHour, hourlyGc)

            // Convert index to crew count (Index 0 -> 1 person, etc.)
            requiredCrewTarget = targetIndex?.let { it + 1 }
        } else {
            incompleteData = true
        }

        hourlyGroups.add(HourGroup(
            hourLabel = String.format("%02d:00", normalizedHour),
            slots = slotsInHour,
            min = counts.first(),
            max = counts.last(),
            secondSmallest = if (counts.size > 1) counts[1] else counts[0],
            requiredCrew = requiredCrewTarget,
            isDataIncomplete = incompleteData
        ))
    }
    return hourlyGroups
}