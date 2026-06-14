# PomoTick

> Wear OS 番茄计时 MVP — 优先适配 OPPO Watch 4 Pro（方形 1.91" / ColorOS Watch V7.1 / Android 11 / API 30）。

## 技术栈

- **语言**：Kotlin
- **UI**：Jetpack Compose for Wear OS
- **架构**：纯函数 TimerEngine + Repository + ViewModel（无 Hilt / 无 Navigation 框架）
- **持久化**：Room（已完成 session）+ DataStore Preferences（运行时状态 + 设置）
- **后台**：ForegroundService（`specialUse` 前台服务类型）+ `VibrationEffect.createWaveform`

## 开发要求

- Android Studio 2024.x
- JDK 17（AS 内置）
- Android SDK API 30、33、34
- Wear OS Square 模拟器（API 34）——主调试环境
- 可选：OPPO Watch 4 Pro 真机（API 30）

## 构建命令

```bash
# 编译
./gradlew :app:assembleDebug

# 单测
./gradlew :app:testDebugUnitTest

# 安装到模拟器/真机
./gradlew :app:installDebug

# 清理
./gradlew clean
```

## 项目结构

```
app/src/main/java/com/pomotick/
├── MainActivity.kt
├── data/          # Room + DataStore
├── timer/         # TimerEngine（纯函数）
├── service/       # ForegroundService
├── reminder/      # 震动提醒
├── ui/            # ViewModel + Screen
└── ...
```

## MVP 5 屏

1. **TimerScreen** — 主倒计时（25:00 / 5:00 / 15:00）
2. **QuickActionsScreen** — 延长 5 分钟 / 提前结束 / 放弃
3. **ReminderScreen** — 提醒响应（知道了 / 开始休息 / 继续专注）
4. **SettingsScreen** — 时长配置 / 震动强度 / 持续提醒
5. **TodayStatsScreen** — 今日完成数 / 今日专注时长 / 最近完成

## 关键约束（来自 AGENTS.md）

- `minSdk = 30`，`targetSdk = 34`
- `android.hardware.type.watch` = `required="false"`
- **不**依赖 Google Play Services、Hilt、Vico、Navigation
- 计时以时间戳为唯一真实来源，不依赖每秒后台循环
- 强提醒最多重复 10 次（每 30 秒一次）

## 真机调试

参考 `WearOS番茄计时APP环境配置指南.md` §9。
