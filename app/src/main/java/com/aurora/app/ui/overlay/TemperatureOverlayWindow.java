package com.aurora.app.ui.overlay;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.aurora.app.core.monitor.TemperatureMonitor;

/**
 * 温度监视器悬浮窗：
 * 显示电池温度与 CPU 温度两行数据。
 * 刷新周期 2s。
 */
public final class TemperatureOverlayWindow extends OverlayWindow {

    private TextView batteryRow; // 电池温度行
    private TextView cpuRow;     // CPU 温度行

    /** 最近一次采集的温度（后台线程写，主线程读）。 */
    private float batteryTempC = -1f;
    private float cpuTempC = -1f;

    public TemperatureOverlayWindow(Context context) {
        super(context, MonitorType.TEMPERATURE);
    }

    @Override
    protected View buildContentView() {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = OverlayWindowStyle.dpToPx(context, OverlayWindowStyle.PADDING_DP);
        root.setPadding(pad, pad, pad, pad);
        root.setBackground(OverlayWindowStyle.createWindowBackground(context));

        root.addView(OverlayWindowStyle.createTitleView(context, "温度"));

        batteryRow = OverlayWindowStyle.createBodyView(context);
        batteryRow.setText("电池 --");
        root.addView(batteryRow);

        cpuRow = OverlayWindowStyle.createBodyView(context);
        cpuRow.setText("CPU --");
        root.addView(cpuRow);
        return root;
    }

    @Override
    protected void collectData() {
        // 后台线程：sysfs 温度读取
        batteryTempC = TemperatureMonitor.readBatteryTempC(context);
        cpuTempC = TemperatureMonitor.readCpuTempC();
    }

    @Override
    protected void refreshContent() {
        batteryRow.setText("电池 " + TemperatureMonitor.formatTemp(batteryTempC));
        cpuRow.setText("CPU " + TemperatureMonitor.formatTemp(cpuTempC));
    }

    @Override
    protected long getRefreshIntervalMs() {
        return 2000L;
    }
}
