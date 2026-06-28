package com.pomotick.system

import android.content.Context
import android.content.Intent
import android.util.Log
import com.pomotick.MainActivity

/**
 * 后台直接拉起 [MainActivity] 的辅助类。
 *
 * 在 OPPO/ColorOS Watch 上,Notification + fullScreenIntent 会被 HeyNotification 拦截,
 * 无法亮屏,进而导致震动被 BmNonAndroidState 丢弃。通过直接 startActivity 强制系统
 * 退出低功耗状态并点亮屏幕,可绕过该限制。
 *
 * 无需额外 Manifest 权限:从后台启动同一应用内的 Activity 在 API 30 上是允许的,
 * 且 Intent 带有 [FLAG_ACTIVITY_NEW_TASK]。
 */
object ActivityWakeupHelper {

    const val ACTION_SHOW_REMINDER = "com.pomotick.action.SHOW_REMINDER"
    const val EXTRA_SHOW_REMINDER = "com.pomotick.extra.SHOW_REMINDER"

    private const val TAG = "PomoTick/Wakeup"

    fun intent(context: Context): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = ACTION_SHOW_REMINDER
            putExtra(EXTRA_SHOW_REMINDER, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

    /**
     * 从后台拉起 [MainActivity],尝试点亮屏幕。
     *
     * - 使用 [FLAG_ACTIVITY_NEW_TASK] 满足后台启动要求
     * - 使用 [FLAG_ACTIVITY_CLEAR_TOP] 清理到 MainActivity 的栈顶
     * - 使用 [FLAG_ACTIVITY_SINGLE_TOP] 避免重复创建 Activity 实例
     *
     * 用 [runCatching] 包裹,避免 OEM 策略抛异常影响主流程。
     */
    fun wakeUp(context: Context) {
        runCatching {
            context.startActivity(intent(context))
            Log.d(TAG, "started MainActivity from background")
        }.onFailure { e ->
            Log.w(TAG, "wakeUp failed: ${e.message}")
        }
    }
}
