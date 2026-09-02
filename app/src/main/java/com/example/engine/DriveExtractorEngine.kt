package com.example.engine

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.data.model.DownloadHistoryItem
import com.example.data.model.ResolutionQuality
import com.example.data.storage.HistoryRepository
import com.example.data.storage.PdfStorageHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import java.util.concurrent.ConcurrentSkipListMap

data class PageBoundingBox(
    val found: Boolean,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val totalDiscovered: Int
)

class DriveExtractorEngine(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private var webView: WebView? = null
    private var extractedTitle: String? = null
    private var currentResolution: ResolutionQuality = ResolutionQuality.ULTRA
    private var isExtracting = false

    private val capturedPagesMap = ConcurrentSkipListMap<Int, String>()
    private val capturedUrlsSet = Collections.synchronizedSet(HashSet<String>())
    private val capturedPageFiles = Collections.synchronizedList(mutableListOf<File>())

    private var mainHandler = Handler(Looper.getMainLooper())
    private var captureJob: Job? = null
    private var totalDetectedPages: Int = 1

    var onStatusUpdate: ((message: String) -> Unit)? = null
    var onTitleExtracted: ((title: String) -> Unit)? = null
    var onTotalPagesDetected: ((total: Int) -> Unit)? = null
    var onPageDiscovered: ((pageNumber: Int, highResUrl: String, totalCaptured: Int) -> Unit)? = null
    
    // Precision page-by-page capture callbacks
    var onPageCaptured: ((pageNum: Int, total: Int, message: String) -> Unit)? = null
    var onCompilingPdf: ((currentPage: Int, totalPages: Int, percent: Float, message: String) -> Unit)? = null
    var onPdfReady: ((uri: Uri, fileName: String, pageCount: Int, fileSizeBytes: Long, localPath: String?) -> Unit)? = null
    
    var onExtractionCompleted: ((pageUrls: List<String>, title: String?, baseTemplateUrl: String?) -> Unit)? = null
    var onError: ((message: String) -> Unit)? = null

    companion object {
        private const val TAG = "DriveExtractorEngine"
        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        fun extractFileId(rawUrl: String): String? {
            val trimmed = rawUrl.trim()
            val fileIdPatterns = listOf(
                Regex("/file/d/([a-zA-Z0-9_-]+)"),
                Regex("id=([a-zA-Z0-9_-]+)"),
                Regex("/document/d/([a-zA-Z0-9_-]+)"),
                Regex("/presentation/d/([a-zA-Z0-9_-]+)"),
                Regex("/spreadsheets/d/([a-zA-Z0-9_-]+)"),
                Regex("/open\\?id=([a-zA-Z0-9_-]+)")
            )

            for (pattern in fileIdPatterns) {
                val match = pattern.find(trimmed)
                if (match != null) {
                    return match.groupValues[1]
                }
            }
            return null
        }

        fun sanitizeDriveUrl(rawUrl: String): String {
            val trimmed = rawUrl.trim()
            if (trimmed.isEmpty()) return ""

            val fileId = extractFileId(trimmed)
            if (fileId != null) {
                return "https://drive.google.com/file/d/$fileId/preview"
            }

            return if (trimmed.contains("drive.google.com") && trimmed.contains("/view")) {
                trimmed.replace("/view", "/preview")
            } else {
                trimmed
            }
        }

        fun isDriveViewerImageUrl(url: String): Boolean {
            val lower = url.lowercase()
            val isDriveOrGoogle = lower.contains("drive.google.com") ||
                    lower.contains("docs.google.com") ||
                    lower.contains("googleusercontent.com")

            val isViewerPath = lower.contains("/viewer") ||
                    lower.contains("drive-viewer") ||
                    lower.contains("/thumbnail") ||
                    lower.contains("sz=w") ||
                    lower.contains("=w")

            val isUiAsset = lower.contains("cleardot.gif") ||
                    lower.contains("drive_icon") ||
                    lower.contains("photos/private") ||
                    lower.contains("default-avatar") ||
                    lower.contains(".svg") ||
                    lower.contains(".ico")

            return isDriveOrGoogle && isViewerPath && !isUiAsset
        }

        fun extractPageNumberFromUrl(url: String): Int? {
            val pagePatterns = listOf(
                Regex("[?&]page=(\\d+)"),
                Regex("[?&]pg=(\\d+)"),
                Regex("[?&]p=(\\d+)"),
                Regex("page_(\\d+)"),
                Regex("/page/(\\d+)")
            )

            for (pattern in pagePatterns) {
                val match = pattern.find(url)
                if (match != null) {
                    return match.groupValues[1].toIntOrNull()
                }
            }
            return null
        }

        fun upgradeImageUrlResolution(originalUrl: String, quality: ResolutionQuality): String {
            var modified = originalUrl

            val widthRegex = Regex("=w\\d+(-h\\d+)?")
            val szRegex = Regex("sz=w\\d+")
            val paramWRegex = Regex("([?&])w=\\d+")

            if (widthRegex.containsMatchIn(modified)) {
                modified = modified.replace(widthRegex, "=${quality.paramSuffix}")
            } else if (szRegex.containsMatchIn(modified)) {
                modified = modified.replace(szRegex, "sz=${quality.paramSuffix}")
            } else if (paramWRegex.containsMatchIn(modified)) {
                modified = modified.replace(paramWRegex, "$1w=${quality.width}")
            } else {
                if (modified.contains("googleusercontent.com") && !modified.contains("=")) {
                    modified += "=${quality.paramSuffix}"
                } else if (!modified.contains("w=") && !modified.contains("sz=")) {
                    val sep = if (modified.contains("?")) "&" else "?"
                    modified = "$modified${sep}w=${quality.width}"
                }
            }

            return modified
        }

        fun buildPageUrl(baseUrl: String, pageNumber: Int, quality: ResolutionQuality): String {
            val highRes = upgradeImageUrlResolution(baseUrl, quality)
            val pageParam = "page=$pageNumber"
            return if (highRes.contains("page=")) {
                highRes.replace(Regex("page=\\d+"), pageParam)
            } else if (highRes.contains("pg=")) {
                highRes.replace(Regex("pg=\\d+"), "pg=$pageNumber")
            } else if (highRes.contains("?")) {
                "$highRes&$pageParam"
            } else {
                "$highRes?$pageParam"
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun startExtraction(
        url: String,
        resolution: ResolutionQuality = ResolutionQuality.ULTRA,
        existingWebView: WebView? = null
    ) {
        currentResolution = resolution
        extractedTitle = null
        isExtracting = true
        capturedPagesMap.clear()
        capturedUrlsSet.clear()
        capturedPageFiles.clear()
        captureJob?.cancel()

        val targetUrl = sanitizeDriveUrl(url)
        if (targetUrl.isBlank()) {
            onError?.invoke("Invalid Google Drive URL provided.")
            return
        }

        mainHandler.post {
            try {
                val wv = existingWebView ?: WebView(context).also { webView = it }
                try {
                    wv.measure(
                        android.view.View.MeasureSpec.makeMeasureSpec(1200, android.view.View.MeasureSpec.EXACTLY),
                        android.view.View.MeasureSpec.makeMeasureSpec(1800, android.view.View.MeasureSpec.EXACTLY)
                    )
                    wv.layout(0, 0, 1200, 1800)
                } catch (e: Exception) {
                    Log.d(TAG, "Layout measurement: ${e.message}")
                }
                setupWebView(wv, targetUrl)
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing WebView", e)
                onError?.invoke("Failed to initialize browser engine: ${e.localizedMessage}")
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(wv: WebView, targetUrl: String) {
        with(wv.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = DESKTOP_USER_AGENT
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

        wv.addJavascriptInterface(DriveJsBridge(), "DriveBridge")

        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                if (request != null && isExtracting) {
                    val reqUrl = request.url.toString()
                    if (isDriveViewerImageUrl(reqUrl)) {
                        val pageNum = extractPageNumberFromUrl(reqUrl)
                        handleImageDiscovered(reqUrl, pageNum)
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                onStatusUpdate?.invoke("Connecting to Google Drive document...")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                onStatusUpdate?.invoke("Document loaded. Initializing precision page capturer...")
                injectPageScraperAndCaptureScript(view)

                // Start the sequential precision page-by-page capture loop
                startSequentialPageCapture(view ?: wv)
            }
        }

        wv.loadUrl(targetUrl)
    }

    @Synchronized
    private fun handleImageDiscovered(rawUrl: String, detectedPageNum: Int?) {
        if (!isExtracting) return

        val cleanBaseUrl = rawUrl.substringBefore("=w").substringBefore("sz=w")
        if (capturedUrlsSet.contains(cleanBaseUrl)) {
            return
        }
        capturedUrlsSet.add(cleanBaseUrl)

        val highResUrl = upgradeImageUrlResolution(rawUrl, currentResolution)

        val pageNumber = if (detectedPageNum != null && detectedPageNum > 0) {
            detectedPageNum
        } else if (detectedPageNum == 0) {
            1
        } else {
            val maxKey = capturedPagesMap.keys.maxOrNull() ?: 0
            maxKey + 1
        }

        capturedPagesMap[pageNumber] = highResUrl
        val currentTotal = capturedPagesMap.size

        mainHandler.post {
            onPageDiscovered?.invoke(pageNumber, highResUrl, currentTotal)
        }
    }

    private fun injectPageScraperAndCaptureScript(view: WebView?) {
        val jsScript = """
            (function() {
                window._driveCapture = {
                    hideChrome: function() {
                        var selectors = [
                            '.drive-viewer-toolstrip',
                            '.drive-viewer-action-bar',
                            '.ndfHFb-c4YZDc-j7LFlb',
                            '.drive-viewer-popout-button',
                            '.drive-viewer-banner',
                            'header',
                            '[role="banner"]',
                            '.drive-viewer-navigation-overlay'
                        ];
                        for (var i = 0; i < selectors.length; i++) {
                            var els = document.querySelectorAll(selectors[i]);
                            for (var j = 0; j < els.length; j++) {
                                els[j].style.display = 'none';
                            }
                        }
                    },

                    getTitle: function() {
                        try {
                            var title = document.title || "";
                            title = title.replace(" - Google Drive", "").replace(" - Google Docs", "").trim();
                            var titleElem = document.querySelector('.ndfHFb-c4YZDc-s2gQvd') || 
                                            document.querySelector('.drive-viewer-toolstrip-name') ||
                                            document.querySelector('.drive-viewer-title');
                            if (titleElem && titleElem.innerText) {
                                title = titleElem.innerText.trim();
                            }
                            return title;
                        } catch(e) { return ""; }
                    },

                    findPages: function() {
                        var selectors = [
                            '.drive-viewer-paginated-page',
                            '[data-page-index]',
                            '[data-page-number]',
                            '.ndfHFb-c4YZDc-Wrql6b',
                            '.drive-viewer-page'
                        ];
                        for (var s = 0; s < selectors.length; s++) {
                            var list = document.querySelectorAll(selectors[s]);
                            if (list && list.length > 0) {
                                return Array.from(list);
                            }
                        }
                        var container = document.querySelector('.drive-viewer-paginated-scrollable, .ndfHFb-c4YZDc-bN97Pc, [role="main"]');
                        if (container && container.children) {
                            var valid = [];
                            for (var i = 0; i < container.children.length; i++) {
                                var ch = container.children[i];
                                if (ch.clientHeight > 250 && ch.clientWidth > 250) {
                                    valid.push(ch);
                                }
                            }
                            if (valid.length > 0) return valid;
                        }
                        return [];
                    },

                    getTotalCount: function() {
                        var textNodes = document.querySelectorAll('.drive-viewer-toolstrip, .ndfHFb-c4YZDc-j7LFlb, div, span');
                        for (var i = 0; i < textNodes.length; i++) {
                            var txt = textNodes[i].innerText || "";
                            var m = /(?:of|\/)\s*(\d+)/i.exec(txt);
                            if (m && m[1]) {
                                var cnt = parseInt(m[1], 10);
                                if (cnt > 0 && cnt < 1000) return cnt;
                            }
                        }
                        return this.findPages().length;
                    },

                    scrollToPage: function(idx) {
                        this.hideChrome();
                        var pages = this.findPages();
                        if (!pages || pages.length === 0 || idx >= pages.length) {
                            var sc = document.querySelector('.drive-viewer-paginated-scrollable, .ndfHFb-c4YZDc-bN97Pc') || document.documentElement || document.body;
                            var step = 1400;
                            if (sc.scrollTop !== undefined) {
                                sc.scrollTop = idx * step;
                            } else {
                                window.scrollBy(0, step);
                            }
                            return JSON.stringify({
                                found: false,
                                left: 0,
                                top: 0,
                                width: window.innerWidth || 1200,
                                height: window.innerHeight || 1800,
                                totalDiscovered: pages ? pages.length : 0
                            });
                        }

                        var pageEl = pages[idx];
                        pageEl.scrollIntoView({ behavior: 'instant', block: 'start', inline: 'center' });

                        try {
                            pageEl.dispatchEvent(new Event('scroll', { bubbles: true }));
                            window.dispatchEvent(new Event('scroll'));
                        } catch(e) {}

                        var rect = pageEl.getBoundingClientRect();
                        return JSON.stringify({
                            found: true,
                            left: Math.round(rect.left),
                            top: Math.round(rect.top),
                            width: Math.round(rect.width),
                            height: Math.round(rect.height),
                            totalDiscovered: pages.length
                        });
                    },

                    isPageImageReady: function(idx) {
                        var pages = this.findPages();
                        if (!pages || idx >= pages.length) return true;
                        var pageEl = pages[idx];
                        var img = pageEl.querySelector('img');
                        if (img) {
                            return img.complete && img.naturalWidth > 0;
                        }
                        return true;
                    }
                };
            })();
        """.trimIndent()

        view?.evaluateJavascript(jsScript, null)
    }

    private fun startSequentialPageCapture(wv: WebView) {
        captureJob?.cancel()
        captureJob = scope.launch(Dispatchers.Main) {
            delay(1000)
            if (!isExtracting) return@launch

            // 1. Scrape Title
            wv.evaluateJavascript("window._driveCapture ? window._driveCapture.getTitle() : '';") { titleRaw ->
                val clean = titleRaw?.trim('"', '\\', ' ')?.takeIf { it.isNotBlank() && it != "null" }
                if (clean != null && extractedTitle == null) {
                    extractedTitle = clean
                    onTitleExtracted?.invoke(clean)
                }
            }

            // 2. Detect Total Pages
            wv.evaluateJavascript("window._driveCapture ? window._driveCapture.getTotalCount() : 1;") { countRaw ->
                val count = countRaw?.trim('"', ' ')?.toIntOrNull() ?: 1
                if (count > 0) {
                    totalDetectedPages = count
                    onTotalPagesDetected?.invoke(count)
                }
            }

            delay(300)
            onStatusUpdate?.invoke("Starting exact page-by-page capture (Total: $totalDetectedPages detected)...")

            var pageIndex = 0
            var consecutiveMisses = 0
            val maxLimit = 300

            while (isExtracting && pageIndex < maxLimit) {
                val pageDisplay = pageIndex + 1

                // Scroll to exact page element and get bounding rect
                val box = fetchPageBoundingBox(wv, pageIndex)

                // Wait 450ms for Google Drive lazy load / image decode
                delay(450)

                // Quick image load check
                val isReady = checkImageReady(wv, pageIndex)
                if (!isReady) {
                    delay(350)
                }

                // Capture exact real-size page screenshot
                val tempFile = File(context.cacheDir, "drive_page_${System.currentTimeMillis()}_$pageDisplay.jpg")
                val success = captureExactPageScreenshot(wv, box, tempFile)

                if (success && tempFile.length() > 2048) {
                    consecutiveMisses = 0
                    capturedPageFiles.add(tempFile)

                    val effectiveTotal = maxOf(totalDetectedPages, box?.totalDiscovered ?: 0, capturedPageFiles.size)
                    val statusMsg = "Captured Page $pageDisplay of $effectiveTotal (Real-size page screenshot)..."
                    
                    onStatusUpdate?.invoke(statusMsg)
                    onPageCaptured?.invoke(pageDisplay, effectiveTotal, statusMsg)

                    // If we have reached the confirmed total pages, finish
                    if (totalDetectedPages > 0 && capturedPageFiles.size >= totalDetectedPages) {
                        Log.d(TAG, "All $totalDetectedPages pages captured successfully!")
                        break
                    }
                } else {
                    tempFile.delete()
                    consecutiveMisses++
                    if (consecutiveMisses >= 3) {
                        Log.d(TAG, "Consecutive misses reached 3 at page $pageDisplay. Finishing capture.")
                        break
                    }
                }

                pageIndex++
            }

            // Compile the captured real-size screenshots into the PDF
            if (capturedPageFiles.isNotEmpty()) {
                compileCapturedFilesToPdf(capturedPageFiles.toList())
            } else {
                // Fallback to URL-based extraction if available
                if (capturedPagesMap.isNotEmpty()) {
                    val pagesList = capturedPagesMap.values.toList()
                    onExtractionCompleted?.invoke(pagesList, extractedTitle, pagesList.firstOrNull())
                } else {
                    onError?.invoke("Could not capture document pages. Please ensure the document is viewable.")
                }
            }
        }
    }

    private suspend fun fetchPageBoundingBox(wv: WebView, pageIndex: Int): PageBoundingBox? = withContext(Dispatchers.Main) {
        val deferred = kotlinx.coroutines.CompletableDeferred<PageBoundingBox?>()
        wv.evaluateJavascript("window._driveCapture ? window._driveCapture.scrollToPage($pageIndex) : '{}';") { jsonResult ->
            try {
                val cleaned = jsonResult?.trim('"', '\\', ' ') ?: "{}"
                val unescaped = jsonResult?.replace("\\\"", "\"")?.trim('"') ?: "{}"
                val source = if (unescaped.startsWith("{")) unescaped else cleaned
                val json = JSONObject(source)
                val box = PageBoundingBox(
                    found = json.optBoolean("found", true),
                    left = json.optInt("left", 0),
                    top = json.optInt("top", 0),
                    width = json.optInt("width", wv.width.coerceAtLeast(1080)),
                    height = json.optInt("height", wv.height.coerceAtLeast(1600)),
                    totalDiscovered = json.optInt("totalDiscovered", 1)
                )
                deferred.complete(box)
            } catch (e: Exception) {
                deferred.complete(null)
            }
        }
        deferred.await()
    }

    private suspend fun checkImageReady(wv: WebView, pageIndex: Int): Boolean = withContext(Dispatchers.Main) {
        val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
        wv.evaluateJavascript("window._driveCapture ? window._driveCapture.isPageImageReady($pageIndex) : true;") { res ->
            deferred.complete(res?.contains("true") == true)
        }
        deferred.await()
    }

    private fun captureExactPageScreenshot(
        wv: WebView,
        box: PageBoundingBox?,
        outputFile: File
    ): Boolean {
        return try {
            val w = wv.width.coerceAtLeast(1080)
            val h = wv.height.coerceAtLeast(1600)
            val fullBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(fullBitmap)
            wv.draw(canvas)

            // Precision crop: if page bounding box is found and has realistic page dimensions, crop to it
            val cropBitmap: Bitmap = if (box != null && box.found && box.width > 200 && box.height > 200) {
                val safeX = box.left.coerceIn(0, (w - 100).coerceAtLeast(0))
                val safeY = box.top.coerceIn(0, (h - 100).coerceAtLeast(0))
                val safeW = box.width.coerceIn(100, (w - safeX).coerceAtLeast(100))
                val safeH = box.height.coerceIn(100, (h - safeY).coerceAtLeast(100))
                Bitmap.createBitmap(fullBitmap, safeX, safeY, safeW, safeH)
            } else {
                fullBitmap
            }

            FileOutputStream(outputFile).use { out ->
                cropBitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                out.flush()
            }

            if (cropBitmap != fullBitmap) {
                cropBitmap.recycle()
            }
            fullBitmap.recycle()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error capturing page bitmap", e)
            false
        }
    }

    private fun compileCapturedFilesToPdf(files: List<File>) {
        scope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    onStatusUpdate?.invoke("Compiling ${files.size} real-size page screenshots into native PDF...")
                    onCompilingPdf?.invoke(1, files.size, 0.1f, "Compiling ${files.size} pages...")
                }

                val pdfCompiler = PdfCompiler()
                val pdfBytes = pdfCompiler.compileImageFiles(files) { current, total ->
                    val fraction = current.toFloat() / total.coerceAtLeast(1)
                    scope.launch(Dispatchers.Main) {
                        onCompilingPdf?.invoke(current, total, fraction, "Writing page $current of $total to PDF...")
                    }
                }

                val title = extractedTitle ?: "Drive_Document_${System.currentTimeMillis()}"
                val safeFileName = PdfStorageHelper.generateFileName(title)

                // Save to public Downloads/DrivePDFs
                val savedUri = PdfStorageHelper.savePdfToDownloads(context, pdfBytes, safeFileName).getOrNull()
                val cacheFile = PdfStorageHelper.saveToAppCache(context, pdfBytes, safeFileName)

                // Save record in History
                val historyItem = DownloadHistoryItem(
                    title = title,
                    uriString = (savedUri ?: Uri.fromFile(cacheFile)).toString(),
                    pageCount = files.size,
                    fileSizeBytes = pdfBytes.size.toLong(),
                    localPath = cacheFile.absolutePath,
                    timestamp = System.currentTimeMillis()
                )
                HistoryRepository(context).addHistoryItem(historyItem)

                // Clean up temporary image files
                files.forEach { it.delete() }

                withContext(Dispatchers.Main) {
                    onStatusUpdate?.invoke("PDF Successfully Created (${files.size} pages)!")
                    onPdfReady?.invoke(
                        savedUri ?: Uri.fromFile(cacheFile),
                        safeFileName,
                        files.size,
                        pdfBytes.size.toLong(),
                        cacheFile.absolutePath
                    )
                    destroy()
                }
            } catch (e: Exception) {
                Log.e(TAG, "PDF compilation failed", e)
                withContext(Dispatchers.Main) {
                    onError?.invoke("Failed to compile PDF: ${e.localizedMessage}")
                    destroy()
                }
            }
        }
    }

    fun forceCompileNow() {
        if (capturedPageFiles.isNotEmpty()) {
            isExtracting = false
            captureJob?.cancel()
            compileCapturedFilesToPdf(capturedPageFiles.toList())
        } else if (capturedPagesMap.isNotEmpty()) {
            isExtracting = false
            captureJob?.cancel()
            val pagesList = capturedPagesMap.values.toList()
            onExtractionCompleted?.invoke(pagesList, extractedTitle, pagesList.firstOrNull())
        } else {
            onError?.invoke("No document pages have been captured yet. Please wait a moment.")
        }
    }

    fun destroy() {
        isExtracting = false
        captureJob?.cancel()
        mainHandler.post {
            try {
                webView?.stopLoading()
                webView?.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "Error destroying WebView", e)
            }
            webView = null
        }
    }

    private inner class DriveJsBridge {
        @JavascriptInterface
        fun onTitleFound(title: String) {
            if (title.isNotBlank() && extractedTitle == null) {
                extractedTitle = title
                onTitleExtracted?.invoke(title)
            }
        }

        @JavascriptInterface
        fun onTotalPagesDetected(total: Int) {
            if (total > 0) {
                totalDetectedPages = total
                mainHandler.post {
                    onTotalPagesDetected?.invoke(total)
                }
            }
        }

        @JavascriptInterface
        fun onPageImageFound(url: String, pageIndex: Int) {
            if (url.isNotBlank() && isDriveViewerImageUrl(url)) {
                handleImageDiscovered(url, if (pageIndex >= 0) pageIndex + 1 else null)
            }
        }
    }
}
