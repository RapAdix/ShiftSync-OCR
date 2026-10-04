package com.example.workflowocr

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: TableViewModel) {
    val activePreset = viewModel.activePresetType
    val currentLayout = viewModel.activeLayout
    val universalSettings = viewModel.universalSettings
    val scrollState = rememberScrollState()

    Scaffold {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp, 0.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // --- SECTION 1: GLOBAL FACILITY TIMINGS ---
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.settings_workplace_shift_timings),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    NumericSettingInput(
                        label = stringResource(R.string.settings_opening_hour),
                        value = universalSettings.workplaceOpeningTime,
                        enabled = true,
                        onValueChange = { viewModel.updateUniversalSettings(universalSettings.copy(workplaceOpeningTime = it)) }
                    )
                    NumericSettingInput(
                        label = stringResource(R.string.settings_closing_hour),
                        value = universalSettings.workplaceClosingTime,
                        enabled = true,
                        onValueChange = { viewModel.updateUniversalSettings(universalSettings.copy(workplaceClosingTime = it)) }
                    )
                }
            }

            // --- SECTION 2: REMOTE SPREADSHEET SOURCE LINK ---
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.settings_cloud_sync_integration),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    // Local storage for url. Isolates rapid typing from slow disk I/O
                    var urlInputState by remember(universalSettings.spreadsheetUrl) {
                        mutableStateOf(universalSettings.spreadsheetUrl)
                    }

                    OutlinedTextField(
                        value = urlInputState,
                        onValueChange = { input ->
                            // Update character state immediately on screen
                            urlInputState = input
                            // Safely trigger asynchronous background write task
                            viewModel.updateUniversalSettings(universalSettings.copy(spreadsheetUrl = input))
                        },
                        label = { Text(stringResource(R.string.settings_spreadsheet_source_url)) },
                        placeholder = { Text(stringResource(R.string.settings_spreadsheet_url_example)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth()
                    )

                    var cellInputState by remember(universalSettings.targetCellCoordinate) {
                        mutableStateOf(universalSettings.targetCellCoordinate)
                    }

                    OutlinedTextField(
                        value = cellInputState,
                        onValueChange = { input ->
                            cellInputState = input
                            viewModel.updateUniversalSettings(universalSettings.copy(targetCellCoordinate = input))
                        },
                        label = { Text(stringResource(R.string.settings_projection_cell_coordinate)) },
                        placeholder = { Text(stringResource(R.string.settings_spreadsheet_cell_example)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // --- SECTION 2.5: TARGET SCAN PAGES CONFIGURATION ---
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.settings_active_scan_templates),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Text(
                        text = stringResource(R.string.settings_scan_template_explanation),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Constructing the 2x2 multi-select matrix row sets
                    val chunkedPages = remember { ScanPageType.entries.chunked(2) }

                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        chunkedPages.forEach { rowItems ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                rowItems.forEach { pageType ->
                                    val isEnabled = universalSettings.enabledScanPages.contains(pageType)

                                    // Balanced grid weights
                                    ScanPageToggleCard(
                                        pageType = pageType,
                                        isActive = isEnabled,
                                        onClick = { toggleEnabledScanPages(pageType, isEnabled, viewModel) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                // Fill missing slots on rows that aren't perfectly filled out (for future expansions)
                                if (rowItems.size < 2) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // --- SECTION 3: PRESET TYPE SELECTOR ---
            Text(
                text = stringResource(R.string.settings_table_layout_configuration),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(8.dp)) {
                    PresetSelectionRow(
                        label = stringResource(R.string.settings_layout_13_columns),
                        selected = activePreset == PresetType.DEFAULT_13_COL,
                        onClick = { viewModel.updateLayoutPreset(PresetType.DEFAULT_13_COL) }
                    )
                    PresetSelectionRow(
                        label = stringResource(R.string.settings_layout_12_columns),
                        selected = activePreset == PresetType.DEFAULT_12_COL,
                        onClick = { viewModel.updateLayoutPreset(PresetType.DEFAULT_12_COL) }
                    )
                    PresetSelectionRow(
                        label = stringResource(R.string.settings_custom_layout_template),
                        selected = activePreset == PresetType.CUSTOM,
                        onClick = { viewModel.updateLayoutPreset(PresetType.CUSTOM) }
                    )
                }
            }

            // --- SECTION 4: EDITABLE PARAMETERS ---
            Text(
                text = if (currentLayout.isCustom) stringResource(R.string.settings_modify_custom_layout) else stringResource(R.string.settings_view_locked_layout),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (currentLayout.isCustom) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!currentLayout.isCustom) {
                Text(
                    text = stringResource(R.string.settings_unlock_layout_explanation),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            // Group 2: Table Columns Configuration Mapping
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.settings_table_column_placements), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)

                    NumericSettingInput(
                        label = stringResource(R.string.settings_expected_column_count),
                        value = currentLayout.expectedCols,
                        enabled = currentLayout.isCustom,
                        onValueChange = { viewModel.updateLayoutPreset(PresetType.CUSTOM, currentLayout.copy(expectedCols = it)) }
                    )
                    NumericSettingInput(
                        label = stringResource(R.string.settings_employee_name_column),
                        value = currentLayout.nameCol,
                        enabled = currentLayout.isCustom,
                        onValueChange = { viewModel.updateLayoutPreset(PresetType.CUSTOM, currentLayout.copy(nameCol = it)) }
                    )
                    NumericSettingInput(
                        label = stringResource(R.string.settings_shift_start_column),
                        value = currentLayout.timeStartCol,
                        enabled = currentLayout.isCustom,
                        onValueChange = { viewModel.updateLayoutPreset(PresetType.CUSTOM, currentLayout.copy(timeStartCol = it)) }
                    )
                    NumericSettingInput(
                        label = stringResource(R.string.settings_shift_end_column),
                        value = currentLayout.timeEndCol,
                        enabled = currentLayout.isCustom,
                        onValueChange = { viewModel.updateLayoutPreset(PresetType.CUSTOM, currentLayout.copy(timeEndCol = it)) }
                    )
                    NumericSettingInput(
                        label = stringResource(R.string.settings_first_modification_column),
                        value = currentLayout.firstModificationCol,
                        enabled = currentLayout.isCustom,
                        onValueChange = { viewModel.updateLayoutPreset(PresetType.CUSTOM, currentLayout.copy(firstModificationCol = it)) }
                    )
                    NumericSettingInput(
                        label = stringResource(R.string.settings_change_log_column),
                        value = currentLayout.changeCol,
                        enabled = currentLayout.isCustom,
                        onValueChange = { viewModel.updateLayoutPreset(PresetType.CUSTOM, currentLayout.copy(changeCol = it)) }
                    )
                    NumericSettingInput(
                        label = stringResource(R.string.settings_manager_signature_column),
                        value = currentLayout.managerCol,
                        enabled = currentLayout.isCustom,
                        onValueChange = { viewModel.updateLayoutPreset(PresetType.CUSTOM, currentLayout.copy(managerCol = it)) }
                    )
                    NumericSettingInput(
                        label = stringResource(R.string.settings_team_information_column),
                        value = currentLayout.team?.toString() ?: "",
                        enabled = currentLayout.isCustom,
                        onValueChange = { rawString ->
                            val updatedTeamValue = rawString.trim().toIntOrNull()
                            viewModel.updateLayoutPreset(
                                PresetType.CUSTOM,
                                currentLayout.copy(team = updatedTeamValue)
                            )
                        }
                    )
                }
            }

            // Group 3: Formatting Ratios
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.settings_structural_ratios), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)

                    DoubleSettingInput(
                        label = stringResource(R.string.settings_header_row_height_multiplier),
                        value = currentLayout.headerRowHeightMultiplier,
                        enabled = currentLayout.isCustom,
                        onValueChange = { viewModel.updateLayoutPreset(PresetType.CUSTOM, currentLayout.copy(headerRowHeightMultiplier = it)) }
                    )
                }
            }
        }
    }
}

private fun toggleEnabledScanPages(pageType: ScanPageType, isEnabled: Boolean, viewModel: TableViewModel) {
    val updatedSet = if (isEnabled) {
        // Cascading turn-off: If turning off a page, also turn off any higher sequential pages
        val pagesToRemove = when(pageType) {
            ScanPageType.EMPLOYEE_P1 -> listOf(ScanPageType.EMPLOYEE_P1, ScanPageType.EMPLOYEE_P2, ScanPageType.EMPLOYEE_P3)
            ScanPageType.EMPLOYEE_P2 -> listOf(ScanPageType.EMPLOYEE_P2, ScanPageType.EMPLOYEE_P3)
            ScanPageType.EMPLOYEE_P3 -> listOf(ScanPageType.EMPLOYEE_P3)
            ScanPageType.MANAGER_P1  -> listOf(ScanPageType.MANAGER_P1)
        }
        viewModel.universalSettings.enabledScanPages - pagesToRemove.toSet()
    } else {
        // Cascading turn-on: If turning on a page, automatically ensure lower sequential pages are on too
        val pagesToAdd = when(pageType) {
            ScanPageType.EMPLOYEE_P3 -> listOf(ScanPageType.EMPLOYEE_P1, ScanPageType.EMPLOYEE_P2, ScanPageType.EMPLOYEE_P3)
            ScanPageType.EMPLOYEE_P2 -> listOf(ScanPageType.EMPLOYEE_P1, ScanPageType.EMPLOYEE_P2)
            ScanPageType.EMPLOYEE_P1 -> listOf(ScanPageType.EMPLOYEE_P1)
            ScanPageType.MANAGER_P1  -> listOf(ScanPageType.MANAGER_P1)
        }
        viewModel.universalSettings.enabledScanPages + pagesToAdd.toSet()
    }

    viewModel.updateUniversalSettings(
        viewModel.universalSettings.copy(enabledScanPages = updatedSet)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanPageToggleCard(
    pageType: ScanPageType,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Distinct visual states matching current configuration selections
    val containerColor = if (isActive) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    }

    val contentColor = if (isActive) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f) // Standard Material Disabled Alpha
    }

    val borderStroke = if (isActive) {
        BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }

    Surface(
        onClick = onClick,
        modifier = modifier.height(56.dp),
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor,
        border = borderStroke
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(pageType.displayNameRes),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                maxLines = 2
            )
        }
    }
}

@Composable
fun PresetSelectionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun NumericSettingInput(label: String, value: Int, enabled: Boolean, onValueChange: (Int) -> Unit) {
    NumericSettingInput(label, value.toString(), enabled, { input ->
        input.toIntOrNull()?.let { validInt ->
            onValueChange(validInt)
        }
    })
}

@Composable
fun NumericSettingInput(label: String, value: String, enabled: Boolean, onValueChange: (String) -> Unit) {
    var textState by remember(value) { mutableStateOf(value) }

    OutlinedTextField(
        value = textState,
        onValueChange = { input ->
            textState = input
            onValueChange(input)
        },
        label = { Text(label) },
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
fun DoubleSettingInput(label: String, value: Double, enabled: Boolean, onValueChange: (Double) -> Unit) {
    var textState by remember(value) { mutableStateOf(value.toString()) }

    OutlinedTextField(
        value = textState,
        onValueChange = { input ->
            textState = input
            input.toDoubleOrNull()?.let { validDouble ->
                onValueChange(validDouble)
            }
        },
        label = { Text(label) },
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth()
    )
}
