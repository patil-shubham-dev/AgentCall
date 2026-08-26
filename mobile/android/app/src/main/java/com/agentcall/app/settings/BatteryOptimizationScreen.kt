package com.agentcall.app.settings

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.ViewModel
import com.agentcall.app.ui.composables.AmbientBackground
import com.agentcall.app.ui.composables.SectionLabel
import com.agentcall.app.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BatteryOptimizationViewModel @Inject constructor(
    val manager: BatteryOptimizationManager,
) : ViewModel()

/**
 * Battery-optimization / autostart onboarding (ColorOS Doze finding). This is
 * the one-time guidance screen: checks the exemption, fires
 * ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, shows OEM-specific instructions
 * with resolveActivity-guarded deep links, and falls back to the app-details
 * page. Every check is logged locally — never sent to the backend.
 */
@Composable
fun BatteryOptimizationScreen(
    viewModel: BatteryOptimizationViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
) {
    val manager = viewModel.manager
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val oem = manager.oemInfo()
    var exempt by remember { mutableStateOf(manager.isExempt(context)) }
    var showLogs by rememberSaveable { mutableStateOf(false) }
    var logs by remember { mutableStateOf(manager.recentLogs()) }

    // Re-check exemption + refresh the local log whenever we return to this
    // screen (the exemption request and OEM settings both navigate away).
    LifecycleResumeEffect(Unit) {
        exempt = manager.isExempt(context)
        logs = manager.recentLogs()
        onPauseOrDispose { }
    }

    val exemptionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        exempt = manager.isExempt(context)
        manager.logExemptionCheck(exempt, "exemption_request")
        logs = manager.recentLogs()
    }

    fun startFallbackAppDetails() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        )
        runCatching { context.startActivity(intent) }
            .onFailure {
                scope.launch { snackbarHostState.showSnackbar("Couldn't open app settings") }
            }
    }

    fun openOemSettings() {
        // Debug hook exercises the forced-fallback path: pretend no OEM deep
        // link resolves so the app-details fallback is verified on-device.
        if (!manager.debugForceDeepLinkFailure) {
            for (component in oem.deepLinkCandidates) {
                val slash = component.indexOf('/')
                if (slash <= 0 || slash >= component.length - 1) continue
                val intent = Intent().setComponent(
                    ComponentName(component.substring(0, slash), component.substring(slash + 1)),
                )
                try {
                    if (intent.resolveActivity(context.packageManager) != null) {
                        manager.logExemptionCheck(null, "opened_${oem.slug}")
                        context.startActivity(intent)
                        return
                    }
                } catch (_: Exception) {
                    // Malformed component or blocked intent — try the next candidate.
                }
            }
        }
        manager.logExemptionCheck(null, "opened_app_details_fallback")
        startFallbackAppDetails()
    }

    fun openDontKillMyApp() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(oem.supportUrl))
        try {
            if (intent.resolveActivity(context.packageManager) != null) {
                manager.logExemptionCheck(null, "opened_dontkillmyapp")
                context.startActivity(intent)
                return
            }
        } catch (_: Exception) {
            // fall through to the snackbar
        }
        scope.launch { snackbarHostState.showSnackbar("No browser available to open ${oem.supportUrl}") }
    }

    fun requestExemption() {
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        )
        exemptionLauncher.launch(intent)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background),
        ) {
            // Header
            Box(modifier = Modifier.height(80.dp)) {
                AmbientBackground(
                    accentColor = Indigo500,
                    density = 0.6f,
                    speedMultiplier = 0.5f,
                    modifier = Modifier.fillMaxSize(),
                )
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Call Reliability",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Text(
                            text = "Keep AgentCall reachable for calls",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                // ── Battery optimization status ──────────
                BatterySection(title = "BATTERY OPTIMIZATION") {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(if (exempt) Green400 else Amber400),
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (exempt) "Exempt from battery optimization" else "Battery optimization is ON",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = if (exempt) {
                                        "AgentCall can wake from a killed state to ring."
                                    } else {
                                        "The system may defer calls while the app is closed."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(
                                imageVector = if (exempt) Icons.Default.CheckCircle else Icons.Default.BatteryAlert,
                                contentDescription = null,
                                tint = if (exempt) Green400 else Amber400,
                                modifier = Modifier.size(22.dp),
                            )
                        }

                        if (!exempt) {
                            Spacer(modifier = Modifier.height(12.dp))
                            HorizontalDivider(color = Slate700.copy(alpha = 0.6f))
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = { requestExemption() },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Indigo600),
                            ) {
                                Icon(Icons.Default.BatteryChargingFull, "Request exemption", modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Request battery exemption")
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Android will ask you to allow AgentCall to run without " +
                                    "battery restrictions.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 16.sp,
                            )
                        }
                    }
                }

                // ── Manufacturer-specific guidance ───────
                BatterySection(title = "YOUR PHONE — ${oem.displayName.uppercase()}") {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = oem.instructions,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            lineHeight = 20.sp,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Button(
                                onClick = { openOemSettings() },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Indigo600),
                            ) {
                                Icon(Icons.Default.Settings, "Open phone settings", modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Open phone settings")
                            }
                            OutlinedButton(
                                onClick = { openDontKillMyApp() },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                                border = null,
                            ) {
                                Icon(Icons.Default.OpenInNew, "Guided instructions", modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Guided")
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Plain-text guide: ${oem.supportUrl}",
                            style = MonoLabel,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
                }

                // ── Local audit log ───────────────────────
                BatterySection(title = "LOCAL LOG") {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Exemption checks — stored on this device only",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { showLogs = !showLogs }) {
                                Icon(
                                    imageVector = if (showLogs) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = if (showLogs) "Collapse log" else "Expand log",
                                    tint = Indigo400,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                        AnimatedVisibility(
                            visible = showLogs,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically(),
                        ) {
                            Column(modifier = Modifier.padding(top = 8.dp)) {
                                if (logs.isEmpty()) {
                                    Text(
                                        text = "No checks yet — run the exemption request to log your first entry.",
                                        style = MonoLabel,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    )
                                } else {
                                    logs.take(20).forEach { entry ->
                                        Text(
                                            text = entry,
                                            style = MonoLabel,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            lineHeight = 16.sp,
                                            modifier = Modifier.padding(vertical = 2.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = {
                        manager.onboardingShown = true
                        onBack()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Indigo600),
                ) {
                    Text("Done", style = MaterialTheme.typography.labelLarge)
                }
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun BatterySection(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        Spacer(modifier = Modifier.height(24.dp))
        SectionLabel(title = title)
        Spacer(modifier = Modifier.height(10.dp))
        GlassCard { content() }
    }
}