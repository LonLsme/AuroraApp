package com.aurora.app.core.shell;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.aurora.app.core.root.RootShell;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 设备的「特权通道」管理器：先判有没有通道，再把 shell 命令路由到具体那一条。
 *
 * <h3>为什么需要它</h3>
 * 设备页要读的东西分成两类：
 * <ul>
 *   <li><b>普通 API 能拿到的</b>：{@code Build.*}、电池属性、{@code /proc} 与
 *       {@code /sys/devices/system/cpu} 下的节点 —— 不需要任何权限；</li>
 *   <li><b>必须有 shell 身份的</b>：{@code getenforce}（Android 12 起三方应用
 *       读不到真实结果）、{@code settings get secure android_id} 这类从系统设置里
 *       取值的命令。</li>
 * </ul>
 * 第二类没有通道就只能显示「—」，与其让用户看到一片「—」再猜原因，不如把整页
 * 收成一块授权引导。这条产品规则落在 {@code DeviceFragment.applyGate()}，
 * 本类只负责<b>老实回答「哪条通道能用」</b>。
 *
 * <h3>优先级：Root → Shizuku → 无线 ADB</h3>
 * 顺序不是随手排的，三条通道的能力与代价都是递减的：
 * <table border="1">
 *   <tr><th>通道</th><th>身份</th><th>代价</th></tr>
 *   <tr><td>{@link Mode#ROOT}</td><td>uid 0</td><td>用户已经装好 root，零额外操作</td></tr>
 *   <tr><td>{@link Mode#SHIZUKU}</td><td>2000 (shell) / root 授权</td><td>需要装 Shizuku 并授权一次</td></tr>
 *   <tr><td>{@link Mode#WIRELESS_ADB}</td><td>2000 (shell)</td><td>要连无线调试，端口每次重启都变</td></tr>
 * </table>
 * 有 root 时<b>绝不</b>提示 Shizuku 授权 —— 那是在让用户为已经拥有的能力再装一个 App。
 * 这条由 {@link #detectAsync} 的短路顺序保证：一旦 {@code root} 命中就直接返回，
 * 后面两条连探测都不做（探测 Shizuku 要跨进程、探测无线 ADB 要起 adb，都省掉）。
 *
 * <h3>线程</h3>
 * 检测与执行<b>全部在后台单线程</b>串行跑：{@code su} 首次调用要等授权弹窗（实测
 * 可达 1 秒以上），Shizuku 要跨进程，无线 ADB 要起进程 —— 任何一条进主线程都会
 * 让设备页卡在首屏。回调统一切回主线程。
 */
public final class ShellManager {

    private static final String TAG = "ShellManager";

    /** Shizuku 本体包名（用于「去激活」时判断装没装）。 */
    public static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";

    /** 通道种类，声明顺序即优先级。 */
    public enum Mode {
        /** 还没检测过（首帧就是这个状态）。 */
        DETECTING,
        /** {@code su} 可用，身份 uid 0。 */
        ROOT,
        /** Shizuku 服务在跑且已授权。 */
        SHIZUKU,
        /** 内置 adb 已与本机建立无线调试连接。 */
        WIRELESS_ADB,
        /** 三条都不通 —— 设备页只剩授权引导。 */
        NONE
    }

    /** 一次检测的结果。不可变，可安全地跨线程传递。 */
    public static final class State {
        public final Mode mode;
        /** 无线 ADB 已连时对端的 {@code ip:port}；其它通道为 {@code null}。 */
        public final String adbSerial;
        /** 系统的「无线调试」开关是否开着（用于决定要不要提示配对）。 */
        public final boolean wirelessEnabled;

        State(Mode mode, String adbSerial, boolean wirelessEnabled) {
            this.mode = mode;
            this.adbSerial = adbSerial;
            this.wirelessEnabled = wirelessEnabled;
        }

        /** 有任意一条通道，设备信息可以完整读取。 */
        public boolean hasShell() {
            return mode == Mode.ROOT || mode == Mode.SHIZUKU || mode == Mode.WIRELESS_ADB;
        }

        /** 页面上用的短标签（引导页/下拉里的小字）。 */
        public String label() {
            switch (mode) {
                case ROOT:
                    return "Root";
                case SHIZUKU:
                    return "Shizuku";
                case WIRELESS_ADB:
                    return "无线调试";
                case NONE:
                    return "未授权";
                default:
                    return "检测中";
            }
        }
    }

    /** 一条命令的执行结果。 */
    public static final class Result {
        public final int exitCode;
        /** stdout + stderr（各通道都做了合并，不再区分）。 */
        public final String out;
        public final boolean timedOut;
        /** 通道不可用，命令根本没执行 —— 与「执行了但失败」必须区分。 */
        public final boolean unavailable;

        Result(int exitCode, String out, boolean timedOut, boolean unavailable) {
            this.exitCode = exitCode;
            this.out = out == null ? "" : out;
            this.timedOut = timedOut;
            this.unavailable = unavailable;
        }

        static Result unavailable() {
            return new Result(-1, "", false, true);
        }

        public boolean ok() {
            return !timedOut && !unavailable && exitCode == 0;
        }

        /** 是否有可用输出（有些命令退出码非 0 但输出有效，不该一律当失败）。 */
        public boolean hasOutput() {
            return !timedOut && !unavailable && !out.trim().isEmpty();
        }

        public String trimmed() {
            return out.trim();
        }
    }

    /** 检测完成回调，永远在主线程。 */
    public interface Callback {
        void onState(State state);
    }

    private static final ShellManager INSTANCE = new ShellManager();

    public static ShellManager get() {
        return INSTANCE;
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "aurora-shell");
        t.setDaemon(true);
        return t;
    });

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** 防止同一时刻排进多次检测（页面 onResume 与权限回调常常同时触发）。 */
    private final AtomicBoolean detecting = new AtomicBoolean(false);

    private volatile State state = new State(Mode.DETECTING, null, false);

    private ShellManager() {
    }

    /** 最近一次检测结果（主线程直接读，不阻塞）。 */
    public State state() {
        return state;
    }

    /**
     * 丢掉缓存，下次检测重新走一遍。
     *
     * <p>用户可能刚从授权页回来、刚装上 root、刚连上无线调试 —— 这些都不会有系统
     * 广播通知我们，只能由页面在 {@code onResume} 时主动重测。
     */
    public void invalidate() {
        RootShell.get().invalidateRootCache();
    }

    /**
     * 后台检测一次通道，结果回主线程。
     *
     * <p>已在检测中时<b>直接返回</b>（不发回调）：调用方都是「页面变可见了，重测一下」，
     * 排队重测没有意义，反而会让刚拿到手的结果被另一份更旧的结果覆盖。
     */
    public void detectAsync(Context context, Callback callback) {
        final Context app = context.getApplicationContext();
        if (!detecting.compareAndSet(false, true)) {
            return;
        }
        worker.execute(() -> {
            State result;
            try {
                result = detect(app);
            } catch (Throwable t) {
                Log.w(TAG, "通道检测异常，按未授权处理", t);
                result = new State(Mode.NONE, null, false);
            } finally {
                detecting.set(false);
            }
            final State finalResult = result;
            state = finalResult;
            Log.i(TAG, "通道: " + finalResult.mode + (finalResult.adbSerial == null ? "" : " / " + finalResult.adbSerial));
            if (callback != null) {
                mainHandler.post(() -> callback.onState(finalResult));
            }
        });
    }

    /** 同步检测（已在后台线程时用；不要在 UI 线程调用）。 */
    public State detect(Context context) {
        // ① Root 优先，命中就短路 —— 有 root 时不允许走到 Shizuku 那条分支
        if (RootShell.get().isRootAvailable()) {
            return new State(Mode.ROOT, null, false);
        }
        // ② Shizuku：服务在跑 + 已授权
        if (ShizukuBridge.hasPermission()) {
            return new State(Mode.SHIZUKU, null, false);
        }
        // ③ 无线 ADB：只认「连到自己」的连接，连别人的手机不算本机通道
        boolean wirelessEnabled = AdbBridge.wirelessSettingsEnabled(context);
        String serial = wirelessEnabled ? AdbBridge.findSelfWirelessSerial(context) : null;
        if (serial != null) {
            return new State(Mode.WIRELESS_ADB, serial, true);
        }
        return new State(Mode.NONE, null, wirelessEnabled);
    }

    // ───────────────── 命令执行 ─────────────────

    /** 用当前最优通道执行一条命令（默认超时）。回调线程 = 调用线程。 */
    public Result exec(Context context, String command) {
        return exec(context, command, RootShell.DEFAULT_TIMEOUT_MS);
    }

    /**
     * 用当前最优通道执行一条命令。
     *
     * <p>{@code context} 只在无线 ADB 分支里用到（要定位内置 adb 与它的 HOME）。
     * 通道不可用时返回 {@link Result#unavailable()}，调用方据此显示「—」而不是报错。
     */
    public Result exec(Context context, String command, long timeoutMs) {
        State current = state;
        switch (current.mode) {
            case ROOT: {
                RootShell.Result r = RootShell.get().exec(command, timeoutMs);
                return new Result(r.exitCode, r.out, r.timedOut, r.unavailable);
            }
            case SHIZUKU:
                return ShizukuBridge.exec(command, timeoutMs);
            case WIRELESS_ADB:
                return AdbBridge.shell(context, current.adbSerial, command, timeoutMs);
            default:
                return Result.unavailable();
        }
    }

    /** 通道可用就执行，否则返回空串（读信息类调用统一走这个，省掉满屏判空）。 */
    public String execOrEmpty(Context context, String command, long timeoutMs) {
        Result r = exec(context, command, timeoutMs);
        return r.hasOutput() ? r.trimmed() : "";
    }

    // ───────────────── Shizuku 授权 ─────────────────

    /** Shizuku 本体是否已安装。 */
    public boolean isShizukuInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(SHIZUKU_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } catch (Throwable t) {
            return false;
        }
    }
}
