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
    private var sampleImageUrl: String? = null
    private var hasTriggeredSessionGeneration = false

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

            // Fallback: If it's already a drive/docs URL, ensure it has /preview
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

        /**
         * Session URL Generation:
         * Given a captured image URL with a page parameter pattern and total page count,
         * synthesizes URLs for all pages (1..totalPages) using the session base URL structure.
         */
        fun generateSessionPageUrls(
            firstImageUrl: String,
            totalPages: Int,
            quality: ResolutionQuality
        ): List<Pair<Int, String>> {
            if (totalPages <= 0) return emptyList()

            val pagePatterns = listOf(
                Regex("([?&]page=)(\\d+)") to "$1",
                Regex("([?&]pg=)(\\d+)") to "$1",
                Regex("([?&]p=)(\\d+)") to "$1",
                Regex("(page_)(\\d+)") to "$1",
                Regex("(/page/)(\\d+)") to "$1"
            )

            for ((regex, prefix) in pagePatterns) {
                if (regex.containsMatchIn(firstImageUrl)) {
                    val result = mutableListOf<Pair<Int, String>>()
                    for (i in 1..totalPages) {
                        val replaced = firstImageUrl.replace(regex, "$prefix$i")
                        val highRes = upgradeImageUrlResolution(replaced, quality)
                        result.add(Pair(i, highRes))
                    }
                    return result
                }
            }

            return emptyList()
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
        sampleImageUrl = null
        hasTriggeredSessionGeneration = false
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
                onStatusUpdate?.invoke("Document viewer loaded. Initializing automated auto-scroll...")
                injectPageScraperScript(view)
            }
        }

        wv.loadUrl(targetUrl)
    }

    private fun handleInterceptedUrl(url: String) {
        val isDriveImage = url.contains("drive.google.com/viewer/img") ||
                url.contains("drive-viewer") ||
                url.contains("googleusercontent.com/drive-viewer") ||
                url.contains("drive.google.com/thumbnail")

        if (!isDriveImage) return

        if (sampleImageUrl == null) {
            sampleImageUrl = url
        }

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

        // Check if we can trigger session URL generation
        checkAndTriggerSessionUrlGeneration()
    }

    private fun checkAndTriggerSessionUrlGeneration() {
        val sample = sampleImageUrl ?: return
        if (hasTriggeredSessionGeneration || estimatedTotalPages < 2) return

        val synthesized = generateSessionPageUrls(sample, estimatedTotalPages, currentResolution)
        if (synthesized.isNotEmpty() && synthesized.size >= estimatedTotalPages) {
            hasTriggeredSessionGeneration = true
            synthesized.forEach { (pNum, pUrl) ->
                capturedPages[pNum] = pUrl
            }
            onPagesDiscovered?.invoke(capturedPages.size, estimatedTotalPages)
            onStatusUpdate?.invoke("Auto-generated session URLs for all $estimatedTotalPages pages!")

            // Short delay to allow any final metadata extraction, then complete
            scope.launch(Dispatchers.Main) {
                delay(600)
                if (isActive && isExtracting) {
                    completeExtraction()
                }
            }
        }
    }

    /**
     * Automated JavaScript Auto-Scroll Script:
     * Systematically scrolls through Google Drive's virtualized DOM containers to trigger
     * rendering of all lazy-loaded pages (e.g. 40+ pages) and captures page images and total count.
     */
    private fun injectPageScraperScript(view: WebView?) {
        val jsScript = """
            (function() {
                try {
                    // 1. Extract Document Title
                    var title = document.title || "";
                    title = title.replace(" - Google Drive", "").replace(" - Google Docs", "").trim();
                    var titleElem = document.querySelector('.ndfHFb-c4YZDc-s2gQvd') || 
                                    document.querySelector('.drive-viewer-toolstrip-name') ||
                                    document.querySelector('.drive-viewer-title');
                    if (titleElem && titleElem.innerText) {
                        title = titleElem.innerText.trim();
                    }
                    if (title) {
                        window.DriveBridge.onTitleFound(title);
                    }

                    // 2. Discover Total Pages from UI Indicators (e.g. '1 / 45' or 'Page 1 of 45')
                    function scanTotalPages() {
                        var totalPages = 0;
                        var allElements = document.querySelectorAll('div, span, p, [role="status"], .drive-viewer-toolstrip-page-number');
                        for (var i = 0; i < allElements.length; i++) {
                            var text = (allElements[i].innerText || allElements[i].textContent || "").trim();
                            if (text.length < 30) {
                                var match = text.match(/(?:page\s*)?(\d+)\s*(?:\/|of)\s*(\d+)/i);
                                if (match) {
                                    var count = parseInt(match[2]);
                                    if (count > totalPages && count < 2000) {
                                        totalPages = count;
                                    }
                                }
                            }
                        }

                        // Also query page wrapper divs in DOM
                        var pageDivs = document.querySelectorAll('.ndfHFb-c4YZDc-cYSp0e-DARUcf, .ndfHFb-c4YZDc-j7LFlb, div[role="region"], div[data-page-index], .drive-viewer-page');
                        if (pageDivs.length > totalPages) {
                            totalPages = pageDivs.length;
                        }

                        if (totalPages > 0) {
                            window.DriveBridge.onTotalPagesEstimated(totalPages);
                        }
                        return totalPages;
                    }

                    scanTotalPages();

                    // 3. Scan DOM for currently rendered <img> elements
                    function scanImages() {
                        var imgs = document.querySelectorAll('img');
                        imgs.forEach(function(img, index) {
                            var src = img.src || img.getAttribute('src') || '';
                            if (src && (src.indexOf('viewer/img') !== -1 || 
                                        src.indexOf('drive-viewer') !== -1 || 
                                        src.indexOf('googleusercontent.com') !== -1 ||
                                        src.indexOf('drive.google.com/thumbnail') !== -1)) {
                                var pIndex = index + 1;
                                var parentPage = img.closest('[data-page-index], [aria-label*="Page"], .ndfHFb-c4YZDc-cYSp0e-DARUcf, .drive-viewer-page');
                                if (parentPage) {
                                    var label = parentPage.getAttribute('aria-label') || 
                                                parentPage.getAttribute('data-page-index') || 
                                                parentPage.id || '';
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

                    // 4. Setup MutationObserver to immediately catch newly added images during virtualization
                    var observer = new MutationObserver(function(mutations) {
                        scanTotalPages();
                        scanImages();
                    });
                    observer.observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['src'] });

                    // 5. Automated Virtual Scroller: Finds all scrollable viewports
                    function findScrollContainers() {
                        var list = [document.scrollingElement, document.documentElement, document.body];
                        var candidates = document.querySelectorAll('div, section, main, [role="main"]');
                        candidates.forEach(function(el) {
                            if (el.scrollHeight > el.clientHeight + 100 && el.clientHeight > 100) {
                                list.push(el);
                            }
                        });
                        return list;
                    }

                    var scrollables = findScrollContainers();
                    var currentScroll = 0;
                    var scrollStep = 700;
                    var idleCount = 0;
                    var lastScrollHeight = document.body.scrollHeight;

                    var scrollerInterval = setInterval(function() {
                        currentScroll += scrollStep;
                        scrollables = findScrollContainers();

                        scrollables.forEach(function(s) {
                            if (s) {
                                s.scrollTop = currentScroll;
                            }
                        });
                        window.scrollBy(0, scrollStep);

                        scanTotalPages();
                        scanImages();

                        var maxScroll = Math.max(
                            document.body.scrollHeight,
                            document.documentElement.scrollHeight,
                            ...scrollables.map(function(s) { return s ? s.scrollHeight : 0; })
                        );

                        if (maxScroll > lastScrollHeight) {
                            lastScrollHeight = maxScroll;
                            idleCount = 0;
                        } else if (currentScroll >= maxScroll) {
                            idleCount++;
                        }

                        // Stop when reached bottom or idle for 3 consecutive ticks past scroll limit
                        if (idleCount >= 3 || currentScroll > 150000) {
                            clearInterval(scrollerInterval);
                            observer.disconnect();
                            setTimeout(function() {
                                scanImages();
                                window.DriveBridge.onScrollerFinished();
                            }, 800);
                        }
                    }, 250);

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
                checkAndTriggerSessionUrlGeneration()
            }
        }

        @JavascriptInterface
        fun onImageFound(pageIndex: Int, src: String) {
            handleInterceptedUrl(src)
        }

        @JavascriptInterface
        fun onScrollerFinished() {
            onStatusUpdate?.invoke("Automated scroll complete. Finalizing page interception...")
            scope.launch(Dispatchers.Main) {
                delay(1000)
                if (isActive && isExtracting) {
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
