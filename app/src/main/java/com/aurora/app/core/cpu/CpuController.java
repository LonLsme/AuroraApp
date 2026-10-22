package com.aurora.app.core.cpu;

import com.aurora.app.core.root.RootShell;
import com.aurora.app.core.util.CpuSysFiles;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CPU 频率与调度策略的读写控制。
 *
 * <p><b>读取</b>走普通文件 IO——cpufreq 的只读节点（scaling_cur_freq、scaling_available_* 等）
 * 普通应用就有权限，没必要动用 root，也快得多。
 *
 * <p><b>写入</b>必须 root，统一交给 {@link RootShell}。部分内核的 cpufreq 节点权限是 0644
 * （root 可写），但也有 0444 的，所以写入失败时会先 chmod 再重试一次。
 *
 * <h3>集群（cluster）的处理</h3>
 * 大小核架构下，同一集群的核心共享调频策略。判断依据是每个核心
 * {@code cpufreq/related_cpus} 的内容——同一集群的核心读到的值相同。
 * 按这个值分组即可得到集群划分，不需要依赖机型白名单。
 *
 * <p>设置频率/调度器时，会对集群内<b>所有</b>核心下发，避免只改了其中一个。
 */
public final class CpuController {

    private static final String CPU_BASE = "/sys/devices/system/cpu/";

    /** 可用频率节点名不统一：高通是 scaling_available_frequencies，部分内核写作 scaling_available_freq。 */
    private static final String[] AVAILABLE_FREQ_NODES = {
            "scaling_available_frequencies",
            "scaling_available_freq",
    };

    private CpuController() {
    }

    /** 一个 CPU 集群的状态快照。 */
    public static final class Cluster {
        /** 属于该集群的核心编号，升序。 */
        public final int[] cpus;
        /** 可用频率列表（kHz），已按升序排列；读不到时为空数组。 */
        public final long[] availableFreqsKhz;
        /** 可用调度器列表；读不到时为空数组。 */
        public final String[] availableGovernors;
        /** 当前调度器。 */
        public final String governor;
        /** 当前频率上下限与实时频率（kHz），读不到时为 0。 */
        public final long minFreqKhz;
        public final long maxFreqKhz;
        public final long curFreqKhz;

        Cluster(int[] cpus, long[] availableFreqsKhz, String[] availableGovernors,
                String governor, long minFreqKhz, long maxFreqKhz, long curFreqKhz) {
            this.cpus = cpus;
            this.availableFreqsKhz = availableFreqsKhz;
            this.availableGovernors = availableGovernors;
            this.governor = governor;
            this.minFreqKhz = minFreqKhz;
            this.maxFreqKhz = maxFreqKhz;
            this.curFreqKhz = curFreqKhz;
        }

        /** 集群的展示名，如 "CPU0-3"（单核时显示 "CPU0"）。 */
        public String displayName() {
            if (cpus.length == 0) {
                return "CPU";
            }
            return cpus.length == 1
                    ? "CPU" + cpus[0]
                    : "CPU" + cpus[0] + "-" + cpus[cpus.length - 1];
        }

        /** 该集群是否支持调频（能读到可用频率列表）。 */
        public boolean isAdjustable() {
            return availableFreqsKhz.length > 0;
        }
    }

    /**
     * 读取全部集群的当前状态。
     * 纯文件 IO，不需要 root，可在采集线程调用。
     */
    public static List<Cluster> readClusters() {
        int cpuCount = Math.max(1, CpuSysFiles.getSysfsCpuCount());
        // 按 related_cpus 的内容分组，同组即同一集群
        Map<String, int[]> groups = new LinkedHashMap<>();
        for (int i = 0; i < cpuCount; i++) {
            String related = readLine(CPU_BASE + "cpu" + i + "/cpufreq/related_cpus");
            String key = (related == null || related.isEmpty())
                    ? String.valueOf(i)      // 读不到就退化成「一核一集群」
                    : related;
            if (!groups.containsKey(key)) {
                groups.put(key, related == null || related.isEmpty()
                        ? new int[]{i}
                        : parseInts(related));
            }
        }

        List<Cluster> clusters = new ArrayList<>(groups.size());
        for (int[] cpus : groups.values()) {
            if (cpus.length == 0) {
                continue;
            }
            int rep = cpus[0];  // 用集群第一个核心作为代表读取策略
            clusters.add(new Cluster(
                    cpus,
                    readAvailableFreqs(rep),
                    readAvailableGovernors(rep),
                    readLine(CPU_BASE + "cpu" + rep + "/cpufreq/scaling_governor"),
                    CpuSysFiles.readCpuMinFreqKhz(rep),
                    CpuSysFiles.readCpuMaxFreqKhz(rep),
                    CpuSysFiles.readCpuCurFreqKhz(rep)));
        }
        return clusters;
    }

    /** 设置集群调度器。 */
    public static boolean setGovernor(Cluster cluster, String governor) {
        if (cluster == null || governor == null || governor.isEmpty()) {
            return false;
        }
        return writeToCluster(cluster, "scaling_governor", governor);
    }

    /** 设置集群最低频率（kHz）。 */
    public static boolean setMinFreqKhz(Cluster cluster, long khz) {
        if (cluster == null || khz <= 0) {
            return false;
        }
        return writeToCluster(cluster, "scaling_min_freq", String.valueOf(khz));
    }

    /** 设置集群最高频率（kHz）。 */
    public static boolean setMaxFreqKhz(Cluster cluster, long khz) {
        if (cluster == null || khz <= 0) {
            return false;
        }
        return writeToCluster(cluster, "scaling_max_freq", String.valueOf(khz));
    }

    /**
     * 向集群内所有核心写入同一个节点值。
     * 只要有一个核心写成功即视为成功——部分核心可能处于 offline 状态，写不进去属正常。
     */
    private static boolean writeToCluster(Cluster cluster, String node, String value) {
        RootShell shell = RootShell.get();
        if (!shell.isRootAvailable()) {
            return false;
        }
        boolean anySuccess = false;
        for (int cpu : cluster.cpus) {
            if (writeCpuNode(shell, cpu, node, value)) {
                anySuccess = true;
            }
        }
        return anySuccess;
    }

    /**
     * 写入单个核心的 cpufreq 节点。
     * 先尝试直接写；失败说明权限不足，chmod 后重试一次。
     */
    private static boolean writeCpuNode(RootShell shell, int cpu, String node, String value) {
        String path = CPU_BASE + "cpu" + cpu + "/cpufreq/" + node;
        // value 只可能是数字或来自系统可用列表的标识符，仍统一转义以防万一
        String safe = RootShell.escape(value);
        if (shell.exec("echo " + safe + " > " + RootShell.escape(path), 3000L).isSuccess()) {
            return true;
        }
        return shell.exec("chmod 0664 " + RootShell.escape(path)
                + " && echo " + safe + " > " + RootShell.escape(path), 3000L).isSuccess();
    }

    // ───────────────── 读取辅助 ─────────────────

    private static long[] readAvailableFreqs(int cpu) {
        for (String node : AVAILABLE_FREQ_NODES) {
            String line = readLine(CPU_BASE + "cpu" + cpu + "/cpufreq/" + node);
            if (line == null || line.isEmpty()) {
                continue;
            }
            long[] parsed = parseLongs(line);
            if (parsed.length > 0) {
                return parsed;
            }
        }
        return new long[0];
    }

    private static String[] readAvailableGovernors(int cpu) {
        String line = readLine(CPU_BASE + "cpu" + cpu + "/cpufreq/scaling_available_governors");
        if (line == null || line.isEmpty()) {
            return new String[0];
        }
        String[] parts = line.trim().split("\\s+");
        List<String> list = new ArrayList<>(parts.length);
        for (String p : parts) {
            if (!p.isEmpty()) {
                list.add(p);
            }
        }
        return list.toArray(new String[0]);
    }

    /** 读取文本首行；不可读时返回 null（区分「读到空内容」与「读不到」）。 */
    private static String readLine(String path) {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            return br.readLine();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static int[] parseInts(String raw) {
        String[] parts = raw.trim().split("\\s+");
        List<Integer> list = new ArrayList<>(parts.length);
        for (String p : parts) {
            try {
                list.add(Integer.valueOf(Integer.parseInt(p)));
            } catch (NumberFormatException ignored) {
            }
        }
        int[] out = new int[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = list.get(i);
        }
        return out;
    }

    private static long[] parseLongs(String raw) {
        String[] parts = raw.trim().split("\\s+");
        List<Long> list = new ArrayList<>(parts.length);
        for (String p : parts) {
            try {
                long v = Long.parseLong(p);
                if (v > 0) {
                    list.add(Long.valueOf(v));
                }
            } catch (NumberFormatException ignored) {
            }
        }
        long[] out = new long[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = list.get(i).longValue();
        }
        java.util.Arrays.sort(out);
        return out;
    }
}
