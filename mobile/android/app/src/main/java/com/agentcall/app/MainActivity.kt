package com.agentcall.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.agentcall.app.call.CallActivity
import com.agentcall.app.home.HomeScreen
import com.agentcall.app.profile.ProfileDetailScreen
import com.agentcall.app.settings.SettingsScreen
import com.agentcall.app.settings.BatteryOptimizationScreen
import com.agentcall.app.ui.theme.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)

        requestNotificationPermission()

        pendingProfileId = intent.getStringExtra("profile_id")

        setContent {
            AgentCallTheme {
                var showSplash by remember { mutableStateOf(savedInstanceState == null) }

                Box(modifier = Modifier.fillMaxSize()) {
                    MainApp(
                        initialProfileId = pendingProfileId,
                        onCallClicked = { callId ->
                            val intent = Intent(this@MainActivity, CallActivity::class.java).apply {
                                putExtra("call_id", callId)
                            }
                            startActivity(intent)
                        }
                    )

                    if (showSplash) {
                        AgentCallSplash(onFinished = { showSplash = false })
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Missed-call notifications deep-link into a profile (backlog item 1).
        intent.getStringExtra("profile_id")?.let { pendingProfileId = it }
    }

    override fun onResume() {
        super.onResume()
        // FCM-only idle: no persistent WS. onResume no longer restores a
        // socket — the app is fully idle until FCM wakes it or user answers.
    }

    override fun onStop() {
        super.onStop()
        // FCM-only idle: no park needed — there is no idle socket or FGS to
        // park. CallService owns the WS only for the duration of an answered
        // call and tears it down itself.
    }

    private var pendingProfileId: String? by mutableStateOf(null)
}

@Composable
private fun AgentCallSplash(onFinished: () -> Unit) {
    val overlayAlpha = remember { Animatable(1f) }
    val logoAlpha = remember { Animatable(0f) }
    val logoRotation = remember { Animatable(0f) }
    val logoScale = remember { Animatable(0.86f) }

    LaunchedEffect(Unit) {
        launch { logoAlpha.animateTo(1f, tween(durationMillis = 90, easing = LinearOutSlowInEasing)) }
        logoScale.animateTo(0.92f, tween(durationMillis = 90, easing = LinearOutSlowInEasing))

        launch {
            logoRotation.animateTo(
                targetValue = 360f,
                animationSpec = tween(durationMillis = 430, easing = FastOutSlowInEasing),
            )
        }
        logoScale.animateTo(1f, tween(durationMillis = 430, easing = FastOutSlowInEasing))

        logoScale.animateTo(1.035f, tween(durationMillis = 110, easing = LinearOutSlowInEasing))
        logoScale.animateTo(1f, tween(durationMillis = 70, easing = FastOutSlowInEasing))
        logoScale.animateTo(1.08f, tween(durationMillis = 150, easing = LinearOutSlowInEasing))

        launch { logoAlpha.animateTo(0f, tween(durationMillis = 90, easing = FastOutSlowInEasing)) }
        overlayAlpha.animateTo(0f, tween(durationMillis = 90, easing = FastOutSlowInEasing))
        onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = overlayAlpha.value }
            .background(Color(0xFF0E0D11)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.agentcall_splash_logo),
            contentDescription = null,
            modifier = Modifier
                .size(124.dp)
                .graphicsLayer {
                    alpha = logoAlpha.value
                    rotationZ = logoRotation.value
                    scaleX = logoScale.value
                    scaleY = logoScale.value
                },
        )
    }
}

@Composable
fun MainApp(
    initialProfileId: String? = null,
    onCallClicked: (String) -> Unit = {},
) {
    val navController = rememberNavController()

    LaunchedEffect(initialProfileId) {
        val id = initialProfileId
        if (id != null) {
            navController.navigate("profile/$id")
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(innerPadding),
        ) {
            composable("home") {
                HomeScreen(
                    onCallClicked = onCallClicked,
                    onProfileClicked = { profileId ->
                        navController.navigate("profile/$profileId")
                    },
                    onOpenBatteryHelp = { navController.navigate("battery") },
                    onOpenSettings = { navController.navigate("settings") },
                )
            }
            composable(
                route = "profile/{profileId}",
                arguments = listOf(navArgument("profileId") { type = NavType.StringType }),
            ) { backStackEntry ->
                ProfileDetailScreen(
                    profileId = backStackEntry.arguments?.getString("profileId") ?: "",
                    onBack = { navController.popBackStack() },
                )
            }
            composable("settings") {
                SettingsScreen(
                    onOpenBatteryHelp = { navController.navigate("battery") },
                    onBack = { navController.popBackStack() },
                )
            }
            composable("battery") {
                BatteryOptimizationScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
