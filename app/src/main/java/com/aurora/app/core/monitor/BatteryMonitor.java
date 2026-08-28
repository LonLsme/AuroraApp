package com.aurora.app.core.monitor;

import java.io.BufferedReader;
import java.io.FileReader;

/**
 * 电池监视器数据源：
 * 读取电池电量百分比（/sys/class/power_supply 下 battery 等目录的 capacity 节点）。
 * 不走 BatteryManager 接口（部分机型 getIntProperty 失效，与温度同理），
 * 直接读 sysfs 最可靠。
 */
public final class BatteryMonitor {

    private BatteryMonitor() {
    }

    /** 电池电量百分比（0-100）；读取失败返回 -1。 */
    public static int readBatteryPercent() {
        String[] paths = {
                "/sys/class/power_supply/battery/capacity",
                "/sys/class/power_supply/Battery/capacity",
                "/sys/class/power_supply/bms/capacity",
        };
        for (String path : paths) {
            long value = readLongFirstLine(path);
            if (value >= 0 && value <= 100) {
                return (int) value;
            }
        }
        return -1;
    }

    private static long readLongFirstLine(String path) {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line = br.readLine();
            if (line == null) {
                return -1;
            }
            return Long.parseLong(line.trim());
        } catch (Exception ignored) {
            return -1;
        }
    }
}
