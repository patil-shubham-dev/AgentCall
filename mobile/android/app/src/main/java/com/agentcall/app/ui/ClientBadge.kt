package com.agentcall.app.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * Which MCP client requested the call, rendered as a small "via ..." pill.
 * Known harnesses get their own icon + friendly name; anything else gets a
 * generic fallback so the badge never renders blank or broken. Pass null to
 * hide the badge entirely (calls from older clients / polled rings).
 */
@Composable
fun ClientBadge(
    clientInfoName: String?,
    modifier: Modifier = Modifier,
) {
    val friendly = friendlyClientName(clientInfoName) ?: return
    val icon = clientIcon(clientInfoName)
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                "via $friendly",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

private fun friendlyClientName(name: String?): String? {
    if (name.isNullOrBlank()) return null
    val lower = name.lowercase()
    return when {
        "chatgpt" in lower || "openai" in lower -> "ChatGPT"
        "claude" in lower -> "Claude"
        "opencode" in lower -> "OpenCode"
        "cursor" in lower -> "Cursor"
        else -> "AI harness"
    }
}

private fun clientIcon(name: String?): ImageVector {
    val lower = name?.lowercase() ?: ""
    return when {
        "chatgpt" in lower || "openai" in lower -> Icons.Default.SmartToy
        "claude" in lower -> Icons.Default.AutoAwesome
        "opencode" in lower -> Icons.Default.Terminal
        "cursor" in lower -> Icons.Default.Terminal
        else -> Icons.Default.Computer
    }
}
