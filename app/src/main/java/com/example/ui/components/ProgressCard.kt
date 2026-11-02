package com.example.ui.components

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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.FindInPage
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.DownloadState
import com.example.data.model.EngineTelemetry

@Composable
fun ProgressCard(
    state: DownloadState,
    documentTitle: String?,
    telemetry: EngineTelemetry = EngineTelemetry(),
    onCancel: () -> Unit,
    onForceCompile: () -> Unit,
    onOpenInteractive: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("download_progress_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header with Active Engine Pill and Cancel button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = telemetry.activeTierName,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = documentTitle ?: "Extracting Protected Document...",
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
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f))
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

            // Live 4-Tier Engine Telemetry Strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                    .padding(10.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TelemetryMetricItem(
                    label = "Streams",
                    value = "${telemetry.interceptedStreams}"
                )
                TelemetryMetricItem(
                    label = "Canvas",
                    value = "${telemetry.canvasHooksCaptured}"
                )
                TelemetryMetricItem(
                    label = "DOM Tiles",
                    value = "${telemetry.domTilesDiscovered}"
                )
                TelemetryMetricItem(
                    label = "Captured",
                    value = "${telemetry.precisionScreenshotsCaptured}/${telemetry.totalDetectedPages.coerceAtLeast(1)}"
                )
            }

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
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .testTag("linear_progress_bar"),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Speed,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = getDetailedStatusText(state),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    if (progressVal > 0f) {
                        Text(
                            text = "${(progressVal * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Actions row: Live Viewer & Instant Compile
            val capturedSoFar = maxOf(
                telemetry.precisionScreenshotsCaptured,
                telemetry.canvasHooksCaptured,
                telemetry.domTilesDiscovered,
                if (state is DownloadState.InterceptingPages) state.capturedCount else 0,
                if (state is DownloadState.DownloadingImages) state.current else 0
            )
            val showViewer = onOpenInteractive != null
            val showCompile = capturedSoFar > 0 && state !is DownloadState.CompilingPdf

            if (showViewer || showCompile) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (showViewer) {
                        OutlinedButton(
                            onClick = onOpenInteractive!!,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Visibility,
                                contentDescription = "Open Live Viewer",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Live Browser",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }

                    if (showCompile) {
                        Button(
                            onClick = onForceCompile,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1.3f)
                                .height(48.dp)
                                .testTag("force_compile_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.FastForward,
                                contentDescription = "Compile Captured Pages Now",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Build PDF ($capturedSoFar Pages)",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TelemetryMetricItem(
    label: String,
    value: String
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PipelineStagesRow(state: DownloadState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
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
            title = "1. Hook & Scan",
            icon = Icons.Outlined.FindInPage,
            isCurrent = stageIndex == 1,
            isDone = stageIndex > 1
        )
        StageItem(
            title = "2. Page Capture",
            icon = Icons.Outlined.CloudDownload,
            isCurrent = stageIndex == 2,
            isDone = stageIndex > 2
        )
        StageItem(
            title = "3. Build A4 PDF",
            icon = Icons.Default.PictureAsPdf,
            isCurrent = stageIndex == 3,
            isDone = stageIndex > 3
        )
    }
}

@Composable
private fun StageItem(
    title: String,
    icon: ImageVector,
    isCurrent: Boolean,
    isDone: Boolean
) {
    val containerColor = when {
        isDone -> MaterialTheme.colorScheme.secondaryContainer
        isCurrent -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    val contentColor = when {
        isDone -> MaterialTheme.colorScheme.onSecondaryContainer
        isCurrent -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(containerColor),
            contentAlignment = Alignment.Center
        ) {
            if (isDone) {
                Icon(
                    imageVector = Icons.Default.Done,
                    contentDescription = "Completed",
                    tint = contentColor,
                    modifier = Modifier.size(18.dp)
                )
            } else if (isCurrent) {
                CircularProgressIndicator(
                    color = contentColor,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp)
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
        is DownloadState.LoadingWebView -> "Probing streams, injecting Blob/Canvas hooks & loading document..."
        is DownloadState.InterceptingPages -> "Intercepting high-DPI page tiles & DOM containers"
        is DownloadState.DownloadingImages -> "Capturing full-resolution pages without splits or cutoffs"
        is DownloadState.CompilingPdf -> "Rendering pages into a native A4 PDF document"
        else -> "Working..."
    }
}

private fun getDetailedStatusText(state: DownloadState): String {
    return when (state) {
        is DownloadState.LoadingWebView -> state.message
        is DownloadState.InterceptingPages -> "${state.capturedCount} of ~${state.estimatedTotal} pages detected"
        is DownloadState.DownloadingImages -> state.message
        is DownloadState.CompilingPdf -> state.message
        else -> ""
    }
}

private fun getProgressFraction(state: DownloadState): Float {
    return when (state) {
        is DownloadState.LoadingWebView -> 0.06f
        is DownloadState.InterceptingPages -> {
            if (state.estimatedTotal > 0) {
                (state.capturedCount.toFloat() / state.estimatedTotal.toFloat() * 0.35f).coerceIn(0.06f, 0.35f)
            } else {
                0.15f
            }
        }
        is DownloadState.DownloadingImages -> 0.20f + (state.percent * 0.55f)
        is DownloadState.CompilingPdf -> 0.75f + (state.percent * 0.25f)
        is DownloadState.Success -> 1.0f
        else -> 0f
    }
}
