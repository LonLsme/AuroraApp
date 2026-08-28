package com.aurora.app.ui.overlay;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.aurora.app.core.monitor.LoadMonitor;

import java.util.Locale;

/**
 * 负载监视器悬浮窗：
 * 显示 CPU 总使用率（Aurora 绿大数字）+ /proc/loadavg 的 1/5/15 分钟平均负载。
 * 刷新周期 1s。
 */
public final class LoadOverlayWindow extends OverlayWindow {

    /** 数据源：跨刷新周期保留上次快照用于差值计算。 */
    private final LoadMonitor loadMonitor = new LoadMonitor();

    private TextView valueView; // CPU 使用率大数字
    private TextView loadView;  // 平均负载小字

    public LoadOverlayWindow(Context context) {
        super(context, MonitorType.LOAD);
    }

    @Override
    protected View buildContentView() {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = OverlayWindowStyle.dpToPx(context, OverlayWindowStyle.PADDING_DP);
        root.setPadding(pad, pad, pad, pad);
        root.setBackground(OverlayWindowStyle.createWindowBackground(context));

        root.addView(OverlayWindowStyle.createTitleView(context, "负载"));

        valueView = OverlayWindowStyle.createValueView(context);
        valueView.setText("--");
        root.addView(valueView);

        loadView = OverlayWindowStyle.createBodyView(context);
        loadView.setText("--");
        root.addView(loadView);
        return root;
    }

    @Override
    protected void refreshContent() {
        int cpu = loadMonitor.readCpuUsagePercent();
        valueView.setText(cpu + "%");
        float[] avg = LoadMonitor.readLoadAvg();
        loadView.setText(String.format(Locale.US, "1m %.2f · 5m %.2f · 15m %.2f", avg[0], avg[1], avg[2]));
    }

    @Override
    protected long getRefreshIntervalMs() {
        return 1000L;
    }
}
