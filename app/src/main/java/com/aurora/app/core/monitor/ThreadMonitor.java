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
}
