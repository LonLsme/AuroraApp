package com.aurora.app.core.monitor;

import com.aurora.app.core.root.RootShell;
import com.aurora.app.core.util.NodeReader;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
    /** 上次探测 DDR 频率的时间；失败后隔一段时间重试，而不是一次失败就永久 --。 */
    private static volatile long ddrProbeAtMs = 0L;
    private static final long DDR_RETRY_MS = 30_000L;

    /**
     * DDR 频率（MHz）：遍历 {@code /sys/class/devfreq}，取名字含 {@code ddr} / {@code dmc}
     * 的节点读 {@code cur_freq}（Hz）。
     *
     * <p>读取走 {@link NodeReader}：devfreq 目录在 Android 12+ 上对普通进程不可 list，
     * 必须先 root {@code ls}。以前直接 {@code listFiles()} 且「读不到就永久缓存 -1」，
     * 结果是冷启动那一刻 root 还没授权，整次会话的 DDR 频率都显示 "--"。
     * 现在失败只缓存 30 秒，超时后重试一次。
     */
    public static int readDdrFreqMhz() {
        int cached = ddrFreqCacheMhz;
        if (cached >= 0) {
            return cached;
        }
        long now = System.currentTimeMillis();
        if (cached == -1 && now - ddrProbeAtMs < DDR_RETRY_MS) {
            return -1;
        }
        ddrProbeAtMs = now;
        int mhz = probeDdrFreqMhz();
        ddrFreqCacheMhz = mhz;
        return mhz;
    }

    private static int probeDdrFreqMhz() {
        for (String name : listDirNames("/sys/class/devfreq")) {
            String lower = name.toLowerCase(Locale.US);
            if (!lower.contains("ddr") && !lower.contains("dmc")) {
                continue;
            }
            String line = NodeReader.readFirstLine(
                    "/sys/class/devfreq/" + name + "/cur_freq", true);
            if (line == null || line.isEmpty()) {
                continue;
            }
            try {
                long hz = Long.parseLong(line.trim());
                if (hz > 0) {
                    return (int) (hz / 1_000_000L);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return -1;
    }

    /**
     * 列目录：普通 {@code listFiles()} 优先，失败时走 root {@code ls -1}。
     * Android 12+ 起 {@code /sys} 下不少目录对 untrusted_app 不可 list。
     */
    private static List<String> listDirNames(String dir) {
        File[] entries = new File(dir).listFiles();
        if (entries != null && entries.length > 0) {
            List<String> plain = new ArrayList<>(entries.length);
            for (File entry : entries) {
                if (entry.isDirectory()) {
                    plain.add(entry.getName());
                }
            }
            if (!plain.isEmpty()) {
                return plain;
            }
        }
        RootShell.Result result = RootShell.get().exec("ls -1 " + RootShell.escape(dir));
        if (result == null || !result.isSuccess()) {
            return Collections.emptyList();
        }
        return result.lines();
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
