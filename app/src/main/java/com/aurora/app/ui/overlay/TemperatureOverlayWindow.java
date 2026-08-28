package com.aurora.app.ui.overlay;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.aurora.app.core.monitor.TemperatureMonitor;

import java.util.List;

/**
 * 温度监视器悬浮窗（折叠/展开双状态，风格与负载监视器统一）：
 * <ul>
 *   <li>折叠小窗：单行 —— 电池温度 · CPU 温度；</li>
 *   <li>展开大窗：电池/CPU 温度 + 全部 thermal zone 温度列表；</li>
 *   <li>交互/状态记忆/浅色穿透背景由基类统一提供。</li>
 * </ul>
 * 数据采集（sysfs 温度读取）在后台线程进行。
 */
public final class TemperatureOverlayWindow extends OverlayWindow {

    /** 展开态最多展示的热区条数（防止窗口过长）。 */
    private static final int ZONES_LIMIT = 12;

    // ─── 采集结果（后台线程写，主线程读） ───
    private float batteryTempC = -1f;
    private float cpuTempC = -1f;
    private List<String> zones = java.util.Collections.emptyList();

    // ─── 折叠态视图引用 ───
    private TextView foldRow; // 单行：电池 · CPU

    // ─── 展开态视图引用 ───
    private TextView batteryRow; // 电池温度行
    private TextView cpuRow;     // CPU 温度行
    private final List<TextView> zoneRows = new java.util.ArrayList<>(); // 各热区行

    public TemperatureOverlayWindow(Context context) {
        super(context, MonitorType.TEMPERATURE);
    }

    @Override
    protected View buildCollapsedView() {
        LinearLayout col = new LinearLayout(context);
        col.setOrientation(LinearLayout.VERTICAL);
        foldRow = OverlayWindowStyle.createDarkBodyView(context);
        foldRow.setText("电池 -- · CPU --");
        col.addView(foldRow);
        return col;
    }

    @Override
    protected View buildExpandedView() {
        LinearLayout col = new LinearLayout(context);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(OverlayWindowStyle.createDarkTitleView(context, "温度"));

        batteryRow = OverlayWindowStyle.createDarkBodyView(context);
        batteryRow.setTextSize(14);
        batteryRow.setTypeface(Typeface.DEFAULT_BOLD);
        batteryRow.setText("电池 --");
        col.addView(batteryRow);

        cpuRow = OverlayWindowStyle.createDarkBodyView(context);
        cpuRow.setText("CPU --");
        col.addView(cpuRow);

        col.addView(OverlayWindowStyle.createDarkTitleView(context, "热区"));
        zoneRows.clear();
        for (int i = 0; i < ZONES_LIMIT; i++) {
            TextView row = OverlayWindowStyle.createDarkBodyView(context);
            row.setText("--");
            row.setTextSize(10);
            col.addView(row);
            zoneRows.add(row);
        }
        return col;
    }

    @Override
    protected void collectData() {
        // 后台线程：sysfs 温度读取 + 热区遍历
        batteryTempC = TemperatureMonitor.readBatteryTempC(context);
        cpuTempC = TemperatureMonitor.readCpuTempC();
        zones = TemperatureMonitor.readAllZones();
    }

    @Override
    protected void refreshContent() {
        if (expanded) {
            batteryRow.setText("电池 " + TemperatureMonitor.formatTemp(batteryTempC));
            cpuRow.setText("CPU " + TemperatureMonitor.formatTemp(cpuTempC));
            for (int i = 0; i < zoneRows.size(); i++) {
                zoneRows.get(i).setText(i < zones.size() ? zones.get(i) : "");
            }
        } else {
            foldRow.setText("电池 " + TemperatureMonitor.formatTemp(batteryTempC)
                    + " · CPU " + TemperatureMonitor.formatTemp(cpuTempC));
        }
    }

    @Override
    protected long getRefreshIntervalMs() {
        return 2000L;
    }
}
