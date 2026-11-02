package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.data.storage.PdfStorageHelper
import java.io.File

object DownloadNotificationHelper {

    private const val CHANNEL_ID = "sketch_pdf_download_channel"
    private const val NOTIFICATION_ID_PROGRESS = 2101
    private const val NOTIFICATION_ID_COMPLETE = 2102

    private fun ensureChannel(context: Context): NotificationManager {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "PDF Download Progress",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Shows live percentage and Open in Files action for PDF downloads"
                setShowBadge(true)
            }
            manager.createNotificationChannel(channel)
        }
        return manager
    }

    fun showProgress(
        context: Context,
        docName: String,
        percent: Int,
        detailText: String
    ) {
        try {
            val manager = ensureChannel(context)
            val safePercent = percent.coerceIn(0, 100)

            val appIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingApp = PendingIntent.getActivity(
                context,
                10,
                appIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("$safePercent% • ${docName.ifBlank { "Downloading PDF..." }}")
                .setContentText(detailText)
                .setProgress(100, safePercent, safePercent <= 2)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pendingApp)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

            manager.notify(NOTIFICATION_ID_PROGRESS, notification)
        } catch (_: Exception) {}
    }

    fun showCompleted(
        context: Context,
        fileName: String,
        pageCount: Int,
        fileSizeBytes: Long,
        uri: Uri,
        cacheFile: File?
    ) {
        try {
            val manager = ensureChannel(context)
            manager.cancel(NOTIFICATION_ID_PROGRESS)

            val openInFilesIntent = PdfStorageHelper.buildOpenInFilesIntent(context, uri, cacheFile)
            val openInFilesPending = PendingIntent.getActivity(
                context,
                21,
                openInFilesIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val openPdfIntent = Intent.createChooser(
                PdfStorageHelper.buildOpenPdfIntent(context, uri, cacheFile),
                "Open PDF"
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val openPdfPending = PendingIntent.getActivity(
                context,
                22,
                openPdfIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val sizeFormatted = PdfStorageHelper.formatFileSize(fileSizeBytes)

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("100% Complete • $fileName")
                .setContentText("$pageCount pages ($sizeFormatted) • Tap Open to view in Files")
                .setAutoCancel(true)
                .setContentIntent(openInFilesPending)
                .addAction(android.R.drawable.ic_menu_view, "Open in Files", openInFilesPending)
                .addAction(android.R.drawable.ic_menu_share, "Open PDF", openPdfPending)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()

            manager.notify(NOTIFICATION_ID_COMPLETE, notification)
        } catch (_: Exception) {}
    }

    fun cancelProgress(context: Context) {
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(NOTIFICATION_ID_PROGRESS)
        } catch (_: Exception) {}
    }
}
