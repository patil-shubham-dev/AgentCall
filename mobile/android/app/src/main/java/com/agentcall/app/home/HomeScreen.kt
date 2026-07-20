package com.agentcall.app.home

import android.content.Intent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentcall.app.call.IncomingCallActivity
import com.agentcall.app.ui.theme.*
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel(),
    onCallClicked: (String) -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(state.incomingCallId) {
        state.incomingCallId?.let { callId ->
            val intent = Intent(context, IncomingCallActivity::class.java).apply {
                putExtra("call_id", callId)
                putExtra("caller_name", "AI Agent")
                putExtra("context_summary", state.incomingSummary ?: "")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            viewModel.clearIncoming()
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "homePulse")
    val waitingPulse by infiniteTransition.animateFloat(0f, 1f,
        infiniteRepeatable(tween(2000, easing = EaseInOutSine), RepeatMode.Reverse), label = "waitingPulse")
    val waitingScale by infiniteTransition.animateFloat(1f, 1.06f,
        infiniteRepeatable(tween(2500, easing = EaseInOutSine), RepeatMode.Reverse), label = "waitingScale")

    Box(
        modifier = Modifier.fillMaxSize().background(Slate900),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val pulseAlpha = 0.03f + waitingPulse * 0.04f
            drawCircle(
                color = Indigo500.copy(alpha = pulseAlpha),
                radius = size.width * (0.5f + waitingPulse * 0.15f),
                center = Offset(size.width * 0.8f, 0f),
            )
        }

        Column(modifier = Modifier.fillMaxSize()) {
            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "VoiceBridge",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Slate50,
                    )
                    Text(
                        text = state.statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = Slate400,
                    )
                }
                val dotColor = if (state.isConnected) Green500 else Amber400
                Surface(
                    shape = RoundedCornerShape(100.dp),
                    color = if (state.isConnected) GlassGreen else GlassAmber,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier.size(8.dp).clip(CircleShape).background(dotColor.copy(alpha = 0.5f + waitingPulse * 0.5f)),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (state.isConnected) "Online" else "Offline",
                            style = MaterialTheme.typography.labelSmall,
                            color = dotColor,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            AnimatedVisibility(
                visible = state.activeCallId != null,
                enter = slideInVertically(animationSpec = spring(dampingRatio = 0.7f)) + fadeIn(),
                exit = slideOutVertically() + fadeOut(),
            ) {
                state.activeCallId?.let { callId ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                            .clickable { onCallClicked(callId) },
                        shape = RoundedCornerShape(16.dp),
                        color = Slate800,
                        tonalElevation = 4.dp,
                        shadowElevation = 8.dp,
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val activeDotAnim by infiniteTransition.animateFloat(0f, 1f,
                                infiniteRepeatable(tween(1000, easing = EaseInOutSine), RepeatMode.Reverse), label = "activeDot")
                            Canvas(modifier = Modifier.size(40.dp)) {
                                drawCircle(
                                    GradientBrandStart.copy(alpha = 0.15f + activeDotAnim * 0.1f),
                                    size.minDimension / 2
                                )
                                drawCircle(Green500.copy(
                                    alpha = 0.8f + activeDotAnim * 0.2f
                                ), size.minDimension * 0.25f)
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Active Call — AI Agent",
                                    style = MaterialTheme.typography.titleSmall, color = Slate50)
                                Text("Tap to open",
                                    style = MaterialTheme.typography.labelSmall, color = Green400)
                            }
                            Icon(Icons.Default.ChevronRight, null, tint = Slate400)
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Column(
                modifier = Modifier.fillMaxWidth().padding(48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier.size(80.dp).scale(waitingScale).clip(CircleShape)
                        .background(Brush.linearGradient(listOf(
                            Indigo500.copy(alpha = 0.1f + waitingPulse * 0.1f),
                            Indigo700.copy(alpha = 0.05f),
                        ))),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.PhoneCallback, null,
                        modifier = Modifier.size(36.dp), tint = Indigo400.copy(alpha = 0.7f + waitingPulse * 0.3f),
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
                Text("Connected", style = MaterialTheme.typography.titleLarge, color = Slate50)
                Text(
                    "Waiting for AI to call...\nYour phone will ring when an agent needs you",
                    style = MaterialTheme.typography.bodyMedium, color = Slate400,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp),
                )
            }

            Spacer(modifier = Modifier.weight(1f))
        }
    }
}
