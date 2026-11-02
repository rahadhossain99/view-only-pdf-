package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.data.model.DownloadState
import com.example.ui.components.InteractiveWebViewSheet
import com.example.ui.components.ProgressCard
import com.example.ui.components.SuccessCard
import com.example.ui.components.UrlInputField
import com.example.ui.theme.SketchCard
import com.example.ui.theme.SketchCoralWash
import com.example.ui.theme.SketchCreamWash
import com.example.ui.theme.SketchCrimsonInk
import com.example.ui.theme.SketchGraphite
import com.example.ui.theme.SketchInkBlack
import com.example.ui.theme.SketchMarkerYellow
import com.example.ui.theme.SketchPaperBackground
import com.example.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val downloadState by viewModel.downloadState.collectAsState()
    val inputUrl by viewModel.inputUrl.collectAsState()
    val customFileName by viewModel.customFileName.collectAsState()
    val resolutionQuality by viewModel.resolutionQuality.collectAsState()
    val engineMode by viewModel.engineMode.collectAsState()
    val enhanceContrast by viewModel.enhanceContrast.collectAsState()
    val telemetry by viewModel.engineTelemetry.collectAsState()
    val isInteractiveMode by viewModel.isInteractiveMode.collectAsState()
    val documentTitle by viewModel.currentDocumentTitle.collectAsState()
    val isCompressingPdf by viewModel.isCompressingPdf.collectAsState()
    val compressionStatus by viewModel.compressionStatus.collectAsState()

    // Request notification permission on Android 13+ so top bar live % notification works seamlessly
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { }
    )

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasPerm = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasPerm) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    val isLoading = downloadState is DownloadState.LoadingWebView ||
            downloadState is DownloadState.InterceptingPages ||
            downloadState is DownloadState.DownloadingImages ||
            downloadState is DownloadState.CompilingPdf

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        topBar = {
            SketchTopHeader(
                onOpenFilesApp = {
                    val success = downloadState as? DownloadState.Success
                    viewModel.openInFiles(success?.uri ?: android.net.Uri.EMPTY, success?.localPath)
                },
                onOpenBrowserSignIn = { viewModel.setInteractiveMode(true) }
            )
        }
    ) { innerPadding ->
        SketchPaperBackground(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Keep the extraction WebView mounted inside visible screen bounds with near-zero alpha
            // so Google Drive's multi-page IntersectionObserver renders all pages rapidly in the background.
            AndroidView(
                factory = { ctx ->
                    viewModel.getOrCreateExtractionWebView(ctx)
                },
                modifier = Modifier
                    .width(380.dp)
                    .height(580.dp)
                    .align(Alignment.TopCenter)
                    .graphicsLayer { alpha = 0.015f }
            )

            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 600.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    // 1. Sketch Notebook Banner Note
                    item {
                        SketchNotebookNoteBanner()
                    }

                    // 2. Main White Sketch Input Card (Link + Custom Name + Upfront Size Selector)
                    item {
                        UrlInputField(
                            url = inputUrl,
                            onUrlChange = viewModel::updateUrl,
                            customFileName = customFileName,
                            onCustomFileNameChange = viewModel::updateCustomFileName,
                            isLoading = isLoading,
                            selectedResolution = resolutionQuality,
                            onResolutionChange = viewModel::setResolution,
                            selectedEngineMode = engineMode,
                            onEngineModeChange = viewModel::setEngineMode,
                            enhanceContrast = enhanceContrast,
                            onEnhanceContrastChange = viewModel::setEnhanceContrast,
                            onStartDownload = { viewModel.startDownload() },
                            onToggleInteractiveMode = { viewModel.setInteractiveMode(true) }
                        )
                    }

                    // 3. Live Percentage & Progress Sketch Card
                    item {
                        AnimatedVisibility(
                            visible = isLoading,
                            enter = fadeIn(),
                            exit = fadeOut()
                        ) {
                            ProgressCard(
                                state = downloadState,
                                documentTitle = documentTitle,
                                customFileName = customFileName,
                                onCustomFileNameChange = viewModel::updateCustomFileName,
                                telemetry = telemetry,
                                onCancel = viewModel::cancelDownload,
                                onForceCompile = viewModel::forceFinishExtraction,
                                onOpenInteractive = if (inputUrl.isNotBlank()) {
                                    { viewModel.setInteractiveMode(true) }
                                } else null
                            )
                        }
                    }

                    // 4. Completed Sketch Card (Inline Rename + Size Compressor + Open in Files)
                    item {
                        val currentSuccess = downloadState as? DownloadState.Success
                        AnimatedVisibility(
                            visible = currentSuccess != null,
                            enter = fadeIn(),
                            exit = fadeOut()
                        ) {
                            if (currentSuccess != null) {
                                SuccessCard(
                                    successState = currentSuccess,
                                    isCompressing = isCompressingPdf,
                                    compressionStatus = compressionStatus,
                                    onRenamePdf = { newName ->
                                        viewModel.renameCompletedPdf(newName)
                                    },
                                    onCompressPdf = { level ->
                                        viewModel.compressCompletedPdf(level)
                                    },
                                    onOpenInFiles = {
                                        viewModel.openInFiles(currentSuccess.uri, currentSuccess.localPath)
                                    },
                                    onOpenPdf = {
                                        viewModel.openPdf(currentSuccess.uri, currentSuccess.localPath)
                                    },
                                    onSharePdf = {
                                        viewModel.sharePdf(
                                            currentSuccess.uri,
                                            currentSuccess.localPath,
                                            currentSuccess.fileName
                                        )
                                    }
                                )
                            }
                        }
                    }

                    // 5. Error Sketch Card (if any error occurred)
                    item {
                        val currentError = downloadState as? DownloadState.Error
                        AnimatedVisibility(
                            visible = currentError != null,
                            enter = fadeIn(),
                            exit = fadeOut()
                        ) {
                            if (currentError != null) {
                                SketchErrorCard(
                                    error = currentError,
                                    onRetry = { viewModel.startDownload() },
                                    onOpenInteractive = { viewModel.setInteractiveMode(true) }
                                )
                            }
                        }
                    }

                    item {
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
            }
        }
    }

    // Interactive Browser Sheet for Private Google Drive Sign-In
    if (isInteractiveMode) {
        InteractiveWebViewSheet(
            url = inputUrl,
            onDismiss = { viewModel.setInteractiveMode(false) },
            onCompilePages = { pageUrls, title ->
                viewModel.setInteractiveMode(false)
                viewModel.startDownloadWithPages(pageUrls, title)
            },
            onStartAutoExtraction = {
                viewModel.setInteractiveMode(false)
                viewModel.startDownload()
            }
        )
    }
}

@Composable
private fun SketchTopHeader(
    onOpenFilesApp: () -> Unit,
    onOpenBrowserSignIn: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(SketchMarkerYellow)
                        .border(2.dp, SketchInkBlack, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Draw,
                        contentDescription = "Sketch PDF Logo",
                        tint = SketchInkBlack,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Column {
                    Text(
                        text = "Sketch PDF Downloader",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = SketchInkBlack
                    )
                    Text(
                        text = "View-Only Drive to Clean Compact PDF",
                        style = MaterialTheme.typography.labelSmall,
                        color = SketchGraphite
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = onOpenFilesApp,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(SketchCreamWash)
                        .border(1.5.dp, SketchInkBlack, RoundedCornerShape(10.dp))
                        .testTag("top_open_files_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = "Open Downloads in Files",
                        tint = SketchInkBlack,
                        modifier = Modifier.size(20.dp)
                    )
                }

                IconButton(
                    onClick = onOpenBrowserSignIn,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White)
                        .border(1.5.dp, SketchInkBlack, RoundedCornerShape(10.dp))
                        .testTag("top_interactive_browser_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Visibility,
                        contentDescription = "Browser Sign-In",
                        tint = SketchInkBlack,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // Solid Charcoal Sketch Rule Line
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(SketchInkBlack)
        )
    }
}

@Composable
private fun SketchNotebookNoteBanner() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SketchMarkerYellow.copy(alpha = 0.65f))
            .border(1.5.dp, SketchInkBlack, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "✦ ফাস্ট ও হালকা সাইজের ভিউ-অনলি পিডিএফ ডাউনলোডার",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.ExtraBold,
                color = SketchInkBlack
            )
            Text(
                text = "লিংক দিয়ে নিজের পছন্দমতো নাম ও সাইজ সিলেক্ট করুন। ডাউনলোড শেষে নোটিফিকেশনে বা নিচে 'Open in Files' বাটনে ট্যাপ করলে সরাসরি ফাইলস-এ ওপেন হবে।",
                style = MaterialTheme.typography.bodySmall,
                color = SketchInkBlack.copy(alpha = 0.85f)
            )
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .padding(top = 2.dp)
            ) {
                drawLine(
                    color = SketchInkBlack.copy(alpha = 0.35f),
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
                )
            }
        }
    }
}

@Composable
private fun SketchErrorCard(
    error: DownloadState.Error,
    onRetry: () -> Unit,
    onOpenInteractive: () -> Unit
) {
    SketchCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("download_error_card"),
        containerColor = SketchCoralWash,
        borderColor = SketchInkBlack,
        shadowColor = SketchInkBlack,
        cornerRadius = 16.dp,
        shadowOffset = 4.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ErrorOutline,
                    contentDescription = "Error",
                    tint = SketchCrimsonInk,
                    modifier = Modifier.size(26.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = error.message,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = SketchInkBlack
                    )
                    if (!error.details.isNullOrBlank()) {
                        Text(
                            text = error.details,
                            style = MaterialTheme.typography.bodySmall,
                            color = SketchGraphite
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onRetry,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                        .background(Color.White, RoundedCornerShape(10.dp))
                        .border(1.6.dp, SketchInkBlack, RoundedCornerShape(10.dp))
                        .testTag("retry_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Retry",
                        tint = SketchInkBlack,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "আবার চেষ্টা করুন",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = SketchInkBlack
                    )
                }

                Button(
                    onClick = onOpenInteractive,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                        .border(1.6.dp, SketchInkBlack, RoundedCornerShape(10.dp))
                        .testTag("error_interactive_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SketchInkBlack,
                        contentColor = Color.White
                    )
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Visibility,
                        contentDescription = "Sign In",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "ব্রাউজারে লগইন",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }
    }
}
