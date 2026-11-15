package com.agentcall.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Design tokens for the incoming-call surface.
 *
 * Violet-cast ink ramp with cream text, matching the answer-call illustration:
 * the screen sits on `Ink`, the violet `Glow` is the only ambient light, and
 * green/red appear only on the slider. Type roles name the bundled Instrument
 * Sans family explicitly — nothing here inherits the device font.
 */
object IncomingCallTokens {

    object Palette {
        /** Screen fill. */
        val Background = Color(0xFF100E18)

        /** Deepest ink, behind the screen (window + halo falloff). */
        val Ink = Color(0xFF07060C)

        val Surface = Color(0xFF1A1623)
        val SurfaceRaised = Color(0xFF1B1825)
        val Track = Color(0xFF17141F)
        val Outline = Color(0x1FF4F0E6) // 12% cream hairline
        val OuterRing = Color(0x14F4F0E6) // 8% cream, 3dp outside the track

        val OnSurface = Color(0xFFF4F0E6) // cream
        val Muted = Color(0x94F4F0E6) // 58% cream
        val Amber = Color(0xFFE8B04B)

        val Answer = Color(0xFF4FD1A1)
        val AnswerFill = Color(0xFF2E8F68)
        val Decline = Color(0xFFFF6B6B)
        val DeclineFill = Color(0xFFB94046)

        val Thumb = Color(0xFFECEDF0)
        val ThumbIcon = Color(0xFF16181C)
        val SliderLabel = Color(0xB8F4F0E6) // 72% cream

        /** Brand violet. Ambient halo only — never a fill or a label colour. */
        val Glow = Color(0xFF7967DE)

        val Scrim = Color(0x99000000)
    }

    object Dimen {
        val ScreenPadding: Dp = 24.dp

        // Identity
        val LogoSize: Dp = 112.dp
        val LogoToName: Dp = 24.dp
        val NameToCaption: Dp = 6.dp
        val CaptionToReason: Dp = 20.dp
        val ReasonMaxLines: Int = 3
        val ContentBottomBias: Dp = 40.dp

        // Actions
        val RemindHeight: Dp = 48.dp
        val RemindPaddingHorizontal: Dp = 20.dp
        val RemindIcon: Dp = 18.dp
        val RemindIconGap: Dp = 8.dp
        // 32dp of air plus the slider's 20dp top margin, as in the study.
        val RemindToSliderGap: Dp = 52.dp
        val SliderHeight: Dp = 92.dp
        val SliderThumb: Dp = 84.dp
        val SliderThumbInset: Dp = 4.dp
        val SliderOutline: Dp = 1.dp
        val SliderLabelPadding: Dp = 20.dp
        val SliderEndIcon: Dp = 22.dp
        val SliderThumbIcon: Dp = SliderHeight * 0.32f
        val BottomGap: Dp = 40.dp
        val TouchTarget: Dp = 48.dp

        // Sheets
        val SheetPadding: Dp = 24.dp
        val SheetOptionHeight: Dp = 56.dp
        val SheetCorner: Dp = 28.dp
        val SheetBottomGap: Dp = 24.dp

        // Slider hint. Sits just outside the centred thumb (42dp radius) so it
        // reads as attached to the thumb, not to the end labels.
        val ChevronSize: Dp = 10.dp
        val ChevronOffset: Dp = 48.dp

        // Slider rings: a 1dp hairline on the track edge, a 3dp band inside it
        // that charges colour, and a 1dp ring sitting 3dp outside the track.
        val SliderRingWidth: Dp = 1.dp
        val SliderRingBand: Dp = 3.dp
        val SliderRingOffset: Dp = 3.dp
        val SliderRingGlow: Dp = 16.dp
    }

    object Type {
        val CallerName = TextStyle(
            fontFamily = InstrumentSans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 30.sp,
            lineHeight = 36.sp,
            letterSpacing = (-0.30).sp, // −0.01em at 30sp
        )
        val Caption = TextStyle(
            fontFamily = InstrumentSans,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            letterSpacing = 0.10.sp, // .008em at 13sp
        )
        val Reason = TextStyle(
            fontFamily = InstrumentSans,
            fontWeight = FontWeight.Normal,
            fontSize = 16.sp,
            lineHeight = 24.sp,
            letterSpacing = 0.sp,
        )
        val Control = TextStyle(
            fontFamily = InstrumentSans,
            fontWeight = FontWeight.Medium,
            fontSize = 15.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.sp,
        )
        val Clock = TextStyle(
            fontFamily = InstrumentSans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.15.sp, // .01em at 15sp
            fontFeatureSettings = "tnum",
        )
        val SheetTitle = TextStyle(
            fontFamily = InstrumentSans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 20.sp,
            lineHeight = 26.sp,
            letterSpacing = 0.sp,
        )
        val SheetOption = TextStyle(
            fontFamily = InstrumentSans,
            fontWeight = FontWeight.Medium,
            fontSize = 16.sp,
            lineHeight = 24.sp,
            letterSpacing = 0.sp,
        )
    }

    object Motion {
        const val ColorMs: Int = 200
        const val IconFadeMs: Int = 120
        const val ChevronMs: Int = 1_400
        const val ChevronTravelDp: Float = 2f
        const val ChevronAlphaMin: Float = 0.12f
        const val ChevronAlphaMax: Float = 0.20f
        const val SliderDamping: Float = 0.7f
        const val SliderFillTrailMs: Int = 70
        const val SliderLabelClearFraction: Float = 0.30f
        const val SliderLabelHoldFraction: Float = 0.74f
        const val SliderPositionalThreshold: Float = 0.75f
        const val SliderVelocityDpPerSecond: Float = 1_000f
        const val ScreenTransitionMs: Int = 260

        // Slider reads its own travel fraction: the thumb tints and the icon
        // turns continuously with the drag, so there is no swap threshold.
        const val SliderMaxIconRotation: Float = 135f
        const val SliderRingTintAlpha: Float = 0.16f
        const val SliderRingGlowAlpha: Float = 0.18f
        const val SliderOuterRingAlpha: Float = 0.18f

        // Final ten seconds: the clock turns amber.
        const val UrgentSeconds: Int = 10
        const val DefaultTimeoutSeconds: Int = 60
    }
}
