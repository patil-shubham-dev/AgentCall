package com.agentcall.app.settings

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentcall.app.data.api.TokenManager
import com.agentcall.app.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val tokenManager: TokenManager
) : ViewModel() {
    private val _deviceId = MutableStateFlow(tokenManager.deviceId ?: "Not registered")
    val deviceId: StateFlow<String> = _deviceId.asStateFlow()

    fun logout() { tokenManager.clear() }
}

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val deviceId by viewModel.deviceId.collectAsStateWithLifecycle()
    var dndEnabled by remember { mutableStateOf(false) }
    var storeTranscripts by remember { mutableStateOf(false) }
    var incomingCallsEnabled by remember { mutableStateOf(true) }
    var taskCompletionsEnabled by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Slate900)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineMedium,
            color = Slate50,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        Spacer(modifier = Modifier.height(20.dp))

        SettingsSection(title = "Profile") {
            SettingsCard {
                Surface(
                    onClick = {},
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Transparent,
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(Brush.linearGradient(
                                    colors = listOf(GradientBrandStart, GradientBrandEnd),
                                )),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = null,
                                tint = Slate50,
                                modifier = Modifier.size(28.dp),
                            )
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Your Account",
                                style = MaterialTheme.typography.titleMedium,
                                color = Slate50,
                            )
                            Text(
                                text = deviceId.take(16) + "...",
                                style = MaterialTheme.typography.bodySmall,
                                color = Slate400,
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = Slate600,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }

        SettingsSection(title = "Notifications") {
            SettingsCard {
                SettingsToggle(
                    icon = Icons.Default.Notifications,
                    iconTint = Indigo400,
                    title = "Incoming calls",
                    subtitle = "Get notified when an AI agent calls",
                    checked = incomingCallsEnabled,
                    onCheckedChange = { incomingCallsEnabled = it },
                )
                SettingsDivider()
                SettingsToggle(
                    icon = Icons.Default.TaskAlt,
                    iconTint = Green400,
                    title = "Task completions",
                    subtitle = "Receive updates when tasks finish",
                    checked = taskCompletionsEnabled,
                    onCheckedChange = { taskCompletionsEnabled = it },
                )
            }
        }

        SettingsSection(title = "Do Not Disturb") {
            SettingsCard {
                SettingsToggle(
                    icon = Icons.Default.DoNotDisturb,
                    iconTint = Amber400,
                    title = "Quiet hours",
                    subtitle = "Mute incoming calls during specific hours",
                    checked = dndEnabled,
                    onCheckedChange = { dndEnabled = it },
                )
                AnimatedVisibility(
                    visible = dndEnabled,
                    enter = expandVertically(animationSpec = spring(dampingRatio = 0.7f)) + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    Column {
                        SettingsDivider()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Schedule,
                                contentDescription = null,
                                tint = Slate500,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "22:00 — 07:00",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Slate300,
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            Surface(
                                onClick = {},
                                shape = RoundedCornerShape(8.dp),
                                color = GlassIndigo,
                            ) {
                                Text(
                                    text = "Edit",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Indigo400,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        SettingsSection(title = "Privacy") {
            SettingsCard {
                SettingsToggle(
                    icon = Icons.Default.Description,
                    iconTint = Slate400,
                    title = "Store transcripts",
                    subtitle = "Keep call transcripts for review",
                    checked = storeTranscripts,
                    onCheckedChange = { storeTranscripts = it },
                )
                SettingsDivider()
                ClickableRow(
                    icon = Icons.Default.DeleteForever,
                    iconTint = Red400,
                    title = "Clear history",
                    subtitle = "Remove all call records",
                )
            }
        }

        SettingsSection(title = "Connected Agents") {
            SettingsCard {
                AgentRow(name = "OpenCode", status = "Active", statusColor = Green500)
                SettingsDivider()
                AgentRow(name = "Claude Code", status = "Active", statusColor = Green500)
                SettingsDivider()
                Surface(
                    onClick = {},
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Transparent,
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(GlassIndigo),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                tint = Indigo400,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Text(
                            text = "Add Agent",
                            style = MaterialTheme.typography.titleMedium,
                            color = Indigo400,
                        )
                    }
                }
            }
        }

        SettingsSection(title = "About") {
            SettingsCard {
                ClickableRow(
                    icon = Icons.Default.Info,
                    iconTint = Slate400,
                    title = "Version",
                    subtitle = "1.0.0",
                )
                SettingsDivider()
                ClickableRow(
                    icon = Icons.Default.Description,
                    iconTint = Slate400,
                    title = "Terms of Service",
                )
                SettingsDivider()
                ClickableRow(
                    icon = Icons.Default.Shield,
                    iconTint = Slate400,
                    title = "Privacy Policy",
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        var signOutPressed by remember { mutableStateOf(false) }
        val signOutScale by animateFloatAsState(
            targetValue = if (signOutPressed) 0.97f else 1f,
            animationSpec = spring(dampingRatio = 0.5f, stiffness = 800f), label = "signOutScale"
        )

        OutlinedButton(
            onClick = { viewModel.logout() },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .scale(signOutScale),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Red400),
            border = ButtonDefaults.outlinedButtonBorder.copy(
                brush = Brush.linearGradient(
                    colors = listOf(Red500.copy(alpha = 0.3f), Red500.copy(alpha = 0.1f)),
                ),
            ),
            interactionSource = remember { MutableInteractionSource() }.also { src ->
                LaunchedEffect(src) {
                    src.collectIsPressedAsState().let { signOutPressed = it.value }
                }
            },
        ) {
            Icon(
                imageVector = Icons.Default.Logout,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Sign Out", fontWeight = FontWeight.Medium)
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = Slate400,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp, top = 16.dp),
        )
        content()
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Slate800,
    ) {
        content()
    }
}

@Composable
private fun SettingsToggle(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        onClick = { onCheckedChange(!checked) },
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(22.dp),
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Slate50,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate400,
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = Indigo600,
                    checkedThumbColor = Indigo400,
                    uncheckedTrackColor = Slate700,
                    uncheckedThumbColor = Slate500,
                ),
            )
        }
    }
}

@Composable
private fun ClickableRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String? = null,
) {
    Surface(
        onClick = {},
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(22.dp),
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Slate50,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = Slate400,
                    )
                }
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = Slate600,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun AgentRow(name: String, status: String, statusColor: Color) {
    Surface(
        onClick = {},
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(GlassIndigo),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.SmartToy,
                    contentDescription = null,
                    tint = Indigo400,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    color = Slate50,
                )
            }
            val dotAnim by rememberInfiniteTransition(label = "agentDot").animateFloat(0f, 1f,
                infiniteRepeatable(tween(1500, easing = EaseInOutSine), RepeatMode.Reverse), label = "agentStatus")
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(statusColor.copy(alpha = 0.5f + dotAnim * 0.5f)),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = status,
                style = MaterialTheme.typography.labelSmall,
                color = statusColor,
            )
        }
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        color = Slate700.copy(alpha = 0.5f),
        modifier = Modifier.padding(start = 52.dp),
    )
}
