package com.agentcall.app.profile

import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PhoneMissed
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Quickreply
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentcall.app.call.OutgoingCallLauncher
import com.agentcall.app.data.database.entity.AiProfileEntity
import com.agentcall.app.data.database.entity.CallRecordEntity
import com.agentcall.app.settings.MessageTemplates
import com.agentcall.app.ui.composables.AmbientBackground
import com.agentcall.app.ui.composables.GradientAvatar
import com.agentcall.app.ui.theme.Amber400
import com.agentcall.app.ui.theme.GlassWhite
import com.agentcall.app.ui.theme.GradientBrandEnd
import com.agentcall.app.ui.theme.GradientBrandStart
import com.agentcall.app.ui.theme.Green400
import com.agentcall.app.ui.theme.Green500
import com.agentcall.app.ui.theme.Indigo100
import com.agentcall.app.ui.theme.Indigo200
import com.agentcall.app.ui.theme.Indigo300
import com.agentcall.app.ui.theme.Indigo400
import com.agentcall.app.ui.theme.Indigo600
import com.agentcall.app.ui.theme.Indigo900
import com.agentcall.app.ui.theme.Red400
import com.agentcall.app.ui.theme.Slate200
import com.agentcall.app.ui.theme.Slate400
import com.agentcall.app.ui.theme.Slate50
import com.agentcall.app.ui.theme.Slate700
import com.agentcall.app.ui.theme.Slate750
import com.agentcall.app.ui.theme.Slate800
import com.agentcall.app.ui.theme.Slate900
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileDetailScreen(
    profileId: String,
    onBack: () -> Unit,
    viewModel: ProfileDetailViewModel = hiltViewModel(),
) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val calls by viewModel.calls.collectAsStateWithLifecycle()
    var showEditSheet by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(profile?.name ?: "Profile", color = Slate50) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Slate50)
                    }
                },
                actions = {
                    IconButton(onClick = { showEditSheet = true }) {
                        Icon(Icons.Default.Edit, "Edit name", tint = Indigo400)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        ) {
            AmbientBackground(
                accentColor = Indigo400,
                secondaryColor = GradientBrandEnd,
                speedMultiplier = 0.8f,
            )

            val p = profile
            if (p != null) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                        ProfileDetailHeader(
                            name = p.name,
                            callCount = p.callCount,
                            lastCalledAt = p.lastCalledAt,
                            onCall = {
                                // Outgoing call: fresh call id, ringback until the agent picks up.
                                OutgoingCallLauncher.launch(context, p.name)
                            },
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        // Per-agent customization: ringtone (item 7) + quick
                        // replies (item 9).
                        ProfileCustomizationCard(profile = p, viewModel = viewModel)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "Call History",
                            style = MaterialTheme.typography.titleSmall,
                            color = Slate200,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.5.sp,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    if (calls.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "No calls yet",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Slate400,
                                )
                            }
                        }
                    }

                    items(calls, key = { it.callId }) { call ->
                        CallHistoryItem(call = call, viewModel = viewModel)
                    }

                    item { Spacer(modifier = Modifier.height(16.dp)) }
                }
            }
        }
    }

    if (showEditSheet && profile != null) {
        EditNameSheet(
            currentName = profile!!.name,
            onSave = { newName ->
                viewModel.renameProfile(newName)
                showEditSheet = false
            },
            onDismiss = { showEditSheet = false },
        )
    }
}

@Composable
private fun ProfileDetailHeader(name: String, callCount: Int, lastCalledAt: Long?, onCall: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        GradientAvatar(size = 100.dp)
        Spacer(modifier = Modifier.height(12.dp))
        Text(name, style = MaterialTheme.typography.headlineSmall, color = Slate50, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(4.dp))
        val lastCall = lastCalledAt?.let { formatRelativeTime(it) } ?: "Never"
        Text(
            "$callCount calls · Last: $lastCall",
            style = MaterialTheme.typography.bodyMedium,
            color = Slate400,
        )
        Spacer(modifier = Modifier.height(16.dp))
        // Primary call entry: outgoing call with ringback + cancel.
        Surface(
            onClick = onCall,
            shape = RoundedCornerShape(28.dp),
            color = Color.Transparent,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(28.dp))
                    .background(Brush.horizontalGradient(listOf(GradientBrandStart, GradientBrandEnd)))
                    .padding(horizontal = 28.dp, vertical = 12.dp),
            ) {
                Icon(
                    Icons.Default.Call,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = Slate50,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Call",
                    style = MaterialTheme.typography.titleMedium,
                    color = Slate50,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun ProfileCustomizationCard(profile: AiProfileEntity, viewModel: ProfileDetailViewModel) {
    val context = LocalContext.current
    var showQuickReplies by remember { mutableStateOf(false) }

    val ringtonePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) {
            result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        }
        val label = uri?.let { RingtoneManager.getRingtone(context, it)?.getTitle(context) }
        viewModel.setRingtone(uri?.toString(), label)
    }

    val quickReplyChips = viewModel.parseQuickReplies(profile.quickReplies)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = Slate800.copy(alpha = 0.6f),
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            ProfileRow(
                icon = Icons.Default.MusicNote,
                title = "Ringtone",
                subtitle = profile.ringtoneLabel ?: "Default ringtone",
                onClick = {
                    val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                        putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE)
                        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                        profile.ringtoneUri?.let {
                            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(it))
                        }
                    }
                    ringtonePicker.launch(intent)
                },
            )
            ProfileDivider()
            ProfileRow(
                icon = Icons.Default.Quickreply,
                title = "Quick replies",
                subtitle = if (quickReplyChips.isEmpty()) {
                    "None — uses AI options"
                } else {
                    quickReplyChips.joinToString(" · ") { it.trim() }
                },
                onClick = { showQuickReplies = true },
            )
        }
    }

    if (showQuickReplies) {
        QuickRepliesSheet(
            chips = quickReplyChips,
            onSave = { viewModel.setQuickReplies(it) },
            onDismiss = { showQuickReplies = false },
        )
    }
}

@Composable
private fun ProfileRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(34.dp).clip(CircleShape).background(GlassWhite),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = Indigo400, modifier = Modifier.size(18.dp))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, color = Slate50, fontWeight = FontWeight.Medium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Slate400, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            @Suppress("DEPRECATION")
            Icon(Icons.Default.ChevronRight, "Edit $title", tint = Slate400, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun ProfileDivider() {
    androidx.compose.material3.HorizontalDivider(
        color = Slate700.copy(alpha = 0.4f),
        modifier = Modifier.padding(start = 62.dp),
    )
}

@Composable
private fun QuickRepliesSheet(
    chips: List<String>,
    onSave: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(chips) }
    var input by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Quick replies", color = Slate50) },
        text = {
            Column {
                Text(
                    "Shown as chips during calls with this agent (max 4). Leave empty to use the AI's options.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate400,
                )
                Spacer(modifier = Modifier.height(12.dp))
                draft.forEachIndexed { index, chip ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = Indigo900.copy(alpha = 0.55f),
                        ) {
                            Text(
                                chip,
                                style = MaterialTheme.typography.labelMedium,
                                color = Indigo100,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        IconButton(
                            onClick = { draft = draft.filterIndexed { i, _ -> i != index } },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(Icons.Default.Close, "Remove", tint = Slate400, modifier = Modifier.size(16.dp))
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text("New reply", color = Slate400) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Slate50,
                            unfocusedTextColor = Slate50,
                            cursorColor = Indigo400,
                            focusedBorderColor = Indigo600,
                            unfocusedBorderColor = Slate700,
                            focusedContainerColor = Slate800,
                            unfocusedContainerColor = Slate800,
                        ),
                        shape = RoundedCornerShape(10.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    IconButton(
                        onClick = {
                            val clean = input.trim()
                            if (clean.isNotBlank() && draft.size < 4) {
                                draft = draft + clean
                                input = ""
                            }
                        },
                        enabled = input.isNotBlank() && draft.size < 4,
                    ) {
                        Icon(Icons.Default.Add, "Add reply", tint = Indigo400)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(draft); onDismiss() }) {
                Text("Save", color = Indigo400)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Slate400) }
        },
    )
}

@Composable
fun CallHistoryItem(call: CallRecordEntity, viewModel: ProfileDetailViewModel) {
    var expanded by remember { mutableStateOf(false) }
    val transcript by viewModel.getTranscriptForCall(call.callId).collectAsState(initial = emptyList())
    val context = LocalContext.current

    // Backlog item 1: an expired ring is a "Missed" call — red-amber, and it
    // carries a Call-back affordance that reuses the outgoing-call flow.
    val statusColor = when (call.status) {
        "ended" -> Green500
        "cancelled" -> Red400
        "expired" -> Amber400
        "ringing" -> Indigo400
        else -> Slate400
    }
    val statusIcon = when (call.status) {
        "ended" -> Icons.Default.CheckCircle
        "cancelled" -> Icons.Default.Cancel
        "expired" -> Icons.AutoMirrored.Filled.PhoneMissed
        "ringing" -> Icons.Default.Call
        else -> Icons.Default.Schedule
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = Slate800.copy(alpha = 0.6f),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    statusIcon, "Status: ${call.status}",
                    modifier = Modifier.size(20.dp), tint = statusColor,
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        formatCallDate(call.startedAt),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Slate50,
                    )
                    Text(
                        "${formatDuration(call.durationSeconds)} · ${statusLabel(call.status)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Slate400,
                    )
                    // Backlog item 3: the AI-generated recap, when one exists.
                    if (call.summary.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            call.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = Slate200,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (call.status == "expired") {
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        onClick = {
                            OutgoingCallLauncher.launch(
                                context,
                                call.callerName,
                                MessageTemplates.callbackPrompt(context),
                            )
                        },
                        shape = RoundedCornerShape(12.dp),
                        color = Indigo600.copy(alpha = 0.25f),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Call, null, modifier = Modifier.size(13.dp), tint = Indigo300)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                "Call back",
                                style = MaterialTheme.typography.labelSmall,
                                color = Indigo200,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    "Toggle transcript",
                    tint = Slate400,
                    modifier = Modifier.size(20.dp),
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = fadeOut(),
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(bottom = 14.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = Slate750,
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "Transcript",
                            style = MaterialTheme.typography.labelSmall,
                            color = Indigo400,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        if (transcript.isEmpty()) {
                            Text(
                                "No transcript available",
                                style = MaterialTheme.typography.bodySmall,
                                color = Slate400,
                            )
                        } else {
                            transcript.forEach { msg ->
                                val prefix = if (msg.role == "ai") "AI" else "You"
                                Text(
                                    "$prefix: ${msg.content}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (msg.role == "ai") Slate200 else Slate50,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatCallDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("MMM dd · h:mm a", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

private fun formatDuration(seconds: Int): String {
    val min = seconds / 60
    val sec = seconds % 60
    return "${min}m ${sec}s"
}

private fun statusLabel(status: String): String = when (status) {
    "ended" -> "Completed"
    "cancelled" -> "Cancelled"
    "expired" -> "Missed"
    "ringing" -> "Ringing"
    "started" -> "In progress"
    else -> status
}

private fun formatRelativeTime(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    val minutes = diff / 60000
    val hours = minutes / 60
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        else -> "${hours / 24}d ago"
    }
}
