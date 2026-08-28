package com.aurora.app.core.monitor;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Locale;

/**
 * 温度监视器数据源：
 * <ul>
 *   <li>电池温度：直接读取电源管理 sysfs（如 /sys/class/power_supply/battery/temp），
 *       不依赖 BatteryManager——部分机型的 getIntProperty 恒为 0 或未实现；</li>
 *   <li>CPU 温度：遍历 /sys/class/thermal 下的 thermal_zone 目录，读取 temp 节点，
 *       优先取 type 含 cpu/tsens 的热区，否则取所有热区最大值作为近似热点温度。</li>
 * </ul>
 * 所有温度统一返回摄氏温度（float，一位小数精度足够）。
 */
public final class TemperatureMonitor {

    private TemperatureMonitor() {
    }

    /** 电池温度（°C）。无电池或读取失败返回 -1。 */
    public static float readBatteryTempC(Context context) {
        // 电源 sysfs 温度：常规值以 0.1°C 为单位（如 350 = 35.0°C），个别机型为 1°C 或毫摄氏度，按数值大小粗略归一
        String[] paths = {
                "/sys/class/power_supply/battery/temp",
                "/sys/class/power_supply/Battery/temp",
                "/sys/class/power_supply/bms/temp",
        };
        for (String path : paths) {
            long raw = readLongFirstLine(path);
            if (raw > 0) {
                // TODO(真机校准)：不同厂商 sysfs temp 单位不一（0.1°C / 1°C / 毫摄氏），
                // 目前按数值量级粗略归一，若某机型读数异常需在此补充该机型的单位规则
                return raw > 1000 ? raw / 1000f : raw / 10f;
            }
        }
        return -1f;
    }

    /** CPU 温度（°C）。读取失败返回 -1。 */
    public static float readCpuTempC() {
        File zones = new File("/sys/class/thermal");
        File[] dirs = zones.listFiles();
        if (dirs == null) {
            return -1f;
        }
        float matched = -1f;   // 命中 cpu/tsens 的热区温度
        float maxAll = -1f;    // 所有热区的最大温度（兜底）
        for (File dir : dirs) {
            String name = dir.getName();
            if (!dir.isDirectory() || !name.startsWith("thermal_zone")) {
                continue;
            }
            String type = readFirstLine(new File(dir, "type"));
            long raw = readLongFirstLine(new File(dir, "temp").getAbsolutePath());
            if (raw <= 0) {
                continue;
            }
            float tempC = raw > 1000 ? raw / 1000f : raw / 10f; // 多数为毫摄氏度，个别为 0.1°C
            maxAll = Math.max(maxAll, tempC);
            if (type != null && (type.contains("cpu") || type.contains("tsens") || type.contains("soc"))) {
                matched = Math.max(matched, tempC);
            }
        }
        return matched > 0 ? matched : maxAll;
    }

    private static String readFirstLine(File file) {
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line = br.readLine();
            return line == null ? null : line.trim();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static long readLongFirstLine(String path) {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line = br.readLine();
            if (line == null) {
                return 0;
            }
            return Long.parseLong(line.trim());
        } catch (Exception ignored) {
            return 0;
        }
    }

    /** 格式化温度文本：xx.x°C；无效值返回 "--" 。 */
    public static String formatTemp(float tempC) {
        if (tempC < 0) {
            return "--";
        }
        return String.format(Locale.US, "%.1f°C", tempC);
    }
}
