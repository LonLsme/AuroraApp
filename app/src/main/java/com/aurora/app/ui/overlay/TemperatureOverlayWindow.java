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
    protected void refreshContent() {
        batteryRow.setText("电池 " + TemperatureMonitor.formatTemp(
                TemperatureMonitor.readBatteryTempC(context)));
        cpuRow.setText("CPU " + TemperatureMonitor.formatTemp(
                TemperatureMonitor.readCpuTempC()));
    }

    @Override
    protected long getRefreshIntervalMs() {
        return 2000L;
    }
}
