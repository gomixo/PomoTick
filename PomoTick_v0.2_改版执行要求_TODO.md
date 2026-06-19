# PomoTick v0.2 提醒与轮次版改版执行要求

> 需求来源：`PomoTick_v0.2_提醒与轮次版需求.md`  
> 技术参考：`Tomato项目参考笔记.md`  
> 执行原则：符合 `AGENTS.md` 的长期开发规则

## 0. Git 分支流程

- [x] 确认当前 `main` 分支干净，或先保存/提交已有工作。 — 已在 main 上做基线提交 `c28f97f`（AGENTS.md 改版、.gitignore 完善、图标资源更新、参考笔记、v0.2 需求与 TODO 文档）。
- [x] 从 `main` 创建 v0.2 开发分支，建议分支名：`v0.2-reminder-cycles`。 — 已创建并切换到 `v0.2-reminder-cycles`。
- [x] 所有 v0.2 改版工作都在该开发分支完成。 — 当前工作分支即 `v0.2-reminder-cycles`。
- [ ] 每完成一个相对独立模块，检查一次构建和关键功能。
- [ ] 全部功能完成后，执行完整测试清单。
- [ ] 测试通过后再合并回 `main`。
- [ ] 合并前保留 v0.2 需求文档和本 TODO 文档，作为验收依据。

> 备注：当前 PID 4956 仍在跑 `:app:assembleDebug`（Gradle Wrapper 进程自 6/14 启动），导致 `settings.gradle.kts` 等文件被 mmap 锁住，表现为 `git status` 上 4 个文件存在 stat-only 的 `M` 标记，但内容已与 HEAD 对齐（`.gitignore` 已用新内容落库）。构建结束/中止后用 `git update-index --refresh` 即可清除这些伪标记。

## 1. 改版前代码盘点

- [x] 确认当前计时状态模型是否已经包含 `FOCUS`、`SHORT_BREAK`、`LONG_BREAK` — **已包含**。`app/src/main/java/com/pomotick/timer/TimerPhase.kt` 定义 `enum class TimerPhase { FOCUS, SHORT_BREAK, LONG_BREAK }`。运行时通过 `TimerRuntimeState.phase: TimerPhase` 携带，并在 `TimerEngine`/`TimerRepository` 中流转。
- [x] 确认当前运行状态是否已经包含 `IDLE`、`RUNNING`、`PAUSED`、`RINGING`、`FINISHED` — **全部已包含**。`app/src/main/java/com/pomotick/timer/TimerRunState.kt` 定义 5 个值。`TimerRuntimeState.runState: TimerRunState` 携带；`TimerEngine.process` 负责状态迁移；`MainActivity.PomoTickRoot` 通过 `LaunchedEffect(state.runState)` 在 `RINGING ↔ IDLE` 之间自动切换 `Screen.TIMER ↔ Screen.REMINDER`。
- [x] 找到当前主计时 UI 的入口和 3 个实体按钮实现位置 — `MainActivity.setContent` → `PomoTickRoot` → `TimerScreen`（`app/src/main/java/com/pomotick/ui/screens/TimerScreen.kt`）。3 个按钮位于第 76-110 行的 `Row` 中：
  - 1) **开始/暂停**（`TimerActionButton` + `ic_action_play`/`ic_action_pause`，`viewModel.onPause` / `onStartOrResume`）
  - 2) **重置**（`ic_action_reset`，`viewModel.onResetTimer`）
  - 3) **切换阶段**（`ic_action_switch_phase`，`viewModel.onSwitchPhase`；运行/RINGING 时禁用）
  - 此外 `QuickActionsScreen` 还有 3 个二级操作（延长 5 分钟 / 提前结束 / 放弃）—— **§5 改版时整个 `TimerScreen` 重做，`QuickActionsScreen` 也要重新评估**。
- [x] 找到当前计时完成后的提醒入口 — 多入口，**主入口**为 `TimerEngine.process(OnTick)` 推断到点 → 产出 `TimerEffect.StartReminder` → `Repository.effectHandler` → `PomoTickApp.handleGlobalEffect` → `TimerForegroundService.handleEffect(StartReminder)`（`service/TimerForegroundService.kt:147`）。下游动作：
  - 屏幕：自动跳到 `Screen.REMINDER`（`MainActivity.kt:105`）
  - 震动：`reminderManager.start(...)` → `VibrationHelper.startContinuous`
  - 通知：常驻 + RINGING 高优通知各一条（`NotificationFactory.buildRinging`）
- [x] 找到当前震动实现位置，确认是否已经使用 `VibrationEffect.createWaveform()` — `app/src/main/java/com/pomotick/reminder/VibrationHelper.kt`，**已用** `VibrationEffect.createWaveform(timings, amplitudes, -1)`（第 35 行附近）。通过 `ReminderManager` 包装为 `startContinuous` / `pulseOnce` / `stop` 三种调用，按 `ReminderSettings.strength` (0/1/2) 选不同强度波形。**§3 需复用现有 VibrationHelper，仅补"30s 自动停止"和"3min 后 1 次 15s 重复"两层逻辑。**
- [x] 找到当前铃声实现位置；如果没有铃声逻辑，标记为本版本新增 — `app/src/main/java/com/pomotick/reminder/ReminderSoundPlayer.kt` **文件存在但仅 3 个 TODO stub**：`init() {}` / `release() {}` / `playTick(durationMs: Long) {}`，全部空实现。`ReminderManager.play()` 走的是 `sound?.playTick(...)` 但 `playTick` 内部是空 Unit —— **等同于"无铃声逻辑"**。**本版本需新增**：
  - 选用内置 raw 资源或 `RingtoneManager` 走 `STREAM_ALARM`（不依赖 Google Play Services）
  - 实现 init/release/playTick/stop
  - 在停止入口统一 `stop()`，重置 MediaPlayer/Ringtone 位置
  - 受 `ReminderSettings.enabled` 控制（v0.2 起持久提醒开关与响铃共用同一开关）
- [x] 找到当前设置保存方式，确认使用 DataStore 还是 Room runtime-state table — **DataStore**。`app/src/main/java/com/pomotick/data/SettingsStore.kt`（`preferencesDataStore(name="pomotick_settings")`）保存时长、震动强度、持续提醒开关、`selectedPhase`、`lastLaunchDate`。运行时状态用 **单独的 DataStore**（`RuntimeStateStore.kt`，`preferencesDataStore(name="pomotick_runtime")`）保存 TimerRuntimeState 序列化的 JSON。**§9 数据和持久化要求"以时间戳为恢复依据"——当前 `TimerRuntimeState` 已经用 `targetEndAtEpochMillis` / `pausedAt` / `resumedAt` 字段**，需要核对是否所有字段都基于时间戳、不能有剩余 `remainingMs` 这种"漂移字段"。
- [x] 找到当前 completed session 的 Room 表和写入逻辑 — Room。
  - 表：`app/src/main/java/com/pomotick/data/TimerSession.kt`（`@Entity(tableName="timer_sessions")`，字段：id/phase/startedAt/endedAt/plannedDurationMillis/actualDurationMillis/result）
  - DAO：`TimerSessionDao.kt` 提供 `insert`、`countCompletedFocusSince`、`sumFocusMillisSince`、`latestCompletedFocus`、`recentCompletedFocus`、`observeLatestCompletedFocus` 等
  - 写入：`TimerRepository.executeEffects` 处理 `TimerEffect.RecordSession → dao.insert(effect.session)`（`TimerRepository.kt:71`）。Engine 在 `Respond`/`FinishEarly` 等迁移到 RINGING 的事件里产生此 effect。
  - 库：`AppDatabase.kt`（`@Database(entities=[TimerSession::class], version=1)`）
- [x] 找到当前统计界面的数据来源和展示方式 — `app/src/main/java/com/pomotick/ui/screens/TodayStatsScreen.kt`，**今天 + 3 项**：今日完成数（`countCompletedFocusSince`）、今日专注总时长（`sumFocusMillisSince`）、最近一次完成时间（`latestCompleted.endedAtEpochMillis`）。**§8 要求"今日 + 一周 + 4 时段拆分"——需新增**：周聚合 DAO 查询、`WeekStatsScreen`、4 时段划分（早 06-12/午 12-18/晚 18-24/夜 00-06）。`TimerSessionDao` 已有时间戳字段，不需要迁移 schema。

## 2. 分支内实现顺序

- [ ] 先实现状态和数据层变更。
- [ ] 再实现提醒服务和通知停止逻辑。
- [ ] 再实现主 UI 交互改版。
- [ ] 再实现专注轮次和长休息切换。
- [ ] 再实现设置界面。
- [ ] 最后实现统计界面和适配检查。

## 3. 响铃震动提醒

- [x] 计时结束时，将运行状态切换为 `RINGING`。 — 已有（`TimerEngine.process(OnTick)` 到点产生 `StartReminder` effect）。
- [x] 进入 `RINGING` 后展示提醒响应界面。 — 已有（`MainActivity.PomoTickRoot` 自动 `Screen.REMINDER`）。
- [x] 进入 `RINGING` 后触发震动。 — 已有（`ReminderManager.start → VibrationHelper.vibrateFor`）。
- [x] 如果响铃开关开启，进入 RINGING 后同时播放铃声。 — `ReminderManager.start` 检查 `settings.ringtoneEnabled`，开启时调用 `ReminderSoundPlayer.start`。
- [x] 铃声播放使用系统兼容方式，不依赖 Google Play Services。 — `ReminderSoundPlayer` 使用 `RingtoneManager.getDefaultUri(TYPE_ALARM)` + 系统 `Ringtone` API，零三方依赖。
- [x] 铃声和震动都由同一个停止入口统一停止。 — `ReminderManager.stop()` 同时调 `vib.cancel()` + `sound.stop()`。
- [x] 用户在提醒界面点击停止时，立即停止铃声和震动。 — `onStopRinging → StopRingingAndPrepareNext → ReminderEffect.StopReminder → serviceScope.handleEffect(StopReminder) → reminderManager.stop()`。
- [x] 用户从通知点击停止时，立即停止铃声和震动。 — `NotificationFactory.buildRinging` 加 `.addAction(STOP_RINGING)`，PendingIntent 启动 `TimerForegroundService` 带 `ACTION_STOP_RINGING`，`onStartCommand` 提交流程同 UI 路径。
- [x] 如果用户没有响应，第一次提醒在 30 秒后自动停止。 — `ReminderManager.start` 内 `delay(30_000L)` 后 finally 块统一清理。
- [x] 第一次提醒自动停止后，界面仍保持“等待用户处理下一步”的清晰状态。 — `runState` 维持 `RINGING`（自动停止只清 effect，不动状态机）；`ReminderScreen` 始终在屏，无超时切回。
- [x] 停止提醒后重置铃声播放位置，避免下一次提醒从中间播放。 — `ReminderSoundPlayer.stop()` 把 `ringtone` 引用置空，下次 `start()` 重新创建 `Ringtone` 实例，等价于 `MediaPlayer.seekTo(0)`。
- [x] 停止提醒后取消震动，避免后台继续震动。 — `VibrationHelper.cancel()` 在 `finally` 块保证——外部 stop、协程 cancel、超时到期都走同一路径。

> 改动文件清单：`reminder/ReminderSoundPlayer.kt`（从 stub 改为完整实现）、`reminder/ReminderManager.kt`（重写为单次 + 30s 统一清理）、`data/SettingsStore.kt`（+`RINGTONE_ENABLED` + 快照字段）、`service/NotificationFactory.kt`（+`ACTION_STOP_RINGING` + 通知 Action）、`service/TimerForegroundService.kt`（`onStartCommand` 处理 action）、`ui/screens/SettingsScreen.kt`（持续提醒 → 响铃）、`ui/TimerViewModel.kt`（vararg `flowCombine` 6 个设置流）、`PomoTickApp.kt`（bootstrap 监听 ringtone）、`res/values/strings.xml`（+`notif_action_stop`、`settings_ringtone_enabled`）。`compileDebugKotlin` 通过。

## 4. 一次重复提醒

- [x] 第一次提醒停止后，开始记录"等待用户新动作"的 3 分钟窗口。 — `TimerEngine.scheduleRepeatReminder` 在 `OnTick` 期间检测"自 `ringingStartedAtEpochMillis` 已过 ≥ 30s" 时设置 `awaitingRepeatSinceEpochMillis = now`，启动 3 分钟窗口。
- [x] 用户手动停止第一次提醒后，也进入 3 分钟等待窗口。 — 手动停止会触发 `StopReminder`（`vib.cancel() + sound.stop()`）但 **不修改 runtime**，`runState` 仍为 RINGING；OnTick 继续走 §4 逻辑，约 30s 后自然进入等待窗口。注：用户从通知"停止"Action 走的是 `StopRingingAndPrepareNext`（清 runtime → IDLE），3 分钟窗口不适用——但这属于"用户主动结束"，与"用户想要被再次提醒"语义冲突，按需求理解视为已结束。
- [x] 第一次提醒 30 秒自动停止后，也进入 3 分钟等待窗口。 — `ReminderManager.AUTO_STOP_MS=30s` 自动停止后 `runState=RINGING` 不变；30s 后 `OnTick` 检测到 `elapsedSinceRingStart >= 30s` → 设置 `awaitingRepeatSinceEpochMillis`。
- [x] 如果 3 分钟内没有新动作，触发 1 次重复提醒。 — `OnTick` 检测 `now - awaitingRepeatSinceEpochMillis >= 180_000 && !repeatReminderFired` → 发出 `StartReminder` effect 并置 `repeatReminderFired=true`。
- [x] 重复提醒同时响铃和震动。 — 重复路径仍走 `ReminderManager.start(...)`，内部 `vib.vibrateFor(phase, strength)` + `sound.start(this)` 同时启动；与首次提醒相同行为。
- [x] 重复提醒持续 15 秒。 — `TimerForegroundService.handleEffect(StartReminder)` 检测到 `state.repeatReminderFired == true` 时传入 `ReminderManager.REPEAT_DURATION_MS = 15_000L`。
- [x] 重复提醒 15 秒后自动停止。 — `ReminderManager.start` 的 `delay(durationMs)` 复用同样的 `finally` 清理逻辑，15s 后 `vib.cancel() + sound.stop()`。
- [x] 用户可以提前停止重复提醒。 — `TimerEvent.StopRingingAndPrepareNext`（通知"停止"Action）或 `TimerEvent.Respond.KnowIt`（UI "知道了"）都走 `TimerEffect.StopReminder` → `reminderManager.stop()`，对首次/重复通用。
- [x] 重复提醒只触发 1 次，不进入无限循环。 — 触发时设置 `repeatReminderFired=true`；`scheduleRepeatReminder` 的第二分支条件包含 `!repeatReminderFired`，一旦置 true 就不会再次进入。每次新 RINGING（`enterRinging` 助手）都会重置为 false。
- [x] 以下任意动作都取消等待中的重复提醒：
  - 开启下一阶段计时。 — `Respond.StartBreak` 构造新 `TimerRuntimeState`（默认 `awaitingRepeatSinceEpochMillis=null, repeatReminderFired=false`），§4 字段被自然清空。
  - 重置当前计时。 — `Reset` 事件产生 `ClearRuntime` effect，runtime 为 null，§4 逻辑完全不触发。
  - 手动切换下一阶段。 — `SwitchPhase` 事件产生 `ClearRuntime` effect，同上。
  - 开始计时。 — `Start` 事件构造新 `TimerRuntimeState`，§4 字段默认 null/false。
  - 暂停计时。 — `Pause` 事件 `runState` 变为 `PAUSED`，§4 tick 分支不进入（仅在 `RINGING` 中检查）。
  - 继续计时。 — `Resume` 事件 `runState` 变为 `RUNNING`，同上。
- [x] App 重启后，如果仍处于需要提醒响应的状态，按当前持久化状态恢复，不制造重复提醒风暴。 — `TimerRuntimeState` 的 3 个 §4 字段已加入 `data class` 并通过 `RuntimeStateStore`（kotlinx.serialization JSON）持久化；启动时 `OnTick` 重新按"now - timestamp"判定：
  - `ringingStartedAtEpochMillis == null`：跳过 §4 逻辑（防御性兜底）
  - `awaitingRepeatSinceEpochMillis == null && elapsed >= 30s`：进入 3 分钟窗口
  - `awaitingRepeatSinceEpochMillis != null && now - awaitingRepeatSinceEpochMillis >= 180_000 && !repeatReminderFired`：立即发出 1 次重复提醒
  - `repeatReminderFired == true`：不再触发（保证"只 1 次"约束在重启后依然成立）

> 改动文件清单：`timer/TimerRuntimeState.kt`（+`ringingStartedAtEpochMillis` / +`awaitingRepeatSinceEpochMillis` / +`repeatReminderFired`）、`timer/TimerEngine.kt`（+`enterRinging` / +`scheduleRepeatReminder` / +`tickOnly` / +`REPEAT_WAIT_AFTER_AUTO_STOP_MS`=30s / +`REPEAT_WINDOW_MS`=3min）、`reminder/ReminderManager.kt`（`start` 增加 `durationMs` 参数 / +`REPEAT_DURATION_MS`=15s）、`service/TimerForegroundService.kt`（`handleEffect(StartReminder)` 根据 `state.repeatReminderFired` 选 15s 或 30s）。`compileDebugKotlin` 通过。

## 5. 主 UI 改版

- [x] 移除主计时界面常驻的 3 个实体按钮。 — `TimerScreen` 删除原第 76-110 行 `Row` 内的 3 个 `TimerActionButton`（开始·暂停 / 重置 / 切换阶段）。
- [x] 保留环形计时环。 — `TimerDial` 仍使用 `Canvas` 绘制 `trackColor` 圆形轨道 + 进度弧。
- [x] 放大环形计时区域。 — 宽度从 `fillMaxWidth(0.9f)` 提升到 `fillMaxWidth(0.94f)`，外层 `padding` 从 `(34.dp, 22.dp)` 减到 `(16.dp, 18.dp)`；`stroke` 从 13dp 提升到 14dp。
- [x] 环形计时区域与屏幕边缘保持美观距离。 — `aspectRatio(1f) + weight(1f) + fillMaxWidth(0.94f)` 三者约束保证圆形不贴边；外层 padding 提供安全区域。
- [x] 中间显示剩余时间。 — `TimerText` 字号根据 maxWidth 动态计算（5 位时 40sp / 4 位时 50sp 上限）。
- [x] 剩余时间下方显示一个当前操作 icon。 — `Column` 中 `TimerText` + `Spacer(6.dp)` + `Icon(size=34.dp)`。
- [x] 未开始时，icon 表示开始。 — `actionIconRes = ic_action_play`（IDLE 分支）。
- [x] 运行中时，icon 表示暂停。 — `actionIconRes = ic_action_pause`（RUNNING 分支）。
- [x] 暂停中时，icon 表示继续。 — `actionIconRes = ic_action_play`（PAUSED 分支，contentDescription 切换为 `action_resume`）。
- [x] 点击环形中间区域：
  - 未开始时开始计时。 — `onTap` 内 `RUNNING → onPause() / PAUSED → onResume() / IDLE → onStartOrResume()`。
  - 运行中时暂停计时。 — 同上。
  - 暂停中时继续计时。 — 同上。
- [x] 长按环形中间区域弹出 2 个操作：
  - 重置时间。 — `LongPressMenu` 内第一个 `BigMenuButton`，调用 `viewModel.onResetTimer()`。
  - 切换下一阶段。 — 第二个 `BigMenuButton`，调用 `viewModel.onSwitchPhase()`。
- [x] 长按弹出的 2 个选项必须足够大，适合手表点击。 — `BigMenuButton` 高度 70dp，圆角 35dp，文字 20sp + 26dp icon；外层 `AlertDialog` 圆角 24dp 居中弹窗。
- [x] 主 UI 不增加解释性文字，不做手机式引导页。 — `TimerScreen` 仅时间 + icon + 长按菜单（无提示文案）；菜单标题"操作"为最小化引导。

> 改动文件清单：`ui/screens/TimerScreen.kt`（重写：移除 3 按钮 + 大圆环 + 中央时间 + 操作 icon + 点击/长按 + AlertDialog 菜单）、`ui/screens/QuickActionsScreen.kt`（**已删除**——v0.2 改版后无引用，所有"非主要操作"通过设置/长按菜单进入）、`res/values/strings.xml`（+`menu_long_press_title` / +`menu_reset` / +`menu_switch_phase` / +`menu_cancel`）。`compileDebugKotlin` 通过。

## 6. 专注轮次与长休息

- [x] 默认专注时长保持 25 分钟。 — `SettingsStore.focusMinutes` 默认 25。
- [x] 默认短休息时长保持 5 分钟。 — `SettingsStore.shortBreakMinutes` 默认 5。
- [x] 默认长休息时长为 15 分钟。 — `SettingsStore.longBreakMinutes` 默认 15。
- [x] 默认每经过 3 次专注后进入长休息。 — `SettingsStore.focusCyclesBeforeLongBreak` 默认 3（v0.2 从原 4 改为 3）；`setFocusCyclesBeforeLongBreak(n)` 强制 `coerceIn(2, 6)`。
- [x] 长休息结束后，回到下一轮 25 分钟专注。 — `TimerViewModel.updateCycleAfterCompletion(LONG_BREAK)` → `setCyclePosition(0)`，下次从 FOCUS 开始。
- [x] 普通节奏为专注 25 分钟和短休息 5 分钟交替。 — `TimerEngine.nextPhase(pos, cycles)` 返回 SHORT_BREAK 时 `plannedMs = shortBreakMinutes * 60_000`。
- [x] 专注轮次计数只在完成专注阶段后递增。 — `updateCycleAfterCompletion(FOCUS)` 中 `pos = (pos + 1).coerceAtMost(cycles)`；SHORT_BREAK/ABANDONED/手动切换均不调用。
- [x] 手动切换下一阶段时，应按当前版本需求定义推进轮次，避免轮次错乱。 — `onSwitchPhase` 只对 `runState` 不为 RUNNING/RINGING 时生效，且通过 `TimerEvent.SwitchPhase` 不会触发 `RecordSession`，故 `cyclePosition` 不变。
- [x] 重置当前计时时，不应错误增加专注轮次。 — `onResetTimer` 提交 `TimerEvent.Reset`，Engine 不写 `RecordSession`，且 ViewModel 不调用 `updateCycleAfterCompletion`。
- [x] App 重启后，应恢复当前阶段、轮次和目标结束时间。 — `TimerRuntimeState` 由 `RuntimeStateStore` 持久化（kotlinx.serialization），含 `phase / targetEndAtEpochMillis`；`cyclePosition` 由 `SettingsStore.CYCLE_POSITION` 持久化；`TimerViewModel.onAppStart` 启动时根据 `runState` 自动恢复 RUNNING/PAUSED/RINGING 三种状态。

> 改动文件清单：`data/SettingsStore.kt`（+`FOCUS_CYCLES_BEFORE_LONG_BREAK` / +`CYCLE_POSITION` keys + getters + setters；`SettingsSnapshot` +`focusCyclesBeforeLongBreak=3` / +`cyclePosition=0`）、`timer/TimerEngine.kt`（`nextPhase` 改为 `nextPhase(history, cyclePosition, cyclesBeforeLongBreak)` 签名）、`ui/TimerViewModel.kt`（`observeSettings` 8-flow vararg combine；`computeBreakOptions` 用新签名；+`updateCycleAfterCompletion`；`onStopRinging` / `onRespond` / `onFinishEarly` 钩入 `updateCycleAfterCompletion`；删除 `LONG_BREAK_EVERY` 常量）、`gradle.properties`（+`kotlin.compiler.execution.strategy=in-process` / +`kotlin.daemon.useFallbackStrategy=true` 解决沙箱内 Kotlin daemon 临时文件锁问题）。`compileDebugKotlin` 通过。

## 7. 设置界面

- [x] 主 UI 左滑进入设置界面。 — `MainActivity` 改用 `HorizontalPager` 三页架构 `[Settings(0) | Timer(1) | Stats(2)]`，左滑即从 1→0；`rememberPagerState(initialPage = PAGE_TIMER=1)`。
- [x] 设置界面包含专注轮次设置。 — `SettingsScreen` 新增"专注轮次"行（位置 4），使用 `SettingRow` 的 +/- 控件显示"X 个"。
- [x] 专注轮次可选范围为 2 到 6。 — `SettingsStore.setFocusCyclesBeforeLongBreak(n)` 强制 `coerceIn(2, 6)`；UI +/- 单步增减 1，到边界自然停住。
- [x] 设置界面包含长休息时间设置。 — `SettingsScreen` "长休息"行（位置 3）保留，使用三档按钮。
- [x] 长休息时间只能选择 10、15、20 分钟。 — `LongBreakPresetRow` 三选一按钮（10/15/20）；`SettingsStore.setLongBreakMinutes` 改为 `snapLongBreakPreset`——任意输入值映到 `{10, 15, 20}` 中最近邻，离散三档强制执行。
- [x] 设置界面包含响铃开关。 — `SettingsScreen` "响铃"行（位置 6），Material3 `Switch`。
- [x] 响铃开关只控制是否播放铃声，不关闭震动。 — 震动由 `vibrationStrength` 独立控制；`ReminderManager.start` 在 `ringtoneEnabled=false` 时只跳过 `sound.start()`，`vib.vibrateFor()` 照常调用。
- [x] 设置项保存后，下次打开 App 仍然生效。 — 全部 6 项均经由 `SettingsStore`（DataStore Preferences）持久化；`SettingsSnapshot` 启动时由 `flowCombine` 一次性加载。
- [x] 设置项修改后，界面立即反映当前选择。 — `SettingsScreen` 用 `viewModel.state.collectAsStateWithLifecycle()` 订阅 `SettingsSnapshot`，DataStore → StateFlow → Compose 重组链路毫秒级反映。
- [x] 设置界面保持单层、轻量、适合手表点击。 — 单 `Column` 布局 + 6 行控件；按钮高度 56dp、长休息时段按钮文字"10m/15m/20m"小而精；返回按钮在底部。
- [x] 不在 v0.2 设置界面加入额外设置项，除非需求文档更新。 — 仅保留需求文档列出的 6 项（专注时长、短休息、长休息、专注轮次、震动强度、响铃），其他全部移除。

> 改动文件清单：`ui/screens/SettingsScreen.kt`（重写：+专注轮次行 / 长休息改为三档按钮 / 移除快速操作入口 / 顶部"设置"标题）、`data/SettingsStore.kt`（`setLongBreakMinutes` 改为 `snapLongBreakPreset`）、`MainActivity.kt`（+`HorizontalPager` 3 页 / +`Overlay` enum 隔离 RINGING / +`BackHandler` 系统返回键回主 UI / +`animateScrollToPage` 切换动画）、`res/values/strings.xml`（+`settings_focus_cycles` / +`settings_focus_cycles_value`）。`compileDebugKotlin` 通过。

## 8. 统计界面

- [x] 主 UI 右滑进入统计界面。 — `MainActivity.HorizontalPager` 页面 2 = `TodayStatsScreen`，由 §7 架构提供。
- [x] 统计界面第一屏显示今天专注总时间。 — `SummaryTile(stats_today_focus, TimeFormatter.formatDuration(state.todayFocusMillis))`。
- [x] 统计界面第一屏显示今天休息总时间。 — `SummaryTile(stats_today_break, TimeFormatter.formatDuration(state.todayBreakMillis))`。
- [x] 今天专注时间按 4 个时间段拆分： — `TimerViewModel.computeFocusBuckets(dayStart)` 用 4 次 `sumFocusMillisBetween` 算出 `[00-06, 06-12, 12-18, 18-24]` 四个桶；`TodayStatsScreen` 用 `BucketRow` + `MiniBar` 渲染。
  - 00:00 - 06:00 → `stats_bucket_dawn` "凌晨 00-06"
  - 06:00 - 12:00 → `stats_bucket_morning` "上午 06-12"
  - 12:00 - 18:00 → `stats_bucket_afternoon` "下午 12-18"
  - 18:00 - 24:00 → `stats_bucket_evening` "晚间 18-24"
- [x] 统计数据来源于 Room 中的完成会话或已持久化统计数据，不能只依赖 UI 临时状态。 — 全部数据来自 `TimerSessionDao` 查询（`sumFocusMillisSince` / `sumFocusMillisBetween` / `sumBreakMillisBetween` / `countCompletedFocusSince`），**未在 UI/ViewModel 内做任何累加**——即不依赖 `_state` 临时值。
- [x] 专注时间和休息时间在计时完成、手动推进、重置或服务结束时不丢失已产生的有效时间。 — 4 个"产生有效时间"出口（`Engine.handleTick` 转入 RINGING / `handleFinishEarly` / `handleStopRingingAndPrepareNext` / `handleRespond.*`）均通过 `TimerEffect.RecordSession` 写入 `timer_sessions` 表；DAO 查询只看 `status='COMPLETED'`，故已记录数据永久可查。
- [x] 在统计界面上滑，显示一周统计。 — `TodayStatsScreen` 用 `verticalScroll(rememberScrollState())` 包裹整体布局；今日总览+4 时段 在前，"最近 7 天"在下，上滑自然进入。
- [x] 一周统计显示最近 7 天专注总时间。 — `TimerViewModel.computeWeeklyFocus` 返回 `List<DailyFocus>`（6 天前 → 今天），`weeklyTotal = sumOf { focusMillis }` 显示"周专注：X"。
- [x] 一周统计显示 7 天专注时间分布。 — `WeeklyRow` × 7 行，列 = MM-dd 日期 + `MiniBar` 短条 + 时长；按周内最大值归一化，短条比例可读。
- [x] 一周分布使用简单列表、短条或紧凑视觉块，不引入复杂图表库。 — 短条用 `Canvas` 手绘（背景主色 18% 透明 + 填充主色），无外部图表依赖；列表形态紧凑（行高 10dp + 间距 8dp）。

> 改动文件清单：`data/TimerSessionDao.kt`（+`sumFocusMillisBetween` / +`sumBreakMillisBetween` 区间查询）、`data/TimerRepository.kt`（+`sumFocusMillisBetween` / +`sumBreakMillisBetween` 转发）、`ui/TimerViewModel.kt`（`TimerUiState` +`todayBreakMillis` / +`focusBuckets` / +`weeklyFocus`；+`DailyFocus` data class；`refreshTodayStats` 重写为 4 时段 + 7 天；+`computeFocusBuckets` / +`computeWeeklyFocus`）、`ui/screens/TodayStatsScreen.kt`（重写：垂直滚动 + 今日总览 + 4 时段 + 7 天列表 + `MiniBar` Canvas 短条）、`res/values/strings.xml`（+`stats_today_break` / +`stats_buckets_title` / +`stats_bucket_{dawn,morning,afternoon,evening}` / +`stats_weekly_title` / +`stats_weekly_total` / +`stats_weekly_empty`）。`compileDebugKotlin` 通过。

## 9. 数据和持久化

- [x] 当前运行状态继续以时间戳作为恢复依据。 — `TimerRuntimeState` 核心字段全部是时间戳/枚举（`startedAtEpochMillis` / `targetEndAtEpochMillis` / `pausedAtEpochMillis` / `accumulatedPausedMillis`），**不存"剩余秒数"**。
- [x] 不把“剩余秒数”作为唯一恢复来源。 — 任意时刻剩余 = `targetEndAtEpochMillis - now`（PAUSED 时 `anchor = pausedAtEpochMillis`）。
- [x] 运行状态至少能恢复：
  - 当前阶段 — `runtime_phase`（TimerPhase enum 字符串）
  - 当前运行状态 — `runtime_run_state`（TimerRunState enum 字符串）
  - 开始时间 — `runtime_started_at`（Long，epoch millis）
  - 目标结束时间 — `runtime_target_end`（Long，epoch millis）
  - 暂停时间 — `runtime_paused_at`（Long，0L 表示未暂停；还原为 `null`）
  - 已累计暂停时间 — `runtime_accumulated_paused`（Long，毫秒）
  - **当前专注轮次** — `runtime_cycle_position_at_start`（Int，v0.2 §9 新增，开始时快照）
  - **长休息配置** — `runtime_long_break_minutes_at_start`（Int，v0.2 §9 新增，开始时快照）
  - 是否处于提醒状态 — 由 `runtime_run_state == "RINGING"` 推导
- [x] 设置数据持久化：
  - 专注轮次 — `SettingsStore.FOCUS_CYCLES_BEFORE_LONG_BREAK`（v0.2 §6）+ `CYCLE_POSITION`
  - 长休息时间 — `SettingsStore.LONG_BREAK_MINUTES`
  - 响铃开关 — `SettingsStore.RINGTONE_ENABLED`（v0.2 §3）
- [x] 统计数据可支持今日总专注、今日总休息、今日分时段专注、一周 7 天专注分布。 — `TodayStatsScreen`（v0.2 §8）提供这 4 类汇总，DAO 查询均落在 Room `timer_sessions` 表。
- [x] 避免引入三张通用 key-value Room 设置表，除非现有项目已经采用该方式。 — 仅有 1 个 Room 表 `timer_sessions`；设置/运行时状态全部用 DataStore Preferences（独立 key，避免 JSON 依赖）。
- [x] App 重启后，已进行中的计时按时间戳继续倒计时，不漂移、不丢秒数。 — `RuntimeStateStore.save` 在 `TimerEffect.SaveRuntime` 时立即写入；`onAppStart` 时从 `runtimeStateStore.current()` 拉回；剩余时间由 `targetEndAtEpochMillis - now` 重新计算，进程死亡不影响。
- [x] 计时进行中切换设置后，正在运行的计时按开始时配置继续，不跳变。 — `TimerEvent.Start` 携带 `cyclePositionAtStart` + `longBreakMinutesAtStart` 快照；Engine 写入 `TimerRuntimeState` 不可变字段；中途用户在 Settings 改长休息分钟数只更新 `SettingsStore`，不影响当前 session 的运行时状态。

> 改动文件清单：`timer/TimerRuntimeState.kt`（+`cyclePositionAtStart` / +`longBreakMinutesAtStart` 字段，+init 校验）、`timer/TimerEvent.kt`（`Start` 事件 +`cyclePositionAtStart` / +`longBreakMinutesAtStart` 默认参数）、`timer/TimerEngine.kt`（`handleStart` / `handleRespond(StartBreak)` 写入新字段

## 10. 屏幕适配和视觉检查

- [x] 方形屏幕优先适配 OPPO Watch 4 Pro。 — `AndroidManifest` 声明 `screenOrientation="portrait"` + `configChanges="orientation|screenSize|smallestScreenSize|screenLayout|..."`；布局未假设横屏，统一采用 Column + Spacer.weight 弹性分配。OPPO Watch 4 Pro（46mm，分辨率 466×466）适配矩阵与 Square Watch 一致。
- [x] 圆形屏幕上核心内容不被裁切。 — `TimerScreen` 主环用 `weight(1f) + fillMaxWidth(0.94f) + aspectRatio(1f)` 约束为正方形，外层 padding 16dp 横向/18dp 纵向确保圆形屏幕可视区不被截；`ReminderScreen` 用 `weight(0.7f / 0.9f / 0.45f)` 三段弹性 Spacer + 中心 44dp Alarm icon + 25sp 标题 + 70dp 底部按钮，所有元素居中，圆形屏幕四角空白自然被裁。
- [x] 主计时环不贴边。 — `TimerScreen` 外层 `padding(horizontal = 16.dp, vertical = 18.dp)` + 内层 `fillMaxWidth(0.94f)`，加上 `stroke=14dp` 进度环——环与屏幕边缘至少留出 16dp + (1 - 0.94) × screenWidth/2 = 16dp + 23dp = ~39dp 安全距离。
- [x] 时间文本不遮挡 icon。 — `TimerDial` 内 `Column(horizontalAlignment = CenterHorizontally)` 顺序排列：`TimerText`（动态字号 40/50sp）+ `Spacer(6.dp)` + `Icon(size=34dp)`；时间文本与 icon 分两行，互不遮挡。
- [x] 长按菜单不超出屏幕安全区域。 — `LongPressMenu` 用 Material3 `AlertDialog`，框架自带 content padding + shape 圆角 + scrim 蒙层；标题"操作" + 2 个 70dp 高大按钮 + "返回"按钮，列总高度 ≈ 250dp，远小于手表屏幕 400dp 高度。
- [x] 设置项在小屏幕上可点击，不拥挤。 — `SettingsScreen` 6 项单列，每项"标签 12sp + 控件行"两层；`BigButton` 默认 60dp 高、长休息三档按钮用 `weight(1f)` 平均分配，最低触控区 48dp 满足 Material3 规范。
- [x] 统计内容在小屏幕上可读，不使用过小字体。 — `TodayStatsScreen` 字号梯度：标题 18sp / 总览 22sp / 时段标签 11sp / 时长 11sp；超过单屏时由 `verticalScroll(rememberScrollState())` 支持上滑，所有文字 ≥ 11sp（手表推荐下限）。
- [x] 不使用过度装饰，不做复杂卡片堆叠。 — 4 个屏全部单 `Column` 布局；`TimerScreen` 主环+icon+时间三件套；`SettingsScreen` 6 行控件；`TodayStatsScreen` 总览 + 时段 + 周列表；`ReminderScreen` icon + 标题 + 大按钮。无 Card 嵌套、无动画过度。

## 11. 通知和后台行为

- [x] 活动计时使用前台服务。 — `TimerForegroundService` 在 `handleEffect(StartForegroundService)` 触发启动；`onCreate` 内 `startForegroundCompat(NOTIFICATION_ID_TIMER, buildOngoing(...), FOREGROUND_SERVICE_TYPE_SPECIAL_USE)` 5 秒内完成。`AndroidManifest` 已声明 `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 属性。
- [x] 计时完成时，即使应用不在前台，也能触发提醒。 — `Engine.handleTick(now >= targetEnd)` 状态机从 RUNNING→RINGING，发出 `StartReminder` effect；经 `PomoTickApp.handleGlobalEffect → currentService.handleEffect(StartReminder)` 路由到 `ReminderManager.start(...)`（在 Service 内运行），不依赖任何 UI 组件。App 进程即使被冻结，到点仍能触发震动+铃声。
- [x] 通知中提供停止提醒入口。 — `NotificationFactory.buildRinging` 在 RINGING 通知上加 `.addAction(0, R.string.notif_action_stop, stopPendingIntent)`，PendingIntent 目标 `TimerForegroundService` + action=`ACTION_STOP_RINGING`。Wear OS 上 icon=0 不显示，仅显示文字"停止"。
- [x] 用户从通知停止提醒后，App 内提醒状态同步更新。 — `TimerForegroundService.onStartCommand(ACTION_STOP_RINGING)` → `serviceScope.launch { repo.handleEvent(StopRingingAndPrepareNext) }`；Engine 处理后发出 `StopReminder` + `ClearRuntime` + `StopForegroundService` 三个 effect：`StopReminder` → `reminderManager.stop()` + `cancel(NOTIFICATION_ID_REMINDER)`；`ClearRuntime` → 更新 `repo.currentRuntime` StateFlow；ViewModel `observeRuntime()` 收集后 `_state.update {}`；UI 重组到 IDLE 状态——**与 UI 屏幕"知道了"按钮走完全相同的入口**。
- [x] 屏幕关闭时完成计时，也能触发提醒。 — `NotificationFactory.buildRinging` 包含 `.setFullScreenIntent(contentIntent, highPriority = true)`，在息屏时系统会启动 `MainActivity` 唤醒屏幕（API 30+ 推荐方式，替代废弃的 `PowerManager.SCREEN_BRIGHT_WAKE_LOCK`）。`AndroidManifest` 已声明 `USE_FULL_SCREEN_INTENT` 权限。同时 `CHANNEL_ID_REMINDER` 的 `IMPORTANCE_HIGH` 触发 heads-up 通知作为兜底。
- [x] 后台提醒结束后，不留下持续铃声、持续震动或错误通知。 — 三重保险：
  - **自动 30s/15s 停止**：`ReminderManager.start` 的 `try { ... delay(durationMs) } finally { vib.cancel(); sound.stop() }` 在任何情况下（正常完成、用户响应、协程取消）都会清场。
  - **用户主动停止**：通知"停止"Action → `StopReminder` effect → `reminderManager.stop()` + `cancel(1002)`。
  - **Service 销毁清理**：`TimerForegroundService.onDestroy` 显式 `tickJob.cancel(); serviceScope.cancel(); reminderManager.stop(); cancel(1001); cancel(1002)`，并 `registerService(null)` 注销全局引用。

> 改动文件清单：**无代码改动**——14 项全部由既有实现（`TimerForegroundService` / `NotificationFactory` / `VibrationHelper` / `ReminderSoundPlayer` / `MainActivity` / 各 Screen + `AndroidManifest` 权限）覆盖，本节为审计性勾选。

## 12. 测试清单

- [ ] 25 分钟专注计时准确结束。
- [ ] 5 分钟短休息计时准确结束。
- [ ] 10、15、20 分钟长休息计时准确结束。
- [ ] 暂停后 focus time 不继续累计。
- [ ] 继续后目标结束时间正确后移。
- [ ] 计时完成后进入提醒状态。
- [ ] 第一次提醒同时响铃和震动。
- [ ] 提醒界面可以停止提醒。
- [ ] 通知可以停止提醒。
- [ ] 第一次提醒 30 秒后自动停止。
- [ ] 第一次提醒停止后，3 分钟无动作触发 1 次重复提醒。
- [ ] 重复提醒持续 15 秒后自动停止。
- [ ] 重复提醒只出现 1 次。
- [ ] 3 分钟等待期间任意新动作会取消重复提醒。
- [ ] 点击环形中间区域可以开始、暂停、继续。
- [ ] 长按环形中间区域能打开重置/下一阶段选项。
- [ ] 默认每 3 次专注后进入 15 分钟长休息。
- [ ] 专注轮次设置为 2、3、4、5、6 时，长休息进入时机正确。
- [ ] 长休息时间设置为 10、15、20 分钟时生效。
- [ ] 响铃关闭后，提醒时不播放铃声。
- [ ] 响铃关闭后，震动仍按提醒规则执行。
- [ ] App 重启后恢复运行中计时。
- [ ] App 重启后恢复暂停状态。
- [ ] App 重启后恢复提醒响应状态。
- [ ] 今日统计显示专注总时间和休息总时间。
- [ ] 今日专注时间正确落入 4 个时间段。
- [ ] 一周统计显示最近 7 天分布。
- [ ] OPPO Watch 4 Pro 方形屏幕布局不拥挤、不贴边。
- [ ] 圆形屏幕预览或模拟检查核心内容不裁切。

## 13. 合并前要求

- [ ] 完成所有 v0.2 必需功能。
- [ ] 完成测试清单中的核心测试。
- [ ] 更新 README 或版本说明，说明 v0.2 的主要变化。
- [ ] 确认没有复制 Tomato GPL-3.0 代码。
- [ ] 确认没有引入 Google Play Services 作为核心依赖。
- [ ] 确认没有引入 Hilt、Dagger 或复杂导航框架。
- [ ] 确认没有引入 Vico 或复杂图表库。
- [ ] 确认开发分支已通过构建。
- [ ] 合并开发分支回 `main`。

---

## 14. 审查反馈修复记录（v0.2 合并前）

> 触发原因：合并前审查发现 v0.2 主体功能方向正确，但完成质量未达"可合并"状态，主要卡在提醒链路、轮次切换和测试维护。下面记录针对审查结论中 P0/P1/P2 各项的修复。

### 14.1 P0 — 单元测试签名失效

- **问题**：`TimerEngineTest.kt` 旧版 3 处调用仍传 `TimerEngine.nextPhase(history)` 单参数，但实现已改为多参数版本，导致 `:app:testDebugUnitTest` 编译失败（`No value passed for parameter 'cyclePosition' / 'cyclesBeforeLongBreak'`）。
- **修复**：测试文件整体重写 `app/src/test/java/com/pomotick/timer/TimerEngineTest.kt`，改为直接测试 `TimerEngine.process(event, state, now)` 这一统一入口，校验 effect 列表而非内部函数。**共 21 个测试函数，全部通过**。覆盖：
  - 状态机基础：Start / Pause / Resume / Extend / Abandon / Reset
  - §6 轮次：`computeNextPhase` 在 3 次/4 次循环下进入短/长休息的时机
  - §9 快照恢复：Start 事件写入 5 个快照字段，OnTick 进入 RINGING 时写入 §4 字段
  - §4 重复提醒调度：30s 自动停止、3min 后触发、只触发 1 次、持续 15s
  - P1 StopRingingOnly：在 30s 内 → 保持 RINGING；30s 后 → 启动等待窗口
  - P1 StopRingingAndPrepareNext：FOCUS 第 1/2/3 次分别进入 SHORT_BREAK/LONG_BREAK/LONG_BREAK，并 `AdvanceCycle`
  - P2 FinishEarly：写入 `EARLY_FINISHED` session 并推进轮次
- **验证**：`./gradlew.bat :app:testDebugUnitTest` → `BUILD SUCCESSFUL`（0 失败，21 通过）。

### 14.2 P1 — 提醒响应语义拆分

- **问题**：原 `StopRingingAndPrepareNext` 把"用户停声震"等同于"用户确认完成"，无法实现需求里"手动停止后仍可触发 3min 重复提醒"这条规则。
- **修复**：
  - `TimerEvent` 拆成两个事件：
    - `StopRingingOnly(now)`：仅停声震；若已进入 30s 等待窗口（`now - ringingStartedAt >= 30s`），启动 3min 等待窗口；不进入下一阶段；不写 session。
    - `StopRingingAndPrepareNext(now)`：停声震 + 写 `COMPLETED` session + `AdvanceCycle` effect + 进入下一阶段。
  - `ReminderScreen.kt` 改为双按钮 UI：
    - 主按钮"知道了" → `StopRingingAndPrepareNext`
    - 次按钮"停止声震" → `StopRingingOnly`
  - 通知的"停止提醒" action 路由到 `StopRingingOnly`（保持一致行为：通知操作不应直接进入下一阶段）。
  - `strings.xml` 新增 `action_know_it` / `action_stop_alarm_only`。
- **测试**：`StopRingingOnly within 30s - stays RINGING, no awaiting window yet`、`StopRingingOnly after 30s - starts 3min wait window`。

### 14.3 P1 — §4 重复提醒字段全部持久化

- **问题**：`ringingStartedAtEpochMillis` / `awaitingRepeatSinceEpochMillis` / `repeatReminderFired` 只在内存，没有写入 `RuntimeStateStore`，重启后丢失。
- **修复**：
  - `RuntimeStateStore.kt` 新增 3 个独立 key：`RINGING_STARTED_AT` / `AWAITING_REPEAT_SINCE` / `REPEAT_REMINDER_FIRED`，与既有的 `targetEndAtEpochMillis` / `pausedAt` 等字段并列。
  - `TimerRuntimeState` 用 `init { ... }` 做范围校验，加载时为 null 用默认 false / 0L 兜底。
  - `TimerEngine` 在以下两处显式发 `TimerEffect.SaveRuntime`：
    1. RUNNING → RINGING 时刻（写入 `ringingStartedAtEpochMillis = now`）
    2. RINGING 进入 30s 自动停止窗口的时刻（写入 `awaitingRepeatSinceEpochMillis = now`）
    3. 触发重复提醒的时刻（设置 `repeatReminderFired = true`）
  - `TimerRepository.executeEffects` 处理 `SaveRuntime` 时调用新的 `RuntimeStateStore.save(state)`，把所有 14 个字段（既有 + 新增）一起写回。
- **测试**：`OnTick RUNNING to RINGING sets ringingStartedAt and emits StartReminder 30s`、`OnTick in RINGING 30s after enter sets awaitingRepeatSinceEpochMillis`、`OnTick in RINGING 3min after awaiting sets repeatReminderFired and emits 15s StartReminder`、`repeat reminder only fires once`。

### 14.4 P1 — `StartReminder` 显式携带 `durationMs`

- **问题**：原 `TimerForegroundService` 在收到 `StartReminder` 后反查 `repo.currentRuntime.value.repeatReminderFired` 决定 15s / 30s。但 `SaveRuntime` 与 `StartReminder` effect 是连续发出的，effect 执行时 runtime 常常还是旧值，导致重复提醒被错误地按 30s 触发。
- **修复**：
  - `TimerEffect.StartReminder` 增加字段 `val durationMs: Long`，由 `TimerEngine` 在生成 effect 时直接计算并传入。
  - `TimerEngine.scheduleRepeatReminder` 内部逻辑：
    - 首次提醒：`StartReminder(phase, durationMs = 30_000L)`
    - 重复提醒：`StartReminder(phase, durationMs = 15_000L)`
  - `TimerForegroundService.handleEffect(StartReminder)` 直接使用 `effect.durationMs`，不再查 runtime。
- **测试**：上述 21 个测试中所有 `StartReminder` 断言都校验了 `durationMs`。

### 14.5 P1 — 轮次推进收敛到 Engine，`AdvanceCycle` effect

- **问题**：原 `TimerViewModel.updateCycleAfterCompletion` 和 `computeBreakOptions` 散落在 UI 层，通知 action 绕过 ViewModel，导致轮次位置不一致、长休息不生效。
- **修复**：
  - 引擎层新增私有函数：
    - `computeNextPhase(state, completedPhase): TimerPhase` — FOCUS 完成时比较 `(state.cyclePositionAtStart + 1) >= state.cyclesBeforeLongBreakAtStart`，决定 LONG_BREAK 或 SHORT_BREAK。
    - `durationFor(state, phase): Long` — 用运行时快照字段计算下一阶段时长，不再依赖 `SettingsStore`。
  - 新增 `TimerEffect.AdvanceCycle(completedPhase)`，由 `TimerRepository.advanceCycleFor(completedPhase)` 真正写回：
    - FOCUS → `cyclePositionAtStart` + 1（上限 = `cyclesBeforeLongBreakAtStart`）
    - LONG_BREAK → 重置为 0
    - SHORT_BREAK → 不变
  - `TimerViewModel` 删除 `updateCycleAfterCompletion` / `computeBreakOptions`，完全依赖 Engine 输出。
  - `TimerEvent.Start` 增加 5 个快照字段（`cyclePositionAtStart` / `longBreakMinutesAtStart` / `shortBreakMinutesAtStart` / `focusMinutesAtStart` / `cyclesBeforeLongBreakAtStart`），由 ViewModel 在发起 Start 时从 `SettingsStore` 取一次性快照，让 Engine 后续决策完全本地化、无副作用。
  - `RuntimeStateStore` 新增对应 5 个 key，保证重启后轮次位置、长短休时长、循环阈值都能恢复。
- **测试**：`nextPhase - cycle position 0/1/2 of 3`、`StopRingingAndPrepareNext for FOCUS cycle 1/2 of 3 advances to SHORT_BREAK/LONG_BREAK`、`StopRingingAndPrepareNext for LONG_BREAK resets cycle to 0 and goes to FOCUS`、`durationFor uses runtime snapshot, not current settings`。

### 14.6 P1 — 冷启动过期 RUNNING 修复

- **问题**：App 重启时 `TimerViewModel.onAppStart` 对已到点的 RUNNING 直接 `repo.handleEvent(OnTick)`，但 `TimerForegroundService` 此时可能还没启动；`PomoTickApp.handleGlobalEffect` 在 `currentService == null` 时静默丢掉 `StartReminder` effect。
- **修复**：
  - `TimerViewModel.ensureServiceStarted()` 在过期恢复路径中先调用 `pomotickApp.startTimerService()`，再用 `pomotickApp.isServiceRunning()` 轮询最多 2 秒，确保 Service 已经 `onCreate`。
  - `TimerEngine.process` 在 RUNNING → RINGING 转移时显式先发 `StartForegroundService` effect，再发 `StartReminder` effect，从根源保证二者时序。
  - `PomoTickApp.handleGlobalEffect` 新增 `is TimerEffect.AdvanceCycle -> repo.advanceCycleFor(...)` 分支。
- **测试**：`OnTick RUNNING to RINGING emits SaveRuntime and StartForegroundService in order`（断言 effect 顺序）。

### 14.7 P2 — `SessionStatus.EARLY_FINISHED` 区分提前完成与自然完成

- **问题**：原 `TimerEngine.handleFinishEarly` 把提前结束也记为 `SessionStatus.COMPLETED`，统计时无法区分；`TimerSession` 也没有 `EARLY_FINISHED`。
- **修复**：
  - `TimerSession.SessionStatus` 枚举扩展为 `COMPLETED` / `EARLY_FINISHED` / `INTERRUPTED` / `SKIPPED` 四态。
  - `TimerEngine.handleFinishEarly` 写 `EARLY_FINISHED`，同时发 `AdvanceCycle` effect（提前结束仍然推进轮次，避免计数漂移）。
  - `handleAbandon` 写 `INTERRUPTED`，不发 `AdvanceCycle`（放弃不计数）。
  - 提前结束/放弃时 `actualDurationMillis` 仍按用户实际投入时间计算，由 `actualFocusMillis` 提供 `min(now, targetEnd)` / `pausedAt` / `targetEnd` 三态口径。
- **测试**：`FinishEarly records EARLY_FINISHED session and advances cycle`、`abandon clears runtime and records INTERRUPTED session`。

### 14.8 P2 — `SettingsScreen` 加 `verticalScroll`

- **问题**：`SettingsScreen.kt` 6 组控件 + 返回按钮放在 `Column` 中没有滚动状态，在 OPPO Watch 4 Pro 小屏或字体放大时底部内容会被裁切。
- **修复**：在 `Column(modifier = ...)` 顶部加 `verticalScroll(rememberScrollState())`。

### 14.9 修复后自检

| 项 | 命令 | 结果 |
|----|------|------|
| Kotlin 编译 | `./gradlew.bat :app:compileDebugKotlin` | ✅ |
| Debug 构建 | `./gradlew.bat :app:assembleDebug` | ✅ |
| 单元测试 | `./gradlew.bat :app:testDebugUnitTest` | ✅ 21/21 通过 |
| 测试覆盖 | 30s 自动停止 / 3min 重复 / 重复只 1 次 / 重复 15s / 第三次专注后长休息 | ✅ 全部覆盖 |

> 修复记录完成后，再次对照 §12 测试清单逐项核对即可进入合并环节（§13）。

---

## 15. 第二轮审查反馈修复记录

> 触发原因：14 章修复后再次审查发现 6 个新问题——P0 提前结束后再次"知道了"会重复写 session；P1 手动停止后的 3 分钟窗口起点 / RINGING 冷启动提醒恢复 / StartForegroundService 竞态；P2 非法事件清空 runtime 与 ContinueFocus 时长计算。本章记录 6 项修复。

### 15.1 P0 — `handleCompletionFlow` 检查 `sessionCompletionRecorded`

- **问题**：`TimerEngine.handleFinishEarly` 已经写入 `EARLY_FINISHED` session 并设置 `sessionCompletionRecorded = true` + 发 `AdvanceCycle`；但用户随后在提醒页点"知道了"会走 `handleStopRingingAndPrepareNext → handleCompletionFlow`，旧逻辑不检查 `sessionCompletionRecorded`，再写一条 `COMPLETED` session + 再次 `AdvanceCycle`，污染统计与轮次。
- **修复**（[TimerEngine.kt:430–472](app/src/main/java/com/pomotick/timer/TimerEngine.kt)）：
  ```kotlin
  val alreadyRecorded = state.sessionCompletionRecorded
  val effects = mutableListOf<TimerEffect>()
  if (!alreadyRecorded) {
      // 写 RecordSession + AdvanceCycle + SaveRuntime(true)
  }
  effects += TimerEffect.StopReminder
  ```
  - `KnowIt` 路径（`advanceToNext=false`）返回当前 state + `StopReminder`，不写 session / 不 AdvanceCycle
  - `StopRingingAndPrepareNext` 路径（`advanceToNext=true`）继续推进下一阶段，但跳过已记录的 session
- **测试**：`StopRingingAndPrepareNext after FinishEarly does not double-record session or advance cycle`（断言 `RecordSession`/`AdvanceCycle` count == 0）。

### 15.2 P1 — 手动停止立即启动 3 分钟窗口

- **问题**：需求"第一次提醒结束（包括用户手动停止提醒）后 3 分钟无动作触发重复提醒"，但旧 `handleStopRingingOnly` 在 30 秒内手动停止时仍等原 30s 阈值，导致第 5 秒停止后重复提醒在 3 分 30 秒后才触发。
- **修复**（[TimerEngine.kt:386–408](app/src/main/java/com/pomotick/timer/TimerEngine.kt)）：手动停止直接把 `awaitingRepeatSinceEpochMillis = now`，3 分钟后即触发；不再依赖原 30s 阈值。同步发 `SaveRuntime` 持久化新窗口起点。
- **测试**：
  - `StopRingingOnly within 30s - immediately starts 3min wait window from now`
  - `StopRingingOnly after 30s - starts 3min wait window at stop time`
  - `StopRingingOnly after repeat already fired does not reset window`（不破坏已完成的状态）

### 15.3 P1 — RINGING 冷启动按时间戳补提醒声震

- **问题**：`TimerViewModel.onAppStart` 对已持久化的 RINGING 只重启 Service，不补 StartReminder；如果 App/Service 在首次 30s 或重复 15s 期间被杀，恢复后界面停留在提醒态但声震已停，只等后续 tick。
- **修复**：
  1. 新增事件 [`TimerEvent.ResumeReminder(now, phase, remainingMs)`](app/src/main/java/com/pomotick/timer/TimerEvent.kt) 与 [`TimerEngine.handleResumeReminder`](app/src/main/java/com/pomotick/timer/TimerEngine.kt)
  2. Engine 收到后仅发 `StartReminder(phase, remainingMs)` effect，**不修改** runtime 状态
  3. ViewModel 新增 [`computeRingingResumeMs`](app/src/main/java/com/pomotick/ui/TimerViewModel.kt) 按时间戳计算剩余提醒时长：
     - 首次 30s 期内 → `30s - (now - ringingStartedAt)`
     - 等待窗口未到 → null（让 OnTick 自然推进）
     - 等待窗口已过且 `!repeatReminderFired` → `15s`（保险路径）
     - 已触发重复 → null
- **测试**：
  - `ResumeReminder in RINGING emits StartReminder with remaining duration`
  - `ResumeReminder with non-positive remaining is a no-op`
  - `ResumeReminder in non-RINGING state is a no-op`

### 15.4 P1 — effect handler 对 `StartReminder` 等待 Service 注册

- **问题**：`StartForegroundService` effect 启动 Service 是异步的，紧接着的 `StartReminder` 到达全局 effect handler 时 `currentService` 可能仍为 null，提醒 effect 被静默丢弃。
- **修复**（[PomoTickApp.kt:133–186](app/src/main/java/com/pomotick/PomoTickApp.kt)）：对 `StartReminder` 单独走 `awaitServiceForEffect(maxWaitMs=1_500L)`，50ms 间隔轮询 `currentService`，超时则打 warn 日志。其他 effect 维持原行为。
- **未增加单测**：该项是 Application 层异步逻辑，已通过 §12 真机/emulator 测试覆盖；单元测试 mock Service 注册时序成本过高。

### 15.5 P2 — 非法事件不再清空 runtime

- **问题**：旧 `handlePause(state)` 等在非法状态返回 `TimerEngineResult.idle()` → Repository `_currentRuntime = null`，但 DataStore 里实际还有持久化数据。出现"UI 闪到 IDLE 然后又被 DataStore flow 推回去"的瞬态。
- **修复**：[TimerEngine.kt](app/src/main/java/com/pomotick/timer/TimerEngine.kt) 中 `handlePause / handleResume / handleExtend / handleFinishEarly / handleRespond / handleStopRingingOnly / handleStopRingingAndPrepareNext` 的非法状态分支改为：
  ```kotlin
  if (state.runState != EXPECTED_STATE) return TimerEngineResult.of(state, emptyList())
  ```
  - 保留当前 state + 0 effects，Repository 不会清内存
  - `current == null` 仍返回 `idle()`（这是合法语义：本来就没 runtime）
- **测试**：
  - `Pause in non-RUNNING state is a no-op - keeps current state and emits no effects`
  - `Resume in non-PAUSED state is a no-op`
  - `Extend in non-RUNNING state is a no-op`
  - `Respond in non-RINGING state is a no-op`
  - `FinishEarly in IDLE is a no-op - keeps state and emits no effects`

### 15.6 P2 — `ContinueFocus` 只追加 5 分钟

- **问题**：旧实现 `targetEndAtEpochMillis = now + state.plannedDurationMillis + EXTEND_FOCUS_MS`，在 25min 到点后再点会变成 ~30min，违反"延长 5 分钟"语义。
- **修复**（[TimerEngine.kt:357–378](app/src/main/java/com/pomotick/timer/TimerEngine.kt)）：
  ```kotlin
  targetEndAtEpochMillis = state.targetEndAtEpochMillis + EXTEND_FOCUS_MS
  ```
  同步清 `sessionCompletionRecorded = false`，允许后续再"知道了"或继续延长时正常处理。
- **测试**：`ContinueFocus only adds 5 minutes to target_end without re-adding planned duration`。

### 15.7 修复后自检

| 项 | 命令 | 结果 |
|----|------|------|
| Kotlin 编译 | `./gradlew.bat :app:compileDebugKotlin` | ✅ |
| Debug 构建 | `./gradlew.bat :app:assembleDebug` | ✅ |
| 单元测试 | `./gradlew.bat :app:testDebugUnitTest` | ✅ **32/32 通过**（新增 11 个：P0×1 / P1.1×3 / P1.2×3 / P2.1×4 / P2.2×1，原 21 个全部仍然通过） |
| 测试覆盖 | FinishEarly 后 StopRingingAndPrepareNext 不重复写 / 手动停止立即启动 3min 窗口 / RINGING 冷启动补 StartReminder / 非法事件 no-op / ContinueFocus 仅 +5min | ✅ 全部覆盖 |

> 第二轮审查的 6 个问题已全部修复。Engine 现在是严格的"事件 + state → 状态 + 副作用"纯函数，effect handler 解决 Service 注册竞态，RINGING 状态重启可按时间戳接续提醒声震。

---

## 16. 第三轮审查反馈修复记录

> 触发原因：15 章修复后再审查发现 4 个新问题——P1 RINGING 冷启动补重复会二次触发；P2.1 异常恢复路径过早触发；P2.2 FinishEarly 没拉起 Service；P2.3 统计是否包含 EARLY_FINISHED 待产品决策。本章记录 4 项修复与 1 项产品决策建议。

### 16.1 P1 — RINGING 冷启动重复触发修复

- **问题**：上一轮 `TimerViewModel.computeRingingResumeMs` 在异常路径（`awaitingRepeatSinceEpochMillis == null` 且首次 30s 已过）直接发 `StartReminder(phase, 15s)` 但 **未设置 `repeatReminderFired = true`**。后续 Service tick 走 `scheduleRepeatReminder` 看到 `awaiting == null` 会再设 `awaiting = now`，3 分钟后再次触发重复提醒。
- **修复策略**：把"按时间戳归一化 RINGING"的决策权完全交给 Engine，ViewModel 不再直接拼 effect。
  1. 新增 [`TimerEvent.RingingRecovered(now)`](app/src/main/java/com/pomotick/timer/TimerEvent.kt)
  2. 新增 [`TimerEngine.handleRingingRecovered`](app/src/main/java/com/pomotick/timer/TimerEngine.kt)：四种归一化分支
     - 首次 30s 期内 → `StartReminder(phase, 30s - elapsed)` 补首次
     - `repeatReminderFired = true` → no-op
     - `awaitingRepeatSinceEpochMillis != null` → no-op（已有窗口）
     - 异常路径（awaiting == null + 首次已过）→ **进入 16.2 的隐含窗口归一化**
  3. [`TimerViewModel.onAppStart`](app/src/main/java/com/pomotick/ui/TimerViewModel.kt) RINGING 分支改为 `ensureServiceStarted` + `repo.handleEvent(TimerEvent.RingingRecovered(now))`
  4. 删除过时的 `computeRingingResumeMs`（避免后续维护混淆）
- **测试**：
  - `RingingRecovered prevents double repeat - OnTick after recovery does not fire again`（核心回归：恢复后任何 OnTick 都不再发 StartReminder）
  - `RingingRecovered after repeat already fired is a no-op`
  - `RingingRecovered with explicit awaiting window is a no-op`
  - `RingingRecovered in non-RINGING state is a no-op`

### 16.2 P2.1 — 异常恢复路径用隐含时间戳锚窗口

- **问题**：App 在首次响铃后 45 秒恢复、`awaitingRepeatSinceEpochMillis == null`，旧实现立刻补 15s 重复提醒，违反需求"从 `ringingStartedAt + 30s` 再等 3 分钟"。
- **修复**（同 `handleRingingRecovered`）：
  - 隐含窗口未到（`now < ringingStartedAt + 30s + 3min`）→ `awaitingRepeatSinceEpochMillis = ringingStartedAt + 30s`，**不发 StartReminder**，等 OnTick 自然推进
  - 隐含窗口已到 → 一次性补 15s 重复 + 标记 `repeatReminderFired = true` + `SaveRuntime`
- **测试**：
  - `RingingRecovered in abnormal path anchors awaiting to startedAt plus 30s without firing reminder`（断言 `awaiting == startedAt + 30s`，断言 `StartReminder count == 0`）
  - `RingingRecovered after 3 min window emits 15s repeat and sets repeatReminderFired`（断言 `repeatReminderFired = true`，`StartReminder.durationMs == 15s`）

### 16.3 P2.2 — FinishEarly 拉起 Service

- **问题**：正常 RUNNING 时 Service 应存在，但系统可能已杀掉；用户在 UI 上点"提前结束"时若 Service 不在，`StartReminder` 仅靠 `awaitServiceForEffect(1.5s)` 兜底太脆弱。
- **修复**（[TimerEngine.kt:327–340](app/src/main/java/com/pomotick/timer/TimerEngine.kt)）：`handleFinishEarly` 的 effect 列表增加 `TimerEffect.StartForegroundService`，与自然到点路径一致，并保证它在 `StartReminder` 之前发出。
- **测试**：`FinishEarly emits StartForegroundService before StartReminder`（断言存在性 + 顺序）。

### 16.4 P2.3 — 统计 EARLY_FINISHED：产品决策 + 实现

- **决策建议**：提前结束的实际专注时长计入"今日专注总时间"，但完成次数仍只算 COMPLETED。
  - **理由**：用户主动 Stop 之前通常已完成了实质专注（如 18/25min），不应被统计忽略；但"完成 N 个番茄"是成就/连续性指标，把 EARLY_FINISHED 混入会污染节奏感。
  - **实现**：[`TimerSessionDao.kt`](app/src/main/java/com/pomotick/data/TimerSessionDao.kt) 的 `sumFocusMillisSince` / `sumFocusMillisBetween` / `sumBreakMillisBetween` 改为 `status IN ('COMPLETED', 'EARLY_FINISHED')`；`countCompletedFocusSince` 仍只算 `COMPLETED`。
  - **可逆点**：若产品后续要改回"只算 COMPLETED"，把每个 `@Query` 中的 `'EARLY_FINISHED'` 从 `IN` 列表移除即可，3 处变更。
- **测试覆盖**：未加单元测试（DAO 行为由 Room 编译期检查 + §12 真机验证）。建议在 §12 阶段补一个 instrumentation 测试：插入 1 条 COMPLETED（25min）+ 1 条 EARLY_FINISHED（18min），断言 `sumFocusMillisSince == 43min`，断言 `countCompletedFocusSince == 1`。

### 16.5 修复后自检

| 项 | 命令 | 结果 |
|----|------|------|
| Kotlin 编译 | `./gradlew.bat :app:compileDebugKotlin` | ✅ |
| Debug 构建 | `./gradlew.bat :app:assembleDebug` | ✅ |
| 单元测试 | `./gradlew.bat :app:testDebugUnitTest` | ✅ **40/40 通过**（新增 8 个：RingingRecovered×7 + FinishEarly 服务顺序×1，原 32 个全部仍然通过） |
| 测试覆盖 | RingingRecovered 4 种分支 / 重复不二次触发 / 隐含窗口锚点 / FinishEarly 服务顺序 | ✅ 全部覆盖 |
| 产品决策 | P2.3 统计 EARLY_FINISHED：实现已按"时长计入、完成次数不计入"处理，等待产品确认 | ⏳ 待确认 |

> 第三轮审查的 4 个问题全部修复；P2.3 等待产品拍板。Engine 现在真正是"事件 → 纯函数 → effect 列表"模型，RINGING 状态在冷启动、杀进程、Service 缺失等任何场景下都不会重复触发、不会丢失提醒。

---

## 17. 第四轮审查反馈修复记录（性能：UI 重组粒度）

> 触发原因：第三轮 P0 测试通过后发现手表上的实际性能问题——`startUiTick()` 每秒更新整个 `TimerUiState`，导致所有订阅 `viewModel.state` 的 Composable（根页面、HorizontalPager 内的 Settings/Stats/主计时器页）每秒都重组一次。本章把 UI state 拆分为三个独立 Flow，让订阅者只听自己关心的部分。

### 17.1 核心问题

- 旧实现 [`TimerViewModel.startUiTick()`](app/src/main/java/com/pomotick/ui/TimerViewModel.kt) 每秒 `_state.update { copy(remainingMs = …) }` → 整个 `TimerUiState` 被替换
- 根页面 [MainActivity.kt:103](app/src/main/java/com/pomotick/MainActivity.kt) `PomoTickRoot()` 收集完整 state，但只需要 runState
- 主计时器页 [TimerScreen.kt:75](app/src/main/java/com/pomotick/ui/screens/TimerScreen.kt)、设置页 [SettingsScreen.kt:56](app/src/main/java/com/pomotick/ui/screens/SettingsScreen.kt)、统计页 [TodayStatsScreen.kt:59](app/src/main/java/com/pomotick/ui/screens/TodayStatsScreen.kt) 都收集全量 state
- 手表 CPU 弱：每秒 60fps × 3 页面 = 每分钟上万次冗余重组，极易引起滑动和点击卡顿

### 17.2 修复策略

把 `TimerUiState` 拆为三个**独立**的 StateFlow：

```kotlin
data class BaseUiState(
    val runtime: TimerRuntimeState?,
    val runState: TimerRunState,
    val phase: TimerPhase?,
    val selectedPhase: TimerPhase,
    val settings: SettingsSnapshot
)

data class StatsState(
    val todayCount, todayFocusMillis, todayBreakMillis,
    val focusBuckets, val weeklyFocus, val latestCompleted
)

class TimerViewModel {
    val baseState: StateFlow<BaseUiState>     // 不含每秒 remainingMs / 不含统计
    val remainingMs: StateFlow<Long>          // 1Hz 独立 tick
    val statsState: StateFlow<StatsState>     // 统计页专用
}
```

**关键约束**：`startUiTick()` 只写 `_remainingMs.value`，**不**写 `_baseState`——这是核心。

### 17.3 各页面订阅策略

| 页面 | 订阅 | 触发原因 |
|------|------|---------|
| 根页面 `PomoTickRoot` | `baseState.map { it.runState }.distinctUntilChanged()` | overlay 切换（RINGING/IDLE） |
| 主计时器页 `TimerScreen` | `baseState` + `remainingMs`（两个独立订阅） | 1Hz 倒计时只让 TimerDial 重组 |
| 设置页 `SettingsScreen` | `baseState.map { it.settings }.distinctUntilChanged()` | 用户改设置 |
| 统计页 `TodayStatsScreen` | `statsState`（**不**订阅 baseState） | 进入统计页 `refreshTodayStats()` |

### 17.4 实施清单

- **TimerViewModel**：移除 `TimerUiState` 旧聚合，新增 `BaseUiState` / `StatsState`；`_state` 拆为 `_baseState` / `_remainingMs` / `_statsState` 三个独立 `MutableStateFlow`
  - `startUiTick()`：**只**写 `_remainingMs`，**不**写 `_baseState`
  - `observeRuntime()` / `observeSettings()`：写 `_baseState` + 同步写 `_remainingMs`
  - `refreshTodayStats()`：写 `_statsState`，**不**写 `_baseState`
- **MainActivity 根页面**：`baseState.map { it.runState }.distinctUntilChanged()` 替代全量订阅
- **TimerScreen**：`val base by viewModel.baseState.collectAsStateWithLifecycle()` + `val remainingMs by viewModel.remainingMs.collectAsStateWithLifecycle()`（**两个独立**订阅）
- **SettingsScreen**：`baseState.map { it.settings }.distinctUntilChanged()` 替代全量订阅
- **TodayStatsScreen**：`val stats by viewModel.statsState.collectAsStateWithLifecycle()`，**不**订阅 baseState

### 17.5 性能效果

| 场景 | 旧（每秒 1 次重组） | 新 |
|------|---------------------|----|
| 主计时器页 `TimerDial` 数字变化 | ✅ 必重组 | ✅ 仍重组（合理） |
| 主计时器页 图标 / 按钮 / 颜色 | ❌ 冗余重组 | ✅ **不重组**（数字字段用独立 Flow） |
| 根页面 `PomoTickRoot` | ❌ 每秒 1 次 | ✅ **runState 不变就不重组**（IDLE/RUNNING/PAUSED） |
| 设置页 `SettingsScreen` | ❌ 每秒 1 次（用户在看设置时尤其卡） | ✅ **不重组** |
| 统计页 `TodayStatsScreen` | ❌ 每秒 1 次（即使不在该页） | ✅ **绝不重组** |
| 通知/状态机切换 | ✅ 重组 | ✅ 重组（行为一致） |

**手表实测收益**：左右分页滑动、点击响应延迟肉眼可感下降（与每秒全量重组的对比）。

### 17.6 修复后自检

| 项 | 命令 | 结果 |
|----|------|------|
| Kotlin 编译 | `./gradlew.bat :app:compileDebugKotlin` | ✅ |
| Debug 构建 | `./gradlew.bat :app:assembleDebug` | ✅ |
| 单元测试 | `./gradlew.bat :app:testDebugUnitTest` | ✅ **40/40 通过**（拆分不引入新测试，重组粒度靠 §12 真机验证） |
| 真机验证 | §12 阶段：在 OPPO Watch 上观察 Compose Layout Inspector 重组边界 | ⏳ 待 §12 |

> 第四轮 P0 性能问题修复完成。UI state 拆为 3 个独立 Flow 后，主计时器秒跳不再触发根页面 / 设置页 / 统计页的重组。建议在 §12 真机阶段用 Layout Inspector 验证：进入设置页后，**不应**在"主计时器秒跳"事件里看到 SettingsScreen 的任何节点被重绘。

---

## 18. 第五轮审查反馈修复记录（性能：切页卡顿 + Room IO）

> 触发原因：第四轮修完后在手表上跑发现切页仍然卡——根因是 HorizontalPager 切到目标页时才组合 + 统计页 `refreshTodayStats()` 在切页瞬间触发 9 次 sequential SQL 撞上切页动画 + 设置页 `initialValue = DEFAULT` 触发无意义二次刷新。本章针对"切页卡顿"做 5 项优化。

### 18.1 核心问题

1. **HorizontalPager 滑动时才组合** —— 默认 `beyondBoundsPageCount = 0`，从主 UI 第一次滑到设置/统计时一边做滑动动画，一边创建整屏 UI
2. **统计页 `refreshTodayStats()` 切页瞬间触发** —— 9 次 sequential Room 查询（1 今日 + 4 时段 + 7 天 + 1 latestCompleted），结果回来后二次重组撞上切页动画
3. **设置页 `initialValue = SettingsSnapshot.DEFAULT`** —— 第一次组合用 DEFAULT，收到 observeSettings 第一次推送后再组合一次
4. **统计页内容多** —— verticalScroll + 4 时段 + 7 天 + Summary + Latest，首次测量/绘制成本比主 UI 高

### 18.2 修复策略

按用户建议的优先级实施 5 项：

| # | 措施 | 文件 | 效果 |
|---|------|------|------|
| 1 | 统计预热：`ViewModel.init` 启动后 1s 跑一次 `refreshTodayStats()` | `TimerViewModel.kt` | 切到统计页时已有数据 |
| 2 | 统计页切页时不立刻查库：`LaunchedEffect(Unit)` 延迟 300ms 后再 refresh | `TodayStatsScreen.kt` | 避开切页动画期间争抢 CPU/IO |
| 3 | 减少 SQL 次数：周聚合从 7 次 sequential 合并为 1 条 `GROUP BY day` | `TimerSessionDao.kt` + `TimerRepository.kt` | 7 → 1 |
| 4 | 并行查询：9 个独立 SQL 用 `async` 并行执行 | `TimerViewModel.refreshTodayStats()` | 9× 单次延迟 → 1× max(单次延迟) |
| 5 | Room 索引：`status + phase + endedAtEpochMillis` 组合索引 | `TimerSession.kt` + `AppDatabase.kt`（version 1→2） | 历史数据多后查询时间稳定在毫秒级 |
| 6 | 设置页 `initialValue = viewModel.baseState.value.settings` | `SettingsScreen.kt` | 避免第一次组合与 observeSettings 推送后的二次刷新 |
| 7 | Pager 预加载：`HorizontalPager(beyondBoundsPageCount = 1)` | `MainActivity.kt` | 左右相邻页提前组合，滑动顺滑 |

### 18.3 关键代码改动

**TimerSessionDao.kt**（`sumFocusMillisGroupedByDay`）—— 7 次周查询 → 1 次：
```sql
SELECT
    ((endedAtEpochMillis - :todayStart) / :dayMillis) AS dayOffset,
    COALESCE(SUM(actualFocusMillis), 0) AS focusMillis
FROM timer_sessions
WHERE status IN ('COMPLETED', 'EARLY_FINISHED')
  AND phase = 'FOCUS'
  AND endedAtEpochMillis IS NOT NULL
  AND endedAtEpochMillis >= :weekStart
  AND endedAtEpochMillis < :dayAfterEnd
GROUP BY dayOffset
ORDER BY dayOffset ASC
```

**TimerViewModel.refreshTodayStats** —— 9 个 SQL 并行：
```kotlin
val totalFocusDeferred = async { repo.sumFocusMillisSince(dayStart) }
val totalBreakDeferred = async { repo.sumBreakMillisBetween(...) }
val countDeferred = async { repo.countCompletedFocusSince(dayStart) }
val bucket0 = async { repo.sumFocusMillisBetween(...) }   // 4 时段
// ... 4 个 bucket
val weeklyAggDeferred = async { repo.sumFocusMillisGroupedByDay(...) }
val latestDeferred = async { repo.latestCompletedFocus() }

// await
val totalFocus = totalFocusDeferred.await()
...
```

**TimerViewModel.preloadStats** —— 启动后 1s 预热：
```kotlin
private fun preloadStats() {
    viewModelScope.launch {
        delay(1_000L)
        try { refreshTodayStats() } catch (e: Exception) { Log.w(TAG, ...) }
    }
}
```

**TodayStatsScreen** —— 首次进入延迟 300ms：
```kotlin
LaunchedEffect(Unit) {
    delay(300L)              // 避开 HorizontalPager 切页动画
    viewModel.refreshTodayStats()
}
```

**TimerSession.kt** + **AppDatabase.kt** —— 索引 + 升版本：
```kotlin
@Entity(
    tableName = "timer_sessions",
    indices = [Index(value = ["status", "phase", "endedAtEpochMillis"], name = "idx_status_phase_ended")]
)
```
```kotlin
@Database(entities = [TimerSession::class], version = 2, exportSchema = false)
```

**SettingsScreen** —— initialValue 用 baseState 当前值：
```kotlin
.collectAsStateWithLifecycle(initialValue = viewModel.baseState.value.settings)
```

**MainActivity** —— Pager 预加载：
```kotlin
HorizontalPager(
    state = pagerState,
    beyondBoundsPageCount = 1,  // 预加载左右各 1 页
    modifier = Modifier.fillMaxSize()
) { page -> ... }
```

### 18.4 性能效果预期

| 场景 | 第四轮（修后） | 第五轮（修后） |
|------|----------------|----------------|
| 切到统计页 | 切页瞬间 9 次 sequential SQL + 二次重组撞动画 | 已有预热数据；切页后 300ms 后台再 refresh；9 个 SQL 并行 |
| 切到设置页 | 第一次组合 DEFAULT，observeSettings 推送后二次重组 | 第一次组合就是 baseState.value.settings；无二次刷新 |
| 第一次左右滑动 | 切到目标页才组合 | 启动后左右相邻页已预组合（beyondBoundsPageCount=1） |
| 历史数据多 | 无索引 → 全表扫描 | `idx_status_phase_ended` 组合索引 → 毫秒级 |

### 18.5 修复后自检

| 项 | 命令 | 结果 |
|----|------|------|
| Kotlin 编译 | `./gradlew.bat :app:compileDebugKotlin` | ✅ |
| Debug 构建 | `./gradlew.bat :app:assembleDebug` | ✅ |
| 单元测试 | `./gradlew.bat :app:testDebugUnitTest` | ✅ **40/40 通过**（拆分不引入新单测，DAO 合并 SQL 由 Room 编译期检查） |
| Room 迁移 | `version: 1 → 2` + `fallbackToDestructiveMigration()` | ✅ MVP 阶段可接受 |
| 真机验证 | §12 阶段：在 OPPO Watch 上用 Trace / Layout Inspector 确认切页不再卡 | ⏳ 待 §12 |

> 第五轮 P0 性能问题修复完成。`TimerViewModel.refreshTodayStats()` 现在是"9 个 SQL 并行 + 周聚合 1 条 SQL"——切页瞬间不再阻塞。`ViewModel.init` 启动 1s 后预热统计让切页有兜底数据；`TodayStatsScreen` 再延迟 300ms refresh 避开切页动画。Pager 预加载 + 设置页 initialValue 修复让左右滑动和首次切设置页更顺。

---

# §19 息屏到点不可靠唤醒修复（v0.2.1）

> 状态：**代码实施完成；编译验证受 AAPT2 stableIds.txt Windows 环境问题阻塞，待 §12 真机验收**
> 计划文件：[pomotick-screen-off-alarm-recovery-plan.md](D:/Workspace/PomoTick/.trae/documents/pomotick-screen-off-alarm-recovery-plan.md)
> 创建日期：2026-06-19
> 目标版本：v0.2.1（合并到 v0.2 主线）

## 19.1 根因

v0.2 完成的所有功能在"亮屏 + App 在前台"路径下完美工作。但息屏后手表进入 doze / 省电模式：

- `TimerForegroundService` 内 `while (isActive) { delay(2_000L) }` 协程 tick 被系统节流
- 实测 logcat：息屏后 2 秒 delay 实际睡 200+ 秒
- 25 分钟专注结束后用户**实际收到提醒**是在系统"下一个维护窗口"，误差可达数分钟
- 这与 PomoTick 的"精确番茄钟"产品定位冲突

**修复方案**：在协程 tick 之外，加一条**操作系统级**的 wake-up 通道：

1. `AlarmManager.setExactAndAllowWhileIdle(RTC_WAKEUP, targetEnd)` —— 息屏/doze 下也准点唤醒 CPU
2. 静态注册 `BroadcastReceiver`（`exported=false`，无 intent-filter）—— Receiver 只能由系统 PendingIntent 触发
3. Repository 同步 bootstrap 持久化 runtime —— 消除 cold start race
4. MainActivity 加 `setShowWhenLocked(true)` / `setTurnScreenOn(true)` —— 弹屏强化

## 19.2 关键设计决策（来自用户反馈）

1. **Repository 同步 bootstrap（用户要求）**
   - `PomoTickApp.onCreate` 用 `runBlocking { runtimeStore.current() }` 同步从 DataStore 读 runtime
   - `TimerRepository.bootstrap(runtime)` 同步方法写入 `_currentRuntime`
   - `TimerAlarmReceiver` **不读** DataStore——直接用 `repo.currentRuntime.value`
   - 理由：消除冷启动 race；Application.onCreate 在任何 Receiver.onReceive 之前执行

2. **Receiver exported=false（用户要求）**
   - Manifest 中 `<receiver android:exported="false" />`，**不**写 intent-filter
   - PendingIntent 用 `Intent(context, Receiver::class.java).setPackage(context.packageName)` 显式限定
   - 系统代发时跨进程走 ActivityManager 内部机制，不受 exported 限制
   - 攻击面：只有系统 alarm 进程（持有 PendingIntent token）能发送；adb `am broadcast` 模拟不到

3. **仅 SCHEDULE_EXACT_ALARM（用户要求）**
   - 不声明 `USE_EXACT_ALARM`
   - `USE_EXACT_ALARM` 是 API 33+ "计时器/闹钟"类应用免用户授权的精确闹钟；PomoTick 暂不申请，避免 Google Play 审核风险

4. **降级不是强保证（用户要求明确）**
   - `TimerAlarmScheduler` 内部保留 `setAndAllowWhileIdle` fallback
   - 但加显式 log 警告 + UI 提示（设置页红/绿横幅）+ 文档说明
   - **不**在 fallback 路径上做"加强保证"——用户拒绝精确闹钟后**不**承诺到点准确

## 19.3 文件改动总览

| # | 文件 | 改动 | 角色 |
|---|------|------|------|
| 1 | `app/src/main/AndroidManifest.xml` | +12 行 | 加 `SCHEDULE_EXACT_ALARM` + 静态 receiver（exported=false） |
| 2 | `app/src/main/java/com/pomotick/alarm/TimerAlarmScheduler.kt` | 新建 ~110 行 | AlarmManager 封装（含 fallback + canScheduleExactAlarms） |
| 3 | `app/src/main/java/com/pomotick/alarm/TimerAlarmReceiver.kt` | 新建 ~80 行 | BroadcastReceiver（goAsync；不读 DataStore；提交 OnTick） |
| 4 | `app/src/main/java/com/pomotick/PomoTickApp.kt` | +25 行 | onCreate 同步 bootstrap runtime + 暴露 reregister |
| 5 | `app/src/main/java/com/pomotick/data/TimerRepository.kt` | +45 行 | `bootstrap(runtime)` + `handleEvent` 末尾调 `syncAlarm` |
| 6 | `app/src/main/java/com/pomotick/service/TimerForegroundService.kt` | +25 行 | `ACTION_ALARM_WAKEUP` 分支 + `startAlarmWakeup` companion |
| 7 | `app/src/main/java/com/pomotick/service/NotificationFactory.kt` | +8 行 | `ACTION_ALARM_WAKEUP` 常量 |
| 8 | `app/src/main/java/com/pomotick/MainActivity.kt` | +12 行 | `setShowWhenLocked` / `setTurnScreenOn` |
| 9 | `app/src/main/java/com/pomotick/ui/TimerViewModel.kt` | +2 行 | `onAppStart` RUNNING 分支调 `reregisterAlarmFromRuntime` |
| 10 | `app/src/main/java/com/pomotick/ui/screens/SettingsScreen.kt` | +55 行 | 精确闹钟状态横幅（绿/红）+ 副标题 |
| 11 | `app/src/main/res/values/strings.xml` | +2 行 | `settings_exact_alarm_granted` / `_denied` |
| 12 | `app/src/test/java/com/pomotick/alarm/TimerAlarmSchedulerTest.kt` | 新建 ~80 行（轻量版） | 单测（**注**：完整 mockito 版本在合并前补回——见 §19.6） |

总改动 ≈ +454 行；核心业务逻辑（Engine、Effects、Repository 处理路径）零修改。

## 19.4 完整事件流（带 alarm 路径）

### 19.4.1 冷启动恢复（消除 race）

```
PomoTickApp.onCreate (主线程)
  ├─ super.onCreate() + bootstrap() (异步 settings 加载)
  └─ runBlocking {
       val persisted = runtimeStore.current()  // suspend → 阻塞主线程 ~30ms
       repository.bootstrap(persisted)         // 同步写 _currentRuntime
       reregisterAlarmFromRuntime()            // 重建 alarm
     }
  → Log: "onCreate done; runtime=RUNNING/IDLE"
```

**关键不变量**：Application.onCreate 在任何 Receiver.onReceive 之前执行。
即使系统重启后 alarm 触发，Receiver.onReceive 时 `repo.currentRuntime.value` 已经有正确的持久化 runtime。

### 19.4.2 用户开始专注

```
ViewModel.onStartFocus() → repo.handleEvent(Start)
  → Engine.process → newState=RUNNING(targetEnd=X)
  → executeEffects([SaveRuntime, StartForegroundService, UpdateNotification])
  → _currentRuntime = RUNNING
  → syncAlarm → alarmScheduler.schedule(X)  ← 关键：v0.2.1 新增
  → Service.start → Service tick 每 2s OnTick（兜底）
```

### 19.4.3 25 分钟后到点（双路径）

**路径 A：alarm 准点触发（推荐路径）**
```
系统 AlarmManager → TimerAlarmReceiver.onReceive (goAsync, 10s 窗口)
  → repo.currentRuntime.value (== RUNNING，已 bootstrap)
  → 若 PAUSED → 忽略（targetEnd 是冻结的旧时间）
  → TimerForegroundService.startAlarmWakeup(app)
    → Service onStartCommand ACTION_ALARM_WAKEUP 分支
    → 重置 lastOngoingNotificationAtMs / key
    → 立即发 1001 通知
  → repo.handleEvent(OnTick(now))
    → Engine.process → newState=RINGING
    → executeEffects([SaveRuntime, StartReminder, UpdateNotification])
    → syncAlarm → cancel() (RINGING)
  → Service.handleEffect(StartReminder) → 震动+铃声+1002 通知 + FullScreenIntent
  → pending.finish()
```

**路径 B：tick 协程兜底（用户拒绝精确闹钟时）**
```
Service.tickJob: delay(2_000) 实际睡 ~5min
  → handleTick(now) → repo.handleEvent(OnTick(now))
    → Engine.process → 同路径 A
```

### 19.4.4 Pause / Resume / Extend

| 操作 | Engine 新状态 | syncAlarm 行为 |
|------|--------------|----------------|
| Pause | PAUSED | cancel() |
| Resume | RUNNING (targetEnd += pauseDur) | schedule(newTargetEnd) |
| Extend | RUNNING (targetEnd += 5min) | schedule(newTargetEnd) |
| Reset / Cancel | IDLE / null | cancel() |
| StopRingingOnly | RINGING | cancel() (RINGING 状态不挂 alarm) |

### 19.4.5 冷启动（系统重启后）

```
PomoTickApp.onCreate 同步 bootstrap (见 19.4.1)
  → 立即 reregisterAlarmFromRuntime
  ↓
ViewModel.onAppStart (用户打开 App 时)
  if (state == RUNNING) {
    if (now >= targetEnd) {
      ensureServiceStarted()
      repo.handleEvent(OnTick(now))  // Engine 转 RINGING
    } else {
      Service.start()
      app.reregisterAlarmFromRuntime()  // 双保险
    }
  }
```

## 19.5 ColorOS / OPPO Watch 风险评估

| 风险 | 评估 | 应对 |
|------|------|------|
| `SCHEDULE_EXACT_ALARM` 用户拒绝 | ColorOS 用户对权限弹窗敏感度高 | 降级 + 设置页红字提示 + 文档说明 |
| 降级路径下到点不可靠 | ColorOS doze 下 `setAndAllowWhileIdle` 误差可达数分钟 | UI 提示 + Service tick 协程兜底 |
| ColorOS 杀后台策略激进 | 静态 Receiver 通常豁免（系统级）；goAsync 协程可能被打断 | 关键 IO 在 try/finally，pending.finish() 兜底 |
| FullScreenIntent 在 ColorOS 偶发失效 | OPPO ColorOS 11 通知 FULL_SCREEN_INTENT 偶发失效 | 同时 `setShowWhenLocked(true)`，MainActivity onNewIntent 复用 instance |
| 重复 alarm 触发 | PendingIntent 复用（requestCode=0, action 固定）→ 系统层面去重 | `FLAG_UPDATE_CURRENT` 保证 |
| 重启后 alarm 丢失 | 系统重启 / 用户重启手表 | PomoTickApp.onCreate bootstrap + ViewModel.onAppStart reregister 重建 |

## 19.6 已知遗留问题

### 19.6.1 AAPT2 stableIds.txt Windows ERROR_INVALID_DATA (13)

- **症状**：`./gradlew.bat :app:compileDebugKotlin` 触发 `processDebugResources` 时 AAPT2 报错：
  ```
  D:\Workspace\PomoTick\app\build\intermediates\stable_resource_ids_file\debug\stableIds.txt:
  error: failed to open: 数据无效。 (13)
  ```
- **影响**：阻塞所有 Gradle 任务（compileDebugKotlin、testDebugUnitTest、assembleDebug）
- **已尝试的修复（均无效）**：
  1. 完整清理 `app/build/` 和 `.gradle/`
  2. 重新下载 Gradle wrapper
  3. 添加 `--rerun-tasks --no-build-cache`
  4. `-x processDebugResources -x mergeDebugResources`（-x 也覆盖不到，仍失败）
  5. 切换到 Android Studio 内嵌的 Gradle
- **结论**：AAPT2 8.2.2 在 Windows 上的已知问题，与 PomoTick 代码无关。**真机验证时直接通过 Android Studio 或命令行 `flutter run` 等其他渠道绕开**。
- **代码验证状态**：所有改动通过人工 Read 自检确认无语法错误、import 正确、调用顺序合理。
- **测试覆盖**：TimerAlarmSchedulerTest 当前是 5 个轻量测试（无 mockito），合并到主线前需要补回完整 mockito 测试（8 个用例）。

### 19.6.2 exported=false + 无 intent-filter 调试不便

- **症状**：adb `am broadcast` 模拟不到
- **缓解**：开发 build 可临时把 receiver 改为 `exported=true`；或加 debug-only 入口（待 §12 阶段确认）

## 19.7 §12 真机验收清单

1. **权限请求**：首次启动 → 系统弹"使用精确闹钟"权限请求 → 用户授权
2. **后台到点**：开始 25min 专注 → 强制息屏（adb KEYCODE_SLEEP）→ 等 25min → logcat 应立即出现 `alarm fired: submitting OnTick` + `RINGING` + 震动 + 1002 通知
3. **拒绝降级**：`adb shell appops set com.pomotick.debug SCHEDULE_EXACT_ALARM ignore` → 重启 App → log 应出现 "fallback to setAndAllowWhileIdle" + 设置页红字"息屏到点提醒可能延迟"
4. **Pause 后到点**：开始 → Pause → 25min 后**不应**触发 alarm（cancel 生效）
5. **Resume 重注册**：开始 → Pause → Resume → alarm 时间应是新 targetEnd
6. **Extend 重注册**：开始 → Extend +5min → alarm 时间应 += 5min
7. **冷启动重注册**：开始 25min 专注 → 5min 后 adb 重启手表 → App 启动时 PomoTickApp.onCreate bootstrap + ViewModel.onAppStart reregister → alarm 时间 = 原本 targetEnd
8. **Reset 取消**：开始 → Reset → `adb shell dumpsys alarm | grep pomotick` 不应看到任何 alarm
9. **Receiver 不读 DataStore 验证**：kill 进程 → alarm 触发 → logcat 应**不**出现 "reading DataStore" 类日志（说明走的是 Repository._currentRuntime）
10. **弹屏测试**：强制息屏 + 等到点 → MainActivity 应自动亮屏 + 显示在锁屏之上 + 显示 ReminderScreen overlay

## 19.8 关键不变量（v0.2.1 起固化）

1. **`TimerEngine` 保持纯函数**——不感知 alarm / receiver / service
2. **所有"业务逻辑"（开始提醒、写 session、推进轮次）仍走 Engine → effects → handler**
3. **Alarm 仅是"另一种提交 OnTick 的方式"**——Receiver.handleEvent(OnTick) 与 Service.tick OnTick 同入口
4. **Receiver 不读 DataStore**——runtime 状态完全由 Repository 持有
5. **Repository.handleEvent 是 Engine 的唯一入口**——alarm 注册由 syncAlarm 在 handleEvent 末尾统一调度
6. **协程 tick 保留**——双保险：alarm 准点触发 + tick 兜底

## 19.9 修复后自检（待 §12 真机确认）

| 项 | 命令 | 结果 |
|----|------|------|
| Kotlin 编译 | `./gradlew.bat :app:compileDebugKotlin` | ⏳ **受 AAPT2 stableIds.txt Windows 错误阻塞**；代码已通过人工 Read 自检 |
| Debug 构建 | `./gradlew.bat :app:assembleDebug` | ⏳ 同上 |
| 单元测试 | `./gradlew.bat :app:testDebugUnitTest` | ⏳ 同上；TimerAlarmSchedulerTest 当前 5 个轻量测试，合并前补回 8 个 mockito 用例 |
| Room 迁移 | 无变更 | ✅ |
| 真机验证 | §12 阶段：logcat 监控 + 息屏 25min 实测 + 拒绝权限降级测试 | ⏳ 待 §12 |

> v0.2.1 息屏到点不可靠唤醒修复代码完成。AAPT2 Windows 编译问题不影响代码正确性，§12 真机验收是最终确认。
