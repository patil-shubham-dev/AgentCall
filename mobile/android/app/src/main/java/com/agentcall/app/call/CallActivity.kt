package com.agentcall.app.call

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.agentcall.app.ui.theme.AgentCallTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Activity host for the ongoing-call stage.
 *
 * The stage itself lives in [ActiveCallScreen]; this class only owns the
 * window contract (edge-to-edge, keep-screen-on, mic permission) and the
 * CallService intents. Incoming calls that were answered in-app render the
 * same stage from [IncomingCallActivity].
 *
 * The activity is singleInstance, so a second call delivered through the
 * ongoing notification lands in [onNewIntent] instead of a new activity. The
 * call identity is Compose state so the screen's `LaunchedEffect(callId)`
 * resets and reconnects for the new call.
 */
@AndroidEntryPoint
class CallActivity : ComponentActivity() {

    private var stageCallId by mutableStateOf("")
    private var stageCallerName by mutableStateOf("AI Agent")
    private var stageSummary by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The stage is near-black edge to edge, so the system bar icons must
        // stay light regardless of the OS light/dark setting.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (!adoptIntent(intent)) {
            finish()
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
        }

        setContent {
            AgentCallTheme(darkTheme = true) {
                val callId = stageCallId
                if (callId.isNotBlank()) {
                    ActiveCallScreen(
                        callId = callId,
                        context = this@CallActivity,
                        contextSummary = stageSummary,
                        agentName = stageCallerName,
                        onEndCall = { cid ->
                            startService(Intent(this@CallActivity, CallService::class.java).apply {
                                action = CallService.ACTION_END_CALL
                                putExtra(CallService.EXTRA_CALL_ID, cid)
                            })
                        },
                        onDismiss = { finish() },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // The same call re-delivered (notification tapped again while the
        // stage is up): keep the current stage, including its draft and
        // transcript. A different call id changes the state above, which
        // reconnects the screen and resets the view model for the new call.
        if (callIdOf(intent) == stageCallId) return
        adoptIntent(intent)
    }

    /** Returns false when the intent carries no call id. */
    private fun adoptIntent(intent: Intent?): Boolean {
        val callId = callIdOf(intent) ?: return false
        stageCallId = callId
        stageCallerName = intent?.getStringExtra(CallService.EXTRA_CALLER_NAME)
            ?: intent?.getStringExtra("caller_name")
            ?: "AI Agent"
        stageSummary = intent?.getStringExtra(CallService.EXTRA_CONTEXT_SUMMARY)
        return true
    }

    private fun callIdOf(intent: Intent?): String? =
        intent?.getStringExtra(CallService.EXTRA_CALL_ID)
            ?: intent?.getStringExtra("call_id")
}
