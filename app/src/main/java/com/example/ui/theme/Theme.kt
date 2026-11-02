package com.example.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val WhiteSketchColorScheme = lightColorScheme(
    primary = PrimaryLight,
    onPrimary = OnPrimaryLight,
    primaryContainer = PrimaryContainerLight,
    onPrimaryContainer = OnPrimaryContainerLight,
    secondary = SecondaryLight,
    onSecondary = OnSecondaryLight,
    secondaryContainer = SecondaryContainerLight,
    onSecondaryContainer = OnSecondaryContainerLight,
    tertiary = TertiaryLight,
    onTertiary = OnTertiaryLight,
    tertiaryContainer = TertiaryContainerLight,
    onTertiaryContainer = OnTertiaryContainerLight,
    background = BackgroundLight,
    onBackground = OnBackgroundLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    outline = OutlineLight
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = false,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    // Always use the crisp White Sketch Drawing Theme as requested by the user
    MaterialTheme(
        colorScheme = WhiteSketchColorScheme,
        typography = Typography,
        content = content
    )
}

/**
 * Renders a warm white sketchbook paper background with a subtle architectural dot-grid and faint rule lines.
 */
@Composable
fun SketchPaperBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SketchPaperBg)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stepPx = 24.dp.toPx()
            val dotRadius = 1.15.dp.toPx()
            val dotColor = Color(0xFFD6D1C4).copy(alpha = 0.65f)

            var y = stepPx / 2f
            while (y < size.height) {
                var x = stepPx / 2f
                while (x < size.width) {
                    drawCircle(
                        color = dotColor,
                        radius = dotRadius,
                        center = Offset(x, y)
                    )
                    x += stepPx
                }
                y += stepPx
            }
        }
        content()
    }
}

/**
 * A hand-crafted White Sketch Card with a solid charcoal ink border and an architectural offset ink shadow.
 */
@Composable
fun SketchCard(
    modifier: Modifier = Modifier,
    containerColor: Color = SketchCardSurface,
    borderColor: Color = SketchInkBlack,
    shadowColor: Color = SketchInkBlack,
    cornerRadius: Dp = 14.dp,
    shadowOffset: Dp = 4.dp,
    borderWidth: Dp = 1.8.dp,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier.padding(bottom = shadowOffset, end = shadowOffset)
    ) {
        // Architectural offset ink shadow
        Box(
            modifier = Modifier
                .matchParentSize()
                .offset(x = shadowOffset, y = shadowOffset)
                .clip(shape)
                .background(shadowColor)
        )
        // Main crisp white drawing surface with charcoal ink border
        Box(
            modifier = Modifier
                .clip(shape)
                .background(containerColor)
                .border(width = borderWidth, color = borderColor, shape = shape),
            content = content
        )
    }
}
