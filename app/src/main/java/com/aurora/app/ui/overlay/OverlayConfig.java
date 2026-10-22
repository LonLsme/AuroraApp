package com.aurora.app.ui.overlay;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 监视悬浮窗配置持久化工具：
 * 统一读写开关状态与「折叠/展开」状态，供 UI 层（MainActivity）和悬浮窗层共同使用，
 * 避免各处重复硬编码 prefs 名称与 key。
 *
 * <ul>
 *   <li>开关状态：{@link MonitorType#prefKey}，true = 悬浮窗开启；</li>
 *   <li>展开状态：{@code <type.prefKey>_expanded}，true = 展开大窗、false = 折叠小窗；</li>
 *   <li>窗口位置：{@code <type.prefKey>_x} / {@code _y}，用户拖动松手后保存的坐标。</li>
 * </ul>
 */
public final class OverlayConfig {

    /** SharedPreferences 文件名（与 MainActivity 的历史文件保持一致，兼容已保存的开关）。 */
    public static final String PREFS_NAME = "aurora_usage_monitor";

    private OverlayConfig() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** 指定类型的悬浮窗是否开启。 */
    public static boolean isEnabled(Context context, MonitorType type) {
        return prefs(context).getBoolean(type.prefKey, false);
    }

    /** 设置指定类型悬浮窗的开关状态。 */
    public static void setEnabled(Context context, MonitorType type, boolean enabled) {
        prefs(context).edit().putBoolean(type.prefKey, enabled).apply();
    }

    /** 指定类型悬浮窗是否处于展开大窗状态（默认折叠小窗）。 */
    public static boolean isExpanded(Context context, MonitorType type) {
        return prefs(context).getBoolean(type.prefKey + "_expanded", false);
    }

    /** 保存指定类型悬浮窗的展开/折叠状态。 */
    public static void setExpanded(Context context, MonitorType type, boolean expanded) {
        prefs(context).edit().putBoolean(type.prefKey + "_expanded", expanded).apply();
    }

    /**
     * 读取已保存的窗口 X 坐标。
     *
     * <p>返回 {@code null} 表示从未保存过位置（首次使用），调用方应回退到默认布局。
     * 之所以返回包装类型而不是默认值 0，是因为 0 本身是合法坐标，无法用来区分「没记录」。
     */
    public static Integer getPositionX(Context context, MonitorType type) {
        SharedPreferences p = prefs(context);
        String key = type.prefKey + "_x";
        return p.contains(key) ? Integer.valueOf(p.getInt(key, 0)) : null;
    }

    /** 读取已保存的窗口 Y 坐标；从未保存过返回 {@code null}。 */
    public static Integer getPositionY(Context context, MonitorType type) {
        SharedPreferences p = prefs(context);
        String key = type.prefKey + "_y";
        return p.contains(key) ? Integer.valueOf(p.getInt(key, 0)) : null;
    }

    /** 保存窗口拖动后的最终位置。 */
    public static void setPosition(Context context, MonitorType type, int x, int y) {
        prefs(context).edit()
                .putInt(type.prefKey + "_x", x)
                .putInt(type.prefKey + "_y", y)
                .apply();
    }

    /** 清除已保存的窗口位置，下次显示时回退到默认布局。 */
    public static void clearPosition(Context context, MonitorType type) {
        prefs(context).edit()
                .remove(type.prefKey + "_x")
                .remove(type.prefKey + "_y")
                .apply();
    }
}
