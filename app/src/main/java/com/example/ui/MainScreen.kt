package com.example.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.DownloadState
import com.example.ui.components.HistorySection
import com.example.ui.components.InteractiveWebViewSheet
import com.example.ui.components.ProgressCard
import com.example.ui.components.SuccessCard
import com.example.ui.components.UrlInputField
import com.example.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val downloadState by viewModel.downloadState.collectAsState()
    val inputUrl by viewModel.inputUrl.collectAsState()
    val resolutionQuality by viewModel.resolutionQuality.collectAsState()
    val isInteractiveMode by viewModel.isInteractiveMode.collectAsState()
    val documentTitle by viewModel.currentDocumentTitle.collectAsState()
    val historyItems by viewModel.historyItems.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    val isLoading = downloadState is DownloadState.LoadingWebView ||
            downloadState is DownloadState.InterceptingPages ||
            downloadState is DownloadState.DownloadingImages ||
            downloadState is DownloadState.CompilingPdf

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PictureAsPdf,
                                contentDescription = "PDF App Logo",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "Drive PDF Downloader",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Protected & View-Only PDF Compiler",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Feature Banner / Guide
            item {
                FeatureGuideCard()
            }

            // URL Input Card
            item {
                UrlInputField(
                    url = inputUrl,
                    onUrlChange = { viewModel.updateUrl(it) },
                    isLoading = isLoading,
                    selectedResolution = resolutionQuality,
                    onResolutionChange = { viewModel.setResolution(it) },
                    onStartDownload = { viewModel.startDownload() },
                    onToggleInteractiveMode = { viewModel.setInteractiveMode(true) }
                )
            }

            // Active Download / Compilation Progress
            if (isLoading) {
                item {
                    ProgressCard(
                        state = downloadState,
                        documentTitle = documentTitle,
                        onCancel = { viewModel.cancelDownload() },
                        onForceCompile = { viewModel.forceFinishExtraction() }
                    )
                }
            }

            // Success Card
            if (downloadState is DownloadState.Success) {
                val success = downloadState as DownloadState.Success
                item {
                    SuccessCard(
                        successState = success,
                        onOpenPdf = { viewModel.openPdf(success.uri, success.localPath) },
                        onSharePdf = { viewModel.sharePdf(success.uri, success.localPath, success.fileName) }
                    )
                }
            }

            // Error Card
            if (downloadState is DownloadState.Error) {
                val errorState = downloadState as DownloadState.Error
                item {
                    ErrorCard(
                        errorState = errorState,
                        onRetry = { viewModel.startDownload() },
                        onOpenInteractive = { viewModel.setInteractiveMode(true) },
                        onDismiss = { viewModel.cancelDownload() }
                    )
                }
            }

            // History Section
            item {
                HistorySection(
                    items = historyItems,
                    onOpenItem = { uri -> viewModel.openPdf(uri) },
                    onShareItem = { uri, title -> viewModel.sharePdf(uri, title = title) },
                    onDeleteItem = { id -> viewModel.deleteHistoryItem(id) },
                    onClearAll = { viewModel.clearAllHistory() }
                )
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // Modal Interactive Viewer Sheet
    if (isInteractiveMode) {
        InteractiveWebViewSheet(
            url = inputUrl.ifBlank { "https://drive.google.com" },
            onDismiss = { viewModel.setInteractiveMode(false) },
            onStartAutoExtraction = {
                viewModel.setInteractiveMode(false)
                viewModel.startDownload()
            }
        )
    }
}

@Composable
private fun FeatureGuideCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = "Feature",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "High-Quality Page Interceptor",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = "Extracts protected & view-only Drive PDFs into native, high-resolution A4 documents without download restrictions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                )
            }
        }
    }
}

@Composable
private fun ErrorCard(
    errorState: DownloadState.Error,
    onRetry: () -> Unit,
    onOpenInteractive: () -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("download_error_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
        )
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
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = "Extraction Issue",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }

            Text(
                text = errorState.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )

            errorState.details?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onOpenInteractive,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Text("Try Viewer Mode", style = MaterialTheme.typography.labelSmall)
                }

                if (errorState.canRetry) {
                    OutlinedButton(
                        onClick = onRetry,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Retry",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Retry", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
