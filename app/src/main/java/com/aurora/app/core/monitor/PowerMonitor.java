package com.aurora.app.core.monitor;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;

import com.aurora.app.core.util.BatterySysFiles;
import com.aurora.app.core.util.NodeReader;

import java.util.Locale;

/**
 * 功耗监视器数据源：整机功耗 P = U × I（电池端电压 × 瞬时电流）。
 *
 * <h3>为什么必须改数据源</h3>
 * 原实现只读 {@code /sys/class/power_supply/battery/{voltage_now,current_now}}，
 * 但 Android 12 起 SELinux 把 {@code /sys/class/power_supply/**} 从
 * untrusted_app 的可读集合里剔除，普通读必然 EACCES，功耗因此恒显示 "--"。
 * 现在改为三级取值：
 * <ol>
 *   <li><b>BatteryManager（首选，无需 root）</b>：电流走
 *       {@link BatteryManager#getIntProperty(int)}，电压走
 *       {@link Intent#ACTION_BATTERY_CHANGED} 粘性广播的 {@code EXTRA_VOLTAGE}。
 *       这是 Android 12+ 上唯一稳定可用的公开来源；</li>
 *   <li><b>sysfs 普通读</b>：Android 11 及以下、或厂商放宽策略时仍可读；</li>
 *   <li><b>root cat</b>：设备已 root 时对 sysfs 兜底（覆盖个别 BATTERY 属性返回 0 的机型）。</li>
 * </ol>
 *
 * <h3>单位与符号</h3>
 * 电压统一换算成毫伏（mV），电流按 power_supply 类约定按微安（µA）解释，于是
 * <pre>    P(W) = mV × µA / 1e9</pre>
 * （{@code mV/1000} 得 V、{@code µA/1e6} 得 A，相乘即 W。）
 *
 * <p>符号沿用 Android 约定：<b>流入电池为正</b>，即充电为正、放电为负。
 * 电流一律不取绝对值，符号原样传到瓦数上；个别 OEM 内核反向上报，
 * 由 {@link #applyStatusSign} 用电池状态交叉校正。
 */
public final class PowerMonitor {

    /** 电压节点，按常见程度排序（覆盖 battery / Battery / bms 三种挂法）。 */
    private static final String[] VOLTAGE_PATHS = {
            "/sys/class/power_supply/battery/voltage_now",
            "/sys/class/power_supply/Battery/voltage_now",
            "/sys/class/power_supply/bms/voltage_now",
    };

    /** 电流节点，同上。 */
    private static final String[] CURRENT_PATHS = {
            "/sys/class/power_supply/battery/current_now",
            "/sys/class/power_supply/Battery/current_now",
            "/sys/class/power_supply/bms/current_now",
    };

    /**
     * 功率合理性上界（W）。手机电池功率物理上有界：放电一般 &lt; 20W，快充也远低于此值。
     * 用于排除电流单位误判（见 {@link #wattsOf}）。
     */
    private static final double PLAUSIBLE_POWER_LIMIT_W = 30d;

    /**
     * 功率死区（W）：低于此值一律算 0。
     * 已到充电上限（{@code NOT_CHARGING}）时电流在 0 附近抖动，
     * 不加死区读数会在 {@code +0.00 W} / {@code -0.00 W} 之间反复跳。
     */
    private static final double POWER_DEADBAND_W = 0.05d;

    private PowerMonitor() {
    }

    /**
     * 当前整机功耗（W）。符号：<b>充电为正、放电为负</b>。
     *
     * <p>数据不可用返回 {@link Double#NaN}（由 UI 判 {@code isNaN} 后显示 "--"）。
     * 这里刻意不用负值当哨兵——放电本身就是负数，用负数当哨兵会让所有放电状态
     * 被误判成「不可用」。
     */
    public static double readPowerW(Context context) {
        // 电压与电池状态共用同一次粘性广播取值，避免重复 registerReceiver
        Intent battery = stickyBatteryIntent(context);
        int milliVolts = voltageMvOf(context, battery);
        if (milliVolts <= 0) {
            return Double.NaN;
        }
        long rawCurrent = readCurrentRaw(context);
        if (rawCurrent == 0L) {
            return Double.NaN;
        }
        double watts = applyStatusSign(statusOf(battery), wattsOf(milliVolts, rawCurrent));
        return Math.abs(watts) < POWER_DEADBAND_W ? 0d : watts;
    }

    /**
     * 格式化瓦数用于展示：{@code "+3.20 W"}（充电）/ {@code "-0.85 W"}（放电）；
     * 不可用返回 {@code "--"}。符号由 {@code %+} 自动带出。
     */
    public static String formatPower(double watts) {
        if (Double.isNaN(watts)) {
            return "--";
        }
        return String.format(Locale.US, "%+.2f W", watts);
    }

    /**
     * 电池端电压（mV）。三级取值，不可用返回 -1。
     */
    public static int readVoltageMv(Context context) {
        return voltageMvOf(context, stickyBatteryIntent(context));
    }

    /**
     * 原始瞬时电流读数。符号保留（充电为正、放电为负），但<b>单位不在此处裁定</b>——
     * 交由 {@link #wattsOf} 按物理合理性判断是 µA 还是 mA。
     *
     * <p>注意 BatteryManager 在属性不支持的机型上返回 {@link Integer#MIN_VALUE}，
     * 必须判掉，否则会算出天文数字的功耗。三个来源依次尝试，都取不到返回 0。
     */
    public static long readCurrentRaw(Context context) {
        if (context != null) {
            try {
                BatteryManager bm =
                        (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
                if (bm != null) {
                    int ua = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
                    if (ua != 0 && ua != Integer.MIN_VALUE) {
                        return ua;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        long raw = BatterySysFiles.readCurrentNowMicroAmps();
        if (raw != Long.MIN_VALUE && raw != 0L) {
            return raw;
        }
        for (String path : CURRENT_PATHS) {
            long v = readRootLong(path);
            if (v != 0L) {
                return v;
            }
        }
        return 0L;
    }

    // ─── 内部 ───

    /**
     * 取 {@link Intent#ACTION_BATTERY_CHANGED} 粘性广播。
     * {@code registerReceiver(null, …)} 不注册接收器，只读最后一次广播的 Intent。
     */
    private static Intent stickyBatteryIntent(Context context) {
        if (context == null) {
            return null;
        }
        try {
            return context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        } catch (Exception ignored) {
            // 极少见：registerReceiver 被限制，继续走 sysfs / root 兜底
            return null;
        }
    }

    /** 电压取值主体：优先用调用方已取得的粘性广播，失败再走 sysfs 普通读与 root 兜底。 */
    private static int voltageMvOf(Context context, Intent battery) {
        if (battery != null) {
            int mv = battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
            if (mv > 0) {
                return mv;
            }
        }
        // sysfs 普通读（内核多为 µV）
        long raw = BatterySysFiles.readVoltageNowMicroVolts();
        if (raw != Long.MIN_VALUE && raw > 0) {
            return normalizeVoltageToMv(raw);
        }
        // root 兜底
        for (String path : VOLTAGE_PATHS) {
            long v = readRootLong(path);
            if (v > 0) {
                return normalizeVoltageToMv(v);
            }
        }
        return -1;
    }

    /** 电池状态（{@link BatteryManager#BATTERY_STATUS_CHARGING} 等）；取不到返回 -1。 */
    private static int statusOf(Intent battery) {
        return battery != null
                ? battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                : -1;
    }

    /**
     * 由原始电流值算有符号功率（W）：{@code P = mV × I / 1e9}。
     *
     * <p>电流单位<b>不按量级猜</b>。旧实现用「绝对值 &gt; 100000 就是 µA」的阈值判断，
     * 该阈值对电压成立（4V 只可能是 4000mV 或 4000000µV，不重叠），
     * 但对电流在常见区间失效：息屏待机放电仅 20–80 mA（= 20000–80000 µA），
     * 落在阈值以下会被判成 mA 再乘 1000，于是 {@code 4V × 50A = 200W}——
     * 表现为「越省电、显示功耗越夸张」。
     *
     * <p>改为对两种单位假设各算一次功率，取物理上合理的那个；
     * 两个都合理或都不合理时优先 µA，因为 power_supply 类约定的单位就是 µA。
     */
    private static double wattsOf(int milliVolts, long rawCurrent) {
        double asMicro = milliVolts * (double) rawCurrent / 1e9d;
        double asMilli = milliVolts * (double) rawCurrent / 1e6d;
        boolean microPlausible = Math.abs(asMicro) <= PLAUSIBLE_POWER_LIMIT_W;
        boolean milliPlausible = Math.abs(asMilli) <= PLAUSIBLE_POWER_LIMIT_W;
        if (microPlausible == milliPlausible) {
            return asMicro;
        }
        return microPlausible ? asMicro : asMilli;
    }

    /**
     * 用电池状态校正符号。
     *
     * <p>Android 约定「流入电池为正」，但部分 OEM 内核反向上报，且没有标准 API 可查。
     * 这里做交叉校验：状态说在充电却算出负功率（或状态说在放电却算出正功率）时，
     * 按状态定符号、取绝对值。
     *
     * <p>只让明确的 {@code CHARGING} / {@code DISCHARGING} 参与校正：
     * {@code FULL} 与 {@code NOT_CHARGING}（如已到充电上限）时电流本就贴近 0 且可能微负，
     * 强行按「充电」处理会假报出正的功率。
     */
    private static double applyStatusSign(int status, double watts) {
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING;
        boolean discharging = status == BatteryManager.BATTERY_STATUS_DISCHARGING;
        if (charging && watts < 0d) {
            return -watts;
        }
        if (discharging && watts > 0d) {
            return -watts;
        }
        return watts;
    }

    /** 经 NodeReader 读一个长整数（普通读优先，失败 root cat）；读不到返回 0。 */
    private static long readRootLong(String path) {
        String raw = NodeReader.readFirstLine(path, true);
        if (raw == null) {
            return 0L;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    /**
     * 把 sysfs 电压归一到 mV。
     * 内核绝大多数为 µV（4000000 ≈ 4.0V）；个别机型直接给 mV（4000）。
     * 阈值取 100000：µV 读数必然远大于它，mV 读数必然小于它。
     *
     * <p>该阈值只用于电压——电压的两种单位量级天然不重叠，可以安全地按量级判断。
     * 电流没有这个性质，故不走这条路（见 {@link #wattsOf}）。
     */
    private static int normalizeVoltageToMv(long raw) {
        long mv = raw > 100_000L ? raw / 1000L : raw;
        return mv > Integer.MAX_VALUE ? -1 : (int) mv;
    }
}
