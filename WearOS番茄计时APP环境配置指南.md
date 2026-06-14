# Wear OS 番茄计时APP - 开发环境配置指南

> 本文档描述了搭建 Wear OS 番茄计时APP开发环境所需的全部软件、配置和验证步骤。

---

## 一、硬件要求

| 项目 | 最低要求 | 推荐配置 |
|------|----------|----------|
| CPU | x86_64 架构 | i5 以上 |
| 内存 | 8 GB | 16 GB（模拟器较吃内存） |
| 硬盘 | 10 GB 可用空间 | SSD，30 GB 以上 |
| 操作系统 | Windows 10 64位 | Windows 11 64位 |

> **说明**：Wear OS 模拟器比手机模拟器更轻量，8GB 内存基本够用。

---

## 二、软件安装清单

### 2.1 必装软件

| 软件 | 版本 | 用途 | 下载地址 |
|------|------|------|----------|
| **Android Studio** | 2024.x（最新稳定版） | 官方 IDE | https://developer.android.com/studio |
| **JDK** | 17（AS 内置） | Kotlin 编译 | AS 自带，无需单独安装 |
| **Android SDK** | API 30+ | Wear OS 开发 | AS 中通过 SDK Manager 安装 |

### 2.2 可选软件

| 软件 | 用途 | 下载地址 |
|------|------|----------|
| Git | 版本管理（克隆 Tomato 参考项目） | https://git-scm.com/ |
| ADB | 调试真实手表设备 | AS 自带 |

---

## 三、Android Studio 安装与配置

### 3.1 安装 Android Studio

1. 下载 Android Studio 安装包（.exe）
2. 运行安装程序，按默认选项安装
3. 首次启动选择 **Standard** 安装
4. 等待 SDK 下载完成

### 3.2 安装 SDK Platforms

打开 Android Studio → **File → Settings → Languages & Frameworks → Android SDK → SDK Platforms**

勾选以下平台：

| 组件 | API Level | 说明 |
|------|-----------|------|
| ✅ Android 14.0 (Upside Down Cake) | API 34 | **Wear OS 目标版本** |
| ✅ Android 13 (Tiramisu) | API 33 | 中间兼容版本 |
| ✅ Android 11 (R) | API 30 | **Wear OS 最低兼容版本** |

### 3.3 安装 SDK Tools

切换到 **SDK Tools** 标签页，勾选以下组件：

| 组件 | 说明 |
|------|------|
| ✅ Android SDK Build-Tools | 最新版（34.0.0+） |
| ✅ Android SDK Platform-Tools | ADB、fastboot 等工具 |
| ✅ Android SDK Command-line Tools | 命令行工具 |
| ✅ Android Emulator | 模拟器运行环境 |
| ✅ Intel HAXM（Windows Intel） | 硬件加速 |
| ✅ Google Play Services for Wear OS | Wear OS 服务 |

> **AMD 处理器用户**：不安装 HAXM，改用 Windows Hyper-V（需在 BIOS 中开启 SVM）。

### 3.4 安装 Wear OS 模拟器系统镜像

在 **SDK Tools** 中额外勾选：

| 组件 | 说明 |
|------|------|
| ✅ Wear OS Emulator: x86_64 | 手表模拟器引擎 |
| ✅ Wear OS 5 (API 34) 系统镜像 | 最新 Wear OS 版本 |
| ✅ Wear OS 4 (API 33) 系统镜像 | 兼容测试用 |

---

## 四、创建 Wear OS 模拟器

### 4.1 创建步骤

**Tools → Device Manager → Create Device**

1. **选择类别**：选择 **Wear OS**（不是 Phone）
2. **选择设备型号**：
   - **Wear OS Square**（方形，⭐ 推荐，与 OPPO Watch 4 Pro 一致）
   - **Pixel Watch 2**（圆形，用于兼容测试）
   - **Samsung Galaxy Watch 6**（圆形，用于兼容测试）
   - **Wear OS Small Round**（通用圆形，用于兼容测试）
3. **选择系统镜像**：
   - 优先选择 **API 34** → Wear OS 5
4. **命名并完成创建**

### 4.2 推荐创建的模拟器

| 模拟器 | 用途 | 优先级 |
|--------|------|--------|
| Wear OS Square (API 34, 方形) | 主要开发和测试（与真机一致） | ⭐ 必须 |
| Pixel Watch 2 (API 34, 圆形) | 圆形屏幕兼容测试 | 推荐 |
| Wear OS Square (API 30, 方形) | 最低版本兼容测试 | 可选 |

---

## 五、项目依赖配置

### 5.1 根级 `build.gradle.kts`

```kotlin
plugins {
    id("com.android.application") version "8.2.0" apply false
    id("com.android.library") version "8.2.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    id("com.google.dagger.hilt.android") version "2.50" apply false
    id("com.google.devtools.ksp") version "1.9.22-1.0.17" apply false
}
```

### 5.2 Wear 模块 `build.gradle.kts`

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.pomodoro.wear"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pomodoro.wear"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // === Compose BOM（统一版本管理） ===
    implementation(platform("androidx.compose:compose-bom:2024.01.00"))

    // === Compose for Wear OS 核心 ===
    implementation("androidx.wear.compose:compose-foundation:1.3.0")
    implementation("androidx.wear.compose:compose-material3:1.0.0-alpha15")
    implementation("androidx.wear.compose:compose-navigation:1.3.0")

    // === Compose 基础 ===
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")

    // === Wear OS 特有 ===
    implementation("androidx.wear:wear:1.3.0")
    implementation("com.google.android.gms:play-services-wearable:18.1.0")
    implementation("androidx.wear.compose:compose-tooling:1.3.0")

    // === 生命周期 & ViewModel ===
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    // === Room 数据库 ===
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // === Hilt 依赖注入 ===
    implementation("com.google.dagger:hilt-android:2.50")
    ksp("com.google.dagger:hilt-compiler:2.50")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")

    // === 协程 ===
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // === Vico 图表库（统计页面） ===
    implementation("com.patrykandpatrick.vico:compose-m3:2.0.0-alpha.0")

    // === 调试 ===
    debugImplementation("androidx.compose.ui:ui-tooling")
}
```

### 5.3 `AndroidManifest.xml` 关键权限

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- 震动权限 -->
    <uses-permission android:name="android.permission.VIBRATE" />

    <!-- 前台服务权限 -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />

    <!-- 通知权限（Android 13+） -->
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <!-- 唤醒锁（确保计时可靠） -->
    <uses-permission android:name="android.permission.WAKE_LOCK" />

    <!-- Wear OS 特性声明 -->
    <uses-feature android:name="android.hardware.type.watch" />

    <application
        android:allowBackup="true"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@android:style/Theme.DeviceDefault">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:screenOrientation="portrait">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <!-- 前台服务声明 -->
        <service
            android:name=".service.WearTimerService"
            android:enabled="true"
            android:exported="false"
            android:foregroundServiceType="connectedDevice" />
    </application>
</manifest>
```

### 5.4 `gradle.properties` 配置

```properties
# Kotlin 代码生成
kotlin.code.style=official

# AndroidX
android.useAndroidX=true

# 并行编译
org.gradle.parallel=true

# 构建缓存
org.gradle.caching=true

# JVM 内存
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
```

---

## 六、克隆参考项目

```bash
# 克隆 Tomato 项目（主要参考）
git clone https://github.com/nsh07/Tomato.git

# 克隆 WearPomodoro 项目（Wear OS 结构参考）
git clone https://github.com/AlexKorovyansky/WearPomodoro.git

# 克隆 Snaptick 项目（MVVM 架构参考）
git clone https://github.com/vishal2376/snaptick.git
```

用 Android Studio 分别打开这些项目，确保能成功编译运行。

---

## 七、环境验证清单

按以下顺序逐项验证，全部通过即可开始开发：

| 序号 | 验证步骤 | 验证内容 | 预期结果 |
|------|----------|----------|----------|
| 1 | 打开 Android Studio | IDE 正常启动 | 无报错，进入欢迎页 |
| 2 | 检查 SDK | File → Settings → SDK Manager | API 30/33/34 已安装 |
| 3 | 检查 SDK Tools | SDK Tools 标签页 | Build-Tools、Emulator、Wear OS Emulator 已安装 |
| 4 | 创建 Wear OS 项目 | New → Wear OS → Empty Compose Activity | 项目创建成功 |
| 5 | 启动模拟器 | 选择 Wear OS Square 模拟器启动 | 方形手表界面正常显示 |
| 6 | 运行 Hello World | 点击 Run | 模拟器上显示 Hello World |
| 7 | 编译 Tomato 项目 | 打开 Tomato → Build → Make Project | 编译成功，无报错 |
| 8 | 编译 WearPomodoro | 打开 WearPomodoro → Build | 编译成功（可能有警告，属正常） |

---

## 八、常见问题排查

### 8.1 模拟器相关

| 问题 | 原因 | 解决方案 |
|------|------|----------|
| 模拟器启动失败，黑屏 | BIOS 未开启虚拟化 | 重启电脑，进入 BIOS 开启 Intel VT-x 或 AMD SVM |
| 模拟器启动极慢 | 未启用硬件加速 | 安装 Intel HAXM（Intel）或开启 Hyper-V（AMD） |
| Wear OS 模拟器选项为空 | 未安装 Wear OS Emulator | SDK Tools → 勾选 "Wear OS Emulator" 并安装 |
| 模拟器无网络 | DNS 配置问题 | 模拟器设置 → Advanced → 设置 DNS 为 8.8.8.8 |

### 8.2 编译相关

| 问题 | 原因 | 解决方案 |
|------|------|----------|
| SDK 找不到 | SDK 路径未配置 | File → Settings → SDK Manager → 确认路径正确 |
| Compose 版本冲突 | 多模块版本不一致 | 使用 Compose BOM 统一管理 |
| Hilt 编译失败 | 使用了 KAPT 而非 KSP | 改用 KSP（性能更好，兼容 Room） |
| Kotlin 版本不匹配 | 插件与运行时版本不一致 | 确保 `kotlin-android` 插件版本与 `kotlinCompilerExtensionVersion` 兼容 |
| Wear Compose 依赖找不到 | 未添加 Wear OS Maven 仓库 | 检查 `settings.gradle.kts` 中是否包含 Google 和 Maven Central 仓库 |

### 8.3 `settings.gradle.kts` 仓库配置参考

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolution {
    repositories {
        google()
        mavenCentral()
    }
}
```

---

## 九、真实设备调试（OPPO Watch 4 Pro）

### 9.1 设备信息

| 属性 | 详情 |
|------|------|
| 设备型号 | OPPO Watch 4 Pro |
| 操作系统 | ColorOS Watch V7.1 |
| Android 版本 | Android 11 (API 30) |
| 屏幕形状 | **方形**（1.91英寸大屏弧面） |
| 连接方式 | USB（需 OTG 转接头）/ WiFi |

> **重要说明**：OPPO Watch 4 Pro 基于 ColorOS Watch（非原生 Wear OS），ADB 调试方式与标准 Wear OS 设备不同，需要通过手机中转或 OTG 线直连。

### 9.2 开启开发者选项

1. 手表上进入 **设置 → 其他设置 → 关于手表**
2. 找到 **版本信息**，快速连续点击 **版本号** 7次以上
3. 提示"已处于开发者模式"
4. 返回 **其他设置**，滚动到底部，找到新增的 **开发者选项**
5. 开启 **USB 调试**

### 9.3 通过 OTG 线直连调试（推荐）

OPPO Watch 4 Pro 使用专用充电触点，需要 OTG 转接头连接电脑。

**所需配件**：
- OPPO Watch 4 Pro 专用充电底座（带触点）
- USB OTG 转接头（或支持数据传输的底座）

**连接步骤**：

```bash
# 1. 将手表放在充电底座上
# 2. 底座通过 OTG 转接头连接电脑 USB 口
# 3. 手表上弹出"允许 USB 调试"提示，点击"允许"
# 4. 电脑上验证连接
adb devices

# 预期输出：
# List of devices attached
# XXXXXXXX    device
```

> **注意**：如果使用的是纯充电底座（不支持数据传输），此方法不可行，需改用方法 9.4。

### 9.4 通过手机中转调试

如果 OTG 直连不可行，可通过 OPPO 手机中转 ADB 连接。

**所需条件**：
- 一台 OPPO 手机（与手表配对）
- 手机已安装"甲壳虫ADB助手"或类似 ADB 工具

**步骤**：

```
1. 手表开启 USB 调试（见 9.2）
2. 手机开启 OTG 功能
   设置 → 其他设置 → OTG 连接 → 开启
3. 手机安装 ADB 工具（如甲壳虫ADB助手）
4. 手表通过充电底座连接手机（OTG 线）
5. 手表弹出"允许 USB 调试"，点击允许
6. 手机 ADB 工具中确认已连接手表
7. 手机通过 WiFi 将 ADB 端口转发到电脑
```

**手机端转发到电脑**：

```bash
# 在手机上（通过 Termux 或 ADB 工具）执行：
adb tcpip 5555

# 获取手表 IP（手表 → 设置 → WiFi → 点击已连接网络）
# 然后在电脑上执行：
adb connect <手表IP>:5555
```

### 9.5 通过 WiFi 调试

如果手表和电脑在同一局域网内：

```bash
# 1. 确保手表已连接 WiFi
#    手表 → 设置 → WiFi → 连接到与电脑同一网络

# 2. 先通过 USB/OTG 连接一次，开启 TCP 模式
adb tcpip 5555

# 3. 获取手表 IP 地址
#    手表 → 设置 → WiFi → 点击已连接的网络名称 → 查看 IP 地址

# 4. 拔掉 USB 线，通过 WiFi 连接
adb connect <手表IP>:5555

# 5. 验证连接
adb devices

# 预期输出：
# List of devices attached
# <手表IP>:5555    device
```

### 9.6 Android Studio 连接 OPPO Watch 4 Pro

连接成功后，在 Android Studio 中操作：

1. 打开项目
2. 顶部设备选择器中应出现 OPPO Watch 4 Pro
3. 选择该设备，点击 **Run**
4. APP 将安装到手表上并自动启动

### 9.7 OPPO Watch 4 Pro 调试注意事项

| 注意事项 | 说明 |
|----------|------|
| **minSdk 兼容** | OPPO Watch 4 Pro 为 Android 11 (API 30)，项目 `minSdk` 设为 30 即可完美兼容 |
| **ColorOS Watch 差异** | ColorOS Watch 非原生 Wear OS，部分 Wear OS 特有 API 可能不可用（如 WearableLayoutManager） |
| **通知渠道** | ColorOS Watch 的通知管理可能与原生不同，需在真机上测试提醒功能 |
| **震动反馈** | OPPO Watch 4 Pro 震动马达较强，建议在真机上调试震动强度参数 |
| **电池优化** | ColorOS 可能有额外的后台限制，需在手表设置中为 APP 关闭电池优化 |
| **后台保活** | 设置 → 电池 → 应用电池管理 → 找到你的APP → 选择"不限制" |
| **方形屏幕** | OPPO Watch 4 Pro 为方形屏幕（1.91英寸），四角可充分利用，无需担心圆形裁切问题 |
| **Google Play Services** | ColorOS Watch 可能不包含完整 Google Play Services，部分依赖需替换 |

### 9.8 针对 ColorOS Watch 的项目配置调整

由于 OPPO Watch 4 Pro 运行 ColorOS Watch 而非原生 Wear OS，需要在项目中做以下调整：

```kotlin
// build.gradle.kts - 调整依赖
dependencies {
    // 移除或标记为可选（ColorOS Watch 可能不支持）
    // implementation("com.google.android.gms:play-services-wearable:18.1.0")

    // 使用 Android 通用替代方案
    implementation("androidx.wear:wear:1.3.0")
    implementation("androidx.wear.compose:compose-foundation:1.3.0")
    implementation("androidx.wear.compose:compose-material3:1.0.0-alpha15")
}
```

```xml
<!-- AndroidManifest.xml - 调整特性声明 -->
<manifest>
    <!-- 移除或改为可选（ColorOS Watch 可能不声明此特性） -->
    <!-- <uses-feature android:name="android.hardware.type.watch" required="true" /> -->
    <uses-feature android:name="android.hardware.type.watch" required="false" />

    <!-- 添加通用手表特性 -->
    <uses-feature android:name="android.hardware.screen.portrait" />
</manifest>
```

### 9.9 OPPO Watch 4 Pro 专项测试清单

| 测试项 | 验证内容 | 通过标准 |
|--------|----------|----------|
| APP 安装 | 通过 ADB 安装 APK | 安装成功，无报错 |
| 启动速度 | 冷启动到主界面 | < 3秒 |
| 计时准确性 | 运行25分钟计时 | 误差 < 1秒 |
| 后台保活 | 息屏后继续计时 | 计时不中断 |
| 震动提醒 | 计时结束触发震动 | 震动明显可感知 |
| 持续提醒 | 未响应时重复震动 | 每30秒重复 |
| 方形屏适配 | UI 在方形屏幕内正常显示 | 所有元素完整显示，无裁切 |
| 电池消耗 | 运行1小时耗电 | < 10% |
| 数据持久化 | 强制关闭后重开 | 计时状态恢复 |
| 通知显示 | 计时结束通知 | 通知正常弹出 |

---

> 文档版本：v1.1
> 创建日期：2026-06-07
> 更新日期：2026-06-07（新增 OPPO Watch 4 Pro 真机调试章节）
> 配套文档：[WearOS番茄计时APP开发方案.md](./WearOS番茄计时APP开发方案.md)
