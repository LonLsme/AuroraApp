package com.aurora.app.core.device;

import android.content.Context;
import android.os.BatteryManager;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;

import com.aurora.app.core.shell.AdbBridge;
import com.aurora.app.core.shell.ShellManager;
import com.aurora.app.core.util.BatterySysFiles;
import com.aurora.app.core.util.CpuSysFiles;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 设备信息读取：同一个快照结构，两条来源。
 *
 * <h3>本机 vs 外接</h3>
 * <ul>
 *   <li><b>本机</b>：{@code Build.*} 直接给，CPU/电池读 sysfs，只有 {@code getenforce}
 *       与 {@code android_id} 走一次 shell（两者都受 SELinux 限制，普通应用拿不到真值）。</li>
 *   <li><b>外接设备</b>：一条 {@code adb shell} 把十几个字段一次性 dump 回来。
 *       绝不逐字段各发一条命令 —— 每次 adb 往返 50~150ms，十几次就是一两秒。</li>
 * </ul>
 *
 * <h3>为什么用 {@code echo KEY=value} 而不是自己定分隔符</h3>
 * 字段值里可能含冒号、逗号、斜杠（基带 {@code Q_V1_P14,Q_V1_P14}、指纹里全是 {@code /}），
 * 但几乎不会含换行与 {@code =}。一行一个 {@code KEY=VALUE} 既不用管转义，
 * 也能在某个 {@code getprop} 返回空时让整段结构保持对齐 —— 空值就是空串，不会错位。
 *
 * <h3>外接设备为什么不走 {@link ShellManager#exec}</h3>
 * 那是「当前最优通道」，也就是本机自己的 shell。外接设备必须显式点名序列号，
 * 所以这边直接用 {@link AdbBridge#shell}。
 */
public final class DeviceReader {

    /** 外接设备一次读取的上限：刚连上时首次 shell 会慢一拍。 */
    private static final long REMOTE_TIMEOUT_MS = 8000L;

    /** 本机拿特权字段的一次调用：五条命令合成一次，省掉四次 su 往返。 */
    private static final long LOCAL_SHELL_TIMEOUT_MS = 3000L;

    /**
     * {@code BATTERY_PROPERTY_CYCLE_COUNT}。
     *
     * <p>这个字段是 API 34 才加的，而本机 android.jar 存根里<b>没有</b>它（直接引用编译器就报
     * 「找不到符号」），所以只能反射取一次、取不到就按 AOSP 里那个值兜底。
     */
    private static final int BATTERY_PROPERTY_CYCLE_COUNT = batteryPropertyId("BATTERY_PROPERTY_CYCLE_COUNT", 35);

    private static int batteryPropertyId(String fieldName, int fallback) {
        try {
            return BatteryManager.class.getField(fieldName).getInt(null);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    /** CPU 各核节点，静态读取与每秒刷新共用这一份脚本。 */
    private static final String CPU_LOOP =
            "for d in /sys/devices/system/cpu/cpu[0-9]*; do "
                    + "id=${d##*/cpu}; "
                    + "on=$(cat $d/online 2>/dev/null); "
                    + "cur=$(cat $d/cpufreq/scaling_cur_freq 2>/dev/null); "
                    + "max=$(cat $d/cpufreq/cpuinfo_max_freq 2>/dev/null); "
                    + "min=$(cat $d/cpufreq/cpuinfo_min_freq 2>/dev/null); "
                    // key 必须是 CPU<编号>：若只写 CPU，每核都产出同一行 key，
                    // 解析时后一个会覆盖前一个，最后只剩一个核心
                    + "echo \"CPU${id}=$id:$on:$cur:$max:$min\"; "
                    + "done";

    private DeviceReader() {
    }

    // ───────────────── 对外入口 ─────────────────

    /**
     * 读一份完整快照。
     *
     * @param serial 外接设备序列号；{@code null} 表示读本机
     */
    public static DeviceSnapshot readStatic(Context context, String serial) {
        return serial == null ? readLocalStatic(context) : readRemoteStatic(context, serial);
    }

    /**
     * 只读会变的字段（各核频率 + 电池循环次数）。
     *
     * <p>本机这一份<b>不碰 shell</b>：sysfs 直接读，毫秒级，适合每秒刷。外接设备只能走
     * adb，所以调用方要把刷新间隔放宽（设备页里是 2 秒）。
     */
    public static DeviceSnapshot readDynamic(Context context, String serial) {
        return serial == null ? readLocalDynamic(context) : readRemoteDynamic(context, serial);
    }

    // ───────────────── 本机 ─────────────────

    private static DeviceSnapshot readLocalStatic(Context context) {
        DeviceSnapshot s = new DeviceSnapshot();
        s.title = (Build.MANUFACTURER + " " + Build.MODEL).trim();
        s.deviceCode = Build.DEVICE;
        s.android = "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")";

        String kernel = System.getProperty("os.version");
        s.kernel = TextUtils.isEmpty(kernel) ? "未知" : kernel;

        s.buildDisplay = Build.DISPLAY;
        s.securityPatch = Build.VERSION.SECURITY_PATCH;
        s.fingerprint = Build.FINGERPRINT;
        s.radio = Build.getRadioVersion();
        s.bootSlot = bootSlotLabel(systemProperty("ro.boot.slot_suffix",
                systemProperty("ro.boot.slot", "")));

        // 一次 shell 拿回 5 个字段：getenforce、android_id 与 BL 的三个属性。
        // Android 12 起三方应用读不到真实 SELinux 状态，android_id 也可能被 ROM 改写，
        // BL 属性则是 application 域拿不全 —— 三个都得靠 shell 身份才可信。
        // 合并成一次调用，省掉 4 次 su 往返（单次约 60~150ms）。
        String dump = ShellManager.get().execOrEmpty(
                "getenforce; echo '##AURORA##'; settings get secure android_id; "
                        + "echo '##AURORA##'; getprop ro.boot.verifiedbootstate; "
                        + "echo '##AURORA##'; getprop ro.boot.flash.locked; "
                        + "echo '##AURORA##'; getprop ro.boot.vbmeta.device_state",
                LOCAL_SHELL_TIMEOUT_MS);
        if (!dump.isEmpty()) {
            // 必须传 -1：属性不存在时 getprop 输出空行，尾部空段会被默认的 split 丢掉，
            // 那样 parts.length 小于 5，按固定下标取值就会错位
            String[] parts = dump.split("##AURORA##", -1);
            if (parts.length > 0) {
                s.selinux = emptyToNull(parts[0]);
            }
            if (parts.length > 1) {
                s.androidId = emptyToNull(parts[1]);
            }
            if (parts.length > 4) {
                s.bootloader = BootloaderState.of(parts[2], parts[3], parts[4]);
            }
        }
        if (s.androidId == null) {
            // shell 不通时的兜底：普通 API 也能读到，只是可能被 ROM 改写过
            String api = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
            s.androidId = emptyToNull(api);
        }

        DeviceSnapshot dynamic = readLocalDynamic(context);
        s.cores = dynamic.cores;
        s.batteryCycles = dynamic.batteryCycles;
        return s;
    }

    private static DeviceSnapshot readLocalDynamic(Context context) {
        DeviceSnapshot s = new DeviceSnapshot();
        int count = CpuSysFiles.getSysfsCpuCount();
        DeviceSnapshot.CpuCore[] cores = new DeviceSnapshot.CpuCore[count];
        for (int i = 0; i < count; i++) {
            boolean online = CpuSysFiles.isCpuOnline(i);
            cores[i] = new DeviceSnapshot.CpuCore(i, online,
                    online ? CpuSysFiles.readCpuCurFreqKhz(i) : -1L,
                    CpuSysFiles.readCpuMaxFreqKhz(i),
                    CpuSysFiles.readCpuMinFreqKhz(i));
        }
        s.cores = cores;
        s.batteryCycles = localCycleCount(context);
        return s;
    }

    /**
     * 电池循环次数：先问 {@link BatteryManager}（Android 14 起才有这个属性），
     * 再退到 sysfs 的 {@code cycle_count}。都读不到返回 {@code null}（页面显示「—」）。
     */
    private static String localCycleCount(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
                if (bm != null) {
                    int value = bm.getIntProperty(BATTERY_PROPERTY_CYCLE_COUNT);
                    if (value != Integer.MIN_VALUE && value >= 0) {
                        return String.valueOf(value);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        int fromSysfs = BatterySysFiles.readCycleCount();
        return fromSysfs >= 0 ? String.valueOf(fromSysfs) : null;
    }

    // ───────────────── 外接设备 ─────────────────

    private static DeviceSnapshot readRemoteStatic(Context context, String serial) {
        String cmd = "echo \"MAN=$(getprop ro.product.manufacturer)\"; "
                + "echo \"MODEL=$(getprop ro.product.model)\"; "
                + "echo \"DEVICE=$(getprop ro.product.device)\"; "
                + "echo \"REL=$(getprop ro.build.version.release)\"; "
                + "echo \"SDK=$(getprop ro.build.version.sdk)\"; "
                + "echo \"KERNEL=$(uname -r)\"; "
                + "echo \"SE=$(getenforce)\"; "
                + "echo \"SLOT=$(getprop ro.boot.slot_suffix)\"; "
                + "echo \"DISPLAY=$(getprop ro.build.display.id)\"; "
                + "echo \"PATCH=$(getprop ro.build.version.security_patch)\"; "
                + "echo \"RADIO=$(getprop gsm.version.baseband)\"; "
                + "echo \"AID=$(settings get secure android_id)\"; "
                + "echo \"FP=$(getprop ro.build.fingerprint)\"; "
                + "echo \"CYCLE=$(cat /sys/class/power_supply/battery/cycle_count 2>/dev/null)\"; "
                + "echo \"VB=$(getprop ro.boot.verifiedbootstate)\"; "
                + "echo \"FL=$(getprop ro.boot.flash.locked)\"; "
                + "echo \"VBM=$(getprop ro.boot.vbmeta.device_state)\"; "
                + CPU_LOOP;

        ShellManager.Result result = AdbBridge.shell(context, serial, cmd, REMOTE_TIMEOUT_MS);
        DeviceSnapshot s = new DeviceSnapshot();
        if (!result.hasOutput()) {
            // 连上了却读不到：返回空快照，页面显示「—」而不是崩
            return s;
        }
        applyFields(s, parseKeyValues(result.out));
        return s;
    }

    private static DeviceSnapshot readRemoteDynamic(Context context, String serial) {
        String cmd = "echo \"CYCLE=$(cat /sys/class/power_supply/battery/cycle_count 2>/dev/null)\"; " + CPU_LOOP;
        ShellManager.Result result = AdbBridge.shell(context, serial, cmd, REMOTE_TIMEOUT_MS);
        DeviceSnapshot s = new DeviceSnapshot();
        if (!result.hasOutput()) {
            return s;
        }
        applyFields(s, parseKeyValues(result.out));
        return s;
    }

    /**
     * 把 {@code KEY=VALUE} 行铺进快照；缺的字段留 {@code null}。
     *
     * <p>包内可见是为了单测能直接喂一段假输出进来 —— 外接设备那条链路在真机上
     * 才有素材，但「解析有没有把 BL 三个属性接对」不需要真机就能验。
     */
    static void applyFields(DeviceSnapshot s, Map<String, String> kv) {
        String manufacturer = kvGet(kv, "MAN");
        String model = kvGet(kv, "MODEL");
        s.title = ((manufacturer == null ? "" : manufacturer) + " " + (model == null ? "" : model)).trim();
        s.deviceCode = kvGet(kv, "DEVICE");

        String release = kvGet(kv, "REL");
        String sdk = kvGet(kv, "SDK");
        if (release != null) {
            s.android = sdk == null ? "Android " + release : "Android " + release + " (API " + sdk + ")";
        }

        s.kernel = kvGet(kv, "KERNEL");
        s.selinux = kvGet(kv, "SE");
        s.bootSlot = bootSlotLabel(kvGet(kv, "SLOT"));
        s.bootloader = BootloaderState.of(kvGet(kv, "VB"), kvGet(kv, "FL"), kvGet(kv, "VBM"));
        s.buildDisplay = kvGet(kv, "DISPLAY");
        s.securityPatch = kvGet(kv, "PATCH");
        s.radio = kvGet(kv, "RADIO");
        s.androidId = kvGet(kv, "AID");
        s.fingerprint = kvGet(kv, "FP");
        s.batteryCycles = kvGet(kv, "CYCLE");
        s.cores = parseCores(kv);
    }

    /**
     * 解析 {@code CPU=id:online:cur:max:min} 行。
     *
     * <p>按 {@code CPU0}、{@code CPU1}… 连续取值，遇到连续两个缺失就收手 ——
     * 比「先数核心数再逐个取」少一次解析，也不会因为个别核心目录缺失而整段错位。
     *
     * <p>包内可见是为了单测能直接喂一段假输出进来（这是整条外接读取链路里唯一
     * 容易写错、又不需要真机就能验的一段）。
     */
    static DeviceSnapshot.CpuCore[] parseCores(Map<String, String> kv) {
        List<DeviceSnapshot.CpuCore> cores = new ArrayList<>();
        for (int i = 0; ; i++) {
            String raw = kvGet(kv, "CPU" + i);
            if (raw == null) {
                if (kvGet(kv, "CPU" + (i + 1)) == null) {
                    break;
                }
                continue;
            }
            String[] parts = raw.split(":", -1);
            boolean online = parts.length > 1 && !parts[1].trim().isEmpty()
                    ? "1".equals(parts[1].trim())
                    // cpu0 通常没有 online 节点，缺省视为在线
                    : i == 0;
            long cur = parts.length > 2 ? parseLong(parts[2]) : -1L;
            long max = parts.length > 3 ? parseLong(parts[3]) : -1L;
            long min = parts.length > 4 ? parseLong(parts[4]) : -1L;
            cores.add(new DeviceSnapshot.CpuCore(i, online, online ? cur : -1L, max, min));
        }
        return cores.toArray(new DeviceSnapshot.CpuCore[0]);
    }

    // ───────────────── 解析工具 ─────────────────

    /** 把 {@code KEY=VALUE} 行收成 map；同一 key 只留第一次出现的值。 */
    static Map<String, String> parseKeyValues(String raw) {
        Map<String, String> map = new HashMap<>();
        if (raw == null) {
            return map;
        }
        for (String line : raw.split("\n")) {
            String text = line.trim();
            int eq = text.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = text.substring(0, eq).trim();
            String value = text.substring(eq + 1).trim();
            if (!key.isEmpty() && !map.containsKey(key)) {
                map.put(key, value);
            }
        }
        return map;
    }

    private static String kvGet(Map<String, String> kv, String key) {
        String value = kv.get(key);
        return emptyToNull(value);
    }

    private static long parseLong(String raw) {
        try {
            String text = raw == null ? "" : raw.trim();
            if (text.isEmpty()) {
                return -1L;
            }
            long value = Long.parseLong(text);
            return value > 0 ? value : -1L;
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private static String emptyToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        return text.isEmpty() || "null".equalsIgnoreCase(text) ? null : text;
    }

    /** {@code _a} / {@code a} → {@code A 槽位}；空 → 未启用 A/B 分区。 */
    static String bootSlotLabel(String raw) {
        String text = emptyToNull(raw);
        if (text == null) {
            return "未启用 A/B 分区";
        }
        String lower = text.toLowerCase(Locale.US);
        if (lower.contains("a")) {
            return "A 槽位";
        }
        if (lower.contains("b")) {
            return "B 槽位";
        }
        return text;
    }

    /** 反射读系统属性（{@code android.os.SystemProperties} 不在公开 SDK 里）。 */
    private static String systemProperty(String key, String fallback) {
        try {
            Class<?> clazz = Class.forName("android.os.SystemProperties");
            Method get = clazz.getMethod("get", String.class, String.class);
            Object value = get.invoke(null, key, fallback);
            return value instanceof String ? (String) value : fallback;
        } catch (Throwable t) {
            return fallback;
        }
    }
}
