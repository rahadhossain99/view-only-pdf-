package com.example.data.storage

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PdfStorageHelper {

    fun generateFileName(customTitle: String? = null): String {
        val cleaned = customTitle
            ?.trim()
            ?.removeSuffix(".pdf")
            ?.removeSuffix(".PDF")
            ?.replace(Regex("[/\\\\:*?\"<>|\\r\\n\\t]"), "_")
            ?.trim()
            ?.take(65)

        return if (!cleaned.isNullOrBlank()) {
            "$cleaned.pdf"
        } else {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            "SketchPDF_$timeStamp.pdf"
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
            val safeName = if (fileName.endsWith(".pdf", ignoreCase = true)) fileName else "$fileName.pdf"
            val resolver = context.contentResolver

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
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
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) {
                    downloadsDir.mkdirs()
                }
                val destFile = File(downloadsDir, safeName)
                FileOutputStream(destFile).use { fos ->
                    fos.write(pdfBytes)
                    fos.flush()
                }
                Uri.fromFile(destFile)
            }
        }
    }

    /**
     * Saves a cached copy for fast compression, renaming, and FileProvider opening.
     */
    fun saveToAppCache(context: Context, pdfBytes: ByteArray, fileName: String): File {
        val safeName = if (fileName.endsWith(".pdf", ignoreCase = true)) fileName else "$fileName.pdf"
        val cacheDir = File(context.cacheDir, "saved_pdfs")
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        val file = File(cacheDir, safeName)
        FileOutputStream(file).use { fos ->
            fos.write(pdfBytes)
            fos.flush()
        }
        return file
    }

    /**
     * Renames an existing saved PDF in both Downloads (MediaStore) and local cache.
     */
    fun renameSavedPdf(
        context: Context,
        currentUri: Uri,
        localPath: String?,
        newTitle: String
    ): Pair<Uri, String> {
        val newFileName = generateFileName(newTitle)
        var updatedUri = currentUri

        try {
            if (currentUri.scheme == "content" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, newFileName)
                }
                val rows = context.contentResolver.update(currentUri, values, null, null)
                if (rows <= 0 && localPath != null && File(localPath).exists()) {
                    val bytes = File(localPath).readBytes()
                    updatedUri = savePdfToDownloads(context, bytes, newFileName).getOrDefault(currentUri)
                }
            } else if (localPath != null && File(localPath).exists()) {
                val bytes = File(localPath).readBytes()
                updatedUri = savePdfToDownloads(context, bytes, newFileName).getOrDefault(currentUri)
            }
        } catch (e: Exception) {
            if (localPath != null && File(localPath).exists()) {
                val bytes = File(localPath).readBytes()
                updatedUri = savePdfToDownloads(context, bytes, newFileName).getOrDefault(currentUri)
            }
        }

        return Pair(updatedUri, newFileName)
    }

    fun buildOpenInFilesIntent(context: Context, uri: Uri, fallbackFile: File? = null): Intent {
        val downloadsIntent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pm = context.packageManager
        return if (downloadsIntent.resolveActivity(pm) != null) {
            downloadsIntent
        } else {
            buildOpenPdfIntent(context, uri, fallbackFile)
        }
    }

    fun buildOpenPdfIntent(context: Context, uri: Uri, fallbackFile: File? = null): Intent {
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

        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(targetUri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Takes the user directly into the Android system Files / Downloads folder.
     */
    fun openInFiles(context: Context, uri: Uri, fallbackFile: File? = null) {
        try {
            val downloadsIntent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(downloadsIntent)
        } catch (e: Exception) {
            try {
                openPdf(context, uri, fallbackFile)
            } catch (ex: Exception) {
                Toast.makeText(context, "Saved in Downloads folder", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun openPdf(context: Context, uri: Uri, fallbackFile: File? = null) {
        try {
            val intent = buildOpenPdfIntent(context, uri, fallbackFile)
            val chooser = Intent.createChooser(intent, "Open PDF").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            openInFiles(context, uri, fallbackFile)
        }
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

        val chooser = Intent.createChooser(shareIntent, "Share PDF via").apply {
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
