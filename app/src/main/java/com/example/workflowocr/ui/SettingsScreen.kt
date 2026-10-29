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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.annotation.StringRes
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.workflowocr.licensing.LicenseSettingsSectionCard

enum class SettingsTab(@StringRes val labelRes: Int) {
    WORKFLOW(R.string.settings_tab_workflow),
    LICENSE(R.string.settings_tab_license),
    LANGUAGE(R.string.settings_tab_language),
    SYNC(R.string.settings_tab_sync),
    LAYOUT(R.string.settings_tab_layout)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: TableViewModel,
    initialTab: SettingsTab = SettingsTab.WORKFLOW
) {
    val selectedLanguage = AppLanguage.fromLanguageCode(LocalConfiguration.current.locales[0]?.language)
    var selectedTab by rememberSaveable { mutableStateOf(initialTab) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        PrimaryScrollableTabRow(
            selectedTabIndex = SettingsTab.entries.indexOf(selectedTab),
            edgePadding = 0.dp
        ) {
            SettingsTab.entries.forEach { tab ->
                Tab(
                    selected = selectedTab == tab,
                    onClick = { selectedTab = tab },
                    text = {
                        Text(
                            stringResource(tab.labelRes),
                            modifier = Modifier.offset(y = (-4).dp)
                        )
                    }
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            when (selectedTab) {
                SettingsTab.LICENSE -> LicenseSettingsSectionCard(LocalLicenseManager.current)
                SettingsTab.LANGUAGE -> LanguageSettingsCard(selectedLanguage)
                SettingsTab.WORKFLOW -> WorkflowSettings(viewModel)
                SettingsTab.SYNC -> SpreadsheetSettings(viewModel)
                SettingsTab.LAYOUT -> TableLayoutSettings(viewModel)
            }
        }
    }
}

@Composable
private fun LanguageSettingsCard(selectedLanguage: AppLanguage) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp).selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                stringResource(R.string.settings_language),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(stringResource(R.string.settings_language_description), style = MaterialTheme.typography.bodySmall)
            AppLanguage.entries.forEach { language ->
                Row(
                    modifier = Modifier.fillMaxWidth().selectable(
                        selected = selectedLanguage == language,
                        role = Role.RadioButton,
                        onClick = { language.select() }
                    ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = selectedLanguage == language, onClick = null)
                    Text(stringResource(language.labelRes))
                }
            }
        }
    }
}

@Composable
private fun WorkflowSettings(viewModel: TableViewModel) {
    val settings = viewModel.universalSettings
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.settings_workplace_shift_timings),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            NumericSettingInput(
                label = stringResource(R.string.settings_opening_hour),
                value = settings.workplaceOpeningTime,
                enabled = true,
                onValueChange = { viewModel.updateUniversalSettings(settings.copy(workplaceOpeningTime = it)) }
            )
            NumericSettingInput(
                label = stringResource(R.string.settings_closing_hour),
                value = settings.workplaceClosingTime,
                enabled = true,
                onValueChange = { viewModel.updateUniversalSettings(settings.copy(workplaceClosingTime = it)) }
            )
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.settings_active_scan_templates),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                stringResource(R.string.settings_scan_template_explanation),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            val chunkedPages = remember { ScanPageType.entries.chunked(2) }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                chunkedPages.forEach { rowItems ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowItems.forEach { pageType ->
                            val isEnabled = settings.enabledScanPages.contains(pageType)
                            ScanPageToggleCard(
                                pageType = pageType,
                                isActive = isEnabled,
                                onClick = { toggleEnabledScanPages(pageType, isEnabled, viewModel) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (rowItems.size < 2) Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun SpreadsheetSettings(viewModel: TableViewModel) {
    val settings = viewModel.universalSettings
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.settings_cloud_sync_integration),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            var urlInputState by remember(settings.spreadsheetUrl) { mutableStateOf(settings.spreadsheetUrl) }
            OutlinedTextField(
                value = urlInputState,
                onValueChange = { input ->
                    urlInputState = input
                    viewModel.updateUniversalSettings(settings.copy(spreadsheetUrl = input))
                },
                label = { Text(stringResource(R.string.settings_spreadsheet_source_url)) },
                placeholder = { Text(stringResource(R.string.settings_spreadsheet_url_example)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth()
            )

            var cellInputState by remember(settings.targetCellCoordinate) {
                mutableStateOf(settings.targetCellCoordinate)
            }
            OutlinedTextField(
                value = cellInputState,
                onValueChange = { input ->
                    cellInputState = input
                    viewModel.updateUniversalSettings(settings.copy(targetCellCoordinate = input))
                },
                label = { Text(stringResource(R.string.settings_projection_cell_coordinate)) },
                placeholder = { Text(stringResource(R.string.settings_spreadsheet_cell_example)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun TableLayoutSettings(viewModel: TableViewModel) {
    val activePreset = viewModel.activePresetType
    val layout = viewModel.activeLayout

    Text(
        stringResource(R.string.settings_table_layout_configuration),
        modifier = Modifier.padding(top = 8.dp),
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

    Text(
        if (layout.isCustom) stringResource(R.string.settings_modify_custom_layout)
        else stringResource(R.string.settings_view_locked_layout),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = if (layout.isCustom) MaterialTheme.colorScheme.secondary
        else MaterialTheme.colorScheme.onSurfaceVariant
    )
    if (!layout.isCustom) {
        Text(
            stringResource(R.string.settings_unlock_layout_explanation),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.settings_table_column_placements),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            NumericSettingInput(stringResource(R.string.settings_expected_column_count), layout.expectedCols, layout.isCustom) {
                viewModel.updateLayoutPreset(PresetType.CUSTOM, layout.copy(expectedCols = it))
            }
            NumericSettingInput(stringResource(R.string.settings_employee_name_column), layout.nameCol, layout.isCustom) {
                viewModel.updateLayoutPreset(PresetType.CUSTOM, layout.copy(nameCol = it))
            }
            NumericSettingInput(stringResource(R.string.settings_shift_start_column), layout.timeStartCol, layout.isCustom) {
                viewModel.updateLayoutPreset(PresetType.CUSTOM, layout.copy(timeStartCol = it))
            }
            NumericSettingInput(stringResource(R.string.settings_shift_end_column), layout.timeEndCol, layout.isCustom) {
                viewModel.updateLayoutPreset(PresetType.CUSTOM, layout.copy(timeEndCol = it))
            }
            NumericSettingInput(stringResource(R.string.settings_first_modification_column), layout.firstModificationCol, layout.isCustom) {
                viewModel.updateLayoutPreset(PresetType.CUSTOM, layout.copy(firstModificationCol = it))
            }
            NumericSettingInput(stringResource(R.string.settings_change_log_column), layout.changeCol, layout.isCustom) {
                viewModel.updateLayoutPreset(PresetType.CUSTOM, layout.copy(changeCol = it))
            }
            NumericSettingInput(stringResource(R.string.settings_manager_signature_column), layout.managerCol, layout.isCustom) {
                viewModel.updateLayoutPreset(PresetType.CUSTOM, layout.copy(managerCol = it))
            }
            NumericSettingInput(
                stringResource(R.string.settings_team_information_column),
                layout.team?.toString() ?: "",
                layout.isCustom
            ) { rawString ->
                viewModel.updateLayoutPreset(PresetType.CUSTOM, layout.copy(team = rawString.trim().toIntOrNull()))
            }
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.settings_structural_ratios),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            DoubleSettingInput(
                label = stringResource(R.string.settings_header_row_height_multiplier),
                value = layout.headerRowHeightMultiplier,
                enabled = layout.isCustom,
                onValueChange = {
                    viewModel.updateLayoutPreset(PresetType.CUSTOM, layout.copy(headerRowHeightMultiplier = it))
                }
            )
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
