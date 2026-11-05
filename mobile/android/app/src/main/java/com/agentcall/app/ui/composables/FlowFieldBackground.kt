package com.agentcall.app.ui.composables

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.agentcall.app.ui.theme.BrandPurpleLight
import com.agentcall.app.ui.theme.DarkBg
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Autonomous flow-field behind the incoming-call surface, ported from the
 * supplied NeuralBackground component.
 *
 * The particle update (base angle = (cos(x·0.005) + sin(y·0.005))·π, velocity
 * accumulation, 0.95 damping, life/reset, edge wrap), the 1.5px particles, the
 * alpha-from-age envelope and the fading trail canvas are kept as supplied.
 * Composition additions: a slow eddy layer and drift that route particles
 * through the central band, stratified golden-ratio spawn positions so density
 * covers the whole viewport, and per-particle speed variation. Mobile
 * integration is unchanged: no pointer handling, reusable dp-resolution bitmap,
 * lifecycle pause, and a particle budget that respects the viewport and RAM.
 */
@Composable
fun FlowFieldBackground(
    modifier: Modifier = Modifier,
    color: Color = BrandPurpleLight,
    trailOpacity: Float = 0.10f,
    particleCount: Int = 0,
    speed: Float = 0.42f,
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val reducedMotion = rememberReducedMotion()
    val lowRam = remember(context) {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        manager?.isLowRamDevice == true
    }

    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val frame = remember { mutableIntStateOf(0) }
    val renderer = remember { FlowFieldRenderer() }

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

    LaunchedEffect(viewport, visible, reducedMotion, particleCount, speed, color, trailOpacity) {
        if (viewport.width < 8 || viewport.height < 8) return@LaunchedEffect
        renderer.configure(
            width = viewport.width,
            height = viewport.height,
            density = density,
            requestedCount = particleCount,
            lowRam = lowRam,
            color = color,
            trailOpacity = trailOpacity,
        )
        if (reducedMotion || !visible) {
            // Grow some trail history for a still frame, so the reduced-motion
            // surface still reads as the supplied flow field rather than dots.
            renderer.paintStill(speed)
            frame.intValue++
            return@LaunchedEffect
        }

        val safeSpeed = speed.coerceIn(0.1f, 1.2f)
        while (isActive) {
            delay(FRAME_MS)
            renderer.advance(safeSpeed)
            frame.intValue++
        }
    }

    Canvas(
        modifier = modifier.onSizeChanged { viewport = it },
    ) {
        frame.intValue
        renderer.draw(this)
    }
}

// ── Renderer ─────────────────────────────────────────────

private class FlowFieldRenderer {

    private var random = Random(0x5EED)
    private var bitmap: Bitmap? = null
    private var bufferCanvas: AndroidCanvas? = null
    private val particlePaint = Paint().apply {
        isAntiAlias = false
        isDither = false
        style = Paint.Style.FILL
    }
    private val blitPaint = Paint().apply {
        isAntiAlias = false
        isFilterBitmap = true
        isDither = false
    }
    private val dst = Rect()

    private var width = 0
    private var height = 0
    private var bufferWidth = 0
    private var bufferHeight = 0
    private var count = 0
    private var particleColor = 0
    private var fadeColor = 0

    private var x = FloatArray(0)
    private var y = FloatArray(0)
    private var vx = FloatArray(0)
    private var vy = FloatArray(0)
    private var rate = FloatArray(0)
    private var age = IntArray(0)
    private var life = IntArray(0)

    private var spawnIndex = 0
    private var tick = 0f

    fun configure(
        width: Int,
        height: Int,
        density: Float,
        requestedCount: Int,
        lowRam: Boolean,
        color: Color,
        trailOpacity: Float,
    ) {
        val safeDensity = density.coerceAtLeast(1f)
        val newBufferWidth = ceil(width / safeDensity).toInt().coerceAtLeast(2)
        val newBufferHeight = ceil(height / safeDensity).toInt().coerceAtLeast(2)
        val targetCount = if (requestedCount > 0) {
            requestedCount.coerceIn(MIN_PARTICLES, MAX_PARTICLES)
        } else {
            autoParticleCount(newBufferWidth, newBufferHeight, lowRam)
        }

        val sizeChanged = this.width != width ||
            this.height != height ||
            this.bufferWidth != newBufferWidth ||
            this.bufferHeight != newBufferHeight
        val countChanged = this.count != targetCount
        this.width = width
        this.height = height
        this.bufferWidth = newBufferWidth
        this.bufferHeight = newBufferHeight
        this.particleColor = color.toArgb()
        this.fadeColor = withAlpha(DarkBg.toArgb(), trailOpacity.coerceIn(0f, 1f))
        if (!sizeChanged && !countChanged) return

        count = targetCount
        x = FloatArray(count)
        y = FloatArray(count)
        vx = FloatArray(count)
        vy = FloatArray(count)
        rate = FloatArray(count)
        age = IntArray(count)
        life = IntArray(count)
        random = Random(0x5EED)
        spawnIndex = 0
        tick = 0f

        val newBitmap = Bitmap.createBitmap(newBufferWidth, newBufferHeight, Bitmap.Config.ARGB_8888)
        val newCanvas = AndroidCanvas(newBitmap)
        newCanvas.drawColor(DarkBg.toArgb())
        bitmap = newBitmap
        bufferCanvas = newCanvas
        dst.set(0, 0, width, height)

        for (i in 0 until count) reset(i)
    }

    fun advance(speed: Float) {
        val canvas = bufferCanvas ?: return
        canvas.drawColor(fadeColor)
        tick = (tick + 1f) % 16384f
        // One slow collective breath makes this a connection field rather
        // than a random particle simulation. The range is deliberately tiny
        // so caller information remains dominant throughout the ring state.
        val breathing = 0.92f + 0.08f * sin(tick * 0.018f)
        for (i in 0 until count) {
            val angle = flowAngle(x[i], y[i], bufferWidth.toFloat(), bufferHeight.toFloat(), tick)
            val step = 0.16f * speed * rate[i] * breathing
            vx[i] += cos(angle) * step
            vy[i] += sin(angle) * step

            x[i] += vx[i]
            y[i] += vy[i]
            vx[i] *= 0.95f
            vy[i] *= 0.95f

            age[i]++
            if (age[i] > life[i]) {
                reset(i)
                continue
            }

            if (x[i] < 0f) x[i] = bufferWidth.toFloat()
            if (x[i] > bufferWidth) x[i] = 0f
            if (y[i] < 0f) y[i] = bufferHeight.toFloat()
            if (y[i] > bufferHeight) y[i] = 0f

            val alpha = 1f - abs(age[i].toFloat() / life[i] - 0.5f) * 2f
            particlePaint.color = particleColor
            particlePaint.alpha = (alpha.coerceIn(0f, 1f) * 255f).roundToInt()
            canvas.drawRect(x[i], y[i], x[i] + PARTICLE_CSS_PX, y[i] + PARTICLE_CSS_PX, particlePaint)
        }
        particlePaint.alpha = 255
    }

    fun paintStill(speed: Float) {
        if (bitmap == null) return
        repeat(STILL_STEPS) { advance(speed) }
    }

    fun draw(scope: DrawScope) {
        val current = bitmap ?: return
        if (width <= 0 || height <= 0) return
        scope.drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawBitmap(current, null, dst, blitPaint)
        }
    }

    private fun reset(index: Int) {
        // Low-discrepancy spawn positions cover the whole buffer — including
        // the middle of the screen — instead of clustering where the field
        // happens to leave holes.
        val i = spawnIndex++
        val jitter = 0.012f
        x[index] = (spawnX(i) + (random.nextFloat() - 0.5f) * jitter) * bufferWidth
        y[index] = (spawnY(i) + (random.nextFloat() - 0.5f) * jitter) * bufferHeight
        vx[index] = 0f
        vy[index] = 0f
        rate[index] = 0.65f + random.nextFloat() * 0.7f
        age[index] = 0
        life[index] = random.nextInt(150, 361)
    }
}

// ── Flow math (unit-tested) ──────────────────────────────

/**
 * Original vector field plus a slower eddy layer and a very slow drift. The
 * eddy layer pushes routes through the central band instead of letting every
 * particle settle onto the same edge lanes; the drift keeps long sessions from
 * repeating the exact same paths.
 */
internal fun flowAngle(x: Float, y: Float, width: Float, height: Float, tick: Float): Float {
    val w = width.coerceAtLeast(1f)
    val h = height.coerceAtLeast(1f)
    val nx = x / w
    val ny = y / h
    val base = (cos(x * 0.005f) + sin(y * 0.005f)) * PI.toFloat()
    val eddy = 0.55f * sin((nx * 3.1f + ny * 2.3f) * PI.toFloat() + 1.2f) *
        cos((ny * 2.7f - nx * 1.9f) * PI.toFloat())
    val drift = 0.22f * sin(tick * 0.0037f + nx * 2.4f - ny * 1.8f)
    return base + eddy + drift
}

/**
 * Golden-ratio spawn coordinates, uniform over [0, 1). The index wraps at a
 * power of two so float precision stays sharp over a long-running call.
 */
internal fun spawnX(index: Int): Float = ((index % 16384) * 0.61803398875f + 0.13f) % 1f

internal fun spawnY(index: Int): Float = ((index % 16384) * 0.75487766625f + 0.57f) % 1f

// ── Tuning ───────────────────────────────────────────────

private const val PARTICLE_CSS_PX = 1.5f
private const val STILL_STEPS = 110
// A calm ring-state field gains nothing from 60fps updates. Thirty frames per
// second keeps trajectories smooth while halving the idle renderer workload.
private const val FRAME_MS = 33L
private const val MIN_PARTICLES = 160
private const val MAX_PARTICLES = 600

/** Same field density as the reference, scaled to the actual CSS-pixel area. */
private fun autoParticleCount(width: Int, height: Int, lowRam: Boolean): Int {
    val area = width.toLong() * height.toLong()
    val base = (area / 1_400L).toInt().coerceIn(MIN_PARTICLES, MAX_PARTICLES)
    return if (lowRam) (base * 0.55f).toInt().coerceAtLeast(MIN_PARTICLES) else base
}

private fun withAlpha(argb: Int, alpha: Float): Int =
    ((alpha * 255f).roundToInt().coerceIn(0, 255) shl 24) or (argb and 0x00FFFFFF)
