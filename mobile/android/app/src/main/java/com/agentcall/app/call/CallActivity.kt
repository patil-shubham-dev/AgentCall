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
import androidx.compose.material.icons.automirrored.filled.VolumeDown
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentcall.app.ui.composables.AmbientBackground
import com.agentcall.app.ui.theme.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import java.util.UUID

@AndroidEntryPoint
class CallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val callId = intent.getStringExtra("call_id") ?: run { finish(); return }
        val isOutgoing = intent.getBooleanExtra("outgoing", false)
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
                    isOutgoing = isOutgoing,
                    onEndCall = {
                        startService(Intent(this@CallActivity, CallService::class.java).apply {
                            action = CallService.ACTION_END_CALL
                        })
                        finish()
                    },
                    onCancelCall = {
                        startService(Intent(this@CallActivity, CallService::class.java).apply {
                            action = CallService.ACTION_CANCEL_CALL
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
    onEndCall: () -> Unit,
    onCancelCall: () -> Unit = onEndCall,
    contextSummary: String? = null,
    agentName: String = "AI Agent",
    isOutgoing: Boolean = false,
    viewModel: CallViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var showContext by remember { mutableStateOf(true) }
    var textInput by remember { mutableStateOf("") }
    var optionsPicked by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(callId) {
        viewModel.connect(callId, contextSummary, agentName, isOutgoing)
        while (true) { delay(250); viewModel.tick() }
    }

    // Outgoing: ringback until the first confirmation (call_answered /
    // ai_message), then hand off to the real voice session.
    var ringbackPlayer by remember { mutableStateOf<android.media.MediaPlayer?>(null) }
    var ringbackStarted by remember { mutableStateOf(false) }

    fun startRingback() {
        if (ringbackStarted) return
        ringbackStarted = true
        val player = try {
            android.media.MediaPlayer().apply {
                setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(context, android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE))
                isLooping = true
                setOnErrorListener { _, _, _ -> true }
            }
        } catch (e: Exception) {
            null
        }
        ringbackPlayer = player
        if (player != null) {
            try {
                player.prepare()
                player.start()
            } catch (_: Exception) {
                player.release()
                ringbackPlayer = null
            }
        }
    }

    fun stopRingback(ringback: android.media.MediaPlayer?) {
        try {
            ringback?.stop()
            ringback?.release()
        } catch (_: Exception) {
        }
        ringbackPlayer = null
        ringbackStarted = false
    }

    LaunchedEffect(state.phase) {
        if (state.phase == CallPhase.OUTGOING) {
            startRingback()
        } else if (state.phase == CallPhase.ACTIVE && isOutgoing) {
            stopRingback(ringbackPlayer)
            // The agent picked up: start the real session (ANSWER + greeting).
            context.startService(Intent(context, CallService::class.java).apply {
                action = CallService.ACTION_START_CALL
                putExtra(CallService.EXTRA_CALL_ID, state.callId)
                putExtra(CallService.EXTRA_CALLER_NAME, state.agentName)
                putExtra(CallService.EXTRA_CONTEXT_SUMMARY, state.callContext.summary)
            })
        }
    }

    DisposableEffect(Unit) {
        onDispose { stopRingback(ringbackPlayer) }
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
            accentColor = Indigo400,
            secondaryColor = Indigo600,
            speedMultiplier = 1f,
            density = if (state.isAiSpeaking || state.isRecording) 1.2f else 0.6f,
        )

        Column(modifier = Modifier.fillMaxSize()) {
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
                }
                Text(
                    if (state.phase == CallPhase.ACTIVE) {
                        timerText
                    } else {
                        when (state.phase) {
                            CallPhase.OUTGOING -> "Ringing…"
                            CallPhase.CONNECTING -> "Connecting…"
                            else -> timerText
                        }
                    },
                    fontSize = 18.sp, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Light, color = Slate50, letterSpacing = 1.sp)
            }

            // Reconnecting banner: the session stays live while the link heals.
            AnimatedVisibility(visible = state.phase == CallPhase.RECONNECTING,
                enter = slideInVertically(animationSpec = spring(dampingRatio = 0.8f)) + fadeIn(),
                exit = slideOutVertically() + fadeOut()) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp), color = Amber400.copy(alpha = 0.12f),
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Amber400))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Reconnecting — the call stays live",
                            style = MaterialTheme.typography.labelMedium, color = Amber300,
                        )
                    }
                }
            }

            AnimatedVisibility(visible = state.aiResponding == false,
                enter = slideInVertically(animationSpec = spring(dampingRatio = 0.8f)) + fadeIn(),
                exit = slideOutVertically() + fadeOut()) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp), color = Amber400.copy(alpha = 0.12f),
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Amber400))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            if (state.agentOnline == false) {
                                "Agent is offline — your reply will be saved when they reconnect"
                            } else {
                                "AI is not currently responding — your reply will be saved"
                            },
                            style = MaterialTheme.typography.labelMedium, color = Amber300,
                        )
                    }
                }
            }

            AnimatedVisibility(visible = showContext && state.callContext.summary.isNotBlank(),
                enter = slideInVertically(animationSpec = spring(dampingRatio = 0.8f)) + fadeIn(),
                exit = slideOutVertically() + fadeOut()) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(16.dp), color = Indigo900.copy(alpha = 0.6f),
                    tonalElevation = 4.dp
                ) {
                    Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Default.Info, "AI calling about", modifier = Modifier.size(18.dp), tint = Indigo300)
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text("AI is calling about:",
                                style = MaterialTheme.typography.labelSmall, color = Indigo300,
                                fontWeight = FontWeight.SemiBold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(state.callContext.summary,
                                style = MaterialTheme.typography.bodyMedium, color = Slate200)
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
                            shape = RoundedCornerShape(12.dp), color = Amber400.copy(alpha = 0.12f),
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

            // Text Input + Controls
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Slate900.copy(alpha = 0.95f),
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
                                containerColor = Indigo600,
                                contentColor = Slate50,
                            ),
                            enabled = textInput.isNotBlank(),
                        ) {
                            Icon(Icons.Default.Send, "Send")
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

                    // Control buttons row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CallControl(
                            icon = if (state.isRecording) Icons.Default.StopCircle else Icons.Default.Mic,
                            label = if (state.isRecording) "Stop" else "Record",
                            tint = if (state.isRecording) Red400 else Slate50,
                            bgColor = if (state.isRecording) GlassRed else GlassWhite,
                            onClick = {
                                context.startService(Intent(context, CallService::class.java).apply {
                                    action = if (state.isRecording) CallService.ACTION_STOP_RECORDING
                                    else CallService.ACTION_START_RECORDING
                                })
                                viewModel.setRecording(!state.isRecording)
                            })

                        CallControl(
                            icon = if (state.isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                            label = if (state.isMuted) "Unmute" else "Mute",
                            tint = if (state.isMuted) Indigo400 else Slate50,
                            bgColor = if (state.isMuted) GlassIndigo else GlassWhite,
                            onClick = { viewModel.setMuted(context, !state.isMuted) })

                        CallControl(
                            icon = if (state.isSpeakerOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeDown,
                            label = "Speaker",
                            tint = if (state.isSpeakerOn) Indigo400 else Slate50,
                            bgColor = if (state.isSpeakerOn) GlassIndigo else GlassWhite,
                            onClick = { viewModel.toggleSpeaker() })

                        CallControl(
                            icon = Icons.Default.Replay, label = "Repeat",
                            tint = Slate50, bgColor = GlassWhite, onClick = {
                                context.startService(Intent(context, CallService::class.java).apply {
                                    action = CallService.ACTION_REPEAT_LAST
                                })
                            })
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // End Call Button
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        var isPressed by remember { mutableStateOf(false) }
                        val pressScale by animateFloatAsState(
                            targetValue = if (isPressed) 0.92f else 1f,
                            animationSpec = spring(dampingRatio = 0.5f, stiffness = 800f), label = "endCallPress"
                        )
                        Button(
                            onClick = {
                                if (isOutgoing && state.phase != CallPhase.ACTIVE) {
                                    // Outgoing calls can be cancelled while ringing.
                                    onCancelCall()
                                } else {
                                    onEndCall()
                                }
                            },
                            modifier = Modifier.size(56.dp).scale(pressScale), shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(containerColor = Red500),
                            contentPadding = PaddingValues(0.dp),
                            interactionSource = remember { MutableInteractionSource() },
                        ) {
                            Icon(
                                if (isOutgoing && state.phase != CallPhase.ACTIVE)
                                    Icons.Default.PhoneDisabled
                                else Icons.AutoMirrored.Filled.PhoneForwarded,
                                if (isOutgoing && state.phase != CallPhase.ACTIVE) "Cancel" else "End",
                                modifier = Modifier.size(24.dp), tint = Slate50,
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            if (isOutgoing && state.phase != CallPhase.ACTIVE) "Cancel" else "End",
                            style = MaterialTheme.typography.labelSmall, color = Red400, fontWeight = FontWeight.Medium,
                        )
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
                color = Indigo900.copy(alpha = 0.55f),
                border = BorderStroke(1.dp, Indigo400.copy(alpha = 0.45f)),
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
        ) {
            if (isAi) {
                val avatarGradient = Brush.linearGradient(listOf(GradientBrandStart, GradientBrandEnd))
                Surface(
                    modifier = Modifier.size(32.dp),
                    shape = CircleShape,
                    color = Color.Transparent,
                ) {
                    Box(
                        modifier = Modifier.size(28.dp).clip(CircleShape).background(avatarGradient),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.SmartToy, "AI avatar", modifier = Modifier.size(16.dp), tint = Slate50)
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
            }

            Surface(
                shape = RoundedCornerShape(
                    topStart = if (isAi) 4.dp else 16.dp,
                    topEnd = if (isAi) 16.dp else 4.dp,
                    bottomStart = 16.dp, bottomEnd = 16.dp,
                ),
                color = if (isAi) Slate800 else Indigo800,
                tonalElevation = if (isAi) 0.dp else 2.dp,
            ) {
                Column(modifier = Modifier.padding(12.dp).widthIn(max = 280.dp)) {
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
        val avatarGradient = Brush.linearGradient(listOf(GradientBrandStart, GradientBrandEnd))
        Surface(modifier = Modifier.size(28.dp), shape = CircleShape, color = Color.Transparent) {
            Box(modifier = Modifier.size(28.dp).clip(CircleShape).background(avatarGradient),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Default.SmartToy, "AI", modifier = Modifier.size(14.dp), tint = Slate50)
            }
        }
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

@Composable
private fun CallControl(
    icon: ImageVector, label: String, tint: Color, bgColor: Color,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.88f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 800f), label = "callControlPress"
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(
            onClick = onClick, shape = CircleShape, color = bgColor,
            tonalElevation = 0.dp, interactionSource = interactionSource,
        ) {
            Box(modifier = Modifier.size(48.dp).scale(pressScale), contentAlignment = Alignment.Center) {
                Icon(icon, label, modifier = Modifier.size(22.dp), tint = tint)
            }
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = Slate400)
    }
}