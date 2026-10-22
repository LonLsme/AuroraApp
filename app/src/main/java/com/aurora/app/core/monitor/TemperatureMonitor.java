package com.aurora.app.core.monitor;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;

import com.aurora.app.core.root.RootShell;
import com.aurora.app.core.util.NodeReader;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 温度监视器数据源：
 * <ul>
 *   <li><b>电池温度</b>：优先用 BatteryManager 粘性广播的 {@code EXTRA_TEMPERATURE}
 *       （公开 API、无需 root、单位固定为 0.1℃），失败再回退电源管理 sysfs；</li>
 *   <li><b>CPU 温度</b>：遍历 /sys/class/thermal 下的 thermal_zone 目录读取 temp 节点，
 *       优先取 type 含 cpu/tsens 的热区，否则取所有热区最大值作为近似热点温度。</li>
 * </ul>
 *
 * <p><b>Android 12+ 权限提示</b>：{@code /sys/class/power_supply/**} 与
 * {@code /sys/class/thermal/**} 对普通应用已是 EACCES（SELinux），
 * 因此 sysfs 一律经 {@link NodeReader} 读取（普通读优先 → root cat 兜底）。
 * 未 root 设备上电池温度由 BatteryManager 兜住，CPU 温度则不可得（显示 "--"）。
 *
 * <p>性能：热区目录/type 是静态信息，首扫一次建索引并缓存
 * （{@link #ZONE_SCAN_TTL_MS} 后重扫），运行期只读命中 zone 的 temp 节点——
 * 避免悬浮窗每秒对几十个 thermal_zone 做「目录枚举 + type 文件 + temp 文件」全量遍历
 * （这是负载/温度悬浮窗每秒采集的耗时大头）。
 */
public final class TemperatureMonitor {

    /** 热区索引缓存时长：30 秒后重扫一次（覆盖热区插拔等变化）。 */
    private static final long ZONE_SCAN_TTL_MS = 30_000L;

    private TemperatureMonitor() {
    }

    /**
     * 电池温度（°C）。无电池或读取失败返回 -1。
     *
     * <p>先走 BatteryManager（始终可用），再回退 sysfs（Android 12+ 需 root）。
     */
    public static float readBatteryTempC(Context context) {
        float fromManager = readBatteryTempFromManager(context);
        if (fromManager >= 0f) {
            return fromManager;
        }
        // 电源 sysfs 温度：常规值以 0.1°C 为单位（如 350 = 35.0°C），个别机型为 1°C 或毫摄氏度，按数值大小粗略归一
        String[] paths = {
                "/sys/class/power_supply/battery/temp",
                "/sys/class/power_supply/Battery/temp",
                "/sys/class/power_supply/bms/temp",
        };
        for (String path : paths) {
            long raw = readLongFirstLine(path);
            if (raw > 0) {
                // TODO(真机校准)：不同厂商 sysfs temp 单位不一（0.1°C / 1°C / 毫摄氏），
                // 目前按数值量级粗略归一，若某机型读数异常需在此补充该机型的单位规则
                return raw > 1000 ? raw / 1000f : raw / 10f;
            }
        }
        return -1f;
    }

    /**
     * 从 BatteryManager 粘性广播读电池温度；失败返回 -1。
     * {@code EXTRA_TEMPERATURE} 的单位恒为 0.1℃，无需按机型猜测。
     */
    private static float readBatteryTempFromManager(Context context) {
        if (context == null) {
            return -1f;
        }
        try {
            Intent intent = context.registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (intent == null) {
                return -1f;
            }
            int tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
            if (tenths == Integer.MIN_VALUE) {
                return -1f;
            }
            float celsius = tenths / 10f;
            // 合理区间兜底：个别模拟器/虚拟设备会返回 0 或明显越界值
            return (celsius > -30f && celsius < 100f) ? celsius : -1f;
        } catch (Exception ignored) {
            return -1f;
        }
    }

    /** CPU 温度（°C）。读取失败返回 -1。 */
    public static float readCpuTempC() {
        ensureZoneIndex();
        // 优先 cpu/tsens/soc 类型热区；无命中时用全部有效热区的最大温度兜底
        List<String> zones = !cpuZones.isEmpty() ? cpuZones : zoneNames;
        float max = -1f;
        for (String zoneName : zones) {
            long raw = readLongFirstLine(tempPathOf(zoneName));
            if (raw <= 0) {
                continue;
            }
            float tempC = normalizeTemp(raw);
            max = Math.max(max, tempC);
        }
        return max;
    }

    /**
     * 读取全部 thermal zone 温度，返回 "类型 温度" 文本列表（用于展开大窗）。
     * 类型缺失时回退 zone 名称；无热区返回空列表。
     */
    public static List<String> readAllZones() {
        ensureZoneIndex();
        List<String> zones = new ArrayList<>(zoneNames.size());
        for (int i = 0; i < zoneNames.size(); i++) {
            String zoneName = zoneNames.get(i);
            long raw = readLongFirstLine(tempPathOf(zoneName));
            if (raw <= 0) {
                continue;
            }
            String type = zoneTypes.get(i);
            if (type == null || type.isEmpty()) {
                type = zoneName;
            }
            zones.add(type + " " + formatTemp(normalizeTemp(raw)));
        }
        return zones;
    }

    // ─── 热区索引（30s 缓存） ───

    private static volatile long zoneScanAtMs = 0L;
    /** 可读出有效温度的 zone 目录名（如 thermal_zone0）。 */
    private static volatile List<String> zoneNames = new ArrayList<>();
    /** 与 {@link #zoneNames} 对齐的 type 内容。 */
    private static volatile List<String> zoneTypes = new ArrayList<>();
    /** type 含 cpu/tsens/soc 的热区（CPU 温度优先取这些）。 */
    private static volatile List<String> cpuZones = new ArrayList<>();

    private static void ensureZoneIndex() {
        long now = System.currentTimeMillis();
        if (now - zoneScanAtMs < ZONE_SCAN_TTL_MS) {
            return;
        }
        synchronized (TemperatureMonitor.class) {
            if (System.currentTimeMillis() - zoneScanAtMs < ZONE_SCAN_TTL_MS) {
                return;
            }
            zoneScanAtMs = now;
            List<String> names = new ArrayList<>();
            List<String> types = new ArrayList<>();
            List<String> cpus = new ArrayList<>();
            for (String name : listDirNames("/sys/class/thermal")) {
                if (!name.startsWith("thermal_zone")) {
                    continue;
                }
                String type = readFirstLine("/sys/class/thermal/" + name + "/type");
                if (type == null) {
                    type = name;
                }
                if (readLongFirstLine(tempPathOf(name)) <= 0) {
                    continue;   // temp 不可读的 zone 不建档，避免运行期反复失败
                }
                names.add(name);
                types.add(type);
                if (type.contains("cpu") || type.contains("tsens") || type.contains("soc")) {
                    cpus.add(name);
                }
            }
            zoneNames = names;
            zoneTypes = types;
            cpuZones = cpus;
        }
    }

    /**
     * 列出目录下的全部条目名。
     *
     * <p>Android 12+ 下 {@code /sys/class/thermal} 连 {@code listFiles()} 都被 SELinux
     * 拒绝（目录不可 list，返回 null），必须经 root 兜底；否则热区索引恒为空、
     * CPU 温度永远显示 "--"。
     */
    private static List<String> listDirNames(String dir) {
        File[] entries = new File(dir).listFiles();
        if (entries != null && entries.length > 0) {
            List<String> plain = new ArrayList<>(entries.length);
            for (File entry : entries) {
                if (entry.isDirectory()) {
                    plain.add(entry.getName());
                }
            }
            if (!plain.isEmpty()) {
                return plain;
            }
        }
        RootShell.Result result = RootShell.get().exec("ls -1 " + RootShell.escape(dir));
        if (result == null || !result.isSuccess()) {
            return java.util.Collections.emptyList();
        }
        return result.lines();
    }

    private static String tempPathOf(String zoneName) {
        return "/sys/class/thermal/" + zoneName + "/temp";
    }

    /** 归一化温度：毫摄氏度（>1000）÷1000，0.1°C 的 ÷10。 */
    private static float normalizeTemp(long raw) {
        return raw > 1000 ? raw / 1000f : raw / 10f;
    }

    /** 读文件首行；普通读优先，EACCES（Android 12+ 的 thermal/power_supply）时 root cat 兜底。 */
    private static String readFirstLine(String path) {
        return NodeReader.readFirstLine(path, true);
    }

    /** 读文件首行并解析为 long；失败返回 0。 */
    private static long readLongFirstLine(String path) {
        String line = NodeReader.readFirstLine(path, true);
        if (line == null || line.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(line.trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    /** 格式化温度文本：xx.x°C；无效值返回 "--" 。 */
    public static String formatTemp(float tempC) {
        if (tempC < 0) {
            return "--";
        }
        return String.format(Locale.US, "%.1f°C", tempC);
    }
}
