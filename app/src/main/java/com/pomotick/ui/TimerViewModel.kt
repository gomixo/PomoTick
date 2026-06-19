package com.pomotick.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pomotick.PomoTickApp
import com.pomotick.data.SettingsSnapshot
import com.pomotick.data.TimerRepository
import com.pomotick.data.TimerSession
import com.pomotick.timer.ResponseAction
import com.pomotick.timer.TimerEngine
import com.pomotick.timer.TimerEvent
import com.pomotick.timer.TimerPhase
import com.pomotick.timer.TimerRunState
import com.pomotick.timer.TimerRuntimeState
import com.pomotick.timer.actualFocusMillis
import com.pomotick.timer.remainingMillis
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine as flowCombine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * v0.2 第四轮 P0 性能修复：UI 状态拆分为三个**独立**的 Flow，避免每秒 remainingMs
 * 倒计时触发整张 [TimerUiState] 重组。
 *
 * ```
 * baseState    — runtime / runState / phase / selectedPhase / settings
 *                → 主计时器页（按字段切分订阅）+ 根页面（仅 runState）+ 设置页（仅 settings）
 * remainingMs  — 独立 1Hz 刷新，**不**触发 baseState
 *                → 主计时器页的 TimerDial
 * statsState   — 今日统计 / 4 时段 / 周聚合
 *                → 统计页（仅在进入时刷新 + 新 session 推送时刷新）
 * ```
 *
 * **设计动机**：原实现 `startUiTick()` 每秒 `_state.update { copy(remainingMs = …) }`
 * 会让所有订阅 `viewModel.state` 的 Composable（根页面、HorizontalPager 里的
 * Settings/Stats、主计时器页）每秒钟都重组一次。手表 CPU 弱，长时间运行极易
 * 引起滑动卡顿、点击响应延迟。
 *
 * **关键约束**：
 * - `remainingMs` 的 1Hz tick **不**写 `baseState`——这是核心
 * - 根页面用 `baseState.map { it.runState }.distinctUntilChanged()` 只听 runState 变化
 * - 设置页用 `baseState.map { it.settings }.distinctUntilChanged()` 只听 settings 变化
 * - 统计页用独立的 `statsState`，**绝不订阅 baseState**——
 *   进入统计页时才 `refreshTodayStats()`，并订阅 Room `observeLatestCompletedFocus()`
 *   来推送最新一条完成 session
 */

/**
 * 主 UI 状态（不含每秒变化的 remainingMs，也不含统计字段）。
 *
 * - 任何字段变化才会推送；UI 用 `.map { it.x }` 切分订阅。
 */
data class BaseUiState(
    val runtime: TimerRuntimeState?,
    val runState: TimerRunState,
    val phase: TimerPhase?,
    val selectedPhase: TimerPhase,
    val settings: SettingsSnapshot
) {
    companion object {
        val IDLE = BaseUiState(
            runtime = null,
            runState = TimerRunState.IDLE,
            phase = null,
            selectedPhase = TimerPhase.FOCUS,
            settings = SettingsSnapshot.DEFAULT
        )
    }
}

/**
 * v0.2 第四轮 P0：统计页专用 state，与 baseState 完全解耦。
 *
 * - `refreshTodayStats()` 进入统计页时主动刷新
 * - 不再随 baseState 变化（如剩余时间倒计时）触发重组
 */
data class StatsState(
    val todayCount: Int = 0,
    val todayFocusMillis: Long = 0L,
    val todayBreakMillis: Long = 0L,
    /** v0.2 §8: 今日 4 时段专注毫秒（顺序：00-06, 06-12, 12-18, 18-24） */
    val focusBuckets: List<Long> = listOf(0L, 0L, 0L, 0L),
    /** v0.2 §8: 最近 7 天专注毫秒（顺序：6 天前 → 今天） */
    val weeklyFocus: List<DailyFocus> = emptyList(),
    val latestCompleted: TimerSession? = null
) {
    companion object {
        val IDLE = StatsState()
    }
}

/**
 * v0.2 §8: 一日专注聚合——用于周列表展示。
 */
data class DailyFocus(
    val dayStartEpochMillis: Long,
    val label: String,
    val focusMillis: Long
)

/**
 * 主 ViewModel。
 *
 * v0.2 第四轮 P0 性能修复：状态拆分为 [baseState] / [remainingMs] / [statsState] 三个独立 Flow。
 */
class TimerViewModel(
    private val repo: TimerRepository,
    private val app: Application
) : AndroidViewModel(app) {

    companion object {
        private const val TAG = "PomoTick/VM"
    }

    // ===== 三个独立的 StateFlow =====

    private val _baseState = MutableStateFlow(BaseUiState.IDLE)
    /** 主计时器页 + 根页面 + 设置页订阅的"基态"——**不**含每秒 remainingMs。 */
    val baseState: StateFlow<BaseUiState> = _baseState.asStateFlow()

    private val _remainingMs = MutableStateFlow(0L)
    /**
     * 独立的"显示用倒计时"Flow——1Hz 刷新，**不**触发 baseState。
     * 仅主计时器页 TimerDial 订阅；其他页面完全不订阅这个 Flow。
     */
    val remainingMs: StateFlow<Long> = _remainingMs.asStateFlow()

    private val _statsState = MutableStateFlow(StatsState.IDLE)
    /**
     * 统计页专用 state——**不**订阅 baseState，避免主计时器秒跳触发统计页重组。
     * 统计页进入时主动 `refreshTodayStats()`，并通过 Room Flow 接收新 session 推送。
     */
    val statsState: StateFlow<StatsState> = _statsState.asStateFlow()

    init {
        observeRuntime()
        observeSettings()
        startUiTick()
        preloadStats()
    }

    /**
     * v0.2 第五轮 P0 性能修复：APP 启动后**空闲时**预热一次统计查询，
     * 让第一次切到统计页时已经有数据可显示，避免"切页瞬间触发 9 次 SQL"。
     *
     * 时机选择：
     * - 延迟 1s 启动，让 startUiTick / observeSettings / observeRuntime 先就位
     * - 后续用户切到统计页时直接复用 `statsState`，切页动画不被 SQL 拖慢
     *
     * 后续统计页首次进入仍会再 `refreshTodayStats()` 一次（确保数据最新），
     * 但已有 `statsState` 作为兜底，UI 不会闪空。
     */
    private fun preloadStats() {
        viewModelScope.launch {
            kotlinx.coroutines.delay(1_000L)
            try {
                refreshTodayStats()
            } catch (e: Exception) {
                Log.w(TAG, "preloadStats failed (non-fatal): ${e.message}")
            }
        }
    }

    private fun observeRuntime() {
        viewModelScope.launch {
            repo.currentRuntime.collect { runtime ->
                val now = System.currentTimeMillis()
                _baseState.update { current ->
                    current.copy(
                        runtime = runtime,
                        runState = runtime?.runState ?: TimerRunState.IDLE,
                        phase = runtime?.phase ?: current.phase ?: current.selectedPhase
                    )
                }
                // 同步更新 remainingMs（不会触发 baseState 订阅者重组）
                _remainingMs.value = runtime?.let { remainingMillis(now, it) }
                    ?: durationFor(_baseState.value.selectedPhase, _baseState.value.settings)
            }
        }
    }

    /**
     * **启动恢复**——在 APP 启动 / ViewModel 创建后调用一次。
     *
     * 职责：
     * 1. 等待 DataStore 加载 runtime 状态
     * 2. 若 [TimerRuntimeState.runState] == RUNNING 且 `now >= targetEnd`，
     *    主动提交一次 [TimerEvent.OnTick] → Engine 自动转 RINGING 并触发 StartReminder
     * 3. 若 RUNNING 且尚未到点 → **重启 ForegroundService** 接管 tick
     * 4. 若 PAUSED → 重启 Service（tick 继续冻结，但 Service 准备好）
     * 5. 若 RINGING → 重启 Service + 不重复 StartReminder（避免重置提醒节奏）
     *
     * 这覆盖了 Service 被杀 / APP 闪退 / 重启手表 等恢复场景。
     */
    fun onAppStart() {
        viewModelScope.launch {
            val pomotickApp = app as PomoTickApp
            val initial = repo.currentRuntime.first()
            if (initial == null) {
                initializeIdleLaunch(pomotickApp)
                return@launch
            }

            when (initial.runState) {
                TimerRunState.RUNNING -> {
                    val now = System.currentTimeMillis()
                    if (now >= initial.targetEndAtEpochMillis) {
                        ensureServiceStarted(pomotickApp)
                        repo.handleEvent(TimerEvent.OnTick(now))
                    } else {
                        com.pomotick.service.TimerForegroundService.start(app)
                        // v0.2.1: 系统重启后 alarm 已被清；必须重新注册
                        pomotickApp.reregisterAlarmFromRuntime()
                    }
                }
                TimerRunState.PAUSED -> {
                    com.pomotick.service.TimerForegroundService.start(app)
                }
                TimerRunState.RINGING -> {
                    // v0.2 第三轮 P1 修复：把"RINGING 状态如何归一化"的决策权完全交给 Engine。
                    ensureServiceStarted(pomotickApp)
                    repo.handleEvent(TimerEvent.RingingRecovered(System.currentTimeMillis()))
                }
                TimerRunState.IDLE, TimerRunState.FINISHED -> {
                    repo.handleEvent(TimerEvent.Reset(initial.phase))
                    initializeIdleLaunch(pomotickApp)
                }
            }
        }
    }

    /**
     * v0.2 P1 修复：确保 Service 启动并注册到 [PomoTickApp] 后再返回。
     */
    private suspend fun ensureServiceStarted(pomotickApp: PomoTickApp) {
        com.pomotick.service.TimerForegroundService.start(pomotickApp)
        val deadline = System.currentTimeMillis() + 2_000L
        while (!pomotickApp.isServiceRunning() && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(50L)
        }
    }

    private suspend fun initializeIdleLaunch(pomotickApp: PomoTickApp) {
        val today = todayKey()
        if (pomotickApp.settingsStore.lastLaunchDate.first() != today) {
            pomotickApp.settingsStore.setSelectedPhase(TimerPhase.FOCUS)
            pomotickApp.settingsStore.setLastLaunchDate(today)
        }
    }

    // 注：v0.2 第三轮 P1 修复后，RINGING 冷启动恢复的"按时间戳归一化"逻辑
    // 已下沉到 TimerEngine.handleRingingRecovered，ViewModel 不再直接计算 remainingMs。
    // 旧的 computeRingingResumeMs 已删除——它在异常恢复路径会过早触发重复提醒
    // 且没有标记 repeatReminderFired，导致下一次 OnTick 再次触发。

    private fun observeSettings() {
        viewModelScope.launch {
            val pomotickApp = app as PomoTickApp
            // kotlinx.coroutines.flow.combine 最多 5 参数 overload；8 个 Flow 用 vararg 版本
            flowCombine(
                pomotickApp.settingsStore.focusMinutes,
                pomotickApp.settingsStore.shortBreakMinutes,
                pomotickApp.settingsStore.longBreakMinutes,
                pomotickApp.settingsStore.focusCyclesBeforeLongBreak,
                pomotickApp.settingsStore.cyclePosition,
                pomotickApp.settingsStore.vibrationStrength,
                pomotickApp.settingsStore.ringtoneEnabled,
                pomotickApp.settingsStore.persistentReminder
            ) { values ->
                @Suppress("UNCHECKED_CAST")
                SettingsSnapshot(
                    focusMinutes = values[0] as Int,
                    shortBreakMinutes = values[1] as Int,
                    longBreakMinutes = values[2] as Int,
                    focusCyclesBeforeLongBreak = values[3] as Int,
                    cyclePosition = values[4] as Int,
                    vibrationStrength = values[5] as Int,
                    ringtoneEnabled = values[6] as Boolean,
                    persistentReminder = values[7] as Boolean
                )
            }.collect { snap ->
                val current = _baseState.value
                val merged = snap.copy(
                    selectedPhase = current.selectedPhase,
                    lastLaunchDate = current.settings.lastLaunchDate
                )
                _baseState.update { it.copy(settings = merged) }
                // 同步更新 remainingMs（设置改变影响 IDLE 时显示的 plannedDuration）
                val now = System.currentTimeMillis()
                _remainingMs.value = current.runtime?.let { remainingMillis(now, it) }
                    ?: durationFor(current.selectedPhase, merged)
            }
        }
        viewModelScope.launch {
            val pomotickApp = app as PomoTickApp
            pomotickApp.settingsStore.selectedPhase.collect { phase ->
                val current = _baseState.value
                _baseState.update {
                    it.copy(
                        selectedPhase = phase,
                        phase = it.runtime?.phase ?: phase
                    )
                }
                // 同步更新 remainingMs（仅当没有 active runtime 时）
                if (current.runtime == null) {
                    _remainingMs.value = durationFor(phase, _baseState.value.settings)
                }
            }
        }
        viewModelScope.launch {
            val pomotickApp = app as PomoTickApp
            pomotickApp.settingsStore.lastLaunchDate.collect { date ->
                _baseState.update {
                    it.copy(settings = it.settings.copy(lastLaunchDate = date))
                }
            }
        }
    }

    /**
     * v0.2 第四轮 P0 修复：每秒刷新"显示用 remainingMs"——**只**写 [_remainingMs]，
     * **不**再写 [_baseState]。这样 baseState 的订阅者（根页面、设置页、统计页、
     * 主计时器页的非数字部分）不会被每秒触发。
     *
     * 真实计时仍由 Engine 在 Service 内按 timestamp 计算——本 tick 只为 UI 倒计时显示。
     */
    private fun startUiTick() {
        viewModelScope.launch {
            while (isActive) {
                delay(1000L)
                val now = System.currentTimeMillis()
                val runtime = _baseState.value.runtime
                _remainingMs.value = runtime?.let { r -> remainingMillis(now, r) }
                    ?: durationFor(_baseState.value.selectedPhase, _baseState.value.settings)
            }
        }
    }

    // ===== 用户事件 =====

    fun onStartFocus() {
        viewModelScope.launch {
            startPhase(TimerPhase.FOCUS)
        }
    }

    fun onStartSelectedPhase() {
        viewModelScope.launch {
            startPhase(_baseState.value.selectedPhase)
        }
    }

    fun onStartOrResume() {
        when (_baseState.value.runState) {
            TimerRunState.PAUSED -> onResume()
            else -> onStartSelectedPhase()
        }
    }

    private suspend fun startPhase(phase: TimerPhase) {
        val settings = _baseState.value.settings
        val plannedMs = durationFor(phase, settings)
        // v0.2 P1：把 5 个配置快照全部传给 Engine——
        // 中途改设置不影响当前 session；App 重启后 Engine 仅用 runtime 自身就能计算"下一阶段 + plannedMs"
        repo.handleEvent(
            TimerEvent.Start(
                now = System.currentTimeMillis(),
                phase = phase,
                plannedMs = plannedMs,
                cyclePositionAtStart = settings.cyclePosition,
                longBreakMinutesAtStart = settings.longBreakMinutes,
                shortBreakMinutesAtStart = settings.shortBreakMinutes,
                focusMinutesAtStart = settings.focusMinutes,
                cyclesBeforeLongBreakAtStart = settings.focusCyclesBeforeLongBreak
            )
        )
    }

    fun onResetTimer() {
        viewModelScope.launch {
            repo.handleEvent(TimerEvent.Reset(_baseState.value.selectedPhase))
        }
    }

    fun onSwitchPhase() {
        val current = _baseState.value
        if (current.runState == TimerRunState.RUNNING || current.runState == TimerRunState.RINGING) return
        viewModelScope.launch {
            val next = nextIdlePhase(current.runtime?.phase ?: current.selectedPhase)
            repo.handleEvent(TimerEvent.SwitchPhase(next))
        }
    }

    /**
     * v0.2 P1 修复：通知"停止"Action 与 UI "停止声震"按钮的入口。
     *
     * - 仅停声震、保持 RINGING
     * - 启动 §4 等待窗口（30s 后进入 3min 等待）
     * - 3min 后由 Engine 在 RINGING tick 中发出 StartReminder（15s 重复）
     * - 不写 session、不停服务、不清 runtime
     */
    fun onStopRingingOnly() {
        viewModelScope.launch {
            repo.handleEvent(TimerEvent.StopRingingOnly(System.currentTimeMillis()))
        }
    }

    /**
     * v0.2 P1 修复：UI "知道了"按钮的入口。
     */
    fun onStopRinging() {
        viewModelScope.launch {
            repo.handleEvent(TimerEvent.StopRingingAndPrepareNext(System.currentTimeMillis()))
        }
    }

    fun onPause() {
        viewModelScope.launch {
            repo.handleEvent(TimerEvent.Pause(System.currentTimeMillis()))
        }
    }

    fun onResume() {
        viewModelScope.launch {
            repo.handleEvent(TimerEvent.Resume(System.currentTimeMillis()))
        }
    }

    fun onExtend5Min() {
        viewModelScope.launch {
            repo.handleEvent(TimerEvent.Extend(System.currentTimeMillis(), 5L * 60L * 1000L))
        }
    }

    fun onFinishEarly() {
        viewModelScope.launch {
            // v0.2 P2: Engine 内部会写 EARLY_FINISHED session（区分自然到点）+ 发 AdvanceCycle
            repo.handleEvent(TimerEvent.FinishEarly(System.currentTimeMillis()))
        }
    }

    fun onAbandon() {
        viewModelScope.launch {
            // Abandon 写 INTERRUPTED session——Engine 已不再推进轮次（INTERRUPTED 不计数）
            repo.handleEvent(TimerEvent.Abandon(System.currentTimeMillis()))
        }
    }

    fun onRespond(action: ResponseAction) {
        viewModelScope.launch {
            // v0.2 P1: Engine 用 runtime 快照自己决定下一阶段 + plannedMs
            // 不再传入 RespondOptions
            repo.handleEvent(TimerEvent.Respond(System.currentTimeMillis(), action))
        }
    }

    /**
     * 启动休息（兼容旧 API，等价 onRespond(StartBreak)）。
     */
    fun onStartBreak() = onRespond(ResponseAction.StartBreak)

    // ===== 统计 =====

    /**
     * v0.2 §8 + 第四轮 P0 + 第五轮 P0 性能修复：刷新统计页所需的所有数据，**只**更新 [statsState]，
     * 不再写 [baseState]——避免主计时器倒计时触发统计页重组。
     *
     * 第五轮 P0 性能优化：
     * 1. **并行查询** —— 9 个独立 SQL 用 `async` 并行执行，从 9× 单次延迟降为 1× max(单次延迟)
     * 2. **合并周聚合** —— 7 次"最近 7 天"sequential 查询合并为 1 条 `GROUP BY day` SQL
     * 3. **结果空桶填空** —— 即使某一天没有 session，UI 也要显示一行（focusMillis = 0）
     *
     * 调用方式：
     * - ViewModel.init 启动时跑一次（[preloadStats]）预热，切到统计页时已有数据
     * - 统计页首次 `LaunchedEffect(Unit)` **延迟 300ms** 后再调用一次，避开切页动画
     * - `observeLatestSession` Flow 推送新完成 session 时也可再调用一次
     */
    fun refreshTodayStats() {
        viewModelScope.launch {
            val dayStart = startOfTodayMillis()
            val oneDay = 24L * 60L * 60L * 1000L
            val tomorrowStart = dayStart + oneDay
            val weekStart = dayStart - 6 * oneDay  // 6 天前
            val dayAfterEnd = dayStart + oneDay

            // 并行：9 个独立 SQL 一次性全部启动
            val totalFocusDeferred = async {
                repo.sumFocusMillisSince(dayStart)
            }
            val totalBreakDeferred = async {
                repo.sumBreakMillisBetween(dayStart, tomorrowStart)
            }
            val countDeferred = async {
                repo.countCompletedFocusSince(dayStart)
            }
            val bucket0 = async {
                repo.sumFocusMillisBetween(dayStart, dayStart + 6L * 60L * 60L * 1000L)
            }
            val bucket1 = async {
                repo.sumFocusMillisBetween(dayStart + 6L * 60L * 60L * 1000L, dayStart + 12L * 60L * 60L * 1000L)
            }
            val bucket2 = async {
                repo.sumFocusMillisBetween(dayStart + 12L * 60L * 60L * 1000L, dayStart + 18L * 60L * 60L * 1000L)
            }
            val bucket3 = async {
                repo.sumFocusMillisBetween(dayStart + 18L * 60L * 60L * 1000L, dayStart + 24L * 60L * 60L * 1000L)
            }
            val weeklyAggDeferred = async {
                repo.sumFocusMillisGroupedByDay(
                    todayStart = dayStart,
                    dayMillis = oneDay,
                    weekStart = weekStart,
                    dayAfterEnd = dayAfterEnd
                )
            }
            val latestDeferred = async {
                repo.latestCompletedFocus()
            }

            // awaitAll 等所有并行任务完成
            val totalFocus = totalFocusDeferred.await()
            val totalBreak = totalBreakDeferred.await()
            val count = countDeferred.await()
            val buckets = listOf(bucket0.await(), bucket1.await(), bucket2.await(), bucket3.await())
            val weeklyAgg = weeklyAggDeferred.await()
            val latest = latestDeferred.await()

            val weekly = buildWeeklyList(dayStart, weeklyAgg)

            _statsState.value = StatsState(
                todayCount = count,
                todayFocusMillis = totalFocus,
                todayBreakMillis = totalBreak,
                focusBuckets = buckets,
                weeklyFocus = weekly,
                latestCompleted = latest
            )
        }
    }

    /**
     * 把 DAO 返回的 (dayOffset, focusMillis) 列表补全成"6 天前 → 今天"7 个槽位。
     *
     * 即便某一天没有 session 也要占位 0，让 UI 列表行数固定。
     */
    private fun buildWeeklyList(
        todayStart: Long,
        aggregates: List<com.pomotick.data.DailyFocusAggregate>
    ): List<DailyFocus> {
        val oneDay = 24L * 60L * 60L * 1000L
        val labelFmt = java.text.SimpleDateFormat("MM-dd", java.util.Locale.getDefault())
        // dayOffset 范围 [-6, 0]；UI 顺序：-6 (6 天前) ... 0 (今天)
        val byOffset = aggregates.associate { it.dayOffset to it.focusMillis }
        return (-6..0).map { offset ->
            val dayStart = todayStart + offset * oneDay
            DailyFocus(
                dayStartEpochMillis = dayStart,
                label = labelFmt.format(java.util.Date(dayStart)),
                focusMillis = byOffset[offset.toLong()] ?: 0L
            )
        }
    }

    private fun startOfTodayMillis(): Long {
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    private fun todayKey(): String = java.time.LocalDate.now().toString()

    private fun durationFor(phase: TimerPhase, settings: SettingsSnapshot): Long =
        when (phase) {
            TimerPhase.FOCUS -> settings.focusMinutes
            TimerPhase.SHORT_BREAK -> settings.shortBreakMinutes
            TimerPhase.LONG_BREAK -> settings.longBreakMinutes
        } * 60_000L

    private fun nextIdlePhase(phase: TimerPhase): TimerPhase =
        when (phase) {
            TimerPhase.FOCUS -> TimerPhase.SHORT_BREAK
            TimerPhase.SHORT_BREAK, TimerPhase.LONG_BREAK -> TimerPhase.FOCUS
        }
}

/**
 * 手动注入 ViewModel（无 Hilt）。
 */
class TimerViewModelFactory(
    private val app: Application,
    private val repo: TimerRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(TimerViewModel::class.java)) {
            "Unknown ViewModel: ${modelClass.name}"
        }
        return TimerViewModel(repo, app) as T
    }
}
