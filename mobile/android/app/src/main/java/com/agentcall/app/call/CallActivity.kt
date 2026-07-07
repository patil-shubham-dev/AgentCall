package com.agentcall.app.call

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.agentcall.app.ui.theme.AgentCallTheme
import kotlinx.coroutines.delay

class CallActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val callId = intent.getStringExtra("call_id") ?: run {
            finish(); return
        }

        setContent {
            AgentCallTheme {
                val context = this@CallActivity
                ActiveCallScreen(
                    callId = callId,
                    context = context,
                    onEndCall = {
                        val intent = Intent(this@CallActivity, CallService::class.java).apply {
                            action = CallService.ACTION_END_CALL
                        }
                        this@CallActivity.startService(intent)
                        finish()
                    }
                )
            }
        }
    }
}

@Composable
fun ActiveCallScreen(
    callId: String,
    context: Context,
    onEndCall: () -> Unit
) {
    var isMuted by remember { mutableStateOf(false) }
    var isSpeakerOn by remember { mutableStateOf(false) }
    var elapsedSeconds by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            elapsedSeconds++
        }
    }

    val minutes = elapsedSeconds / 60
    val seconds = elapsedSeconds % 60
    val timerText = "%02d:%02d".format(minutes, seconds)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                text = "AI Agent",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )

            Text(
                text = timerText,
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(32.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(32.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconToggleButton(
                    checked = isMuted,
                    onCheckedChange = {
                        isMuted = !isMuted
                        val intent = Intent(context, CallService::class.java).apply {
                            action = CallService.ACTION_MUTE
                        }
                        context.startService(intent)
                    },
                    modifier = Modifier.size(64.dp)
                ) {
                    Icon(
                        if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = if (isMuted) "Unmute" else "Mute",
                        modifier = Modifier.size(32.dp)
                    )
                }

                IconToggleButton(
                    checked = isSpeakerOn,
                    onCheckedChange = { isSpeakerOn = !isSpeakerOn },
                    modifier = Modifier.size(64.dp)
                ) {
                    Icon(
                        Icons.Default.VolumeUp,
                        contentDescription = "Speaker",
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = onEndCall,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                ),
                modifier = Modifier
                    .size(80.dp)
                    .padding(8.dp)
            ) {
                Text("End", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
