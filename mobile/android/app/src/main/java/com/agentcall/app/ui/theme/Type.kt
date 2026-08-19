package com.agentcall.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.agentcall.app.R

// Industrial register: Fraunces Black serif for display identity, clean sans
// for body, monospace for every readout. Uppercase text is applied at call
// sites. Fraunces is a single variable file; the display cut is requested via
// variation settings (wght 900, opsz 36) so headers render as Black display
// type instead of the text cut. License: SIL OFL 1.1 (docs/fonts/OFL-Fraunces.txt).

@OptIn(ExperimentalTextApi::class)
private val FrauncesDisplay = FontFamily(
    Font(
        resId = R.font.fraunces_variable,
        weight = FontWeight.Black,
        variationSettings = FontVariation.Settings(
            FontVariation.Setting("wght", 900f),
            FontVariation.Setting("opsz", 36f),
        ),
    ),
)

val Typography = Typography(
    displayLarge = TextStyle(
        fontFamily = FrauncesDisplay,
        fontWeight = FontWeight.Black,
        fontSize = 40.sp,
        lineHeight = 48.sp,
        letterSpacing = 2.sp,
    ),
    displayMedium = TextStyle(
        fontFamily = FrauncesDisplay,
        fontWeight = FontWeight.Black,
        fontSize = 34.sp,
        lineHeight = 42.sp,
        letterSpacing = 1.5.sp,
    ),
    displaySmall = TextStyle(
        fontFamily = FrauncesDisplay,
        fontWeight = FontWeight.Black,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = 1.2.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FrauncesDisplay,
        fontWeight = FontWeight.Black,
        fontSize = 30.sp,
        lineHeight = 38.sp,
        letterSpacing = 1.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FrauncesDisplay,
        fontWeight = FontWeight.Black,
        fontSize = 26.sp,
        lineHeight = 34.sp,
        letterSpacing = 1.2.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FrauncesDisplay,
        fontWeight = FontWeight.Black,
        fontSize = 22.sp,
        lineHeight = 30.sp,
        letterSpacing = 1.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.8.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.6.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.5.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.2.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.15.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 1.2.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 1.4.sp,
    ),
)

// ── Readout roles (monospace data) ───────────
val MonoBody = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Normal,
    fontSize = 12.sp,
    lineHeight = 16.sp,
    letterSpacing = 0.5.sp,
)
val MonoLabel = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Medium,
    fontSize = 11.sp,
    lineHeight = 16.sp,
    letterSpacing = 0.8.sp,
)
val MonoTitle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.SemiBold,
    fontSize = 16.sp,
    lineHeight = 20.sp,
    letterSpacing = 0.6.sp,
)