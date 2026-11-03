package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.DownloadState
import com.example.data.model.EngineTelemetry
import com.example.data.storage.PdfStorageHelper
import com.example.ui.theme.SketchCard
import com.example.ui.theme.SketchCoralWash
import com.example.ui.theme.SketchCreamWash
import com.example.ui.theme.SketchCrimsonInk
import com.example.ui.theme.SketchForestInk
import com.example.ui.theme.SketchGraphite
import com.example.ui.theme.SketchInkBlack
import com.example.ui.theme.SketchMarkerYellow
import com.example.ui.theme.SketchMintWash
import com.example.ui.theme.SketchPenBlue
import com.example.ui.theme.SketchPenBlueWash

@Composable
fun ProgressCard(
    state: DownloadState,
    documentTitle: String?,
    knownLinkTotalPages: Int = 0,
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

    val isCompiling = state is DownloadState.CompilingPdf

    val currentActivePage = when (state) {
        is DownloadState.CompilingPdf -> state.currentPage
        is DownloadState.DownloadingImages -> maxOf(
            state.current,
            telemetry.precisionScreenshotsCaptured,
            telemetry.domTilesDiscovered
        )
        is DownloadState.InterceptingPages -> maxOf(
            state.capturedCount,
            telemetry.precisionScreenshotsCaptured,
            telemetry.domTilesDiscovered
        )
        else -> maxOf(
            telemetry.precisionScreenshotsCaptured,
            telemetry.domTilesDiscovered
        )
    }

    val rawDetectedTotal = maxOf(
        knownLinkTotalPages,
        telemetry.totalDetectedPages,
        if (state is DownloadState.DownloadingImages) state.total else 0,
        if (state is DownloadState.InterceptingPages) state.estimatedTotal else 0,
        if (state is DownloadState.CompilingPdf) state.totalPages else 0
    )

    val confirmedTotalPages = if (rawDetectedTotal >= 1) {
        maxOf(rawDetectedTotal, currentActivePage)
    } else {
        0
    }

    val displayTitle = PdfStorageHelper.cleanDocumentTitle(customFileName)
        ?: PdfStorageHelper.cleanDocumentTitle(documentTitle)
        ?: "Processing Drive PDF..."

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
            // 1. Top Sketch Drawing Animation Banner with Live 1..N of Total Counter
            SketchPageLoadingAnimationBanner(
                currentPage = currentActivePage,
                confirmedTotalPages = confirmedTotalPages,
                percentInt = percentInt,
                isCompiling = isCompiling
            )

            // 2. Document Title + Live Percentage Stamp
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = displayTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = SketchInkBlack,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = getDetailedStatusText(state, currentActivePage, confirmedTotalPages),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = SketchGraphite,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

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

            // 3. Clean Dual Live Page Counter Cards (Counts 1 / 50 -> 2 / 50 -> ... -> 50 / 50)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Left Card: Step-by-Step Page Progress
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(SketchMintWash.copy(alpha = 0.65f))
                        .border(1.6.dp, SketchInkBlack, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Description,
                                contentDescription = null,
                                tint = SketchForestInk,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = if (isCompiling) "BINDING PAGE" else "PROCESSING PAGE",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = SketchGraphite
                            )
                        }
                        Text(
                            text = when {
                                confirmedTotalPages > 0 && currentActivePage > 0 ->
                                    "$currentActivePage / $confirmedTotalPages"
                                currentActivePage > 0 ->
                                    "Page $currentActivePage"
                                confirmedTotalPages > 0 ->
                                    "0 / $confirmedTotalPages"
                                else ->
                                    "Starting..."
                            },
                            style = MaterialTheme.typography.titleLarge,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.ExtraBold,
                            color = SketchInkBlack
                        )
                    }
                }

                // Right Card: Total Document Pages
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(SketchPenBlueWash.copy(alpha = 0.55f))
                        .border(1.6.dp, SketchInkBlack, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoStories,
                                contentDescription = null,
                                tint = SketchPenBlue,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "TOTAL PAGES",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = SketchGraphite
                            )
                        }
                        Text(
                            text = if (confirmedTotalPages > 0) {
                                "$confirmedTotalPages Pages"
                            } else {
                                "Counting..."
                            },
                            style = MaterialTheme.typography.titleLarge,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.ExtraBold,
                            color = SketchInkBlack
                        )
                    }
                }
            }

            // 4. Hand-Drawn Sketch Hatched Progress Bar
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

            // 5. Action Buttons: Save Now & Cancel
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (currentActivePage > 0 && state !is DownloadState.CompilingPdf) {
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
                            text = "Save Now ($currentActivePage Pages)",
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
                        text = "Cancel",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = SketchCrimsonInk
                    )
                }
            }
        }
    }
}

/**
 * White-Sketch Drawing Animation Banner at the top of the progress card.
 * Shows an animated sketch page scanner beam, flying pages transferring into a bound PDF book,
 * and a prominent live step-by-step counter (`Page 1 of 50` -> `Page 2 of 50` -> ... -> `Page 50 of 50`).
 */
@Composable
private fun SketchPageLoadingAnimationBanner(
    currentPage: Int,
    confirmedTotalPages: Int,
    percentInt: Int,
    isCompiling: Boolean
) {
    val infiniteTransition = rememberInfiniteTransition(label = "SketchScannerAnim")

    val scanSweep by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ScanSweep"
    )

    val pageFlight by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "PageFlight"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(106.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(SketchCreamWash.copy(alpha = 0.6f))
            .border(1.8.dp, SketchInkBlack, RoundedCornerShape(14.dp))
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // 1. Subtle notebook grid dots
            val dotStep = 18.dp.toPx()
            var gx = dotStep
            while (gx < w) {
                var gy = dotStep
                while (gy < h) {
                    drawCircle(
                        color = SketchInkBlack.copy(alpha = 0.1f),
                        radius = 1.2.dp.toPx(),
                        center = Offset(gx, gy)
                    )
                    gy += dotStep
                }
                gx += dotStep
            }

            // 2. Left Source Page Stack (Google Drive View-Only Pages)
            val leftPageX = 22.dp.toPx()
            val pageW = 46.dp.toPx()
            val pageH = 60.dp.toPx()
            val pageY = (h - pageH) / 2f

            // Back shadow page
            drawRoundRect(
                color = SketchInkBlack,
                topLeft = Offset(leftPageX + 3.dp.toPx(), pageY + 3.dp.toPx()),
                size = Size(pageW, pageH),
                cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
            )
            // Main left page
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(leftPageX, pageY),
                size = Size(pageW, pageH),
                cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
            )
            drawRoundRect(
                color = SketchInkBlack,
                topLeft = Offset(leftPageX, pageY),
                size = Size(pageW, pageH),
                cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
                style = Stroke(width = 2.dp.toPx())
            )

            // Sketch lines inside left page
            for (lineIdx in 0..3) {
                val ly = pageY + 14.dp.toPx() + lineIdx * 10.dp.toPx()
                drawLine(
                    color = SketchInkBlack.copy(alpha = 0.65f),
                    start = Offset(leftPageX + 8.dp.toPx(), ly),
                    end = Offset(leftPageX + pageW - 8.dp.toPx(), ly),
                    strokeWidth = 1.8.dp.toPx()
                )
            }

            // Animated yellow marker scanner beam sweeping up & down on left page
            val beamY = pageY + 6.dp.toPx() + (pageH - 12.dp.toPx()) * scanSweep
            drawRoundRect(
                color = SketchMarkerYellow.copy(alpha = 0.85f),
                topLeft = Offset(leftPageX + 3.dp.toPx(), beamY - 4.dp.toPx()),
                size = Size(pageW - 6.dp.toPx(), 8.dp.toPx()),
                cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
            )

            // 3. Right Destination Bound PDF Book
            val rightBookX = w - 22.dp.toPx() - pageW
            drawRoundRect(
                color = SketchInkBlack,
                topLeft = Offset(rightBookX + 3.dp.toPx(), pageY + 3.dp.toPx()),
                size = Size(pageW, pageH),
                cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
            )
            drawRoundRect(
                color = SketchMarkerYellow,
                topLeft = Offset(rightBookX, pageY),
                size = Size(pageW, pageH),
                cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
            )
            drawRoundRect(
                color = SketchInkBlack,
                topLeft = Offset(rightBookX, pageY),
                size = Size(pageW, pageH),
                cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
                style = Stroke(width = 2.dp.toPx())
            )
            // Book spine & lines
            drawLine(
                color = SketchInkBlack,
                start = Offset(rightBookX + 8.dp.toPx(), pageY),
                end = Offset(rightBookX + 8.dp.toPx(), pageY + pageH),
                strokeWidth = 1.8.dp.toPx()
            )
            for (lineIdx in 0..2) {
                val ly = pageY + 18.dp.toPx() + lineIdx * 11.dp.toPx()
                drawLine(
                    color = SketchInkBlack,
                    start = Offset(rightBookX + 14.dp.toPx(), ly),
                    end = Offset(rightBookX + pageW - 7.dp.toPx(), ly),
                    strokeWidth = 2.dp.toPx()
                )
            }

            // 4. Dashed Connecting Track & Animated Flying Sketch Page in Center
            val trackStartX = leftPageX + pageW + 12.dp.toPx()
            val trackEndX = rightBookX - 12.dp.toPx()
            val centerY = h / 2f

            if (trackEndX > trackStartX) {
                drawLine(
                    color = SketchInkBlack.copy(alpha = 0.35f),
                    start = Offset(trackStartX, centerY),
                    end = Offset(trackEndX, centerY),
                    strokeWidth = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                )

                val flyW = 28.dp.toPx()
                val flyH = 36.dp.toPx()
                val currentFlyX = trackStartX + (trackEndX - trackStartX - flyW) * pageFlight
                val arcOffsetY = -kotlin.math.sin(pageFlight * Math.PI.toFloat()) * 10.dp.toPx()
                val flyY = centerY - (flyH / 2f) + arcOffsetY

                drawRoundRect(
                    color = Color.White,
                    topLeft = Offset(currentFlyX, flyY),
                    size = Size(flyW, flyH),
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                )
                drawRoundRect(
                    color = SketchInkBlack,
                    topLeft = Offset(currentFlyX, flyY),
                    size = Size(flyW, flyH),
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                    style = Stroke(width = 1.6.dp.toPx())
                )
                drawLine(
                    color = SketchInkBlack,
                    start = Offset(currentFlyX + 5.dp.toPx(), flyY + 12.dp.toPx()),
                    end = Offset(currentFlyX + flyW - 5.dp.toPx(), flyY + 12.dp.toPx()),
                    strokeWidth = 1.5.dp.toPx()
                )
                drawLine(
                    color = SketchInkBlack,
                    start = Offset(currentFlyX + 5.dp.toPx(), flyY + 20.dp.toPx()),
                    end = Offset(currentFlyX + flyW - 8.dp.toPx(), flyY + 20.dp.toPx()),
                    strokeWidth = 1.5.dp.toPx()
                )
            }
        }

        // Top Live Step-by-Step Counter Badge Inside the Animation (`1 / 50`, `2 / 50` ... `50 / 50`)
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 7.dp)
                .clip(RoundedCornerShape(50))
                .background(SketchMarkerYellow)
                .border(1.5.dp, SketchInkBlack, RoundedCornerShape(50))
                .padding(horizontal = 14.dp, vertical = 3.dp)
                .testTag("animation_live_page_counter")
        ) {
            val topCounterText = when {
                confirmedTotalPages > 0 && currentPage > 0 ->
                    "Page $currentPage of $confirmedTotalPages"
                confirmedTotalPages > 0 ->
                    "0 of $confirmedTotalPages Pages"
                currentPage > 0 ->
                    "Page $currentPage"
                else ->
                    "Scanning Pages..."
            }
            Text(
                text = topCounterText,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.ExtraBold,
                color = SketchInkBlack
            )
        }

        // Bottom Floating Live Status Pill inside the Animation
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 6.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.95f))
                .border(1.4.dp, SketchInkBlack, RoundedCornerShape(50))
                .padding(horizontal = 12.dp, vertical = 3.dp)
        ) {
            val pillText = when {
                isCompiling && confirmedTotalPages > 0 ->
                    "Binding PDF • $currentPage / $confirmedTotalPages ($percentInt%)"
                isCompiling ->
                    "Binding PDF • $percentInt%"
                confirmedTotalPages > 0 && currentPage > 0 ->
                    "Extracting • $currentPage / $confirmedTotalPages ($percentInt%)"
                currentPage > 0 ->
                    "Extracting Page $currentPage • $percentInt%"
                else ->
                    "Connecting to Google Drive..."
            }
            Text(
                text = pillText,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.ExtraBold,
                color = SketchInkBlack
            )
        }
    }
}

private fun getDetailedStatusText(
    state: DownloadState,
    currentPage: Int,
    confirmedTotalPages: Int
): String {
    return when (state) {
        is DownloadState.LoadingWebView -> {
            if (confirmedTotalPages > 0) {
                "Preparing to extract all $confirmedTotalPages pages..."
            } else {
                "Connecting to Google Drive & counting pages..."
            }
        }
        is DownloadState.InterceptingPages -> {
            if (confirmedTotalPages > 0) {
                "Detected $confirmedTotalPages pages — loading page $currentPage of $confirmedTotalPages..."
            } else {
                "Scanning document pages ($currentPage loaded)..."
            }
        }
        is DownloadState.DownloadingImages -> {
            if (confirmedTotalPages > 0) {
                "Capturing page ${state.current} of $confirmedTotalPages..."
            } else {
                "Capturing page ${state.current}..."
            }
        }
        is DownloadState.CompilingPdf -> {
            if (state.totalPages > 0) {
                "Binding page ${state.currentPage} of ${state.totalPages} into PDF..."
            } else {
                "Building final PDF document..."
            }
        }
        else -> "Processing..."
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
