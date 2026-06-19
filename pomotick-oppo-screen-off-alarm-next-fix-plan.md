# PomoTick OPPO 息屏提醒修复计划：setAlarmClock + 去重注册

## 背景

当前版本在 OPPO Watch 4 Pro 息屏后，番茄钟到点提醒仍可能被系统阻断。

从 `OPPO-OWW221-Android-11_2026-06-19_114134.logcat` 看，App 已经注册了 `RTC_WAKEUP` alarm，但 OPPO 功耗模块在息屏和 balance 模式下将第三方 wakeup alarm 降级或拦截：

- `convert thirdapp(com.pomotick.debug) wakeup alarm(tag:null) to non-wakeup when balance!`
- `forbin thirdapp(com.pomotick.debug) set wakeup alarm(tag:null)when balance and screen off!`
- `Forbid delivering pending non wakeup alarm:Intent { act=com.pomotick.action.TIMER_FIRE ... }`

因此问题不在于 Receiver 或 Reminder 没写通，而是系统没有准时投递 alarm。

## OPPO 规则依据

`OPPO 开放平台-OPPO开发者服务中心 1.md` 的应用功耗规则中写到：

- 禁止频繁使用非唤醒 alarm；
- 灭屏情况下禁止申请非唤醒 alarm；
- 禁止申请非用户设置的唤醒 alarm；
- 闹钟类应用仅允许用户设置的唤醒 alarm；
- 禁止申请持有唤醒锁；
- 禁止使用各种保活手段让应用常驻后台。

PomoTick 的番茄钟结束提醒属于用户主动开始计时后的到点提醒，应尽量建模为“用户设置的唤醒 alarm”，而不是普通后台 alarm。

## 核心判断

当前 `TimerAlarmScheduler` 使用：

```kotlin
alarmManager.setExactAndAllowWhileIdle(
    AlarmManager.RTC_WAKEUP,
    targetEndAtEpochMillis,
    pendingIntent
)
```

在标准 Android 上，这通常可以满足低功耗下的精确提醒。但在 OPPO Watch 4 Pro 上，系统日志表明它仍会被第三方功耗策略降级。

下一步应改为使用更接近系统闹钟语义的：

```kotlin
alarmManager.setAlarmClock(
    AlarmManager.AlarmClockInfo(targetEndAtEpochMillis, showIntent),
    operationIntent
)
```

`setAlarmClock()` 会被 Android 系统识别为用户可见的 alarm clock 事件，优先级高于普通 exact alarm，更符合 OPPO 文档中“闹钟类应用，仅允许用户设置的唤醒 alarm”的要求。

## 修改目标

本轮只解决息屏到点提醒被阻断的问题。

目标：

- 息屏后番茄钟到点时，系统能准时投递 PomoTick 的 alarm。
- 避免同一个目标时间被每次 tick 反复注册。
- 保持现有提醒生命周期不变：进入 `RINGING` 后仍由 `ReminderManager` 控制声音、震动、自动停止和用户响应。
- 不引入 WakeLock、不做保活、不增加后台轮询。

## Key Changes

### 1. TimerAlarmScheduler 改用 setAlarmClock 主路径

修改 `TimerAlarmScheduler.schedule(targetEndAtEpochMillis)`：

- API 21+ 使用 `AlarmManager.setAlarmClock()`。
- `operationIntent` 仍指向 `TimerAlarmReceiver`，action 仍为 `com.pomotick.action.TIMER_FIRE`。
- `showIntent` 指向 `MainActivity`，用于系统展示“即将响铃/闹钟详情”时打开 App。
- 保留 `cancel()` 取消同一个 operation PendingIntent。

建议策略：

- OPPO Watch / API 30 主路径：`setAlarmClock()`。
- 如未来需要兼容非手表设备，可保留 `setExactAndAllowWhileIdle()` 作为非 OPPO 或配置开关路径，但当前主目标设备应优先验证 `setAlarmClock()`。

### 2. 避免重复注册同一 target

当前 log 中同一个 target 会被频繁注册，说明 alarm 调度可能跟随 tick 反复执行。

建议在 Repository 或 Scheduler 层加去重：

- 记录当前已注册的 `scheduledTargetEndAtEpochMillis`。
- 当新 target 与已注册 target 一致时，不重复 schedule。
- 当状态离开 `RUNNING` 时，cancel 并清空记录。
- 当 target 变化时，先覆盖注册新 alarm，并更新记录。

需要覆盖的状态：

- `RUNNING` 且 target 未来：注册或保持 alarm。
- `PAUSED`：取消 alarm。
- `RINGING`：取消 alarm。
- `IDLE` / `FINISHED` / null：取消 alarm。
- `RUNNING` 但 target 已过期：不再注册过去时间，应交给 Engine 进入 `RINGING` 或等待下一次恢复检查处理。

### 3. 保留现有 Receiver 和提醒链路

`TimerAlarmReceiver` 的职责保持不变：

- 收到 alarm 后读取 Repository 当前运行状态。
- 仅在 `RUNNING` 时提交 `TimerEvent.OnTick(now)`。
- 由 Engine 负责从 `RUNNING` 转成 `RINGING`。
- 由全局 effect 触发 `StartReminder`。

`TimerForegroundService.startAlarmWakeup()` 可继续保留，用于 alarm 触发时立即拉起前台服务并刷新通知。

### 4. 不使用 WakeLock

不要通过 `PowerManager.WakeLock` 解决本问题。

原因：

- OPPO 文档明确禁止应用申请持有唤醒锁。
- log 中 OPPO 功耗模块已经在记录 wakelock 问题。
- `setAlarmClock()` 是更符合平台规则的入口。

### 5. 不把 JobScheduler 作为准点提醒主路径

OPPO 文档提到后台定时任务可使用 JobScheduler，但它不适合番茄钟准点提醒。

JobScheduler 可用于非准点维护任务，但不能替代到点提醒。

## 验收标准

### 本地检查

- `./gradlew testDebugUnitTest` 通过。
- `./gradlew assembleDebug` 通过。
- 静态确认 `TimerAlarmScheduler.start()` 或 `schedule()` 不再在每次 tick 下重复注册同一 target。

### 实机测试

设备：

- OPPO Watch 4 Pro
- ColorOS Watch V7.1
- Android 11 / API 30

测试场景：

1. 启动 1 分钟专注计时，立即息屏，等待到点。
2. 启动 5 分钟计时，息屏等待到点。
3. 启动计时后暂停，确认到原目标时间不会提醒。
4. 暂停后恢复，确认按恢复后的新目标时间提醒。
5. 计时中延长，确认按延长后的目标时间提醒。
6. 到点提醒后点击“下一阶段 / 放弃 / 响应”，确认声音和震动立即停止。

### logcat 验收

重点观察是否还出现：

- `convert thirdapp(com.pomotick.debug) wakeup alarm(tag:null) to non-wakeup`
- `forbin thirdapp(com.pomotick.debug) set wakeup alarm`
- `Forbid delivering pending non wakeup alarm`

期待看到：

- App 注册 alarm clock 相关日志。
- 到点附近出现 `PomoTick/AlarmReceiver alarm fired`。
- 随后出现 `PomoTick/Service [global] Start reminder`。

## 风险

### 风险 1：OPPO 仍然限制 debug 包

如果 `com.pomotick.debug` 因 debug 包、未上架、非白名单等原因仍被系统策略限制，`setAlarmClock()` 也可能不完全可靠。

应对：

- 用 release 签名包再测一次。
- 确认 App 电池策略为允许后台运行或不受限制。
- 记录新的 logcat，重点看系统是否仍把 alarm clock 降级。

### 风险 2：系统会显示“下一个闹钟”

`setAlarmClock()` 可能让系统认为 PomoTick 设置了一个闹钟，并在系统 UI 中显示下一个闹钟时间。

这是预期副作用。番茄钟到点提醒本质上是用户设置的到点 alarm，优先保证可靠性。

### 风险 3：频繁短时番茄测试可能触发功耗策略

连续多次 1 分钟测试可能仍被系统视为异常频繁 alarm。

实机测试时建议：

- 先用 1 分钟快速验证链路。
- 再用 5 分钟或真实 25 分钟验证实际使用场景。

## 暂不处理

本轮不处理：

- 通知渠道重构。
- 震动/响铃体验调整。
- 统计和 UI 性能问题。
- 长期后台保活。
- 系统 Clock App 接管提醒。

如果 `setAlarmClock()` 仍失败，再评估是否引入系统 Clock 兜底方案。

