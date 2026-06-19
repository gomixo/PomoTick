# PomoTick OPPO 息屏 Alarm Action Tag 实验方案

## 目标

验证一个关键假设：

> OPPO Watch 4 Pro 的功耗模块可能会更倾向识别带明确 action tag 的 alarm，而不是 `tag:null` 的显式 component PendingIntent。

当前 PomoTick 虽然已经使用 `AlarmManager.setAlarmClock()`，但 logcat 仍显示：

```text
[BmPowerManager] convert thirdapp(com.pomotick.debug) wakeup alarm(tag:null) to non-wakeup when balance!
```

而 OPPO/HeyTap 的“番茄时钟”成功唤醒时，AlarmManager 日志是：

```text
[alarmlog]Deliver wakeup alarm which type=2,
tag=*walarm*:com.heytap.wearable.tomato.alarm_action,
package=com.heytap.wearable.tomatotimer2
```

两者最明显的差异之一是：

- PomoTick：`tag:null`
- 番茄时钟：`tag=*walarm*:具体 action`

本实验只验证这一点：让 PomoTick 的 alarm 从 `tag:null` 变成明确的 action tag，例如：

```text
*walarm*:com.pomotick.action.TIMER_FIRE
```

## 范围

本轮只改 alarm 的 manifest 声明和 PendingIntent 构造方式。

不处理：

- 提醒 Activity 直启方案；
- 通知渠道重构；
- 震动/响铃体验；
- 后台保活；
- WakeLock；
- 系统 Clock 委托；
- OPPO 私有权限。

## 当前问题点

当前 `TimerAlarmScheduler.operationPendingIntent()` 使用显式 component：

```kotlin
val intent = Intent(context, TimerAlarmReceiver::class.java).apply {
    action = ACTION_TIMER_FIRE
    setPackage(context.packageName)
}

PendingIntent.getBroadcast(
    context,
    REQUEST_CODE,
    intent,
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
)
```

Manifest 中 receiver 没有 intent-filter：

```xml
<receiver
    android:name=".alarm.TimerAlarmReceiver"
    android:exported="false" />
```

这种写法安全、明确，但在 OPPO log 中 alarm tag 仍然是 `null`。推测 OPPO 的 AlarmManager tag 提取逻辑可能没有从显式 component PendingIntent 中拿到 action。

## 修改思路

改成“action + package + intent-filter”的静态广播。

也就是：

- Manifest 给 `TimerAlarmReceiver` 增加固定 action 的 `intent-filter`。
- PendingIntent 使用 `Intent(ACTION_TIMER_FIRE).setPackage(context.packageName)`。
- 不再使用 `Intent(context, TimerAlarmReceiver::class.java)` 显式 component。
- Receiver 保持 `exported=false`，防止外部 App 调起。

这样系统看到的是一个明确 action 的 broadcast PendingIntent，期望 AlarmManager 日志中的 tag 从 `null` 变成 action。

## 具体修改

### 1. 修改 AndroidManifest.xml

文件：

```text
app/src/main/AndroidManifest.xml
```

将当前 receiver：

```xml
<receiver
    android:name=".alarm.TimerAlarmReceiver"
    android:exported="false" />
```

改为：

```xml
<receiver
    android:name=".alarm.TimerAlarmReceiver"
    android:exported="false">
    <intent-filter>
        <action android:name="com.pomotick.action.TIMER_FIRE" />
    </intent-filter>
</receiver>
```

说明：

- `exported=false` 保持不变。
- 即使声明了 intent-filter，外部 App 仍不能直接发送给这个 receiver。
- 系统持有的 PendingIntent 可以正常投递。
- action 字符串必须与 `TimerAlarmScheduler.ACTION_TIMER_FIRE` 完全一致。

### 2. 修改 TimerAlarmScheduler.operationPendingIntent()

文件：

```text
app/src/main/java/com/pomotick/alarm/TimerAlarmScheduler.kt
```

将当前写法：

```kotlin
private fun operationPendingIntent(): PendingIntent {
    val intent = Intent(context, TimerAlarmReceiver::class.java).apply {
        action = ACTION_TIMER_FIRE
        setPackage(context.packageName)
    }
    return PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}
```

改为：

```kotlin
private fun operationPendingIntent(): PendingIntent {
    val intent = Intent(ACTION_TIMER_FIRE).apply {
        setPackage(context.packageName)
    }
    return PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}
```

说明：

- 去掉显式 component。
- 保留 `setPackage(context.packageName)`，让广播只在本包内解析。
- 保留固定 `REQUEST_CODE`，继续复用同一个 AlarmManager slot。
- 保留 `FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE`。

### 3. 修改注释

同步更新 `TimerAlarmScheduler` 和 Manifest 注释，避免继续写“无 intent-filter / 显式 component”。

建议注释重点：

```text
v0.2.1+ OPPO action-tag experiment:
使用 action + package + manifest intent-filter 构造 broadcast PendingIntent，
目标是让 OPPO AlarmManager 日志从 tag:null 变为 *walarm*:com.pomotick.action.TIMER_FIRE。
```

### 4. Receiver 代码保持不变

文件：

```text
app/src/main/java/com/pomotick/alarm/TimerAlarmReceiver.kt
```

`onReceive()` 中保留 action 校验：

```kotlin
if (intent.action != TimerAlarmScheduler.ACTION_TIMER_FIRE) {
    Log.w(TAG, "received unknown action: ${intent.action}")
    return
}
```

这仍然是必要防线。

## 预期效果

修改后，注册 alarm 时仍应看到 PomoTick 自己的日志：

```text
registered alarmClock for ... (setAlarmClock path)
```

同时重点观察系统日志是否变化。

期望从：

```text
convert thirdapp(com.pomotick.debug) wakeup alarm(tag:null) to non-wakeup
```

变成：

```text
convert thirdapp(com.pomotick.debug) wakeup alarm(tag:*walarm*:com.pomotick.action.TIMER_FIRE) ...
```

或者更理想地看到：

```text
Deliver wakeup alarm which type=2,
tag=*walarm*:com.pomotick.action.TIMER_FIRE,
package=com.pomotick.debug
```

## 本地验证

执行：

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

检查点：

- 编译通过。
- `TimerAlarmReceiver` 能被 manifest 正确解析。
- `ACTION_TIMER_FIRE` 单测继续通过。

## 实机验证步骤

1. 安装新 APK。
2. 打开 PomoTick。
3. 启动 2 分钟或 5 分钟计时。
4. 确认 PomoTick log 出现：

```text
registered alarmClock for ...
```

5. 立刻息屏。
6. 等待到点。
7. 导出 logcat。

## logcat 重点搜索

搜索 PomoTick：

```text
PomoTick/Alarm
PomoTick/AlarmReceiver
PomoTick/Service
com.pomotick.action.TIMER_FIRE
com.pomotick.debug
```

搜索系统 alarm：

```text
AlarmManager
AlarmController
BmPowerManager
Deliver wakeup alarm
convert thirdapp
forbin thirdapp
Forbid delivering pending
```

## 判断标准

### 成功

满足以下任意一种即可认为实验有效：

- 到点时出现 `PomoTick/AlarmReceiver alarm fired`。
- 到点时出现：

```text
Deliver wakeup alarm ... tag=*walarm*:com.pomotick.action.TIMER_FIRE
```

- 息屏下能准时唤醒，并进入 PomoTick 提醒。

### 部分成功

如果 tag 从 `null` 变成了：

```text
*walarm*:com.pomotick.action.TIMER_FIRE
```

但仍被 OPPO 降级或拦截，说明 action tag 方向有效，但不足以获得唤醒权限。

这种情况下下一步应做“第二优先级”：alarm 到点后直接启动专用提醒 Activity，而不是依赖通知。

### 失败

如果仍然是：

```text
tag:null
```

说明 OPPO 的 tag 提取不受这种 PendingIntent 形态影响，或者 `setAlarmClock()` 在该系统里仍被特殊处理。

这种情况下继续调整 action/component 的收益会很低。

## 风险

### 1. exported=false + intent-filter 的兼容性

理论上系统通过 PendingIntent 投递不受影响；外部 App 不能直接投递。

如果实测 receiver 收不到，可短期实验性改为 `exported=true` 并加自定义签名权限，但不建议作为正式方案，因为会扩大攻击面。

### 2. PendingIntent identity 变化

从显式 component 改成 action + package 后，PendingIntent identity 会变化。

安装新版本后，旧 alarm 可能不会被新 `cancel()` 取消。测试前建议：

- 强制停止旧 App；
- 清理一次运行状态；
- 或重新安装后打开 App 再开始新计时。

正式代码中可以接受，因为升级时 App 启动会重新同步 runtime 并注册新的 alarm。

### 3. 仍可能被 OPPO 识别为 thirdapp

即使 tag 变成 action，PomoTick 仍是普通第三方包：

```text
com.pomotick.debug
```

它没有 HeyTap 签名、白名单或私有权限。这个实验不保证绕过系统策略，只是验证 tag 是否影响策略判断。

## 不建议同时做的事

为了保持实验结论清晰，本轮不要同时做：

- 直接启动 AlarmActivity；
- 增加 WakeLock；
- 改 targetSdk；
- 改包名；
- 加 OPPO 私有权限；
- 改通知渠道；
- 改震动/响铃逻辑。

一次只改 alarm PendingIntent 形态，才能判断 log 差异是否来自 action tag。

