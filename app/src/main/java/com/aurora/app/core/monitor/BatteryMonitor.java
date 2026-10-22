package com.aurora.app.core.monitor;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;

import com.aurora.app.core.util.NodeReader;

/**
 * 电池监视器数据源：读取电池电量百分比。
 *
 * <p>取数顺序（按可靠性降序）：
 * <ol>
 *   <li><b>BatteryManager 粘性广播</b>（{@link Intent#ACTION_BATTERY_CHANGED}）——
 *       公开 API、无需 root、全机型可用；传 {@code null} 的 receiver 即"只读粘性值"，
 *       不会注册常驻监听，读一次几乎零开销；</li>
 *   <li><b>sysfs 节点回退</b>（{@code /sys/class/power_supply/*&#47;capacity}）——
 *       Android 12 起对普通应用为 EACCES（SELinux），故经 {@link NodeReader}
 *       走 root cat 兜底；未 root 时这一层必然失败。</li>
 * </ol>
 *
 * <p><b>与旧实现相反</b>：旧注释称「不走 BatteryManager 接口（部分机型 getIntProperty
 * 失效），直接读 sysfs 最可靠」。实测在 Android 12+ 上 SELinux 已把
 * {@code /sys/class/power_supply/**} 整体移出 untrusted_app 可读集合，
 * 而 BatteryManager 走系统服务、始终可用，因此这里把顺序倒过来。
 */
public final class BatteryMonitor {

    private BatteryMonitor() {
    }

    /** 电池电量百分比（0-100）；读取失败返回 -1。 */
    public static int readBatteryPercent(Context context) {
        int fromManager = readFromBatteryManager(context);
        if (fromManager >= 0) {
            return fromManager;
        }
        String[] paths = {
                "/sys/class/power_supply/battery/capacity",
                "/sys/class/power_supply/Battery/capacity",
                "/sys/class/power_supply/bms/capacity",
        };
        for (String path : paths) {
            String line = NodeReader.readFirstLine(path, true);
            if (line == null || line.isEmpty()) {
                continue;
            }
            try {
                long value = Long.parseLong(line.trim());
                if (value >= 0 && value <= 100) {
                    return (int) value;
                }
            } catch (NumberFormatException ignored) {
                // 格式异常，换下一个候选节点
            }
        }
        return -1;
    }

    /** 从 BatteryManager 的粘性广播读电量；失败返回 -1。 */
    private static int readFromBatteryManager(Context context) {
        if (context == null) {
            return -1;
        }
        try {
            Intent intent = context.registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (intent == null) {
                return -1;
            }
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level < 0 || scale <= 0) {
                return -1;
            }
            int percent = (int) (level * 100f / scale);
            return (percent >= 0 && percent <= 100) ? percent : -1;
        } catch (Exception ignored) {
            return -1;
        }
    }
}
