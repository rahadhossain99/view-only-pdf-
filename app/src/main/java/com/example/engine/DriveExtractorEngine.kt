package com.example.engine

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
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
import com.example.data.model.ResolutionQuality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class DriveExtractorEngine(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private var webView: WebView? = null
    private val capturedPages = ConcurrentHashMap<Int, String>()
    private var estimatedTotalPages = 0
    private var extractedTitle: String? = null
    private var currentResolution: ResolutionQuality = ResolutionQuality.ULTRA
    private var autoScrollJob: Job? = null
    private var isExtracting = false

    var onStatusUpdate: ((message: String) -> Unit)? = null
    var onPagesDiscovered: ((count: Int, total: Int) -> Unit)? = null
    var onTitleExtracted: ((title: String) -> Unit)? = null
    var onExtractionFinished: ((pages: List<Pair<Int, String>>, title: String?) -> Unit)? = null
    var onError: ((message: String) -> Unit)? = null

    companion object {
        private const val TAG = "DriveExtractorEngine"
        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        fun sanitizeDriveUrl(rawUrl: String): String {
            val trimmed = rawUrl.trim()
            if (trimmed.isEmpty()) return ""

            // Extract Google Drive file ID if present
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
                    val fileId = match.groupValues[1]
                    return "https://drive.google.com/file/d/$fileId/preview"
                }
            }

            // Fallback: If it's already a drive/docs URL, ensure it has /preview or embedded=true
            return if (trimmed.contains("drive.google.com") && trimmed.contains("/view")) {
                trimmed.replace("/view", "/preview")
            } else {
                trimmed
            }
        }

        fun extractPageNumberFromUrl(url: String): String? {
            val regexes = listOf(
                Regex("[?&]page=(\\d+)"),
                Regex("[?&]pg=(\\d+)"),
                Regex("[?&]p=(\\d+)"),
                Regex("page_(\\d+)"),
                Regex("/page/(\\d+)")
            )
            for (regex in regexes) {
                val match = regex.find(url)
                if (match != null) {
                    return match.groupValues[1]
                }
            }
            return null
        }

        fun upgradeImageUrlResolution(originalUrl: String, quality: ResolutionQuality): String {
            var modified = originalUrl

            // Replace low-resolution width parameters (e.g. =w800, =w1200, w=800, sz=w800)
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
                // If it's a googleusercontent URL without resolution suffix, append it
                if (modified.contains("googleusercontent.com") && !modified.contains("=")) {
                    modified += "=${quality.paramSuffix}"
                }
            }

            return modified
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun startExtraction(
        url: String,
        resolution: ResolutionQuality = ResolutionQuality.ULTRA,
        existingWebView: WebView? = null
    ) {
        currentResolution = resolution
        capturedPages.clear()
        estimatedTotalPages = 0
        extractedTitle = null
        isExtracting = true

        val targetUrl = sanitizeDriveUrl(url)
        if (targetUrl.isBlank()) {
            onError?.invoke("Invalid Google Drive URL provided.")
            return
        }

        Handler(Looper.getMainLooper()).post {
            try {
                val wv = existingWebView ?: WebView(context).also { webView = it }
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

        // Enable third-party cookies for Drive authentication session
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
                    handleInterceptedUrl(reqUrl)
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                onStatusUpdate?.invoke("Loading Google Drive document viewer...")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                onStatusUpdate?.invoke("Document viewer loaded. Initializing page extraction...")
                injectPageScraperScript(view)
            }
        }

        wv.loadUrl(targetUrl)
    }

    private fun handleInterceptedUrl(url: String) {
        // Intercept drive viewer image URLs
        val isDriveImage = url.contains("drive.google.com/viewer/img") ||
                url.contains("drive-viewer") ||
                url.contains("googleusercontent.com/drive-viewer") ||
                url.contains("drive.google.com/thumbnail")

        if (!isDriveImage) return

        // Extract page number from query params if present (e.g. ?id=...&page=3 or &pg=3)
        val uri = try { Uri.parse(url) } catch (e: Exception) { null }
        val pageParam = uri?.getQueryParameter("page")
            ?: uri?.getQueryParameter("pg")
            ?: uri?.getQueryParameter("p")
            ?: extractPageNumberFromUrl(url)

        val pageNumber = pageParam?.toIntOrNull() ?: (capturedPages.size + 1)
        val highResUrl = upgradeImageUrlResolution(url, currentResolution)

        capturedPages[pageNumber] = highResUrl

        if (pageNumber > estimatedTotalPages) {
            estimatedTotalPages = pageNumber
        }

        onPagesDiscovered?.invoke(capturedPages.size, estimatedTotalPages)
        onStatusUpdate?.invoke("Discovered page $pageNumber (${capturedPages.size} total captured)")
    }

    private fun injectPageScraperScript(view: WebView?) {
        val jsScript = """
            (function() {
                try {
                    // Extract Document Title
                    var title = document.title || "";
                    title = title.replace(" - Google Drive", "").replace(" - Google Docs", "").trim();
                    var titleElem = document.querySelector('.ndfHFb-c4YZDc-s2gQvd') || document.querySelector('.drive-viewer-toolstrip-name');
                    if (titleElem && titleElem.innerText) {
                        title = titleElem.innerText.trim();
                    }
                    if (title) {
                        window.DriveBridge.onTitleFound(title);
                    }

                    // Look for total page count in viewer UI
                    var totalPages = 0;
                    var pageCountElements = document.querySelectorAll('*');
                    for (var i = 0; i < pageCountElements.length; i++) {
                        var text = pageCountElements[i].innerText || "";
                        var match = text.match(/(\d+)\s*\/\s*(\d+)/);
                        if (match && parseInt(match[2]) > totalPages) {
                            totalPages = parseInt(match[2]);
                        }
                    }

                    // Query all container page divs
                    var pageContainers = document.querySelectorAll('.ndfHFb-c4YZDc-cYSp0e-DARUcf, .ndfHFb-c4YZDc-j7LFlb, div[role="region"], div[data-page-index]');
                    if (pageContainers.length > totalPages) {
                        totalPages = pageContainers.length;
                    }

                    window.DriveBridge.onTotalPagesEstimated(totalPages);

                    // Scan current DOM for any rendered page images
                    function scanImages() {
                        var imgs = document.querySelectorAll('img');
                        imgs.forEach(function(img, index) {
                            var src = img.src || img.getAttribute('src') || '';
                            if (src && (src.indexOf('viewer/img') !== -1 || src.indexOf('drive-viewer') !== -1 || src.indexOf('googleusercontent') !== -1)) {
                                var pIndex = index + 1;
                                var parentPage = img.closest('[data-page-index], [aria-label*="Page"], .ndfHFb-c4YZDc-cYSp0e-DARUcf');
                                if (parentPage) {
                                    var label = parentPage.getAttribute('aria-label') || parentPage.getAttribute('data-page-index') || '';
                                    var numMatch = label.match(/\d+/);
                                    if (numMatch) {
                                        pIndex = parseInt(numMatch[0]);
                                    }
                                }
                                window.DriveBridge.onImageFound(pIndex, src);
                            }
                        });
                    }

                    scanImages();

                    // Find main scrollable element in Drive viewer
                    var scrollContainer = document.querySelector('.ndfHFb-c4YZDc-bN97Pc') ||
                                          document.querySelector('div[role="main"]') ||
                                          document.querySelector('.drive-viewer-paginated-scrollable') ||
                                          document.scrollingElement ||
                                          document.body;

                    // Automated smooth scroll to trigger lazy loading of every page
                    var currentScroll = 0;
                    var maxScroll = scrollContainer.scrollHeight || document.body.scrollHeight || 5000;
                    var scrollStep = window.innerHeight * 0.75;
                    var scrollInterval = setInterval(function() {
                        currentScroll += scrollStep;
                        scrollContainer.scrollTop = currentScroll;
                        window.scrollBy(0, scrollStep);
                        scanImages();

                        if (currentScroll >= (scrollContainer.scrollHeight || document.body.scrollHeight)) {
                            clearInterval(scrollInterval);
                            setTimeout(function() {
                                scanImages();
                                window.DriveBridge.onScrollerFinished();
                            }, 1200);
                        }
                    }, 400);

                } catch(e) {
                    window.DriveBridge.onJsError(e.toString());
                }
            })();
        """.trimIndent()

        view?.evaluateJavascript(jsScript, null)
    }

    fun finishExtractionManually() {
        completeExtraction()
    }

    private fun completeExtraction() {
        if (!isExtracting) return
        isExtracting = false
        autoScrollJob?.cancel()

        val orderedList = capturedPages.entries
            .map { Pair(it.key, it.value) }
            .sortedBy { it.first }

        if (orderedList.isEmpty()) {
            onError?.invoke("No page images could be intercepted. If this document requires Google login, please sign in via Interactive Mode.")
        } else {
            onExtractionFinished?.invoke(orderedList, extractedTitle)
        }
    }

    fun destroy() {
        isExtracting = false
        autoScrollJob?.cancel()
        Handler(Looper.getMainLooper()).post {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }
    }

    private inner class DriveJsBridge {
        @JavascriptInterface
        fun onTitleFound(title: String) {
            if (!title.isBlank() && extractedTitle == null) {
                extractedTitle = title
                onTitleExtracted?.invoke(title)
            }
        }

        @JavascriptInterface
        fun onTotalPagesEstimated(total: Int) {
            if (total > estimatedTotalPages) {
                estimatedTotalPages = total
                onPagesDiscovered?.invoke(capturedPages.size, estimatedTotalPages)
            }
        }

        @JavascriptInterface
        fun onImageFound(pageIndex: Int, src: String) {
            handleInterceptedUrl(src)
        }

        @JavascriptInterface
        fun onScrollerFinished() {
            onStatusUpdate?.invoke("Page scanning finished. Verifying captured pages...")
            // Wait 1.5 seconds for in-flight requests before finishing
            scope.launch(Dispatchers.Main) {
                delay(1500)
                if (isActive) {
                    completeExtraction()
                }
            }
        }

        @JavascriptInterface
        fun onJsError(error: String) {
            Log.w(TAG, "JS Scraper reported: $error")
        }
    }
}
