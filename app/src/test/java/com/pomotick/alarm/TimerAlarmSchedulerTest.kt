package com.pomotick.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.2.1+ (OPPO fix): [TimerAlarmScheduler] 单元测试。
 *
 * **覆盖范围**：
 * - `ACTION_TIMER_FIRE` 常量稳定性（防止 release 误改导致 receiver 收不到 alarm）
 * - **去重逻辑** [TimerAlarmScheduler.shouldSkipSchedule]——v0.2.1+ OPPO fix 核心
 * - 边界值（target=0L、null vs not-null）
 *
 * **未覆盖**（需 Robolectric / 真机）：
 * - `setAlarmClock` 真实调用（`adb dumpsys alarm` 在 §12 真机验证）
 * - `canScheduleExactAlarms` 在 API 31+ 设备的真值（需 runtime grant）
 * - PendingIntent slot 复用（reflection-based 检查 Kotlin companion 字段会被 inline，
 *   跳过——已在 `TimerAlarmScheduler` 注释中说明）
 *
 * 完整 mockito 测试在 §13 合并前补回（移出 mockito-inline 配置后）。
 */
class TimerAlarmSchedulerTest {

    @Test
    fun `action constant is stable across releases`() {
        // 防止有人手抖改字符串导致 receiver 收不到 alarm
        assertEquals("com.pomotick.action.TIMER_FIRE", TimerAlarmScheduler.ACTION_TIMER_FIRE)
    }

    // ─── v0.2.1+ OPPO fix: 去重逻辑（核心）──────────────────────────

    @Test
    fun `shouldSkipSchedule returns true when scheduled equals new (dedup hit)`() {
        // OPPO fix 核心：相同 target 直接 return，避免每 tick 重复 setAlarmClock
        assertTrue(
            TimerAlarmScheduler.shouldSkipSchedule(
                scheduledTarget = 1_000_000L,
                newTarget = 1_000_000L
            )
        )
    }

    @Test
    fun `shouldSkipSchedule returns false when scheduled is null (first schedule)`() {
        // 首次注册：scheduled=null 必须调 setAlarmClock
        assertFalse(
            TimerAlarmScheduler.shouldSkipSchedule(
                scheduledTarget = null,
                newTarget = 1_000_000L
            )
        )
    }

    @Test
    fun `shouldSkipSchedule returns false when targets differ (target changed)`() {
        // target 变化（extend / resume）：必须重新注册
        assertFalse(
            TimerAlarmScheduler.shouldSkipSchedule(
                scheduledTarget = 1_000_000L,
                newTarget = 2_000_000L
            )
        )
    }

    @Test
    fun `shouldSkipSchedule returns false after cancel (scheduled cleared)`() {
        // 模拟 cancel 后状态：scheduled 重新为 null
        // cancel → schedule(sameTarget) 必须重新注册（因为 cancel 清空了记录）
        val scheduledAfterCancel: Long? = null
        assertFalse(
            TimerAlarmScheduler.shouldSkipSchedule(
                scheduledTarget = scheduledAfterCancel,
                newTarget = 1_000_000L
            )
        )
    }

    @Test
    fun `shouldSkipSchedule handles edge case 0L`() {
        // 边界：target=0L（实际不会发生，但确保函数正确）
        assertTrue(
            TimerAlarmScheduler.shouldSkipSchedule(scheduledTarget = 0L, newTarget = 0L)
        )
        assertFalse(
            TimerAlarmScheduler.shouldSkipSchedule(scheduledTarget = null, newTarget = 0L)
        )
        assertFalse(
            TimerAlarmScheduler.shouldSkipSchedule(scheduledTarget = 0L, newTarget = 1L)
        )
    }

    @Test
    fun `shouldSkipSchedule is symmetric across Long range`() {
        // 长整数范围内一致性（避免 == 误用导致精度问题）
        val veryLarge = Long.MAX_VALUE - 1
        assertTrue(
            TimerAlarmScheduler.shouldSkipSchedule(veryLarge, veryLarge)
        )
        assertFalse(
            TimerAlarmScheduler.shouldSkipSchedule(veryLarge, veryLarge - 1)
        )
    }
}