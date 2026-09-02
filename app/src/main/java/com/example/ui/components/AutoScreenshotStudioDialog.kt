package com.example.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.DownloadHistoryItem
import com.example.data.storage.HistoryRepository
import com.example.data.storage.PdfStorageHelper
import com.example.engine.DriveExtractorEngine
import com.example.engine.PdfCompiler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class CapturedScreenshot(
    val id: String = UUID.randomUUID().toString(),
    val pageIndex: Int,
    val file: File,
    val thumbnail: Bitmap
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoScreenshotStudioDialog(
    url: String,
    onDismiss: () -> Unit,
    onPdfCreated: (Uri, String, Int) -> Unit = { _, _, _ -> }
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val historyRepository = remember { HistoryRepository(context) }

    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var isDocumentLoading by remember { mutableStateOf(true) }
    var docTitle by remember { mutableStateOf<String?>(null) }
    var detectedTotalPages by remember { mutableIntStateOf(0) }

    // Auto-scroll capture states
    var isAutoCapturing by remember { mutableStateOf(false) }
    var autoCaptureDelayMs by remember { mutableIntStateOf(950) } // Delay between scroll and screenshot
    var cleanDocumentMode by remember { mutableStateOf(true) } // Hides Google Drive headers/toolbars
    var showSettingsSheet by remember { mutableStateOf(false) }
    var previewScreenshotItem by remember { mutableStateOf<CapturedScreenshot?>(null) }

    // Compilation states
    var isCompilingPdf by remember { mutableStateOf(false) }
    var compileProgressText by remember { mutableStateOf("") }
    var compileProgressFraction by remember { mutableFloatStateOf(0f) }
    var completedPdfResult by remember { mutableStateOf<Triple<Uri, File, Int>?>(null) }

    // List of captured screenshots
    val capturedScreenshots = remember { mutableStateListOf<CapturedScreenshot>() }

    val sanitizedUrl = remember(url) {
        DriveExtractorEngine.sanitizeDriveUrl(url)
    }

    // Function to take screenshot of current WebView content
    fun takeCurrentScreenshot(pageNumber: Int? = null) {
        val wv = webViewInstance ?: return
        if (wv.width <= 0 || wv.height <= 0) return

        try {
            val fullBitmap = Bitmap.createBitmap(wv.width, wv.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(fullBitmap)
            wv.draw(canvas)

            // Cache bitmap to local disk file for 100% memory safety
            val screenshotDir = File(context.cacheDir, "screenshots_cache").apply {
                if (!exists()) mkdirs()
            }
            val pageNum = pageNumber ?: (capturedScreenshots.size + 1)
            val imageFile = File(screenshotDir, "shot_${System.currentTimeMillis()}_$pageNum.jpg")

            FileOutputStream(imageFile).use { out ->
                fullBitmap.compress(Bitmap.CompressFormat.JPEG, 94, out)
                out.flush()
            }

            // Create small thumbnail for UI carousel
            val thumbWidth = 140
            val thumbHeight = ((wv.height.toFloat() / wv.width.toFloat()) * thumbWidth).toInt().coerceAtLeast(100)
            val thumbnail = Bitmap.createScaledBitmap(fullBitmap, thumbWidth, thumbHeight, true)

            // Free full size bitmap immediately to keep memory ultra low
            fullBitmap.recycle()

            capturedScreenshots.add(
                CapturedScreenshot(
                    pageIndex = pageNum,
                    file = imageFile,
                    thumbnail = thumbnail
                )
            )
        } catch (e: Exception) {
            Log.e("ScreenshotStudio", "Failed to capture screenshot", e)
        }
    }

    // Auto-Scroll Loop
    LaunchedEffect(isAutoCapturing) {
        if (!isAutoCapturing) return@LaunchedEffect

        // Clean toolbars before capturing
        if (cleanDocumentMode) {
            webViewInstance?.evaluateJavascript(
                """
                (function() {
                    var toolbars = document.querySelectorAll('.drive-viewer-toolstrip, .drive-viewer-action-bar, .ndfHFb-c4YZDc-j7LFlb');
                    toolbars.forEach(function(t) { t.style.display = 'none'; });
                })();
                """.trimIndent(),
                null
            )
        }

        var consecutiveStagnant = 0
        var lastScroll = -1

        while (isActive && isAutoCapturing) {
            // Take screenshot of currently visible page
            takeCurrentScreenshot()

            // Wait for rendering delay
            delay(autoCaptureDelayMs.toLong())

            // Scroll down one page step
            val scrollScript = """
                (function() {
                    var selectors = ['.drive-viewer-paginated-scrollable', '.ndfHFb-c4YZDc-bN97Pc', '.ndfHFb-c4YZDc-Wrql6b', '.drive-viewer-content', '[role="main"]'];
                    var el = null;
                    for (var i = 0; i < selectors.length; i++) {
                        var c = document.querySelector(selectors[i]);
                        if (c && c.scrollHeight > c.clientHeight + 40) {
                            el = c;
                            break;
                        }
                    }
                    if (!el) {
                        var divs = document.querySelectorAll('div');
                        for (var j = 0; j < divs.length; j++) {
                            var d = divs[j];
                            if (d.scrollHeight > d.clientHeight + 80) {
                                var st = window.getComputedStyle(d);
                                if (st.overflowY === 'auto' || st.overflowY === 'scroll') {
                                    el = d;
                                    break;
                                }
                            }
                        }
                    }
                    if (!el) el = document.scrollingElement || document.documentElement || document.body;

                    var curScroll = el.scrollTop || window.pageYOffset || 0;
                    var maxScroll = (el.scrollHeight || document.body.scrollHeight) - (el.clientHeight || window.innerHeight);
                    var step = Math.max(500, (el.clientHeight || window.innerHeight || 800) * 0.90);

                    if (el.scrollTop !== undefined && el.scrollHeight > el.clientHeight) {
                        el.scrollTop += step;
                    }
                    window.scrollBy(0, step);

                    return {
                        cur: curScroll,
                        max: maxScroll,
                        atBottom: (maxScroll > 0 && curScroll >= maxScroll - 60)
                    };
                })();
            """.trimIndent()

            webViewInstance?.evaluateJavascript(scrollScript) { resJson ->
                // Check if stuck or reached end
                if (resJson != null && resJson.contains("\"atBottom\":true")) {
                    isAutoCapturing = false
                }
            }

            delay(350)
        }
    }

    Dialog(
        onDismissRequest = {
            isAutoCapturing = false
            onDismiss()
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header Bar
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shadowElevation = 6.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = {
                                    isAutoCapturing = false
                                    onDismiss()
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close Studio",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = docTitle ?: "Auto-Scroll Screenshot to PDF",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (isAutoCapturing) Color(0xFFE53935)
                                                else if (capturedScreenshots.isNotEmpty()) Color(0xFF2E7D32)
                                                else MaterialTheme.colorScheme.primary
                                            )
                                    )
                                    Text(
                                        text = if (isAutoCapturing) {
                                            "📸 Auto-Capturing (Page ${capturedScreenshots.size + 1})..."
                                        } else if (capturedScreenshots.isNotEmpty()) {
                                            "Captured ${capturedScreenshots.size} page screenshots"
                                        } else {
                                            "Ready for Screenshot Capture"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (isAutoCapturing) Color(0xFFE53935)
                                        else if (capturedScreenshots.isNotEmpty()) Color(0xFF2E7D32)
                                        else MaterialTheme.colorScheme.primary
                                    )
                                }
                            }

                            IconButton(onClick = { showSettingsSheet = !showSettingsSheet }) {
                                Icon(
                                    imageVector = Icons.Default.Tune,
                                    contentDescription = "Settings",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Studio Live Action Toolbar
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Primary Auto-Capture Toggle Button
                            Button(
                                onClick = { isAutoCapturing = !isAutoCapturing },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isAutoCapturing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                ),
                                modifier = Modifier
                                    .weight(1.3f)
                                    .testTag("auto_capture_toggle_button")
                            ) {
                                Icon(
                                    imageVector = if (isAutoCapturing) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (isAutoCapturing) "Pause" else "Start Auto-Capture",
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isAutoCapturing) "Pause Auto-Capture" else "▶️ Start Auto-Capture",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            // Manual Snapshot Button
                            OutlinedButton(
                                onClick = { takeCurrentScreenshot() },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CameraAlt,
                                    contentDescription = "Snap Page",
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Snap Page",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }

                            // Quick step scroll down
                            FilledTonalButton(
                                onClick = {
                                    webViewInstance?.evaluateJavascript(
                                        """
                                        (function() {
                                            var selectors = ['.drive-viewer-paginated-scrollable', '.ndfHFb-c4YZDc-bN97Pc', '.drive-viewer-content'];
                                            for (var i = 0; i < selectors.length; i++) {
                                                var el = document.querySelector(selectors[i]);
                                                if (el) el.scrollTop += 600;
                                            }
                                            window.scrollBy(0, 600);
                                        })();
                                        """.trimIndent(),
                                        null
                                    )
                                },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(0.8f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ArrowDownward,
                                    contentDescription = "Step Down",
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Text("Step", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }

                // Settings drawer / banner if opened
                AnimatedVisibility(visible = showSettingsSheet) {
                    Card(
                        shape = RoundedCornerShape(0.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Clean Document Only (Hide Toolbars)",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "Hides Google Drive header & buttons for clean PDF pages",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = cleanDocumentMode,
                                    onCheckedChange = { cleanDocumentMode = it }
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Capture Pause Interval",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf(750, 1000, 1500).forEach { ms ->
                                        FilledTonalButton(
                                            onClick = { autoCaptureDelayMs = ms },
                                            colors = ButtonDefaults.filledTonalButtonColors(
                                                containerColor = if (autoCaptureDelayMs == ms) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
                                            ),
                                            shape = RoundedCornerShape(8.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                            modifier = Modifier.height(32.dp)
                                        ) {
                                            Text("${ms / 1000f}s", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Interactive Native WebView Window
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )

                                isVerticalScrollBarEnabled = true
                                isHorizontalScrollBarEnabled = false

                                @SuppressLint("SetJavaScriptEnabled")
                                settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    databaseEnabled = true
                                    loadsImagesAutomatically = true
                                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                    userAgentString = DriveExtractorEngine.DESKTOP_USER_AGENT
                                    useWideViewPort = true
                                    loadWithOverviewMode = true
                                    setSupportZoom(true)
                                    builtInZoomControls = true
                                    displayZoomControls = false
                                }

                                CookieManager.getInstance().setAcceptCookie(true)
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                                class StudioJsBridge {
                                    @JavascriptInterface
                                    fun onTitleExtracted(title: String) {
                                        if (title.isNotBlank()) {
                                            Handler(Looper.getMainLooper()).post {
                                                docTitle = title
                                            }
                                        }
                                    }

                                    @JavascriptInterface
                                    fun onPagesTotalDetected(total: Int) {
                                        if (total > 0) {
                                            Handler(Looper.getMainLooper()).post {
                                                detectedTotalPages = total
                                            }
                                        }
                                    }
                                }

                                addJavascriptInterface(StudioJsBridge(), "StudioBridge")

                                webViewClient = object : WebViewClient() {
                                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                        isDocumentLoading = true
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        isDocumentLoading = false
                                        view?.evaluateJavascript(
                                            """
                                            (function() {
                                                try {
                                                    var title = document.title || "";
                                                    title = title.replace(" - Google Drive", "").replace(" - Google Docs", "").trim();
                                                    var nameEl = document.querySelector('.ndfHFb-c4YZDc-s2gQvd') || document.querySelector('.drive-viewer-toolstrip-name');
                                                    if (nameEl && nameEl.innerText) title = nameEl.innerText.trim();
                                                    if (title) window.StudioBridge.onTitleExtracted(title);

                                                    var textNodes = document.querySelectorAll('.drive-viewer-toolstrip, div, span');
                                                    for (var i = 0; i < textNodes.length; i++) {
                                                        var m = /(?:of|\/)\s*(\d+)/i.exec(textNodes[i].innerText || "");
                                                        if (m && m[1]) {
                                                            window.StudioBridge.onPagesTotalDetected(parseInt(m[1], 10));
                                                            break;
                                                        }
                                                    }
                                                } catch(e) {}
                                            })();
                                            """.trimIndent(),
                                            null
                                        )
                                    }
                                }

                                loadUrl(sanitizedUrl)
                                webViewInstance = this
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    if (isDocumentLoading) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(20.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    CircularProgressIndicator(
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(28.dp)
                                    )
                                    Text(
                                        text = "Loading Document for Screenshot Capture...",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }

                // Bottom Carousel of Captured Screenshots & Compile Button
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shadowElevation = 10.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Thumbnail Strip if any screenshots captured
                        if (capturedScreenshots.isNotEmpty()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "📸 Captured Pages (${capturedScreenshots.size}):",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                TextButton(
                                    onClick = { capturedScreenshots.clear() },
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text(
                                        text = "Clear All",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }

                            LazyRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(horizontal = 2.dp)
                            ) {
                                itemsIndexed(capturedScreenshots) { index, item ->
                                    Box(
                                        modifier = Modifier
                                            .width(72.dp)
                                            .height(96.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                                            .clickable { previewScreenshotItem = item }
                                    ) {
                                        Image(
                                            bitmap = item.thumbnail.asImageBitmap(),
                                            contentDescription = "Page ${index + 1}",
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )

                                        // Badge
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.BottomStart)
                                                .background(Color.Black.copy(alpha = 0.7f))
                                                .padding(horizontal = 4.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = "P${index + 1}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color.White,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }

                                        // Delete single
                                        IconButton(
                                            onClick = { capturedScreenshots.remove(item) },
                                            modifier = Modifier
                                                .size(22.dp)
                                                .align(Alignment.TopEnd)
                                                .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = "Remove",
                                                tint = Color.White,
                                                modifier = Modifier.size(12.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Final Action: Compile Screenshots into PDF
                        Button(
                            onClick = {
                                isAutoCapturing = false
                                if (capturedScreenshots.isEmpty()) {
                                    Toast.makeText(context, "Please capture at least 1 screenshot first!", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }

                                isCompilingPdf = true
                                compileProgressText = "Preparing ${capturedScreenshots.size} screenshots..."
                                compileProgressFraction = 0.05f

                                coroutineScope.launch {
                                    try {
                                        val filesList = capturedScreenshots.map { it.file }
                                        val compiler = PdfCompiler()
                                        val pdfBytes = compiler.compileImageFiles(filesList) { current, total ->
                                            compileProgressFraction = current.toFloat() / total.toFloat()
                                            compileProgressText = "Compiling screenshot page $current of $total..."
                                        }

                                        val finalTitle = docTitle ?: "Screenshot_Document"
                                        val fileName = PdfStorageHelper.generateFileName(finalTitle)

                                        val savedUri = PdfStorageHelper.savePdfToDownloads(
                                            context = context,
                                            pdfBytes = pdfBytes,
                                            fileName = fileName
                                        ).getOrThrow()

                                        val cachedFile = PdfStorageHelper.saveToAppCache(
                                            context = context,
                                            pdfBytes = pdfBytes,
                                            fileName = fileName
                                        )

                                        // Add to History
                                        val historyItem = DownloadHistoryItem(
                                            title = finalTitle,
                                            uriString = savedUri.toString(),
                                            pageCount = capturedScreenshots.size,
                                            fileSizeBytes = pdfBytes.size.toLong()
                                        )
                                        historyRepository.addItem(historyItem)

                                        isCompilingPdf = false
                                        completedPdfResult = Triple(savedUri, cachedFile, capturedScreenshots.size)
                                        onPdfCreated(savedUri, fileName, capturedScreenshots.size)
                                    } catch (e: Exception) {
                                        Log.e("ScreenshotStudio", "Compilation failed", e)
                                        isCompilingPdf = false
                                        Toast.makeText(context, "Error compiling PDF: ${e.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                            enabled = capturedScreenshots.isNotEmpty() && !isCompilingPdf,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .testTag("compile_screenshots_to_pdf_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.PictureAsPdf,
                                contentDescription = "Compile PDF",
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = if (capturedScreenshots.isNotEmpty()) {
                                    "📄 Compile PDF from ${capturedScreenshots.size} Screenshots"
                                } else {
                                    "Capture Screenshots to Build PDF"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }

    // Modal Progress Dialog during PDF compilation
    if (isCompilingPdf) {
        Dialog(
            onDismissRequest = { /* Non-cancelable during compile */ },
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
        ) {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PictureAsPdf,
                            contentDescription = "Compiling",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    Text(
                        text = "Creating PDF Document",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    LinearProgressIndicator(
                        progress = { compileProgressFraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                    )

                    Text(
                        text = compileProgressText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }

    // Success Dialog when PDF is successfully created from screenshots
    completedPdfResult?.let { (publicUri, cachedFile, pageCount) ->
        AlertDialog(
            onDismissRequest = { completedPdfResult = null },
            icon = {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Success",
                    tint = Color(0xFF2E7D32),
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    text = "PDF Successfully Created!",
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Your $pageCount-page PDF has been built from screenshots and saved to Downloads/DrivePDFs.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        PdfStorageHelper.openPdf(context, publicUri, cachedFile)
                    }
                ) {
                    Icon(imageVector = Icons.Default.OpenInNew, contentDescription = "Open")
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Open PDF")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        PdfStorageHelper.sharePdf(context, publicUri, cachedFile)
                    }
                ) {
                    Icon(imageVector = Icons.Default.Share, contentDescription = "Share")
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Share")
                }
            }
        )
    }

    // Screenshot Fullscreen Preview Dialog
    previewScreenshotItem?.let { item ->
        Dialog(onDismissRequest = { previewScreenshotItem = null }) {
            Card(
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Page Screenshot Preview",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(onClick = { previewScreenshotItem = null }) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    }

                    Image(
                        bitmap = item.thumbnail.asImageBitmap(),
                        contentDescription = "Page Preview",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(340.dp)
                            .clip(RoundedCornerShape(10.dp)),
                        contentScale = ContentScale.Fit
                    )

                    Button(
                        onClick = { previewScreenshotItem = null },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Close Preview")
                    }
                }
            }
        }
    }
}
