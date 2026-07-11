package com.agentcall.app.call

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentcall.app.ui.theme.*
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class IncomingCallActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
                        startCallService(callId, CallService.ACTION_ACCEPT_CALL)
                        finish()
                    },
                    onDecline = {
                        startCallService(callId, CallService.ACTION_END_CALL)
                        finish()
                    },
                    onSnooze = {
                        startCallService(callId, CallService.ACTION_END_CALL)
                        finish()
                    },
                )
            }
        }
    }

    private fun startCallService(callId: String, action: String) {
        val intent = Intent(this, CallService::class.java).apply {
            this.action = action
            putExtra("call_id", callId)
        }
        startService(intent)
    }
}

@Composable
fun IncomingCallScreen(
    callerName: String,
    contextSummary: String,
    priority: String = "normal",
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
    onSnooze: () -> Unit,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "incoming")
    val pulseRing by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pulseRing",
    )

    val pulseDot by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseDot",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Slate900),
    ) {
        // Background glow orbs
        Canvas(modifier = Modifier.fillMaxSize()) {
            val pulseRadius = size.width * (0.15f + pulseRing * 0.1f)
            drawCircle(
                color = GradientBrandStart.copy(alpha = 0.12f * (1f - pulseRing)),
                radius = pulseRadius * 3f,
                center = Offset(size.width / 2, size.height * 0.3f),
            )
            drawCircle(
                color = GradientBrandEnd.copy(alpha = 0.06f),
                radius = size.width * 0.4f,
                center = Offset(size.width * 0.6f, size.height * 0.5f),
            )
            drawCircle(
                color = Indigo500.copy(alpha = 0.04f),
                radius = size.width * 0.6f,
                center = Offset(size.width * 0.3f, size.height * 0.7f),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.weight(0.15f))

            // ── "Incoming AI Call" Chip ─────────────
            Surface(
                shape = RoundedCornerShape(100.dp),
                color = GlassIndigo,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Indigo400.copy(alpha = 0.5f + pulseDot * 0.5f)),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Incoming AI Call",
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp),
                        color = Indigo300,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Spacer(modifier = Modifier.weight(0.08f))

            // ── AI Avatar (pulsing) ─────────────────
            Box(
                contentAlignment = Alignment.Center,
            ) {
                // Outer ring
                Canvas(modifier = Modifier.size(160.dp)) {
                    val ringAlpha = 0.15f * (1f - pulseRing)
                    drawCircle(
                        color = Indigo500.copy(alpha = ringAlpha),
                        radius = size.minDimension / 2,
                    )
                    drawCircle(
                        color = Indigo400.copy(alpha = ringAlpha * 0.5f),
                        radius = size.minDimension * (0.35f + pulseRing * 0.12f),
                    )
                }

                // Avatar circle
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(GradientBrandStart, GradientBrandEnd),
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Call,
                        contentDescription = null,
                        modifier = Modifier.size(52.dp),
                        tint = Slate50,
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Caller Info ─────────────────────────
            Text(
                text = callerName,
                style = MaterialTheme.typography.headlineSmall,
                color = Slate50,
                fontWeight = FontWeight.Bold,
            )

            Spacer(modifier = Modifier.height(12.dp))

            // ── Priority Badge ──────────────────────
            val (priorityLabel, priorityColor) = when (priority) {
                "urgent" -> "URGENT" to Red400
                "high" -> "HIGH" to Amber400
                "normal" -> "NORMAL" to Indigo400
                "low" -> "LOW" to Slate500
                else -> priority.uppercase() to Slate500
            }
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = priorityColor.copy(alpha = 0.15f),
            ) {
                Text(
                    text = priorityLabel,
                    style = MaterialTheme.typography.labelMedium.copy(
                        letterSpacing = 1.5.sp,
                        fontSize = 11.sp,
                    ),
                    color = priorityColor,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Context Card ────────────────────────
            if (contextSummary.isNotBlank()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(0.85f),
                    shape = RoundedCornerShape(16.dp),
                    color = Slate800.copy(alpha = 0.7f),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            modifier = Modifier
                                .size(18.dp)
                                .offset(y = 2.dp),
                            tint = Slate400,
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = contextSummary,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Slate300,
                            textAlign = TextAlign.Start,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(0.12f))

            // ── Action Buttons ──────────────────────
            Row(
                horizontalArrangement = Arrangement.spacedBy(40.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Decline
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Surface(
                        onClick = onDecline,
                        shape = CircleShape,
                        color = GlassRed,
                        tonalElevation = 0.dp,
                    ) {
                        Box(
                            modifier = Modifier.size(64.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.PhoneForwarded,
                                contentDescription = "Decline",
                                modifier = Modifier.size(28.dp),
                                tint = Red400,
                            )
                        }
                    }
                    Text(
                        text = "Decline",
                        style = MaterialTheme.typography.labelMedium,
                        color = Red400,
                        fontWeight = FontWeight.Medium,
                    )
                }

                // Answer (prominent)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = onAnswer,
                        modifier = Modifier.size(72.dp),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Green500,
                        ),
                        contentPadding = PaddingValues(0.dp),
                        elevation = ButtonDefaults.buttonElevation(
                            defaultElevation = 12.dp,
                            pressedElevation = 6.dp,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Call,
                            contentDescription = "Answer",
                            modifier = Modifier.size(32.dp),
                            tint = Slate50,
                        )
                    }
                    Text(
                        text = "Answer",
                        style = MaterialTheme.typography.labelLarge,
                        color = Green400,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                // Snooze
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Surface(
                        onClick = onSnooze,
                        shape = CircleShape,
                        color = GlassWhite,
                        tonalElevation = 0.dp,
                    ) {
                        Box(
                            modifier = Modifier.size(64.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.NotificationsOff,
                                contentDescription = "Snooze",
                                modifier = Modifier.size(28.dp),
                                tint = Slate400,
                            )
                        }
                    }
                    Text(
                        text = "Snooze",
                        style = MaterialTheme.typography.labelMedium,
                        color = Slate400,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── "Snooze 5 min" hint ────────────────
            Text(
                text = "Snooze to return the call\nback to the agent queue",
                style = MaterialTheme.typography.labelSmall,
                color = Slate500,
                textAlign = TextAlign.Center,
                lineHeight = 18.sp,
            )

            Spacer(modifier = Modifier.weight(0.05f))
        }
    }
}
