package com.example.workflowocr

import android.util.Log
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.ss.usermodel.WorkbookFactory
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter

sealed interface ProjectionResult {
    data class Success(val data: List<Int?>) : ProjectionResult
    sealed interface Failure : ProjectionResult {
        object InvalidUrl : Failure
        object NetworkError : Failure
        data class DateTabNotFound(val expectedTabName: String) : Failure
        object InvalidCellCoordinate : Failure
        object FileTooLarge : Failure
        data class Unknown(val message: String) : Failure
    }
}

object SpreadSheetDownloader {
    private const val TAG = "SpreadsheetLoader"
    private const val MAX_ALLOWED_FILE_SIZE = 10 * 1024 * 1024 // 10 Megabytes max limit guardrail

    // Supported Microsoft Excel binary content types
    private val VALID_EXCEL_TYPES = setOf(
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", // .xlsx
        "application/vnd.ms-excel" // .xls
    )

    suspend fun downloadSecureWorkbook(rawUserLink: String): Result<Workbook> = withContext(Dispatchers.IO) {
        try {
            // Initialize cookie manager to seamlessly persist temporary session access tokens across redirects
            if (CookieHandler.getDefault() == null) {
                CookieHandler.setDefault(CookieManager(null, CookiePolicy.ACCEPT_ALL))
            }

            if (!rawUserLink.startsWith("https://", ignoreCase = true)) {
                return@withContext Result.failure(IllegalArgumentException("Insecure URL format. Must use HTTPS."))
            }

            // 1. Ensure download protocol queries are properly attached
            val cleanUrl = rawUserLink.trim()
            val finalDownloadUrl = if (!cleanUrl.contains("download=1")) {
                if (cleanUrl.contains("?")) "$cleanUrl&download=1" else "$cleanUrl?download=1"
            } else {
                cleanUrl
            }

            Log.d(TAG, "Initiating secure spreadsheet sync pipeline from browser reference node.")

            var currentUrl = URL(finalDownloadUrl)
            var connection = createBrowserMimicConnection(currentUrl)
            connection.connect()

            var responseCode = connection.responseCode
            var redirectCount = 0

            // 2. Headless execution loop resolving relative/absolute HTTP redirect handshakes
            while (isRedirectResponse(responseCode)) {
                if (redirectCount++ > 5) {
                    connection.disconnect()
                    return@withContext Result.failure(SecurityException("Aborted: Infinite redirect loop safety limit triggered."))
                }

                val locationHeader = connection.getHeaderField("Location")
                if (locationHeader.isNullOrEmpty()) {
                    connection.disconnect()
                    return@withContext Result.failure(Exception("Redirect handshake failed: Location header payload missing."))
                }

                // Uniformly combine base URL context with relative paths (e.g., "/personal/...") or absolute addresses
                val resolvedTargetUrl = URL(currentUrl, locationHeader)
                connection.disconnect()

                currentUrl = resolvedTargetUrl
                connection = createBrowserMimicConnection(currentUrl)
                connection.connect()

                responseCode = connection.responseCode
            }

            if (responseCode != HttpURLConnection.HTTP_OK) {
                connection.disconnect()
                return@withContext Result.failure(Exception("Server returned bad response: HTTP $responseCode"))
            }

            // Check file size before streaming into RAM
            val contentLength = connection.contentLengthLong
            if (contentLength > MAX_ALLOWED_FILE_SIZE) {
                Log.e(TAG, "Aborted! Target file size exceeds safety limits ($contentLength bytes).")
                connection.disconnect()
                return@withContext Result.failure(SecurityException("Aborted! Target file size exceeds safety limits."))
            }

            val contentType = connection.contentType?.split(";")?.get(0)?.trim()?.lowercase()
            if (contentType !in VALID_EXCEL_TYPES && contentType != "application/octet-stream") {
                connection.disconnect()
                return@withContext Result.failure(SecurityException("Aborted! Unexpected file structure MIME layout: $contentType"))
            }

            // Stream raw byte chunks smoothly into memory allocation blocks
            connection.inputStream.use { stream ->
                val workbook = WorkbookFactory.create(stream)
                Log.d(TAG, "Workbook byte streams mapped into Apache POI memory frameworks successfully.")
                return@withContext Result.success(workbook)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Fail-safe intercepted data pipeline error context: ${e.localizedMessage}", e)
            return@withContext Result.failure(e)
        }
    }

    private fun createBrowserMimicConnection(targetUrl: URL): HttpURLConnection {
        return (targetUrl.openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            instanceFollowRedirects = false // Handled manually to bypass login pages
            setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
        }
    }

    private fun isRedirectResponse(responseCode: Int): Boolean {
        return responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                responseCode == 307 || responseCode == 308
    }

    suspend fun getProjectionForDate(
        dateStr: String,
        targetCellCoordinate: String,
        workbook: Workbook
    ): ProjectionResult = withContext(Dispatchers.IO) {
        try {
            val parts = dateStr.replace(".", "-").split("-")
            val day = parts.getOrNull(0)?.toIntOrNull() ?: return@withContext ProjectionResult.Failure.Unknown("Invalid Date Elements Format")
            val month = parts.getOrNull(1)?.toIntOrNull() ?: return@withContext ProjectionResult.Failure.Unknown("Invalid Date Elements Format")
            val formattedTabName = String.format("%02d.%02d", day, month)

            val sheet = workbook.getSheet(formattedTabName)
            if (sheet == null) {
                Log.e(TAG, "Excel Tab '$formattedTabName' not found in workbook.")
                return@withContext ProjectionResult.Failure.DateTabNotFound(formattedTabName)
            }

            // Separate the letters from the numbers (e.g., "AB6" -> letters: "AB", digits: "6")
            val letterPart = targetCellCoordinate.takeWhile { it.isLetter() }.uppercase()
            val digitPart = targetCellCoordinate.dropWhile { it.isLetter() }

            if (letterPart.isEmpty() || digitPart.isEmpty()) {
                return@withContext ProjectionResult.Failure.InvalidCellCoordinate
            }

            val startRowIndex = (digitPart.toIntOrNull() ?: 1) - 1 // "6" -> index 5

            // Convert Excel column letters (Base-26) to a 0-based integer index
            var columnIndex = 0
            for (i in letterPart.indices) {
                columnIndex = columnIndex * 26 + (letterPart[i] - 'A' + 1)
            }
            columnIndex -= 1 // Shift from 1-based math down to 0-based index (e.g., "A"->0, "B"->1, "AB"->27)

            val rawProjectionList = mutableListOf<Int?>()

            for (i in 0 until 24) {
                val currentRowIndex = startRowIndex + i
                val row = sheet.getRow(currentRowIndex)
                val cell = row?.getCell(columnIndex)

                if (cell == null || cell.cellType == org.apache.poi.ss.usermodel.CellType.BLANK) {
                    rawProjectionList.add(null)
                } else {
                    try {
                        rawProjectionList.add(cell.numericCellValue.toInt())
                    } catch (e: Exception) {
                        rawProjectionList.add(null)
                    }
                }
            }

            // Trim trailing null elements while preserving empty blocks in the middle
            while (rawProjectionList.isNotEmpty() && rawProjectionList.last() == null) {
                rawProjectionList.removeAt(rawProjectionList.lastIndex)
            }

            return@withContext ProjectionResult.Success(rawProjectionList)
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting projection sequence: ${e.localizedMessage}")
            return@withContext ProjectionResult.Failure.Unknown(e.localizedMessage ?: "Extraction Error")
        }
    }

    suspend fun getProjectionForDate(
        dateStr: String,
        targetCellCoordinate: String = "B5",
        link: String
    ): ProjectionResult {
        val downloadResult = downloadSecureWorkbook(link)

        return downloadResult.fold(
            onSuccess = { workbook ->
                val outcome = getProjectionForDate(dateStr, targetCellCoordinate, workbook)
                try { workbook.close() } catch (ignored: Exception) {}
                outcome
            },
            onFailure = { error ->
                Log.e(TAG, "Failed downloading workbook for sequence: ${error.localizedMessage}")
                when (error) {
                    is IllegalArgumentException -> ProjectionResult.Failure.InvalidUrl
                    is SecurityException -> {
                        if (error.message?.contains("size", ignoreCase = true) == true) {
                            ProjectionResult.Failure.FileTooLarge
                        } else {
                            ProjectionResult.Failure.NetworkError
                        }
                    }
                    else -> ProjectionResult.Failure.NetworkError
                }
            }
        )
    }

    // Saves it under the currentWorkingDate
    suspend fun fetchAndSaveProjection(
        settings: UniversalSettings,
        viewModel: TableViewModel
    ): ProjectionResult {
        val date = viewModel.currentWorkingDate ?: return ProjectionResult.Failure.DateTabNotFound("empty")
        val sourceUrl = settings.spreadsheetUrl
        if (sourceUrl.isBlank()) {
            return ProjectionResult.Failure.InvalidUrl
        }

        return try {
            val result = withContext(Dispatchers.IO) {
                getProjectionForDate(
                    dateStr = date,
                    targetCellCoordinate = settings.targetCellCoordinate,
                    link = sourceUrl
                )
            }

            if (result is ProjectionResult.Success) {
                val generatedMap = mutableMapOf<Int, Int?>()
                var currentHour = settings.workplaceOpeningTime

                result.data.forEach { value ->
                    generatedMap[currentHour] = value
                    currentHour = (currentHour + 1) % 24
                }

                // If local overrides aren't initialized yet, deduce day tracking defaults
                if (viewModel.projectedGcs.isEmpty()) {
                    val resolvedLocalDate = TimeUtils.getClosestFullDate(date) ?: LocalDate.now()
                    val isWeekend = resolvedLocalDate.dayOfWeek == DayOfWeek.SATURDAY ||
                            resolvedLocalDate.dayOfWeek == DayOfWeek.SUNDAY
                    viewModel.setDayTypeOverride(isWeekend)
                }

                viewModel.saveProjection(generatedMap)
            }

            result
        } catch (e: Exception) {
            e.printStackTrace()
            ProjectionResult.Failure.Unknown(e.localizedMessage ?: "Unhandled Runtime Error")
        }
    }

    suspend fun downloadAndSaveAllProjections(
        settings: UniversalSettings,
        viewModel: TableViewModel,
        maxDays: Int = 60
    ): ProjectionResult {
        val sourceUrl = settings.spreadsheetUrl
        if (sourceUrl.isBlank()) {
            return ProjectionResult.Failure.InvalidUrl
        }

        return withContext(Dispatchers.IO) {
            try {
                // 1. Download the workbook using your secure extractor engine
                val workbookResult = downloadSecureWorkbook(sourceUrl)

                val workbook = workbookResult.getOrElse { exception ->
                    Log.e("Downloader", "Secure download pipeline rejected workbook access", exception)
                    return@withContext ProjectionResult.Failure.Unknown(
                        exception.localizedMessage ?: "Secure Network Download Error"
                    )
                }

                var savedCount = 0
                val today = LocalDate.now()
                val storageDateFormatter = StorageManager.storageDateFormatter()

                // 2. Walk forward from today up to maxDays using Long steps
                for (daysAhead in 0L..maxDays.toLong()) {
                    val walkingDate = today.plusDays(daysAhead)

                    // Convert our loop instance date into target sheet matching format: "DD.MM"
                    val sheetTabName = String.format("%02d.%02d", walkingDate.dayOfMonth, walkingDate.monthValue)
                    val storageKeyName = walkingDate.format(storageDateFormatter)

                    // Check if this sheet exists in the workbook. If not, proceed to the next one
                    val sheetExists = workbook.getSheet(sheetTabName) != null
                    if (!sheetExists) {
                        continue
                    }

                    // 3. Extract the hourly array data from the workbook sheet using your core logic
                    val extractionResult = getProjectionForDate(
                        dateStr = storageKeyName,
                        targetCellCoordinate = settings.targetCellCoordinate,
                        workbook = workbook
                    )

                    if (extractionResult is ProjectionResult.Success) {
                        val rawList = extractionResult.data // List<Int?>

                        // Criteria check: Only save if the extracted data is not completely empty/null
                        val hasValidData = rawList.any { it != null }
                        if (hasValidData) {

                            // Convert the extracted List<Int?> sequence directly to hourly map positions
                            val hourMap = mutableMapOf<Int, Int?>()
                            var currentHour = settings.workplaceOpeningTime

                            rawList.forEach { value ->
                                hourMap[currentHour] = value
                                currentHour = (currentHour + 1) % 24
                            }

                            // Derive weekend properties based on structural date components
                            val isWeekend = walkingDate.dayOfWeek == DayOfWeek.SATURDAY ||
                                    walkingDate.dayOfWeek == DayOfWeek.SUNDAY

                            val finalProjection = DayProjectionData(
                                isWeekend = isWeekend,
                                hourlyGcs = hourMap
                            )

                            // Save file onto disk storage media inside its respective date tree partition
                            viewModel.storageManager.saveProjectionToDisk(finalProjection, storageKeyName)
                            savedCount++
                        }
                    }
                }

                workbook.close()
                Log.d("Downloader", "Batch processing finished. Saved $savedCount operational documents.")

                // Satisfies your explicit List<Int?> Success type contract safely
                ProjectionResult.Success(emptyList())

            } catch (e: Exception) {
                e.printStackTrace()
                ProjectionResult.Failure.Unknown(e.localizedMessage ?: "Batch Download Failure")
            }
        }
    }
}
