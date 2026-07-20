package com.agentcall.app.auth

import android.os.Build
import android.os.Bundle
import android.transition.Fade
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Apple
import androidx.compose.material.icons.filled.Code
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentcall.app.ui.theme.*
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class AuthActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(
                ComponentActivity.OVERRIDE_TRANSITION_OPEN,
                Fade().apply { duration = 400 },
                Fade().apply { duration = 300 }
            )
        }

        enableEdgeToEdge()
        setContent {
            AgentCallTheme {
                AuthScreen(
                    onLoginSuccess = {
                        setResult(RESULT_OK)
                        finish()
                    }
                )
            }
        }
    }
}

@Composable
fun AuthScreen(
    onLoginSuccess: () -> Unit,
    viewModel: AuthViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.isLoggedIn) {
        if (state.isLoggedIn) onLoginSuccess()
    }

    if (state.isLoggedIn) {
        onLoginSuccess()
        return
    }

    var pulseAnim by remember { mutableFloatStateOf(0f) }
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    pulseAnim = infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    ).value

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Slate900),
    ) {
        // Background gradient orbs
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                color = Indigo500.copy(alpha = 0.12f),
                radius = size.width * 0.7f,
                center = Offset(size.width * 0.3f, size.height * 0.15f),
            )
            drawCircle(
                color = Color(0xFF8B5CF6).copy(alpha = 0.08f),
                radius = size.width * 0.5f,
                center = Offset(size.width * 0.8f, size.height * 0.3f),
            )
            drawCircle(
                color = Indigo400.copy(alpha = 0.06f),
                radius = size.width * 0.9f,
                center = Offset(size.width * 0.5f, size.height * 0.6f),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.weight(0.18f))

            // ── Brand Logo / Icon ──────────────────────
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(GradientBrandStart, GradientBrandEnd),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {Icon(
                        imageVector = Icons.Default.AccountCircle,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = Slate50,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "AgentCall",
                style = MaterialTheme.typography.headlineLarge,
                color = Slate50,
                fontWeight = FontWeight.Bold,
            )

            Text(
                text = "Your AI agents call you.\nYou stay in control.",
                style = MaterialTheme.typography.bodyLarge,
                color = Slate400,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp, start = 32.dp, end = 32.dp),
            )

            Spacer(modifier = Modifier.weight(0.12f))

            // ── Login Card ────────────────────────────
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = Slate800.copy(alpha = 0.7f),
                tonalElevation = 0.dp,
                shadowElevation = 8.dp,
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "Get Started",
                        style = MaterialTheme.typography.titleLarge,
                        color = Slate50,
                    )

                    Text(
                        text = "Connect your account to enable\nAI agent calling",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Slate400,
                        textAlign = TextAlign.Center,
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Google OAuth
                    OAuthButton(
                        icon = Icons.Default.AccountCircle,
                        label = "Continue with Google",
                        iconTint = Color(0xFF4285F4),
                        onClick = { viewModel.login("google") },
                    )

                    // GitHub OAuth
                    OAuthButton(
                        icon = Icons.Default.Code,
                        label = "Continue with GitHub",
                        iconTint = Slate50,
                        onClick = { viewModel.login("github") },
                    )

                    // Apple OAuth
                    OAuthButton(
                        icon = Icons.Default.Apple,
                        label = "Continue with Apple",
                        iconTint = Slate50,
                        onClick = { viewModel.login("apple") },
                    )

                    if (state.error != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = GlassRed,
                        ) {
                            Text(
                                text = state.error!!,
                                color = Red400,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(0.25f))

            // ── Footer ────────────────────────────────
            Text(
                text = "By continuing, you agree to our\nTerms of Service and Privacy Policy",
                style = MaterialTheme.typography.labelSmall,
                color = Slate500,
                textAlign = TextAlign.Center,
                lineHeight = 18.sp,
            )
        }
    }
}

@Composable
private fun OAuthButton(
    icon: ImageVector,
    label: String,
    iconTint: Color,
    onClick: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (pressed) Slate700 else Slate750,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = iconTint,
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = Slate200,
            )
        }
    }
}
