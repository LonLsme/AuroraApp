package com.aurora.app.core.monitor;

import java.io.BufferedReader;
import java.io.File;
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

    /**
     * Swap / ZRAM 占用率（0-100）：{@code (SwapTotal - SwapFree) / SwapTotal}。
     * 设备未启用 swap（SwapTotal 为 0）或读取失败时返回 -1。
     */
    public static int swapUsedPercent() {
        long total = 0L;
        long free = 0L;
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("SwapTotal:")) {
                    total = parseKb(line);
                } else if (line.startsWith("SwapFree:")) {
                    free = parseKb(line);
                    break;
                }
            }
        } catch (Exception ignored) {
            return -1;
        }
        if (total <= 0) {
            return -1;
        }
        return (int) Math.min(100, Math.max(0, (total - free) * 100 / total));
    }

    /** DDR 频率缓存：{@code Integer.MIN_VALUE} = 尚未尝试；-1 = 不可用（不再重试）。 */
    private static volatile int ddrFreqCacheMhz = Integer.MIN_VALUE;

    /**
     * DDR 频率（MHz）：遍历 {@code /sys/class/devfreq}，取名字含 {@code ddr} / {@code dmc}
     * 的节点读 {@code cur_freq}（Hz）。
     *
     * <p>只尝试一次：部分 ROM 的 devfreq 目录受 SELinux 限制，读不到就缓存 -1，
     * 避免每轮刷新反复访问节点产生审计日志。
     */
    public static int readDdrFreqMhz() {
        int cached = ddrFreqCacheMhz;
        if (cached != Integer.MIN_VALUE) {
            return cached;
        }
        int mhz = -1;
        File[] entries = new File("/sys/class/devfreq").listFiles();
        if (entries != null) {
            for (File entry : entries) {
                String name = entry.getName().toLowerCase(Locale.US);
                if (!name.contains("ddr") && !name.contains("dmc")) {
                    continue;
                }
                long hz = readLongFirstLine(new File(entry, "cur_freq").getPath());
                if (hz > 0) {
                    mhz = (int) (hz / 1_000_000L);
                    break;
                }
            }
        }
        ddrFreqCacheMhz = mhz;
        return mhz;
    }

    /** 读取 meminfo 中指定字段的数值（KB）。 */
    private static long readMemInfoField(String field) {
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith(field + ":")) {
                    return parseKb(line);
                }
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    /** 解析 {@code "MemTotal:\t  123456 kB"} 形式的 KB 数值；格式异常返回 0。 */
    private static long parseKb(String line) {
        String[] parts = line.split("\\s+");
        if (parts.length < 2) {
            return 0L;
        }
        try {
            return Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** 读取文件首行的 long 值（kHz / Hz 等）；失败返回 0。 */
    private static long readLongFirstLine(String path) {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line = br.readLine();
            return line == null ? 0L : Long.parseLong(line.trim());
        } catch (Exception ignored) {
            return 0L;
        }
    }
}
