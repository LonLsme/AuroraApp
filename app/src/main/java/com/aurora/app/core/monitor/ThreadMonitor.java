package com.aurora.app.core.monitor;

import java.io.File;

/**
 * 线程监视器数据源：
 * 遍历 /proc/[pid]/task 目录统计系统当前全部线程数。
 * 线程数 = 所有进程的 task 子目录个数之和。
 *
 * <p>该统计本身有一定开销（需遍历 /proc 下数百个目录），
 * 建议由窗口以 2s 左右的低频刷新，避免频繁遍历影响性能。
 */
public final class ThreadMonitor {

    private ThreadMonitor() {
    }

    /**
     * 统计系统全部线程总数；遍历失败或进程退出等异常目录会被跳过。
     */
    public static int readSystemThreadCount() {
        int total = 0;
        File[] pids = new File("/proc").listFiles();
        if (pids == null) {
            return 0;
        }
        for (File pidDir : pids) {
            if (!pidDir.isDirectory() || !isNumeric(pidDir.getName())) {
                continue;
            }
            File taskDir = new File(pidDir, "task");
            File[] tasks = taskDir.listFiles();
            if (tasks != null) {
                total += tasks.length;
            }
        }
        return total;
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
     * 返回线程数最多的前 {@code count} 个进程，格式为 "进程名 N线程"。
     * 供展开大窗展示；遍历 /proc 开销大，仅在后台线程调用。
     */
    public static java.util.List<String> readTopThreadProcesses(int count) {
        java.util.List<ProcThreads> list = new java.util.ArrayList<>();
        File[] pids = new File("/proc").listFiles();
        if (pids != null) {
            for (File pidDir : pids) {
                if (!pidDir.isDirectory() || !isNumeric(pidDir.getName())) {
                    continue;
                }
                File taskDir = new File(pidDir, "task");
                File[] tasks = taskDir.listFiles();
                if (tasks == null || tasks.length == 0) {
                    continue;
                }
                list.add(new ProcThreads(readComm(pidDir), tasks.length));
            }
        }
        list.sort((a, b) -> Integer.compare(b.threads, a.threads));
        java.util.List<String> result = new java.util.ArrayList<>();
        int n = Math.min(count, list.size());
        for (int i = 0; i < n; i++) {
            ProcThreads pt = list.get(i);
            result.add(pt.name + " " + pt.threads + "线程");
        }
        return result;
    }

    /** 读取进程名（/proc/[pid]/comm，可能截断为 15 字符）。 */
    private static String readComm(File pidDir) {
        try (java.io.BufferedReader br =
                     new java.io.BufferedReader(new java.io.FileReader(new File(pidDir, "comm")))) {
            String line = br.readLine();
            return line == null ? pidDir.getName() : line.trim();
        } catch (Exception ignored) {
            return pidDir.getName();
        }
    }

    /** 进程线程数条目。 */
    private static final class ProcThreads {
        final String name;
        final int threads;

        ProcThreads(String name, int threads) {
            this.name = name;
            this.threads = threads;
        }
    }
}
