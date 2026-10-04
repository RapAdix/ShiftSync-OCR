package com.example.workflowocr

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.core.Mat

class OcrFlowCoordinator(
    private val context: Context,
    private val onNavigate: (Screen) -> Unit,
    private val onTriggerCameraLaunch: () -> Unit,
    private val originalBitmap: Bitmap,
    private val scope: CoroutineScope,
    private val tableViewModel: TableViewModel,
    private val snackbarHostState: SnackbarHostState
) {
    var isDebugCapture by mutableStateOf(false)
        private set

    var activeScanningPage by mutableStateOf(ScanPageType.EMPLOYEE_P1)
        private set

    var showPagePicker by mutableStateOf(false)

    var capturedBitmap by mutableStateOf<Bitmap?>(null)
        private set

    var cellPreviewBitmap by mutableStateOf<Bitmap?>(null)
        private set

    var diagnosticBitmap by mutableStateOf<Bitmap?>(null)
        private set

    var processingErrorMsg by mutableStateOf<String?>(null)
        private set

    var onDateSupplied by mutableStateOf<((String) -> Unit)?>(null)
        private set

    var isManualDateDialogVisible by mutableStateOf(false)
        private set

    var isDateDetectionFinished by mutableStateOf(false)
        private set

    private var extractionJob: Job? = null

    fun openManualDateDialog() {
        if (onDateSupplied != null) isManualDateDialogVisible = true
    }

    fun dismissManualDateDialog() {
        isManualDateDialogVisible = false
    }

    fun submitManualDate(date: String) {
        val enteredDate = date.trim()
        if (enteredDate.isEmpty()) return
        val action = onDateSupplied
        clearManualDateRequest()
        action?.invoke(enteredDate)
    }

    private fun clearManualDateRequest() {
        onDateSupplied = null
        isManualDateDialogVisible = false
    }

    fun abandonProcessing() {
        extractionJob?.cancel()
        extractionJob = null
        clearManualDateRequest()
        cellPreviewBitmap = null
        diagnosticBitmap = null
        processingErrorMsg = null
        isDateDetectionFinished = false
    }

    fun navigateTo(screen: Screen) {
        if (screen != Screen.PROCESSING_PREVIEW) abandonProcessing()
        onNavigate(screen)
    }

    fun onCancelProcessing() {
        navigateTo(Screen.SCAN_HUB)
    }

    fun prepareForScan(debugMode: Boolean) {
        this.isDebugCapture = debugMode
        onTriggerCameraLaunch()
    }

    fun onScanRequest() {
        this.isDebugCapture = false
        val enabledPages = tableViewModel.universalSettings.enabledScanPages
        if (enabledPages.size > 1) {
            this.showPagePicker = true
        } else {
            this.activeScanningPage = enabledPages.firstOrNull() ?: ScanPageType.EMPLOYEE_P1
            onTriggerCameraLaunch()
        }
    }

    fun onPageSelected(pageType: ScanPageType) {
        this.activeScanningPage = pageType
        this.showPagePicker = false
        onTriggerCameraLaunch()
    }

    fun onPagePickerDismissed() {
        this.showPagePicker = false
    }

    fun onDebugScanRequest() {
        this.isDebugCapture = true
        onTriggerCameraLaunch()
    }

    fun handleCameraResult(bitmap: Bitmap) {
        if (isDebugCapture) {
            this.capturedBitmap = bitmap
            navigateTo(Screen.SAMPLE_DETECTION)
        } else {
            // 1. Immediately cache the raw photo and show it on screen
            this.capturedBitmap = bitmap
            this.cellPreviewBitmap = null
            this.diagnosticBitmap = null
            this.processingErrorMsg = null
            navigateTo(Screen.PROCESSING_PREVIEW)

            // 2. Fire off background operations while user views the preview
            executeFullExtractionFlow(
                bitmap,
                { navigateTo(Screen.TABLE_RESULTS) },
                { cellPreview, debugImage, errorMsg ->
                    // Instead of navigating away, we simply supply the error artifacts
                    // to update the preview screen dynamically
                    this.cellPreviewBitmap = cellPreview
                    this.diagnosticBitmap = debugImage
                    this.processingErrorMsg = errorMsg
                }
            )
        }
        this.isDebugCapture = false
    }

    fun onStubRequest() {
        val bitmapToProcess = (this.capturedBitmap ?: originalBitmap).also {
            this.capturedBitmap = it
        }
        // Stub target page is always EMPLOYEE_1
        this.activeScanningPage = ScanPageType.EMPLOYEE_P1

        this.cellPreviewBitmap = null
        this.diagnosticBitmap = null
        this.processingErrorMsg = null
        navigateTo(Screen.PROCESSING_PREVIEW)

        executeFullExtractionFlow(
            bitmapToProcess,
            { navigateTo(Screen.TABLE_RESULTS) },
            { cellPreview, debugImage, errorMsg ->
                this.cellPreviewBitmap = cellPreview
                this.diagnosticBitmap = debugImage
                this.processingErrorMsg = errorMsg
            }
        )
    }

    fun onRedoClicked() {
        navigateTo(Screen.SCAN_HUB)
        onTriggerCameraLaunch()
    }

    /**
     * This is the "Full Flow" function:
     * 1. Detects the table and shows its preview.
     * 2. Detects the date or offers manual entry while column OCR runs.
     * 3. Checks the extracted table, then saves and opens the results.
     */
    private fun executeFullExtractionFlow(bitmap: Bitmap, onSuccess: () -> Unit, setPreview: (Bitmap?, Bitmap?, String?) -> Unit) {
        extractionJob?.cancel()
        clearManualDateRequest()
        isDateDetectionFinished = false
        extractionJob = scope.launch {
            val detection = withContext(Dispatchers.Default) {
                // We will collect Mats here to ensure we release them all
                var grayMat: Mat? = null
                var deskewMat: Mat? = null

                try {
                    grayMat = ImageProcessor.bitmapToGrayMat(bitmap)

                    // Note: deskewGrayMat should return a NEW Mat if it modifies it
                    deskewMat = TableDetector.deskewGrayMat(grayMat) ?: grayMat

                    val result = TableDetector.detectTableCellsByLines(deskewMat, tableViewModel.activeLayout)
                    try {
                        currentCoroutineContext().ensureActive()
                        result
                    } catch (error: Throwable) {
                        releaseDetection(result)
                        throw error
                    }
                } finally {
                    grayMat?.release()
                    // Only release deskewMat if it's a different object than grayMat
                    if (deskewMat != grayMat) {
                        deskewMat?.release()
                    }
                }
            }

            try {
                currentCoroutineContext().ensureActive()
                val cellPreview = withContext(Dispatchers.Default) {
                    val boxedMat = TableDetector.drawCells(detection.gray, detection.cells)
                    try {
                        ImageProcessor.matToBitmap(boxedMat).scaleForPreview(1000)
                    } finally {
                        boxedMat.release()
                    }
                }
                currentCoroutineContext().ensureActive()
                when (detection) {
                    is TableDetector.TableDetectionResult.Success -> {
                        setPreview(cellPreview, null, null)
                        launch {
                            snackbarHostState.showSnackbar(
                                AppSnackbarVisuals(context.getString(R.string.preview_table_detection_success))
                            )
                        }
                        val imageBitmap = ImageProcessor.matToBitmap(detection.gray)
                        var suppliedDate: CompletableDeferred<String>? = null
                        var completePendingDate: ((String) -> Unit)? = null
                        try {
                            val settings = tableViewModel.activeLayout
                            val detectedDate = try {
                                TextProcessor.determineDate(detection.cells, imageBitmap, settings)
                            } catch (_: TextProcessor.CouldNotDetermineDateException) {
                                val pendingDate = CompletableDeferred<String>()
                                suppliedDate = pendingDate
                                completePendingDate = { pendingDate.complete(it) }
                                onDateSupplied = completePendingDate
                                null
                            }
                            isDateDetectionFinished = true
                            val rawTextGrid = TextProcessor.extractTextFromColumns(
                                detection.cells,
                                imageBitmap,
                                buildList {
                                    add(settings.nameCol)
                                    add(settings.timeStartCol)
                                    add(settings.timeEndCol)
                                    if (activeScanningPage.isManagerPage() && settings.team != null) {
                                        add(settings.team)
                                    }
                                }
                            )
                            currentCoroutineContext().ensureActive()
                            val analysis = withContext(Dispatchers.Default) {
                                CellAnalyzer.analyzeCells(
                                    detection.gray, detection.thresh, detection.cells, settings
                                )
                            }
                            currentCoroutineContext().ensureActive()
                            val failureStringID = TableQualityVerifier.verifyTimeColumns(
                                rawTextGrid, settings.timeStartCol, settings.timeEndCol
                            ) { row, col ->
                                if (col == settings.timeStartCol) analysis[row].startTimeCrossed
                                else analysis[row].endTimeCrossed
                            }
                            if (failureStringID != null) {
                                val linesBitmap = ImageProcessor.matToBitmap(detection.lines)
                                val failureMessage = context.getString(failureStringID)
                                setPreview(cellPreview, linesBitmap.scaleForPreview(1000), failureMessage)
                                launch { showExtractionFailure(failureMessage) }
                                return@launch
                            }
                            val date = detectedDate ?: checkNotNull(suppliedDate).await()
                            currentCoroutineContext().ensureActive()
                            proceedWithExtraction(date, detection, imageBitmap, rawTextGrid, analysis)
                            currentCoroutineContext().ensureActive()
                            onSuccess()
                        } finally {
                            if (onDateSupplied === completePendingDate) {
                                clearManualDateRequest()
                            }
                            imageBitmap.recycle()
                        }
                    }

                    is TableDetector.TableDetectionResult.Failure -> {
                        val failureMessage = tableFailureMessage(detection.exception)
                        val linesBitmap = ImageProcessor.matToBitmap(detection.lines)
                        setPreview(cellPreview, linesBitmap.scaleForPreview(1000), failureMessage)
                        launch { showExtractionFailure(failureMessage) }
                    }
                }
            } finally {
                releaseDetection(detection)
            }
        }
    }

    private fun releaseDetection(detection: TableDetector.TableDetectionResult) {
        detection.gray.release()
        detection.thresh.release()
        detection.mask.release()
        detection.lines.release()
    }

    private suspend fun showExtractionFailure(message: String) {
        snackbarHostState.currentSnackbarData?.dismiss()
        snackbarHostState.showSnackbar(
            AppSnackbarVisuals(
                message = context.getString(R.string.preview_extraction_aborted, message),
                isError = true,
                duration = SnackbarDuration.Long
            )
        )
    }

    private fun tableFailureMessage(error: Exception): String = when (error) {
        is TooManyLinesException -> context.getString(
            R.string.scan_table_background_noisy,
            context.resources.getQuantityString(R.plurals.scan_horizontal_line_count, error.horizontal.size, error.horizontal.size),
            context.resources.getQuantityString(R.plurals.scan_vertical_line_count, error.vertical.size, error.vertical.size)
        )
        is TableDetector.TableGridException -> when (error.reason) {
            TableDetector.TableGridException.Reason.MISSING_TOP_ROW -> context.getString(R.string.scan_table_header_missing)
            TableDetector.TableGridException.Reason.INCOMPLETE_COLUMNS -> context.resources.getQuantityString(
                R.plurals.scan_table_columns_incomplete, error.expected, error.found, error.expected
            )
            TableDetector.TableGridException.Reason.UNRELIABLE_GRID -> context.getString(R.string.scan_table_grid_unreliable)
            TableDetector.TableGridException.Reason.TOO_FEW_ROWS -> context.resources.getQuantityString(
                R.plurals.scan_table_rows_too_few, error.found, error.found
            )
        }
        else -> context.getString(R.string.scan_table_layout_not_found)
    }

    private suspend fun proceedWithExtraction(
        date: String,
        detection: TableDetector.TableDetectionResult,
        imageBitmap: Bitmap,
        rawTextGrid: Array<Array<String>>,
        analysis: Array<CellAnalyzer.RowAnalysis>
    ) {
        val settings = tableViewModel.activeLayout
        val pageName = activeScanningPage.name.lowercase()
        val table = withContext(Dispatchers.IO) {
            TextProcessor.refineTableData(rawTextGrid, settings)
        }
        currentCoroutineContext().ensureActive()
        val needsProjection = tableViewModel.saveExtractedScan(
            date = date,
            pageName = pageName,
            settings = settings,
            imageBitmap = imageBitmap,
            cells = detection.cells,
            rawTextGrid = rawTextGrid,
            table = table,
            analysis = analysis
        )
        // Returning to the caller's dispatcher checks cancellation before optional work.
        if (needsProjection) {
            scope.launch(Dispatchers.IO) {
                SpreadSheetDownloader.fetchAndSaveProjection(
                    settings = tableViewModel.universalSettings,
                    viewModel = tableViewModel
                )
            }
        }
    }
}
