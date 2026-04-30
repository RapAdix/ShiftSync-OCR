package com.example.workflowocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.opencv.core.Point
import java.io.File
import java.io.FileOutputStream

class StorageManager(private val context: Context) {
    // Configured to ignore unknown keys - in case fields are added to ProcessorRow later
    private val json = Json { ignoreUnknownKeys = true }

    fun saveRowsToDisk(rows: Map<String, ProcessorRow>, subDir: String?) {
        if (subDir.isNullOrBlank()) {
            Log.d("Storage", "Save aborted: subDir is null or blank.")
            return
        }

        try {
            val folder = File(context.filesDir, "data/$subDir")
            if (!folder.exists()) folder.mkdirs()

            val file = File(folder, "rows.json")
            val jsonString = json.encodeToString(rows.values.toList())
            file.writeText(jsonString)
        } catch (e: Exception) {
            Log.e("Storage", "Error saving rows to $subDir", e)
        }
    }

    fun loadRowsFromDisk(subDir: String): Map<String, ProcessorRow> {
        val file = File(context.filesDir, "data/$subDir/rows.json")
        if (!file.exists()) return emptyMap()

        return try {
            val list: List<ProcessorRow> = json.decodeFromString(file.readText())
            list.associateBy { it.id }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun getAvailableDates(): List<String> {
        val dataDir = File(context.filesDir, "data")
        if (!dataDir.exists() || !dataDir.isDirectory) return emptyList()

        return dataDir.listFiles { file -> file.isDirectory }
            ?.map { it.name }
            ?.sortedWith(
                compareByDescending<String> { it.split("-").getOrNull(1)?.toIntOrNull() ?: 0 } // Month
                    .thenByDescending { it.split("-").getOrNull(0)?.toIntOrNull() ?: 0 } // Day
            ) ?: emptyList()
    }

    companion object {
        fun createSnippets(
            context: Context,
            bitmap: Bitmap,
            table: Array<Array<TableDetector.TableCell>>,
            date: String
        ): Map<Int, Map<String, String?>> {
            val rowPaths = mutableMapOf<Int, Map<String, String?>>()
            val timestamp = System.currentTimeMillis()

            // detection.cells holds the coordinates for every cell
            for (i in table.indices) {
                val cells = table[i]

                // 1. Name Snippet (Column 0)
                val namePath = saveSnippet(
                    context = context,
                    bitmap = bitmap,
                    p1 = cells[0].topLeft,
                    p2 = cells[0].topRight,
                    p3 = cells[0].bottomRight,
                    p4 = cells[0].bottomLeft,
                    fileName = "name_${timestamp}_$i",
                    subDir = date,
                    paddingFactor = -0.05f
                )

                // 2. Start Time Snippet (Column 2)
                val startPath = saveSnippet(
                    context = context,
                    bitmap = bitmap,
                    p1 = cells[2].topLeft,
                    p2 = cells[2].topRight,
                    p3 = cells[2].bottomRight,
                    p4 = cells[2].bottomLeft,
                    fileName = "start_${timestamp}_$i",
                    subDir = date,
                    paddingFactor = -0.05f
                )

                // 3. Finish Time Snippet (Column 3)
                val finishPath = saveSnippet(
                    context = context,
                    bitmap = bitmap,
                    p1 = cells[3].topLeft,
                    p2 = cells[3].topRight,
                    p3 = cells[3].bottomRight,
                    p4 = cells[3].bottomLeft,
                    fileName = "finish_${timestamp}_$i",
                    subDir = date,
                    paddingFactor = -0.05f
                )

                // 4. Modifications Snippet (The full/wide row context)

                val modsPath = saveSnippet(
                    context = context,
                    bitmap = bitmap,
                    p1 = cells[4].topLeft,
                    p2 = cells[4].topRight.move(40.0, 0.0),
                    p3 = cells[4].bottomRight.move(40.0, 0.0),
                    p4 = cells[4].bottomLeft,
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

        private fun saveSnippet(
            context: Context,
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

                val folder = File(context.filesDir, "extracted_snippets/$subDir")
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

        /**
         * Deletes the file at [oldPath] if it exists, and returns [newPath]
         * to be shifted into the old slot.
         */
        fun rotateFile(oldPath: String?, newPath: String?): String? {
            if (!oldPath.isNullOrEmpty() && oldPath != newPath) {
                val file = File(oldPath)
                if (file.exists()) {
                    file.delete()
                }
            }
            return newPath
        }
    }
}