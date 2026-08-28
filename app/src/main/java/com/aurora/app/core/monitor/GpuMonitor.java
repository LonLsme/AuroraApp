package com.aurora.app.core.monitor;

import java.io.BufferedReader;
import java.io.FileReader;

/**
 * GPU 使用率监视器数据源：
 * 主要面向高通 GPU（kgsl），读取两种常见节点：
 * <ul>
 *   <li>/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage：部分内核直接给出百分比；</li>
 *   <li>/sys/class/kgsl/kgsl-3d0/gpubusy：内容为 "busy total" 两个计数，busy/total 即占用率。</li>
 * </ul>
 * 其他 SoC（Mali 等）节点路径不同，读取失败时返回 -1，由 UI 显示 "--"。
 */
public final class GpuMonitor {

    private GpuMonitor() {
    }

    /**
     * GPU 使用率（0-100）；读取失败返回 -1。
     */
    public static int readGpuUsagePercent() {
        // 方式一：直接百分比节点
        long pct = readLongFirstLine("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage");
        if (pct > 0) {
            return (int) Math.min(100, pct);
        }
        // 方式二：busy/total 计数节点
        try (BufferedReader br = new BufferedReader(new FileReader("/sys/class/kgsl/kgsl-3d0/gpubusy"))) {
            String line = br.readLine();
            if (line != null) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 2) {
                    long busy = Long.parseLong(parts[0]);
                    long total = Long.parseLong(parts[1]);
                    if (total > 0) {
                        return (int) Math.min(100, busy * 100 / total);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    /**
     * GPU 当前频率（MHz）；读取失败返回 0。
     * <ul>
     *   <li>/sys/class/kgsl/kgsl-3d0/gpuclk：高通 kgsl，单位 kHz；</li>
     *   <li>/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq：devfreq 框架，单位 Hz。</li>
     * </ul>
     * 按数值量级粗略判断单位（≥1e6 视为 Hz，否则视为 kHz）。
     */
    public static int readGpuFreqMhz() {
        String[] paths = {
                "/sys/class/kgsl/kgsl-3d0/gpuclk",
                "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq",
        };
        for (String path : paths) {
            long raw = readLongFirstLine(path);
            if (raw > 0) {
                // TODO(真机校准)：gpuclk 与 devfreq 单位不同（kHz vs Hz），
                // 按数值量级猜测单位，若某机型读数异常需补充判断规则
                if (raw >= 1_000_000L) {
                    return (int) (raw / 1_000_000L); // Hz → MHz
                }
                return (int) (raw / 1000L); // kHz → MHz
            }
        }
        return 0;
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
}
