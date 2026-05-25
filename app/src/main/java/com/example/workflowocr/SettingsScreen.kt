package com.example.workflowocr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: TableViewModel) {
    // 1. Observe our clean forwarded state hooks from the ViewModel
    val activePreset = viewModel.activePresetType
    val currentSettings = viewModel.activeSettings
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Application Settings") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // --- SECTION 1: PRESET TYPE SELECTOR ---
            Text(
                text = "Table Layout Configuration",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(8.dp)) {
                    PresetSelectionRow(
                        label = "Default 13-Column Layout",
                        selected = activePreset == PresetType.DEFAULT_13_COL,
                        onClick = { viewModel.updateSettings(PresetType.DEFAULT_13_COL, PresetDefaults.default13Col) }
                    )
                    PresetSelectionRow(
                        label = "Default 12-Column Layout",
                        selected = activePreset == PresetType.DEFAULT_12_COL,
                        onClick = { viewModel.updateSettings(PresetType.DEFAULT_12_COL, PresetDefaults.default12Col) }
                    )
                    PresetSelectionRow(
                        label = "Custom Rules Template (Editable)",
                        selected = activePreset == PresetType.CUSTOM,
                        onClick = {
                            // If switching to custom for the first time, clone the current 13-col parameters as a base
                            if (activePreset != PresetType.CUSTOM) {
                                viewModel.updateSettings(PresetType.CUSTOM)
                            }
                        }
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // --- SECTION 2: EDITABLE PARAMETERS ---
            Text(
                text = if (currentSettings.isCustom) "Modify Custom Settings" else "View Active Preset Rules (Locked)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (currentSettings.isCustom) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!currentSettings.isCustom) {
                Text(
                    text = "Select 'Custom Rules Template' above to unlock editing fields.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            // Group 1: Shift Operational Hours
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Workplace Shifts Timings", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)

                    NumericSettingInput(
                        label = "Opening Hour (0-23)",
                        value = currentSettings.workplaceOpeningTime,
                        enabled = currentSettings.isCustom,
                        onValueChange = { viewModel.updateSettings(PresetType.CUSTOM, currentSettings.copy(workplaceOpeningTime = it)) }
                    )
                    NumericSettingInput(
                        label = "Closing Hour (0-23)",
                        value = currentSettings.workplaceClosingTime,
                        enabled = currentSettings.isCustom,
                        onValueChange = { viewModel.updateSettings(PresetType.CUSTOM, currentSettings.copy(workplaceClosingTime = it)) }
                    )
                }
            }

            // Group 2: Table Columns Configuration Mapping
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Table Column Placements", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)

                    NumericSettingInput(
                        label = "Expected Column Size",
                        value = currentSettings.expectedCols,
                        enabled = currentSettings.isCustom,
                        onValueChange = { viewModel.updateSettings(PresetType.CUSTOM, currentSettings.copy(expectedCols = it)) }
                    )
                    NumericSettingInput(
                        label = "Employee Name Column",
                        value = currentSettings.nameCol,
                        enabled = currentSettings.isCustom,
                        onValueChange = { viewModel.updateSettings(PresetType.CUSTOM, currentSettings.copy(nameCol = it)) }
                    )
                    NumericSettingInput(
                        label = "Shift Start Time Column",
                        value = currentSettings.timeStartCol,
                        enabled = currentSettings.isCustom,
                        onValueChange = { viewModel.updateSettings(PresetType.CUSTOM, currentSettings.copy(timeStartCol = it)) }
                    )
                    NumericSettingInput(
                        label = "Shift End Time Column",
                        value = currentSettings.timeEndCol,
                        enabled = currentSettings.isCustom,
                        onValueChange = { viewModel.updateSettings(PresetType.CUSTOM, currentSettings.copy(timeEndCol = it)) }
                    )
                    NumericSettingInput(
                        label = "First Modification Track Column",
                        value = currentSettings.firstModificationCol,
                        enabled = currentSettings.isCustom,
                        onValueChange = { viewModel.updateSettings(PresetType.CUSTOM, currentSettings.copy(firstModificationCol = it)) }
                    )
                    NumericSettingInput(
                        label = "Change Log Column",
                        value = currentSettings.changeCol,
                        enabled = currentSettings.isCustom,
                        onValueChange = { viewModel.updateSettings(PresetType.CUSTOM, currentSettings.copy(changeCol = it)) }
                    )
                    NumericSettingInput(
                        label = "Manager Signature Column",
                        value = currentSettings.managerCol,
                        enabled = currentSettings.isCustom,
                        onValueChange = { viewModel.updateSettings(PresetType.CUSTOM, currentSettings.copy(managerCol = it)) }
                    )
                }
            }

            // Group 3: Formatting Ratios
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Structural Ratios", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)

                    DoubleSettingInput(
                        label = "Header Row Height Multiplier",
                        value = currentSettings.headerRowHeightMultiplier,
                        enabled = currentSettings.isCustom,
                        onValueChange = { viewModel.updateSettings(PresetType.CUSTOM, currentSettings.copy(headerRowHeightMultiplier = it)) }
                    )
                }
            }
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
    var textState by remember(value) { mutableStateOf(value.toString()) }

    OutlinedTextField(
        value = textState,
        onValueChange = { input ->
            textState = input
            // Only update downstream if input maps to a valid base-10 number string
            input.toIntOrNull()?.let { validInt ->
                onValueChange(validInt)
            }
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