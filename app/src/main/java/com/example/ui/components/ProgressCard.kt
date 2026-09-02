package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.FindInPage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.DownloadState

@Composable
fun ProgressCard(
    state: DownloadState,
    documentTitle: String?,
    onCancel: () -> Unit,
    onForceCompile: () -> Unit,
    onOpenInteractive: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("download_progress_card"),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header with status and Cancel button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = documentTitle ?: "Processing Document...",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                    Text(
                        text = getStatusSubtitle(state),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .testTag("cancel_download_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cancel Download",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Pipeline Stage Badges
            PipelineStagesRow(state = state)

            // Progress Bar
            val progressVal = getProgressFraction(state)
            val animatedProgress by animateFloatAsState(targetValue = progressVal, label = "ProgressAnim")

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (progressVal > 0f) {
                    LinearProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .testTag("linear_progress_bar"),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surface
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .testTag("linear_progress_bar"),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surface
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = getDetailedStatusText(state),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (progressVal > 0f) {
                        Text(
                            text = "${(progressVal * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Actions row: Live Viewer & Force Compile
            val showViewer = onOpenInteractive != null && (state is DownloadState.LoadingWebView || state is DownloadState.InterceptingPages)
            val showCompile = state is DownloadState.InterceptingPages && state.capturedCount > 0

            if (showViewer || showCompile) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (showViewer) {
                        OutlinedButton(
                            onClick = onOpenInteractive!!,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Visibility,
                                contentDescription = "Open Live Viewer",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Live Viewer",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }

                    if (showCompile) {
                        Button(
                            onClick = onForceCompile,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1.3f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.FastForward,
                                contentDescription = "Compile Discovered Pages",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Compile (${(state as DownloadState.InterceptingPages).capturedCount} Pages)",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PipelineStagesRow(state: DownloadState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val stageIndex = when (state) {
            is DownloadState.LoadingWebView -> 1
            is DownloadState.InterceptingPages -> 1
            is DownloadState.DownloadingImages -> 2
            is DownloadState.CompilingPdf -> 3
            is DownloadState.Success -> 4
            else -> 0
        }

        StageItem(
            step = 1,
            title = "Intercept",
            icon = Icons.Outlined.FindInPage,
            isCurrent = stageIndex == 1,
            isDone = stageIndex > 1
        )
        StageItem(
            step = 2,
            title = "Download",
            icon = Icons.Outlined.CloudDownload,
            isCurrent = stageIndex == 2,
            isDone = stageIndex > 2
        )
        StageItem(
            step = 3,
            title = "Compile PDF",
            icon = Icons.Default.PictureAsPdf,
            isCurrent = stageIndex == 3,
            isDone = stageIndex > 3
        )
    }
}

@Composable
private fun StageItem(
    step: Int,
    title: String,
    icon: ImageVector,
    isCurrent: Boolean,
    isDone: Boolean
) {
    val containerColor = when {
        isDone -> MaterialTheme.colorScheme.primaryContainer
        isCurrent -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surface
    }

    val contentColor = when {
        isDone -> MaterialTheme.colorScheme.onPrimaryContainer
        isCurrent -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(containerColor),
            contentAlignment = Alignment.Center
        ) {
            if (isDone) {
                Icon(
                    imageVector = Icons.Default.Done,
                    contentDescription = "Done",
                    tint = contentColor,
                    modifier = Modifier.size(18.dp)
                )
            } else if (isCurrent) {
                CircularProgressIndicator(
                    color = contentColor,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(20.dp)
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = contentColor,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun getStatusSubtitle(state: DownloadState): String {
    return when (state) {
        is DownloadState.LoadingWebView -> "Connecting to headless browser engine..."
        is DownloadState.InterceptingPages -> "Scrolling & capturing page network requests"
        is DownloadState.DownloadingImages -> "Downloading full-resolution page bitmaps"
        is DownloadState.CompilingPdf -> "Rendering pages to native PDF document"
        else -> "Working..."
    }
}

private fun getDetailedStatusText(state: DownloadState): String {
    return when (state) {
        is DownloadState.LoadingWebView -> state.message
        is DownloadState.InterceptingPages -> "${state.capturedCount} of ~${state.estimatedTotal} pages detected"
        is DownloadState.DownloadingImages -> "Page ${state.current} / ${state.total} downloaded"
        is DownloadState.CompilingPdf -> "Rendering page ${state.currentPage} / ${state.totalPages}"
        else -> ""
    }
}

private fun getProgressFraction(state: DownloadState): Float {
    return when (state) {
        is DownloadState.LoadingWebView -> 0.05f
        is DownloadState.InterceptingPages -> {
            if (state.estimatedTotal > 0) {
                (state.capturedCount.toFloat() / state.estimatedTotal.toFloat() * 0.35f).coerceIn(0.05f, 0.35f)
            } else {
                0.15f
            }
        }
        is DownloadState.DownloadingImages -> 0.35f + (state.percent * 0.40f)
        is DownloadState.CompilingPdf -> 0.75f + (state.percent * 0.25f)
        is DownloadState.Success -> 1.0f
        else -> 0f
    }
}
