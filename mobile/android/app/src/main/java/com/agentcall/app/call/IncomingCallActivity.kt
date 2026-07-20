package com.agentcall.app.call

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.transition.Fade
import android.transition.Slide
import android.view.Gravity
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentcall.app.data.api.ApiClient
import com.agentcall.app.data.api.ApiService
import com.agentcall.app.ui.theme.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class IncomingCallActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(
                ComponentActivity.OVERRIDE_TRANSITION_OPEN,
                Fade().apply { duration = 400 },
                Slide(Gravity.BOTTOM).apply { duration = 300 }
            )
        }

        val callId = intent.getStringExtra("call_id") ?: return
        val callerName = intent.getStringExtra("caller_name") ?: "AI Agent"
        val contextSummary = intent.getStringExtra("context_summary") ?: ""
        val priority = intent.getStringExtra("priority") ?: "normal"

        setContent {
            AgentCallTheme {
                IncomingCallScreen(
                    callerName = callerName,
                    contextSummary = contextSummary,
                    priority = priority,
                    onAnswer = {
                        val intent = Intent(this, CallActivity::class.java).apply {
                            putExtra("call_id", callId)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        }
                        startActivity(intent)

                        val svcIntent = Intent(this, CallService::class.java).apply {
                            action = CallService.ACTION_START_CALL
                            putExtra(CallService.EXTRA_CALL_ID, callId)
                        }
                        startService(svcIntent)
                        finish()
                    },
                    onDecline = { finish() },
                    onLater = { minutes ->
                        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
                        scope.launch {
                            try {
                                val api: ApiService = ApiClient.create()
                                api.scheduleCallback(callId, mapOf("delay_minutes" to minutes))
                            } catch (_: Exception) {}
                        }
                        finish()
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IncomingCallScreen(
    callerName: String,
    contextSummary: String,
    priority: String = "normal",
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
    onLater: (Int) -> Unit,
) {
    var showLaterPicker by remember { mutableStateOf(false) }
    var selectedMinutes by remember { mutableIntStateOf(10) }

    val laterOptions = listOf(5 to "5 min", 10 to "10 min", 15 to "15 min", 30 to "30 min", 60 to "1 hour")

    val infiniteTransition = rememberInfiniteTransition(label = "incoming")
    val pulseRing by infiniteTransition.animateFloat(0f, 1f,
        infiniteRepeatable(tween(2000, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "pulseRing")
    val pulseDot by infiniteTransition.animateFloat(0f, 1f,
        infiniteRepeatable(tween(2000, easing = EaseInOutSine), RepeatMode.Reverse), label = "pulseDot")

    val orbAnim by infiniteTransition.animateFloat(0f, 1f,
        infiniteRepeatable(tween(8000, easing = LinearEasing), RepeatMode.Restart), label = "orbDrift")
    val avatarGlow by infiniteTransition.animateFloat(0f, 1f,
        infiniteRepeatable(tween(3000, easing = EaseInOutSine), RepeatMode.Reverse), label = "avatarGlow")

    Box(modifier = Modifier.fillMaxSize().background(Slate900)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2
            val cy = size.height * 0.3f
            drawCircle(GradientBrandStart.copy(alpha = 0.12f * (1f - pulseRing)),
                size.width * (0.15f + pulseRing * 0.1f) * 3f, Offset(cx, cy))
            val orbOffsetX = sin(orbAnim * 2 * Math.PI.toFloat()) * 20f
            drawCircle(GradientBrandEnd.copy(alpha = 0.06f), size.width * 0.4f,
                Offset(size.width * 0.6f + orbOffsetX, size.height * 0.5f))
            drawCircle(Indigo500.copy(alpha = 0.04f), size.width * 0.6f,
                Offset(size.width * 0.3f - orbOffsetX, size.height * 0.7f))
        }

        Column(modifier = Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(modifier = Modifier.weight(0.12f))

            Surface(shape = RoundedCornerShape(100.dp), color = GlassIndigo) {
                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape)
                        .background(Indigo400.copy(alpha = 0.5f + pulseDot * 0.5f)))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Incoming AI Call",
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp),
                        color = Indigo300, fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(modifier = Modifier.weight(0.08f))

            Box(
                modifier = Modifier.size(160.dp).scale(0.97f + pulseRing * 0.03f),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.size(160.dp)) {
                    val maxRadius = size.minDimension / 2
                    drawCircle(Indigo500.copy(alpha = 0.15f * (1f - pulseRing)), maxRadius)
                    drawCircle(Indigo400.copy(alpha = 0.15f * (1f - pulseRing) * 0.5f),
                        maxRadius * (0.35f + pulseRing * 0.12f))
                }
                Box(modifier = Modifier.size(100.dp).clip(CircleShape)
                    .background(Brush.linearGradient(listOf(GradientBrandStart, GradientBrandEnd))),
                    contentAlignment = Alignment.Center) {
                    Canvas(modifier = Modifier.size(100.dp)) {
                        val sweepAngle = avatarGlow * 60f
                        drawArc(
                            color = Color.White.copy(alpha = 0.15f),
                            startAngle = -30f + avatarGlow * 360f,
                            sweepAngle = sweepAngle,
                            useCenter = false,
                            style = Stroke(width = 3f),
                            topLeft = Offset(8f, 8f),
                            size = androidx.compose.ui.geometry.Size(size.width - 16f, size.height - 16f),
                        )
                    }
                    Icon(Icons.Default.Call, null, modifier = Modifier.size(52.dp), tint = Slate50)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            Text(callerName, style = MaterialTheme.typography.headlineSmall, color = Slate50, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))

            val (priorityLabel, priorityColor) = when (priority) {
                "urgent" -> "URGENT" to Red400
                "high" -> "HIGH" to Amber400
                "normal" -> "NORMAL" to Indigo400
                "low" -> "LOW" to Slate500
                else -> priority.uppercase() to Slate500
            }
            Surface(shape = RoundedCornerShape(6.dp), color = priorityColor.copy(alpha = 0.15f)) {
                Text(priorityLabel,
                    style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.5.sp, fontSize = 11.sp),
                    color = priorityColor, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (contextSummary.isNotBlank()) {
                Surface(modifier = Modifier.fillMaxWidth(0.85f), shape = RoundedCornerShape(16.dp),
                    color = Slate800.copy(alpha = 0.7f)) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Default.Info, null, modifier = Modifier.size(18.dp), tint = Slate400)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(contextSummary, style = MaterialTheme.typography.bodyMedium,
                            color = Slate300, textAlign = TextAlign.Start)
                    }
                }
            }

            Spacer(modifier = Modifier.weight(0.1f))

            if (showLaterPicker) {
                Surface(
                    modifier = Modifier.fillMaxWidth(0.85f),
                    shape = RoundedCornerShape(20.dp), color = Slate800,
                ) {
                    Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Call back in...",
                            style = MaterialTheme.typography.titleMedium, color = Slate200,
                            fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(16.dp))
                        laterOptions.forEach { (mins, label) ->
                            val isSelected = selectedMinutes == mins
                            Surface(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                onClick = { selectedMinutes = mins },
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) Indigo800 else Slate700,
                            ) {
                                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = { selectedMinutes = mins },
                                        colors = RadioButtonDefaults.colors(selectedColor = Indigo400),
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(label, style = MaterialTheme.typography.bodyLarge, color = Slate200)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { onLater(selectedMinutes) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Indigo600),
                        ) {
                            Text("Call me back in $selectedMinutes min")
                        }
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
                    PressableActionButton(
                        icon = Icons.Default.PhoneForwarded,
                        label = "Decline",
                        iconTint = Red400,
                        labelColor = Red400,
                        bgColor = GlassRed,
                        size = 64.dp,
                        onClick = onDecline,
                    )

                    PressableActionButton(
                        icon = Icons.Default.Call,
                        label = "Answer",
                        iconTint = Slate50,
                        labelColor = Green400,
                        bgColor = Color.Transparent,
                        size = 72.dp,
                        onClick = onAnswer,
                        isSolid = true,
                    )

                    PressableActionButton(
                        icon = Icons.Default.Schedule,
                        label = "Later",
                        iconTint = Indigo300,
                        labelColor = Indigo300,
                        bgColor = GlassWhite,
                        size = 64.dp,
                        onClick = { showLaterPicker = true },
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            if (!showLaterPicker) {
                Text("Answer, decline, or schedule for later",
                    style = MaterialTheme.typography.labelSmall, color = Slate500, textAlign = TextAlign.Center)
            }
            Spacer(modifier = Modifier.weight(0.05f))
        }
    }
}

@Composable
private fun PressableActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    iconTint: Color,
    labelColor: Color,
    bgColor: Color,
    size: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    isSolid: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 800f), label = "pressActionScale"
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (isSolid) {
            Button(
                onClick = onClick,
                modifier = Modifier.size(size).scale(pressScale),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = Green500),
                contentPadding = PaddingValues(0.dp),
                interactionSource = interactionSource,
            ) {
                Icon(icon, label, modifier = Modifier.size(size * 0.44f), tint = iconTint)
            }
        } else {
            Surface(
                onClick = onClick,
                shape = CircleShape,
                color = bgColor,
                tonalElevation = 0.dp,
                interactionSource = interactionSource,
            ) {
                Box(modifier = Modifier.size(size).scale(pressScale), contentAlignment = Alignment.Center) {
                    Icon(icon, label, modifier = Modifier.size(size * 0.44f), tint = iconTint)
                }
            }
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = labelColor, fontWeight = FontWeight.Medium)
    }
}
