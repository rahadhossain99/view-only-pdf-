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
import com.example.data.model.PdfCompressionLevel
import com.example.data.model.PdfPageEditItem
import com.example.data.model.ResolutionQuality
import com.example.data.model.WorkspaceTab
import com.example.data.storage.HistoryRepository
import com.example.data.storage.PdfStorageHelper
import com.example.engine.DriveExtractorEngine
import com.example.engine.PdfCompiler
import com.example.engine.PdfOrganizerEngine
import com.example.service.DownloadNotificationHelper
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

    private val _customFileName = MutableStateFlow("")
    val customFileName: StateFlow<String> = _customFileName.asStateFlow()

    // Default to Balanced (HIGH) for fast download and compact 3-7 MB file size
    private val _resolutionQuality = MutableStateFlow(ResolutionQuality.HIGH)
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

    // Post-creation PDF size reducer / compressor state
    private val _isCompressingPdf = MutableStateFlow(false)
    val isCompressingPdf: StateFlow<Boolean> = _isCompressingPdf.asStateFlow()

    private val _compressionStatus = MutableStateFlow<String?>(null)
    val compressionStatus: StateFlow<String?> = _compressionStatus.asStateFlow()

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
                        val cleanName = serviceState.fileName.removeSuffix(".pdf")
                        preloadPdfForStudio(
                            uri = serviceState.uri,
                            localPath = serviceState.localPath,
                            title = cleanName
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

    fun updateCustomFileName(name: String) {
        _customFileName.value = name
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
            _downloadState.value = DownloadState.Error("অনুগ্রহ করে একটি সঠিক Google Drive বা PDF লিংক দিন।")
            return
        }

        cancelDownload()
        PdfDownloadService.resetState()
        _discoveredPages.value = emptyList()
        _currentDocumentTitle.value = null
        _compressionStatus.value = null

        if (!PdfStorageHelper.isValidDocumentTitle(_customFileName.value)) {
            _customFileName.value = ""
        }

        val initialName = PdfStorageHelper.cleanDocumentTitle(_customFileName.value) ?: "Drive PDF"
        DownloadNotificationHelper.showProgress(
            context = context,
            docName = initialName,
            percent = 3,
            detailText = "গুগল ড্রাইভের সাথে সংযোগ হচ্ছে..."
        )

        _engineTelemetry.value = EngineTelemetry(
            activeMode = _engineMode.value,
            activeTierName = "Stream & Export Probe",
            lastLogMessage = "ডকুমেন্ট স্ক্যান করা হচ্ছে..."
        )
        _downloadState.value = DownloadState.LoadingWebView(cleanUrl, "ডকুমেন্ট সংযোগ হচ্ছে (3%)...")

        viewModelScope.launch {
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
                val chosenTitle = _customFileName.value.trim().takeIf { it.isNotBlank() } ?: _currentDocumentTitle.value
                _downloadState.value = DownloadState.DownloadingImages(
                    current = 1,
                    total = 1,
                    percent = 0.25f,
                    message = "Direct PDF stream detected. Downloading..."
                )
                PdfDownloadService.startDirectPdfDownload(context, directPdfUrl, chosenTitle)
                return@launch
            }

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
            Log.d("MainViewModel", "Direct check did not succeed: ${e.localizedMessage}")
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

        engine.customTitleProvider = {
            PdfStorageHelper.cleanDocumentTitle(_customFileName.value)
        }

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
            val validTitle = PdfStorageHelper.cleanDocumentTitle(title)
            if (validTitle != null) {
                _currentDocumentTitle.value = validTitle
            }
        }

        engine.onTotalPagesDetected = { total ->
            val currentState = _downloadState.value
            if (currentState is DownloadState.InterceptingPages) {
                _downloadState.value = currentState.copy(
                    estimatedTotal = maxOf(currentState.estimatedTotal, total),
                    message = "মোট $total টি পেজ পাওয়া গেছে..."
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
            val safeTotal = total.coerceAtLeast(pageNum).coerceAtLeast(1)
            val pct = ((pageNum.toFloat() / safeTotal.toFloat()) * 0.85f).coerceIn(0.05f, 0.85f)
            _downloadState.value = DownloadState.DownloadingImages(
                current = pageNum,
                total = safeTotal,
                percent = pct,
                message = msg
            )
        }

        engine.onCompilingPdf = { current, total, percent, msg ->
            _downloadState.value = DownloadState.CompilingPdf(
                currentPage = current,
                totalPages = total,
                percent = percent.coerceIn(0.86f, 0.99f),
                message = msg
            )
        }

        engine.onPdfReady = { uri, fileName, pageCount, fileSizeBytes, localPath ->
            val cleanBaseName = fileName.removeSuffix(".pdf")
            _downloadState.value = DownloadState.Success(
                uri = uri,
                fileName = fileName,
                pageCount = pageCount,
                fileSizeBytes = fileSizeBytes,
                localPath = localPath
            )
            preloadPdfForStudio(uri, localPath, cleanBaseName)
            extractorEngine = null
        }

        engine.onExtractionCompleted = { pageUrls, title, baseTemplateUrl ->
            val docTitle = _customFileName.value.trim().takeIf { it.isNotBlank() }
                ?: title
                ?: _currentDocumentTitle.value
            _currentDocumentTitle.value = docTitle

            if (pageUrls.size > 1) {
                _downloadState.value = DownloadState.DownloadingImages(
                    current = 1,
                    total = pageUrls.size,
                    percent = 0.05f,
                    message = "Downloading ${pageUrls.size} pages..."
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
                    percent = 0.1f,
                    message = "Probing all document pages..."
                )
                PdfDownloadService.startProbeDownload(
                    context = context,
                    firstImageUrl = baseTemplateUrl,
                    docTitle = docTitle,
                    resolution = _resolutionQuality.value
                )
            } else {
                DownloadNotificationHelper.cancelProgress(context)
                _downloadState.value = DownloadState.Error(
                    message = "এই লিংক থেকে পেজ পাওয়া যায়নি।",
                    details = "লিঙ্কটি ভিউ-অনলি বা প্রাইভেট হলে প্রিভিউ মোডে লগইন করে আবার চেষ্টা করুন।"
                )
            }

            engine.destroy()
            extractorEngine = null
        }

        engine.onError = { errorMsg ->
            DownloadNotificationHelper.cancelProgress(context)
            _downloadState.value = DownloadState.Error(
                message = errorMsg,
                details = "Make sure the link is accessible or sign in via Browser Preview."
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
        val docTitle = _customFileName.value.trim().takeIf { it.isNotBlank() }
            ?: title
            ?: _currentDocumentTitle.value
        _currentDocumentTitle.value = docTitle
        _downloadState.value = DownloadState.DownloadingImages(
            current = 1,
            total = pageUrls.size,
            percent = 0.05f,
            message = "Starting download of ${pageUrls.size} pages..."
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
        DownloadNotificationHelper.cancelProgress(context)
        _downloadState.value = DownloadState.Idle
    }

    /**
     * Renames the already-created PDF file in Downloads/DrivePDFs & local cache,
     * and updates both UI state and the top completion notification.
     */
    fun renameCompletedPdf(newRawName: String) {
        val currentState = _downloadState.value as? DownloadState.Success ?: return
        val trimmed = newRawName.trim()
        if (trimmed.isBlank()) return

        _customFileName.value = trimmed.removeSuffix(".pdf")

        viewModelScope.launch {
            val cacheFile = currentState.localPath?.let { File(it) }
            val (newUri, newFileName) = withContext(Dispatchers.IO) {
                PdfStorageHelper.renameSavedPdf(
                    context = context,
                    currentUri = currentState.uri,
                    localPath = currentState.localPath,
                    newTitle = trimmed
                )
            }
            _downloadState.value = currentState.copy(
                uri = newUri,
                fileName = newFileName
            )
            _compressionStatus.value = "নাম পরিবর্তন করে '$newFileName' রাখা হয়েছে"
            DownloadNotificationHelper.showCompleted(
                context = context,
                fileName = newFileName,
                pageCount = currentState.pageCount,
                fileSizeBytes = currentState.fileSizeBytes,
                uri = newUri,
                cacheFile = cacheFile
            )
        }
    }

    /**
     * Compresses an already-created PDF (e.g., if it is 55 MB or 100 MB+ or user wants a smaller size)
     * into a lightweight native JPEG-stream PDF.
     */
    fun compressCompletedPdf(level: PdfCompressionLevel) {
        val currentState = _downloadState.value as? DownloadState.Success ?: return
        if (_isCompressingPdf.value) return

        viewModelScope.launch {
            _isCompressingPdf.value = true
            _compressionStatus.value = "${level.title} সাইজে কমানো হচ্ছে (0%)..."

            try {
                val sourceFile = withContext(Dispatchers.IO) {
                    pdfOrganizerEngine.resolveSeekableFile(currentState.uri, currentState.localPath)
                }

                if (sourceFile == null || !sourceFile.exists()) {
                    _compressionStatus.value = "পিডিএফ ফাইলটি পাওয়া যায়নি।"
                    _isCompressingPdf.value = false
                    return@launch
                }

                val originalBytesSize = currentState.fileSizeBytes
                val compressedBytes = PdfCompiler.compressExistingPdf(
                    sourcePdfFile = sourceFile,
                    level = level
                ) { page, total ->
                    val pct = ((page.toFloat() / total.coerceAtLeast(1)) * 100f).toInt().coerceIn(1, 99)
                    _compressionStatus.value = "পেজ $page/$total অপ্টিমাইজ হচ্ছে ($pct%)..."
                }

                if (compressedBytes.isNotEmpty()) {
                    val baseTitle = _customFileName.value.trim().ifBlank {
                        currentState.fileName.removeSuffix(".pdf")
                    }
                    val finalFileName = PdfStorageHelper.generateFileName(baseTitle)
                    val savedUri = withContext(Dispatchers.IO) {
                        PdfStorageHelper.savePdfToDownloads(context, compressedBytes, finalFileName).getOrNull()
                    }
                    val cacheFile = withContext(Dispatchers.IO) {
                        PdfStorageHelper.saveToAppCache(context, compressedBytes, finalFileName)
                    }
                    val finalUri = savedUri ?: Uri.fromFile(cacheFile)
                    val newSize = compressedBytes.size.toLong()

                    _downloadState.value = currentState.copy(
                        uri = finalUri,
                        fileName = finalFileName,
                        fileSizeBytes = newSize,
                        localPath = cacheFile.absolutePath,
                        previousSizeBytes = originalBytesSize
                    )

                    val oldFormatted = PdfStorageHelper.formatFileSize(originalBytesSize)
                    val newFormatted = PdfStorageHelper.formatFileSize(newSize)
                    _compressionStatus.value = "সাইজ কমানো সম্পন্ন: $oldFormatted → $newFormatted"

                    DownloadNotificationHelper.showCompleted(
                        context = context,
                        fileName = finalFileName,
                        pageCount = currentState.pageCount,
                        fileSizeBytes = newSize,
                        uri = finalUri,
                        cacheFile = cacheFile
                    )
                } else {
                    _compressionStatus.value = "সাইজ কমানো সম্ভব হয়নি।"
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error compressing PDF", e)
                _compressionStatus.value = "ত্রুটি: ${e.localizedMessage}"
            } finally {
                _isCompressingPdf.value = false
            }
        }
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
        _studioExportMessage.value = "Exporting $keptCount organized pages to compact PDF..."

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

    fun openInFiles(uri: Uri, localPath: String? = null) {
        val fallbackFile = localPath?.let { File(it) }
        PdfStorageHelper.openInFiles(context, uri, fallbackFile)
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
