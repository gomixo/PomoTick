package com.pomotick

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pomotick.timer.TimerRunState
import com.pomotick.ui.TimerViewModel
import com.pomotick.ui.TimerViewModelFactory
import com.pomotick.ui.screens.QuickActionsScreen
import com.pomotick.ui.screens.ReminderScreen
import com.pomotick.ui.screens.SettingsScreen
import com.pomotick.ui.screens.TimerScreen
import com.pomotick.ui.screens.TodayStatsScreen
import com.pomotick.ui.theme.PomoTickTheme

/**
 * 唯一 Activity。
 *
 * 使用本地 `enum class Screen` + `when` 切换 5 个屏幕，**不使用 Navigation 框架**。
 *
 * 关键职责：
 * - **运行时权限请求**：Android 13+ POST_NOTIFICATIONS（不到 API 33 跳过）
 * - **启动恢复**：创建后调用 [TimerViewModel.onAppStart]，覆盖 Service 被杀 / 重启场景
 */
class MainActivity : ComponentActivity() {

    /**
     * Android 13+ 通知权限请求 launcher。
     * 使用 ActivityResultContracts.RequestPermission 是现代做法（非已废弃的 onRequestPermissionsResult）。
     */
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // 即使用户拒绝，仍可继续使用 APP（震动 + 视觉反馈），只是通知不出现在通知中心
            // 不做强制退出逻辑
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // API 33+：请求 POST_NOTIFICATIONS 运行时权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            PomoTickTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PomoTickRoot()
                }
            }
        }
    }
}

private enum class Screen {
    TIMER, QUICK_ACTIONS, REMINDER, SETTINGS, STATS
}

@Composable
private fun PomoTickRoot() {
    val context = LocalContext.current
    val app = context.applicationContext as PomoTickApp
    val viewModel: TimerViewModel = viewModel(
        factory = TimerViewModelFactory(app, app.repository)
    )

    val state by viewModel.state.collectAsStateWithLifecycle()

    var screen by remember { mutableStateOf(Screen.TIMER) }

    // **启动恢复**：APP 启动 / ViewModel 创建后检测 RUNNING 过期 → 转 RINGING
    LaunchedEffect(Unit) {
        viewModel.onAppStart()
    }

    // 自动跳转：RINGING → ReminderScreen；从 RINGING 离开（用户响应）→ 回到 TIMER
    LaunchedEffect(state.runState) {
        when (state.runState) {
            TimerRunState.RINGING -> screen = Screen.REMINDER
            TimerRunState.IDLE -> {
                if (screen == Screen.REMINDER) screen = Screen.TIMER
            }
            else -> Unit
        }
    }

    when (screen) {
        Screen.TIMER -> TimerScreen(
            viewModel = viewModel,
            onNavigateToQuickActions = { screen = Screen.QUICK_ACTIONS },
            onNavigateToSettings = { screen = Screen.SETTINGS },
            onNavigateToStats = { screen = Screen.STATS }
        )
        Screen.QUICK_ACTIONS -> QuickActionsScreen(
            viewModel = viewModel,
            onBack = { screen = Screen.TIMER }
        )
        Screen.REMINDER -> ReminderScreen(viewModel = viewModel)
        Screen.SETTINGS -> SettingsScreen(
            viewModel = viewModel,
            onBack = { screen = Screen.TIMER }
        )
        Screen.STATS -> TodayStatsScreen(
            viewModel = viewModel,
            onBack = { screen = Screen.TIMER }
        )
    }
}
