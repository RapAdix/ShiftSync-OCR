package com.example.workflowocr
import kotlinx.serialization.Serializable

enum class PresetType {
    DEFAULT_13_COL,
    DEFAULT_12_COL,
    CUSTOM
}

object PresetDefaults {
    val layout13Col = TableLayout(
        expectedCols = 13,
        nameCol = 0,
        timeStartCol = 3,
        timeEndCol = 4,
        firstModificationCol = 7,
        changeCol = 9,
        managerCol = 10,
        headerRowHeightMultiplier = 3.78, // ratio of height between header_row / normal_row
        isCustom = false // Flag indicating this is a factory default
    )

    val layout12Col = TableLayout(
        expectedCols = 12,
        nameCol = 0,
        timeStartCol = 2,
        timeEndCol = 3,
        firstModificationCol = 6,
        changeCol = 7,
        managerCol = 9,
        headerRowHeightMultiplier = 3.78, // ratio of height between header_row / normal_row
        isCustom = false // Flag indicating this is a factory default
    )
}

// 1. Facility-wide rules. Always editable, independent of presets.
@Serializable
data class UniversalSettings(
    val workplaceOpeningTime: Int = 6, // Hour of opening
    val workplaceClosingTime: Int = 1,  // Hour of closing
    val spreadsheetUrl: String = ""
)

// 2. Structural grid rules. Highly volatile.
@Serializable
data class TableLayout(
    val expectedCols: Int = PresetDefaults.layout13Col.expectedCols,
    val nameCol: Int = PresetDefaults.layout13Col.nameCol,
    val timeStartCol: Int = PresetDefaults.layout13Col.timeStartCol,
    val timeEndCol: Int = PresetDefaults.layout13Col.timeEndCol,
    val firstModificationCol: Int = PresetDefaults.layout13Col.firstModificationCol,
    val changeCol: Int = PresetDefaults.layout13Col.changeCol,
    val managerCol: Int = PresetDefaults.layout13Col.managerCol,
    val headerRowHeightMultiplier: Double = PresetDefaults.layout13Col.headerRowHeightMultiplier,
    val isCustom: Boolean = true // True unless explicitly instantiated by our factory defaults
) {
    val modificationColumns: List<Int>
        get() = (firstModificationCol until (expectedCols - 2)).toList()
}
