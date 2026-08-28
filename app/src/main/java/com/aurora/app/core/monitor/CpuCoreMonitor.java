package com.aurora.app.core.monitor;

import com.aurora.app.core.util.CpuStatReader;

import java.io.BufferedReader;
import java.io.FileReader;

/**
 * CPU 各核心监视器数据源：
 * 展开大窗口需要「每个核心」的频率与占用率。
 * <ul>
 *   <li>占用率：复用 {@link CpuStatReader} 的 per-cpu 快照差值（跨刷新周期计算）；</li>
 *   <li>频率：读取 cpufreq sysfs（scaling_cur_freq / cpuinfo_cur_freq），kHz → MHz。</li>
 * </ul>
 * 与 LoadMonitor 类似，本类持有上一次 per-cpu 快照，由窗口定时驱动。
 */
public final class CpuCoreMonitor {

    /** 单个核心的采样结果。 */
    public static final class CoreState {
        public final int index;     // 核心序号
        public final int freqMhz;   // 当前频率（MHz）
        public final int percent;   // 占用率（0-100）

        CoreState(int index, int freqMhz, int percent) {
            this.index = index;
            this.freqMhz = freqMhz;
            this.percent = percent;
        }
    }

    /** 上一次 per-cpu 快照，用于计算各核占用率差值。 */
    private CpuStatReader[] prevPerCpu;

    /**
     * 读取全部核心的频率与占用率。
     * 数量以 /proc/stat 的 per-cpu 行数为准；某核频率读取失败时为 0。
     */
    public CoreState[] readCoreStates() {
        CpuStatReader.ProcCpuSnapshot snapshot = CpuStatReader.readProcCpuSnapshot();
        if (snapshot == null || snapshot.perCpu.length == 0) {
            return new CoreState[0];
        }
        int[] percents = CpuStatReader.computePerCorePercents(prevPerCpu, snapshot.perCpu);
        prevPerCpu = snapshot.perCpu;
        CoreState[] out = new CoreState[snapshot.perCpu.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = new CoreState(i, readCoreFreqMhz(i), percents != null ? percents[i] : 0);
        }
        return out;
    }

    /**
     * 读取指定核心的当前频率（MHz）。
     * 部分内核只开放其中一个节点，按顺序尝试；全部失败返回 0。
     */
    public static int readCoreFreqMhz(int coreIndex) {
        String[] patterns = {
                "/sys/devices/system/cpu/cpu%d/cpufreq/scaling_cur_freq",
                "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_cur_freq",
        };
        for (String pattern : patterns) {
            long khz = readLongFirstLine(String.format(java.util.Locale.US, pattern, coreIndex));
            if (khz > 0) {
                return (int) (khz / 1000);
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
