package com.aurora.app.core.monitor;

import com.aurora.app.core.util.BatterySysFiles;

/**
 * 功耗监视器数据源：
 * 功耗 = 电池电压 × 电流（P = U × I）。
 * <ul>
 *   <li>电压：BatterySysFiles.readVoltageNowMicroVolts（µV）；</li>
 *   <li>电流：BatterySysFiles.readCurrentNowMicroAmps（µA，充电为负，放电为正）；</li>
 * </ul>
 * 计算：mW = |µV × µA| / 1e9。
 * 电流为 0（无负载/未采样）或读取失败时返回 -1，由 UI 显示 "--"。
 */
public final class PowerMonitor {

    private PowerMonitor() {
    }

    /**
     * 当前整机功耗（mW）。数据不可用返回 -1。
     */
    public static double readPowerMw() {
        long microVolts = BatterySysFiles.readVoltageNowMicroVolts();
        long microAmps = BatterySysFiles.readCurrentNowMicroAmps();
        if (microVolts <= 0 || microAmps == 0) {
            return -1d;
        }
        // µV × µA = 皮瓦(pW)，除以 1e9 得毫瓦(mW)
        return Math.abs((double) microVolts * microAmps) / 1e9d;
    }
}
