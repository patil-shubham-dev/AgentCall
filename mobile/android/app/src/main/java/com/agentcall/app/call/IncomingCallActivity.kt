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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentcall.app.data.api.ApiClient
import com.agentcall.app.data.api.ApiService
import com.agentcall.app.ui.composables.AmbientBackground
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

    // Priority colors
    val (priorityLabel, priorityColor, themeAccent, themeGradient) = when (priority) {
        "urgent" -> "URGENT" to Red400 to Red400 to listOf(Red500, Red600)
        "high" -> "HIGH" to Amber400 to Amber400 to listOf(Amber500, Amber600)
        "normal" -> "NORMAL" to Indigo400 to Indigo500 to listOf(Indigo500, GradientBrandEnd)
        "low" -> "LOW" to Slate500 to Slate400 to listOf(Slate500, Slate600)
        else -> priority.uppercase() to Slate500 to Indigo500 to listOf(Indigo500, GradientBrandEnd)
    }

    val infiniteTransition = rememberInfiniteTransition(label = "incoming")

    // Expanding ring pulses (multiple layers)
    val ring1 by infiniteTransition.animateFloat(0f, 1f,
        infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "ring1")
    val ring2 by infiniteTransition.animateFloat(0f, 1f,
        infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing, delayMillis = 600), RepeatMode.Restart), label = "ring2")
    val ring3 by infiniteTransition.animateFloat(0f, 1f,
        infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing, delayMillis = 1200), RepeatMode.Restart), label = "ring3")

    val pulseDot by infiniteTransition.animateFloat(0f, 1f,
        infiniteRepeatable(tween(2000, easing = EaseInOutSine), RepeatMode.Reverse), label = "pulseDot")
    val orbDrift by infiniteTransition.animateFloat(0f, 1f,
        infiniteRepeatable(tween(10000, easing = LinearEasing), RepeatMode.Restart), label = "orbDrift")
    val glowSweep by infiniteTransition.animateFloat(0f, 1f,
        infiniteRepeatable(tween(4000, easing = EaseInOutSine), RepeatMode.Reverse), label = "glowSweep")

    Box(modifier = Modifier.fillMaxSize().background(Slate900)) {
        // Animated ambient background with priority color
        AmbientBackground(
            accentColor = themeAccent,
            secondaryColor = themeGradient.getOrElse(1) { GradientBrandEnd },
            speedMultiplier = if (priority == "urgent") 2f else 1f,
            density = 1.5f,
        )

        Column(modifier = Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(modifier = Modifier.weight(0.12f))

            // ── Incoming Call Badge ────────────────────
            Surface(shape = RoundedCornerShape(100.dp), color = themeAccent.copy(alpha = 0.12f)) {
                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape)
                        .background(themeAccent.copy(alpha = 0.5f + pulseDot * 0.5f)))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Incoming AI Call",
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp),
                        color = themeAccent, fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(modifier = Modifier.weight(0.08f))

            // ── Animated Avatar with Expanding Rings ───
            Box(
                modifier = Modifier.size(170.dp),
                contentAlignment = Alignment.Center,
            ) {
                // Expanding ring 1
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val r = size.minDimension / 2
                    val ringRadius = r * (0.5f + ring1 * 0.5f)
                    drawCircle(
                        color = themeAccent.copy(alpha = 0.12f * (1f - ring1)),
                        radius = ringRadius,
                    )
                }
                // Expanding ring 2
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val r = size.minDimension / 2
                    val ringRadius = r * (0.5f + ring2 * 0.5f)
                    drawCircle(
                        color = themeAccent.copy(alpha = 0.08f * (1f - ring2)),
                        radius = ringRadius,
                    )
                }
                // Expanding ring 3
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val r = size.minDimension / 2
                    val ringRadius = r * (0.5f + ring3 * 0.5f)
                    drawCircle(
                        color = themeAccent.copy(alpha = 0.05f * (1f - ring3)),
                        radius = ringRadius,
                    )
                }

                // Rotating glow sweep arc
                Canvas(modifier = Modifier.size(140.dp)) {
                    val sweep = 30f + glowSweep * 20f
                    val rotation = glowSweep * 360f
                    drawArc(
                        color = themeAccent.copy(alpha = 0.2f),
                        startAngle = rotation,
                        sweepAngle = sweep,
                        useCenter = false,
                        style = Stroke(width = 2.5f),
                        topLeft = Offset(4f, 4f),
                        size = androidx.compose.ui.geometry.Size(size.width - 8f, size.height - 8f),
                    )
                }

                // Main avatar circle with gradient
                Box(modifier = Modifier.size(110.dp).clip(CircleShape)
                    .background(Brush.linearGradient(listOf(themeGradient[0], themeGradient[1]))),
                    contentAlignment = Alignment.Center) {
                    // Inner glow
                    Canvas(modifier = Modifier.size(110.dp)) {
                        drawCircle(
                            color = Color.White.copy(alpha = 0.08f + glowSweep * 0.08f),
                            radius = size.minDimension / 2 * 0.85f,
                        )
                    }
                    Icon(Icons.Default.Call, null, modifier = Modifier.size(54.dp), tint = Slate50)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            Text(callerName, style = MaterialTheme.typography.headlineSmall, color = Slate50, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))

            // ── Priority Badge ─────────────────────────
            Surface(shape = RoundedCornerShape(6.dp), color = priorityColor.copy(alpha = 0.15f)) {
                Text(priorityLabel,
                    style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.5.sp, fontSize = 11.sp),
                    color = priorityColor, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Context Card ───────────────────────────
            if (contextSummary.isNotBlank()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(0.85f),
                    shape = RoundedCornerShape(16.dp),
                    color = Slate800.copy(alpha = 0.7f),
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Default.Info, null, modifier = Modifier.size(18.dp), tint = Slate400)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            contextSummary,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Slate300,
                            textAlign = TextAlign.Start,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(0.1f))

            // ── Action Buttons / Later Picker ──────────
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
                    ActionButton(
                        icon = Icons.Default.PhoneForwarded,
                        label = "Decline",
                        iconTint = Red400,
                        labelColor = Red400,
                        bgColor = GlassRed,
                        size = 64.dp,
                        onClick = onDecline,
                    )

                    ActionButton(
                        icon = Icons.Default.Call,
                        label = "Answer",
                        iconTint = Slate50,
                        labelColor = Green400,
                        bgColor = Color.Transparent,
                        size = 72.dp,
                        onClick = onAnswer,
                        isSolid = true,
                        solidColor = Green500,
                    )

                    ActionButton(
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
private fun ActionButton(
    icon: ImageVector,
    label: String,
    iconTint: Color,
    labelColor: Color,
    bgColor: Color,
    size: Dp,
    onClick: () -> Unit,
    isSolid: Boolean = false,
    solidColor: Color = Green500,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 800f), label = "pressScale"
    )
    val pressElevation by animateDpAsState(
        targetValue = if (isPressed) 4.dp else 8.dp,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 600f), label = "pressElevation"
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (isSolid) {
            Button(
                onClick = onClick,
                modifier = Modifier.size(size).scale(pressScale),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = solidColor),
                contentPadding = PaddingValues(0.dp),
                interactionSource = interactionSource,
                elevation = ButtonDefaults.buttonElevation(defaultElevation = pressElevation),
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
                shadowElevation = pressElevation,
            ) {
                Box(modifier = Modifier.size(size).scale(pressScale), contentAlignment = Alignment.Center) {
                    Icon(icon, label, modifier = Modifier.size(size * 0.44f), tint = iconTint)
                }
            }
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = labelColor, fontWeight = FontWeight.Medium)
    }
}
