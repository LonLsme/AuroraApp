package com.aurora.app.core.root;

import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Root 命令执行统一层。
 *
 * <h3>为什么需要它</h3>
 * 原来 10 处 {@code new ProcessBuilder("su", "-c", ...)} 散落在 6 个类里，每处超时、
 * 错误处理、su 路径回退都各写一遍。本类把这些统一掉，同时解决性能问题。
 *
 * <h3>性能：持久化会话</h3>
 * 每次 {@code su -c} 都要 fork 进程 + 走一遍 Magisk/KernelSU 的授权检查，实测 50～200ms。
 * 这里改为<b>启动一个长期存活的 su 进程，通过 stdin 写命令、stdout 读结果</b>，
 * 单条命令降到毫秒级。对「悬浮窗每 500ms 采集一次」这类高频场景是数量级的差别。
 *
 * <p>会话线程安全性：所有命令经由单线程 {@link #SHELL_EXECUTOR} 串行提交，
 * 保证「写命令 → 读输出」严格配对，不会多路输出交错。
 *
 * <h3>使用约定</h3>
 * <ul>
 *   <li>{@link #exec} 会阻塞调用线程，<b>必须在后台线程调用</b>（已标 {@code @WorkerThread}）。
 *       悬浮窗采集链路本来就跑在 {@code OverlayWindow} 的共享采集线程上，天然满足；</li>
 *   <li>不需要 root 的操作（读 /proc、/sys 的大部分节点）<b>不要走这里</b>，
 *       直接用文件读取更快，也避免因 root 不可用而失败；</li>
 *   <li>所有拼接进命令的外部输入必须先用 {@link #escape} 包裹，否则是 root 权限下的命令注入。</li>
 * </ul>
 *
 * <h3>超时语义</h3>
 * 超时后无法安全复用会话——流里可能残留未读完的输出，继续读写必然错位。
 * 因此超时会销毁会话，下次调用自动重建。这是正确性优先于性能的取舍。
 */
public final class RootShell {

    private static final String TAG = "RootShell";

    /** 候选 su 路径，按常见程度排序。不同 root 方案（Magisk / KernelSU / APatch）安装位置不同。 */
    private static final String[] SU_PATHS = {
            "su",
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
    };

    /** 默认超时：单条命令 10 秒。备份、刷机类长任务请显式传更大的值。 */
    public static final long DEFAULT_TIMEOUT_MS = 10_000L;

    /** 结束标记前缀。配合递增序号，避免与命令自身的输出混淆。 */
    private static final String TOKEN_PREFIX = "__aurora_eof_";

    private static volatile RootShell instance;

    /** 单线程串行执行 shell 命令，保证写读配对。 */
    private final ExecutorService shellExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "aurora-root-shell");
        t.setDaemon(true);
        return t;
    });

    private final AtomicInteger tokenSeq = new AtomicInteger(0);

    // 以下字段只在 shellExecutor 线程内访问，无需额外同步
    private Process process;
    private BufferedWriter writer;
    private BufferedReader reader;

    private volatile Boolean rootAvailable;

    private RootShell() {
    }

    public static RootShell get() {
        if (instance == null) {
            synchronized (RootShell.class) {
                if (instance == null) {
                    instance = new RootShell();
                }
            }
        }
        return instance;
    }

    // ───────────────── 执行结果 ─────────────────

    /** 一次命令执行的结果。 */
    public static final class Result {
        /** 命令退出码；未成功执行时为 -1。 */
        public final int exitCode;
        /** 标准输出与标准错误合并后的内容（未 trim，保留换行）。 */
        public final String out;
        /** 是否因超时中断。 */
        public final boolean timedOut;
        /** 是否因会话不可用而未执行（无 root 或 su 启动失败）。 */
        public final boolean unavailable;

        Result(int exitCode, String out, boolean timedOut, boolean unavailable) {
            this.exitCode = exitCode;
            this.out = out == null ? "" : out;
            this.timedOut = timedOut;
            this.unavailable = unavailable;
        }

        /** 命令成功执行且退出码为 0。 */
        public boolean isSuccess() {
            return !timedOut && !unavailable && exitCode == 0;
        }

        /** 输出按行拆分，去掉首尾空行。 */
        public List<String> lines() {
            if (out.isEmpty()) {
                return Collections.emptyList();
            }
            String[] parts = out.split("\n");
            List<String> list = new ArrayList<>(parts.length);
            for (String p : parts) {
                String trimmed = p.trim();
                if (!trimmed.isEmpty()) {
                    list.add(trimmed);
                }
            }
            return list;
        }

        static Result timeout() {
            return new Result(-1, "", true, false);
        }

        static Result unavailable() {
            return new Result(-1, "", false, true);
        }
    }

    /** 异步回调。 */
    public interface Callback {
        void onResult(Result result);
    }

    // ───────────────── 对外 API ─────────────────

    /**
     * 是否具备 root 权限。
     * 结果会缓存——检测本身要起 su 会话，没必要每次都做。
     * 注意缓存后即使权限被回收也不会立即反映，需要时可调用 {@link #invalidateRootCache}。
     */
    public boolean isRootAvailable() {
        Boolean cached = rootAvailable;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (rootAvailable == null) {
                Result r = exec("id", 3000L);
                rootAvailable = Boolean.valueOf(r.isSuccess() && r.out.contains("uid=0"));
            }
            return rootAvailable;
        }
    }

    /** 丢弃 root 可用性缓存，下次 {@link #isRootAvailable} 重新检测。 */
    public void invalidateRootCache() {
        rootAvailable = null;
    }

    /**
     * 执行命令并返回完整结果。使用 {@link #DEFAULT_TIMEOUT_MS} 超时。
     * 必须在后台线程调用。
     */
    public Result exec(String command) {
        return exec(command, DEFAULT_TIMEOUT_MS);
    }

    /**
     * 执行命令并返回完整结果。
     * 必须在后台线程调用。
     *
     * @param timeoutMs 超时毫秒数；超过则中断并销毁会话（下次自动重建）
     */
    public Result exec(String command, long timeoutMs) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // 不抛异常——现有调用点历史包袱较重，先记录，避免直接把线上打崩
            Log.w(TAG, "exec() 在主线程调用，命令可能阻塞 UI: " + command);
        }
        Callable<Result> task = () -> execOnShellThread(command);
        Future<Result> future = shellExecutor.submit(task);
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            // 流里可能残留未读完的输出，无法安全复用
            shellExecutor.execute(this::closeSession);
            return Result.timeout();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.unavailable();
        } catch (Exception e) {
            Log.w(TAG, "exec failed: " + command, e);
            return Result.unavailable();
        }
    }

    /** 只要退出码：0 表示成功，-1 表示失败/超时/无 root。 */
    public int execCode(String command) {
        return exec(command).exitCode;
    }

    /** 只要输出：已 trim，失败时返回空串。 */
    public String execOut(String command) {
        Result r = exec(command);
        return r.isSuccess() ? r.out.trim() : "";
    }

    /** 输出按行返回；失败时返回空列表。 */
    public List<String> execLines(String command) {
        Result r = exec(command);
        return r.isSuccess() ? r.lines() : Collections.emptyList();
    }

    /** 异步执行，回调在 shell 线程，勿直接操作 UI。 */
    public void execAsync(String command, Callback callback) {
        execAsync(command, DEFAULT_TIMEOUT_MS, callback);
    }

    /** 异步执行，回调在 shell 线程，勿直接操作 UI。 */
    public void execAsync(String command, long timeoutMs, Callback callback) {
        shellExecutor.execute(() -> {
            Result r = exec(command, timeoutMs);
            if (callback != null) {
                try {
                    callback.onResult(r);
                } catch (Exception e) {
                    Log.w(TAG, "callback threw", e);
                }
            }
        });
    }

    /** 以 root 权限判断文件/目录是否存在。 */
    public boolean fileExists(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        return exec("[ -e " + escape(path) + " ]").isSuccess();
    }

    /** 主动释放会话与线程池。一般不需要调用——su 进程随应用存活即可。 */
    public void shutdown() {
        shellExecutor.execute(this::closeSession);
        shellExecutor.shutdown();
        instance = null;
    }

    // ───────────────── 转义 ─────────────────

    /**
     * 用单引号包裹字符串，使其中的 shell 元字符全部失去特殊含义。
     *
     * <p>凡是把外部输入（路径、包名、用户输入）拼进命令的地方都必须调用，
     * 否则就是 root 权限下的命令注入。
     *
     * <pre>
     * escape("a b")     → 'a b'
     * escape("it's")    → 'it'\''s'
     * </pre>
     */
    public static String escape(String raw) {
        if (raw == null) {
            return "''";
        }
        return "'" + raw.replace("'", "'\\''") + "'";
    }

    // ───────────────── 内部实现 ─────────────────

    /** 在 shell 线程内执行一条命令。 */
    private Result execOnShellThread(String command) {
        if (!ensureSession()) {
            return Result.unavailable();
        }
        String token = TOKEN_PREFIX + tokenSeq.incrementAndGet();
        try {
            // 命令后紧跟一行 echo，把退出码和结束标记一起输出，
            // 读输出时据此判断命令结束位置并取回 exit code。
            writer.write(command);
            writer.write("\n");
            writer.write("echo \"$? " + token + "\"\n");
            writer.flush();
        } catch (Exception e) {
            closeSession();
            return Result.unavailable();
        }

        StringBuilder out = new StringBuilder();
        int exitCode = -1;
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                int idx = line.indexOf(token);
                if (idx >= 0) {
                    String prefix = line.substring(0, idx).trim();
                    try {
                        exitCode = Integer.parseInt(prefix);
                    } catch (NumberFormatException ignored) {
                        exitCode = 0;
                    }
                    break;
                }
                out.append(line).append('\n');
            }
        } catch (Exception e) {
            // 读失败通常意味着 su 进程已死
            closeSession();
            return new Result(exitCode, out.toString(), false, true);
        }
        return new Result(exitCode, out.toString(), false, false);
    }

    /** 确保会话可用；不可用则重建。只在 shell 线程调用。 */
    private boolean ensureSession() {
        if (process != null && writer != null && reader != null && isProcessAlive(process)) {
            return true;
        }
        closeSession();
        for (String suPath : SU_PATHS) {
            try {
                Process p = new ProcessBuilder(suPath).redirectErrorStream(true).start();
                BufferedWriter w = new BufferedWriter(new OutputStreamWriter(p.getOutputStream()));
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                process = p;
                writer = w;
                reader = r;
                // 写一条空命令确认会话真的可用（有些 su 会立即退出）
                w.write("echo " + TOKEN_PREFIX + "probe\n");
                w.flush();
                String line = r.readLine();
                if (line != null && line.contains(TOKEN_PREFIX + "probe")) {
                    return true;
                }
                closeSession();
            } catch (Exception ignored) {
                closeSession();
            }
        }
        return false;
    }

    /** 关闭会话并释放流。只在 shell 线程调用。 */
    private void closeSession() {
        Process p = process;
        process = null;
        writer = null;
        reader = null;
        if (p != null) {
            try {
                p.getOutputStream().close();
            } catch (Exception ignored) {
            }
            try {
                p.destroy();
            } catch (Exception ignored) {
            }
        }
    }

    /** {@code Process.isAlive()} 需 API 26，这里用 exitValue 兼容低版本。 */
    private static boolean isProcessAlive(Process p) {
        try {
            p.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }
}
