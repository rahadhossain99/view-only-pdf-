package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.webkit.WebView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.DownloadHistoryItem
import com.example.data.model.DownloadState
import com.example.data.model.EngineTelemetry
import com.example.data.model.ExtractionEngineMode
import com.example.data.model.PageItem
import com.example.data.model.PdfPageEditItem
import com.example.data.model.ResolutionQuality
import com.example.data.model.WorkspaceTab
import com.example.data.storage.HistoryRepository
import com.example.data.storage.PdfStorageHelper
import com.example.engine.DriveExtractorEngine
import com.example.engine.PdfOrganizerEngine
import com.example.service.PdfDownloadService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val historyRepository = HistoryRepository(context)
    private val pdfOrganizerEngine = PdfOrganizerEngine(context)

    private var extractorEngine: DriveExtractorEngine? = null

    private val _activeWorkspaceTab = MutableStateFlow(WorkspaceTab.EXTRACTOR)
    val activeWorkspaceTab: StateFlow<WorkspaceTab> = _activeWorkspaceTab.asStateFlow()

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private val _discoveredPages = MutableStateFlow<List<PageItem>>(emptyList())
    val discoveredPages: StateFlow<List<PageItem>> = _discoveredPages.asStateFlow()

    private val _inputUrl = MutableStateFlow("")
    val inputUrl: StateFlow<String> = _inputUrl.asStateFlow()

    private val _resolutionQuality = MutableStateFlow(ResolutionQuality.ULTRA)
    val resolutionQuality: StateFlow<ResolutionQuality> = _resolutionQuality.asStateFlow()

    private val _engineMode = MutableStateFlow(ExtractionEngineMode.AUTO_HYBRID)
    val engineMode: StateFlow<ExtractionEngineMode> = _engineMode.asStateFlow()

    private val _enhanceContrast = MutableStateFlow(false)
    val enhanceContrast: StateFlow<Boolean> = _enhanceContrast.asStateFlow()

    private val _engineTelemetry = MutableStateFlow(EngineTelemetry())
    val engineTelemetry: StateFlow<EngineTelemetry> = _engineTelemetry.asStateFlow()

    private val _isInteractiveMode = MutableStateFlow(false)
    val isInteractiveMode: StateFlow<Boolean> = _isInteractiveMode.asStateFlow()

    private val _isScreenshotStudioOpen = MutableStateFlow(false)
    val isScreenshotStudioOpen: StateFlow<Boolean> = _isScreenshotStudioOpen.asStateFlow()

    private val _currentDocumentTitle = MutableStateFlow<String?>(null)
    val currentDocumentTitle: StateFlow<String?> = _currentDocumentTitle.asStateFlow()

    // Built-in PDF Viewer & Page Organizer Studio State
    private val _studioPdfFile = MutableStateFlow<File?>(null)
    val studioPdfFile: StateFlow<File?> = _studioPdfFile.asStateFlow()

    private val _studioDocumentTitle = MutableStateFlow("Document")
    val studioDocumentTitle: StateFlow<String> = _studioDocumentTitle.asStateFlow()

    private val _studioPageEdits = MutableStateFlow<List<PdfPageEditItem>>(emptyList())
    val studioPageEdits: StateFlow<List<PdfPageEditItem>> = _studioPageEdits.asStateFlow()

    private val _isStudioLoading = MutableStateFlow(false)
    val isStudioLoading: StateFlow<Boolean> = _isStudioLoading.asStateFlow()

    private val _studioExportMessage = MutableStateFlow<String?>(null)
    val studioExportMessage: StateFlow<String?> = _studioExportMessage.asStateFlow()

    val historyItems: StateFlow<List<DownloadHistoryItem>> = historyRepository.historyFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val quickCheckClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    init {
        viewModelScope.launch {
            PdfDownloadService.downloadState.collect { serviceState ->
                if (serviceState !is DownloadState.Idle) {
                    _downloadState.value = serviceState
                    if (serviceState is DownloadState.Success) {
                        preloadPdfForStudio(
                            uri = serviceState.uri,
                            localPath = serviceState.localPath,
                            title = serviceState.fileName.removeSuffix(".pdf")
                        )
                    }
                }
            }
        }
    }

    fun setWorkspaceTab(tab: WorkspaceTab) {
        _activeWorkspaceTab.value = tab
    }

    fun updateUrl(url: String) {
        _inputUrl.value = url
    }

    fun setResolution(quality: ResolutionQuality) {
        _resolutionQuality.value = quality
    }

    fun setEngineMode(mode: ExtractionEngineMode) {
        _engineMode.value = mode
        _engineTelemetry.value = _engineTelemetry.value.copy(
            activeMode = mode,
            lastLogMessage = "Selected ${mode.title}"
        )
    }

    fun setEnhanceContrast(enabled: Boolean) {
        _enhanceContrast.value = enabled
    }

    fun setInteractiveMode(enabled: Boolean) {
        _isInteractiveMode.value = enabled
    }

    fun setScreenshotStudioOpen(open: Boolean) {
        _isScreenshotStudioOpen.value = open
    }

    fun clearStudioExportMessage() {
        _studioExportMessage.value = null
    }

    fun startDownload(rawUrl: String = _inputUrl.value) {
        val cleanUrl = rawUrl.trim()
        if (cleanUrl.isBlank()) {
            _downloadState.value = DownloadState.Error("Please enter a valid Google Drive or PDF URL.")
            return
        }

        cancelDownload()
        PdfDownloadService.resetState()
        _discoveredPages.value = emptyList()
        _currentDocumentTitle.value = null
        _engineTelemetry.value = EngineTelemetry(
            activeMode = _engineMode.value,
            activeTierName = "Tier 1: Stream & Export Probe",
            lastLogMessage = "Probing direct stream & initializing ${_engineMode.value.shortBadge}..."
        )
        _downloadState.value = DownloadState.LoadingWebView(cleanUrl, "Connecting to document...")

        viewModelScope.launch {
            // Tier 1: Check if direct PDF export stream is available (for AUTO_HYBRID or STREAM_BLOB_INTERCEPTOR)
            val fileId = DriveExtractorEngine.extractFileId(cleanUrl)
            var directPdfUrl: String? = null

            if (fileId != null &&
                (_engineMode.value == ExtractionEngineMode.AUTO_HYBRID ||
                        _engineMode.value == ExtractionEngineMode.STREAM_BLOB_INTERCEPTOR)
            ) {
                directPdfUrl = checkDirectPdfExport(fileId)
            } else if (DriveExtractorEngine.isDirectPdfStreamUrl(cleanUrl)) {
                directPdfUrl = cleanUrl
            }

            if (directPdfUrl != null) {
                Log.d("MainViewModel", "Direct PDF export stream available! Starting direct download.")
                _engineTelemetry.value = _engineTelemetry.value.copy(
                    activeTierName = "Tier 1: Direct PDF Stream",
                    interceptedStreams = 1,
                    lastLogMessage = "Direct native PDF stream detected! Downloading directly..."
                )
                _downloadState.value = DownloadState.DownloadingImages(
                    current = 1,
                    total = 1,
                    percent = 0.25f,
                    message = "Direct PDF stream detected. Downloading native PDF..."
                )
                PdfDownloadService.startDirectPdfDownload(context, directPdfUrl, _currentDocumentTitle.value)
                return@launch
            }

            // Otherwise, start the 4-Tier Multi-Engine Browser Extractor
            startBrowserPageExtraction(cleanUrl)
        }
    }

    private suspend fun checkDirectPdfExport(fileId: String): String? = withContext(Dispatchers.IO) {
        try {
            val candidateUrl = "https://drive.google.com/uc?export=download&id=$fileId"
            val request = Request.Builder()
                .url(candidateUrl)
                .head()
                .addHeader("User-Agent", DriveExtractorEngine.DESKTOP_USER_AGENT)
                .build()

            val response = quickCheckClient.newCall(request).execute()
            val contentType = response.header("Content-Type") ?: ""
            response.close()

            if (response.isSuccessful && contentType.contains("application/pdf", ignoreCase = true)) {
                return@withContext candidateUrl
            }
        } catch (e: Exception) {
            Log.d("MainViewModel", "Direct check did not succeed, using multi-engine extractor: ${e.localizedMessage}")
        }
        return@withContext null
    }

    private var attachedExtractionWebView: WebView? = null

    fun getOrCreateExtractionWebView(ctx: Context): WebView {
        if (attachedExtractionWebView == null) {
            attachedExtractionWebView = WebView(ctx.applicationContext).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(1200, 1800)
            }
        }
        return attachedExtractionWebView!!
    }

    private fun startBrowserPageExtraction(cleanUrl: String) {
        val engine = DriveExtractorEngine(context, viewModelScope)
        extractorEngine = engine

        engine.onTelemetryUpdate = { telemetry ->
            _engineTelemetry.value = telemetry
        }

        engine.onStatusUpdate = { msg ->
            val currentState = _downloadState.value
            if (currentState is DownloadState.LoadingWebView) {
                _downloadState.value = currentState.copy(message = msg)
            } else if (currentState is DownloadState.InterceptingPages) {
                _downloadState.value = currentState.copy(message = msg)
            } else if (currentState is DownloadState.DownloadingImages) {
                _downloadState.value = currentState.copy(message = msg)
            }
        }

        engine.onTitleExtracted = { title ->
            _currentDocumentTitle.value = title
        }

        engine.onTotalPagesDetected = { total ->
            val currentState = _downloadState.value
            if (currentState is DownloadState.InterceptingPages) {
                _downloadState.value = currentState.copy(
                    estimatedTotal = maxOf(currentState.estimatedTotal, total),
                    message = "Scanning document pages (Total: $total detected)..."
                )
            }
        }

        engine.onPageDiscovered = { pageNum, url, _ ->
            val currentList = _discoveredPages.value.toMutableList()
            if (currentList.none { it.pageNumber == pageNum }) {
                currentList.add(
                    PageItem(
                        pageNumber = pageNum,
                        imageUrl = url,
                        highResUrl = url
                    )
                )
                currentList.sortBy { it.pageNumber }
                _discoveredPages.value = currentList
            }
        }

        engine.onPageCaptured = { pageNum, total, msg ->
            _downloadState.value = DownloadState.DownloadingImages(
                current = pageNum,
                total = total,
                percent = pageNum.toFloat() / total.coerceAtLeast(1),
                message = msg
            )
        }

        engine.onCompilingPdf = { current, total, percent, msg ->
            _downloadState.value = DownloadState.CompilingPdf(
                currentPage = current,
                totalPages = total,
                percent = percent,
                message = msg
            )
        }

        engine.onPdfReady = { uri, fileName, pageCount, fileSizeBytes, localPath ->
            _downloadState.value = DownloadState.Success(
                uri = uri,
                fileName = fileName,
                pageCount = pageCount,
                fileSizeBytes = fileSizeBytes,
                localPath = localPath
            )
            preloadPdfForStudio(uri, localPath, fileName.removeSuffix(".pdf"))
            extractorEngine = null
        }

        engine.onExtractionCompleted = { pageUrls, title, baseTemplateUrl ->
            val docTitle = title ?: _currentDocumentTitle.value
            _currentDocumentTitle.value = docTitle

            if (pageUrls.size > 1) {
                _downloadState.value = DownloadState.DownloadingImages(
                    current = 1,
                    total = pageUrls.size,
                    percent = 0.05f,
                    message = "Downloading ${pageUrls.size} high-resolution page tiles..."
                )
                PdfDownloadService.startDownloadWithUrls(
                    context = context,
                    pageUrls = pageUrls,
                    docTitle = docTitle,
                    resolution = _resolutionQuality.value
                )
            } else if (!baseTemplateUrl.isNullOrBlank()) {
                _downloadState.value = DownloadState.CompilingPdf(
                    currentPage = 1,
                    totalPages = 1,
                    percent = 0f,
                    message = "Starting resilient smart page probe..."
                )
                PdfDownloadService.startProbeDownload(
                    context = context,
                    firstImageUrl = baseTemplateUrl,
                    docTitle = docTitle,
                    resolution = _resolutionQuality.value
                )
            } else {
                _downloadState.value = DownloadState.Error(
                    message = "No pages could be extracted from this document.",
                    details = "Try opening Interactive Mode to sign in or verify document permissions."
                )
            }

            engine.destroy()
            extractorEngine = null
        }

        engine.onError = { errorMsg ->
            _downloadState.value = DownloadState.Error(
                message = errorMsg,
                details = "Make sure the link is accessible or open Interactive Mode to sign in."
            )
            engine.destroy()
            extractorEngine = null
        }

        engine.startExtraction(
            url = cleanUrl,
            resolution = _resolutionQuality.value,
            engineMode = _engineMode.value,
            contrastEnhance = _enhanceContrast.value,
            existingWebView = attachedExtractionWebView
        )
    }

    fun forceFinishExtraction() {
        extractorEngine?.forceCompileNow()
    }

    fun startDownloadWithPages(pageUrls: List<String>, title: String?) {
        if (pageUrls.isEmpty()) return
        cancelDownload()
        val docTitle = title ?: _currentDocumentTitle.value
        _currentDocumentTitle.value = docTitle
        _downloadState.value = DownloadState.DownloadingImages(
            current = 1,
            total = pageUrls.size,
            percent = 0.05f,
            message = "Starting download of ${pageUrls.size} document pages..."
        )
        PdfDownloadService.startDownloadWithUrls(
            context = context,
            pageUrls = pageUrls,
            docTitle = docTitle,
            resolution = _resolutionQuality.value
        )
    }

    fun cancelDownload() {
        extractorEngine?.destroy()
        extractorEngine = null
        PdfDownloadService.cancelDownload(context)
        PdfDownloadService.resetState()
        _downloadState.value = DownloadState.Idle
    }

    // --- Built-in PDF Viewer & Page Organizer Studio Methods ---

    private fun preloadPdfForStudio(uri: Uri?, localPath: String?, title: String) {
        viewModelScope.launch {
            val resolvedFile = pdfOrganizerEngine.resolveSeekableFile(uri, localPath) ?: return@launch
            val pageCount = pdfOrganizerEngine.getPageCount(resolvedFile)
            if (pageCount > 0) {
                _studioPdfFile.value = resolvedFile
                _studioDocumentTitle.value = title
                _studioPageEdits.value = (0 until pageCount).map { idx ->
                    PdfPageEditItem(
                        originalPageIndex = idx,
                        rotationDegrees = 0,
                        isDeleted = false,
                        enhanceContrast = false
                    )
                }
            }
        }
    }

    fun openPdfInStudio(uri: Uri?, localPath: String?, title: String) {
        _activeWorkspaceTab.value = WorkspaceTab.PDF_STUDIO
        _isStudioLoading.value = true
        _studioExportMessage.value = null

        viewModelScope.launch {
            val resolvedFile = pdfOrganizerEngine.resolveSeekableFile(uri, localPath)
            if (resolvedFile != null) {
                val pageCount = pdfOrganizerEngine.getPageCount(resolvedFile)
                _studioPdfFile.value = resolvedFile
                _studioDocumentTitle.value = title.removeSuffix(".pdf")
                _studioPageEdits.value = (0 until pageCount).map { idx ->
                    PdfPageEditItem(
                        originalPageIndex = idx,
                        rotationDegrees = 0,
                        isDeleted = false,
                        enhanceContrast = false
                    )
                }
            }
            _isStudioLoading.value = false
        }
    }

    suspend fun renderStudioPageBitmap(
        pageIndex: Int,
        targetWidthPx: Int = 900,
        rotationDegrees: Int = 0,
        enhanceContrast: Boolean = false
    ): Bitmap? {
        val file = _studioPdfFile.value ?: return null
        return pdfOrganizerEngine.renderPage(
            pdfFile = file,
            pageIndex = pageIndex,
            targetWidthPx = targetWidthPx,
            rotationDegrees = rotationDegrees,
            enhanceContrast = enhanceContrast
        )
    }

    fun rotateStudioPage(pageId: String) {
        _studioPageEdits.value = _studioPageEdits.value.map { item ->
            if (item.id == pageId) {
                item.copy(rotationDegrees = (item.rotationDegrees + 90) % 360)
            } else {
                item
            }
        }
    }

    fun moveStudioPage(currentIndex: Int, delta: Int) {
        val list = _studioPageEdits.value.toMutableList()
        val targetIndex = currentIndex + delta
        if (currentIndex in list.indices && targetIndex in list.indices) {
            val item = list.removeAt(currentIndex)
            list.add(targetIndex, item)
            _studioPageEdits.value = list
        }
    }

    fun toggleDeleteStudioPage(pageId: String) {
        val activeCount = _studioPageEdits.value.count { !it.isDeleted }
        _studioPageEdits.value = _studioPageEdits.value.map { item ->
            if (item.id == pageId) {
                // Prevent deleting the very last remaining page
                if (!item.isDeleted && activeCount <= 1) item else item.copy(isDeleted = !item.isDeleted)
            } else {
                item
            }
        }
    }

    fun toggleContrastStudioPage(pageId: String) {
        _studioPageEdits.value = _studioPageEdits.value.map { item ->
            if (item.id == pageId) {
                item.copy(enhanceContrast = !item.enhanceContrast)
            } else {
                item
            }
        }
    }

    fun toggleAllStudioContrast() {
        val currentList = _studioPageEdits.value
        val allEnhanced = currentList.isNotEmpty() && currentList.all { it.enhanceContrast }
        _studioPageEdits.value = currentList.map { it.copy(enhanceContrast = !allEnhanced) }
    }

    fun resetStudioEdits() {
        val file = _studioPdfFile.value ?: return
        viewModelScope.launch {
            val count = pdfOrganizerEngine.getPageCount(file)
            _studioPageEdits.value = (0 until count).map { idx ->
                PdfPageEditItem(originalPageIndex = idx)
            }
            _studioExportMessage.value = null
        }
    }

    fun exportEditedStudioPdf() {
        val srcFile = _studioPdfFile.value ?: return
        val edits = _studioPageEdits.value
        val keptCount = edits.count { !it.isDeleted }
        if (keptCount == 0) return

        _isStudioLoading.value = true
        _studioExportMessage.value = "Exporting $keptCount organized pages to native A4 PDF..."

        viewModelScope.launch {
            try {
                val pdfBytes = pdfOrganizerEngine.exportEditedPdf(srcFile, edits) { cur, total ->
                    _studioExportMessage.value = "Rendering organized page $cur of $total..."
                }

                if (pdfBytes.isNotEmpty()) {
                    val baseTitle = "${_studioDocumentTitle.value}_Organized"
                    val safeFileName = PdfStorageHelper.generateFileName(baseTitle)
                    val savedUri = PdfStorageHelper.savePdfToDownloads(context, pdfBytes, safeFileName).getOrNull()
                    val cacheFile = PdfStorageHelper.saveToAppCache(context, pdfBytes, safeFileName)

                    val historyItem = DownloadHistoryItem(
                        title = baseTitle,
                        uriString = (savedUri ?: Uri.fromFile(cacheFile)).toString(),
                        pageCount = keptCount,
                        fileSizeBytes = pdfBytes.size.toLong(),
                        localPath = cacheFile.absolutePath,
                        timestamp = System.currentTimeMillis()
                    )
                    historyRepository.addHistoryItem(historyItem)

                    // Reload the newly saved file as the active studio file
                    _studioPdfFile.value = cacheFile
                    _studioPageEdits.value = (0 until keptCount).map { idx ->
                        PdfPageEditItem(originalPageIndex = idx)
                    }
                    _studioExportMessage.value = "Saved '$safeFileName' ($keptCount pages) to Downloads/DrivePDFs!"
                } else {
                    _studioExportMessage.value = "Could not export PDF. Please try again."
                }
            } catch (e: Exception) {
                _studioExportMessage.value = "Export failed: ${e.localizedMessage}"
            } finally {
                _isStudioLoading.value = false
            }
        }
    }

    fun openPdf(uri: Uri, localPath: String? = null) {
        val fallbackFile = localPath?.let { File(it) }
        PdfStorageHelper.openPdf(context, uri, fallbackFile)
    }

    fun sharePdf(uri: Uri, localPath: String? = null, title: String = "Drive PDF") {
        val fallbackFile = localPath?.let { File(it) }
        PdfStorageHelper.sharePdf(context, uri, fallbackFile, title)
    }

    fun deleteHistoryItem(id: String) {
        historyRepository.removeItem(id)
    }

    fun clearAllHistory() {
        historyRepository.clearAll()
    }

    override fun onCleared() {
        super.onCleared()
        extractorEngine?.destroy()
    }
}
