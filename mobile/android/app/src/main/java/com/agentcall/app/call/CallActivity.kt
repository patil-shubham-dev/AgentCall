package com.agentcall.app.call

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PhoneForwarded
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
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
import com.agentcall.app.ui.composables.ActionCircle
import com.agentcall.app.ui.composables.AmbientBackground
import com.agentcall.app.ui.composables.GradientAvatar
import com.agentcall.app.ui.composables.Notice
import com.agentcall.app.ui.ClientBadge
import com.agentcall.app.ui.theme.*
import dagger.hilt.android.AndroidEntryPoint
import androidx.compose.ui.platform.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.UUID

@AndroidEntryPoint
class CallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val callId = intent.getStringExtra("call_id") ?: run { finish(); return }
        val callerName = intent.getStringExtra("caller_name") ?: "AI Agent"
        // Voice-first: keep the screen lit for the duration of the call.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
        }

        setContent {
            AgentCallTheme(darkTheme = true) {
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
                        finish()
                    })
            }
        }
    }
}

@Composable
fun ActiveCallScreen(
    callId: String,
    context: Context,
    onEndCall: (String) -> Unit,
    contextSummary: String? = null,
    agentName: String = "AI Agent",
    viewModel: CallViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var showContext by remember { mutableStateOf(true) }
    var textInput by remember { mutableStateOf("") }
    var optionsPicked by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(callId) {
        viewModel.connect(callId, contextSummary, agentName)
    }

    // Battery audit M2: the tick is a per-second UI clock, not a busy loop.
    // repeatOnLifecycle(STARTED) cancels it whenever the screen stops
    // (covered/backgrounded) instead of spinning blind, 1 s is enough for a
    // second-resolution timer (was 250 ms), and the phase check ends the loop
    // for good at ENDED — previously a finished call kept ticking for as long
    // as the composition survived. connect() stays in its own one-shot effect
    // so a START->STOP->START cycle never re-runs session setup.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(callId, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive && viewModel.uiState.value.phase != CallPhase.ENDED) {
                delay(1_000)
                viewModel.tick()
            }
        }
    }

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.size - 1)
        }
    }

    LaunchedEffect(state.isConnected) {
        if (state.isConnected) {
            delay(4000)
            showContext = false
        }
    }

    val minutes = state.elapsedSeconds / 60
    val seconds = state.elapsedSeconds % 60
    val timerText = "%02d:%02d".format(minutes, seconds)

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")

    Box(modifier = Modifier.fillMaxSize().background(Slate900)) {
        AmbientBackground(
            accentColor = MaterialTheme.colorScheme.primary,
            secondaryColor = MaterialTheme.colorScheme.surfaceVariant,
            speedMultiplier = 1f,
            density = if (state.isAiSpeaking || state.isRecording) 1.2f else 0.6f,
        )

        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val dotAnim by infiniteTransition.animateFloat(0f, 1f,
                    infiniteRepeatable(tween(1200, easing = EaseInOutSine), RepeatMode.Reverse), label = "statusDot")
                Box(modifier = Modifier.size(8.dp).clip(CircleShape)
                    .background(if (state.isConnected) Green500.copy(alpha = 0.5f + dotAnim * 0.5f) else Amber400))
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    // Voice-first: the agent's name is the identity on screen.
                    Text(agentName, style = MaterialTheme.typography.titleMedium, color = Slate50,
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(state.statusText, style = MaterialTheme.typography.labelSmall,
                        color = if (state.isConnected) Green500 else Amber400)
                    // Which MCP client requested the call (ChatGPT, Claude...).
                    state.callContext.clientInfoName?.let { name ->
                        Spacer(modifier = Modifier.height(2.dp))
                        ClientBadge(clientInfoName = name)
                    }
                }
                Text(
                    if (state.phase == CallPhase.ACTIVE) {
                        timerText
                    } else {
                        when (state.phase) {
                            CallPhase.CONNECTING -> "Connecting..."
                            else -> timerText
                        }
                    },
                    style = MonoTitle, color = Slate50)
            }

            // Reconnecting banner: opaque so AmbientBackground orbs don't bleed
            AnimatedVisibility(visible = state.phase == CallPhase.RECONNECTING,
                enter = slideInVertically(animationSpec = spring(dampingRatio = 0.8f)) + fadeIn(),
                exit = slideOutVertically() + fadeOut()) {
                Notice(
                    text = "Reconnecting — the call stays live",
                    lampColor = Amber400,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            // Informational banners (agent offline / not responding): opaque surface
            AnimatedVisibility(visible = state.aiResponding == false,
                enter = slideInVertically(animationSpec = spring(dampingRatio = 0.8f)) + fadeIn(),
                exit = slideOutVertically() + fadeOut()) {
                Notice(
                    text = if (state.agentOnline == false) {
                        "Agent is offline — your reply will be saved when they reconnect"
                    } else {
                        "AI is not currently responding — your reply will be saved"
                    },
                    lampColor = Slate400,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            AnimatedVisibility(visible = showContext && state.callContext.summary.isNotBlank(),
                enter = slideInVertically(animationSpec = spring(dampingRatio = 0.8f)) + fadeIn(),
                exit = slideOutVertically() + fadeOut()) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    tonalElevation = 0.dp
                ) {
                    Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Default.Info, "AI calling about", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text("AI is calling about:",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.SemiBold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(state.callContext.summary,
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }

            // Transcript Messages
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(state.messages, key = { it.id }) { msg ->
                    MessageBubble(
                        msg = msg,
                        isAi = msg.role == "ai",
                        onRetry = if (msg.role == "user" && msg.failed) {
                            { viewModel.retryUserText(context, msg.id, msg.text) }
                        } else null,
                    )
                }

                if (state.isAiSpeaking && state.messages.isNotEmpty()) {
                    item(key = "typing") {
                        TypingIndicator()
                    }
                }

                if (state.isPaused) {
                    item(key = "paused") {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        ) {
                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.PauseCircle, "Call paused", tint = Amber400,
                                    modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Call paused — press Record when you're ready",
                                    style = MaterialTheme.typography.labelMedium, color = Amber300)
                            }
                        }
                    }
                }
            }

            // Waveform
            WaveformBar(
                levels = state.waveformLevels,
                isActive = state.isRecording || state.isAiSpeaking,
                isRecording = state.isRecording,
            )

            // Text Input + Controls — imePadding keeps transcript visible above keyboard
            Surface(
                modifier = Modifier.fillMaxWidth().imePadding(),
                color = MaterialTheme.colorScheme.background,
                tonalElevation = 0.dp,
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    // Text input row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val sendDraft = {
                            if (textInput.isNotBlank()) {
                                val messageId = UUID.randomUUID().toString()
                                val msg = textInput.trim()
                                viewModel.sendTextMessage(msg, messageId)
                                textInput = ""
                                focusManager.clearFocus()
                                context.startService(Intent(context, CallService::class.java).apply {
                                    action = CallService.ACTION_SEND_TEXT
                                    putExtra(CallService.EXTRA_CALL_ID, state.callId)
                                    putExtra(CallService.EXTRA_TEXT, msg)
                                    putExtra(CallService.EXTRA_MESSAGE_ID, messageId)
                                })
                            }
                        }
                        OutlinedTextField(
                            value = textInput,
                            onValueChange = { textInput = it },
                            modifier = Modifier
                                .weight(1f)
                                .onPreviewKeyEvent { event ->
                                    // ENTER sends the draft (standard chat behavior);
                                    // Shift+Enter still inserts a newline.
                                    if (event.type == KeyEventType.KeyDown
                                        && event.key == Key.Enter
                                        && !event.isShiftPressed
                                    ) {
                                        sendDraft()
                                        true
                                    } else {
                                        false
                                    }
                                },
                            placeholder = { Text("Type your answer...", color = Slate400) },
                            singleLine = false,
                            maxLines = 2,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Indigo400,
                                unfocusedBorderColor = Slate700,
                                cursorColor = Indigo400,
                                focusedTextColor = Slate50,
                                unfocusedTextColor = Slate50,
                                focusedContainerColor = Slate800,
                                unfocusedContainerColor = Slate800,
                            ),
                            shape = RoundedCornerShape(12.dp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(
                                onSend = { sendDraft() }
                            ),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        FilledIconButton(
                            onClick = { sendDraft() },
                            modifier = Modifier.size(48.dp),
                            shape = CircleShape,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = BrandPurple,
                                contentColor = Color.White,
                                disabledContainerColor = Slate700,
                                disabledContentColor = Slate500,
                            ),
                            enabled = textInput.isNotBlank(),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, "Send")
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Quick-reply chips offered by the AI at call creation
                    if (state.callContext.options.isNotEmpty() && !optionsPicked) {
                        QuickReplyChips(
                            options = state.callContext.options,
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
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    // 5-inline control row — icon-only per design system; Mute uses crossed speaker (VolumeOff) not MicOff
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        InlineControl(modifier = Modifier.weight(1f), icon = if (state.isRecording) Icons.Default.StopCircle else Icons.Default.Mic, contentDescription = if (state.isRecording) "Stop recording" else "Start recording", active = state.isRecording, onClick = { context.startService(Intent(context, CallService::class.java).apply { action = if (state.isRecording) CallService.ACTION_STOP_RECORDING else CallService.ACTION_START_RECORDING }); viewModel.setRecording(!state.isRecording) })
                        InlineControl(modifier = Modifier.weight(1f), icon = Icons.AutoMirrored.Filled.VolumeOff, contentDescription = if (state.isMuted) "Unmute AI voice" else "Mute AI voice", active = state.isMuted, onClick = { viewModel.setMuted(context, !state.isMuted) })
                        InlineControl(modifier = Modifier.weight(1f), icon = if (state.isSpeakerOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeDown, contentDescription = if (state.isSpeakerOn) "Speaker on" else "Speaker off", active = state.isSpeakerOn, onClick = { viewModel.toggleSpeaker() })
                        InlineControl(modifier = Modifier.weight(1f), icon = Icons.Default.Replay, contentDescription = "Repeat last message", active = false, onClick = { context.startService(Intent(context, CallService::class.java).apply { action = CallService.ACTION_REPEAT_LAST }) })
                        Surface(onClick = { val cid = state.callId.ifBlank { callId }; onEndCall(cid) }, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(Radii.Field), color = Error, border = androidx.compose.foundation.BorderStroke(6.dp, ErrorBg)) { Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.AutoMirrored.Filled.PhoneForwarded, contentDescription = "End call", tint = Color.White, modifier = Modifier.size(20.dp)) } }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickReplyChips(
    options: List<String>,
    onPick: (String) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            Surface(
                onClick = { onPick(option) },
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Icon(
                        Icons.Default.Quickreply,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = Indigo300,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        option,
                        style = MaterialTheme.typography.labelMedium,
                        color = Indigo100,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
private fun InlineControl(
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(Radii.Field),
        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        border = if (active) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDescription, tint = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun MessageBubble(msg: ChatBubble, isAi: Boolean, onRetry: (() -> Unit)? = null) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(if (isAi) 50 else 100)
        visible = true
    }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(
            initialOffsetY = { if (isAi) -it / 3 else it / 2 },
            animationSpec = spring(dampingRatio = 0.65f, stiffness = 250f)
        ) + fadeIn(animationSpec = tween(250)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isAi) Arrangement.Start else Arrangement.End,
            verticalAlignment = Alignment.Top,
        ) {
            if (isAi) {
                GradientAvatar(size = 32.dp)
                Spacer(modifier = Modifier.width(8.dp))
            }

            Surface(
                modifier = Modifier.weight(1f, fill = false).widthIn(max = 280.dp),
                shape = RoundedCornerShape(
                    topStart = if (isAi) 4.dp else 16.dp,
                    topEnd = if (isAi) 16.dp else 4.dp,
                    bottomStart = 16.dp, bottomEnd = 16.dp,
                ),
                color = if (isAi) Slate800 else UserBubbleBg,
                border = if (isAi) null else BorderStroke(1.dp, UserBubbleBorder),
                tonalElevation = 0.dp,
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    if (isAi) {
                        Text("AI", style = MaterialTheme.typography.labelSmall,
                            color = Indigo400, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    Text(msg.text, style = MaterialTheme.typography.bodyMedium, color = if (isAi) Slate100 else Slate50)
                }
            }

            if (!isAi) {
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier.size(32.dp).clip(CircleShape).background(Slate700),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Person, "You", modifier = Modifier.size(18.dp), tint = Slate400)
                }
            }
        }

        if (!isAi && msg.failed) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Surface(
                    onClick = onRetry ?: {},
                    shape = RoundedCornerShape(12.dp),
                    color = Amber400.copy(alpha = 0.15f),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Icon(Icons.Default.Warning, "not sent", modifier = Modifier.size(14.dp), tint = Amber400)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Not sent — tap to retry", style = MaterialTheme.typography.labelSmall, color = Amber300)
                    }
                }
            }
        }
    }
}

@Composable
private fun TypingIndicator() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GradientAvatar(size = 28.dp, contentDescription = "AI")
        Spacer(modifier = Modifier.width(8.dp))
        Surface(shape = RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp), color = Slate800) {
            Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                repeat(3) { i ->
                    val dotDelay by rememberInfiniteTransition(label = "dot$i").animateFloat(0f, 1f,
                        infiniteRepeatable(tween(1200, delayMillis = i * 200, easing = EaseInOutSine), RepeatMode.Reverse),
                        label = "dotAnim$i")
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape)
                        .background(Indigo400.copy(alpha = 0.3f + dotDelay * 0.7f)))
                    if (i < 2) Spacer(modifier = Modifier.width(6.dp))
                }
            }
        }
    }
}

@Composable
private fun WaveformBar(levels: List<Float>, isActive: Boolean, isRecording: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "waveformGlow")
    val glowAlpha by infiniteTransition.animateFloat(0.4f, 0.8f,
        infiniteRepeatable(tween(1000, easing = EaseInOutSine), RepeatMode.Reverse), label = "glow")

    val waveformColor = WaveformActive

    Canvas(modifier = Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 20.dp)) {
        val barWidth = size.width / (levels.size * 2 - 1)
        val centerY = size.height / 2
        levels.forEachIndexed { i, level ->
            val barHeight = level * size.height * 0.8f
            val x = i * barWidth * 2
            val baseAlpha = 0.4f + level * 0.6f
            val alpha = if (isActive) (baseAlpha * (0.8f + glowAlpha * 0.2f)).coerceIn(0f, 1f) else baseAlpha * 0.5f
            drawRoundRect(
                color = waveformColor.copy(alpha = alpha),
                topLeft = Offset(x, centerY - barHeight / 2),
                size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2),
            )
        }
    }
}