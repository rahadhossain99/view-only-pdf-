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
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
    val percentInt = (progressVal * 100f).toInt().coerceIn(1, 99)

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

    SketchCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag("download_progress_card"),
        containerColor = Color.White,
        borderColor = SketchInkBlack,
        shadowColor = SketchInkBlack,
        cornerRadius = 16.dp,
        shadowOffset = 4.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Top Row: Title + Large Sketch Percentage Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.NotificationsActive,
                            contentDescription = "Notification Active",
                            tint = SketchInkBlack,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "নোটিফিকেশনে লাইভ প্রগ্রেস চলছে",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = SketchGraphite
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = customFileName.ifBlank { documentTitle ?: "পিডিএফ তৈরি হচ্ছে..." },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = SketchInkBlack,
                        maxLines = 1
                    )
                    Text(
                        text = getDetailedStatusText(state, capturedPages, totalPages),
                        style = MaterialTheme.typography.bodySmall,
                        color = SketchGraphite,
                        maxLines = 2
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                // Big Sketch Percentage Stamp
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(SketchMarkerYellow)
                        .border(2.dp, SketchInkBlack, RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "$percentInt%",
                        style = MaterialTheme.typography.headlineSmall,
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
                    .height(22.dp)
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

            // 3. Live Editable PDF File Name while downloading!
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DriveFileRenameOutline,
                        contentDescription = null,
                        tint = SketchInkBlack,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = "ডাউনলোড চলাকালীন পিডিএফ-এর নাম পরিবর্তন করুন:",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = SketchInkBlack
                    )
                }

                OutlinedTextField(
                    value = customFileName,
                    onValueChange = onCustomFileNameChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("progress_pdf_name_input"),
                    placeholder = {
                        Text(
                            text = documentTitle ?: "নতুন পিডিএফ-এর নাম লিখুন...",
                            style = MaterialTheme.typography.bodySmall,
                            color = SketchGraphite.copy(alpha = 0.65f)
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = SketchCreamWash.copy(alpha = 0.4f),
                        unfocusedContainerColor = SketchCreamWash.copy(alpha = 0.25f),
                        focusedBorderColor = SketchInkBlack,
                        unfocusedBorderColor = SketchInkBlack.copy(alpha = 0.55f),
                        focusedTextColor = SketchInkBlack,
                        unfocusedTextColor = SketchInkBlack
                    )
                )
            }

            // 4. Action Buttons: Instant Save (if pages captured) & Cancel
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
                            .height(48.dp)
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
                        .height(48.dp)
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
        is DownloadState.LoadingWebView -> "ডকুমেন্ট লোড হচ্ছে এবং পেজ স্ক্যান করা হচ্ছে..."
        is DownloadState.InterceptingPages -> "পেজ স্ক্যান চলছে ($capturedPages / ~$totalPages পেজ)..."
        is DownloadState.DownloadingImages -> {
            if (totalPages > 0) {
                "পেজ ${state.current} / $totalPages স্ক্রিনশট নেওয়া হয়েছে..."
            } else {
                state.message
            }
        }
        is DownloadState.CompilingPdf -> "সবগুলো পেজ যুক্ত করে পারফেক্ট পিডিএফ তৈরি হচ্ছে..."
        else -> "কাজ চলছে..."
    }
}

private fun getProgressFraction(state: DownloadState): Float {
    return when (state) {
        is DownloadState.LoadingWebView -> 0.05f
        is DownloadState.InterceptingPages -> {
            if (state.estimatedTotal > 0) {
                (state.capturedCount.toFloat() / state.estimatedTotal.toFloat() * 0.40f).coerceIn(0.06f, 0.40f)
            } else {
                0.12f
            }
        }
        is DownloadState.DownloadingImages -> state.percent.coerceIn(0.06f, 0.90f)
        is DownloadState.CompilingPdf -> state.percent.coerceIn(0.90f, 0.99f)
        is DownloadState.Success -> 1.0f
        else -> 0f
    }
}
