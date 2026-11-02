package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.DownloadState
import com.example.data.model.EngineTelemetry
import com.example.data.storage.PdfStorageHelper
import com.example.ui.theme.SketchCard
import com.example.ui.theme.SketchCoralWash
import com.example.ui.theme.SketchCreamWash
import com.example.ui.theme.SketchCrimsonInk
import com.example.ui.theme.SketchGraphite
import com.example.ui.theme.SketchInkBlack
import com.example.ui.theme.SketchMarkerYellow

@Composable
fun ProgressCard(
    state: DownloadState,
    documentTitle: String?,
    customFileName: String = "",
    onCustomFileNameChange: (String) -> Unit = {},
    telemetry: EngineTelemetry = EngineTelemetry(),
    onCancel: () -> Unit,
    onForceCompile: () -> Unit,
    onOpenInteractive: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val progressVal = getProgressFraction(state)
    val animatedProgress by animateFloatAsState(targetValue = progressVal, label = "SketchProgress")
    val percentInt = (progressVal * 100f).toInt().coerceIn(2, 99)

    val capturedPages = maxOf(
        telemetry.precisionScreenshotsCaptured,
        if (state is DownloadState.DownloadingImages) state.current else 0,
        if (state is DownloadState.InterceptingPages) state.capturedCount else 0
    )
    val totalPages = maxOf(
        telemetry.totalDetectedPages,
        if (state is DownloadState.DownloadingImages) state.total else 0,
        capturedPages
    )

    val displayTitle = PdfStorageHelper.cleanDocumentTitle(customFileName)
        ?: PdfStorageHelper.cleanDocumentTitle(documentTitle)
        ?: "পিডিএফ প্রসেসিং চলছে..."

    SketchCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag("download_progress_card"),
        containerColor = Color.White,
        borderColor = SketchInkBlack,
        shadowColor = SketchInkBlack,
        cornerRadius = 18.dp,
        shadowOffset = 4.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Clean Top Row: Document Title + Page Status + Percentage Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = displayTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = SketchInkBlack,
                        maxLines = 1
                    )
                    Text(
                        text = getDetailedStatusText(state, capturedPages, totalPages),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = SketchGraphite,
                        maxLines = 2
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Big Sketch Percentage Stamp
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(SketchMarkerYellow)
                        .border(2.dp, SketchInkBlack, RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "$percentInt%",
                        style = MaterialTheme.typography.titleLarge,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.ExtraBold,
                        color = SketchInkBlack
                    )
                }
            }

            // 2. Hand-Drawn Sketch Hatched Progress Bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(20.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(SketchCreamWash)
                    .border(1.8.dp, SketchInkBlack, RoundedCornerShape(8.dp))
                    .testTag("linear_progress_bar")
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(animatedProgress.coerceIn(0.04f, 1f))
                        .background(SketchMarkerYellow)
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val step = 10.dp.toPx()
                        var x = -size.height
                        while (x < size.width + size.height) {
                            drawLine(
                                color = SketchInkBlack.copy(alpha = 0.22f),
                                start = Offset(x, size.height),
                                end = Offset(x + size.height, 0f),
                                strokeWidth = 2.dp.toPx()
                            )
                            x += step
                        }
                    }
                }
            }

            // 3. Action Buttons: Instant Save (if pages captured) & Cancel
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (capturedPages > 0 && state !is DownloadState.CompilingPdf) {
                    Button(
                        onClick = onForceCompile,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1.3f)
                            .height(46.dp)
                            .border(1.8.dp, SketchInkBlack, RoundedCornerShape(10.dp))
                            .testTag("force_compile_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SketchInkBlack,
                            contentColor = Color.White
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.FastForward,
                            contentDescription = "Save Captured Pages Now",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "এখনই সেভ করুন ($capturedPages পেজ)",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                OutlinedButton(
                    onClick = onCancel,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                        .background(SketchCoralWash, RoundedCornerShape(10.dp))
                        .border(1.8.dp, SketchInkBlack, RoundedCornerShape(10.dp))
                        .testTag("cancel_download_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cancel Download",
                        tint = SketchCrimsonInk,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "বাতিল করুন",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = SketchCrimsonInk
                    )
                }
            }
        }
    }
}

private fun getDetailedStatusText(
    state: DownloadState,
    capturedPages: Int,
    totalPages: Int
): String {
    return when (state) {
        is DownloadState.LoadingWebView -> "গুগল ড্রাইভ থেকে পেজ স্ক্যান করা হচ্ছে..."
        is DownloadState.InterceptingPages -> "পেজ শনাক্ত করা হচ্ছে ($capturedPages / ~$totalPages পেজ)..."
        is DownloadState.DownloadingImages -> {
            if (totalPages > 0) {
                "পেজ ${state.current} / $totalPages প্রস্তুত হয়েছে..."
            } else {
                state.message
            }
        }
        is DownloadState.CompilingPdf -> {
            if (state.totalPages > 0) {
                "পিডিএফ বাইন্ডিং চলছে (${state.currentPage} / ${state.totalPages} পেজ)..."
            } else {
                "সবগুলো পেজ যুক্ত করে পিডিএফ তৈরি হচ্ছে..."
            }
        }
        else -> "কাজ চলছে..."
    }
}

private fun getProgressFraction(state: DownloadState): Float {
    return when (state) {
        is DownloadState.LoadingWebView -> 0.04f
        is DownloadState.InterceptingPages -> {
            if (state.estimatedTotal > 0) {
                (state.capturedCount.toFloat() / state.estimatedTotal.toFloat() * 0.35f).coerceIn(0.05f, 0.35f)
            } else {
                0.10f
            }
        }
        is DownloadState.DownloadingImages -> state.percent.coerceIn(0.05f, 0.85f)
        is DownloadState.CompilingPdf -> {
            if (state.percent >= 0.85f) {
                state.percent.coerceIn(0.86f, 0.99f)
            } else {
                val frac = state.currentPage.toFloat() / state.totalPages.coerceAtLeast(1).toFloat()
                (0.86f + frac * 0.13f).coerceIn(0.86f, 0.99f)
            }
        }
        is DownloadState.Success -> 1.0f
        else -> 0f
    }
}
