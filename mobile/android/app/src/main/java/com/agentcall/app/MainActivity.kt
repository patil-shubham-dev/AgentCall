package com.agentcall.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.agentcall.app.auth.AuthActivity
import com.agentcall.app.home.HomeScreen
import com.agentcall.app.settings.SettingsScreen
import com.agentcall.app.data.api.TokenManager
import com.agentcall.app.ui.theme.*
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var tokenManager: TokenManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!tokenManager.isLoggedIn) {
            startActivity(android.content.Intent(this, AuthActivity::class.java))
            finish()
            return
        }

        setContent {
            AgentCallTheme {
                MainApp()
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
fun MainApp() {
    val navController = rememberNavController()
    var selectedIndex by remember { mutableIntStateOf(0) }

    Scaffold(
        containerColor = Slate900,
        bottomBar = {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Slate800.copy(alpha = 0.95f),
                tonalElevation = 0.dp,
                shadowElevation = 8.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    navItems.forEachIndexed { index, item ->
                        val isSelected = selectedIndex == index
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
                            color = if (isSelected) GlassIndigo else Color.Transparent,
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
                                    tint = if (isSelected) Indigo400 else Slate500,
                                )
                                if (isSelected) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = item.label,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = Indigo400,
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
                HomeScreen(
                    onCallClicked = { /* navigate to call detail */ },
                )
            }
            composable("settings") { SettingsScreen() }
        }
    }
}
