package com.agentcall.app.call

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas as ComposeCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * The hidden stage composer from docs/ONGOING_CALL_STAGE_SPEC.md §7.
 *
 * The field is only composed when it is asked for. It grows in 22 dp steps to
 * four lines, scrolls internally after that, sends on Enter / IME send, and
 * dissolves its text on submit. The draft survives close/reopen for the
 * session; the caller owns open/close, the composer owns draft and dissolve.
 *
 * @param maxLines 4 normally; the screen passes 3 on small phones with the IME
 *   visible so the controls stay reachable.
 */
@Composable
fun StageComposer(
    open: Boolean,
    agentName: String,
    reducedMotion: Boolean,
    enabled: Boolean,
    onSend: (String) -> Unit,
    onRequestClose: () -> Unit,
    modifier: Modifier = Modifier,
    maxLines: Int = StageMath.MAX_COMPOSER_LINES,
) {
    val lineCap = maxLines.coerceIn(1, StageMath.MAX_COMPOSER_LINES)
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val focusManager = LocalFocusManager.current

    var draft by remember { mutableStateOf("") }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var fieldSize by remember { mutableStateOf(IntSize.Zero) }
    var rendered by remember { mutableStateOf(open) }
    var focused by remember { mutableStateOf(false) }
    var everFocused by remember { mutableStateOf(false) }
    var settling by remember { mutableStateOf(false) }
    var heldHeight by remember { mutableStateOf<Dp?>(null) }
    var overlay by remember { mutableStateOf<VanishOverlay?>(null) }
    var overlayVersion by remember { mutableIntStateOf(0) }
    val scroll = rememberScrollState()
    val focusRequester = remember { FocusRequester() }

    val measuredLines = layout?.lineCount ?: 1
    val textLines = StageMath.visualLineCount(draft, measuredLines)
    val visibleLines = min(textLines, lineCap)
    val contentHeight = StageMath.composerContentHeight(textLines)
    val targetHeight = heldHeight ?: StageMath.composerFieldHeight(visibleLines)

    val fieldHeight by animateDpAsState(
        targetValue = targetHeight,
        animationSpec = when {
            reducedMotion -> snap()
            settling -> tween(200, easing = StageEase)
            else -> snap()
        },
        label = "composerFieldHeight",
    )
    val appear by animateFloatAsState(
        targetValue = if (open) 1f else 0f,
        animationSpec = tween(if (reducedMotion) 0 else 220, easing = StageEase),
        label = "composerAppear",
    )
    val fieldAlpha by animateFloatAsState(
        targetValue = if (open) 1f else 0f,
        animationSpec = tween(if (reducedMotion) 0 else if (open) 180 else 220),
        label = "composerAlpha",
    )

    fun cancelVanish() {
        if (overlay != null) overlay = null
        if (heldHeight != null) heldHeight = null
        if (settling) settling = false
    }

    fun submit() {
        if (!open) return
        val raw = draft
        val text = StageMath.deliverableDraft(raw) ?: return
        // Commit first: the thread and live caption must not wait for a visual
        // exit. The dissolve is only an exit animation.
        onSend(text)
        val started = startVanish(
            text = raw,
            reducedMotion = reducedMotion,
            layout = layout,
            scrollPx = scroll.value,
            fieldSize = fieldSize,
            density = density,
            layoutDirection = layoutDirection,
        )
        if (started != null) {
            heldHeight = fieldHeight
            settling = false
            overlay = started
            draft = ""
        } else {
            draft = ""
            heldHeight = null
            settling = false
            focusRequester.requestFocus()
        }
    }

    // Render/tear-down window. The exit keeps its height for 220 ms, then the
    // slot collapses so the live area takes the space back. A field that is
    // being re-created needs a frame to attach its focus node before the
    // request, or the keyboard never opens.
    LaunchedEffect(open) {
        if (open) {
            val alreadyRendered = rendered
            rendered = true
            if (!alreadyRendered) {
                withFrameNanos { }
                withFrameNanos { }
            }
            runCatching { focusRequester.requestFocus() }
        } else {
            cancelVanish()
            everFocused = false
            focusManager.clearFocus(force = true)
            if (!reducedMotion) delay(220)
            rendered = false
        }
    }

    LaunchedEffect(focused) {
        if (focused) everFocused = true
    }

    // Blur closes an empty field after 140 ms, but only once it actually had
    // focus. A non-empty draft keeps the composer open across focus changes.
    LaunchedEffect(open, focused, draft) {
        if (!open || !everFocused || focused || draft.isNotEmpty()) return@LaunchedEffect
        delay(140)
        if (open && !focused && draft.isEmpty() && overlay == null) {
            onRequestClose()
        }
    }

    LaunchedEffect(settling) {
        if (settling) {
            delay(240)
            settling = false
        }
    }

    // After a send the field is empty, so one line is the known target. The
    // visible 66 -> 44 settle is the height animation above.
    LaunchedEffect(draft, textLines) {
        if (textLines > lineCap && draft.isNotEmpty()) {
            withFrameNanos { }
            withFrameNanos { }
            scroll.scrollTo(scroll.maxValue)
        }
    }

    val showGhost = open && draft.isEmpty() && overlay == null
    var ghostIndex by remember { mutableIntStateOf(0) }
    var ghostFading by remember { mutableStateOf(false) }
    val ghostLines = remember(agentName) {
        listOf(
            "Message $agentName",
            "Ask about the canary",
            "Tell it to hold",
            "Type a reply",
        )
    }
    LaunchedEffect(showGhost, reducedMotion) {
        ghostFading = false
        if (!showGhost || reducedMotion) return@LaunchedEffect
        while (true) {
            delay(4200)
            if (!showGhost) break
            ghostFading = true
            delay(200)
            ghostIndex = (ghostIndex + 1) % ghostLines.size
            ghostFading = false
        }
    }
    val ghostAlpha by animateFloatAsState(
        targetValue = if (showGhost && !ghostFading) 1f else 0f,
        animationSpec = tween(if (reducedMotion) 0 else 180),
        label = "composerGhost",
    )

    val activeOverlay = overlay
    LaunchedEffect(activeOverlay) {
        val current = activeOverlay ?: return@LaunchedEffect
        var last = 0L
        while (current.pos > 0f) {
            withFrameNanos { now ->
                val delta = if (last == 0L) 16.67f else min(48f, (now - last) / 1_000_000f)
                last = now
                current.step(delta)
                overlayVersion++
            }
        }
        if (overlay === current) {
            overlay = null
            heldHeight = null
            settling = true
        }
    }

    if (!rendered) return

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(fieldHeight)
            .graphicsLayer {
                alpha = fieldAlpha
                translationY = (1f - appear) * 6.dp.toPx()
            }
            .onSizeChanged { fieldSize = it },
    ) {
        val shape = RoundedCornerShape(16.dp)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .background(CallStageColors.SurfaceRaised)
                .border(
                    width = 1.dp,
                    color = if (focused) CallStageColors.FieldFocus else CallStageColors.Hairline,
                    shape = shape,
                ),
        ) {
            Text(
                text = ghostLines[ghostIndex],
                style = CallStageText.Composer.copy(color = CallStageColors.Muted),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, top = 11.dp)
                    .graphicsLayer { alpha = ghostAlpha }
                    .clearAndSetSemantics { },
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp, vertical = 11.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scroll),
                ) {
                    BasicTextField(
                        value = draft,
                        onValueChange = { next ->
                            if (open) {
                                if (overlay != null) cancelVanish()
                                if (settling) settling = false
                                draft = next.take(StageMath.MAX_DRAFT_CHARS)
                            }
                        },
                        enabled = open && enabled,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(contentHeight)
                            .focusRequester(focusRequester)
                            .onFocusChanged { focused = it.isFocused }
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                                    if (event.isShiftPressed) {
                                        false
                                    } else {
                                        submit()
                                        true
                                    }
                                } else {
                                    false
                                }
                            }
                            .then(
                                if (open) {
                                    Modifier.semantics {
                                        contentDescription = "Message $agentName"
                                    }
                                } else {
                                    // The field is only still composed for its
                                    // exit animation; drop it from the tree so
                                    // focus cannot land on a dead field.
                                    Modifier.clearAndSetSemantics { }
                                },
                            ),
                        textStyle = CallStageText.Composer.copy(color = CallStageColors.OnSurface),
                        // The caret is invisible during the dissolve: the text
                        // is already committed and the canvas is the only thing
                        // that should move.
                        cursorBrush = SolidColor(
                            if (overlay != null) Color.Transparent else CallStageColors.AiBright,
                        ),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            autoCorrect = true,
                            imeAction = ImeAction.Send,
                        ),
                        keyboardActions = KeyboardActions(onSend = { submit() }),
                        onTextLayout = { layout = it },
                        maxLines = Int.MAX_VALUE,
                        minLines = 1,
                    )
                }
            }

            activeOverlay?.let { vanish ->
                Canvas(modifier = Modifier.matchParentSize()) {
                    overlayVersion
                    drawImage(
                        image = vanish.image,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(vanish.image.width, vanish.image.height),
                        dstOffset = IntOffset.Zero,
                        dstSize = IntSize(
                            size.width.roundToInt(),
                            size.height.roundToInt(),
                        ),
                        filterQuality = FilterQuality.None,
                    )
                }
            }
        }
    }
}

/**
 * Builds the dissolve synchronously from the field's own layout, then sweeps
 * it right-to-left at 480 dp/s. Returns null when nothing can be built; the
 * caller then clears the field normally.
 */
private fun startVanish(
    text: String,
    reducedMotion: Boolean,
    layout: TextLayoutResult?,
    scrollPx: Int,
    fieldSize: IntSize,
    density: Density,
    layoutDirection: LayoutDirection,
): VanishOverlay? {
    if (reducedMotion) return null
    if (text.isBlank()) return null
    if (layout == null || fieldSize.width <= 0 || fieldSize.height <= 0) return null

    val realDensity = density.density
    if (realDensity <= 0f) return null
    val dpr = min(realDensity, 3f)
    val scale = dpr / realDensity
    val bitmapWidth = max(1, (fieldSize.width * scale).roundToInt())
    val bitmapHeight = max(1, (fieldSize.height * scale).roundToInt())

    val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
    val image = bitmap.asImageBitmap()
    val canvas = ComposeCanvas(image)

    val paddingPx = with(density) { StageMath.FieldPaddingV.toPx() }
    val horizontalPx = with(density) { StageMath.FieldPaddingH.toPx() }
    val contentLeft = horizontalPx
    val contentTop = paddingPx
    val contentRight = fieldSize.width - horizontalPx
    val contentBottom = fieldSize.height - paddingPx

    CanvasDrawScope().draw(
        density = Density(dpr, density.fontScale),
        layoutDirection = layoutDirection,
        canvas = canvas,
        size = Size(bitmapWidth.toFloat(), bitmapHeight.toFloat()),
    ) {
        scale(scaleX = scale, scaleY = scale, pivot = Offset.Zero) {
            clipRect(contentLeft, contentTop, contentRight, contentBottom) {
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(contentLeft, contentTop - scrollPx),
                    color = CallStageColors.OnSurface,
                )
            }
        }
    }

    val pixels = IntArray(bitmapWidth * bitmapHeight)
    bitmap.getPixels(pixels, 0, bitmapWidth, 0, 0, bitmapWidth, bitmapHeight)

    val step = max(1, dpr.roundToInt())
    val grains = ArrayList<Grain>()
    var maxX = 0f
    var y = 0
    while (y < bitmapHeight) {
        val row = y * bitmapWidth
        var x = 0
        while (x < bitmapWidth) {
            val argb = pixels[row + x]
            val alpha = argb ushr 24
            if (alpha > 10) {
                val px = x / dpr
                val py = y / dpr
                grains += Grain(px, py, 1f, argb)
                if (px > maxX) maxX = px
            }
            x += step
        }
        y += step
    }
    if (grains.isEmpty()) return null

    return VanishOverlay(
        bitmap = bitmap,
        image = image,
        canvas = android.graphics.Canvas(bitmap),
        pos = maxX,
        grains = grains,
        dpr = dpr,
    )
}

private class VanishOverlay(
    val bitmap: Bitmap,
    val image: ImageBitmap,
    val canvas: android.graphics.Canvas,
    var pos: Float,
    val grains: MutableList<Grain>,
    val dpr: Float,
) {
    private val clearPaint = Paint().apply {
        isAntiAlias = false
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val grainPaint = Paint().apply {
        isAntiAlias = false
        style = Paint.Style.FILL
    }

    /**
     * One frame: erase the strip the front has reached, jitter/shrink the
     * grains ahead of it, and repaint them. Grains left of the front keep the
     * pixels they were painted with, exactly like the canvas prototype.
     */
    fun step(deltaMs: Float) {
        val clamped = deltaMs.coerceIn(0f, 48f)
        val frameScale = clamped / 16.67f
        pos -= StageMath.sweepStep(deltaMs)
        if (pos < 0f) pos = 0f

        canvas.drawRect(
            pos * dpr,
            0f,
            bitmap.width.toFloat(),
            bitmap.height.toFloat(),
            clearPaint,
        )

        val iterator = grains.iterator()
        while (iterator.hasNext()) {
            val grain = iterator.next()
            if (grain.x < pos) continue
            if (grain.r <= 0.06f) {
                iterator.remove()
                continue
            }
            grain.x += (if (Random.nextBoolean()) 1f else -1f) * frameScale
            grain.y += (if (Random.nextBoolean()) 1f else -1f) * frameScale
            grain.r -= StageMath.grainShrink(Random.nextFloat()) * frameScale
            if (grain.r <= 0.06f) {
                iterator.remove()
                continue
            }
            grainPaint.color = grain.argb
            canvas.drawRect(
                grain.x * dpr,
                grain.y * dpr,
                (grain.x + grain.r) * dpr,
                (grain.y + grain.r) * dpr,
                grainPaint,
            )
        }
    }
}

private class Grain(
    var x: Float,
    var y: Float,
    var r: Float,
    val argb: Int,
)
