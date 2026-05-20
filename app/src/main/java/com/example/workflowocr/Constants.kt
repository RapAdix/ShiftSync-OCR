package com.example.workflowocr

val WorkplaceOpeningTime: Int = 6  // Hour of opening
val WorkplaceClosingTime: Int = 1 // Hour of closure

const val EXPECTED_COLS = 13
const val NAME_COL = 0
const val TIME_START_COL = 3
const val TIME_END_COL = 4
const val FIRST_MODIFICATION_COL = 7
val MODIFICATION_COLUMNS : List<Int> = (FIRST_MODIFICATION_COL until (EXPECTED_COLS - 2)).toList()
val CHANGE_COL = 9
val MANAGER_COL = 10
const val HEADER_ROW_HEIGHT_MULTIPLIER = 3.78 // ratio of height between header_row / normal_row