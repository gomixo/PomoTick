# Tomato 项目参考笔记

> 参考仓库：[nsh07/Tomato](https://github.com/nsh07/Tomato)  
> 阅读分支：`main`  
> 阅读时 HEAD：`629fcdb807c1148d1b801ac5964ee71e8914d5ed`  
> 记录时间：2026-06-17

## 许可提醒

Tomato 使用 GPL-3.0 许可证。本笔记只整理项目行为和架构观察，不记录可直接复制的实现方案。

## 项目整体结构

Tomato 是一个 Android 番茄钟项目：

- Android app + shared 模块结构。
- 使用 Room 存储设置和统计。
- 使用 Koin 依赖注入。
- 使用 Material 3、Vico 图表、Glance 小组件、Quick Settings Tile 等。
- 目标主要是手机 Android 应用。

## 响铃和震动逻辑

核心文件：

- `androidApp/src/main/java/org/nsh07/pomodoro/service/TimerService.kt`
- `shared/src/androidMain/kotlin/org/nsh07/pomodoro/ui/timerScreen/AlarmDialog.kt`
- `shared/src/androidMain/kotlin/org/nsh07/pomodoro/ui/settingsScreen/PlatformVibrator.android.kt`

### 完成后的流程

TimerService 在计时完成后调用 `showTimerNotification(..., complete = true)`，随后进入 `startAlarm()`。

`startAlarm()` 主要做这些事：

- 如果 `alarmEnabled = true`，启动 `MediaPlayer` 播放铃声。
- 点亮或唤醒 Activity。
- 启动一个自动停止任务，1 分钟后调用 `stopAlarm(fromAutoStop = true)`。
- 如果 `vibrateEnabled = true`，使用 `VibrationEffect.createWaveform()` 播放震动。

### 铃声逻辑

Tomato 使用 `MediaPlayer` 播放用户选择的系统闹钟铃声。

铃声来源：

- 默认使用系统默认 alarm tone。
- 用户可以在设置中选择 ringtone URI。
- 设置里有一个 `mediaVolumeForAlarm` 选项：
  - 开启时使用 `AudioAttributes.USAGE_MEDIA`。
  - 关闭时使用 `AudioAttributes.USAGE_ALARM`。

### 震动逻辑

默认震动设置：

- `vibrateEnabled = true`
- `vibrationOnDuration = 1000L`
- `vibrationOffDuration = 1000L`
- `vibrationAmplitude = -1`

震动波形概念：

- timings: `[0, onDuration, offDuration, onDuration]`
- amplitudes: `[0, amplitude, 0, amplitude]`
- repeat index: `2`

这意味着先等待 0ms，然后震动一段、停一段、震动一段，并从 index 2 开始重复，也就是持续执行“停顿 + 震动”的循环。

如果设备没有震动器，直接返回。

### 停止提醒

`stopAlarm()` 会：

- 取消自动停止任务。
- 暂停铃声并 seek 到 0。
- 调用 `vibrator.cancel()`。
- 关闭唤醒状态。
- 将 `alarmRinging` 设为 false。
- 更新通知按钮为“开始下一段”。
- 如果开启 `autostartNextSession` 且不是自动停止，则自动启动下一段。

用户停止入口：

- 全屏 `AlarmDialog` 点击任意区域。
- 对话框里的停止按钮。
- 通知里的停止按钮。
- 1 分钟自动停止。

## 设置逻辑

核心文件：

- `shared/src/commonMain/kotlin/org/nsh07/pomodoro/data/Preference.kt`
- `shared/src/commonMain/kotlin/org/nsh07/pomodoro/data/PreferenceDao.kt`
- `shared/src/commonMain/kotlin/org/nsh07/pomodoro/data/PreferenceRepository.kt`
- `shared/src/commonMain/kotlin/org/nsh07/pomodoro/data/StateRepository.kt`
- `shared/src/commonMain/kotlin/org/nsh07/pomodoro/ui/settingsScreen/viewModel/SettingsState.kt`
- `shared/src/commonMain/kotlin/org/nsh07/pomodoro/ui/settingsScreen/viewModel/SettingsViewModel.kt`
- `shared/src/commonMain/kotlin/org/nsh07/pomodoro/ui/settingsScreen/viewModel/SettingsAction.kt`

### 存储方式

Tomato 没有使用 Android DataStore，而是用 Room 做了三张 key-value 表：

- `boolean_preference`
- `int_preference`
- `string_preference`

每张表都有：

- `key`
- `value`

`PreferenceRepository` 对这些表做了一层封装，效果类似 Preferences DataStore。

### 默认设置

默认值集中在 `SettingsState`：

- `focusTime = 25 * 60 * 1000L`
- `shortBreakTime = 5 * 60 * 1000L`
- `longBreakTime = 15 * 60 * 1000L`
- `sessionLength = 4`
- `alarmEnabled = true`
- `vibrateEnabled = true`
- `dndEnabled = false`
- `mediaVolumeForAlarm = false`
- `autostartNextSession = false`
- `vibrationOnDuration = 1000L`
- `vibrationOffDuration = 1000L`
- `vibrationAmplitude = -1`
- `alarmSoundUri = getDefaultAlarmTone()`

### 启动加载

`StateRepository.reloadSettings()` 的策略：

- 读取每个设置项。
- 如果数据库没有值，就写入默认值。
- 最后把所有设置更新到内存里的 `settingsState`。
- 首次加载后，还会根据设置初始化 timer state。

### 设置更新

设置页通过 `SettingsAction` 表达用户操作。

`SettingsViewModel` 收到 action 后：

- 更新内存里的 `settingsState`。
- 写入 Room preference 表。
- 如果修改的是专注时长、休息时长或 session length，并且服务没有运行，则刷新当前 timer。

## 统计逻辑

核心文件：

- `shared/src/commonMain/kotlin/org/nsh07/pomodoro/data/Stat.kt`
- `shared/src/commonMain/kotlin/org/nsh07/pomodoro/data/StatDao.kt`
- `shared/src/commonMain/kotlin/org/nsh07/pomodoro/data/StatRepository.kt`
- `shared/src/commonMain/kotlin/org/nsh07/pomodoro/ui/statsScreen/viewModel/StatsViewModel.kt`
- `androidApp/src/main/java/org/nsh07/pomodoro/service/TimerService.kt`

### 数据模型

Tomato 使用 `stat` 表记录每天统计，每天一条：

- `date: LocalDate`
- `focusTimeQ1`
- `focusTimeQ2`
- `focusTimeQ3`
- `focusTimeQ4`
- `breakTime`

`totalFocusTime()` 是四个 focus 分段相加。

四个 focus 分段用于分析一天中哪个时间段更专注。代码注释说是四个 quarter，但实现中实际按一天 4 等分处理：

- 00:00 - 06:00
- 06:00 - 12:00
- 12:00 - 18:00
- 18:00 - 24:00

### 写入方式

`TimerService.saveTimeToDb()` 会计算当前 timer 已经过的时间，并减去 `lastSavedDuration`，只保存增量。

写入规则：

- 当前是 FOCUS：调用 `statRepository.addFocusTime(...)`
- 当前是 SHORT_BREAK 或 LONG_BREAK：调用 `statRepository.addBreakTime(...)`

触发保存的时机：

- 运行中新增超过 60 秒时保存。
- timer skip 时保存。
- reset 时保存。
- service destroy 时保存。

`addFocusTime()` 会根据当前本地时间决定累加到 Q1/Q2/Q3/Q4 哪一列。

### 查询方式

常用查询：

- 今日统计：`getTodayStat()`
- 最近 N 天统计：`getLastNDaysStats(n)`
- 最近 N 天平均分布：`getLastNDaysAvgStats(n)`
- 总专注时间：`getAllTimeTotalFocusTime()`

`StatsViewModel` 在此基础上做：

- 今日统计展示。
- all-time total focus 展示。
- 最近 7 天、31 天、365 天图表数据。
- focus breakdown 数据。
- heatmap / calendar 数据。

## 核心逻辑简述

### 响铃震动逻辑

计时结束后，应用会进入提醒状态，同时发出铃声和震动，并把提醒界面展示给用户。用户可以通过提醒界面或通知停止提醒；如果用户没有处理，提醒会在一段时间后自动停止。

铃声和震动都受设置控制。用户可以关闭铃声、关闭震动，也可以调整震动的节奏和强度。提醒停止后，铃声会回到开头，震动会立即取消，界面和通知会回到等待下一段计时的状态。

### 设置逻辑

应用有一组默认设置，包括专注时长、短休息时长、长休息时长、每轮专注次数、是否响铃、是否震动、是否自动开始下一段等。

启动时，应用会先读取已经保存过的设置；如果某个设置没有保存过，就使用默认值。用户在设置页修改选项后，应用会立即更新当前界面状态，并把新设置保存下来。对于计时时长这类会影响当前计时器的设置，如果计时器没有运行，界面会同步刷新到新的时长。

### 统计逻辑

应用按天记录统计数据。每天会记录当天的专注时间和休息时间，其中专注时间还会按一天中的不同时间段拆开保存，用来展示用户在什么时间段更专注。

计时运行过程中，应用会定期保存已经产生的有效时间，避免只在结束时才记录。暂停、跳过、重置或退出服务时，也会把当前已经产生的时间保存下来。统计页面会基于这些每日记录展示今日统计、总专注时间、最近一段时间的趋势和时间段分布。
