package com.agentcall.app.call

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class StageSheet { NONE, MORE, AUDIO }

/**
 * In-screen bottom sheets from docs/ONGOING_CALL_STAGE_SPEC.md §10.
 *
 * They are not platform dialogs: the More -> Type a message hand-off must
 * remove the panel and focus the composer inside the same tap, with no fading
 * panel left behind a live caret. The scrim blocks the stage, and Escape
 * closes. Focus is moved to the first row and returned by the caller.
 */
@Composable
fun StageSheetOverlay(
    sheet: StageSheet,
    visible: Boolean,
    agentName: String,
    contextSummary: String,
    transcriptShown: Boolean,
    routes: List<CallAudioManager.AudioRoute>,
    selectedRouteId: String,
    reducedMotion: Boolean,
    onOpenComposer: () -> Unit,
    onOpenAudio: () -> Unit,
    onRepeatLast: () -> Unit,
    onTranscriptChange: (Boolean) -> Unit,
    onSelectRoute: (CallAudioManager.AudioRoute) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (sheet == StageSheet.NONE && !visible) return

    val moreFirst = remember { FocusRequester() }
    val audioSelected = remember { FocusRequester() }

    LaunchedEffect(visible, sheet) {
        if (!visible) return@LaunchedEffect
        delay(80)
        when (sheet) {
            StageSheet.MORE -> runCatching { moreFirst.requestFocus() }
            StageSheet.AUDIO -> if (routes.isNotEmpty()) {
                runCatching { audioSelected.requestFocus() }
            }
            StageSheet.NONE -> Unit
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = if (reducedMotion) EnterTransition.None else fadeIn(tween(200)),
            exit = if (reducedMotion) ExitTransition.None else fadeOut(tween(260)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(CallStageColors.Scrim)
                    .pointerInput(Unit) {
                        detectTapGestures { onDismiss() }
                    },
            )
        }

        AnimatedVisibility(
            visible = visible,
            enter = if (reducedMotion) {
                EnterTransition.None
            } else {
                fadeIn(tween(220)) + slideInVertically(
                    animationSpec = tween(220, easing = StageEase),
                    initialOffsetY = { it / 20 },
                )
            },
            exit = if (reducedMotion) {
                ExitTransition.None
            } else {
                fadeOut(tween(260)) + slideOutVertically(
                    animationSpec = tween(260, easing = StageEase),
                    targetOffsetY = { it / 20 },
                )
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.BottomCenter,
            ) {
                BoxWithConstraints {
                    // A long sheet (context summary + four rows) or a short
                    // landscape window must not clip rows off the top of the
                    // screen; the panel scrolls inside a capped height instead.
                    val sheetScroll = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = maxHeight - 48.dp)
                            .shadow(18.dp, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                            .background(CallStageColors.Surface)
                            .verticalScroll(sheetScroll)
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                                    onDismiss()
                                    true
                                } else {
                                    false
                                }
                            }
                            .windowInsetsPadding(
                                WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom),
                            )
                            .padding(start = 24.dp, end = 24.dp, top = 10.dp, bottom = 24.dp)
                            .semantics {
                                paneTitle = if (sheet == StageSheet.AUDIO) "Audio output" else "Call options"
                            },
                    ) {
                        SheetHandle()
                        when (sheet) {
                            StageSheet.MORE -> MoreSheetContent(
                                agentName = agentName,
                                contextSummary = contextSummary,
                                transcriptShown = transcriptShown,
                                routes = routes,
                                selectedRouteId = selectedRouteId,
                                firstRow = moreFirst,
                                onOpenComposer = onOpenComposer,
                                onOpenAudio = onOpenAudio,
                                onRepeatLast = onRepeatLast,
                                onTranscriptChange = onTranscriptChange,
                            )
                            StageSheet.AUDIO -> AudioSheetContent(
                                routes = routes,
                                selectedRouteId = selectedRouteId,
                                selectedRequester = audioSelected,
                                onSelectRoute = onSelectRoute,
                            )
                            StageSheet.NONE -> Unit
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MoreSheetContent(
    agentName: String,
    contextSummary: String,
    transcriptShown: Boolean,
    routes: List<CallAudioManager.AudioRoute>,
    selectedRouteId: String,
    firstRow: FocusRequester,
    onOpenComposer: () -> Unit,
    onOpenAudio: () -> Unit,
    onRepeatLast: () -> Unit,
    onTranscriptChange: (Boolean) -> Unit,
) {
    Text(
        "Call options",
        style = CallStageText.SheetTitle,
        color = CallStageColors.OnSurface,
        modifier = Modifier.padding(bottom = 12.dp),
    )

    if (contextSummary.isNotBlank()) {
        Text(
            "About this call",
            style = CallStageText.RouteSub.copy(fontWeight = FontWeight.Medium),
            color = CallStageColors.Muted,
            modifier = Modifier.padding(bottom = 3.dp),
        )
        Text(
            contextSummary,
            style = CallStageText.ContextBody,
            color = CallStageColors.OnSurface,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        SheetDivider()
    }

    SheetButtonRow(
        icon = Icons.Default.Edit,
        title = "Type a message",
        onClick = onOpenComposer,
        chevron = true,
        focusRequester = firstRow,
    )
    SheetDivider()
    SheetButtonRow(
        icon = Icons.AutoMirrored.Filled.VolumeUp,
        title = "Audio output",
        value = StageMath.routeShortLabel(
            routes.firstOrNull { it.id == selectedRouteId }?.kind
                ?: CallAudioManager.RouteKind.EARPIECE,
        ),
        onClick = onOpenAudio,
        chevron = true,
    )
    SheetDivider()
    SheetButtonRow(
        icon = Icons.Default.Replay,
        title = "Repeat last line",
        onClick = onRepeatLast,
    )
    SheetDivider()
    SheetSwitchRow(
        icon = if (transcriptShown) Icons.Default.Visibility else Icons.Default.VisibilityOff,
        title = "Transcript",
        checked = transcriptShown,
        onCheckedChange = onTranscriptChange,
    )
}

@Composable
private fun AudioSheetContent(
    routes: List<CallAudioManager.AudioRoute>,
    selectedRouteId: String,
    selectedRequester: FocusRequester,
    onSelectRoute: (CallAudioManager.AudioRoute) -> Unit,
) {
    Text(
        "Audio output",
        style = CallStageText.SheetTitle,
        color = CallStageColors.OnSurface,
        modifier = Modifier.padding(bottom = 6.dp),
    )
    if (routes.isEmpty()) {
        Text(
            "No audio devices reported",
            style = CallStageText.RouteSub,
            color = CallStageColors.Muted,
            modifier = Modifier.padding(vertical = 12.dp),
        )
        return
    }
    routes.forEachIndexed { index, route ->
        if (index > 0) SheetDivider()
        val selected = route.id == selectedRouteId
        // focusRequester must wrap the focusable node: applied after
        // selectable() the request never finds a target.
        val rowModifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .then(if (selected) Modifier.focusRequester(selectedRequester) else Modifier)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = { onSelectRoute(route) },
            )
            .padding(end = 12.dp)
        Row(
            modifier = rowModifier,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = routeIcon(route.kind),
                contentDescription = null,
                tint = CallStageColors.Muted,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    route.name,
                    style = CallStageText.RouteName,
                    color = CallStageColors.OnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    StageMath.routeSubtitle(route.kind),
                    style = CallStageText.RouteSub,
                    color = CallStageColors.Muted,
                )
            }
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = CallStageColors.AiBright,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun SheetHandle() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 32.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(CallStageColors.Muted.copy(alpha = 0.4f)),
        )
    }
}

@Composable
private fun SheetButtonRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    value: String? = null,
    chevron: Boolean = false,
    focusRequester: FocusRequester? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = CallStageColors.Muted,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            title,
            style = CallStageText.SheetRow,
            color = CallStageColors.OnSurface,
            modifier = Modifier.weight(1f),
        )
        if (value != null) {
            Text(
                value,
                style = CallStageText.SheetValue,
                color = CallStageColors.Muted,
            )
            Spacer(Modifier.width(4.dp))
        }
        if (chevron) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = CallStageColors.Muted.copy(alpha = 0.7f),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun SheetSwitchRow(
    icon: ImageVector,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = CallStageColors.Muted,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            title,
            style = CallStageText.SheetRow,
            color = CallStageColors.OnSurface,
            modifier = Modifier.weight(1f),
        )
        StageSwitch(checked)
    }
}

@Composable
private fun StageSwitch(checked: Boolean) {
    val knobOffset by animateDpAsState(
        targetValue = if (checked) 16.dp else 0.dp,
        animationSpec = tween(180, easing = StageEase),
        label = "stageSwitch",
    )
    Box(
        modifier = Modifier
            .size(width = 40.dp, height = 24.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (checked) CallStageColors.Ai else Color(0xFF34373D)),
    ) {
        Box(
            modifier = Modifier
                .padding(start = 3.dp, top = 3.dp)
                .graphicsLayer { translationX = knobOffset.toPx() }
                .size(18.dp)
                .clip(CircleShape)
                .background(CallStageColors.OnSurface),
        )
    }
}

@Composable
private fun SheetDivider() {
    HorizontalDivider(
        color = Color.White.copy(alpha = 0.06f),
        thickness = 1.dp,
    )
}

private fun routeIcon(kind: CallAudioManager.RouteKind): ImageVector = when (kind) {
    CallAudioManager.RouteKind.EARPIECE -> Icons.Default.Phone
    CallAudioManager.RouteKind.SPEAKER -> Icons.AutoMirrored.Filled.VolumeUp
    CallAudioManager.RouteKind.BLUETOOTH -> Icons.Default.Bluetooth
    CallAudioManager.RouteKind.WIRED -> Icons.Default.Headset
}

/**
 * The conversation panel from §11. It slides in below the call header so the
 * identity, timer and both voice fields stay visible above it.
 */
@Composable
fun ConversationSheet(
    open: Boolean,
    agentName: String,
    messages: List<ChatBubble>,
    quickReplies: List<String>,
    liveSpeaker: String?,
    reducedMotion: Boolean,
    onClose: () -> Unit,
    onSend: (String) -> Unit,
    onQuickReply: (String) -> Unit,
    onRetry: (ChatBubble) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val thresholdPx = with(density) { 28.dp.toPx() }

    val pinned by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            if (info.totalItemsCount == 0) {
                true
            } else {
                val last = info.visibleItemsInfo.lastOrNull()
                last != null &&
                    last.index >= info.totalItemsCount - 1 &&
                    last.offset + last.size >= info.viewportEndOffset - thresholdPx
            }
        }
    }

    val capped = remember(messages) { messages.takeLast(80) }
    val lastIndex = capped.size - 1

    LaunchedEffect(open) {
        if (open && lastIndex >= 0) listState.scrollToItem(lastIndex)
    }
    LaunchedEffect(capped.size, open) {
        if (open && lastIndex >= 0 && pinned) listState.animateScrollToItem(lastIndex)
    }

    AnimatedVisibility(
        visible = open,
        enter = if (reducedMotion) {
            EnterTransition.None
        } else {
            fadeIn(tween(200)) + slideInVertically(
                animationSpec = tween(260, easing = StageEase),
                initialOffsetY = { it / 10 },
            )
        },
        exit = if (reducedMotion) {
            ExitTransition.None
        } else {
            fadeOut(tween(280)) + slideOutVertically(
                animationSpec = tween(280, easing = StageEase),
                targetOffsetY = { it / 10 },
            )
        },
        modifier = modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .shadow(24.dp, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(CallStageColors.Surface)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        onClose()
                        true
                    } else {
                        false
                    }
                }
                .imePadding()
                .semantics { paneTitle = "Conversation" },
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .size(width = 32.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(CallStageColors.Muted.copy(alpha = 0.32f))
                    .align(Alignment.CenterHorizontally),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 12.dp, top = 6.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Conversation",
                        style = CallStageText.ThreadMessage.copy(
                            fontSize = 16.sp,
                            lineHeight = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = CallStageColors.OnSurface,
                    )
                    Text(
                        if (capped.size == 1) "1 message" else "${capped.size} messages",
                        style = CallStageText.ThreadTime.copy(fontSize = 11.sp),
                        color = CallStageColors.Muted,
                    )
                }
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Button, onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close conversation",
                        tint = CallStageColors.Muted,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            HorizontalDivider(color = CallStageColors.Outline, thickness = 1.dp)

            Box(modifier = Modifier.weight(1f)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 24.dp,
                        end = 24.dp,
                        bottom = 24.dp,
                    ),
                ) {
                    itemsIndexed(capped, key = { _, item -> item.id }) { index, message ->
                        val isAi = message.role == "ai"
                        val isLive = index == lastIndex && liveSpeaker == message.role
                        ConversationMessage(
                            message = message,
                            agentName = agentName,
                            isAi = isAi,
                            isLive = isLive,
                            first = index == 0,
                            onRetry = { onRetry(message) },
                        )
                    }
                }

                JumpToLivePill(
                    visible = !pinned && capped.isNotEmpty(),
                    reducedMotion = reducedMotion,
                    onJump = {
                        scope.launch {
                            if (lastIndex >= 0) {
                                if (reducedMotion) {
                                    listState.scrollToItem(lastIndex)
                                } else {
                                    listState.animateScrollToItem(lastIndex)
                                }
                            }
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp),
                )
            }

            if (quickReplies.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    quickReplies.forEach { option ->
                        Box(
                            modifier = Modifier
                                .heightIn(min = 44.dp)
                                .widthIn(max = 240.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(CallStageColors.SurfaceRaised)
                                .border(
                                    1.dp,
                                    Color.White.copy(alpha = 0.10f),
                                    RoundedCornerShape(999.dp),
                                )
                                .clickable(role = Role.Button) { onQuickReply(option) }
                                .padding(horizontal = 14.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                option,
                                style = CallStageText.ContextBody.copy(
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                ),
                                color = CallStageColors.OnSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = CallStageColors.Outline, thickness = 1.dp)
            ConversationComposer(
                agentName = agentName,
                onSend = onSend,
                bottomInset = if (WindowInsets.ime.getBottom(density) > 0) {
                    0.dp
                } else {
                    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                },
            )
        }
    }
}

/**
 * "Jump to live" pill from docs/ONGOING_CALL_STAGE_SPEC.md §11. The pill stays
 * 34 dp like the prototype; the clickable area around it grows to the 44 dp
 * accessibility minimum (the prototype does the same with its ::before zone).
 *
 * Extracted so the plain `AnimatedVisibility` symbol resolves here: nested in
 * the sheet Column the `ColumnScope` extension would win and refuse to compile.
 */
@Composable
private fun JumpToLivePill(
    visible: Boolean,
    reducedMotion: Boolean,
    onJump: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = if (reducedMotion) EnterTransition.None else fadeIn(tween(160)),
        exit = if (reducedMotion) ExitTransition.None else fadeOut(tween(120)),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .heightIn(min = 44.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClick = onJump,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier
                    .height(34.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(CallStageColors.SurfaceRaised)
                    .border(
                        1.dp,
                        Color.White.copy(alpha = 0.14f),
                        RoundedCornerShape(999.dp),
                    )
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    "Jump to live",
                    style = CallStageText.ThreadMeta.copy(
                        fontSize = 12.sp,
                        letterSpacing = 0.1.sp,
                    ),
                    color = CallStageColors.OnSurface,
                )
                Icon(
                    Icons.Default.ArrowUpward,
                    contentDescription = null,
                    tint = CallStageColors.OnSurface,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

@Composable
private fun ConversationMessage(
    message: ChatBubble,
    agentName: String,
    isAi: Boolean,
    isLive: Boolean,
    first: Boolean,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    val clock = remember(context) { DateFormat.getTimeFormat(context) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (first) 16.dp else 0.dp)
            .drawBehind {
                if (!first) {
                    val y = 0.5f
                    drawLine(
                        color = Color.White.copy(alpha = 0.06f),
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
            }
            .padding(vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isLive) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(if (isAi) CallStageColors.AiBright else CallStageColors.Human),
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                if (isAi) agentName else "You",
                style = CallStageText.ThreadMeta,
                color = if (isAi) CallStageColors.AiBright else CallStageColors.Human,
            )
            Spacer(Modifier.weight(1f))
            Text(
                clock.format(message.timestamp),
                style = CallStageText.ThreadTime,
                color = CallStageColors.Muted.copy(alpha = 0.85f),
            )
        }
        Spacer(Modifier.height(5.dp))
        Text(
            message.text,
            style = CallStageText.ThreadMessage,
            color = if (isLive) Color.White else CallStageColors.OnSurface,
        )
        if (!isAi && message.failed) {
            Spacer(Modifier.height(2.dp))
            Row(
                modifier = Modifier
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .clickable(role = Role.Button, onClick = onRetry)
                    .padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Not sent",
                    style = CallStageText.ThreadTime.copy(fontWeight = FontWeight.SemiBold),
                    color = CallStageColors.Amber,
                )
                Text(
                    "Retry",
                    style = CallStageText.ThreadTime.copy(fontWeight = FontWeight.SemiBold),
                    color = CallStageColors.AiBright,
                )
            }
        }
    }
}

@Composable
private fun ConversationComposer(
    agentName: String,
    onSend: (String) -> Unit,
    bottomInset: androidx.compose.ui.unit.Dp,
) {
    var draft by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    fun submit() {
        val text = StageMath.deliverableDraft(draft) ?: return
        onSend(text)
        draft = ""
        focusRequester.requestFocus()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 22.dp + bottomInset),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(CallStageColors.SurfaceRaised)
                .border(1.dp, CallStageColors.Hairline, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (draft.isEmpty()) {
                Text(
                    "Message $agentName",
                    style = CallStageText.Composer,
                    color = CallStageColors.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clearAndSetSemantics { },
                )
            }
            BasicTextField(
                value = draft,
                onValueChange = { draft = it.take(StageMath.MAX_DRAFT_CHARS) },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                            submit()
                            true
                        } else {
                            false
                        }
                    }
                    .semantics { contentDescription = "Message $agentName" },
                textStyle = CallStageText.Composer.copy(color = CallStageColors.OnSurface),
                cursorBrush = SolidColor(CallStageColors.AiBright),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Send,
                ),
                keyboardActions = KeyboardActions(onSend = { submit() }),
            )
        }

        val canSend = draft.isNotBlank()
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(
                    if (canSend) CallStageColors.SecondaryButton else CallStageColors.SendDisabled,
                )
                .clickable(enabled = canSend, role = Role.Button) { submit() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.ArrowUpward,
                contentDescription = "Send message",
                tint = if (canSend) CallStageColors.OnSurface else CallStageColors.SendDisabledIcon,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

@Composable
fun EndedPanel(
    duration: String,
    focusRequester: FocusRequester,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
            },
        ) {
            Text(
                "Call ended",
                style = CallStageText.EndedTitle,
                color = CallStageColors.OnSurface,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Duration $duration",
                style = CallStageText.EndedDuration,
                color = CallStageColors.Muted,
            )
        }
        Spacer(Modifier.height(18.dp))
        Box(
            modifier = Modifier
                .heightIn(min = 48.dp, max = 48.dp)
                .widthIn(min = 132.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(CallStageColors.SecondaryButton)
                .focusRequester(focusRequester)
                .clickable(role = Role.Button, onClick = onDone)
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Done",
                style = CallStageText.SheetRow.copy(
                    fontSize = 15.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = CallStageColors.OnSurface,
            )
        }
    }
}

/**
 * The four controls from §2. 72 x 76 each with a 56 dp disc; the row never
 * moves when the composer grows because the live area absorbs the change.
 */
@Composable
fun StageControls(
    muted: Boolean,
    routeKind: CallAudioManager.RouteKind,
    ended: Boolean,
    canInteract: Boolean,
    onMute: () -> Unit,
    onAudio: () -> Unit,
    onMore: () -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
    audioFocusRequester: FocusRequester? = null,
    moreFocusRequester: FocusRequester? = null,
) {
    Row(
        modifier = modifier
            .width(312.dp)
            .height(76.dp)
            .then(if (ended) Modifier.clearAndSetSemantics { } else Modifier)
            .graphicsLayer { alpha = if (ended) 0f else 1f },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        StageControl(
            icon = if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
            label = if (muted) "Unmute" else "Mute",
            contentDescription = if (muted) "Unmute AI voice" else "Mute AI voice",
            active = muted,
            enabled = canInteract && !ended,
            onClick = onMute,
            labelColor = CallStageColors.Muted,
        )
        StageControl(
            icon = routeIcon(routeKind),
            label = "Audio",
            contentDescription = "Audio output: ${StageMath.routeShortLabel(routeKind)}",
            active = false,
            enabled = canInteract && !ended,
            onClick = onAudio,
            labelColor = CallStageColors.Muted,
            focusRequester = audioFocusRequester,
        )
        StageControl(
            icon = Icons.Default.MoreHoriz,
            label = "More",
            contentDescription = "More call options",
            active = false,
            enabled = canInteract && !ended,
            onClick = onMore,
            labelColor = CallStageColors.Muted,
            focusRequester = moreFocusRequester,
        )
        StageControl(
            icon = Icons.Default.CallEnd,
            label = "End",
            contentDescription = "End call",
            active = false,
            enabled = canInteract && !ended,
            onClick = onEnd,
            labelColor = CallStageColors.EndLabel,
            end = true,
        )
    }
}

@Composable
private fun StageControl(
    icon: ImageVector,
    label: String,
    contentDescription: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    labelColor: Color,
    end: Boolean = false,
    focusRequester: FocusRequester? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = tween(120),
        label = "controlPress",
    )
    var focused by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .width(72.dp)
            .height(76.dp)
            .onFocusChanged { focused = it.isFocused }
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier,
            )
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) {
                this.contentDescription = contentDescription
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                }
                .drawBehind {
                    if (focused) {
                        drawCircle(
                            color = CallStageColors.AiBright,
                            radius = size.minDimension / 2f + 2.dp.toPx(),
                            style = Stroke(width = 2.dp.toPx()),
                        )
                    }
                }
                .clip(CircleShape)
                .background(
                    when {
                        end -> CallStageColors.Decline
                        active -> CallStageColors.AiTint
                        else -> CallStageColors.SurfaceRaised
                    },
                )
                .border(
                    width = 1.dp,
                    color = when {
                        end -> Color.Transparent
                        active -> CallStageColors.AiTintBorder
                        else -> CallStageColors.Hairline
                    },
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = when {
                    end -> CallStageColors.DeclineIcon
                    active -> CallStageColors.AiBright
                    else -> CallStageColors.OnSurface
                },
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            style = CallStageText.ControlLabel,
            color = labelColor,
            maxLines = 1,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}
