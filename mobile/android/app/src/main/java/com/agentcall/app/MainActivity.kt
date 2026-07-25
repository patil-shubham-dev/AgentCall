package com.agentcall.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.transition.Fade
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.agentcall.app.call.CallActivity
import com.agentcall.app.home.HomeScreen
import com.agentcall.app.settings.SettingsScreen
import com.agentcall.app.ui.theme.*
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(
                ComponentActivity.OVERRIDE_TRANSITION_OPEN,
                Fade().apply { duration = 300 },
                Fade().apply { duration = 200 }
            )
        }

        setContent {
            AgentCallTheme {
                MainApp(
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
fun MainApp(onCallClicked: (String) -> Unit = {}) {
    val navController = rememberNavController()
    var selectedIndex by remember { mutableIntStateOf(0) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                tonalElevation = 0.dp,
                shadowElevation = 8.dp,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    navItems.forEachIndexed { index, item ->
                        val isSelected = selectedIndex == index
                        val selectedColor = if (MaterialTheme.extendedColors.isDark) Indigo400 else Indigo600
                        val unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant
                        val selectedBg = if (MaterialTheme.extendedColors.isDark) GlassIndigo else Color(0x146366F1)

                        Surface(
                            onClick = {
                                selectedIndex = index
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.startDestinationId) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            shape = RoundedCornerShape(16.dp),
                            color = if (isSelected) selectedBg else Color.Transparent,
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
                                    modifier = Modifier.size(22.dp),
                                    tint = if (isSelected) selectedColor else unselectedColor,
                                )
                                if (isSelected) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = item.label,
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
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(innerPadding),
        ) {
            composable("home") {
                HomeScreen(onCallClicked = onCallClicked)
            }
            composable("settings") { SettingsScreen() }
        }
    }
}
