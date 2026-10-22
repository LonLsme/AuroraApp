package com.aurora.app.core.display;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.aurora.app.core.root.RootShell;

/**
 * 屏幕改动的「断点兜底」。
 *
 * <h3>要解决的问题</h3>
 * 改分辨率/密度是会把界面改到无法操作的操作（例如把密度写歪导致 UI 巨小、
 * 或分辨率改成本机不支持的尺寸后黑屏）。一旦落到「应用了但用户没确认」这个状态，
 * 就必须保证系统能以任意方式回到原样 —— 包括用户点不到按钮、应用被强杀、
 * 甚至设备被重启这几种情况。
 *
 * <h3>机制</h3>
 * <ol>
 *   <li>应用改动<b>之前</b>，先把原始状态快照进 SharedPreferences
 *       （{@code wm size} / {@code wm density} 的完整输出 + 两个刷新率设置的原始值）；</li>
 *   <li>打完快照才下发改动，并置「待确认」标记；</li>
 *   <li>用户点「保留更改」→ 清标记与快照（此后重启也保留，因为 {@code wm} 的
 *       override 本身就是持久化的）；</li>
 *   <li>倒计时走完 / 退出页面 / 应用被强杀后重新启动 / 设备重启
 *       —— 任一情况只要标记还在，就按快照精确回滚。</li>
 * </ol>
 *
 * <p>快照存的是 {@code wm size} 的<b>原始文本</b>而不是解析后的数字：
 * 这样「用户本来就有第三方 override」的场景也能精确还原成他原来的值，
 * 而不是一律打回物理分辨率。
 *
 * <p>回滚走 {@code wm size reset} 而不是删 {@code settings} 的键：
 * 实测 Android 16 把分辨率写在 {@code settings global display_size_forced}，
 * 而老资料里的救援脚本删的是 secure 命名空间，会漏掉分辨率那一半。
 */
public final class DisplayGuard {

    private static final String TAG = "DisplayGuard";

    /** 未确认改动的倒计时时长，与页面上的提示保持一致。 */
    public static final long COUNTDOWN_MS = 15_000L;

    private static final String PREFS = "aurora_display_guard";
    private static final String KEY_PENDING = "pending";
    private static final String KEY_TS = "pending_ts";
    private static final String KEY_DESC = "pending_desc";
    private static final String KEY_HAS_SNAPSHOT = "has_snapshot";
    private static final String KEY_ORIG_SIZE = "orig_wm_size";
    private static final String KEY_ORIG_DENSITY = "orig_wm_density";
    private static final String KEY_ORIG_PEAK = "orig_peak_refresh_rate";
    private static final String KEY_ORIG_USER_RATE = "orig_user_refresh_rate";
    /** 改动发生前 SurfaceFlinger 正在用的模式号，回滚时原样打回去。 */
    private static final String KEY_ORIG_ACTIVE_MODE = "orig_active_mode_id";

    private DisplayGuard() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 当前是否处于「已改动但未确认」状态。 */
    public static boolean isPending(Context context) {
        return prefs(context).getBoolean(KEY_PENDING, false);
    }

    /** 未确认改动的描述（用于页面提示）；无则返回空串。 */
    public static String pendingDesc(Context context) {
        return prefs(context).getString(KEY_DESC, "");
    }

    /**
     * 快照当前屏幕状态。已有快照时不再覆盖 —— 保证连续改多次时，
     * 回滚目标始终是「用户动手之前」的原始状态。
     *
     * <p>必须在 {@link #markPending} 之前调用，且必须在后台线程。
     */
    public static void snapshotOriginals(Context context) {
        SharedPreferences sp = prefs(context);
        if (sp.getBoolean(KEY_HAS_SNAPSHOT, false)) {
            return;
        }
        SharedPreferences.Editor editor = sp.edit();
        if (RootShell.get().isRootAvailable()) {
            RootShell.Result size = RootShell.get().exec("wm size", 6000L);
            RootShell.Result density = RootShell.get().exec("wm density", 6000L);
            editor.putString(KEY_ORIG_SIZE, size.isSuccess() ? size.out : "");
            editor.putString(KEY_ORIG_DENSITY, density.isSuccess() ? density.out : "");
            editor.putString(KEY_ORIG_PEAK,
                    RootShell.get().execOut("settings get system peak_refresh_rate"));
            editor.putString(KEY_ORIG_USER_RATE,
                    RootShell.get().execOut("settings get system user_refresh_rate"));
            // 当前显示模式号也要记：回滚时若只是「删设置」，SurfaceFlinger 仍停在我们
            // 下发的那个模式上（实测会一直卡在 60Hz），必须把模式号原样打回去
            editor.putInt(KEY_ORIG_ACTIVE_MODE, DisplayController.readActiveModeId());
            editor.putBoolean(KEY_HAS_SNAPSHOT, true);
        } else {
            // 没有 root 就存不下快照；回滚时只能走 wm reset 打回物理值
            editor.putBoolean(KEY_HAS_SNAPSHOT, false);
        }
        editor.apply();
    }

    /** 置「待确认」标记。改动下发成功后调用。 */
    public static void markPending(Context context, String desc) {
        prefs(context).edit()
                .putBoolean(KEY_PENDING, true)
                .putLong(KEY_TS, System.currentTimeMillis())
                .putString(KEY_DESC, desc == null ? "" : desc)
                .apply();
        // 同步登记系统级兜底任务：它是唯一能跨重启、且不依赖开机广播的通道。
        // 在小米 / HyperOS 上 BOOT_COMPLETED 会被 ROM 拦掉，这条路径必须留着。
        DisplayRestoreJobService.schedule(context);
    }

    /** 用户点了「保留更改」：清标记与快照，此后改动长期生效。 */
    public static void confirm(Context context) {
        prefs(context).edit().clear().apply();
        DisplayRestoreJobService.cancel(context);
    }

    /**
     * 按快照回滚到用户动手之前的状态，并清掉待确认标记。
     * 阻塞 IO，必须在后台线程调用。
     */
    public static DisplayController.OpResult rollback(Context context) {
        SharedPreferences sp = prefs(context);
        boolean pending = sp.getBoolean(KEY_PENDING, false);
        if (!pending) {
            return new DisplayController.OpResult(true, "没有待还原的改动");
        }
        if (!RootShell.get().isRootAvailable()) {
            return new DisplayController.OpResult(false, "需要 Root 权限才能还原屏幕设置");
        }

        boolean hasSnapshot = sp.getBoolean(KEY_HAS_SNAPSHOT, false);
        String origSize = sp.getString(KEY_ORIG_SIZE, "");
        String origDensity = sp.getString(KEY_ORIG_DENSITY, "");
        String origPeak = sp.getString(KEY_ORIG_PEAK, "");
        String origUserRate = sp.getString(KEY_ORIG_USER_RATE, "");

        int[] physical = new int[]{0, 0};
        int[] override = new int[]{0, 0};
        if (hasSnapshot && origSize != null && !origSize.isEmpty()) {
            DisplayController.parseWmSize(origSize, physical, override);
        }

        // ① 分辨率：原来有 override 就还原成原 override，没有就 reset
        boolean sizeOk;
        if (override[0] > 0 && override[1] > 0) {
            sizeOk = RootShell.get().exec("wm size " + override[0] + "x" + override[1], 8000L)
                    .isSuccess();
        } else {
            sizeOk = RootShell.get().exec("wm size reset", 8000L).isSuccess();
        }

        // ② 密度：同上
        int[] densityPhysical = new int[]{0};
        int[] densityOverride = new int[]{0};
        if (hasSnapshot && origDensity != null && !origDensity.isEmpty()) {
            DisplayController.parseWmDensity(origDensity, densityPhysical, densityOverride);
        }
        boolean densityOk;
        if (densityOverride[0] > 0) {
            densityOk = RootShell.get().exec("wm density " + densityOverride[0], 8000L).isSuccess();
        } else {
            densityOk = RootShell.get().exec("wm density reset", 8000L).isSuccess();
        }

        // ③ 刷新率设置：原来是 null 就删除键，否则写回原值
        restoreSetting("peak_refresh_rate", origPeak);
        restoreSetting("user_refresh_rate", origUserRate);
        // ④ 显示模式打回改动前的那个模式号。
        //    这里<b>不能</b>硬编码 0：模式号 0 在多数机器上是 60Hz，
        //    硬打 0 会把用户原本的 120Hz 直接降成 60Hz（实测踩过）。
        //    拿不到原模式号（无快照/读取失败）时干脆不动，交给系统自身策略。
        int origMode = sp.getInt(KEY_ORIG_ACTIVE_MODE, -1);
        if (origMode >= 0) {
            RootShell.get().exec("service call SurfaceFlinger 1035 i32 " + origMode, 8000L);
        }

        sp.edit().clear().apply();
        // 已经还原，兜底任务没有存在意义了，撤掉避免多一次无谓的进程拉起
        DisplayRestoreJobService.cancel(context);

        if (!sizeOk && !densityOk) {
            Log.w(TAG, "回滚命令均未成功");
            return new DisplayController.OpResult(false,
                    "自动还原未成功，请用电脑执行：adb shell wm size reset && adb shell wm density reset");
        }
        int[] nowPhysical = new int[]{0, 0};
        int[] nowOverride = new int[]{0, 0};
        RootShell.Result verify = RootShell.get().exec("wm size", 6000L);
        if (verify.isSuccess()) {
            DisplayController.parseWmSize(verify.out, nowPhysical, nowOverride);
        }
        if (nowOverride[0] > 0) {
            return new DisplayController.OpResult(false,
                    "还原命令已下发但仍检测到 override：" + nowOverride[0] + "x" + nowOverride[1]);
        }
        return new DisplayController.OpResult(true, "已还原屏幕设置");
    }

    private static void restoreSetting(String key, String original) {
        if (original == null || original.isEmpty() || "null".equals(original)) {
            RootShell.get().exec("settings delete system " + key, 6000L);
        } else {
            RootShell.get().exec("settings put system " + key + " " + original, 6000L);
        }
    }

    /**
     * 开机 / 应用冷启动时的兜底：只要「待确认」标记还在就回滚。
     * 阻塞 IO，必须在后台线程调用。
     *
     * @return 是否真的执行了回滚
     */
    public static boolean restoreIfPending(Context context) {
        if (!isPending(context)) {
            return false;
        }
        Log.i(TAG, "检测到未确认的屏幕改动，开始回滚");
        rollback(context);
        return true;
    }
}
