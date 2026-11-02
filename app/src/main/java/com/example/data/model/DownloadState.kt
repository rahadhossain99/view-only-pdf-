package com.example.data.model

import android.net.Uri
import java.util.UUID

enum class ResolutionQuality(val label: String, val paramSuffix: String, val width: Int) {
    STANDARD("Standard (1200p)", "w1200", 1200),
    HIGH("High (2000p)", "w2000", 2000),
    ULTRA("Ultra HD (3200p)", "w3200", 3200)
}

enum class ExtractionEngineMode(
    val title: String,
    val shortBadge: String,
    val description: String
) {
    AUTO_HYBRID(
        title = "All-in-One Hybrid (Auto)",
        shortBadge = "Hybrid 4-Tier",
        description = "Runs Network/Blob stream hook, Canvas/DOM extractor, High-DPI tile upgrader & Precision Page Scroller together"
    ),
    STREAM_BLOB_INTERCEPTOR(
        title = "Direct Stream & Blob Interceptor",
        shortBadge = "Stream / Blob",
        description = "Intercepts raw PDF binary streams, Fetch/XHR buffers, and blob: object URLs directly from browser memory"
    ),
    HIGH_RES_CANVAS_DOM(
        title = "High-Res Canvas & DOM Extractor",
        shortBadge = "Canvas & DOM",
        description = "Extracts HTML5 <canvas> bitmaps and upgrades Google Drive viewer image tiles to =w3200 Ultra HD"
    ),
    PRECISION_PAGE_SCROLLER(
        title = "Precision Page-by-Page Scroller",
        shortBadge = "Precision Scroll",
        description = "Scrolls to exact DOM page bounding boxes, hides toolbars, and captures real-size page screenshots without splits"
    )
}

enum class WorkspaceTab(val label: String) {
    EXTRACTOR("Extractor"),
    PDF_STUDIO("PDF Studio"),
    LIBRARY("Library")
}

data class EngineTelemetry(
    val activeMode: ExtractionEngineMode = ExtractionEngineMode.AUTO_HYBRID,
    val activeTierName: String = "Idle",
    val interceptedStreams: Int = 0,
    val canvasHooksCaptured: Int = 0,
    val domTilesDiscovered: Int = 0,
    val precisionScreenshotsCaptured: Int = 0,
    val totalDetectedPages: Int = 0,
    val lastLogMessage: String = "Ready to extract protected PDF"
)

data class PdfPageEditItem(
    val id: String = UUID.randomUUID().toString(),
    val originalPageIndex: Int,
    val rotationDegrees: Int = 0,
    val isDeleted: Boolean = false,
    val enhanceContrast: Boolean = false
)

sealed class DownloadState {
    data object Idle : DownloadState()

    data class LoadingWebView(
        val url: String,
        val message: String = "Initializing multi-engine browser..."
    ) : DownloadState()

    data class InterceptingPages(
        val capturedCount: Int,
        val estimatedTotal: Int,
        val latestPageUrl: String? = null,
        val message: String = "Scanning and intercepting pages..."
    ) : DownloadState()

    data class DownloadingImages(
        val current: Int,
        val total: Int,
        val percent: Float,
        val message: String = "Capturing high-resolution pages..."
    ) : DownloadState()

    data class CompilingPdf(
        val currentPage: Int,
        val totalPages: Int,
        val percent: Float,
        val message: String = "Compiling native A4 PDF document..."
    ) : DownloadState()

    data class Success(
        val uri: Uri,
        val fileName: String,
        val pageCount: Int,
        val fileSizeBytes: Long,
        val localPath: String? = null
    ) : DownloadState()

    data class Error(
        val message: String,
        val details: String? = null
    ) : DownloadState()
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
    val localPath: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)
