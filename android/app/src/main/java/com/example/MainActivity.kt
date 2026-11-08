package com.example

import com.example.ui.theme.Appearance
import androidx.compose.runtime.LaunchedEffect
import android.os.Build
import com.example.ui.components.IntroSplash
import com.example.ui.components.GlassParticles
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.bubble.BubbleSandboxScreen
import com.example.ui.components.IvyLogoMark
import com.example.ui.components.IvyWordmark
import com.example.ui.components.glassCard
import com.example.ui.components.performTactileFeedback
import com.example.ui.dictionary.DictionaryScreen
import com.example.ui.dictionary.DictionaryViewModel
import com.example.ui.history.HistoryScreen
import com.example.ui.history.HistoryViewModel
import com.example.ui.home.HomeScreen
import com.example.ui.home.HomeViewModel
import com.example.ui.onboarding.OnboardingScreen
import com.example.ui.onboarding.OnboardingViewModel
import com.example.ui.settings.SettingsScreen
import com.example.ui.settings.SettingsViewModel
import com.example.ui.theme.GlassBorder
import com.example.ui.theme.GlassFill
import com.example.ui.theme.IvyNearBlack
import com.example.ui.theme.IvyOrange
import com.example.ui.theme.IvyOrangeSoft
import com.example.ui.theme.IvyTheme
import com.example.ui.theme.InterFamily
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

class MainActivity : ComponentActivity() {
    companion object {
        /** Set by the bubble's History option; the app switches to that tab and clears it. */
        var requestedTab by androidx.compose.runtime.mutableStateOf<IvyTab?>(null)
    }

    private fun readTab(i: android.content.Intent?) {
        if (i?.getStringExtra("tab") == "history") requestedTab = IvyTab.HISTORY
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        readTab(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readTab(intent)
        enableEdgeToEdge()
        Appearance.load(this)
        setContent {
            IvyTheme {
                IvyApp()
            }
        }
    }
}

enum class IvyTab(val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    HOME("Home", Icons.Default.Home),
    HISTORY("History", Icons.Default.History),
    DICTIONARY("Dictionary", Icons.Default.MenuBook),
    SETTINGS("Settings", Icons.Default.Settings)
}

@Composable
fun IvyApp() {
    var showIntro by remember { mutableStateOf(true) }
    Box(modifier = Modifier.fillMaxSize().background(IvyNearBlack.copy(alpha = Appearance.glassOpacity))) {
        // Same drifting glass motes as the desktop app, behind every screen.
        GlassParticles(modifier = Modifier.fillMaxSize())
        IvyAppContent()
        AnimatedVisibility(visible = showIntro, enter = fadeIn(tween(0)), exit = fadeOut(tween(350))) {
            IntroSplash(onFinished = { showIntro = false })
        }
    }
}

@Composable
private fun IvyAppContent() {
    val context = LocalContext.current

    // ViewModels
    val homeViewModel: HomeViewModel = viewModel()
    val historyViewModel: HistoryViewModel = viewModel()
    val dictionaryViewModel: DictionaryViewModel = viewModel()
    val settingsViewModel: SettingsViewModel = viewModel()
    val onboardingViewModel: OnboardingViewModel = viewModel()

    // Navigation and state
    var currentTab by remember { mutableStateOf(IvyTab.HOME) }
    val wantedTab = MainActivity.requestedTab
    LaunchedEffect(wantedTab) { if (wantedTab != null) { currentTab = wantedTab; MainActivity.requestedTab = null } }
    // Setup runs once: the first time the app opens (or when replayed from Settings / the dev panel).
    val prefs = remember { context.getSharedPreferences("ivy", android.content.Context.MODE_PRIVATE) }
    var isOnboardingActive by remember { mutableStateOf(!prefs.getBoolean("setup_done", false)) }
    var isSandboxActive by remember { mutableStateOf(false) }

    // First-run Onboarding Walkthrough
    if (isOnboardingActive) {
        OnboardingScreen(
            viewModel = onboardingViewModel,
            dictionaryViewModel = dictionaryViewModel,
            onFinish = {
                prefs.edit().putBoolean("setup_done", true).apply()
                isOnboardingActive = false
                onboardingViewModel.restart()
            }
        )
        return
    }

    // Interactive Floating Bubble Sandbox (Mock chat with open keyboard)
    if (isSandboxActive) {
        BackHandler {
            isSandboxActive = false
        }
        BubbleSandboxScreen(
            onBack = { isSandboxActive = false }
        )
        return
    }

    // Main 5-Tab Application Scaffold
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            IvyTopBar()
        },
        bottomBar = {
            IvyBottomNavigationBar(
                currentTab = currentTab,
                onTabSelected = { tab ->
                    performTactileFeedback(context, 20)
                    currentTab = tab
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            AnimatedContent(
                targetState = currentTab,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "TabContentTransition"
            ) { targetTab ->
                when (targetTab) {
                    IvyTab.HOME -> HomeScreen(
                        viewModel = homeViewModel,
                        onTryItClicked = { isSandboxActive = true },
                        onNavigateToHistory = { currentTab = IvyTab.HISTORY }
                    )
                    IvyTab.HISTORY -> HistoryScreen(
                        viewModel = historyViewModel
                    )
                    IvyTab.DICTIONARY -> DictionaryScreen(
                        viewModel = dictionaryViewModel
                    )
                    IvyTab.SETTINGS -> SettingsScreen(
                        viewModel = settingsViewModel,
                        onReplayOnboarding = { isOnboardingActive = true }
                    )
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun IvyTopBar() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Logo and Wordmark
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .padding(vertical = 4.dp, horizontal = 4.dp)
                .testTag("ivy_top_logo_mark")
        ) {
            IvyLogoMark(size = 30.dp)
            Spacer(modifier = Modifier.width(10.dp))
            IvyWordmark(
                showLogo = false,
                fontSize = 22
            )
        }

        // "By Glow.co" pill
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "By Glow.co",
                fontFamily = InterFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                color = TextSecondary,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(GlassFill)
                    .border(1.dp, GlassBorder, CircleShape)
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            )
        }
    }
}

@Composable
private fun IvyBottomNavigationBar(
    currentTab: IvyTab,
    onTabSelected: (IvyTab) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .glassCard(shape = RoundedCornerShape(26.dp), fillColor = Color(0xF00F0C15), borderColor = GlassBorder)
            .padding(horizontal = 6.dp, vertical = 6.dp)
            .testTag("bottom_nav_bar")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IvyTab.values().forEach { tab ->
                val isSelected = tab == currentTab
                val tabModifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .clickable { onTabSelected(tab) }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .testTag("nav_tab_${tab.name.lowercase()}")

                Column(
                    modifier = tabModifier,
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(if (isSelected) IvyOrange.copy(alpha = 0.2f) else Color.Transparent),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = tab.title,
                            tint = if (isSelected) IvyOrange else TextMuted,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Text(
                        text = tab.title,
                        fontFamily = InterFamily,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 10.sp,
                        color = if (isSelected) Color.White else TextMuted
                    )
                }
            }
        }
    }
}
