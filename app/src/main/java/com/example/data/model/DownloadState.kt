package com.example.data.model

import android.net.Uri
import java.util.UUID

enum class ResolutionQuality(
    val label: String,
    val shortTitle: String,
    val sizeHint: String,
    val paramSuffix: String,
    val width: Int,
    val jpegQuality: Int
) {
    STANDARD(
        label = "Lite (Fast & Small)",
        shortTitle = "Lite • Fast",
        sizeHint = "~1-4 MB",
        paramSuffix = "w1100",
        width = 1100,
        jpegQuality = 60
    ),
    HIGH(
        label = "Balanced (Recommended)",
        shortTitle = "Balanced",
        sizeHint = "~3-7 MB",
        paramSuffix = "w1450",
        width = 1450,
        jpegQuality = 74
    ),
    ULTRA(
        label = "HD Print (High Detail)",
        shortTitle = "HD Print",
        sizeHint = "~6-14 MB",
        paramSuffix = "w2000",
        width = 2000,
        jpegQuality = 85
    )
}

enum class PdfCompressionLevel(
    val title: String,
    val subtitle: String,
    val maxWidthPx: Int,
    val jpegQuality: Int
) {
    ULTRA_SMALL(
        title = "Ultra Small",
        subtitle = "~75% Smaller",
        maxWidthPx = 920,
        jpegQuality = 50
    ),
    MEDIUM(
        title = "Medium Size",
        subtitle = "~50% Smaller",
        maxWidthPx = 1220,
        jpegQuality = 66
    ),
    LIGHT(
        title = "High Clarity",
        subtitle = "~30% Smaller",
        maxWidthPx = 1520,
        jpegQuality = 78
    )
}

enum class ExtractionEngineMode(
    val title: String,
    val shortBadge: String,
    val description: String
) {
    AUTO_HYBRID(
        title = "All-in-One Hybrid (Auto)",
        shortBadge = "Auto Engine",
        description = "Automatic multi-engine extraction"
    ),
    STREAM_BLOB_INTERCEPTOR(
        title = "Direct Stream & Blob Interceptor",
        shortBadge = "Stream",
        description = "Direct stream capture"
    ),
    HIGH_RES_CANVAS_DOM(
        title = "High-Res Canvas & DOM Extractor",
        shortBadge = "Canvas",
        description = "Canvas & DOM extraction"
    ),
    PRECISION_PAGE_SCROLLER(
        title = "Precision Page-by-Page Scroller",
        shortBadge = "Precision",
        description = "Precision page screenshot capture"
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
    val lastLogMessage: String = "Ready"
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
        val message: String = "Connecting to Google Drive document..."
    ) : DownloadState()

    data class InterceptingPages(
        val capturedCount: Int,
        val estimatedTotal: Int,
        val latestPageUrl: String? = null,
        val message: String = "Detecting document pages..."
    ) : DownloadState()

    data class DownloadingImages(
        val current: Int,
        val total: Int,
        val percent: Float,
        val message: String = "Extracting pages..."
    ) : DownloadState()

    data class CompilingPdf(
        val currentPage: Int,
        val totalPages: Int,
        val percent: Float,
        val message: String = "Building compressed PDF..."
    ) : DownloadState()

    data class Success(
        val uri: Uri,
        val fileName: String,
        val pageCount: Int,
        val fileSizeBytes: Long,
        val localPath: String? = null,
        val previousSizeBytes: Long? = null
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
