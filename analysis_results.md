# PomoTick 在 OPPO Watch 4 Pro 上的问题分析

## 日志关键证据

### 问题一：定时器到时提醒不准 / 被系统拦截

日志中发现了 **两步拦截链**，ColorOS Watch 的 `BmPowerManager` 和 `AlarmController` 联手把 PomoTick 的闹钟吞掉了：

#### 第一步：BmPowerManager 把 wakeup alarm 降级为 non-wakeup

```
08:30:32 [BmPowerManager] convert thirdapp(com.pomotick.debug) 
         wakeup alarm(tag:null) to non-wakeup when balance!
```

> [!CAUTION]
> 关键线索：**`tag:null`**。尽管代码使用了 `setAlarmClock()`，OPPO 的 BmPowerManager 仍然识别到 alarm tag 为 null，将其视为普通第三方 alarm 并降级。

#### 第二步：AlarmController 直接禁止投递降级后的 alarm

```
08:34:27 [AlarmController] Forbid delivering pending non wakeup alarm:
         Intent { act=com.pomotick.action.TIMER_FIRE 
         pkg=com.pomotick.debug }, pkgName(com.pomotick.debug), uid(10128)
```

#### 完整时间线

| 时间 | 事件 | 备注 |
|------|------|------|
| 08:22:xx | Focus timer 启动，注册 alarmClock → 目标 08:26:54 | 正常 |
| 08:26:54 | ⏰ Alarm 应该触发 | **没有触发** |
| 08:30:18 | `Start reminder: phase=FOCUS` 实际触发 | ⚠️ **延迟 204 秒 (3.4分钟)** |
| 08:30:32 | BmPowerManager 将下一个 alarm 降级为 non-wakeup | 第二个 alarm 被降级 |
| 08:30:32 | 注册新 alarmClock → 目标 08:32:31 | Short break timer |
| 08:32:31 | ⏰ Alarm 应该触发 | **没有触发** |
| 08:34:27 | AlarmController **Forbid** 投递 TIMER_FIRE | ❌ **Alarm 被完全吞掉** |
| 08:42:41 | `Start reminder: phase=SHORT_BREAK` 实际触发 | ⚠️ **延迟 610 秒 (10分钟)**，可能是用户手动亮屏后才触发 |

> [!WARNING]
> 第一个 Alarm 延迟了 3.4 分钟才送达，第二个 Alarm **被完全禁止投递**，直到用户手动操作才恢复。在实际使用中，25 分钟专注结束后可能完全收不到提醒。

---

### 问题二：无法弹出屏幕界面 / 亮屏失败

日志中发现了 MCU 层面的屏幕唤醒阻止：

```
09:39:09 [PowerManagerService] BM mcu state or screenControlledByMcu forbid wakeup
```

> [!IMPORTANT]
> OPPO Watch 4 Pro 的屏幕由 **MCU（微控制器）** 控制。当手表进入低功耗模式后，MCU 接管屏幕控制，Android 侧的 `PowerManager.wakeUp()` 调用会被 MCU 拒绝。这意味着 `fullScreenIntent` 即使正常发出，也无法唤醒屏幕。

当前代码的 `fullScreenIntent` 亮屏策略在 OPPO Watch 上面临双重打击：
1. Alarm 本身就不一定能按时送达 → notification 发不出去
2. 即使 notification 发出了，MCU 也可能阻止屏幕唤醒

---

## 根因分析

### 根因 1：`setAlarmClock()` 的 tag 在 OPPO Watch 上为 null

代码中使用 `setAlarmClock()` 本意是利用"用户可见闹钟"的豁免机制。但 OPPO Watch 的 `BmPowerManager` 检查的是 alarm 的 **tag 字段**，而 `setAlarmClock()` 设置的 alarm 内部 tag 在 ColorOS Watch 实现中可能为 null，导致被识别为普通第三方 alarm。

相关代码 ([TimerAlarmScheduler.kt](file:///d:/Workspace/PomoTick/app/src/main/java/com/pomotick/alarm/TimerAlarmScheduler.kt)):
```kotlin
alarmManager.setAlarmClock(
    AlarmManager.AlarmClockInfo(targetEndAtEpochMillis, showPendingIntent()),
    operationPendingIntent()
)
```

### 根因 2：没有 ForegroundService 自检兜底机制

当 AlarmManager 被系统拦截后，没有任何备用机制来检测 timer 到期。ForegroundService 虽然在运行，但它的 tick loop 每 2 秒一次，只向 Repository 发 `OnTick` 事件。如果 alarm 被吞掉了，tick 本身也会触发 RINGING（因为 Engine 检查 `now >= targetEndAt`），但 **前提是 Service 还在运行**。

### 根因 3：MCU 控制屏幕，Android 侧无法唤醒

OPPO Watch 4 Pro 在息屏后由 MCU 接管屏幕控制。`fullScreenIntent` 和 `PowerManager.wakeUp()` 都无法绕过 MCU 的控制。

---

## 解决方案

### 方案 A：ForegroundService 自检 + 振动唤醒（推荐）

**核心思路**：不完全依赖 AlarmManager，让 ForegroundService 作为主要的 timer 到期检测机制，并用振动来"唤醒"用户注意。

#### 具体做法：

1. **Service tick 增强**：现有 2s tick loop 中增加到期检测逻辑
   - 当 `now >= targetEndAtEpochMillis` 且 `runState == RUNNING` 时，直接触发 RINGING
   - 不再完全依赖 AlarmManager 的 BroadcastReceiver
   - AlarmManager 仍然保留作为"Service 被杀"时的恢复手段

2. **AlarmManager 双重保险**：保留 `setAlarmClock()` 不变，但同时使用 `setExactAndAllowWhileIdle()` 设置备份 alarm，增加投递概率

3. **振动优先**：timer 到期时不依赖亮屏，优先触发强烈振动
   - 振动不受 MCU 屏幕控制的限制
   - 日志中 `BM forbid vibrate false` 表明振动是被允许的

4. **WakeLock 保活**：在 timer 即将到期前（比如最后 60 秒），acquire 一个 partial WakeLock，确保 Service tick 不被暂停

**优点**：改动较小，不依赖 OPPO 特殊 API，振动在 MCU 模式下仍然可用  
**缺点**：仍然无法保证亮屏

---

### 方案 B：模拟系统闹钟行为

**核心思路**：让 PomoTick 的 alarm 看起来像系统闹钟应用，获得 OPPO 白名单豁免。

#### 具体做法：

1. **设置 alarm tag**：在调用 `setAlarmClock()` 前，通过反射或 hidden API 设置 alarm 的 tag 为 `*walarm*:pomotick.alarm` 格式
   - 日志中成功投递的系统 alarm 都有 `*walarm*:` 前缀的 tag
   - 但这种做法不保证在所有 ColorOS 版本上有效

2. **使用 `android.intent.action.DISMISS_ALARM` 等标准闹钟 Intent**：让系统把 PomoTick 识别为闹钟类应用

3. **请求 `android.permission.SET_ALARM`** 权限

**优点**：如果有效，可以获得和系统闹钟同等的 alarm 投递优先级  
**缺点**：依赖 OPPO 内部实现细节，可能在系统更新后失效，反射 API 有兼容性风险

---

### 方案 C：引导用户关闭电池优化（用户操作方案）

**核心思路**：通过引导用户修改系统设置，将 PomoTick 加入白名单。

#### 具体做法：

1. **引导用户到电池优化设置**：添加一个设置项，一键跳转到：
   - `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`（通用 Android）
   - OPPO 特有的电池管理页面（`com.coloros.battery` 或 `com.oppo.battery`）

2. **检测当前电池优化状态**：使用 `PowerManager.isIgnoringBatteryOptimizations()` 检测，在未豁免时显示引导提示

3. **建议用户设置"不受限制"的后台运行权限**

**优点**：最可靠的解决方案，直接解决 BmPowerManager 拦截问题  
**缺点**：需要用户手动操作，增加使用门槛

---

### 方案 D：混合方案（推荐最终方案）

**同时实施 A + C**，覆盖所有场景：

| 层级 | 机制 | 作用 |
|------|------|------|
| **第一层** | 用户引导关闭电池优化 | 从根源解决 alarm 被拦截 |
| **第二层** | ForegroundService 自检 tick | 即使 alarm 被拦截，Service tick 也能检测到期 |
| **第三层** | AlarmManager `setAlarmClock()` | 作为 Service 被杀后的恢复机制 |
| **第四层** | 最后 60s WakeLock | 确保 Service 在到期前不被 suspend |
| **第五层** | 振动优先提醒 | 即使无法亮屏，振动仍能通知用户 |

#### 需要修改的文件：

| 文件 | 改动 |
|------|------|
| [TimerForegroundService.kt](file:///d:/Workspace/PomoTick/app/src/main/java/com/pomotick/service/TimerForegroundService.kt) | tick loop 增加到期检测 + WakeLock 逻辑 |
| [TimerAlarmScheduler.kt](file:///d:/Workspace/PomoTick/app/src/main/java/com/pomotick/alarm/TimerAlarmScheduler.kt) | 保持不变或增加备份 alarm |
| [NotificationFactory.kt](file:///d:/Workspace/PomoTick/app/src/main/java/com/pomotick/service/NotificationFactory.kt) | 可选：调整 notification channel |
| 新文件：`BatteryOptimizationHelper.kt` | 电池优化检测 + 引导跳转 |
| UI 层（设置页面） | 添加电池优化引导入口 |

---

## 下一步建议

请选择要实施的方案，或者我可以先实施方案 D（混合方案），它覆盖面最广且风险最低。
