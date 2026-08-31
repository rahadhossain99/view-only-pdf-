package com.example.data.storage

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PdfStorageHelper {

    fun generateFileName(customTitle: String? = null): String {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val cleanTitle = customTitle?.trim()?.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            ?.take(40)
        return if (!cleanTitle.isNullOrBlank()) {
            "Drive_${cleanTitle}_$timeStamp.pdf"
        } else {
            "DriveDoc_$timeStamp.pdf"
        }
    }

    /**
     * Saves PDF bytes into public Downloads directory via MediaStore (Scoped Storage)
     * and returns the public Uri.
     */
    fun savePdfToDownloads(
        context: Context,
        pdfBytes: ByteArray,
        fileName: String
    ): Result<Uri> {
        return runCatching {
            val resolver = context.contentResolver

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DrivePDFs")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }

                val collectionUri = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val itemUri = resolver.insert(collectionUri, contentValues)
                    ?: throw IllegalStateException("Failed to create MediaStore entry for Downloads")

                resolver.openOutputStream(itemUri)?.use { outputStream ->
                    outputStream.write(pdfBytes)
                    outputStream.flush()
                } ?: throw IllegalStateException("Failed to open output stream for $itemUri")

                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(itemUri, contentValues, null, null)

                itemUri
            } else {
                // Legacy Android (API < 29)
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val targetDir = File(downloadsDir, "DrivePDFs")
                if (!targetDir.exists()) {
                    targetDir.mkdirs()
                }
                val destFile = File(targetDir, fileName)
                FileOutputStream(destFile).use { fos ->
                    fos.write(pdfBytes)
                    fos.flush()
                }
                Uri.fromFile(destFile)
            }
        }
    }

    /**
     * Also saves a cached copy for fast preview/in-app opening via FileProvider
     */
    fun saveToAppCache(context: Context, pdfBytes: ByteArray, fileName: String): File {
        val cacheDir = File(context.cacheDir, "saved_pdfs")
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        val file = File(cacheDir, fileName)
        FileOutputStream(file).use { fos ->
            fos.write(pdfBytes)
            fos.flush()
        }
        return file
    }

    fun openPdf(context: Context, uri: Uri, fallbackFile: File? = null) {
        val targetUri = try {
            if (uri.scheme == "content") {
                uri
            } else if (fallbackFile != null && fallbackFile.exists()) {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.provider",
                    fallbackFile
                )
            } else {
                uri
            }
        } catch (e: Exception) {
            uri
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(targetUri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val chooser = Intent.createChooser(intent, "Open PDF with").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        context.startActivity(chooser)
    }

    fun sharePdf(context: Context, uri: Uri, fallbackFile: File? = null, title: String = "Share PDF") {
        val targetUri = try {
            if (uri.scheme == "content") {
                uri
            } else if (fallbackFile != null && fallbackFile.exists()) {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.provider",
                    fallbackFile
                )
            } else {
                uri
            }
        } catch (e: Exception) {
            uri
        }

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, targetUri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooser = Intent.createChooser(shareIntent, "Share PDF document via").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        context.startActivity(chooser)
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        val index = digitGroups.coerceIn(0, units.size - 1)
        val value = bytes / Math.pow(1024.0, index.toDouble())
        return String.format(Locale.US, "%.1f %s", value, units[index])
    }
}
