package com.example.workflowocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.opencv.core.Point
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

private val Context.dataStore by preferencesDataStore(name = "app_preferences")

class StorageManager(private val context: Context) {
    private val PRESET_TYPE_KEY = stringPreferencesKey("preset_type")
    private val LAYOUT_JSON_KEY = stringPreferencesKey("layout_json")
    private val UNIVERSAL_JSON_KEY = stringPreferencesKey("universal_json")
    private val WEEKDAY_TABLE_KEY = stringPreferencesKey("vlh_weekday_table")
    private val WEEKEND_TABLE_KEY = stringPreferencesKey("vlh_weekend_table")

    // Configured to ignore unknown keys - in case fields are added to ProcessorRow later
    private val json = Json { ignoreUnknownKeys = true }

    init {
        // Clean up outdated tracking data instantly upon manager initialization
        try {
            purgeExpiredSensitiveData()
        } catch (e: Exception) {
            Log.e("Storage", "Initialization data purge failed", e)
        }
    }

    // Dynamic, safe reading pipeline
    val settingsStateFlow: Flow<Triple<PresetType, TableLayout, UniversalSettings>> = context.dataStore.data.map { preferences ->
        // 1. Parse Universal Settings (or use factory defaults if missing)
        val universalJson = preferences[UNIVERSAL_JSON_KEY]
        val universal = if (universalJson != null) {
            Json.decodeFromString<UniversalSettings>(universalJson)
        } else {
            UniversalSettings()
        }

        // 2. Parse Preset Type
        val presetTypeStr = preferences[PRESET_TYPE_KEY] ?: PresetType.DEFAULT_13_COL.name
        val presetType = PresetType.valueOf(presetTypeStr)

        // 3. Parse Layout
        val layout = when (presetType) {
            PresetType.DEFAULT_13_COL -> PresetDefaults.layout13Col
            PresetType.DEFAULT_12_COL -> PresetDefaults.layout12Col
            PresetType.CUSTOM -> {
                val layoutJson = preferences[LAYOUT_JSON_KEY]
                if (layoutJson != null) {
                    Json.decodeFromString<TableLayout>(layoutJson)
                } else {
                    PresetDefaults.layout13Col.copy(isCustom = true)
                }
            }
        }

        Triple(presetType, layout, universal)
    }

    suspend fun saveUniversalSettings(settings: UniversalSettings) {
        context.dataStore.edit { preferences ->
            preferences[UNIVERSAL_JSON_KEY] = Json.encodeToString(settings)
        }
    }

    // Conditional savings rules
    suspend fun saveLayoutPreset(type: PresetType, layout: TableLayout? = null) {
        context.dataStore.edit { preferences ->
            preferences[PRESET_TYPE_KEY] = type.name
            if (type == PresetType.CUSTOM && layout != null) {
                preferences[LAYOUT_JSON_KEY] = Json.encodeToString(layout)
            }
        }
    }

    val vlhTablesFlow: Flow<Pair<VlhTableState, VlhTableState>> = context.dataStore.data.map { preferences ->
        val weekdayJson = preferences[WEEKDAY_TABLE_KEY]
        val weekendJson = preferences[WEEKEND_TABLE_KEY]

        val weekdayData = if (weekdayJson != null) {
            json.decodeFromString<VlhTableState>(weekdayJson)
        } else {
            // Fallback factory generation defaults if disk sectors are unallocated
            VlhTableState(DayType.WEEKDAY)
        }

        val weekendData = if (weekendJson != null) {
            json.decodeFromString<VlhTableState>(weekendJson)
        } else {
            VlhTableState(DayType.WEEKEND)
        }

        Pair(weekdayData, weekendData)
    }

    // Asynchronous atomic write mechanics
    suspend fun saveVlhTable(tableData: VlhTableState) {
        context.dataStore.edit { preferences ->
            val targetKey = if (tableData.type == DayType.WEEKDAY) WEEKDAY_TABLE_KEY else WEEKEND_TABLE_KEY
            preferences[targetKey] = json.encodeToString(tableData)
        }
    }

    fun saveRowsToDisk(rows: Map<String, ProcessorRow>, subDir: String?) {
        if (subDir.isNullOrBlank()) {
            Log.d("Storage", "Save aborted: subDir is null or blank.")
            return
        }

        try {
            val folder = File(getDataDir(), subDir)
            if (!folder.exists()) folder.mkdirs()

            val file = File(folder, "rows.json")
            val jsonString = json.encodeToString(rows.values.toList())
            file.writeText(jsonString)
        } catch (e: Exception) {
            Log.e("Storage", "Error saving rows to $subDir", e)
        }
    }

    fun loadRowsFromDisk(subDir: String): Map<String, ProcessorRow> {
        val file = File(getDataDir(), "$subDir/rows.json")
        if (!file.exists()) return emptyMap()

        return try {
            val list: List<ProcessorRow> = json.decodeFromString(file.readText())
            list.associateBy { it.id }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun getAvailableDates(): List<String> {
        val dataDir = getDataDir()
        if (!dataDir.exists() || !dataDir.isDirectory) return emptyList()

        return dataDir.listFiles { file -> file.isDirectory }
            ?.map { it.name }
            ?.sortedWith(
                compareByDescending<String> { it.split("-").getOrNull(1)?.toIntOrNull() ?: 0 } // Month
                    .thenByDescending { it.split("-").getOrNull(0)?.toIntOrNull() ?: 0 } // Day
            ) ?: emptyList()
    }

    fun deleteDataForDate(date: String) {
        val folder = File(getDataDir(), date)
        if (folder.exists()) {
            folder.deleteRecursively() // Deletes folder, snippets, and json in one go
        }
    }

    /**
     * Parses all directory date keys and drops records falling outside retention bounds:
     * - Must not be older than yesterday.
     * - Must not look into the future further than 1 month.
     */
    private fun purgeExpiredSensitiveData() {
        val currentLocalDate = LocalDate.now()
        val availableDates = getAvailableDates() // Yields e.g., ["25-12", "05-01"]

        availableDates.forEach { dateStr ->
            val bestFitTrueDate = TimeUtils.getClosestFullDate(dateStr, currentLocalDate) ?: return@forEach

            // 2. Condition 1: Older than yesterday check rule validation
            // (Case Current: January; Date1: December
            val isOlderThanYesterday = bestFitTrueDate.isBefore(currentLocalDate.minusDays(1))

            // 3. Condition 2: Future boundary retention checking rules
            // (Case Current: December; Date1: January -> relative time must not exceed a 1-month span offset)
            val isMoreThanMonthInFuture = bestFitTrueDate.isAfter(currentLocalDate.plusMonths(1))

            // 4. Combined Evaluation Loop Core Execution
            if (isOlderThanYesterday || isMoreThanMonthInFuture) {
                Log.d("StoragePurge", "Purging expired data directory footprint: $dateStr (Resolved as: $bestFitTrueDate)")
                deleteDataForDate(dateStr)
            }
        }
    }

    fun createSnippets(
        bitmap: Bitmap,
        table: Array<Array<TableDetector.TableCell>>,
        date: String,
        settings: TableLayout
    ): Map<Int, Map<String, String?>> {
        val rowPaths = mutableMapOf<Int, Map<String, String?>>()
        val timestamp = System.currentTimeMillis()

        // detection.cells holds the coordinates for every cell
        for (i in table.indices) {
            val cells = table[i]

            // 1. Name Snippet (Column 0)
            val namePath = saveSnippet(
                bitmap = bitmap,
                p1 = cells[settings.nameCol].topLeft,
                p2 = cells[settings.nameCol].topRight,
                p3 = cells[settings.nameCol].bottomRight,
                p4 = cells[settings.nameCol].bottomLeft,
                fileName = "name_${timestamp}_$i",
                subDir = date,
                paddingFactor = -0.05f
            )

            // 2. Start Time Snippet (Column 2)
            val startPath = saveSnippet(
                bitmap = bitmap,
                p1 = cells[settings.timeStartCol].topLeft,
                p2 = cells[settings.timeStartCol].topRight,
                p3 = cells[settings.timeStartCol].bottomRight,
                p4 = cells[settings.timeStartCol].bottomLeft,
                fileName = "start_${timestamp}_$i",
                subDir = date,
                paddingFactor = -0.05f
            )

            // 3. Finish Time Snippet (Column 3)
            val finishPath = saveSnippet(
                bitmap = bitmap,
                p1 = cells[settings.timeEndCol].topLeft,
                p2 = cells[settings.timeEndCol].topRight,
                p3 = cells[settings.timeEndCol].bottomRight,
                p4 = cells[settings.timeEndCol].bottomLeft,
                fileName = "finish_${timestamp}_$i",
                subDir = date,
                paddingFactor = -0.05f
            )

            // 4. Modifications Snippet (The full/wide row context)

            val modsPath = saveSnippet(
                bitmap = bitmap,
                p1 = cells[settings.modificationColumns[0]].topLeft.move(0.0, -10.0),
                p2 = cells[settings.expectedCols - 1].topRight.move(20.0, 0.0),
                p3 = cells[settings.expectedCols - 1].bottomRight.move(20.0, 0.0),
                p4 = cells[settings.modificationColumns[0]].bottomLeft.move(0.0, 10.0),
                fileName = "mods_${timestamp}_$i",
                subDir = date,
                paddingFactor = 0.1f
            )

            rowPaths[i] = mapOf(
                "name" to namePath,
                "start" to startPath,
                "finish" to finishPath,
                "mods" to modsPath
            )
        }

        return rowPaths
    }

    private fun getDataDir(): File {
        return File(context.filesDir, "data")
    }

    private fun saveSnippet(
        bitmap: Bitmap,
        p1: Point, p2: Point, p3: Point, p4: Point,
        fileName: String,
        subDir: String,
        paddingFactor: Float = 0f
    ): String? {
        return try {
            val points = listOf(p1, p2, p3, p4)

            // 1. Inflate to Rectangle and FORCE to Int immediately
            val minX = points.minOf { it.x }.toInt()
            val maxX = points.maxOf { it.x }.toInt()
            val minY = points.minOf { it.y }.toInt()
            val maxY = points.maxOf { it.y }.toInt()

            val originalW = maxX - minX
            val originalH = maxY - minY

            // 2. Apply Custom Padding (Math stays Float, then converts to Int)
            val dx = (originalW * paddingFactor).toInt()
            val dy = (originalH * paddingFactor).toInt()

            // 3. Distribution (Stay in Int land)
            val paddedX = minX - (dx / 2)
            val paddedY = minY - (dy / 2)
            val paddedW = originalW + dx
            val paddedH = originalH + dy

            // 4. Safety Bounds (Now everything is Int, so this works)
            val finalX = paddedX.coerceIn(0, bitmap.width - 1)
            val finalY = paddedY.coerceIn(0, bitmap.height - 1)

            // Final width/height cannot exceed remaining space and must be at least 1px
            val finalW = paddedW.coerceIn(1, bitmap.width - finalX)
            val finalH = paddedH.coerceIn(1, bitmap.height - finalY)

            // 5. Success! The types now match Int perfectly
            val crop = Bitmap.createBitmap(bitmap, finalX, finalY, finalW, finalH)

            val folder = File(getDataDir(), subDir)
            if (!folder.exists()) folder.mkdirs()
            val file = File(folder, "$fileName.jpg")

            FileOutputStream(file).use { out ->
                crop.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }

            crop.recycle()
            file.absolutePath
        } catch (e: Exception) {
            Log.e("CROP_DEBUG", "Save failed for $fileName: ${e.message}", e)
            null
        }
    }

    companion object {
        /**
         * Deletes the file at [oldPath] if it exists, and returns [newPath]
         * to be shifted into the old slot.
         * Returns [oldPath] if [newPath] was empty.
         */
        fun rotateFile(oldPath: String?, newPath: String?): String? {
            if (newPath.isNullOrEmpty()) return oldPath
            if (!oldPath.isNullOrEmpty() && oldPath != newPath) {
                val file = File(oldPath)
                if (file.exists()) {
                    file.delete()
                }
            }
            return newPath
        }
    }

    object ImageUtils {
        fun createTempImageUri(context: Context): Uri {
            val tempFile = File.createTempFile("scan_", ".jpg", context.externalCacheDir)
            return FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider", // Must match AndroidManifest
                tempFile
            )
        }

        fun uriToBitmap(context: Context, uri: Uri): Bitmap {
            return if (Build.VERSION.SDK_INT < 28) {
                MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
            } else {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.isMutableRequired = true // Important for OpenCV/Processing
                }
            }
        }
    }
}
