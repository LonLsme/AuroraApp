package com.aurora.app.core.monitor;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.SystemClock;

import com.aurora.app.core.util.BatterySysFiles;
import com.aurora.app.core.util.NodeReader;

import java.util.Locale;

/**
 * 功耗监视器数据源：整机功耗 P = U × I（电池端电压 × 瞬时电流）。
 *
 * <h3>数据来源（三级）</h3>
 * Android 12 起 SELinux 把 {@code /sys/class/power_supply/**} 从 untrusted_app 可读集合剔除，
 * 普通读必然 EACCES，因此取值顺序为：
 * <ol>
 *   <li><b>BatteryManager（首选，无需 root）</b>：电流走
 *       {@link BatteryManager#getIntProperty(int)}，电压走粘性广播的 {@code EXTRA_VOLTAGE}；</li>
 *   <li><b>sysfs 普通读</b>：Android 11 及以下、或厂商放宽策略时仍可读；</li>
 *   <li><b>root cat</b>：已 root 时对 sysfs 兜底（覆盖 BATTERY 属性返回 0 的机型）。</li>
 * </ol>
 *
 * <h3>电流单位：为什么必须标定</h3>
 * <p>power_supply 类约定 {@code current_now} 单位是微安，但<b>部分 OEM 内核按毫安上报</b>
 * （一加 / OPPO / realme 的部分机型），而 Android 没有任何 API 能查单位。旧实现按
 * 「两种单位各算一次功率，取物理合理的那个」猜测，在毫安机型上必错：
 * 4.4V × 800mA 时 µA 假设得 0.0035W、mA 假设得 3.52W，两个都「合理」，
 * 于是优先 µA —— 功率小 1000 倍，再被 0.05W 死区抹成 {@code 0.00W}。
 *
 * <p>现在改为<b>先标定、再计算</b>，按可信度依次尝试：
 * <ol>
 *   <li>{@code current_max}（或 {@code constant_charge_current_max}）的量级：
 *       µA 机型是百万级（如 4000000），mA 机型是千级（如 4000），二者不重叠，一次即可判定；</li>
 *   <li>{@code BATTERY_PROPERTY_CHARGE_COUNTER}（µAh）在 ≥10s 窗口内的增量反推平均电流，
 *       与原始读数比：比值 ≈1 为 µA、≈1000 为 mA。只读一次 counter 无法判定，故需两次采样；</li>
 *   <li>都不可用时回退旧的物理合理性猜测（行为与之前一致）。</li>
 * </ol>
 * 判定结果缓存在进程内静态字段，之后不再重复探测。
 *
 * <h3>其他约定</h3>
 * <ul>
 *   <li>符号：流入电池为正（充电为正、放电为负）；个别内核反向上报，由 {@link #applyStatusSign}
 *       按电池状态交叉校正；</li>
 *   <li>电流为 0 是<b>有效读数</b>（满电静置 / 旁路供电），返回 0W 而不是 "--"；
 *       只有所有来源都读不到才返回 NaN；</li>
 *   <li>合理性上限取 150W，覆盖 100W+ 快充，避免把真实快充功率误判成单位错误。</li>
 * </ul>
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
     * 电流上限节点：用于判定单位（毫安还是微安）。
     * 部分机型没有 {@code current_max}，但有充电限流 {@code constant_charge_current_max}，
     * 两者单位一致，都能用来判量级。
     */
    private static final String[] CURRENT_MAX_PATHS = {
            "/sys/class/power_supply/battery/current_max",
            "/sys/class/power_supply/Battery/current_max",
            "/sys/class/power_supply/bms/current_max",
            "/sys/class/power_supply/battery/constant_charge_current_max",
            "/sys/class/power_supply/Battery/constant_charge_current_max",
    };

    /**
     * 内核功率节点（µW）。功率由内核按实际拓扑算好，能绕开 current_now 的单位与串并联问题，
     * 但<b>不能无条件相信</b>：实测小米 HyperOS 3 的 {@code battery/power_now} 返回恒定值
     * （约 10W，充电为正 / 放电为负），并不是实时功率。因此只作为
     * {@link #readPowerW} 里的交叉校验项 —— 与电压×电流对得上才采用。
     * 只读 battery/bms 路径：{@code usb/power_now} 是充电器输入功率，含义不同。
     */
    private static final String[] POWER_NOW_PATHS = {
            "/sys/class/power_supply/battery/power_now",
            "/sys/class/power_supply/Battery/power_now",
            "/sys/class/power_supply/bms/power_now",
    };

    /**
     * 功率合理性上界（W）。取 150 是为了覆盖 100W/120W 快充：
     * 百瓦快充时电池端功率可能到 60–80W，旧的 30W 上限会把正确读数判成「不合理」。
     */
    private static final double PLAUSIBLE_POWER_LIMIT_W = 150d;

    /**
     * 功率死区（W）：低于此值一律算 0。
     * 已到充电上限（{@code NOT_CHARGING}）时电流在 0 附近抖动，
     * 不加死区读数会在 {@code +0.00 W} / {@code -0.00 W} 之间反复跳。
     */
    private static final double POWER_DEADBAND_W = 0.05d;

    /** charge_counter 标定窗口：至少累积 10s 才计算平均电流，太短会被读数噪声淹没。 */
    private static final long CALIBRATION_WINDOW_MS = 10_000L;

    /** 电流单位未知。 */
    private static final int UNIT_UNKNOWN = 0;
    /** current_now 单位是微安（power_supply 约定，绝大多数机型）。 */
    private static final int UNIT_MICRO_AMP = 1;
    /** current_now 单位是毫安（部分一加 / OPPO / realme 内核）。 */
    private static final int UNIT_MILLI_AMP = 2;

    /** 标定结果：0 未知 / 1 µA / 2 mA。判定后整个进程不再改动。 */
    private static volatile int currentUnit = UNIT_UNKNOWN;

    /** current_max 探测时间：节点读一次通常就够，但 root 可能晚于首次采样才授权。 */
    private static volatile long maxProbedAtMs;
    /** 单位仍未知时，重新探测 current_max 的最小间隔。 */
    private static final long MAX_PROBE_RETRY_MS = 60_000L;

    // ─── charge_counter 标定状态（跨调用累计）───
    private static long lastCounterUah = Long.MIN_VALUE;
    private static long lastCounterAtMs;
    private static long lastRawCurrent;

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
        Intent battery = stickyBatteryIntent(context);
        int status = statusOf(battery);

        // 实测支路：电压 × 电流
        int milliVolts = voltageMvOf(context, battery);
        long rawCurrent = milliVolts > 0 ? readCurrentRaw(context) : Long.MIN_VALUE;
        double measuredWatts = Double.NaN;
        if (milliVolts > 0 && rawCurrent != Long.MIN_VALUE && rawCurrent != 0L) {
            measuredWatts = applyStatusSign(status, wattsOf(milliVolts, rawCurrent));
        }

        // 内核 power_now（µW）：只在「与实测对得上」时采信。
        // 实测小米 HyperOS 3：该节点返回恒定值（约 10W），并不是实时功率。
        // 无条件优先会让充电显示 +10.00W、放电显示 -10.00W，且数值一动不动。
        long powerUw = readPowerNowUw();
        double kernelWatts = Double.NaN;
        if (powerUw != Long.MIN_VALUE) {
            kernelWatts = applyStatusSign(status, powerUw / 1e6d);
        }

        if (!Double.isNaN(measuredWatts)) {
            // 两边吻合时用内核值（它已经把串并联、单位换算处理好，读数也更平滑）
            if (!Double.isNaN(kernelWatts) && closeEnough(kernelWatts, measuredWatts)) {
                return deadband(kernelWatts);
            }
            // 对不上：说明该 ROM 的 power_now 不是实时值，以实测为准
            return deadband(measuredWatts);
        }
        if (rawCurrent == 0L) {
            // 真实零电流（满电静置 / 旁路供电）：就是 0W，不能拿内核的桩值
            return 0d;
        }
        if (!Double.isNaN(kernelWatts)) {
            // 拿不到电流时的最后一路
            return deadband(kernelWatts);
        }
        return Double.NaN;
    }

    /** 两个功率读数是否「对得上」：偏差不超过 max(0.5W, 40%)。 */
    private static boolean closeEnough(double kernelWatts, double measuredWatts) {
        double diff = Math.abs(kernelWatts - measuredWatts);
        return diff <= Math.max(0.5d, Math.abs(measuredWatts) * 0.4d);
    }

    /** 死区归零：避免 ±0.00W 来回跳。 */
    private static double deadband(double watts) {
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
     * 原始瞬时电流读数。符号保留（充电为正、放电为负），
     * <b>单位不在此处裁定</b>——由 {@link #wattsOf} 按已标定的单位换算。
     *
     * @return 真实读数（0 表示确实没有电流）；所有来源都读不到时返回 {@link Long#MIN_VALUE}
     */
    public static long readCurrentRaw(Context context) {
        long raw = readCurrentRawInternal(context);
        if (raw != Long.MIN_VALUE) {
            // 每次拿到有效读数都尝试推进单位标定（一次成功即缓存）
            maybeResolveUnit(context, raw);
        }
        return raw;
    }

    // ─── 内部：电流取值 ───

    /**
     * 内核上报的电池功率（µW）。0 与「读不到」都返回 {@link Long#MIN_VALUE}：
     * 节点存在但恒为 0 说明该内核没实现，交给电压×电流路径判断更安全；
     * 而真正的 0W（满电静置）由电压×电流路径同样算得 0。
     */
    private static long readPowerNowUw() {
        for (String path : POWER_NOW_PATHS) {
            long v = readNodeLong(path);
            if (v != Long.MIN_VALUE && v != 0L) {
                return v;
            }
        }
        return Long.MIN_VALUE;
    }

    /**
     * 电流取值主体。
     *
     * <p>优先 {@code CURRENT_AVERAGE}：不少 ROM 只实现平均电流，或 {@code CURRENT_NOW} 恒为 0；
     * 平均电流也更稳，能显著减少 ColorOS 上 ±50mA 的跳动。
     */
    private static long readCurrentRawInternal(Context context) {
        if (context != null) {
            try {
                BatteryManager bm =
                        (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
                if (bm != null) {
                    int average = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE);
                    if (average != 0 && average != Integer.MIN_VALUE) {
                        return average;
                    }
                    int now = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
                    if (now != 0 && now != Integer.MIN_VALUE) {
                        return now;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        // sysfs：0 是有效读数，只有 MIN_VALUE 才算失败
        long sysfs = BatterySysFiles.readCurrentNowMicroAmps();
        if (sysfs != Long.MIN_VALUE) {
            return sysfs;
        }
        for (String path : CURRENT_PATHS) {
            long v = readNodeLong(path);
            if (v != Long.MIN_VALUE) {
                return v;
            }
        }
        return Long.MIN_VALUE;
    }

    // ─── 内部：电流单位标定 ───

    /**
     * 推进单位标定：已标定则直接返回；否则先试 current_max，再试 charge_counter 差分。
     * 加锁是因为多个悬浮窗可能在不同线程同时首次取功耗，避免重复探测 root 节点。
     */
    private static synchronized void maybeResolveUnit(Context context, long rawCurrent) {
        long now = System.currentTimeMillis();
        if (currentUnit == UNIT_UNKNOWN
                && (maxProbedAtMs == 0L || now - maxProbedAtMs >= MAX_PROBE_RETRY_MS)) {
            maxProbedAtMs = now;
            long max = readCurrentMaxRaw();
            if (max > 0) {
                // µA 机型的上限是百万级，mA 机型是千级，量级不重叠，无需再交叉验证
                currentUnit = max > 100_000L ? UNIT_MICRO_AMP : UNIT_MILLI_AMP;
            }
        }
        if (currentUnit == UNIT_UNKNOWN) {
            int unit = calibrateUnitByChargeCounter(context, rawCurrent);
            if (unit != UNIT_UNKNOWN) {
                currentUnit = unit;
            }
        }
    }

    /** 读取电流上限节点的绝对值；读不到返回 0。 */
    private static long readCurrentMaxRaw() {
        for (String path : CURRENT_MAX_PATHS) {
            long raw = readNodeLong(path);
            if (raw == Long.MIN_VALUE || raw == 0L) {
                continue;
            }
            long v = Math.abs(raw);
            if (v > 0L) {
                return v;
            }
        }
        return 0L;
    }

    /**
     * 用 {@code charge_counter}（µAh）的增量反推平均电流，与原始读数比值得出单位。
     *
     * <p>每次调用只记录一次采样；窗口不足 {@link #CALIBRATION_WINDOW_MS}、
     * 或 counter 没变化（满电浮充）时返回未知，等下一轮。
     *
     * @return {@link #UNIT_MICRO_AMP} / {@link #UNIT_MILLI_AMP} / {@link #UNIT_UNKNOWN}
     */
    private static int calibrateUnitByChargeCounter(Context context, long rawCurrent) {
        long counter = readChargeCounterUah(context);
        long now = SystemClock.elapsedRealtime();
        long prevCounter = lastCounterUah;
        long prevAt = lastCounterAtMs;
        long prevRaw = lastRawCurrent;
        lastCounterUah = counter;
        lastCounterAtMs = now;
        lastRawCurrent = rawCurrent;

        if (counter == Long.MIN_VALUE || prevCounter == Long.MIN_VALUE
                || prevRaw == 0L || rawCurrent == 0L) {
            return UNIT_UNKNOWN;
        }
        long dtMs = now - prevAt;
        if (dtMs < CALIBRATION_WINDOW_MS) {
            return UNIT_UNKNOWN;
        }
        double deltaUah = Math.abs((double) counter - prevCounter);
        if (deltaUah < 1d) {
            return UNIT_UNKNOWN;
        }
        double hours = dtMs / 3_600_000d;
        double avgMicroAmps = deltaUah / hours;
        double rawAvg = (Math.abs(rawCurrent) + Math.abs(prevRaw)) / 2d;
        if (rawAvg < 1d) {
            return UNIT_UNKNOWN;
        }
        double ratio = avgMicroAmps / rawAvg;
        // 留出范围，避免个别机型 counter 单位异常时把结果判反
        if (ratio >= 300d && ratio <= 3000d) {
            return UNIT_MILLI_AMP;
        }
        if (ratio >= 0.3d && ratio <= 3d) {
            return UNIT_MICRO_AMP;
        }
        return UNIT_UNKNOWN;
    }

    /** {@code BATTERY_PROPERTY_CHARGE_COUNTER}（µAh）；不支持返回 {@link Long#MIN_VALUE}。 */
    private static long readChargeCounterUah(Context context) {
        if (context == null) {
            return Long.MIN_VALUE;
        }
        try {
            BatteryManager bm =
                    (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
            if (bm != null) {
                int v = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
                if (v != Integer.MIN_VALUE) {
                    return v;
                }
            }
        } catch (Exception ignored) {
        }
        return Long.MIN_VALUE;
    }

    /**
     * 由原始电流值算有符号功率（W）：{@code P = mV × I / 1e9}（µA）或 {@code / 1e6}（mA）。
     *
     * <p>单位已标定时直接换算；尚未标定（节点全部读不到）时退回旧的合理性猜测：
     * 对两种单位各算一次，取物理合理的那个，都合理时优先 µA。
     */
    private static double wattsOf(int milliVolts, long rawCurrent) {
        int unit = currentUnit;
        if (unit == UNIT_MICRO_AMP || unit == UNIT_MILLI_AMP) {
            double amps = unit == UNIT_MILLI_AMP ? rawCurrent / 1000d : rawCurrent / 1e6d;
            return milliVolts / 1000d * amps;
        }
        double asMicro = milliVolts * (double) rawCurrent / 1e9d;
        double asMilli = milliVolts * (double) rawCurrent / 1e6d;
        boolean microPlausible = Math.abs(asMicro) <= PLAUSIBLE_POWER_LIMIT_W;
        boolean milliPlausible = Math.abs(asMilli) <= PLAUSIBLE_POWER_LIMIT_W;
        if (microPlausible == milliPlausible) {
            return asMicro;
        }
        return microPlausible ? asMicro : asMilli;
    }

    // ─── 内部：电压 / 状态 / 符号 ───

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
                // 个别 ROM 这里给的是 µV（如 4400000），按量级归一，避免把 4.4V 当成 4400V
                return mv > 100_000 ? mv / 1000 : mv;
            }
        }
        // sysfs 普通读（内核多为 µV）
        long raw = BatterySysFiles.readVoltageNowMicroVolts();
        if (raw != Long.MIN_VALUE && raw > 0) {
            return normalizeVoltageToMv(raw);
        }
        // root 兜底
        for (String path : VOLTAGE_PATHS) {
            long v = readNodeLong(path);
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

    /** 经 NodeReader 读一个长整数（普通读优先，失败 root cat）；读不到返回 {@link Long#MIN_VALUE}。 */
    private static long readNodeLong(String path) {
        String raw = NodeReader.readFirstLine(path, true);
        if (raw == null) {
            return Long.MIN_VALUE;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ignored) {
            return Long.MIN_VALUE;
        }
    }

    /**
     * 把 sysfs 电压归一到 mV。
     * 内核绝大多数为 µV（4000000 ≈ 4.0V）；个别机型直接给 mV（4000）。
     * 阈值取 100000：µV 读数必然远大于它，mV 读数必然小于它。
     *
     * <p>该阈值只用于电压——电压的两种单位量级天然不重叠，可以安全地按量级判断。
     * 电流在低功耗区间没有这个性质，故改用 {@link #maybeResolveUnit} 的标定流程。
     */
    private static int normalizeVoltageToMv(long raw) {
        long mv = raw > 100_000L ? raw / 1000L : raw;
        return mv > Integer.MAX_VALUE ? -1 : (int) mv;
    }
}
