package com.aurora.app.core.shell;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.aurora.app.core.root.RootShell;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 特权通道判定与命令路由。
 *
 * <h3>只有一条通道：Root</h3>
 * 设备页要读的字段分两类：
 * <ul>
 *   <li><b>普通 API 能拿到的</b> —— {@code Build.*}、电池属性、{@code /sys/devices/system/cpu}
 *       下的频率节点，不需要任何权限；</li>
 *   <li><b>必须有 root 身份的</b> —— {@code getenforce}（Android 12 起三方应用读不到真实结果）、
 *       {@code settings get secure android_id}、{@code getprop ro.boot.*} 这几个。</li>
 * </ul>
 * 第二类读不到就显示「—」，页面照常展示能读到的部分 —— 不存在「整页收起只留提示」的形态。
 * 本类只负责<b>老实回答「现在有没有 root」</b>，并把命令路由过去。
 *
 * <h3>为什么免 root 通道（Shizuku / 无线调试）被整体移除</h3>
 * 那两条能给的身份都是 uid 2000（shell）。它确实能补上几个只读字段，但本项目真正要
 * 动手的地方 —— CPU 调频写 {@code /sys/devices/system/cpu/.../scaling_setspeed}、
 * 温控阈值、部分 sysfs 节点 —— 属主是 root，shell 写不进去。等于让用户多装一个 App、
 * 多授权一次，换来的是一半功能「能看不能改」。与其把两把权限不同的钥匙摆在用户面前，
 * 不如让「能看到什么」和「能改什么」共用同一把。
 *
 * <h3>线程</h3>
 * 检测与执行<b>全部在后台单线程</b>串行跑：{@code su} 首次调用要等授权弹窗（实测
 * 可达 1 秒以上），放到主线程会让设备页卡在首屏。回调统一切回主线程。
 */
public final class ShellManager {

    private static final String TAG = "ShellManager";

    /** 通道种类。 */
    public enum Mode {
        /** 还没检测过（首帧就是这个状态）。 */
        DETECTING,
        /** {@code su} 可用，身份 uid 0。 */
        ROOT,
        /** 没有 root —— 特权字段显示「—」，其余照常。 */
        NONE
    }

    /** 一次检测的结果。不可变，可安全地跨线程传递。 */
    public static final class State {
        public final Mode mode;

        State(Mode mode) {
            this.mode = mode;
        }

        /** 有 root，特权字段可以读。 */
        public boolean hasShell() {
            return mode == Mode.ROOT;
        }
    }

    /** 一条命令的执行结果。 */
    public static final class Result {
        public final int exitCode;
        /** stdout + stderr（已合并，不再区分）。 */
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

        /**
         * 按字段拼一个结果。
         *
         * <p>公开只为让单测能造「超时」「无输出但退出码非零」这些真机上难复现的形态 ——
         * 本类是纯值对象，没有副作用。
         */
        public static Result of(int exitCode, String out, boolean timedOut, boolean unavailable) {
            return new Result(exitCode, out, timedOut, unavailable);
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

    /** 防止同一时刻排进多次检测（页面 onResume 与 resume 后的重测常同时触发）。 */
    private final AtomicBoolean detecting = new AtomicBoolean(false);

    /** 检测进行中被要求重测时置位：这一轮的结果可能是授权前的旧值，跑完立刻再来一轮。 */
    private final AtomicBoolean redetect = new AtomicBoolean(false);

    private volatile State state = new State(Mode.DETECTING);

    private ShellManager() {
    }

    /** 最近一次检测结果（主线程直接读，不阻塞）。 */
    public State state() {
        return state;
    }

    /**
     * 丢掉缓存，下次检测重新走一遍。
     *
     * <p>用户可能刚在 root 管理器里授权完就回到页面 —— 这没有系统广播，只能由页面
     * 在 {@code onResume} 时主动重测。
     */
    public void invalidate() {
        RootShell.get().invalidateRootCache();
    }

    /**
     * 后台检测一次通道，结果回主线程。
     *
     * <p>已在检测中时<b>不并发再跑</b>，只记一个「待重测」标记：并发重测没有意义，反而会让
     * 刚拿到手的结果被另一份更旧的结果覆盖。但标记不能丢 —— {@code su} 授权框刚关掉时
     * 上一轮检测可能还在跑，那一轮读到的是授权前的状态。
     */
    public void detectAsync(Callback callback) {
        if (!detecting.compareAndSet(false, true)) {
            redetect.set(true);
            return;
        }
        worker.execute(() -> {
            State result;
            try {
                result = detect();
            } catch (Throwable t) {
                Log.w(TAG, "通道检测异常，按无 root 处理", t);
                result = new State(Mode.NONE);
            } finally {
                detecting.set(false);
            }
            final State finalResult = result;
            state = finalResult;
            Log.i(TAG, "通道: " + finalResult.mode);
            if (callback != null) {
                mainHandler.post(() -> callback.onState(finalResult));
            }
            if (redetect.compareAndSet(true, false)) {
                detectAsync(callback);
            }
        });
    }

    /** 同步检测（已在后台线程时用；不要在 UI 线程调用）。 */
    public State detect() {
        return new State(RootShell.get().isRootAvailable() ? Mode.ROOT : Mode.NONE);
    }

    // ───────────────── 命令执行 ─────────────────

    /** 执行一条命令（默认超时）。回调线程 = 调用线程。 */
    public Result exec(String command) {
        return exec(command, RootShell.DEFAULT_TIMEOUT_MS);
    }

    /**
     * 执行一条命令。
     *
     * <p>没有 root 时返回 {@link Result#unavailable()}，调用方据此显示「—」而不是报错。
     *
     * <p><b>这里绝不读 {@link #state} 的缓存模式来拦</b>：{@link AdbBridge} 判身份用的是
     * {@link RootShell#isRootAvailable()}（实时），两边判据不一致时会出现
     * 「身份认定是 root → 走进 root 分支 → 本方法按缓存判成 NONE → 命令被静默拒绝」，
     * 表现就是外接设备列表为空、或者切过去一个字段都读不到，而且没有任何报错。
     * 缓存只在页面决定「要不要重读」时用，不参与命令是否放行。
     */
    public Result exec(String command, long timeoutMs) {
        if (!RootShell.get().isRootAvailable()) {
            return Result.unavailable();
        }
        RootShell.Result r = RootShell.get().exec(command, timeoutMs);
        return new Result(r.exitCode, r.out, r.timedOut, r.unavailable);
    }

    /** 可用就执行，否则返回空串（读信息类调用统一走这个，省掉满屏判空）。 */
    public String execOrEmpty(String command, long timeoutMs) {
        Result r = exec(command, timeoutMs);
        return r.hasOutput() ? r.trimmed() : "";
    }
}
