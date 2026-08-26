package com.agentcall.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import com.agentcall.app.call.CallService
import com.agentcall.app.call.CallStateHolder
import com.agentcall.app.call.CallStatus
import com.agentcall.app.call.CallActivity
import com.agentcall.app.call.SignalingClient
import com.agentcall.app.call.SignalingForegroundService
import com.agentcall.app.home.HomeScreen
import com.agentcall.app.profile.ProfileDetailScreen
import com.agentcall.app.settings.SettingsScreen
import com.agentcall.app.settings.BatteryOptimizationScreen
import com.agentcall.app.ui.theme.*
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var signalingClient: SignalingClient
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

        requestNotificationPermission()

        pendingProfileId = intent.getStringExtra("profile_id")

        setContent {
            AgentCallTheme {
                MainApp(
                    initialProfileId = pendingProfileId,
                    onCallClicked = { callId ->
                        val intent = Intent(this@MainActivity, CallActivity::class.java).apply {
                            putExtra("call_id", callId)
                        }
                        startActivity(intent)
                    }
                )
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
        // Restore the websocket a previous onStop parked (or the socket a
        // ring/answer re-established): the app being visible means the
        // signaling path should be live again.
        signalingClient.connectIfIdle()
    }

    override fun onStop() {
        super.onStop()
        // Backlog item 14 — idle self-stop: FCM is the ring-wake path, so an
        // app in the background no longer needs an always-on websocket. Park
        // and let the FGS decide whether it can stop itself entirely (it
        // stays alive when a ring is in flight, a call is active, or the app
        // is still visible elsewhere). The FGS-side guards make the race
        // where a push arrives between park() and this notification safe: a
        // validating push keeps the service alive until it rings or fails.
        val state = CallStateHolder.state.value
        if (!ForegroundTracker.isForeground &&
            !CallService.hasActiveCall &&
            state.status != CallStatus.RINGING
        ) {
            signalingClient.park()
            SignalingForegroundService.notifyIdlePark(this)
        }
    }

    private var pendingProfileId: String? by mutableStateOf(null)
}

private data class NavItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val navItems = listOf(
    NavItem("home", "Home", Icons.Default.Home),
    NavItem("settings", "Settings", Icons.Default.Settings),
)

@Composable
fun MainApp(
    initialProfileId: String? = null,
    onCallClicked: (String) -> Unit = {},
) {
    val navController = rememberNavController()
    var selectedIndex by remember { mutableIntStateOf(0) }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Missed-call notification deep link: open the agent's profile once the
    // NavHost is ready (backlog item 1).
    LaunchedEffect(initialProfileId) {
        val id = initialProfileId
        if (id != null) {
            navController.navigate("profile/$id")
        }
    }

    val showBottomBar = currentRoute in listOf("home", "settings")

    LaunchedEffect(currentRoute) {
        selectedIndex = navItems.indexOfFirst { it.route == currentRoute }.coerceAtLeast(0)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showBottomBar) {
                Surface(
                    modifier = Modifier.fillMaxWidth()
                        .border(1.dp, Slate700, RectangleShape),
                    color = Slate850.copy(alpha = 0.95f),
                    tonalElevation = 0.dp,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        navItems.forEachIndexed { index, item ->
                            val isSelected = selectedIndex == index
                            val selectedColor = if (MaterialTheme.extendedColors.isDark) Phosphor else Indigo600
                            val unselectedColor = Slate400

                            Surface(
                                onClick = {
                                    selectedIndex = index
                                    navController.navigate(item.route) {
                                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                shape = RoundedCornerShape(4.dp),
                                color = if (isSelected) Slate750 else Color.Transparent,
                                border = if (isSelected) BorderStroke(1.dp, Slate600) else null,
                                tonalElevation = 0.dp,
                            ) {
                                Row(
                                    modifier = Modifier.padding(
                                        horizontal = if (isSelected) 16.dp else 12.dp,
                                        vertical = 10.dp,
                                    ),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = item.icon,
                                        contentDescription = item.label,
                                        modifier = Modifier.size(24.dp),
                                        tint = if (isSelected) selectedColor else unselectedColor,
                                    )
                                    if (isSelected) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = item.label.uppercase(),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = selectedColor,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
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
                SettingsScreen(onOpenBatteryHelp = { navController.navigate("battery") })
            }
            composable("battery") {
                BatteryOptimizationScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
