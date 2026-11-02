package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// White Sketch Drawing Theme Palette (হোয়াইট স্কেচ ড্রয়িং থিম)
val SketchPaperBg = Color(0xFFFAF8F4)       // Warm White Sketchbook Paper
val SketchCardSurface = Color(0xFFFFFFFF)   // Pure Crisp White Drawing Card
val SketchCreamWash = Color(0xFFF3EFE6)     // Soft Cream Wash for Inputs & Pills
val SketchInkBlack = Color(0xFF18181B)      // Solid Charcoal Drawing Ink
val SketchGraphite = Color(0xFF52525B)      // Pencil Graphite Subtitle Text
val SketchLightRule = Color(0xFFE4E0D5)     // Faint Sketchbook Grid / Rule Line

val SketchMarkerYellow = Color(0xFFFEF08A)  // Highlighter Marker Yellow Accent
val SketchPenBlue = Color(0xFF1D4ED8)       // Fountain Pen Royal Blue
val SketchPenBlueWash = Color(0xFFDBEAFE)   // Light Watercolor Blue Wash
val SketchMintWash = Color(0xFFDCFCE7)      // Soft Sketch Mint Wash
val SketchForestInk = Color(0xFF15803D)     // Forest Green Marker Ink
val SketchCoralWash = Color(0xFFFEE2E2)     // Soft Coral Wash for Errors/Cancel
val SketchCrimsonInk = Color(0xFFB91C1C)    // Crimson Ink

// Material 3 mapping (Forced Light White Sketch Theme for authentic paper look)
val PrimaryLight = SketchInkBlack
val OnPrimaryLight = Color(0xFFFFFFFF)
val PrimaryContainerLight = SketchMarkerYellow
val OnPrimaryContainerLight = SketchInkBlack

val SecondaryLight = SketchPenBlue
val OnSecondaryLight = Color(0xFFFFFFFF)
val SecondaryContainerLight = SketchPenBlueWash
val OnSecondaryContainerLight = SketchInkBlack

val TertiaryLight = SketchForestInk
val OnTertiaryLight = Color(0xFFFFFFFF)
val TertiaryContainerLight = SketchMintWash
val OnTertiaryContainerLight = SketchInkBlack

val BackgroundLight = SketchPaperBg
val OnBackgroundLight = SketchInkBlack
val SurfaceLight = SketchCardSurface
val OnSurfaceLight = SketchInkBlack
val SurfaceVariantLight = SketchCreamWash
val OnSurfaceVariantLight = SketchGraphite
val OutlineLight = SketchInkBlack
