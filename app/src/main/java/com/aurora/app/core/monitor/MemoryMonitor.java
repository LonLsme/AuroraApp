package com.aurora.app.core.monitor;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.Locale;

/**
 * 内存监视器数据源：
 * 解析 /proc/meminfo 的 MemTotal / MemAvailable，计算使用率与已用/总量。
 * 纯文件读取，无 Android 依赖。
 */
public final class MemoryMonitor {

    private MemoryMonitor() {
    }

    /** 总内存（KB）。读取失败返回 0。 */
    public static long readTotalKb() {
        return readMemInfoField("MemTotal");
    }

    /** 可用内存（KB）。读取失败返回 0。 */
    public static long readAvailableKb() {
        return readMemInfoField("MemAvailable");
    }

    /** 内存使用率（0-100）；数据缺失返回 0。 */
    public static int usedPercent() {
        long total = readTotalKb();
        long avail = readAvailableKb();
        if (total <= 0 || avail < 0) {
            return 0;
        }
        return (int) Math.min(100, Math.max(0, (total - avail) * 100 / total));
    }

    /** 将 KB 格式化为 "x.x GB"。 */
    public static String formatGb(long kb) {
        return String.format(Locale.US, "%.1f GB", kb / 1024f / 1024f);
    }

    /** 读取 meminfo 中指定字段的数值（KB）。 */
    private static long readMemInfoField(String field) {
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith(field + ":")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 2) {
                        return Long.parseLong(parts[1]);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return 0;
    }
}
