package com.agentcall.app.call

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.agentcall.app.R
import com.agentcall.app.ui.theme.AgentCallTheme

class IncomingCallActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val callId = intent.getStringExtra("call_id") ?: return
        val callerName = intent.getStringExtra("caller_name") ?: "AI Agent"
        val contextSummary = intent.getStringExtra("context_summary") ?: ""

        setContent {
            AgentCallTheme {
                IncomingCallScreen(
                    callerName = callerName,
                    contextSummary = contextSummary,
                    onAnswer = {
                        startCallService(callId, CallService.ACTION_ACCEPT_CALL)
                        finish()
                    },
                    onDecline = {
                        startCallService(callId, CallService.ACTION_END_CALL)
                        finish()
                    }
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
    onAnswer: () -> Unit,
    onDecline: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                text = "Incoming AI Call",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = callerName,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )

            if (contextSummary.isNotBlank()) {
                Text(
                    text = contextSummary,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(32.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onDecline,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.size(80.dp)
                ) {
                    Text("Decline", style = MaterialTheme.typography.labelMedium)
                }

                Button(
                    onClick = onAnswer,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.size(80.dp)
                ) {
                    Text("Answer", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
