package com.pomotick.system

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * 方案 D 第一层：电池优化检测 + 引导跳转。
 *
 * ## 背景
 *
 * OPPO Watch 4 Pro 的 `BmPowerManager` 在 balance 模式 + 息屏时会把第三方 wakeup alarm
 * 降级为 non-wakeup，甚至直接禁止投递。根本解法是让用户把 PomoTick 加入电池优化白名单
 *（"不受限制"），从根源上消除拦截。
 *
 * ## 能力
 *
 * - [isIgnoringBatteryOptimizations]：检测当前 App 是否在白名单中
 * - [requestIgnoreBatteryOptimizations]：弹出系统对话框请求加入白名单
 * - [openBatterySettings]：跳转到系统"电池优化"列表页（兜底入口）
 *
 * ## 使用场景
 *
 * - 设置页：显示当前白名单状态 + 一键跳转引导
 * - 未来可扩展：App 启动时检测未授权状态，弹一次性提示（需 SettingsStore 持久化标记）
 */
class BatteryOptimizationHelper(private val context: Context) {

    private val powerManager: PowerManager? =
        context.getSystemService(PowerManager::class.java)

    /**
     * 当前 App 是否已被排除在电池优化之外（即在白名单中）。
     *
     * - true → 不受 Doze / App Standby 限制，alarm 能准点投递
     * - false → 可能被 BmPowerManager 降级 / 禁止投递
     */
    fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = powerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * 请求系统将本 App 加入电池优化白名单。
     *
     * 会弹出系统对话框让用户确认（"允许应用始终在后台运行吗？"）。
     * 仅在 [isIgnoringBatteryOptimizations] 返回 false 时有意义。
     *
     * 需要 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 权限。
     */
    fun requestIgnoreBatteryOptimizations(): Boolean =
        tryStartActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })

    /** 跳转到系统"电池优化"设置列表页（兜底入口）。 */
    fun openBatterySettings(): Boolean =
        tryStartActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })

    /** 跳转到本 App 的系统详情设置页（兜底入口）。 */
    fun openAppDetailsSettings(): Boolean =
        tryStartActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })

    /**
     * 智能跳转：优先弹白名单请求对话框,失败则尝试通用电池优化列表,
     * 最后兜底应用详情页。返回值标识最终落到哪一层（用于 UI 决策 Toast 指引）。
     */
    fun smartOpenBatterySettings(): BatteryJumpResult {
        // 标准 Android 白名单对话框
        if (requestIgnoreBatteryOptimizations()) return BatteryJumpResult.WHITELIST_DIALOG
        // 标准电池优化列表页
        if (openBatterySettings()) return BatteryJumpResult.BATTERY_LIST
        // 兜底：应用详情页（能打开但不是电池页）
        return if (openAppDetailsSettings()) BatteryJumpResult.APP_DETAILS
        else BatteryJumpResult.ALL_FAILED
    }


    private fun tryStartActivity(intent: Intent): Boolean = runCatching {
        context.startActivity(intent)
        true
    }.onFailure { e ->
        Log.w(TAG, "startActivity failed: ${intent.action ?: intent.component} - ${e.javaClass.simpleName}: ${e.message}")
    }.getOrDefault(false)

    companion object {
        private const val TAG = "PomoTick/Battery"
    }
}

/**
 * smartOpenBatterySettings() 的跳转结果，用于 UI 决策是否给用户 Toast 指引。
 */
enum class BatteryJumpResult {
    /** 白名单确认对话框成功弹出 */
    WHITELIST_DIALOG,
    /** 电池优化列表页成功打开 */
    BATTERY_LIST,
    /** 兜底到了应用详情页（能打开但不是电池页，需要提示用户） */
    APP_DETAILS,
    /** 所有 Intent 均失败 */
    ALL_FAILED
}
