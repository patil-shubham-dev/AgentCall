package com.agentcall.app.ui.composables

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.agentcall.app.ui.theme.BrandMagenta
import com.agentcall.app.ui.theme.BrandPurpleLight
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.exp
import kotlin.math.sin

/**
 * A deliberately sparse voice visualizer for the active call surface.
 *
 * AI and human are two independent, edge-anchored waveform lines. The middle
 * is never used as decoration: silence shows two near-flat lines, and real
 * speech alone creates the hills on its own side.
 */
@Composable
fun CallWaveBackground(
    mood: WaveMood,
    userLevel: Float,
    aiLevel: Float,
    modifier: Modifier = Modifier,
    userTint: Color = BrandPurpleLight,
    aiTint: Color = BrandMagenta,
) {
    val density = LocalDensity.current.density
    val displayMetrics = LocalContext.current.resources.displayMetrics
    // Use the display's physical vertical density so the quiet baselines sit
    // about three centimetres from the top and bottom on actual hardware.
    val waveInsetPx = (3f / 2.54f * displayMetrics.ydpi).coerceAtLeast(112f)
    val lifecycleOwner = LocalLifecycleOwner.current
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            visible = true
            try {
                awaitCancellation()
            } finally {
                visible = false
            }
        }
    }

    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val frame = remember { mutableIntStateOf(0) }
    val time = remember { mutableFloatStateOf(0f) }
    val renderer = remember { DualVoiceWaveRenderer() }
    val currentUser by rememberUpdatedState(userLevel.coerceIn(0f, 1f))
    val currentAi by rememberUpdatedState(aiLevel.coerceIn(0f, 1f))
    val currentMood by rememberUpdatedState(mood)
    val currentUserTint by rememberUpdatedState(userTint)
    val currentAiTint by rememberUpdatedState(aiTint)

    LaunchedEffect(viewport, visible, density) {
        if (viewport.width < 8 || viewport.height < 8) return@LaunchedEffect
        renderer.configure(viewport.width, viewport.height, density, waveInsetPx)
        var previous = System.nanoTime()
        while (visible && isActive) {
            val now = System.nanoTime()
            val dt = ((now - previous) / 1_000_000_000f).coerceIn(0f, 0.08f)
            previous = now
            time.floatValue += dt
            renderer.render(time.floatValue, dt, currentUser, currentAi, currentMood, currentUserTint, currentAiTint)
            frame.intValue++
            delay(33L)
        }
    }

    Canvas(modifier = modifier.onSizeChanged { viewport = it }) {
        frame.intValue
        renderer.draw(this)
    }
}

private class DualVoiceWaveRenderer {
    private val aiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val userPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var width = 0
    private var height = 0
    private var density = 1f
    private var insetPx = 112f
    private var samples = 0
    private var ai = FloatArray(0)
    private var user = FloatArray(0)
    private var aiEnvelope = 0f
    private var userEnvelope = 0f
    private var aiColor = 0
    private var userColor = 0
    private var particleFlow = 0f

    fun configure(width: Int, height: Int, density: Float, insetPx: Float) {
        if (this.width == width && this.height == height && this.samples > 0) return
        this.width = width
        this.height = height
        this.density = density
        this.insetPx = insetPx
        samples = (width / (16f * density)).toInt().coerceIn(36, 76)
        ai = FloatArray(samples)
        user = FloatArray(samples)
    }

    fun render(seconds: Float, dt: Float, userLevel: Float, aiLevel: Float, mood: WaveMood, userTint: Color, aiTint: Color) {
        if (samples == 0) return
        aiEnvelope = smoothEnvelope(aiEnvelope, aiLevel, dt)
        userEnvelope = smoothEnvelope(userEnvelope, userLevel, dt)
        val top = insetPx.coerceAtMost(height * 0.38f)
        val bottom = (height - insetPx).coerceAtLeast(height * 0.62f)
        val aiAmplitude = 3f + (height / 2f - top - 18f).coerceAtLeast(3f) * voiceCurve(aiEnvelope) * mood.audioGain
        val userAmplitude = 3f + (bottom - height / 2f - 18f).coerceAtLeast(3f) * voiceCurve(userEnvelope) * mood.audioGain
        val quietRate = 0.18f

        for (i in 0 until samples) {
            val x = i.toFloat() / (samples - 1).coerceAtLeast(1)
            ai[i] = top + voiceShape(x, seconds, 0.7f, aiEnvelope, aiAmplitude, quietRate)
            user[i] = bottom - voiceShape(x, seconds, 3.1f, userEnvelope, userAmplitude, quietRate)
        }
        particleFlow = (particleFlow + dt * (18f + 74f * maxOf(aiEnvelope, userEnvelope))) % width.coerceAtLeast(1)
        aiColor = aiTint.toArgb()
        userColor = userTint.toArgb()
    }

    fun draw(scope: DrawScope) {
        if (samples == 0) return
        scope.drawIntoCanvas { canvas ->
            val interaction = minOf(aiEnvelope, userEnvelope)
            drawParticleRibbon(canvas.nativeCanvas, ai, aiPaint, aiColor, aiEnvelope, interaction)
            drawParticleRibbon(canvas.nativeCanvas, user, userPaint, userColor, userEnvelope, interaction)
        }
    }

    private fun drawParticleRibbon(
        canvas: android.graphics.Canvas,
        points: FloatArray,
        paint: Paint,
        color: Int,
        energy: Float,
        interaction: Float,
    ) {
        val step = width.toFloat() / (points.size - 1).coerceAtLeast(1)
        val shaped = voiceCurve(energy)
        val layers = if (energy < 0.035f) 0 else 1 + (shaped * 5f).toInt() + if (interaction > 0.08f) 1 else 0
        val spread = 3f + 42f * shaped
        val stride = if (energy < 0.035f) 2 else 1
        for (i in points.indices step stride) {
            val x = (i * step + particleFlow * (0.72f + (i % 5) * 0.07f)) % width
            for (layer in -layers..layers) {
                val normalized = if (layers == 0) 0f else layer.toFloat() / layers
                val edge = 1f - kotlin.math.abs(normalized)
                paint.color = color
                paint.alpha = (52 + 170 * (0.35f + 0.65f * shaped) * (0.35f + 0.65f * edge)).toInt()
                canvas.drawCircle(x, points[i] + normalized * spread, density * (0.8f + 1.35f * edge + 0.45f * shaped), paint)
            }
        }
    }
}

private fun voiceCurve(value: Float): Float = value.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }

private fun smoothEnvelope(current: Float, target: Float, dt: Float): Float {
    val tau = if (target > current) 0.055f else 0.30f
    return current + (target - current) * (1f - exp(-dt / tau))
}

private fun voiceShape(x: Float, time: Float, seed: Float, energy: Float, amplitude: Float, quietRate: Float): Float {
    val rate = quietRate + 1.15f * energy
    val hills = sin(x * 15.7f + time * rate + seed) * 0.58f +
        sin(x * 28.3f - time * rate * 1.37f + seed * 2.1f) * 0.29f +
        sin(x * 43.2f + time * rate * 0.73f + seed) * 0.13f
    return hills * amplitude
}
