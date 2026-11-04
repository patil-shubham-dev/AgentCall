package com.agentcall.app.call

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.agentcall.app.MainActivity
import com.agentcall.app.ui.composables.ActionCircle
import com.agentcall.app.ui.composables.AgentCallAvatar
import com.agentcall.app.ui.composables.CallWaveBackground
import com.agentcall.app.ui.composables.WaveMood
import com.agentcall.app.ui.theme.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

private val BubbleTimeFormat = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

@AndroidEntryPoint
class CallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val callId = intent.getStringExtra("call_id") ?: run { finish(); return }
        val callerName = intent.getStringExtra("caller_name") ?: "AI Agent"

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
        }

        setContent {
            AgentCallTheme {
                ActiveCallScreen(
                    callId = callId,
                    context = this@CallActivity,
                    contextSummary = intent.getStringExtra(CallService.EXTRA_CONTEXT_SUMMARY),
                    agentName = callerName,
                    onEndCall = { cid ->
                        startService(Intent(this@CallActivity, CallService::class.java).apply {
                            action = CallService.ACTION_END_CALL
                            putExtra(CallService.EXTRA_CALL_ID, cid)
                        })
                    },
                    onDismiss = { finish() },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
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
    val listState = rememberLazyListState()
    var showContextBanner by remember { mutableStateOf(true) }
    var textInput by remember { mutableStateOf("") }
    var showTranscript by remember { mutableStateOf(true) }
    var showComposer by remember { mutableStateOf(false) }
    var showMoreSheet by remember { mutableStateOf(false) }
    var showAudioSheet by remember { mutableStateOf(false) }
    var optionsPicked by remember { mutableStateOf(false) }
    var endRequested by remember { mutableStateOf(false) }
    var selectedRouteId by remember { mutableStateOf(viewModel.currentAudioRouteId()) }
    var audioRoutes by remember { mutableStateOf(emptyList<CallAudioManager.AudioRoute>()) }
    var appeared by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val haptics = LocalHapticFeedback.current
    val composerFocusRequester = remember { FocusRequester() }
    val contentAlpha by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(durationMillis = 320),
        label = "callScreenFade",
    )

    fun sendDraft() {
        val message = textInput.trim()
        if (message.isBlank()) return
        val messageId = UUID.randomUUID().toString()
        viewModel.sendTextMessage(message, messageId)
        context.startService(Intent(context, CallService::class.java).apply {
            action = CallService.ACTION_SEND_TEXT
            putExtra(CallService.EXTRA_CALL_ID, state.callId)
            putExtra(CallService.EXTRA_TEXT, message)
            putExtra(CallService.EXTRA_MESSAGE_ID, messageId)
        })
        textInput = ""
        focusManager.clearFocus()
    }

    BackHandler(enabled = showMoreSheet || showAudioSheet || showComposer) {
        showMoreSheet = false
        showAudioSheet = false
        showComposer = false
        focusManager.clearFocus()
    }

    LaunchedEffect(callId) {
        endRequested = false
        textInput = ""
        optionsPicked = false
        showComposer = false
        showMoreSheet = false
        showAudioSheet = false
        appeared = true
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

    LaunchedEffect(state.callContext.options) {
        optionsPicked = false
    }

    LaunchedEffect(showComposer) {
        if (showComposer) composerFocusRequester.requestFocus()
    }

    LaunchedEffect(showAudioSheet) {
        if (showAudioSheet) {
            audioRoutes = viewModel.availableAudioRoutes()
            selectedRouteId = viewModel.currentAudioRouteId()
        }
    }

    LaunchedEffect(state.isSpeakerOn) {
        selectedRouteId = viewModel.currentAudioRouteId()
    }

    LaunchedEffect(state.messages.size) {
        if (!showTranscript) return@LaunchedEffect
        val layoutInfo = listState.layoutInfo
        val total = layoutInfo.totalItemsCount
        val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        val isNearBottom = total <= 2 || lastVisible >= total - 3
        if (state.messages.isNotEmpty() && (isNearBottom || state.messages.size == 1)) {
            listState.animateScrollToItem(state.messages.size - 1)
        }
    }

    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    LaunchedEffect(imeBottom) {
        if (!showTranscript || state.messages.isEmpty()) return@LaunchedEffect
        val layoutInfo = listState.layoutInfo
        val total = layoutInfo.totalItemsCount
        val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (imeBottom > 0 && (total <= 2 || lastVisible >= total - 3)) {
            listState.animateScrollToItem(state.messages.size - 1)
        }
    }

    LaunchedEffect(state.isConnected) {
        if (state.isConnected) {
            delay(5_000)
            showContextBanner = false
        }
    }

    val minutes = state.elapsedSeconds / 60
    val seconds = state.elapsedSeconds % 60
    val timerText = "%02d:%02d".format(minutes, seconds)
    val presence = callPresence(
        phase = state.phase,
        isRecording = state.isRecording,
        isProcessing = state.isProcessing,
        isAiSpeaking = state.isAiSpeaking,
        aiResponding = state.aiResponding,
        aiVoiceMuted = state.aiVoiceMuted,
        agentOnline = state.agentOnline,
    )
    val presenceColor = presenceColorFor(presence)
    val waveMood = waveMoodFor(presence)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        val contentWidth = if (maxWidth >= 900.dp) 720.dp else maxWidth
        val compactHeight = maxHeight < 540.dp
        val hideContextBanner = maxHeight < 430.dp

        // 1 · ambient state layer (decorative, no input)
        CallWaveBackground(
            mood = waveMood,
            userLevel = state.userAudioLevel,
            aiLevel = state.aiAudioLevel,
            modifier = Modifier.fillMaxSize(),
        )

        // 2 · readability scrim: header and controls stay legible over the aura
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to MaterialTheme.colorScheme.background.copy(alpha = 0.88f),
                        0.10f to MaterialTheme.colorScheme.background.copy(alpha = 0.46f),
                        0.24f to Color.Transparent,
                        0.76f to Color.Transparent,
                        0.90f to MaterialTheme.colorScheme.background.copy(alpha = 0.58f),
                        1f to MaterialTheme.colorScheme.background.copy(alpha = 0.94f),
                    ),
                ),
        )

        // 3 · content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .align(Alignment.Center)
                .widthIn(max = contentWidth)
                .windowInsetsPadding(WindowInsets.statusBars)
                .graphicsLayer { alpha = contentAlpha },
        ) {
            CallHeader(
                agentName = agentName,
                presence = presence,
                presenceColor = presenceColor,
                timerText = if (presence == CallPresence.CONNECTING) "--:--" else timerText,
                onMinimize = onDismiss,
            )

            // One transient context line under the header, then the call owns
            // the screen. No second status banner competes with the header.
            AnimatedVisibility(
                visible = showContextBanner && !hideContextBanner && state.callContext.summary.isNotBlank(),
                enter = slideInVertically(animationSpec = spring(dampingRatio = 0.9f)) + fadeIn(),
                exit = slideOutVertically() + fadeOut(),
            ) {
                ContextLine(state.callContext.summary)
            }

            if (showTranscript) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.ScreenPadding),
                    verticalArrangement = Arrangement.spacedBy(if (compactHeight) 8.dp else 12.dp),
                    contentPadding = PaddingValues(top = 10.dp, bottom = 6.dp),
                ) {
                    if (state.messages.isEmpty() && presence != CallPresence.THINKING) {
                        item(key = "empty-transcript") { EmptyTranscript() }
                    } else {
                        items(state.messages, key = { it.id }) { msg ->
                            TranscriptEntry(
                                msg = msg,
                                isAi = msg.role == "ai",
                                onRetry = if (msg.role == "user" && msg.failed) {
                                    { viewModel.retryUserText(context, msg.id, msg.text) }
                                } else {
                                    null
                                },
                            )
                        }
                        if (presence == CallPresence.THINKING) {
                            item(key = "typing") { TypingIndicator() }
                        }
                    }
                }
            } else {
                HiddenTranscript(
                    count = state.messages.size,
                    onShow = { showTranscript = true },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.ScreenPadding, vertical = 12.dp),
                )
            }

            if (state.phase == CallPhase.ENDED) {
                CallSummaryCard(
                    statusText = state.statusText,
                    timerText = timerText,
                    onClose = onDismiss,
                )
            } else {
                QuickReplyChips(
                    options = state.callContext.options,
                    enabled = !optionsPicked,
                    onPick = { option ->
                        optionsPicked = true
                        val messageId = UUID.randomUUID().toString()
                        viewModel.sendTextMessage(option, messageId)
                        context.startService(Intent(context, CallService::class.java).apply {
                            action = CallService.ACTION_SEND_TEXT
                            putExtra(CallService.EXTRA_CALL_ID, state.callId)
                            putExtra(CallService.EXTRA_TEXT, option)
                            putExtra(CallService.EXTRA_MESSAGE_ID, messageId)
                        })
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                MessageComposer(
                    visible = showComposer,
                    text = textInput,
                    onTextChange = { textInput = it },
                    onSend = ::sendDraft,
                    focusRequester = composerFocusRequester,
                )

                CallActionBar(
                    routeIcon = routeKindIcon(selectedRouteKind(audioRoutes, selectedRouteId)),
                    routeActive = selectedRouteId != CallAudioManager.ROUTE_EARPIECE,
                    endRequested = endRequested,
                    compact = compactHeight,
                    onAudioPickerOpen = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        showAudioSheet = true
                    },
                    onMoreClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        showMoreSheet = true
                    },
                    onEndCall = {
                        if (endRequested) return@CallActionBar
                        endRequested = true
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onEndCall(state.callId.ifBlank { callId })
                    },
                )
            }
        }

        if (showMoreSheet) {
            CallOptionsSheet(
                aiVoiceMuted = state.aiVoiceMuted,
                transcriptShown = showTranscript,
                onAiVoiceMutedChange = { muted ->
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.setAiVoiceMuted(context, muted)
                },
                onRepeatLast = {
                    if (!state.aiVoiceMuted) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        context.startService(Intent(context, CallService::class.java).apply {
                            action = CallService.ACTION_REPEAT_LAST
                        })
                    }
                },
                onTypeMessage = {
                    showMoreSheet = false
                    showComposer = true
                },
                onTranscriptChange = {
                    showTranscript = it
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                },
                onOpenSettings = {
                    showMoreSheet = false
                    runCatching {
                        context.startActivity(
                            Intent(context, MainActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                putExtra("open_settings", true)
                            },
                        )
                    }
                },
                onDismiss = { showMoreSheet = false },
            )
        }

        if (showAudioSheet) {
            AudioRouteSheet(
                routes = audioRoutes,
                selectedRouteId = selectedRouteId,
                onSelect = { route ->
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.selectAudioRoute(route)
                    selectedRouteId = route.id
                },
                onDismiss = { showAudioSheet = false },
            )
        }
    }
}

/**
 * Call header: identity, one live state line, duration, minimize. The state
 * line cross-fades inside a fixed-height slot so presence changes never shift
 * the layout.
 */
@Composable
private fun CallHeader(
    agentName: String,
    presence: CallPresence,
    presenceColor: Color,
    timerText: String,
    onMinimize: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.ScreenPadding, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AgentCallAvatar(size = 44.dp, contentDescription = "$agentName, AgentCall AI")
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                agentName,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp),
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(presenceColor),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier.height(16.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    AnimatedContent(
                        targetState = presence,
                        transitionSpec = {
                            fadeIn(tween(220)) togetherWith fadeOut(tween(140))
                        },
                        label = "headerState",
                    ) { p ->
                        Text(
                            p.label,
                            style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.sp),
                            color = presenceColorFor(p),
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        Text(
            timerText,
            style = MonoTitle,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.92f),
        )
        IconButton(
            onClick = onMinimize,
            modifier = Modifier.size(44.dp).testTag("minimize_call"),
        ) {
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = "Minimize call",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Context is a quiet aside under the header — never a card. */
@Composable
private fun ContextLine(summary: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.ScreenPadding, vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .width(2.dp)
                .height(30.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(BrandPurple.copy(alpha = 0.65f)),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(
                "AI is calling about",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EmptyTranscript() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Default.ChatBubbleOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.size(22.dp),
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            "The transcript appears here",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Speak naturally — the mic is already on.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun HiddenTranscript(
    count: Int,
    onShow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Default.VisibilityOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(26.dp),
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            "Transcript hidden",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            if (count == 1) "1 message in this call" else "$count messages in this call",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        TextButton(onClick = onShow) {
            Text("Show transcript", color = BrandPurpleLight)
        }
    }
}

@Composable
private fun CallSummaryCard(
    statusText: String,
    timerText: String,
    onClose: () -> Unit,
) {
    val disconnected = statusText.startsWith("AI disconnected")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
            .padding(horizontal = Spacing.ScreenPadding, vertical = 8.dp)
            .clip(RoundedCornerShape(Radii.Panel))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
            .padding(Spacing.CardPadding),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (disconnected) Icons.Default.ErrorOutline else Icons.Default.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (disconnected) Error else Success,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                statusText.ifBlank { "Call ended" },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            "Duration $timerText",
            style = MonoBody,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(14.dp))
        Button(
            onClick = onClose,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(Radii.Field),
            colors = ButtonDefaults.buttonColors(containerColor = BrandPurple),
        ) {
            Text("Done", color = OnAccent)
        }
    }
}

@Composable
private fun QuickReplyChips(
    options: List<String>,
    enabled: Boolean,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!enabled || options.isEmpty()) return
    LazyRow(
        modifier = modifier.padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = Spacing.ScreenPadding),
    ) {
        itemsIndexed(options) { _, option ->
            Surface(
                onClick = { onPick(option) },
                shape = RoundedCornerShape(Radii.Pill),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.78f),
            ) {
                Text(
                    option,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .widthIn(max = 240.dp)
                        .wrapContentHeight(Alignment.CenterVertically)
                        .padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun MessageComposer(
    visible: Boolean,
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    focusRequester: FocusRequester,
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(
            animationSpec = spring(dampingRatio = 0.9f, stiffness = 420f),
            expandFrom = Alignment.Bottom,
        ) + fadeIn(),
        exit = shrinkVertically(
            animationSpec = spring(dampingRatio = 0.9f, stiffness = 420f),
            shrinkTowards = Alignment.Bottom,
        ) + fadeOut(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.ScreenPadding, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Enter && !event.isShiftPressed) {
                            onSend()
                            true
                        } else {
                            false
                        }
                    },
                placeholder = { Text("Type a message…", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                singleLine = false,
                maxLines = 3,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = BrandPurple,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    cursorColor = BrandPurple,
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                ),
                shape = RoundedCornerShape(Radii.Field),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
            )
            IconButton(
                onClick = onSend,
                enabled = text.isNotBlank(),
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (text.isNotBlank()) BrandPurple else ControlInactive),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send message",
                    tint = if (text.isNotBlank()) OnAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Three controls, matching real call apps: audio route, more, end. The mic is
 * never a button — capture is armed by the call session and VAD handles
 * turn-taking. No divider; the scrim separates the bar from the aura.
 */
@Composable
private fun CallActionBar(
    routeIcon: androidx.compose.ui.graphics.vector.ImageVector,
    routeActive: Boolean,
    endRequested: Boolean,
    compact: Boolean,
    onAudioPickerOpen: () -> Unit,
    onMoreClick: () -> Unit,
    onEndCall: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
            .padding(horizontal = 32.dp, vertical = if (compact) 4.dp else 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActionCircle(
            icon = routeIcon,
            label = "Audio",
            onClick = onAudioPickerOpen,
            size = 52.dp,
            iconTint = if (routeActive) BrandPurpleLight else MaterialTheme.colorScheme.onSurface,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            bgColor = ControlInactive.copy(alpha = 0.9f),
            shadowElevation = 0.dp,
        )
        ActionCircle(
            icon = Icons.Default.MoreHoriz,
            label = "More",
            onClick = onMoreClick,
            size = 52.dp,
            bgColor = ControlInactive.copy(alpha = 0.9f),
            shadowElevation = 0.dp,
        )
        ActionCircle(
            icon = Icons.Default.CallEnd,
            label = if (endRequested) "Ending" else "End",
            onClick = onEndCall,
            size = 56.dp,
            isSolid = true,
            solidColor = if (endRequested) ErrorBg else EndCallActive,
            iconTint = OnAccent,
            labelColor = Error,
            shadowElevation = 0.dp,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CallOptionsSheet(
    aiVoiceMuted: Boolean,
    transcriptShown: Boolean,
    onAiVoiceMutedChange: (Boolean) -> Unit,
    onRepeatLast: () -> Unit,
    onTypeMessage: () -> Unit,
    onTranscriptChange: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = Radii.Sheet, topEnd = Radii.Sheet),
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                .padding(horizontal = Spacing.ScreenPadding),
        ) {
            Row(
                modifier = Modifier.padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Call options",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(44.dp)) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close call options",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            ActionSheetRow(
                icon = Icons.AutoMirrored.Filled.VolumeOff,
                title = "Mute AI voice",
                description = "Silence spoken replies — text keeps flowing",
                onClick = { onAiVoiceMutedChange(!aiVoiceMuted) },
                trailing = {
                    Switch(
                        checked = aiVoiceMuted,
                        onCheckedChange = onAiVoiceMutedChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = OnAccent,
                            checkedTrackColor = BrandPurple,
                        ),
                    )
                },
            )
            SheetDivider()
            ActionSheetRow(
                icon = Icons.Default.Replay,
                title = "Replay last response",
                description = "Play the AI's latest reply again",
                onClick = onRepeatLast,
                enabled = !aiVoiceMuted,
            )
            SheetDivider()
            ActionSheetRow(
                icon = Icons.AutoMirrored.Filled.Article,
                title = if (transcriptShown) "Hide transcript" else "Show transcript",
                description = "Keep the live call screen uncluttered",
                onClick = { onTranscriptChange(!transcriptShown) },
                trailing = {
                    Switch(
                        checked = transcriptShown,
                        onCheckedChange = onTranscriptChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = OnAccent,
                            checkedTrackColor = BrandPurple,
                        ),
                    )
                },
            )
            SheetDivider()
            ActionSheetRow(
                icon = Icons.Default.Keyboard,
                title = "Type a message",
                description = "Reply without speaking",
                onClick = onTypeMessage,
            )
            SheetDivider()
            ActionSheetRow(
                icon = Icons.Default.Settings,
                title = "Call settings",
                description = "Voice, language and behavior",
                onClick = onOpenSettings,
            )
            Spacer(modifier = Modifier.height(Spacing.M))
        }
    }
}

@Composable
private fun SheetDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 52.dp),
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
        thickness = 1.dp,
    )
}

@Composable
private fun ActionSheetRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = Color.Transparent,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (enabled) AccentTint else MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (enabled) BrandPurpleLight else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(19.dp),
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (trailing != null) {
                Spacer(modifier = Modifier.width(8.dp))
                trailing()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioRouteSheet(
    routes: List<CallAudioManager.AudioRoute>,
    selectedRouteId: String,
    onSelect: (CallAudioManager.AudioRoute) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = Radii.Sheet, topEnd = Radii.Sheet),
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                .padding(horizontal = Spacing.ScreenPadding),
        ) {
            Row(
                modifier = Modifier.padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Audio output",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Where the AI voice plays",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(44.dp)) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close audio output",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            routes.forEachIndexed { index, route ->
                if (index > 0) SheetDivider()
                AudioRouteRow(
                    route = route,
                    selected = route.id == selectedRouteId,
                    onSelect = { onSelect(route) },
                )
            }
            Spacer(modifier = Modifier.height(Spacing.M))
        }
    }
}

@Composable
private fun AudioRouteRow(
    route: CallAudioManager.AudioRoute,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Surface(
        onClick = onSelect,
        color = Color.Transparent,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                routeKindIcon(route.kind),
                contentDescription = null,
                tint = if (selected) BrandPurpleLight else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                route.name,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                color = if (selected) BrandPurpleLight else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = BrandPurpleLight,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * Transcript entry: a conversation note, not a notification card. One soft
 * fill, no border, the timestamp sits inside at low emphasis, and the text is
 * allowed to wrap to any length.
 */
@Composable
private fun TranscriptEntry(msg: ChatBubble, isAi: Boolean, onRetry: (() -> Unit)? = null) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        visible = true
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200)) + slideInVertically(
            initialOffsetY = { it / 4 },
            animationSpec = spring(dampingRatio = 0.9f, stiffness = 340f),
        ),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (isAi) Arrangement.Start else Arrangement.End,
                verticalAlignment = Alignment.Bottom,
            ) {
                if (isAi) {
                    AgentCallAvatar(size = 22.dp, contentDescription = "AgentCall AI")
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .widthIn(max = 330.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(
                            topStart = if (isAi) 6.dp else 18.dp,
                            topEnd = if (isAi) 18.dp else 6.dp,
                            bottomStart = 18.dp,
                            bottomEnd = 18.dp,
                        ),
                        color = if (isAi) {
                            DarkSurfaceElevated.copy(alpha = 0.86f)
                        } else {
                            BrandPurple.copy(alpha = 0.26f)
                        },
                        tonalElevation = 0.dp,
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
                        ) {
                            Text(
                                msg.text,
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                BubbleTimeFormat.format(Instant.ofEpochMilli(msg.timestamp)),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = if (isAi) {
                                    DarkTextTertiary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                },
                                textAlign = TextAlign.End,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                if (!isAi) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(DarkSurfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = "You",
                            modifier = Modifier.size(14.dp),
                            tint = DarkTextSecondary,
                        )
                    }
                }
            }
            if (!isAi && msg.failed) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Surface(
                        onClick = onRetry ?: {},
                        shape = RoundedCornerShape(Radii.Pill),
                        color = Amber400.copy(alpha = 0.16f),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                modifier = Modifier.size(13.dp),
                                tint = Amber400,
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "Not sent — tap to retry",
                                style = MaterialTheme.typography.labelSmall,
                                color = Amber300,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TypingIndicator() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AgentCallAvatar(size = 22.dp, contentDescription = "AgentCall AI")
        Spacer(modifier = Modifier.width(8.dp))
        Surface(
            shape = RoundedCornerShape(topStart = 6.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp),
            color = DarkSurfaceElevated.copy(alpha = 0.86f),
        ) {
            TypingDots()
        }
    }
}

@Composable
private fun TypingDots() {
    val transition = rememberInfiniteTransition(label = "typingDots")
    Row(
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                0.24f,
                1f,
                infiniteRepeatable(
                    tween(durationMillis = 900, delayMillis = index * 150),
                    RepeatMode.Reverse,
                ),
                label = "typingDot$index",
            )
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(BrandPurpleLight.copy(alpha = alpha)),
            )
        }
    }
}

/**
 * Wave behaviour per call state (see WaveMood):
 * - listening: steady, follows the user's real mic level,
 * - thinking: slower and more deliberate — visibly different from listening,
 * - speaking: faster and brighter, follows the TTS amplitude,
 * - muted / offline / ended: calm, nearly still.
 */
private fun waveMoodFor(presence: CallPresence): WaveMood = when (presence) {
    CallPresence.CONNECTING -> WaveMood(amplitude = 0.30f, speed = 0.75f, brightness = 0.40f)
    CallPresence.LISTENING -> WaveMood(amplitude = 0.34f, speed = 1.0f, brightness = 0.55f)
    CallPresence.THINKING -> WaveMood(amplitude = 0.40f, speed = 0.42f, brightness = 0.48f, audioGain = 0.25f)
    CallPresence.SPEAKING -> WaveMood(amplitude = 0.56f, speed = 1.55f, brightness = 0.76f)
    CallPresence.RECONNECTING -> WaveMood(amplitude = 0.20f, speed = 0.5f, brightness = 0.30f)
    CallPresence.MUTED -> WaveMood(amplitude = 0.18f, speed = 0.45f, brightness = 0.26f, audioGain = 0.2f)
    CallPresence.ERROR -> WaveMood(amplitude = 0.14f, speed = 0.4f, brightness = 0.22f, audioGain = 0.2f)
    CallPresence.ENDED -> WaveMood(amplitude = 0.08f, speed = 0.3f, brightness = 0.10f, audioGain = 0f)
}

private fun presenceColorFor(presence: CallPresence) = when (presence) {
    CallPresence.CONNECTING -> PresenceConnecting
    CallPresence.LISTENING -> PresenceListening
    CallPresence.THINKING -> PresenceThinking
    CallPresence.SPEAKING -> PresenceSpeaking
    CallPresence.RECONNECTING -> PresenceReconnecting
    CallPresence.MUTED -> PresenceMuted
    CallPresence.ERROR -> PresenceError
    CallPresence.ENDED -> PresenceEnded
}

private fun routeKindIcon(kind: CallAudioManager.RouteKind) = when (kind) {
    CallAudioManager.RouteKind.SPEAKER -> Icons.AutoMirrored.Filled.VolumeUp
    CallAudioManager.RouteKind.EARPIECE -> Icons.Default.Phone
    CallAudioManager.RouteKind.BLUETOOTH -> Icons.Default.Bluetooth
    CallAudioManager.RouteKind.WIRED -> Icons.Default.Headset
}

private fun selectedRouteKind(
    routes: List<CallAudioManager.AudioRoute>,
    selectedRouteId: String,
): CallAudioManager.RouteKind =
    routes.firstOrNull { it.id == selectedRouteId }?.kind ?: CallAudioManager.RouteKind.EARPIECE

