package com.example.workflowocr
import kotlinx.serialization.Serializable

enum class PresetType {
    DEFAULT_13_COL,
    DEFAULT_12_COL,
    CUSTOM
}

object PresetDefaults {
    val default13Col = AppSettings(
        workplaceOpeningTime = 6, // Hour of opening
        workplaceClosingTime = 1, // Hour of closing
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

    val default12Col = AppSettings(
        workplaceOpeningTime = 6, // Hour of opening
        workplaceClosingTime = 1, // Hour of closing
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

@Serializable
data class AppSettings(
    val workplaceOpeningTime: Int = PresetDefaults.default13Col.workplaceOpeningTime,
    val workplaceClosingTime: Int = PresetDefaults.default13Col.workplaceClosingTime,
    val expectedCols: Int = PresetDefaults.default13Col.expectedCols,
    val nameCol: Int = PresetDefaults.default13Col.nameCol,
    val timeStartCol: Int = PresetDefaults.default13Col.timeStartCol,
    val timeEndCol: Int = PresetDefaults.default13Col.timeEndCol,
    val firstModificationCol: Int = PresetDefaults.default13Col.firstModificationCol,
    val changeCol: Int = PresetDefaults.default13Col.changeCol,
    val managerCol: Int = PresetDefaults.default13Col.managerCol,
    val headerRowHeightMultiplier: Double = PresetDefaults.default13Col.headerRowHeightMultiplier,
    val isCustom: Boolean = true // True unless explicitly instantiated by our factory defaults
) {
    val modificationColumns: List<Int>
        get() = (firstModificationCol until (expectedCols - 2)).toList()
}
