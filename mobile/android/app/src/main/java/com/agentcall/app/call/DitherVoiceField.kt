package com.agentcall.app.call

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.agentcall.app.ui.composables.BAYER8
import com.agentcall.app.ui.composables.rememberReducedMotion
import kotlinx.coroutines.isActive
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * One edge-anchored dither field from docs/ONGOING_CALL_STAGE_SPEC.md §4.
 *
 * The wave is computed on a 120 x 60 cell buffer (3 dp cells on a 360 dp
 * artboard), uploaded as a nearest-neighbour scaled bitmap, and never blurred:
 * the hard grain is the identity. The AI field hangs from the top, the human
 * field rises from the bottom; only the current speaker is driven.
 *
 * Motion pauses with the lifecycle. Under reduced motion the field renders one
 * still frame instead of starting the frame loop.
 */
@Composable
fun DitherVoiceField(
    side: StageWaveSide,
    active: Boolean,
    level: Float,
    modifier: Modifier = Modifier,
    frozen: Boolean = false,
) {
    val reducedMotion = rememberReducedMotion()
    val buffer = remember { IntArray(COLS * ROWS) }
    val bitmap: Bitmap = remember {
        Bitmap.createBitmap(COLS, ROWS, Bitmap.Config.ARGB_8888)
    }
    val image: ImageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
    val activeState = rememberUpdatedState(active)
    val levelState = rememberUpdatedState(level.coerceIn(0f, 1f))
    var frameVersion by remember { mutableIntStateOf(0) }

    var env by remember { mutableFloatStateOf(0f) }
    var crest by remember { mutableFloatStateOf(0f) }
    var phase by remember { mutableFloatStateOf(if (side == StageWaveSide.AI) 0.6f else 2.4f) }

    fun paint() {
        renderWave(
            buffer = buffer,
            isAi = side == StageWaveSide.AI,
            env = env,
            level = crest,
            phase = phase,
            seed = if (side == StageWaveSide.AI) 0.8f else 2.9f,
        )
        bitmap.setPixels(buffer, 0, COLS, 0, 0, COLS, ROWS)
        frameVersion++
    }

    // Still frames must track the speaker even though nothing animates: with
    // reduced motion (or a frozen ended call) there is no frame loop, so this
    // repaints whenever `active` changes instead of leaving a stale frame up.
    LaunchedEffect(side, reducedMotion, frozen, active) {
        if (frozen) {
            // Ended: freezeStill() with neither speaker active, so both fields
            // settle to the quiet baseline and stop burning frames.
            env = STILL_INACTIVE
            crest = STILL_INACTIVE
            phase = if (side == StageWaveSide.AI) 0.9f else 3.4f
            paint()
            return@LaunchedEffect
        }
        if (reducedMotion) {
            // freezeStill(): active 0.86, inactive 0.05, one rendered frame.
            env = if (active) STILL_ACTIVE else STILL_INACTIVE
            crest = env
            phase = if (side == StageWaveSide.AI) 0.9f else 3.4f
            paint()
            return@LaunchedEffect
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(side, reducedMotion, frozen, lifecycleOwner) {
        if (frozen || reducedMotion) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var lastFrame = 0L
            while (isActive) {
                withFrameNanos { now ->
                    val dt = if (lastFrame == 0L) {
                        0.016f
                    } else {
                        min(0.05f, (now - lastFrame) / 1_000_000_000f)
                    }
                    lastFrame = now

                    val target = if (activeState.value) levelState.value else 0f
                    // A turn lifts the body of the field; the audio level
                    // drives the crest so syllables keep carving the edge.
                    val bodyTarget = if (target > 0.02f) {
                        (0.55f + 0.45f * target).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    val bodyTau = if (bodyTarget > env) 0.16f else 0.34f
                    env += (bodyTarget - env) * (1f - exp(-dt / bodyTau))

                    val crestTau = if (target > crest) 0.05f else 0.12f
                    crest += (target - crest) * (1f - exp(-dt / crestTau))
                    if (target == 0f) {
                        if (env < 0.001f) env = 0f
                        if (crest < 0.001f) crest = 0f
                    }

                    phase += dt * (0.30f + 0.55f * env + 0.85f * crest)
                    paint()
                }
            }
        }
    }

    Canvas(modifier = modifier) {
        // Read the version inside the draw phase so each frame invalidates the
        // draw only, not the composition.
        frameVersion
        drawImage(
            image = image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(image.width, image.height),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            filterQuality = FilterQuality.None,
        )
    }
}

enum class StageWaveSide { AI, HUMAN }

private const val COLS = 120
private const val ROWS = 60
private const val IDLE_ALPHA = 96
private const val ACTIVE_ALPHA = 236
private const val STILL_ACTIVE = 0.86f
private const val STILL_INACTIVE = 0.05f

private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/**
 * The prototype's render() loop, unchanged except for the colour pack into an
 * ARGB int. Only the 120 x 60 buffer is touched here; the bitmap upload and
 * 3x scale happen in [DitherVoiceField].
 */
private fun renderWave(
    buffer: IntArray,
    isAi: Boolean,
    env: Float,
    level: Float,
    phase: Float,
    seed: Float,
) {
    val idleBase = ROWS * if (isAi) 0.34f else 0.66f
    val loudBase = ROWS * if (isAi) 0.80f else 0.20f
    val base = idleBase + (loudBase - idleBase) * env
    val amp = 0.9f + 9.2f * level
    val alpha = (IDLE_ALPHA + (ACTIVE_ALPHA - IDLE_ALPHA) * env)
        .roundToInt()
        .coerceIn(0, 255)
    val dir = if (isAi) 1f else -1f
    val rgb = if (isAi) 0x7967DE else 0xEFE9DF
    val ink = (alpha shl 24) or rgb

    for (cx in 0 until COLS) {
        val u = if (COLS > 1) cx / (COLS - 1f) else 0f
        val wave =
            sin(u * 12.6f + phase * 1.15f + seed) * 0.54f +
                sin(u * 20.9f - phase * 0.80f + seed * 2.1f) * 0.26f +
                sin(u * 33.4f + phase * 0.55f + seed * 0.7f) * 0.12f +
                sin(u * 47.0f - phase * 1.9f + seed * 2.6f) * 0.08f * level
        val boundary = (base + dir * wave * amp).coerceIn(1f, ROWS - 1f)
        val span = if (isAi) boundary else ROWS - boundary

        for (cy in 0 until ROWS) {
            val d = if (isAi) (boundary - cy) / span else (cy - boundary) / span
            val cov = if (d <= 0f) 0f else 0.06f + 0.88f * smoothstep(0f, 0.62f, d)
            val on = cov > BAYER8[((cy and 7) shl 3) or (cx and 7)]
            buffer[cy * COLS + cx] = if (on) ink else 0
        }
    }
}
