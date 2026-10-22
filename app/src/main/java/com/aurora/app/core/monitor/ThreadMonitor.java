package com.aurora.app.core.monitor;

import com.aurora.app.core.root.RootShell;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 线程监视器数据源：统计系统当前全部线程数，以及线程数最多的进程。
 *
 * <h3>为什么要走 root</h3>
 * 原实现遍历 {@code /proc/[pid]/task} 目录。但 Android 12+ 的 SELinux
 * 不允许 untrusted_app 读取其他 uid 进程的 {@code task} 目录（实测 {@code Permission denied}），
 * 连 {@code listFiles()} 都返回 null，于是线程总数恒为 0 / 只统计到自己。
 *
 * <p>改走一条 root 命令：{@code ps -A -T} 会把每个线程各列一行，
 * 用 awk 按 pid 聚合即可同时得到总数与每进程线程数——一次往返、一个 awk 进程，
 * 实测比原来「枚举 300 个 task 目录」还快。
 *
 * <p>root 不可用时回退到原来的目录遍历（在 Android 11 及以下、或策略放宽的机型上仍有效）。
 */
public final class ThreadMonitor {

    /**
     * 一次性输出总数与每进程线程数。
     * 输出格式：首行 {@code TOTAL|<总数>}，其后每行 {@code <线程数>|<pid>|<进程名>}。
     */
    private static final String DUMP_CMD =
            "ps -A -T -w -o PID,ARGS 2>/dev/null"
                    + " | awk 'NR>1 {c[$1]++; n[$1]=$2; t++}"
                    + " END {printf \"TOTAL|%d\\n\", t;"
                    + " for (p in c) printf \"%d|%s|%s\\n\", c[p], p, n[p]}'";

    /** 快照缓存时长：折叠态与展开态在同一次刷新里各取一次，避免重复执行命令。 */
    private static final long CACHE_TTL_MS = 800L;

    private static volatile Snapshot cached;
    private static volatile long cachedAtMs;

    private ThreadMonitor() {
    }

    /** 一次采集的结果：线程总数 + 线程数最多的若干进程。 */
    public static final class Snapshot {
        /** 系统全部线程数。 */
        public final int totalThreads;
        /** 按线程数降序的进程列表。 */
        public final List<Entry> entries;

        Snapshot(int totalThreads, List<Entry> entries) {
            this.totalThreads = totalThreads;
            this.entries = entries;
        }

        /** 取前 count 项，格式化成 "进程名 N线程"。 */
        public List<String> topLabels(int count) {
            List<String> out = new ArrayList<>();
            int n = Math.min(count, entries.size());
            for (int i = 0; i < n; i++) {
                Entry e = entries.get(i);
                out.add(e.name + " " + e.threads + "线程");
            }
            return out;
        }
    }

    /** 单进程的线程数。 */
    public static final class Entry {
        public final String name;
        public final int threads;

        Entry(String name, int threads) {
            this.name = name;
            this.threads = threads;
        }
    }

    /**
     * 统计系统全部线程总数；采集失败返回 0。
     */
    public static int readSystemThreadCount() {
        return snapshot().totalThreads;
    }

    /**
     * 返回线程数最多的前 {@code count} 个进程，格式为 "进程名 N线程"。
     * 供展开大窗展示。
     */
    public static List<String> readTopThreadProcesses(int count) {
        return snapshot().topLabels(count);
    }

    /** 取快照（带 800ms 缓存，保证同一次刷新的两次调用只执行一条命令）。 */
    private static Snapshot snapshot() {
        Snapshot local = cached;
        long now = System.currentTimeMillis();
        if (local != null && now - cachedAtMs < CACHE_TTL_MS) {
            return local;
        }
        Snapshot fresh = readViaRoot();
        if (fresh.totalThreads <= 0) {
            fresh = readDirect();
        }
        cached = fresh;
        cachedAtMs = System.currentTimeMillis();
        return fresh;
    }

    /** root 采集：ps -A -T 列出全部线程，awk 按 pid 聚合。 */
    private static Snapshot readViaRoot() {
        if (!RootShell.get().isRootAvailable()) {
            return new Snapshot(0, Collections.emptyList());
        }
        RootShell.Result result = RootShell.get().exec(DUMP_CMD, 8000L);
        if (!result.isSuccess()) {
            return new Snapshot(0, Collections.emptyList());
        }
        return parseDump(result.out);
    }

    /**
     * 解析 {@link #DUMP_CMD} 的输出。包级可见，便于 javac 单测直接喂真实输出验证解析。
     */
    static Snapshot parseDump(String dump) {
        int total = 0;
        List<Entry> entries = new ArrayList<>();
        if (dump == null || dump.isEmpty()) {
            return new Snapshot(0, Collections.emptyList());
        }
        for (String raw : dump.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] cols = line.split("\\|");
            if (cols.length >= 2 && "TOTAL".equals(cols[0])) {
                total = parseInt(cols[1]);
                continue;
            }
            if (cols.length < 3) {
                continue;
            }
            int threads = parseInt(cols[0]);
            if (threads <= 0) {
                continue;
            }
            entries.add(new Entry(cols[2], threads));
        }
        entries.sort((a, b) -> Integer.compare(b.threads, a.threads));
        return new Snapshot(Math.max(total, 0), entries);
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    /** 无 root 时的回退：直接遍历 /proc/[pid]/task（仅部分系统可读）。 */
    private static Snapshot readDirect() {
        int total = 0;
        List<Entry> entries = new ArrayList<>();
        File[] pids = new File("/proc").listFiles();
        if (pids == null) {
            return new Snapshot(0, Collections.emptyList());
        }
        for (File pidDir : pids) {
            if (!pidDir.isDirectory() || !isNumeric(pidDir.getName())) {
                continue;
            }
            File[] tasks = new File(pidDir, "task").listFiles();
            if (tasks == null || tasks.length == 0) {
                continue;
            }
            total += tasks.length;
            entries.add(new Entry(readComm(pidDir), tasks.length));
        }
        entries.sort((a, b) -> Integer.compare(b.threads, a.threads));
        return new Snapshot(total, entries);
    }

    /** 读取进程名（/proc/[pid]/comm，可能截断为 15 字符）。 */
    private static String readComm(File pidDir) {
        try (BufferedReader br = new BufferedReader(
                new FileReader(new File(pidDir, "comm")))) {
            String line = br.readLine();
            return line == null ? pidDir.getName() : line.trim();
        } catch (Exception ignored) {
            return pidDir.getName();
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
}
