package com.aurora.app.core.monitor;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 进程监视器数据源：
 * 遍历 /proc/[pid]/stat 统计每个进程的 CPU 时间（utime+stime），
 * 通过与上一次采样的差值估算各进程 CPU 占用，取占用最高的前 N 个进程。
 *
 * <p>解析规则（Linux /proc/[pid]/stat 格式）：
 * 第 1 段为 pid，第 2 段为括在 () 中的 comm（可能含空格/括号），
 * 括号后第 3 段起为 state、ppid、...，其中 utime 是第 14 字段、stime 是第 15 字段。
 * 因此从括号后的片段索引 11、12 即为 utime / stime。
 */
public final class ProcessMonitor {

    /** pid -> 上一次采样到的 CPU 时间（tick），用于计算差值。 */
    private final Map<Integer, Long> prevTimes = new HashMap<>();

    /** 单个进程的采样结果。 */
    public static final class ProcEntry {
        public final int pid;
        public final String name;
        public final long cpuTicks;

        ProcEntry(int pid, String name, long cpuTicks) {
            this.pid = pid;
            this.name = name;
            this.cpuTicks = cpuTicks;
        }
    }

    /**
     * 读取当前系统所有进程的 CPU 时间。
     * 只解析数字命名的 /proc 目录，跳过内核线程目录无 task 的特殊情况。
     */
    private static List<ProcEntry> readAllProcesses() {
        List<ProcEntry> list = new ArrayList<>();
        File[] dirs = new File("/proc").listFiles();
        if (dirs == null) {
            return list;
        }
        for (File dir : dirs) {
            String name = dir.getName();
            if (!dir.isDirectory() || !isNumeric(name)) {
                continue;
            }
            ProcEntry entry = readProcStat(dir);
            if (entry != null) {
                list.add(entry);
            }
        }
        return list;
    }

    /** 读取单个进程的 stat 文件，解析 pid/comm/cpuTicks；失败返回 null。 */
    private static ProcEntry readProcStat(File procDir) {
        File statFile = new File(procDir, "stat");
        try (BufferedReader br = new BufferedReader(new FileReader(statFile))) {
            String s = br.readLine();
            if (s == null) {
                return null;
            }
            int open = s.indexOf('(');
            int close = s.lastIndexOf(')');
            if (open < 0 || close <= open) {
                return null;
            }
            int pid = Integer.parseInt(s.substring(0, open).trim());
            String comm = s.substring(open + 1, close);
            String[] rest = s.substring(close + 1).trim().split("\\s+");
            if (rest.length <= 13) {
                return null;
            }
            // rest[0]=state(field3) ... rest[11]=utime(field14) rest[12]=stime(field15)
            long utime = Long.parseLong(rest[11]);
            long stime = Long.parseLong(rest[12]);
            return new ProcEntry(pid, comm, utime + stime);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isNumeric(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 返回当前 CPU 占用最高的前 {@code count} 个进程，格式为 "名称 xx%"。
     * 通过与上次采样的 CPU 时间差排序；首次采样无基线时返回空列表。
     */
    public List<String> readTopProcesses(int count) {
        List<ProcEntry> current = readAllProcesses();
        List<String> result = new ArrayList<>();
        if (current.isEmpty()) {
            return result;
        }
        // 计算每个进程的 CPU 时间增量，deltas 元素为 {增量, pid}
        long totalDelta = 0;
        List<long[]> deltas = new ArrayList<>();
        for (ProcEntry e : current) {
            Long prev = prevTimes.get(e.pid);
            if (prev != null && e.cpuTicks >= prev) {
                long d = e.cpuTicks - prev;
                totalDelta += d;
                deltas.add(new long[]{d, e.pid});
            }
        }
        // 更新基线
        prevTimes.clear();
        for (ProcEntry e : current) {
            prevTimes.put(e.pid, e.cpuTicks);
        }
        // 按增量降序取前 N，直接按 pid 反查进程名
        deltas.sort(Comparator.comparingLong(a -> -a[0]));
        int n = Math.min(count, deltas.size());
        for (int i = 0; i < n; i++) {
            long d = deltas.get(i)[0];
            int percent = totalDelta > 0 ? (int) (d * 100 / totalDelta) : 0;
            String procName = null;
            for (ProcEntry e : current) {
                if (e.pid == (int) deltas.get(i)[1]) {
                    procName = e.name;
                    break;
                }
            }
            if (procName == null) {
                continue;
            }
            result.add(procName + " " + Math.min(percent, 99) + "%");
        }
        return result;
    }

    /** 清理基线，释放 pid 引用（窗口销毁时调用）。 */
    public void reset() {
        prevTimes.clear();
    }
}
