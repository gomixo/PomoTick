package com.pomotick.system

import android.os.Build

/**
 * OEM/ROM 检测工具。
 *
 * 用于在 OPPO、realme、OnePlus 等运行 ColorOS / ColorOS Watch 系统的设备上,
 * 启用针对其功耗/通知策略的兜底行为(如息屏到点直接拉起 Activity)。
 */
object OemDetector {

    /**
     * 当前设备是否为 OPPO / realme / OnePlus / ColorOS 系。
     *
     * 检测维度:
     * - Build.MANUFACTURER / Build.BRAND 包含 oppo / realme / oneplus
     * - 存在 ColorOS 特征类(com.coloros.battery.BatteryMainActivity)
     */
    fun isOppoOrColorOs(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()
        return manufacturer.contains("oppo") || brand.contains("oppo") ||
                manufacturer.contains("realme") || brand.contains("realme") ||
                manufacturer.contains("oneplus") || brand.contains("oneplus")
    }
}
