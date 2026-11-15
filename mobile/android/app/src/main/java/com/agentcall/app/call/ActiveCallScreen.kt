package com.agentcall.app.call

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.agentcall.app.R
import com.agentcall.app.ui.composables.rememberReducedMotion
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * The ongoing-call stage from docs/ONGOING_CALL_STAGE_SPEC.md.
 *
 * This replaces the previous call screen while keeping the same
 * [CallViewModel] / [CallService] wiring: connect, the one-second tick, mute,
 * routes, text sends, repeat-last and end all use the existing intents. The
 * screen itself owns only presentation state (composer, thread, sheets,
 * transcript visibility and the end request).
 */
@Composable
fun ActiveCallScreen(
    callId: String,
    context: Context,
    onEndCall: (String) -> Unit,
    contextSummary: String? = null,
    agentName: String = "AI Agent",
    onDismiss: () -> Unit = {},
    viewModel: CallViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val density = LocalDensity.current
    val reducedMotion = rememberReducedMotion()
    val focusManager = LocalFocusManager.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    var composerOpen by remember { mutableStateOf(false) }
    var threadOpen by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(StageSheet.NONE) }
    var sheetVisible by remember { mutableStateOf(false) }
    var sheetOpener by remember { mutableStateOf(StageSheet.MORE) }
    var showTranscript by remember { mutableStateOf(true) }
    var optionsPicked by remember { mutableStateOf(false) }
    var endRequested by remember { mutableStateOf(false) }
    // Seconds frozen at the End tap. Null until then, so a remote hangup
    // never flashes 0:00 — the duration keeps reading the live value until
    // the tap (or the ENDED phase) freezes it.
    var endSeconds by remember { mutableStateOf<Int?>(null) }
    var audioRoutes by remember { mutableStateOf(emptyList<CallAudioManager.AudioRoute>()) }
    var selectedRouteId by remember { mutableStateOf(viewModel.currentAudioRouteId()) }

    val liveFocus = remember { FocusRequester() }
    val moreFocus = remember { FocusRequester() }
    val audioFocus = remember { FocusRequester() }
    val doneFocus = remember { FocusRequester() }

    val ended = endRequested || state.phase == CallPhase.ENDED
    val displayName = state.agentName.ifBlank { agentName }
    val summary = contextSummary?.takeIf { it.isNotBlank() } ?: state.callContext.summary
    val view = LocalView.current

    fun closeComposer() {
        composerOpen = false
        focusManager.clearFocus(force = true)
    }

    fun openComposer() {
        if (ended) return
        // More -> Type a message is a mode change: the sheet goes away in the
        // same frame so no fading panel is left behind a live caret.
        sheet = StageSheet.NONE
        sheetVisible = false
        threadOpen = false
        composerOpen = true
    }

    fun closeThread() {
        threadOpen = false
        scope.launch {
            delay(280)
            if (!threadOpen && !composerOpen && !ended) {
                runCatching { liveFocus.requestFocus() }
            }
        }
    }

    fun openSheet(target: StageSheet, opener: StageSheet) {
        if (ended) return
        composerOpen = false
        focusManager.clearFocus(force = true)
        sheetOpener = opener
        // Refresh on every open so the More row and the radio selection can
        // never show a route the audio layer no longer has.
        audioRoutes = viewModel.availableAudioRoutes()
        selectedRouteId = viewModel.currentAudioRouteId()
        sheet = target
        sheetVisible = true
    }

    fun dismissSheet() {
        sheetVisible = false
    }

    fun sendMessage(text: String) {
        if (ended) return
        val message = StageMath.deliverableDraft(text) ?: return
        val messageId = UUID.randomUUID().toString()
        // Commit the bubble first; only transport it if the commit happened.
        if (!viewModel.sendTextMessage(message, messageId)) return
        context.startService(
            Intent(context, CallService::class.java).apply {
                action = CallService.ACTION_SEND_TEXT
                putExtra(CallService.EXTRA_CALL_ID, state.callId.ifBlank { callId })
                putExtra(CallService.EXTRA_TEXT, message)
                putExtra(CallService.EXTRA_MESSAGE_ID, messageId)
            },
        )
    }

    fun endCall() {
        if (endRequested) return
        endRequested = true
        // Freeze the displayed duration at the tap, not at the service ack:
        // the stage must stop looking live the instant End is pressed.
        endSeconds = state.elapsedSeconds
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        focusManager.clearFocus(force = true)
        onEndCall(state.callId.ifBlank { callId })
    }

    LaunchedEffect(callId) {
        endRequested = false
        endSeconds = null
        composerOpen = false
        threadOpen = false
        sheet = StageSheet.NONE
        sheetVisible = false
        sheetOpener = StageSheet.MORE
        showTranscript = true
        optionsPicked = false
        audioRoutes = viewModel.availableAudioRoutes()
        selectedRouteId = viewModel.currentAudioRouteId()
        viewModel.connect(callId, contextSummary, agentName)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(callId, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive && viewModel.uiState.value.phase != CallPhase.ENDED) {
                delay(1_000)
                viewModel.tick()
            }
        }
    }

    LaunchedEffect(state.isSpeakerOn) {
        selectedRouteId = viewModel.currentAudioRouteId()
        audioRoutes = viewModel.availableAudioRoutes()
    }

    LaunchedEffect(state.callContext.options) {
        optionsPicked = false
    }

    LaunchedEffect(ended) {
        if (ended) {
            composerOpen = false
            threadOpen = false
            sheet = StageSheet.NONE
            sheetVisible = false
            focusManager.clearFocus(force = true)
            // The ended panel is visual only; without this announcement
            // TalkBack never learns the call is over. Units are spelled out
            // because "1:24" would be read as "one colon twenty-four".
            view.announceForAccessibility(
                "Call ended. Duration ${StageMath.spokenDuration(endSeconds ?: state.elapsedSeconds)}.",
            )
        }
    }

    // Finish the sheet exit after its 260 ms animation, then return focus to
    // whichever control opened it.
    LaunchedEffect(sheetVisible, sheet) {
        if (!sheetVisible && sheet != StageSheet.NONE) {
            delay(260)
            if (!sheetVisible) {
                val opener = sheetOpener
                sheet = StageSheet.NONE
                if (!composerOpen && !threadOpen && !ended) {
                    when (opener) {
                        StageSheet.AUDIO -> runCatching { audioFocus.requestFocus() }
                        else -> runCatching { moreFocus.requestFocus() }
                    }
                }
            }
        }
    }

    BackHandler {
        when {
            ended -> onDismiss()
            sheetVisible || sheet != StageSheet.NONE -> dismissSheet()
            threadOpen -> closeThread()
            composerOpen -> closeComposer()
            // Back is minimize, not hang up: a live call must survive the
            // user leaving the screen.
            else -> (context as? Activity)?.moveTaskToBack(true)
        }
    }

    val presence = if (ended) {
        StagePresence.ENDED
    } else {
        StageMath.stagePresence(
            phase = state.phase,
            isAiSpeaking = state.isAiSpeaking,
            isProcessing = state.isProcessing,
            aiResponding = state.aiResponding,
            aiVoiceMuted = state.aiVoiceMuted,
            agentOnline = state.agentOnline,
            userAudioLevel = state.userAudioLevel,
        )
    }
    val duration = StageMath.formatDuration(endSeconds ?: state.elapsedSeconds)
    val currentRouteKind = audioRoutes.firstOrNull { it.id == selectedRouteId }?.kind
        ?: if (state.isSpeakerOn) {
            CallAudioManager.RouteKind.SPEAKER
        } else {
            CallAudioManager.RouteKind.EARPIECE
        }

    val partial = state.partialTranscript.trim()
    val lastMessage = state.messages.lastOrNull()
    val nowIsPartial = partial.isNotEmpty()
    val nowText = if (nowIsPartial) partial else lastMessage?.text.orEmpty()
    val nowIsAi = !nowIsPartial && lastMessage?.role == "ai"
    val nowSpeaker = when {
        nowIsPartial -> "You"
        lastMessage == null -> null
        nowIsAi -> displayName
        else -> "You"
    }
    val previousMessage = when {
        nowIsPartial -> lastMessage
        state.messages.size >= 2 -> state.messages[state.messages.size - 2]
        else -> null
    }
    val captionKey = when {
        nowIsPartial -> "partial"
        lastMessage != null -> lastMessage.id
        else -> "empty"
    }
    val liveSpeakerRole = when {
        nowIsPartial -> "user"
        state.isAiSpeaking -> "ai"
        lastMessage != null -> lastMessage.role
        else -> null
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(CallStageColors.Bg),
    ) {
        // Once the call is over the layout must ignore the IME: the ended
        // panel owns the whole stage and must not jump while the keyboard
        // (which was open under the composer) animates away.
        val imeBottom = if (ended) {
            0.dp
        } else {
            with(density) { WindowInsets.ime.getBottom(density).toDp() }
        }
        val imeOpen = imeBottom > 0.dp
        // adjustNothing keeps the window at full height, so the height that
        // actually matters for the stage is the part above the keyboard.
        val availableHeight = (maxHeight - imeBottom).coerceAtLeast(0.dp)
        // The keyboard must never bury the controls or push the composer off
        // the stage. On short phones the header yields some of its air so the
        // field and the four controls stay reachable; the waves stay edge to
        // edge. A very short window (landscape) uses the same fallback even
        // without a keyboard, otherwise the 182 + 196 air alone would leave no
        // room for the call controls.
        val shortStage = availableHeight < 620.dp
        val compactIme = imeOpen && availableHeight < 740.dp
        val headerTop = when {
            shortStage -> 96.dp
            compactIme -> if (availableHeight < 640.dp) 96.dp else 128.dp
            else -> CallStageDimens.HeaderTop
        }
        val controlsBottomAir = when {
            imeOpen -> imeBottom + 16.dp
            shortStage -> 96.dp
            else -> CallStageDimens.ControlsBottomAir
        }
        val composerMaxLines = when {
            !imeOpen -> StageMath.MAX_COMPOSER_LINES
            availableHeight < 660.dp -> 2
            availableHeight < 760.dp -> 3
            else -> StageMath.MAX_COMPOSER_LINES
        }
        val topWaveHeight = (headerTop - 2.dp)
            .coerceIn(64.dp, CallStageDimens.WaveHeight)

        // 1 · ambient fields, behind everything
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(topWaveHeight)
                .align(Alignment.TopCenter)
                .clipToBounds(),
        ) {
            DitherVoiceField(
                side = StageWaveSide.AI,
                active = !ended && state.isAiSpeaking && !state.aiVoiceMuted,
                level = if (state.aiVoiceMuted) 0f else state.aiAudioLevel,
                frozen = ended,
                modifier = Modifier
                    .fillMaxWidth()
                    // Required, not preferred: a compact header clips the
                    // field's bottom instead of squashing the whole 180 dp
                    // wave into the shorter band.
                    .requiredHeight(CallStageDimens.WaveHeight),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .align(Alignment.BottomCenter)
                .clipToBounds(),
        ) {
            DitherVoiceField(
                side = StageWaveSide.HUMAN,
                active = !ended && state.userAudioLevel > 0.02f,
                level = state.userAudioLevel,
                frozen = ended,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // 2 · stage
        Column(modifier = Modifier.fillMaxSize()) {
            Spacer(Modifier.height(headerTop))
            CallStageHeader(
                agentName = displayName,
                presence = presence,
                duration = duration,
                reducedMotion = reducedMotion,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(CallStageDimens.HeaderHeight)
                    .padding(horizontal = CallStageDimens.Side),
            )

            if (!ended) {
                LiveTurn(
                    previousText = previousMessage?.text,
                    nowSpeaker = nowSpeaker,
                    nowText = nowText,
                    nowIsAi = nowIsAi,
                    captionKey = captionKey,
                    voiceHint = state.voiceHint,
                    transcriptVisible = showTranscript,
                    composing = composerOpen,
                    reducedMotion = reducedMotion,
                    onOpenThread = {
                        if (!ended) {
                            composerOpen = false
                            focusManager.clearFocus(force = true)
                            threadOpen = true
                        }
                    },
                    onShowTranscript = { showTranscript = true },
                    focusRequester = liveFocus,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = CallStageDimens.Side),
                    contentAlignment = Alignment.Center,
                ) {
                    StageComposer(
                        open = composerOpen,
                        agentName = displayName,
                        reducedMotion = reducedMotion,
                        enabled = !ended,
                        onSend = { sendMessage(it) },
                        onRequestClose = { closeComposer() },
                        maxLines = composerMaxLines,
                        modifier = Modifier
                            .widthIn(max = CallStageDimens.ContentWidth)
                            .fillMaxWidth()
                            .padding(bottom = CallStageDimens.ComposerGap),
                    )
                }

                StageControls(
                    muted = state.aiVoiceMuted,
                    routeKind = currentRouteKind,
                    ended = ended,
                    canInteract = true,
                    onMute = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.setAiVoiceMuted(context, !state.aiVoiceMuted)
                    },
                    onAudio = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        openSheet(StageSheet.AUDIO, StageSheet.AUDIO)
                    },
                    onMore = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        openSheet(StageSheet.MORE, StageSheet.MORE)
                    },
                    onEnd = { endCall() },
                    audioFocusRequester = audioFocus,
                    moreFocusRequester = moreFocus,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )

                Spacer(Modifier.height(controlsBottomAir))
            } else {
                Spacer(Modifier.weight(1f))
                // Invisible but still laid out: the ended panel owns the
                // space their discs used to occupy.
                StageControls(
                    muted = state.aiVoiceMuted,
                    routeKind = currentRouteKind,
                    ended = true,
                    canInteract = false,
                    onMute = {},
                    onAudio = {},
                    onMore = {},
                    onEnd = {},
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Spacer(Modifier.height(CallStageDimens.ControlsBottomAir))
            }
        }

        // 3 · overlays
        if (!ended) {
            ConversationSheet(
                open = threadOpen,
                agentName = displayName,
                messages = state.messages,
                quickReplies = if (optionsPicked) {
                    emptyList()
                } else {
                    state.callContext.options.filter { it.isNotBlank() }.take(4)
                },
                liveSpeaker = liveSpeakerRole,
                reducedMotion = reducedMotion,
                onClose = { closeThread() },
                onSend = { sendMessage(it) },
                onQuickReply = { option ->
                    if (!optionsPicked) {
                        optionsPicked = true
                        sendMessage(option)
                    }
                },
                onRetry = { message ->
                    viewModel.retryUserText(context, message.id, message.text)
                },
                modifier = Modifier.padding(
                    top = headerTop + CallStageDimens.HeaderHeight,
                ),
            )

            StageSheetOverlay(
                sheet = sheet,
                visible = sheetVisible,
                agentName = displayName,
                contextSummary = summary,
                transcriptShown = showTranscript,
                routes = audioRoutes,
                selectedRouteId = selectedRouteId,
                reducedMotion = reducedMotion,
                onOpenComposer = { openComposer() },
                onOpenAudio = { openSheet(StageSheet.AUDIO, sheetOpener) },
                onRepeatLast = {
                    // The service owns the mute check; do not duplicate it here
                    // or a muted repeat becomes a silent, unexplained no-op.
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    context.startService(
                        Intent(context, CallService::class.java).apply {
                            action = CallService.ACTION_REPEAT_LAST
                        },
                    )
                    dismissSheet()
                },
                onTranscriptChange = { showTranscript = it },
                onSelectRoute = { route ->
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.selectAudioRoute(route)
                    // Reflect the route the audio layer actually applied, not
                    // the row the user tapped (selection can fall back).
                    selectedRouteId = viewModel.currentAudioRouteId()
                    audioRoutes = viewModel.availableAudioRoutes()
                    scope.launch {
                        delay(160)
                        // The audio layer can settle a beat after the request;
                        // re-read the applied state before the sheet closes so
                        // reopening it can never show a stale row.
                        selectedRouteId = viewModel.currentAudioRouteId()
                        audioRoutes = viewModel.availableAudioRoutes()
                        sheetVisible = false
                    }
                },
                onDismiss = { dismissSheet() },
            )
        }

        if (ended) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        top = headerTop + CallStageDimens.HeaderHeight,
                        bottom = controlsBottomAir,
                    ),
            ) {
                EndedPanel(
                    duration = duration,
                    focusRequester = doneFocus,
                    onDone = onDismiss,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun CallStageHeader(
    agentName: String,
    presence: StagePresence,
    duration: String,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    val dot by animateColorAsState(
        targetValue = presence.dotColor,
        animationSpec = tween(if (reducedMotion) 0 else 200),
        label = "stagePresenceDot",
    )
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.agentcall_logo_transparent),
            contentDescription = null,
            modifier = Modifier.size(38.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                agentName,
                style = CallStageText.Name,
                color = CallStageColors.OnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(dot),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    presence.label,
                    style = CallStageText.Presence,
                    color = CallStageColors.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            duration,
            style = CallStageText.Duration,
            color = CallStageColors.OnSurface,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

@Composable
private fun LiveTurn(
    previousText: String?,
    nowSpeaker: String?,
    nowText: String,
    nowIsAi: Boolean,
    captionKey: String,
    voiceHint: String?,
    transcriptVisible: Boolean,
    composing: Boolean,
    reducedMotion: Boolean,
    onOpenThread: () -> Unit,
    onShowTranscript: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    BoxWithConstraints(
        modifier = modifier
            .clipToBounds()
            .focusRequester(focusRequester)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClickLabel = "Open conversation",
                onClick = onOpenThread,
            ),
        contentAlignment = Alignment.Center,
    ) {
        val liveHeight = maxHeight
        val showPrevious = transcriptVisible &&
            previousText != null &&
            !composing &&
            liveHeight >= 140.dp
        val captionLines = when {
            liveHeight >= 220.dp -> 3
            liveHeight >= 130.dp -> 2
            else -> 1
        }
        val showHint = transcriptVisible &&
            voiceHint != null &&
            !composing &&
            liveHeight >= 150.dp
        val showNow = transcriptVisible && liveHeight >= 56.dp
        val shownPrevious = previousText.takeIf { showPrevious }
        val shownHint = voiceHint.takeIf { showHint }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = CallStageDimens.Side)
                .graphicsLayer { alpha = if (pressed) 0.86f else 1f },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!transcriptVisible) {
                HiddenTranscriptState(onShow = onShowTranscript)
            } else {
                if (shownPrevious != null) {
                    Text(
                        shownPrevious,
                        style = CallStageText.Previous,
                        color = CallStageColors.Muted.copy(alpha = 0.45f),
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(CallStageDimens.PreviousWidth),
                    )
                    Spacer(Modifier.height(if (composing) 8.dp else 22.dp))
                }
                if (showNow) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .widthIn(max = CallStageDimens.LiveWidth)
                            .fillMaxWidth()
                            .semantics(mergeDescendants = true) {
                                liveRegion = LiveRegionMode.Polite
                            },
                    ) {
                        if (nowSpeaker != null) {
                            Text(
                                nowSpeaker,
                                style = CallStageText.Speaker,
                                color = if (nowIsAi) {
                                    CallStageColors.AiBright
                                } else {
                                    CallStageColors.Human
                                },
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                        SwappingCaption(
                            text = nowText.ifBlank { "The call is live." },
                            captionKey = captionKey,
                            reducedMotion = reducedMotion,
                            maxLines = captionLines,
                        )
                    }
                }
                if (shownHint != null) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        shownHint,
                        style = CallStageText.RouteSub.copy(fontSize = 13.sp, lineHeight = 18.sp),
                        color = CallStageColors.Muted,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = CallStageDimens.LiveWidth),
                    )
                }
            }
        }
    }
}

@Composable
private fun SwappingCaption(
    text: String,
    captionKey: String,
    reducedMotion: Boolean,
    maxLines: Int,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val alpha = remember { Animatable(1f) }
    val translate = remember { Animatable(0f) }
    var shown by remember { mutableStateOf(text) }
    var firstKey by remember { mutableStateOf(true) }

    // Partial transcripts update the same turn; they must not retrigger the
    // 400 ms swap on every token.
    LaunchedEffect(text) {
        shown = text
    }
    LaunchedEffect(captionKey) {
        if (firstKey) {
            firstKey = false
            return@LaunchedEffect
        }
        if (reducedMotion) {
            alpha.snapTo(1f)
            translate.snapTo(0f)
            return@LaunchedEffect
        }
        alpha.snapTo(0f)
        translate.snapTo(with(density) { 5.dp.toPx() })
        coroutineScope {
            launch { alpha.animateTo(1f, tween(400, easing = StageEase)) }
            launch { translate.animateTo(0f, tween(400, easing = StageEase)) }
        }
    }

    Text(
        text = shown,
        style = CallStageText.Caption,
        color = CallStageColors.OnSurface,
        textAlign = TextAlign.Center,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                this.alpha = alpha.value
                translationY = translate.value
            },
    )
}

@Composable
private fun HiddenTranscriptState(onShow: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            Icons.Default.VisibilityOff,
            contentDescription = null,
            tint = CallStageColors.Muted,
            modifier = Modifier.size(26.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Transcript hidden",
            style = CallStageText.SheetRow.copy(fontSize = 15.sp),
            color = CallStageColors.OnSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "The call is still live.",
            style = CallStageText.RouteSub.copy(fontSize = 13.sp, lineHeight = 18.sp),
            color = CallStageColors.Muted,
        )
        Spacer(Modifier.height(14.dp))
        Box(
            modifier = Modifier
                .heightIn(min = 44.dp)
                .clip(RoundedCornerShape(999.dp))
                .border(1.dp, CallStageColors.Hairline, RoundedCornerShape(999.dp))
                .clickable(role = Role.Button, onClick = onShow)
                .padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Show transcript",
                style = CallStageText.SheetValue,
                color = CallStageColors.AiBright,
            )
        }
    }
}
