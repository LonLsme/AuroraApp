package com.aurora.app.ui.overlay;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.aurora.app.core.monitor.FpsMonitor;
import com.aurora.app.core.monitor.LoadMonitor;
import com.aurora.app.core.monitor.TemperatureMonitor;

import java.util.Locale;

/**
 * 迷你监视器悬浮窗：
 * 单行胶囊，一行显示 CPU 使用率 / FPS / 电池温度三个核心指标，
 * 适合打游戏时放在屏幕角落。刷新周期 500ms。
 */
public final class MiniOverlayWindow extends OverlayWindow {

    private final LoadMonitor loadMonitor = new LoadMonitor();
    private final FpsMonitor fpsMonitor = new FpsMonitor();

    private TextView contentView; // 单行组合文本

    public MiniOverlayWindow(Context context) {
        super(context, MonitorType.MINI);
    }

    @Override
    protected View buildContentView() {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.HORIZONTAL);
        int pad = OverlayWindowStyle.dpToPx(context, 10);
        root.setPadding(pad, pad, pad, pad);
        root.setBackground(OverlayWindowStyle.createWindowBackground(context));

        contentView = OverlayWindowStyle.createBodyView(context);
        contentView.setTextSize(13);
        contentView.setTextColor(OverlayWindowStyle.COLOR_ACCENT);
        contentView.setText("CPU -- · FPS -- · --");
        root.addView(contentView);
        return root;
    }

    @Override
    protected void onShown() {
        // 窗口显示时开始统计帧率
        fpsMonitor.start();
    }

    @Override
    protected void onHidden() {
        // 窗口销毁时停止帧率统计，避免回调泄漏
        fpsMonitor.stop();
    }

    @Override
    protected void refreshContent() {
        int cpu = loadMonitor.readCpuUsagePercent();
        int fps = Math.round(fpsMonitor.getFps());
        String temp = TemperatureMonitor.formatTemp(
                TemperatureMonitor.readBatteryTempC(context));
        contentView.setText(String.format(Locale.US, "CPU %d%% · FPS %d · %s", cpu, fps, temp));
    }

    @Override
    protected long getRefreshIntervalMs() {
        return 500L;
    }
}
