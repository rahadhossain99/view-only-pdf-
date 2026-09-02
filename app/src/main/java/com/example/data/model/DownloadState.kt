package com.example.data.model

import android.net.Uri
import android.graphics.Bitmap
import java.util.UUID

sealed interface DownloadState {
    object Idle : DownloadState
    
    data class LoadingWebView(
        val url: String,
        val message: String = "Connecting to Google Drive viewer..."
    ) : DownloadState
    
    data class InterceptingPages(
        val capturedCount: Int,
        val estimatedTotal: Int,
        val message: String = "Capturing high-resolution document pages..."
    ) : DownloadState
    
    data class DownloadingImages(
        val current: Int,
        val total: Int,
        val percent: Float,
        val message: String = "Downloading page images..."
    ) : DownloadState
    
    data class CompilingPdf(
        val currentPage: Int,
        val totalPages: Int,
        val percent: Float,
        val message: String = "Compiling PDF document..."
    ) : DownloadState
    
    data class Success(
        val uri: Uri,
        val fileName: String,
        val pageCount: Int,
        val fileSizeBytes: Long,
        val localPath: String? = null
    ) : DownloadState
    
    data class Error(
        val message: String,
        val details: String? = null,
        val canRetry: Boolean = true
    ) : DownloadState
}

data class PageItem(
    val pageNumber: Int,
    val imageUrl: String,
    val highResUrl: String,
    val isDownloaded: Boolean = false
)

data class DownloadHistoryItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val uriString: String,
    val pageCount: Int,
    val fileSizeBytes: Long,
    val timestamp: Long = System.currentTimeMillis()
)

enum class ResolutionQuality(val label: String, val paramSuffix: String, val width: Int) {
    BALANCED("High (1600px)", "w1600", 1600),
    ULTRA("Ultra HD (2560px)", "w2560", 2560),
    MAXIMUM("Maximum (3200px)", "w3200", 3200)
}
