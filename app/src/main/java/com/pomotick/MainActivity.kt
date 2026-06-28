package com.pomotick

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Window
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pomotick.timer.TimerRunState
import com.pomotick.ui.TimerViewModel
import com.pomotick.ui.TimerViewModelFactory
import com.pomotick.ui.screens.ReminderScreen
import com.pomotick.ui.screens.SettingsScreen
import com.pomotick.ui.screens.TimerScreen
import com.pomotick.ui.screens.TodayStatsScreen
import com.pomotick.ui.theme.PomoTickTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 唯一 Activity。
 *
 * v0.2 §7/§8: 三个核心屏改为左右滑动切换（HorizontalPager）：
 *
 * ```
 *   [Settings]  ←滑→  [Timer]  ←滑→  [Stats]
 *     页面 0         页面 1         页面 2
 * ```
 *
 * - 主 UI（页面 1）= `TimerScreen`——开机/默认进入
 * - 左滑 = 进入 `SettingsScreen`（v0.2 §7）
 * - 右滑 = 进入 `TodayStatsScreen`（v0.2 §8）
 * - **RINGING 状态** 仍以全屏 overlay 模式显示 `ReminderScreen`，
 *   屏蔽左滑/右滑导航——RINGING 是用户必须立即响应的状态。
 *
 * 关键职责：
 * - **运行时权限请求**：Android 13+ POST_NOTIFICATIONS（不到 API 33 跳过）
 * - **启动恢复**：创建后调用 [TimerViewModel.onAppStart]，覆盖 Service 被杀 / 重启场景
 */
class MainActivity : ComponentActivity() {

    // 记录是否已经从设置页的权限行请求过权限。
    // 首次点击仍弹系统权限框；若用户选择“不再询问”，下次点击改跳系统设置。
    private var hasRequestedNotificationFromRow = false

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_POST_NOTIFICATIONS
            )
        }
    }

    /**
     * 设置页入口：先尝试弹权限框；被拒绝且不再询问后跳转系统通知设置。
     */
    fun requestOrOpenNotificationSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) return

        val shouldExplain = ActivityCompat.shouldShowRequestPermissionRationale(
            this, Manifest.permission.POST_NOTIFICATIONS
        )

        if (!hasRequestedNotificationFromRow || shouldExplain) {
            requestNotificationPermission()
            hasRequestedNotificationFromRow = true
        } else {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            }
            startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // v0.2.1: 到点唤醒时直接显示在锁屏之上 + 自动转屏幕。
        // 必须在 super.onCreate 之前调用。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        requestWindowFeature(Window.FEATURE_NO_TITLE)
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        // API 33+：请求 POST_NOTIFICATIONS 运行时权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestNotificationPermission()
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

private enum class Overlay {
    NONE, REMINDER
}

private const val PAGE_TIMER = 1
private const val REQUEST_POST_NOTIFICATIONS = 100

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PomoTickRoot() {
    val context = LocalContext.current
    val app = context.applicationContext as PomoTickApp
    val viewModel: TimerViewModel = viewModel(
        factory = TimerViewModelFactory(app, app.repository)
    )
    val scope = rememberCoroutineScope()

    // v0.2 第四轮 P0 性能修复：根页面**只**订阅 runState，**不**订阅完整 state。
    // 倒计时每秒变化时不会触发 PomoTickRoot 重组；仅 RINGING/IDLE 等状态机
    // 切换时才会重组，进而切换 overlay。
    val runState by remember(viewModel) {
        viewModel.baseState
            .map { it.runState }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = TimerRunState.IDLE)

    var overlay by remember { mutableStateOf(Overlay.NONE) }
    // v0.2 第五轮 P0 性能修复：HorizontalPager 预加载相邻页（beyondBoundsPageCount=1）。
    // 三页成本不高（主 UI / 设置 / 统计），预加载让左右页提前组合，第一次滑动会顺很多。
    // 统计页预加载时**不会**立刻查库（[preloadStats] 启动后 1s 才跑；统计页 LaunchedEffect
    // 300ms 后再 refresh），所以启动阶段不会立刻触发 Room 9 次查询。
    val pagerState = rememberPagerState(initialPage = PAGE_TIMER) { 3 }

    // **启动恢复**：APP 启动 / ViewModel 创建后检测 RUNNING 过期 → 转 RINGING
    LaunchedEffect(Unit) {
        viewModel.onAppStart()
    }

    // 自动跳转：RINGING → 覆盖 ReminderScreen；从 RINGING 离开 → 回到主 UI
    LaunchedEffect(runState) {
        when (runState) {
            TimerRunState.RINGING -> overlay = Overlay.REMINDER
            TimerRunState.IDLE -> {
                if (overlay == Overlay.REMINDER) overlay = Overlay.NONE
            }
            else -> Unit
        }
    }

    if (overlay == Overlay.REMINDER) {
        ReminderScreen(viewModel = viewModel)
    } else {
        HorizontalPager(
            state = pagerState,
            // v0.2 第五轮 P0 性能修复：预加载左右相邻页（各 1 页）。
            // 启动时只多组合 2 个轻量 Composable，第一次滑动不再卡。
            beyondBoundsPageCount = 1,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            when (page) {
                0 -> SettingsScreen(
                    viewModel = viewModel,
                    onBack = {
                        scope.launch { pagerState.animateScrollToPage(PAGE_TIMER) }
                    },
                    onRequestNotificationPermission = {
                        (context as? MainActivity)?.requestOrOpenNotificationSettings()
                    },
                    onRequestBatteryOptimization = {
                        val result = com.pomotick.system.BatteryOptimizationHelper(
                            context
                        ).smartOpenBatterySettings()
                        when (result) {
                            com.pomotick.system.BatteryJumpResult.APP_DETAILS -> {
                                Toast.makeText(
                                    context,
                                    "在系统页里选择后台运行/电池不受限制",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            com.pomotick.system.BatteryJumpResult.ALL_FAILED -> {
                                Toast.makeText(
                                    context,
                                    "无法自动跳转，请手动前往系统设置",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            else -> Unit
                        }
                    }
                )
                1 -> TimerScreen(
                    viewModel = viewModel,
                    isVisible = pagerState.currentPage == PAGE_TIMER
                )
                2 -> TodayStatsScreen(
                    viewModel = viewModel,
                    onBack = {
                        scope.launch { pagerState.animateScrollToPage(PAGE_TIMER) }
                    }
                )
            }
        }

        // 系统返回键：在非主 UI 页面回到主 UI
        val isMainPage = pagerState.currentPage == PAGE_TIMER
        BackHandler(enabled = !isMainPage) {
            scope.launch { pagerState.animateScrollToPage(PAGE_TIMER) }
        }
    }
}
