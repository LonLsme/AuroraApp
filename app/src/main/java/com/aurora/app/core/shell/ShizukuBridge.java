package com.aurora.app.core.shell;

import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;

/**
 * Shizuku 通道：借用户已授权的 Shizuku 服务，以 shell（或 root）身份起子进程。
 *
 * <h3>为什么不直接用 {@code Shizuku.newProcess}</h3>
 * 那条便捷方法在 API 13 里被移出了 {@code Shizuku} 类（取而代之的是 user service 方案），
 * 而 user service 要额外打一个 dex 进 APK、还要处理绑定生命周期 —— 为了跑几条
 * {@code getprop} 不值得。这里直接走它内部同一套 AIDL：
 * {@code Shizuku.getBinder()} → {@link IShizukuService} → {@link IRemoteProcess}。
 * 反编译核对过 13.1.5 的 {@code onBinderReceived}：服务端给回的 binder 是<b>裸 binder</b>
 * （{@code IShizukuService.Stub.asInterface(binder)}，没有再包一层 wrapper），
 * 所以外部代码可以和库内部一样直接调。
 *
 * <h3>没有 exitValue 也要能收尾</h3>
 * {@code IRemoteProcess.alive()} 是轮询式的，{@code waitFor} 会一直阻塞。这里用
 * 「等 {@code alive()} 转 false + 读线程收尾」的方式：进程一退出管道就 EOF，
 * 读线程自然结束；超时就 {@code destroy()} 再 join，绝不把线程留在那里。
 */
public final class ShizukuBridge {

    private static final String TAG = "ShizukuBridge";

    /** 轮询粒度：20ms 足够，比这更细只是在烧 CPU。 */
    private static final long POLL_MS = 20L;

    private ShizukuBridge() {
    }

    /** Shizuku 服务是否在跑（binder 已收到）。 */
    public static boolean pingBinder() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 服务在跑且本应用已被授权。 */
    public static boolean hasPermission() {
        try {
            if (!Shizuku.pingBinder()) {
                return false;
            }
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            // binder 还没 attach 时 checkSelfPermission 抛 IllegalStateException —— 属正常时序
            return false;
        }
    }

    /** 拉起 Shizuku 自己的授权弹窗；结果通过 {@code addRequestPermissionResultListener} 回来。 */
    public static void requestPermission(int requestCode) {
        try {
            Shizuku.requestPermission(requestCode);
        } catch (Throwable t) {
            Log.w(TAG, "请求 Shizuku 授权失败", t);
        }
    }

    /** 执行一条命令。失败/未授权时返回 {@link ShellManager.Result#unavailable()}。 */
    public static ShellManager.Result exec(String command, long timeoutMs) {
        if (command == null || command.trim().isEmpty()) {
            return new ShellManager.Result(-1, "", false, true);
        }
        if (!hasPermission()) {
            return ShellManager.Result.unavailable();
        }

        IRemoteProcess remote = null;
        ParcelFileDescriptor stdout = null;
        try {
            IBinder binder = Shizuku.getBinder();
            if (binder == null) {
                return ShellManager.Result.unavailable();
            }
            IShizukuService service = IShizukuService.Stub.asInterface(binder);
            // 与库内一致：sh -c "<命令>"，env/dir 交给 Shizuku 服务端按默认处理
            remote = service.newProcess(new String[]{"sh", "-c", command}, null, null);
            if (remote == null) {
                return ShellManager.Result.unavailable();
            }
            stdout = remote.getInputStream();
            return drain(remote, stdout, timeoutMs);
        } catch (Throwable t) {
            Log.w(TAG, "Shizuku 执行失败: " + command, t);
            return ShellManager.Result.unavailable();
        } finally {
            closeQuietly(stdout);
            destroyQuietly(remote);
        }
    }

    // ───────────────── 内部 ─────────────────

    /** 读干管道并等进程退出；超时则强杀。 */
    private static ShellManager.Result drain(IRemoteProcess remote, ParcelFileDescriptor stdout,
                                             long timeoutMs) {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(stdout)) {
                byte[] chunk = new byte[4096];
                int n;
                while ((n = in.read(chunk)) > 0) {
                    buffer.write(chunk, 0, n);
                }
            } catch (Throwable ignored) {
                // 进程被 destroy 时管道会提前 EOF/抛错，属预期
            }
        }, "aurora-shizuku-out");
        reader.setDaemon(true);
        reader.start();

        long deadline = SystemClock.uptimeMillis() + Math.max(200L, timeoutMs);
        boolean timedOut = false;
        while (SystemClock.uptimeMillis() < deadline) {
            if (!aliveQuietly(remote)) {
                break;
            }
            sleepQuietly(POLL_MS);
        }
        if (aliveQuietly(remote)) {
            timedOut = true;
            destroyQuietly(remote);
        }
        // 进程退出（或被杀）后管道关闭，读线程一定会在很短时间内返回
        joinQuietly(reader, timedOut ? 300L : 1000L);

        int exitCode = timedOut ? -1 : exitCodeQuietly(remote);
        String out = buffer.toString();
        return new ShellManager.Result(exitCode, out, timedOut, false);
    }

    private static boolean aliveQuietly(IRemoteProcess remote) {
        try {
            return remote.alive();
        } catch (Throwable t) {
            // binder 已死 / 服务端已回收：当成「进程结束」，不要再等
            return false;
        }
    }

    private static int exitCodeQuietly(IRemoteProcess remote) {
        try {
            return remote.exitValue();
        } catch (Throwable t) {
            return 0;
        }
    }

    private static void destroyQuietly(IRemoteProcess remote) {
        if (remote == null) {
            return;
        }
        try {
            if (remote.alive()) {
                remote.destroy();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void closeQuietly(ParcelFileDescriptor pfd) {
        if (pfd == null) {
            return;
        }
        try {
            pfd.close();
        } catch (Throwable ignored) {
        }
    }

    private static void joinQuietly(Thread thread, long millis) {
        try {
            thread.join(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
