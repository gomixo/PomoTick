# Wear OS/ColorOS Watch 番茄计时 APP 开发方案

> OPPO Watch 4 Pro 优先落地版本。目标不是完整移植手机端番茄应用，而是在手表上做一个可靠、顺手、强提醒的番茄计时器。

---

## 一、项目定位

### 1.1 首要目标

开发一款优先运行在 **OPPO Watch 4 Pro** 上的番茄计时 APP，用于快速开始专注、稳定后台计时、到点强提醒，并允许用户按真实工作状态灵活调整。

### 1.2 目标设备

| 属性 | 详情 |
|------|------|
| 首要设备 | OPPO Watch 4 Pro |
| 系统 | ColorOS Watch V7.1 |
| Android 版本 | Android 11 (API 30) |
| 屏幕 | 方形，1.91 英寸大屏弧面 |
| 调试方式 | USB/OTG 或 WiFi ADB |
| 关键限制 | 非原生 Wear OS，部分 Wear OS / Google Play Services 能力可能不可用 |

### 1.3 产品原则

- **手表优先**：核心操作必须能在几秒内完成。
- **可靠优先**：真实计时以时间戳计算，不依赖后台每秒循环。
- **提醒优先**：震动提醒必须明显，但要有上限和停止入口。
- **轻量优先**：先做 OPPO Watch 4 Pro 的方形屏体验，圆形屏兼容后置。
- **独立优先**：不依赖手机伴侣 APP，不依赖 Google Play Services 的核心能力。

### 1.4 核心功能

| 功能 | 优先级 | 说明 |
|------|--------|------|
| 基础番茄计时 | P0 | 默认 25 分钟专注 + 5 分钟短休息，每 4 个番茄后 15 分钟长休息 |
| 灵活调整 | P0 | 支持延长 5 分钟、提前结束、暂停、放弃 |
| 强提醒机制 | P0 | 计时结束后震动提醒，未响应时有限次数重复提醒 |
| 后台可靠运行 | P0 | 息屏、切后台、进程重启后尽量恢复正确状态 |
| OPPO Watch 4 Pro 适配 | P0 | 方形屏布局、Android 11/API 30、ColorOS 后台限制验证 |
| 自定义时长 | P1 | 可配置专注、短休息、长休息时长 |
| 今日统计 | P1 | 今日完成数、今日专注时长 |
| 本周统计/图表 | P2 | 后续再做，避免 MVP 被图表复杂度拖慢 |

---

## 二、关键调整结论

### 2.1 不直接移植 Tomato 代码

nsh07/Tomato 继续作为产品和架构参考，但 **不建议直接复制或移植其核心代码**。

原因：

- Tomato 使用 GPL-3.0 协议，直接派生代码会影响本项目后续分发方式。
- 番茄计时核心逻辑并不复杂，自写能避免许可证和架构包袱。
- OPPO Watch 4 Pro 的限制更像嵌入式小工具场景，完整手机端架构不是必要前提。

调整后策略：

| 内容 | 策略 |
|------|------|
| UI 设计思路 | 可参考 |
| 数据统计思路 | 可参考 |
| Room 表设计 | 可参考，但重新设计 |
| 计时状态机 | 自写 |
| 通知/提醒实现 | 自写，并针对 ColorOS 真机验证 |
| 代码复制 | 默认不复制 |

### 2.2 计时不依赖每秒后台循环

真实计时状态使用时间戳建模，而不是把 `delay(1000)` 循环当作唯一事实来源。

核心字段：

```kotlin
data class TimerRuntimeState(
    val sessionId: Long,
    val phase: TimerPhase,
    val runState: TimerRunState,
    val plannedDurationMillis: Long,
    val startedAtEpochMillis: Long,
    val targetEndAtEpochMillis: Long,
    val pausedAtEpochMillis: Long?,
    val accumulatedPausedMillis: Long,
    val extensionCount: Int
)

enum class TimerPhase {
    FOCUS,
    SHORT_BREAK,
    LONG_BREAK
}

enum class TimerRunState {
    IDLE,
    RUNNING,
    PAUSED,
    RINGING,
    FINISHED
}
```

剩余时间计算：

```kotlin
fun remainingMillis(now: Long, state: TimerRuntimeState): Long {
    if (state.runState == TimerRunState.PAUSED && state.pausedAtEpochMillis != null) {
        return (state.targetEndAtEpochMillis - state.pausedAtEpochMillis).coerceAtLeast(0)
    }
    return (state.targetEndAtEpochMillis - now).coerceAtLeast(0)
}
```

这样做的好处：

- UI 每秒刷新只是展示，不影响真实计时。
- 息屏或进程被杀后，重启时可用 `targetEndAtEpochMillis` 计算是否已经到点。
- 暂停、恢复、延长、提前结束都有明确状态迁移。

### 2.3 后台策略不依赖 WorkManager 做精确提醒

WorkManager 不适合作为“25 分钟后必须立刻提醒”的核心机制。它可以用于后续统计同步或清理任务，但不作为精确计时兜底。

MVP 后台策略：

| 场景 | 策略 |
|------|------|
| 计时进行中 | 启动 ForegroundService，显示常驻通知 |
| APP 在前台 | Compose UI 每秒刷新显示 |
| APP 在后台 | 服务根据目标结束时间判断是否到点 |
| 服务被系统重启 | 从持久化状态恢复，重新计算状态 |
| ColorOS 后台限制 | 引导用户将 APP 电池策略设为“不限制” |
| Google Play Services 缺失 | 不把 play-services-wearable 作为核心依赖 |

可选增强：

- 原生 Wear OS 设备上可接入 Ongoing Activity。
- OPPO Watch 4 Pro 上先验证普通前台服务通知是否稳定。
- 如系统支持精确闹钟，可评估 `AlarmManager.setExactAndAllowWhileIdle` 作为提醒补强，但必须真机验证。

### 2.4 强提醒必须有上限和停止入口

强提醒是核心卖点，但不能无限震动。默认策略应当“足够强，但可控”。

| 提醒类型 | 默认行为 |
|----------|----------|
| 专注结束 | 强震动 + 通知/提醒页 + 屏幕唤醒尝试 |
| 休息结束 | 中等震动 + 通知/提醒页 |
| 未响应重复提醒 | 每 30 秒重复 1 次，最多 10 次 |
| 停止方式 | 用户点击“知道了 / 开始休息 / 继续专注”后停止 |
| 兜底 | 超过最大次数后停止震动，保留通知 |

震动模式需在 OPPO Watch 4 Pro 真机上校准：

```kotlin
val focusDonePattern = longArrayOf(0, 450, 160, 450, 160, 700)
val breakDonePattern = longArrayOf(0, 300, 180, 300)
```

注意：

- Android 11 上使用 `VibrationEffect.createWaveform`。
- 需要 `VIBRATE` 权限。
- 提示音默认关闭，避免手表在公共场景过度打扰。

### 2.5 UI 先砍到手表 MVP

MVP 不做完整手机式页面体系。OPPO Watch 4 Pro 是方形屏，可以利用更多横向空间，但交互仍要保持极简。

MVP 页面：

| 页面 | 内容 |
|------|------|
| 主计时页 | 大倒计时、阶段名称、环形或条形进度、开始/暂停/继续按钮 |
| 快捷操作页 | 延长 5 分钟、提前结束、放弃 |
| 提醒响应页 | 知道了、开始休息、继续专注 |
| 设置页 | 专注时长、短休息、长休息、震动强度、持续提醒 |
| 今日统计页 | 今日完成数、今日专注时长 |

后置功能：

- 本周趋势图。
- Vico 图表。
- 手机伴侣 APP。
- 圆形屏精细适配。

---

## 三、技术选型

### 3.1 推荐技术栈

| 技术 | 选择 | 说明 |
|------|------|------|
| 语言 | Kotlin | Android 端首选，适合状态机和协程 |
| UI | Jetpack Compose + Wear Compose 组件 | 优先适配手表屏幕，必要时回退普通 Compose 组件 |
| 架构 | 简化 MVVM + Repository | MVP 保持清晰，不提前做复杂分层 |
| 计时核心 | 自写时间戳状态机 | 不依赖每秒后台循环 |
| 历史记录 | Room | 存储番茄会话记录 |
| 设置存储 | DataStore | 存储轻量配置，比 Room 更适合设置项 |
| 后台服务 | ForegroundService | 计时中保持用户可感知 |
| 依赖注入 | 暂缓或轻量 Hilt | MVP 可先手动注入，复杂后再引入 |
| 图表 | 暂缓 Vico | 本周统计阶段再引入 |

### 3.2 Android 配置

| 配置 | 建议 |
|------|------|
| minSdk | 30 |
| targetSdk | 34 |
| compileSdk | 34 或更高 |
| Java/Kotlin target | 17 |
| Google Play Services | 不作为核心依赖 |
| `android.hardware.type.watch` | `required="false"`，避免 ColorOS Watch 兼容问题 |

### 3.3 Manifest 关键权限

```xml
<uses-permission android:name="android.permission.VIBRATE" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<uses-feature
    android:name="android.hardware.type.watch"
    android:required="false" />
```

如果后续使用精确闹钟，再评估是否加入：

```xml
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
```

---

## 四、项目结构设计

MVP 阶段建议先保持单 APP 模块，避免过早 KMP/shared 化。

```
PomoTick/
├── app/
│   ├── src/main/java/com/pomotick/
│   │   ├── MainActivity.kt
│   │   ├── timer/
│   │   │   ├── TimerEngine.kt
│   │   │   ├── TimerRuntimeState.kt
│   │   │   ├── TimerPhase.kt
│   │   │   └── TimerRunState.kt
│   │   ├── data/
│   │   │   ├── TimerSession.kt
│   │   │   ├── TimerSessionDao.kt
│   │   │   ├── AppDatabase.kt
│   │   │   └── SettingsStore.kt
│   │   ├── service/
│   │   │   └── TimerForegroundService.kt
│   │   ├── reminder/
│   │   │   ├── ReminderManager.kt
│   │   │   └── VibrationHelper.kt
│   │   └── ui/
│   │       ├── TimerViewModel.kt
│   │       ├── screens/
│   │       │   ├── TimerScreen.kt
│   │       │   ├── QuickActionsScreen.kt
│   │       │   ├── ReminderScreen.kt
│   │       │   ├── SettingsScreen.kt
│   │       │   └── TodayStatsScreen.kt
│   │       ├── components/
│   │       │   ├── TimerProgress.kt
│   │       │   └── BigTimerButton.kt
│   │       └── theme/
│   │           └── Theme.kt
│   └── build.gradle.kts
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

后续如果要支持手机伴侣 APP，再拆分：

- `core`：纯 Kotlin 计时状态机。
- `app`：手表 APP。
- `mobile`：可选手机伴侣 APP。

---

## 五、核心功能设计

### 5.1 状态机

```
IDLE
  └── 开始专注 -> RUNNING(FOCUS)

RUNNING(FOCUS)
  ├── 暂停 -> PAUSED(FOCUS)
  ├── 延长5分钟 -> RUNNING(FOCUS)
  ├── 提前结束 -> RINGING(FOCUS_DONE)
  ├── 到点 -> RINGING(FOCUS_DONE)
  └── 放弃 -> IDLE，记录 INTERRUPTED

PAUSED(FOCUS / BREAK)
  ├── 继续 -> RUNNING(原阶段)
  └── 放弃 -> IDLE，记录 INTERRUPTED

RINGING(FOCUS_DONE)
  ├── 开始休息 -> RUNNING(SHORT_BREAK / LONG_BREAK)
  ├── 继续专注 -> RUNNING(FOCUS，延长一次)
  └── 知道了 -> FINISHED

RUNNING(SHORT_BREAK / LONG_BREAK)
  ├── 暂停 -> PAUSED(BREAK)
  ├── 到点 -> RINGING(BREAK_DONE)
  └── 跳过休息 -> IDLE

RINGING(BREAK_DONE)
  ├── 开始下一个番茄 -> RUNNING(FOCUS)
  └── 知道了 -> IDLE
```

### 5.2 灵活调整机制

| 操作 | 行为 | 记录 |
|------|------|------|
| 延长 5 分钟 | `targetEndAtEpochMillis += 5 分钟` | `extensionCount + 1` |
| 提前结束 | 立即进入专注完成提醒 | 保存实际专注时长 |
| 暂停 | 保存 `pausedAtEpochMillis` | 不增加实际专注时长 |
| 继续 | 按暂停时长顺延 `targetEndAtEpochMillis` | 继续原 session |
| 放弃 | 停止服务和提醒 | 记录 `INTERRUPTED` |

### 5.3 数据模型

```kotlin
@Entity(tableName = "timer_sessions")
data class TimerSession(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val phase: TimerPhase,
    val startedAtEpochMillis: Long,
    val endedAtEpochMillis: Long?,
    val plannedDurationMillis: Long,
    val actualFocusMillis: Long,
    val status: SessionStatus,
    val extensionCount: Int = 0
)

enum class SessionStatus {
    COMPLETED,
    INTERRUPTED,
    SKIPPED
}
```

进行中的状态建议单独持久化到 DataStore 或一张 `runtime_state` 单行表。历史 session 和运行时状态不要混在一起，否则恢复逻辑容易变复杂。

---

## 六、OPPO Watch 4 Pro UI/UX 设计

### 6.1 方形屏布局原则

- 默认以 OPPO Watch 4 Pro 方形屏为基准设计。
- 主倒计时数字居中放大，优先保证扫一眼能读懂。
- 操作按钮使用 56-64dp 大触控区。
- 尽量少用长列表，设置项采用大行距分组。
- 不强依赖滑动手势，关键操作必须能点按完成。

### 6.2 主计时页

主计时页包含：

- 当前阶段：专注 / 短休息 / 长休息。
- 大倒计时：如 `24:38`。
- 进度：方形屏可优先使用圆环，也可使用顶部/底部进度条。
- 主按钮：开始、暂停、继续。
- 次入口：更多操作、设置。

### 6.3 快捷操作页

只保留三个操作：

| 操作 | 说明 |
|------|------|
| 延长 5 分钟 | 任务还没做完时使用 |
| 提前结束 | 任务已完成时使用 |
| 放弃 | 需要二次确认 |

### 6.4 提醒响应页

计时结束后显示大按钮：

| 场景 | 主要按钮 | 次要按钮 |
|------|----------|----------|
| 专注结束 | 开始休息 | 继续专注、知道了 |
| 休息结束 | 开始专注 | 知道了 |

用户点击任意响应按钮后，立即停止重复震动。

### 6.5 统计页

MVP 只做今日统计：

- 今日完成番茄数。
- 今日专注总时长。
- 最近一次完成时间。

本周趋势和图表放到 P2。

---

## 七、后台与提醒实现

### 7.1 ForegroundService 责任

`TimerForegroundService` 只负责长运行和提醒，不负责复杂 UI 状态。

职责：

- 计时开始时启动前台服务。
- 显示常驻通知。
- 定期检查 `targetEndAtEpochMillis` 是否到点。
- 到点后进入 `RINGING` 状态并触发提醒。
- 用户结束或放弃后停止服务。

### 7.2 服务内计时方式

```kotlin
private fun tick(now: Long) {
    val state = repository.getRuntimeState()
    if (state.runState != TimerRunState.RUNNING) return

    if (now >= state.targetEndAtEpochMillis) {
        repository.updateRunState(TimerRunState.RINGING)
        reminderManager.startReminder(state.phase)
        updateNotificationForRinging(state.phase)
    } else {
        updateNotification(remainingMillis(now, state))
    }
}
```

说明：

- 服务可以每 1-5 秒检查一次，但这只是检查机制，不是计时事实来源。
- 通知展示可以降低刷新频率，例如每 15 秒或每分钟更新一次，减少耗电。
- APP 前台 UI 可以每秒刷新，因为前台交互时间较短。

### 7.3 ColorOS 后台限制应对

OPPO Watch 4 Pro 必须做真机验证：

| 项目 | 要求 |
|------|------|
| 电池策略 | 引导用户设置为“不限制” |
| 息屏计时 | 息屏 25 分钟后仍能触发提醒 |
| 后台计时 | 切出 APP 后仍能触发提醒 |
| 强制停止 | 被系统杀掉后，重开 APP 能恢复状态 |
| 通知权限 | 首次启动时检查并引导开启 |

---

## 八、电量优化策略

| 策略 | 说明 |
|------|------|
| 深色主题 | 默认黑色背景，适合 OLED |
| UI 前台每秒刷新 | 只在 APP 可见时每秒刷新 |
| 后台低频通知更新 | 后台通知不必每秒刷新 |
| 不使用传感器 | 不调用心率、加速度等传感器 |
| 持续震动有上限 | 最多重复 10 次，避免耗电失控 |
| 图表后置 | MVP 不引入高复杂度图表页面 |

---

## 九、数据持久化与恢复

### 9.1 保存策略

| 数据 | 存储 |
|------|------|
| 当前运行状态 | DataStore 或 `runtime_state` 单行表 |
| 历史番茄记录 | Room |
| 用户设置 | DataStore |

状态变化时立即保存：

- 开始。
- 暂停。
- 继续。
- 延长。
- 提前结束。
- 到点提醒。
- 放弃。
- 用户响应提醒。

### 9.2 启动恢复

```
APP 启动
  -> 读取运行时状态
  -> 没有进行中状态：显示主页面
  -> 有 RUNNING 状态：
       -> 当前时间 < targetEndAt：恢复倒计时
       -> 当前时间 >= targetEndAt：进入提醒响应页
  -> 有 PAUSED 状态：显示暂停状态
  -> 有 RINGING 状态：恢复提醒响应页
```

### 9.3 不建议的恢复方式

不要只保存“剩余秒数”。如果手表息屏、系统暂停进程或重启，剩余秒数会失真。必须保存目标结束时间。

---

## 十、开发计划

### Phase 1：OPPO Watch 4 Pro MVP 框架（1 周）

- [ ] 创建单 APP 模块项目，minSdk 30。
- [ ] 配置 Compose / Wear Compose 基础依赖。
- [ ] 设置 `android.hardware.type.watch required=false`。
- [ ] 实现主计时页静态 UI。
- [ ] 在 OPPO Watch 4 Pro 或方形模拟器上验证安装和显示。

### Phase 2：自写计时核心（1 周）

- [ ] 实现 `TimerRuntimeState`。
- [ ] 实现 `TimerEngine` 状态机。
- [ ] 使用目标结束时间计算剩余时间。
- [ ] 实现开始、暂停、继续、延长、提前结束、放弃。
- [ ] 编写状态机单元测试。

### Phase 3：后台服务与恢复（1-2 周）

- [ ] 实现 `TimerForegroundService`。
- [ ] 实现常驻通知。
- [ ] 实现运行时状态持久化。
- [ ] 实现 APP 重启后的状态恢复。
- [ ] 真机测试息屏 25 分钟计时。

### Phase 4：强提醒（1 周）

- [ ] 实现震动提醒。
- [ ] 实现每 30 秒重复提醒，最多 10 次。
- [ ] 实现提醒响应页。
- [ ] 用户响应后停止震动。
- [ ] 真机校准 OPPO Watch 4 Pro 震动强度。

### Phase 5：设置与今日统计（1 周）

- [ ] DataStore 保存用户设置。
- [ ] 设置专注/短休息/长休息时长。
- [ ] 设置震动强度和持续提醒开关。
- [ ] Room 保存历史 session。
- [ ] 今日完成数和今日专注时长。

### Phase 6：体验优化（后续）

- [ ] 电池“不限制”引导。
- [ ] 方形屏细节优化。
- [ ] 圆形屏兼容测试。
- [ ] 本周统计。
- [ ] Vico 图表。
- [ ] 原生 Wear OS Ongoing Activity 适配。

---

## 十一、专项测试清单

| 测试项 | 通过标准 |
|--------|----------|
| 安装启动 | OPPO Watch 4 Pro 可通过 ADB 安装并启动 |
| 方形屏显示 | 所有主要元素完整显示，无裁切 |
| 计时准确性 | 25 分钟计时误差小于 1 秒，按时间戳恢复 |
| 暂停恢复 | 暂停期间不消耗专注时间 |
| 延长 5 分钟 | 目标结束时间正确顺延 |
| 提前结束 | 正确保存实际专注时长 |
| 息屏提醒 | 息屏后到点可提醒 |
| 后台提醒 | 切出 APP 后到点可提醒 |
| 重启恢复 | APP 被杀后重开，能根据当前时间恢复正确状态 |
| 震动强度 | 专注结束震动明显可感知 |
| 持续提醒 | 未响应时每 30 秒重复，最多 10 次 |
| 停止提醒 | 点击响应按钮后立即停止震动 |
| 电池消耗 | 运行 1 小时耗电目标小于 10% |

---

## 十二、关键风险与应对

| 风险 | 影响 | 应对策略 |
|------|------|----------|
| ColorOS 后台限制 | 服务被限制，提醒不稳定 | 前台服务 + 电池“不限制”引导 + 真机长时间测试 |
| 非原生 Wear OS API 缺失 | 部分 Wear OS 能力不可用 | 避免核心功能依赖 Google Play Services 和原生 Wear OS 专属能力 |
| GPL 代码复用风险 | 影响项目分发 | Tomato 只做参考，核心代码自写 |
| 每秒循环失真 | 息屏或进程暂停后计时不准 | 使用 `targetEndAtEpochMillis` 作为真实计时依据 |
| 震动过度耗电 | 用户反感或电量下降 | 默认最多重复 10 次，用户可关闭持续提醒 |
| UI 复杂度过高 | 手表上难用 | MVP 只保留主计时、快捷操作、提醒响应、设置、今日统计 |

---

## 十三、参考资源

- [nsh07/Tomato](https://github.com/nsh07/Tomato) - 产品和统计设计参考，GPL-3.0，默认不复制代码
- [AlexKorovyansky/WearPomodoro](https://github.com/AlexKorovyansky/WearPomodoro) - 手表计时器结构参考
- [vishal2376/snaptick](https://github.com/vishal2376/snaptick) - MVVM 和提醒机制参考
- [Wear OS 官方文档](https://developer.android.com/training/wearables)
- [Compose for Wear OS](https://developer.android.com/training/wearables/compose)
- [Wear OS Ongoing Activity](https://developer.android.com/training/wearables/notifications/ongoing-activity)
- [Always-on apps and ambient mode](https://developer.android.com/training/wearables/always-on)
- [Vico 图表库](https://patrykandpatrick.com/vico)

---

> 文档版本：v1.1
> 创建日期：2026-06-07
> 更新日期：2026-06-14
> 本版本调整：OPPO Watch 4 Pro 优先；Tomato 改为参考不移植；计时改为时间戳模型；WorkManager 不作为精确提醒核心；强提醒增加上限；UI 收敛为手表 MVP。
