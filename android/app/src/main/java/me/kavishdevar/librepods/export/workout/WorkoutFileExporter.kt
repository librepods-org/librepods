/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

package me.kavishdevar.librepods.export.workout

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import me.kavishdevar.librepods.BuildConfig
import me.kavishdevar.librepods.data.workout.WorkoutDetail
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object WorkoutFileExporter {
    data class ExportedFile(val file: File, val mimeType: String)

    fun exportCsv(context: Context, workout: WorkoutDetail): ExportedFile {
        val file = exportFile(context, workout, "csv")
        file.writeText(WorkoutCsvEncoder.encode(workout), Charsets.UTF_8)
        return ExportedFile(file, "text/csv")
    }

    fun exportFit(context: Context, workout: WorkoutDetail): ExportedFile {
        val file = exportFile(context, workout, "fit")
        file.writeBytes(FitActivityEncoder.encode(workout))
        return ExportedFile(file, "application/octet-stream")
    }

    fun share(context: Context, exported: ExportedFile) {
        val uri = FileProvider.getUriForFile(
            context,
            "${BuildConfig.APPLICATION_ID}.provider",
            exported.file,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = exported.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(exported.file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Export workout"))
    }

    internal fun sanitizeFilename(value: String): String = value
        .replace(Regex("[^A-Za-z0-9._-]+"), "_")
        .trim('_', '.')
        .take(96)
        .ifBlank { "workout" }

    private fun exportFile(context: Context, workout: WorkoutDetail, extension: String): File {
        val dir = File(context.cacheDir, "workout-exports").apply { mkdirs() }
        val instant = Instant.ofEpochMilli(workout.summary.startTimeEpochMillis)
        val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneId.systemDefault())
            .format(instant)
        val idSuffix = sanitizeFilename(workout.summary.id).take(12)
        val name = sanitizeFilename("LibrePods-workout-$timestamp-$idSuffix.${extension.lowercase()}")
        return File(dir, name)
    }
}
