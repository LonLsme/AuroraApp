package com.aurora.app.ui.overlay;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.aurora.app.core.monitor.FpsMonitor;
import com.aurora.app.core.monitor.GpuMonitor;
import com.aurora.app.core.monitor.LoadMonitor;
import com.aurora.app.core.monitor.MemoryMonitor;
import com.aurora.app.core.monitor.PowerMonitor;
import com.aurora.app.core.monitor.TemperatureMonitor;

import java.util.Locale;

/**
 * 迷你监视器悬浮窗（折叠/展开双状态，风格与负载监视器统一）：
 * <ul>
 *   <li>折叠小窗：单行胶囊 —— CPU / FPS / 电池温度三个核心指标；</li>
 *   <li>展开大窗：CPU/GPU、FPS、内存、功耗、温度、负载的完整明细；</li>
 *   <li>交互/状态记忆/浅色穿透背景由基类统一提供。</li>
 * </ul>
 * 数据采集在后台线程进行，FPS 由 Choreographer 在主线程统计。
 */
public final class MiniOverlayWindow extends OverlayWindow {

    private final LoadMonitor loadMonitor = new LoadMonitor();
    private final FpsMonitor fpsMonitor = new FpsMonitor();

    // ─── 采集结果（后台线程写，主线程读） ───
    private int cpuPercent = 0;
    private int gpuPercent = -1;
    private float batteryTempC = -1f;
    private float cpuTempC = -1f;
    private long memTotalKb = 0;
    private long memAvailKb = 0;
    private double powerMw = -1d;
    private float[] loadAvg = new float[]{0f, 0f, 0f};

    // ─── 折叠态视图引用 ───
    private TextView contentView; // 单行组合文本

    // ─── 展开态视图引用 ───
    private TextView overviewView; // CPU · GPU 概览
    private TextView fpsView;      // FPS
    private TextView memView;      // 内存
    private TextView powerView;    // 功耗
    private TextView tempView;     // 温度
    private TextView loadView;     // 负载

    public MiniOverlayWindow(Context context) {
        super(context, MonitorType.MINI);
    }

    @Override
    protected View buildCollapsedView() {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        contentView = OverlayWindowStyle.createDarkBodyView(context);
        contentView.setTextSize(13);
        contentView.setTextColor(OverlayWindowStyle.COLOR_ACCENT_DARK);
        contentView.setText("CPU -- · FPS -- · --");
        root.addView(contentView);
        return root;
    }

    @Override
    protected View buildExpandedView() {
        LinearLayout col = new LinearLayout(context);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(OverlayWindowStyle.createDarkTitleView(context, "迷你监视器"));

        overviewView = OverlayWindowStyle.createDarkBodyView(context);
        overviewView.setTextSize(14);
        overviewView.setTypeface(Typeface.DEFAULT_BOLD);
        overviewView.setText("--");
        col.addView(overviewView);

        fpsView = OverlayWindowStyle.createDarkBodyView(context);
        fpsView.setText("FPS --");
        col.addView(fpsView);

        memView = OverlayWindowStyle.createDarkBodyView(context);
        memView.setText("内存 --");
        col.addView(memView);

        powerView = OverlayWindowStyle.createDarkBodyView(context);
        powerView.setText("功耗 --");
        col.addView(powerView);

        tempView = OverlayWindowStyle.createDarkBodyView(context);
        tempView.setText("温度 --");
        col.addView(tempView);

        loadView = OverlayWindowStyle.createDarkBodyView(context);
        loadView.setText("负载 --");
        col.addView(loadView);
        return col;
    }

    @Override
    protected void onShown() {
        // 迷你监视器两种形态都显示 FPS，始终统计
        fpsMonitor.start();
    }

    @Override
    protected void onHidden() {
        fpsMonitor.stop();
    }

    @Override
    protected void collectData() {
        // 后台线程：文件 IO 采集
        cpuPercent = loadMonitor.readCpuUsagePercent();
        gpuPercent = GpuMonitor.readGpuUsagePercent();
        batteryTempC = TemperatureMonitor.readBatteryTempC(context);
        cpuTempC = TemperatureMonitor.readCpuTempC();
        memTotalKb = MemoryMonitor.readTotalKb();
        memAvailKb = MemoryMonitor.readAvailableKb();
        powerMw = PowerMonitor.readPowerMw();
        loadAvg = LoadMonitor.readLoadAvg();
    }

    @Override
    protected void refreshContent() {
        if (expanded) {
            overviewView.setText(String.format(Locale.US, "CPU %d%% · GPU %s",
                    cpuPercent, gpuPercent < 0 ? "--" : gpuPercent + "%"));
            float fps = fpsMonitor.getFps();
            fpsView.setText(fps > 0 ? String.format(Locale.US, "FPS %.0f", fps) : "FPS --");
            if (memTotalKb > 0 && memAvailKb >= 0) {
                memView.setText(String.format(Locale.US, "内存 %s / %s · %d%%",
                        MemoryMonitor.formatGb(memTotalKb - memAvailKb),
                        MemoryMonitor.formatGb(memTotalKb),
                        (int) Math.min(100, Math.max(0, (memTotalKb - memAvailKb) * 100 / memTotalKb))));
            } else {
                memView.setText("内存 --");
            }
            powerView.setText(powerMw > 0 ? String.format(Locale.US, "功耗 %.0f mW", powerMw) : "功耗 --");
            tempView.setText("电池 " + TemperatureMonitor.formatTemp(batteryTempC)
                    + " · CPU " + TemperatureMonitor.formatTemp(cpuTempC));
            loadView.setText(String.format(Locale.US, "负载 1m %.2f · 5m %.2f · 15m %.2f",
                    loadAvg[0], loadAvg[1], loadAvg[2]));
        } else {
            int fps = Math.round(fpsMonitor.getFps());
            String temp = TemperatureMonitor.formatTemp(batteryTempC);
            contentView.setText(String.format(Locale.US, "CPU %d%% · FPS %d · %s", cpuPercent, fps, temp));
        }
    }

    @Override
    protected long getRefreshIntervalMs() {
        return 500L;
    }
}
