package com.aurora.app.core.flash;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 刷机命令执行层：一切 adb / fastboot 命令都经这里以 {@code su} 身份跑。
 *
 * <h3>为什么必须走 root</h3>
 * 手机充当刷机主机时，adb / fastboot 要直接操作 USB 设备节点（{@code /dev/bus/usb/**}），
 * 普通应用 uid 没有访问权限，只能
 * {@code ProcessBuilder("su", "-c", <env 前缀> + 命令)}。
 *
 * <h3>固定环境前缀</h3>
 * 所有命令都套同一段前缀，作用是：
 * <ol>
 *   <li>{@code chmod 755 <bin>/*} —— 解压出来的二进制若丢了 x 位（zip 不保留 Unix 权限），
 *       root 也执行不了 ELF，这里每次兜底一次（毫秒级开销，换确定性）；</li>
 *   <li>{@code HOME} / {@code TMPDIR} 指向 {@code /data/local/tmp/aurora_flash}，
 *       adb 会在 HOME 下建 {@code .android}（存 adbkey），不污染应用私有目录；</li>
 *   <li>{@code PATH} / {@code LD_LIBRARY_PATH} 前置工具链 bin 目录，
 *       命令里可以直接写 {@code adb} / {@code fastboot} 而不用绝对路径。</li>
 * </ol>
 *
 * <h3>为什么不用 RootShell</h3>
 * {@link com.aurora.app.core.root.RootShell} 是<b>持久化 su 会话</b>（写 stdin、读 stdout），
 * 适合高频短命令；但刷机是<b>长时间、大输出、需要中断</b>的任务，且输出必须实时逐行推给 UI。
 * 持久会话在超时后要销毁重建、无法安全中断子进程，因此这里用独立的 {@code Process} 模型：
 * 每任务一个进程，{@code redirectErrorStream(true)} 合并输出，可随时 destroy + pkill 清理。
 *
 * <h3>并发</h3>
 * 单线程 {@link #executor} + {@link #busy} 标志，同一时刻只允许一个刷机任务。
 * 重复提交直接返回 {@code false}，由调用方提示「已有任务在执行」。
 */
public final class FlashRuntime {

    private static final String TAG = "FlashRuntime";

    /** adb / fastboot 的共享 HOME 与 TMPDIR（shell 与 root 都可写）。 */
    private static final String RUNTIME_ROOT = "/data/local/tmp/aurora_flash";

    private static final FlashRuntime INSTANCE = new FlashRuntime();

    public static FlashRuntime get() {
        return INSTANCE;
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "aurora-flash-run");
        t.setDaemon(true);
        return t;
    });

    private final AtomicBoolean busy = new AtomicBoolean(false);
    private volatile Process currentProcess;
    /** 用户主动取消标记：区分「失败」与「被终止」，退出回调据此措辞。 */
    private volatile boolean cancelled;

    private FlashRuntime() {
    }

    /** 输出与结束回调。<b>均在后台线程触发</b>，更新 UI 请自行 post 回主线程。 */
    public interface Listener {
        /** 一行输出（已合并 stdout / stderr）。 */
        void onLine(String line);

        /**
         * 进程结束。
         *
         * @param exitCode  退出码；进程未能启动或读取异常时为 -1
         * @param cancelled 是否由 {@link #cancel()} 主动终止
         */
        void onExit(int exitCode, boolean cancelled);
    }

    public boolean isBusy() {
        return busy.get();
    }

    /**
     * 提交一条刷机命令。
     *
     * @return {@code true} 已受理；{@code false} 已有任务在执行（本次未执行）
     */
    public boolean run(final Context context, final String command, final Listener listener) {
        if (!busy.compareAndSet(false, true)) {
            return false;
        }
        cancelled = false;
        final String full = envPrefix(context) + command;

        executor.execute(() -> {
            int exitCode = -1;
            Process process = null;
            try {
                ProcessBuilder builder = new ProcessBuilder("su", "-c", full);
                // stdout / stderr 合并：刷机工具的错误信息大多写在 stderr，
                // 分开读还要处理两路交错，终端体验反而更差。
                builder.redirectErrorStream(true);
                process = builder.start();
                currentProcess = process;

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (listener != null) {
                            listener.onLine(line);
                        }
                    }
                }
                exitCode = process.waitFor();
            } catch (IOException e) {
                Log.e(TAG, "命令启动失败", e);
                if (listener != null) {
                    listener.onLine("[错误] " + e.getMessage());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                if (process != null) {
                    process.destroy();
                }
                currentProcess = null;
                busy.set(false);
                if (listener != null) {
                    listener.onExit(exitCode, cancelled);
                }
            }
        });
        return true;
    }

    /**
     * 终止当前任务。
     *
     * <p>只 destroy 本地 {@code su} 进程是不够的 —— 子进程（adb / fastboot）会被 init 收养继续跑，
     * 残留进程会占住 USB 与 5037 端口，导致下次刷机失败。因此这里同时让 root 清理一遍
     * （与「清理残留进程」按钮走同一套 pkill）。
     */
    public void cancel() {
        cancelled = true;
        Process process = currentProcess;
        if (process != null) {
            process.destroy();
        }
        // 异步清理子进程，避免阻塞调用方（通常是主线程）。
        // executor 是单线程：destroy 后当前任务的读流会立刻结束，本清理任务紧随其后执行，
        // 因此不存在「还没杀完就允许提交下一个任务」的窗口（busy 标志要等当前任务 finally 才放开）。
        executor.execute(FlashRuntime::killResidualProcesses);
    }

    /**
     * 清理 adb / fastboot 残留进程。
     *
     * <p>刷机中断、异常退出后常有 pkill 不干净的残留进程占住设备，
     * 这是专门留一个「清理」入口的原因。
     */
    public static void killResidualProcesses() {
        try {
            ProcessBuilder builder = new ProcessBuilder("su", "-c",
                    "pkill -9 adb 2>/dev/null; pkill -9 fastboot 2>/dev/null; true");
            builder.redirectErrorStream(true);
            Process process = builder.start();
            process.waitFor();
            process.destroy();
        } catch (Exception e) {
            Log.w(TAG, "清理残留进程失败", e);
        }
    }

    // ───────────────── 环境前缀 ─────────────────

    /**
     * 构造固定环境前缀。
     *
     * <p>拼串细节：{@code '<bin>'/*} 里引号只包住路径、通配符留在引号外，
     * 这样 shell 才会展开文件列表；{@code PATH='<bin>':$PATH} 同理，
     * 保证 {@code $PATH} 原样传给子 shell 而不是被本地吞掉。
     */
    private static String envPrefix(Context context) {
        String bin = FlashToolchain.binDir(context).getAbsolutePath();
        String quotedBin = shellEscape(bin);
        return "mkdir -p " + RUNTIME_ROOT + "/home " + RUNTIME_ROOT + "/tmp 2>/dev/null; "
                + "chmod 755 " + quotedBin + "/* 2>/dev/null; "
                + "export HOME=" + RUNTIME_ROOT + "/home "
                + "TMPDIR=" + RUNTIME_ROOT + "/tmp "
                + "PATH=" + quotedBin + ":$PATH "
                + "LD_LIBRARY_PATH=" + quotedBin + ":$LD_LIBRARY_PATH; ";
    }

    /** 单引号转义：把 {@code '} 替换为 {@code '\''}，与 {@code RootShell.escape} 同规则。 */
    public static String shellEscape(String raw) {
        if (raw == null) {
            return "''";
        }
        return "'" + raw.replace("'", "'\\''") + "'";
    }
}
