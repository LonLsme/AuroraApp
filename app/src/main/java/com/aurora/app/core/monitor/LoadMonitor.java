package com.aurora.app.core.monitor;

import com.aurora.app.core.util.CpuStatReader;

/**
 * 负载监视器数据源：
 * 利用 {@link CpuStatReader} 两次 /proc/stat 快照之差计算 CPU 总使用率，
 * 以及 /proc/loadavg 的 1/5/15 分钟平均负载。
 *
 * <p>注意：本类不是静态工具，而是持有上一次快照的状态对象，
 * 由窗口按固定周期（如 1s）调用 {@link #readCpuUsagePercent()}，
 * 两次调用之间的时间差即采样间隔，无需在调用方 sleep，避免卡 UI 线程。
 */
public final class LoadMonitor {

    /** 上一次的 CPU 聚合快照，用于与本次快照做差值。 */
    private CpuStatReader prevSnapshot;

    public LoadMonitor() {
        // 首次构造时读取一次基线，保证第一次 readCpuUsagePercent() 有可比的上一帧
        prevSnapshot = CpuStatReader.read();
    }

    /**
     * 计算自上次调用以来的 CPU 总使用率（0-100）。
     * 若 /proc/stat 读取失败或间隔过短，返回 0。
     */
    public int readCpuUsagePercent() {
        CpuStatReader cur = CpuStatReader.read();
        if (prevSnapshot == null || cur == null) {
            prevSnapshot = cur;
            return 0;
        }
        int[] pct = CpuStatReader.computePercents(prevSnapshot, cur);
        prevSnapshot = cur;
        return pct != null ? pct[0] : 0;
    }

    /**
     * 读取 /proc/loadavg 的 1/5/15 分钟平均负载。
     * 返回长度为 3 的 float 数组；读取失败返回 {0,0,0}。
     */
    public static float[] readLoadAvg() {
        try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader("/proc/loadavg"))) {
            String line = br.readLine();
            if (line == null) {
                return new float[]{0f, 0f, 0f};
            }
            String[] parts = line.trim().split("\\s+");
            float[] out = new float[3];
            for (int i = 0; i < 3 && i < parts.length; i++) {
                try {
                    out[i] = Float.parseFloat(parts[i]);
                } catch (NumberFormatException ignored) {
                    out[i] = 0f;
                }
            }
            return out;
        } catch (Exception ignored) {
            return new float[]{0f, 0f, 0f};
        }
    }
}
