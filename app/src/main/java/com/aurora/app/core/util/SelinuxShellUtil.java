package com.aurora.app.core.util;

import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * 与 {@link com.aurora.app.ui.selinux.SelinuxManagerActivity} 一致：通过 {@code su -c} 执行命令，
 * 用于读取内核真实 {@code getenforce} 结果（部分环境下 Java API 与 sysfs 不可靠）。
 */
public final class SelinuxShellUtil {

    public static final String MODE_ENFORCING = "enforcing";
    public static final String MODE_PERMISSIVE = "permissive";

    private SelinuxShellUtil() {
    }

    public static final class ShellResult {
        public final boolean success;
        public final String stdout;
        public final String stderr;

        public ShellResult(boolean success, String stdout, String stderr) {
            this.success = success;
            this.stdout = stdout != null ? stdout : "";
            this.stderr = stderr != null ? stderr : "";
        }
    }

    /**
     * 与 {@code ProcessBuilder("su", "-c", command)} 行为一致；带超时避免主线程误用时卡死。
     *
     * <p><b>超时对所有 API 版本都生效</b>：以前只在 API 26+ 用 {@code waitFor(timeout)}，
     * 低版本是无期限阻塞；而它会在开机广播的 {@code onReceive} 里被调用（API 24 也支持），
     * 一旦 su 不返回就是 ANR。这里统一改成「轮询 exitValue + 截止时间」。
     *
     * <p>stdout / stderr 由两个线程并发读取：只 wait 不读的话，子进程输出超过管道缓冲
     * 就会把双方一起卡住（{@code pm uninstall} 这类命令输出并不总是只有一行）。
     */
    public static ShellResult runSu(String command, long timeoutMs) {
        Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", command).start();
            final Process p = process;
            final StringBuffer stdout = new StringBuffer();
            final StringBuffer stderr = new StringBuffer();
            Thread outThread = drain(p.getInputStream(), stdout, "aurora-su-stdout");
            Thread errThread = drain(p.getErrorStream(), stderr, "aurora-su-stderr");

            long deadline = SystemClock.elapsedRealtime() + Math.max(200L, timeoutMs);
            int exit = Integer.MIN_VALUE;
            while (true) {
                try {
                    exit = p.exitValue();
                    break;
                } catch (IllegalThreadStateException stillRunning) {
                    if (SystemClock.elapsedRealtime() >= deadline) {
                        p.destroy();
                        return new ShellResult(false, stdout.toString(), "timeout");
                    }
                    try {
                        Thread.sleep(50L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        p.destroy();
                        return new ShellResult(false, stdout.toString(), "interrupted");
                    }
                }
            }
            // 进程已退出，管道里的剩余内容很快读完；join 上限只为极端情况兜底
            try {
                outThread.join(1000L);
                errThread.join(1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new ShellResult(exit == 0, stdout.toString(), stderr.toString());
        } catch (Exception e) {
            return new ShellResult(false, "", e.getMessage() == null ? "" : e.getMessage());
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    /** 后台把流读干，线程结束后自动关闭。 */
    private static Thread drain(final java.io.InputStream stream,
                                final StringBuffer sink,
                                String name) {
        Thread t = new Thread(() -> {
            try (java.io.InputStream in = stream;
                 BufferedReader reader = new BufferedReader(new InputStreamReader(in))) {
                char[] buf = new char[1024];
                int n;
                while ((n = reader.read(buf)) > 0) {
                    sink.append(buf, 0, n);
                }
            } catch (Exception ignored) {
            }
        }, name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** 与 {@link com.aurora.app.ui.selinux.SelinuxManagerActivity} 内解析逻辑一致：解析 getenforce 单行输出 */
    public static String normalizeGetenforceOutput(String modeRaw) {
        if (modeRaw == null) {
            return "";
        }
        String lower = modeRaw.trim().toLowerCase();
        if (lower.startsWith(MODE_ENFORCING)) {
            return MODE_ENFORCING;
        }
        if (lower.startsWith(MODE_PERMISSIVE)) {
            return MODE_PERMISSIVE;
        }
        if (lower.startsWith("disabled")) {
            return "disabled";
        }
        return lower;
    }
}
