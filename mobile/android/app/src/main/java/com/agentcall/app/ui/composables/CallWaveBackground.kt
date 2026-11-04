package com.agentcall.app.ui.composables

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.agentcall.app.ui.theme.BrandMagenta
import com.agentcall.app.ui.theme.BrandPurpleLight
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/** How lively the aura should be. Derived from the call state, not the audio. */
data class WaveMood(
    /** Vertical reach of the field, 0..1. */
    val amplitude: Float = 0.34f,
    /** Phase speed multiplier. Thinking is deliberately slower than listening. */
    val speed: Float = 1f,
    /** Overall ink opacity, 0..1. Muted/ended states stay quiet. */
    val brightness: Float = 0.55f,
    /** How strongly live audio pushes the two fields toward each other, 0..1. */
    val audioGain: Float = 1f,
)

/**
 * AgentCall's call aura: the 21st.dev DitheringShader "wave" field rendered
 * twice — mirrored from the top in the AI tint, upright from the bottom in the
 * user tint.
 *
 * The 8x8 Bayer dithering, block pixelisation and base wave shape are a direct
 * port of the supplied DitheringShader source. On top of that the two mirrored
 * fields are driven by a continuous voice simulation: smoothed audio envelopes,
 * multi-component travelling waves, and a collision term that couples the two
 * fronts when they meet. Silence stays calm and alive; speech raises amplitude,
 * curvature and interference locally instead of scaling the whole field.
 *
 * Performance contract:
 * - one block per `pxSize` (3dp), so the per-frame CPU cost is tiny,
 * - two reused ARGB bitmaps, drawn nearest-neighbour to keep the dither crisp,
 * - ~30 fps, capped delta, works on API 26+ without GLES/RuntimeShader,
 * - stops with the host lifecycle, renders one still frame for reduced motion,
 * - no pointer input: this composable never steals a call gesture.
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
    val reducedMotion = rememberReducedMotion()
    val density = LocalDensity.current.density
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

    val amplitude by animateFloatAsState(
        targetValue = mood.amplitude,
        animationSpec = if (reducedMotion) snap() else tween(durationMillis = 620),
        label = "waveAmplitude",
    )
    val brightness by animateFloatAsState(
        targetValue = mood.brightness,
        animationSpec = if (reducedMotion) snap() else tween(durationMillis = 720),
        label = "waveBrightness",
    )
    val speed by animateFloatAsState(
        targetValue = mood.speed,
        animationSpec = if (reducedMotion) snap() else tween(durationMillis = 800),
        label = "waveSpeed",
    )

    val currentMood by rememberUpdatedState(
        WaveMood(amplitude = amplitude, speed = speed, brightness = brightness, audioGain = mood.audioGain),
    )
    val currentUser by rememberUpdatedState(userLevel.coerceIn(0f, 1f))
    val currentAi by rememberUpdatedState(aiLevel.coerceIn(0f, 1f))
    val currentSpeed by rememberUpdatedState(speed)
    val currentUserTint by rememberUpdatedState(userTint)
    val currentAiTint by rememberUpdatedState(aiTint)

    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val time = remember { mutableFloatStateOf(0f) }
    val frame = remember { mutableIntStateOf(0) }
    val renderer = remember { DitheringWaveRenderer() }

    LaunchedEffect(viewport, visible, reducedMotion) {
        if (viewport.width < 8 || viewport.height < 8) return@LaunchedEffect
        renderer.configure(viewport.width, viewport.height, density)

        // Always paint at least one frame so a paused/reduced-motion surface
        // never flashes an empty rectangle.
        renderer.render(
            seconds = if (reducedMotion) STILL_FRAME_SECONDS else time.floatValue,
            dt = if (reducedMotion) 1f else 0f,
            mood = currentMood,
            userLevel = currentUser,
            aiLevel = currentAi,
            userTint = currentUserTint,
            aiTint = currentAiTint,
        )
        frame.intValue++
        if (reducedMotion || !visible) return@LaunchedEffect

        var previous = System.nanoTime()
        while (isActive) {
            delay(FRAME_MS)
            val now = System.nanoTime()
            val dt = ((now - previous) / 1_000_000_000f).coerceAtMost(0.1f)
            previous = now
            // 0.6 matches the reference demo's speed prop at mood speed 1.
            time.floatValue = (time.floatValue + dt * currentSpeed * 0.6f) % 1_000f
            renderer.render(
                seconds = time.floatValue,
                dt = dt,
                mood = currentMood,
                userLevel = currentUser,
                aiLevel = currentAi,
                userTint = currentUserTint,
                aiTint = currentAiTint,
            )
            frame.intValue++
        }
    }

    Canvas(
        modifier = modifier.onSizeChanged { viewport = it },
    ) {
        // Reading frame keeps the draw invalidated after each rendered block frame.
        frame.intValue
        renderer.draw(this)
    }
}

// ── DitheringShader port ─────────────────────────────────

private class DitheringWaveRenderer {

    private val paint = Paint().apply {
        isAntiAlias = false
        isFilterBitmap = false
        isDither = false
    }
    private val dst = Rect()

    private var width = 0
    private var height = 0
    private var cols = 0
    private var rows = 0
    private var blocksX = 0f
    private var blocksY = 0f

    private var userBitmap: Bitmap? = null
    private var aiBitmap: Bitmap? = null
    private var userPixels = IntArray(0)
    private var aiPixels = IntArray(0)

    private var qx = IntArray(0)
    private var qy = IntArray(0)
    private var userWave = FloatArray(0)
    private var aiWave = FloatArray(0)

    // Smoothed input envelopes and phase drift live across frames so attack,
    // release and organic time-warping survive recompositions.
    private var userEnv = 0f
    private var aiEnv = 0f
    private var phaseDrift = 0f

    fun configure(w: Int, h: Int, density: Float) {
        if (w == width && h == height && cols > 0) return
        width = w
        height = h
        val blockPx = (3f * density).coerceAtLeast(1f)
        blocksX = w / blockPx
        blocksY = h / blockPx
        cols = kotlin.math.ceil(blocksX.toDouble()).toInt().coerceAtLeast(1).coerceAtMost(w)
        rows = kotlin.math.ceil(blocksY.toDouble()).toInt().coerceAtLeast(1).coerceAtMost(h)
        if (cols <= 0 || rows <= 0) return

        userPixels = IntArray(cols * rows)
        aiPixels = IntArray(cols * rows)
        userBitmap = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888)
        aiBitmap = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888)

        qx = IntArray(cols) { bx ->
            floor((bx + 0.5f) - blocksX / 2f).toInt()
        }
        qy = IntArray(rows) { by ->
            // Dithering uses WebGL's bottom-left fragment coordinate.
            floor(blocksY / 2f - (by + 0.5f)).toInt()
        }
        userWave = FloatArray(cols)
        aiWave = FloatArray(cols)
        dst.set(0, 0, w, h)
    }

    fun render(
        seconds: Float,
        dt: Float,
        mood: WaveMood,
        userLevel: Float,
        aiLevel: Float,
        userTint: Color,
        aiTint: Color,
    ) {
        if (cols == 0 || rows == 0) return
        userEnv = smoothLevel(userEnv, userLevel, dt)
        aiEnv = smoothLevel(aiEnv, aiLevel, dt)
        val energy = max(userEnv, aiEnv)
        // Speech slightly accelerates the field; silence keeps a slow, calm
        // current. The drift accumulates, so motion never snaps between loops.
        phaseDrift += dt * (0.16f + 1.05f * energy)
        val t = 0.5f * seconds + phaseDrift

        val amp = mood.amplitude.coerceIn(0f, 1f)
        val audioGain = mood.audioGain.coerceIn(0f, 1f)
        val headroom = 0.65f + 0.55f * amp
        val userSpread = waveSpread(userEnv, headroom)
        val aiSpread = waveSpread(aiEnv, headroom)
        val userPush = (0.06f + 0.94f * userEnv) * audioGain * MAX_APPROACH
        val aiPush = (0.06f + 0.94f * aiEnv) * audioGain * MAX_APPROACH
        val speedScale = 0.86f + amp * 0.30f + energy * 0.10f

        for (bx in 0 until cols) {
            val x = (qx[bx] / blocksX) * 4f
            val userBase = organicWave(x, t, USER_SEED, userEnv, userSpread)
            val aiBase = organicWave(x, t, AI_SEED, aiEnv, aiSpread)

            // Screen-space front positions (0 = top, 1 = bottom). The gap is
            // positive while the fields are apart; near zero they are touching
            // and the collision term lets each path bend the other.
            val userFront = 1f - WAVE_REGION *
                (0.5f + (userPush - userBase) / (4f * speedScale))
            val aiFront = WAVE_REGION *
                (0.5f + (aiPush - aiBase) / (4f * speedScale))
            val proximity = collisionWeight(userFront - aiFront)
            val userReach = (1f - abs(userFront - 0.5f) * 2.4f).coerceIn(0f, 1f) * userEnv
            val aiReach = (1f - abs(aiFront - 0.5f) * 2.4f).coerceIn(0f, 1f) * aiEnv
            val collide = proximity * max(userReach, aiReach)

            // Short-lived ripples where the fronts meet: temporary amplitude,
            // curvature and energy changes rather than a global scale.
            val interference = sin(5.1f * x + 3.2f * t) * 0.20f +
                sin(8.3f * x - 4.6f * t + 0.7f) * 0.10f
            val breathe = sin(7.7f * x + 6.1f * t) * 0.10f +
                sin(3.3f * x - 2.7f * t + 2.2f) * 0.06f

            userWave[bx] = userBase +
                breathe * (0.35f + 0.65f * userEnv) +
                collide * (aiBase * 0.45f + interference)
            aiWave[bx] = aiBase -
                breathe * (0.35f + 0.65f * aiEnv) -
                collide * (userBase * 0.45f - interference)
        }

        val userColor = inkColor(userTint, mood.brightness * 1.15f)
        val aiColor = inkColor(aiTint, mood.brightness)
        java.util.Arrays.fill(userPixels, 0)
        java.util.Arrays.fill(aiPixels, 0)

        for (by in 0 until rows) {
            val ditherRow = mod8(qy[by]) * 8
            val p = (by + 0.5f) / rows
            // Each field owns one edge of the screen and reaches toward the
            // centre at most halfway through its own region. The reference
            // shader and its phase are unchanged; only the vertical viewport is
            // placed. At full audio the two fronts can meet at the centre.
            val yBottom = ((1f - p) / WAVE_REGION - 0.5f) * 4f * speedScale - userPush
            val yTop = (p / WAVE_REGION - 0.5f) * 4f * speedScale - aiPush
            val rowOffset = by * cols
            for (bx in 0 until cols) {
                val threshold = BAYER8[ditherRow + mod8(qx[bx])]
                val index = rowOffset + bx
                if (waveShape(yBottom + userWave[bx]) + threshold >= 1f) {
                    userPixels[index] = userColor
                }
                if (waveShape(yTop + aiWave[bx]) + threshold >= 1f) {
                    aiPixels[index] = aiColor
                }
            }
        }

        userBitmap?.setPixels(userPixels, 0, cols, 0, 0, cols, rows)
        aiBitmap?.setPixels(aiPixels, 0, cols, 0, 0, cols, rows)
    }

    fun draw(scope: DrawScope) {
        val user = userBitmap ?: return
        val ai = aiBitmap ?: return
        if (width <= 0 || height <= 0) return
        scope.drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.drawBitmap(user, null, dst, paint)
            native.drawBitmap(ai, null, dst, paint)
        }
    }

    private fun inkColor(color: Color, brightness: Float): Int {
        val alpha = ((0.32f + 0.62f * brightness.coerceIn(0f, 1f)) *
            WAVE_OPACITY * 255f)
            .roundToInt()
            .coerceIn(0, 255)
        return (alpha shl 24) or (color.toArgb() and 0x00FFFFFF)
    }
}

// ── Pure shader math (unit-tested) ───────────────────────

/**
 * Exact wave branch of the DitheringShader fragment shader:
 * `1.0 - smoothstep(-1.0, 1.0, shapeY + wave)`.
 */
internal fun waveShape(z: Float): Float {
    val t = ((z + 1f) / 2f).coerceIn(0f, 1f)
    return 1f - t * t * (3f - 2f * t)
}

/**
 * Attack/release envelope for one audio stream. Speech rises quickly; silence
 * falls slowly so the wave never snaps flat between words.
 */
internal fun smoothLevel(current: Float, target: Float, dt: Float): Float {
    if (dt <= 0f) return current
    val goal = target.coerceIn(0f, 1f)
    val tau = if (goal > current) LEVEL_ATTACK_S else LEVEL_RELEASE_S
    val k = 1f - exp(-dt / tau)
    return current + (goal - current) * k
}

/**
 * Wave crest travel as a function of voice energy. Silence keeps a small,
 * non-zero spread so the field stays alive; loud speech expands it smoothly.
 */
internal fun waveSpread(energy: Float, headroom: Float): Float {
    val e = energy.coerceIn(0f, 1f)
    val voice = e * e * (3f - 2f * e)
    return (SILENCE_SPREAD + (LOUD_SPREAD - SILENCE_SPREAD) * voice) * headroom
}

/**
 * One field's organic displacement: several non-harmonic travelling components
 * whose sum produces moving interference, plus a slow swell so the motion is
 * never a mechanically repeating loop.
 */
internal fun organicWave(
    x: Float,
    t: Float,
    seed: Float,
    energy: Float,
    spread: Float,
): Float {
    val e = energy.coerceIn(0f, 1f)
    val swell = 0.82f + 0.18f * sin(t * 0.31f + seed * 2.3f)
    val a = sin(0.62f * x + 1.35f * t + seed) * 0.55f
    val b = sin(1.37f * x - 0.98f * t + seed * 1.7f + 0.9f) * 0.30f
    val c = sin(2.43f * x + 0.51f * t + seed * 0.4f + 1.9f) * (0.12f + 0.06f * e)
    val d = sin(4.37f * x - 1.83f * t + seed * 2.3f) * 0.05f * e
    return (a + b + c + d) * spread * swell
}

/** How strongly two approaching fronts interact, 0..1, from their gap. */
internal fun collisionWeight(gap: Float): Float {
    val raw = ((COLLISION_RANGE - gap) / COLLISION_RANGE).coerceIn(0f, 1f)
    return raw * raw
}

/** Ordered 8x8 Bayer thresholds in [0, 1), row-major. */
internal val BAYER8: FloatArray = intArrayOf(
    0, 32, 8, 40, 2, 34, 10, 42,
    48, 16, 56, 24, 50, 18, 58, 26,
    12, 44, 4, 36, 14, 46, 6, 38,
    60, 28, 52, 20, 62, 30, 54, 22,
    3, 35, 11, 43, 1, 33, 9, 41,
    51, 19, 59, 27, 49, 17, 57, 25,
    15, 47, 7, 39, 13, 45, 5, 37,
    63, 31, 55, 23, 61, 29, 53, 21,
).let { values -> FloatArray(values.size) { values[it] / 64f } }

/** GLSL `mod(x, 8)` for a signed block coordinate. */
internal fun mod8(value: Int): Int = ((value % 8) + 8) % 8

private const val FRAME_MS = 33L
private const val STILL_FRAME_SECONDS = 12.7f

/** Fraction of the screen each wave may use from its own edge. */
private const val WAVE_REGION = 0.66f

/**
 * Maximum vertical shift (in shader units) that audio may pull a field toward
 * the centre. The original wave itself is unchanged.
 */
private const val MAX_APPROACH = 1.0f

/** Calm and full-voice crest spread (shader units). */
private const val SILENCE_SPREAD = 0.09f
private const val LOUD_SPREAD = 0.72f

/** Audio envelope time constants: fast attack, slower natural release. */
private const val LEVEL_ATTACK_S = 0.08f
private const val LEVEL_RELEASE_S = 0.42f

/** Gap at which two fronts start interfering (screen fraction). */
private const val COLLISION_RANGE = 0.28f

private const val USER_SEED = -0.7f
private const val AI_SEED = 2.35f

/** Slight global reduction so the aura stays atmospheric under the UI. */
private const val WAVE_OPACITY = 0.92f
