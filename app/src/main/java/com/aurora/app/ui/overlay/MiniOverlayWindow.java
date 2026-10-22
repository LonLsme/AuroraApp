package com.aurora.app.ui.overlay;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.aurora.app.R;
import com.aurora.app.core.monitor.CpuCoreMonitor;
import com.aurora.app.core.monitor.FpsSampler;
import com.aurora.app.core.monitor.GpuMonitor;
import com.aurora.app.core.monitor.LoadMonitor;
import com.aurora.app.core.monitor.MemoryMonitor;
import com.aurora.app.core.monitor.PowerMonitor;
import com.aurora.app.core.monitor.TemperatureMonitor;

import java.util.Locale;

/**
 * 迷你监视器悬浮窗 —— 复刻 HoshinoScene 的 {@code fw_monitor_mini.xml}。
 *
 * <p>折叠（单行胶囊）排列与原布局逐项对应：<br>
 * {@code [图标 CPU柱状图 频率] [图标 GPU柱 CPU] [图标 FPS] [图标 电池温度]}
 * <ul>
 *   <li>CPU：20dp 宽的 5 段柱状图（历史走势，柱高 = 占用率），右侧实时频率；</li>
 *   <li>GPU：5dp 宽的单柱（当前占用），右侧实时频率；</li>
 *   <li>FPS：系统级采样（{@link FpsSampler}，非本进程 Choreographer 计数）；</li>
 *   <li>电池：温度（≥41℃ 起文字转橙/红，与圆环阈值一致）。</li>
 * </ul>
 *
 * <p>展开态：CPU/GPU/内存/帧率/功耗/温度/负载的完整读数。
 */
public final class MiniOverlayWindow extends OverlayWindow {

    private static final int CPU_HISTORY_BARS = 5;

    private final LoadMonitor loadMonitor = new LoadMonitor();
    private final FpsSampler fpsSampler = new FpsSampler();

    // ─── 采集结果 ───
    private int cpuPercent = 0;
    private int gpuPercent = -1;
    private int gpuMhz = 0;
    private int cpuMhz = 0;
    private float batteryTempC = -1f;
    private float fps = -1f;
    // 展开态附加
    private int memPercent = -1;
    private long memTotalKb = 0;
    private long memUsedKb = 0;
    private float cpuTempC = -1f;
    /** 整机功耗（W）：充电为正、放电为负；NaN = 不可用。 */
    private double powerW = Double.NaN;
    private float[] loadAvg = new float[]{0f, 0f, 0f};

    /** GPU 单柱数据（复用数组，避免每次刷新分配）。 */
    private final Integer[] gpuBarData = new Integer[]{0};

    /** 展开态的慢源（内存/功耗/温度/负载）节流间隔。 */
    private static final long SLOW_INTERVAL_MS = 3000L;
    private long lastSlowCollectMs = 0L;

    // ─── 折叠态视图 ───
    private CoreBarChartView cpuBar;
    private CoreBarChartView gpuBar;
    private TextView cpuFreqView;
    private TextView gpuFreqView;
    private TextView fpsView;
    private TextView batteryView;

    // ─── 展开态视图 ───
    private TextView overviewView;
    private TextView memView;
    private TextView powerView;
    private TextView tempView;
    private TextView loadView;

    public MiniOverlayWindow(Context context) {
        super(context, MonitorType.MINI);
    }

    /** 迷你形态使用 HoshinoScene 的 monitor_game_bg（更浅一档、仅底部圆角）。 */
    @Override
    protected void onShown() {
        setWindowBackground(OverlayWindowStyle.createMonitorMiniBackground(context));
        fpsSampler.resetBaseline();
    }

    @Override
    protected void onHidden() {
        fpsSampler.resetBaseline();
    }

    // ─── 数据采集（后台线程） ───

    @Override
    protected void collectData() {
        cpuPercent = loadMonitor.readCpuUsagePercent();
        gpuPercent = GpuMonitor.readGpuUsagePercent();
        batteryTempC = TemperatureMonitor.readBatteryTempC(context);
        fps = fpsSampler.sample();

        long now = android.os.SystemClock.elapsedRealtime();
        if (lastSlowCollectMs == 0L || now - lastSlowCollectMs >= SLOW_INTERVAL_MS) {
            lastSlowCollectMs = now;
            cpuMhz = CpuCoreMonitor.readMaxCoreFreqMhz();
            gpuMhz = GpuMonitor.readGpuFreqMhz();
            if (expanded) {
                memTotalKb = MemoryMonitor.readTotalKb();
                memUsedKb = memTotalKb - MemoryMonitor.readAvailableKb();
                memPercent = MemoryMonitor.usedPercent();
                cpuTempC = TemperatureMonitor.readCpuTempC();
                powerW = PowerMonitor.readPowerW(context);
                loadAvg = LoadMonitor.readLoadAvg();
            }
        }
    }

    @Override
    protected long getRefreshIntervalMs() {
        return 1000L;
    }

    // ─── UI 刷新（主线程） ───

    @Override
    protected void refreshContent() {
        cpuBar.push(cpuPercent);
        gpuBarData[0] = Math.max(0, gpuPercent);
        gpuBar.setData(gpuBarData);
        cpuFreqView.setText(cpuMhz > 0 ? cpuMhz + "M" : "--");
        gpuFreqView.setText(gpuMhz > 0 ? gpuMhz + "M" : "--");
        fpsView.setText(fps > 0 ? String.format(Locale.US, "%.0f", fps) : "--");

        if (batteryTempC < 0) {
            batteryView.setText("--");
            batteryView.setTextColor(OverlayWindowStyle.MONITOR_TEXT);
        } else {
            batteryView.setText(String.format(Locale.US, "%.0f℃", batteryTempC));
            // 温度阈值与电池环一致：>41 橙、>44 深橙、>=48 红
            batteryView.setTextColor(batteryTempC >= 44f
                    ? OverlayWindowStyle.MONITOR_LEVEL_VERY_HIGH
                    : batteryTempC > 41f
                    ? OverlayWindowStyle.MONITOR_LEVEL_HIGH
                    : OverlayWindowStyle.MONITOR_TEXT);
        }

        if (!expanded) {
            return;
        }
        overviewView.setText(String.format(Locale.US, "CPU %d%% · GPU %s",
                cpuPercent, gpuPercent < 0 ? "--" : gpuPercent + "%"));
        memView.setText(memPercent < 0
                ? "内存 --"
                : String.format(Locale.US, "内存 %s / %s · %d%%",
                MemoryMonitor.formatGb(memUsedKb), MemoryMonitor.formatGb(memTotalKb), memPercent));
        powerView.setText("功耗 " + PowerMonitor.formatPower(powerW));
        tempView.setText("温度 电池 " + TemperatureMonitor.formatTemp(batteryTempC)
                + " · CPU " + TemperatureMonitor.formatTemp(cpuTempC));
        loadView.setText(String.format(Locale.US, "负载 %.2f / %.2f / %.2f",
                loadAvg[0], loadAvg[1], loadAvg[2]));
    }

    // ─── 视图构建 ───

    /** 折叠态：单行胶囊（复刻 fw_monitor_mini 的排列顺序与留白）。 */
    @Override
    protected View buildCollapsedView() {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(3), dp(2), dp(2), dp(2));

        row.addView(tinyIcon(R.drawable.ic_monitor_cpu, 0));

        cpuBar = new CoreBarChartView(context);
        cpuBar.setBarCount(CPU_HISTORY_BARS);
        LinearLayout.LayoutParams cpuBarLp = new LinearLayout.LayoutParams(dp(20), dp(9));
        cpuBarLp.setMarginStart(dp(1));
        row.addView(cpuBar, cpuBarLp);

        cpuFreqView = tinyValue(dp(24), 0);
        row.addView(cpuFreqView);

        row.addView(tinyIcon(R.drawable.ic_monitor_gpu, 2));

        gpuBar = new CoreBarChartView(context);
        gpuBar.setBarCount(1);
        LinearLayout.LayoutParams gpuBarLp = new LinearLayout.LayoutParams(dp(5), dp(9));
        gpuBarLp.setMarginStart(dp(2));
        row.addView(gpuBar, gpuBarLp);

        gpuFreqView = tinyValue(dp(24), 1);
        row.addView(gpuFreqView);

        row.addView(tinyIcon(R.drawable.ic_monitor_frame, 2));

        fpsView = tinyValue(dp(22), 1);
        fpsView.setTextSize(10);
        row.addView(fpsView);

        row.addView(tinyIcon(R.drawable.ic_monitor_battery, 2));

        batteryView = tinyValue(dp(32), 1);
        row.addView(batteryView);
        return row;
    }

    /** 展开态：完整读数。 */
    @Override
    protected View buildExpandedView() {
        LinearLayout col = new LinearLayout(context);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(OverlayWindowStyle.createMonitorTitleView(context, "迷你监视器"));

        overviewView = OverlayWindowStyle.createMonitorMonoView(context);
        overviewView.setTextSize(12);
        col.addView(overviewView);

        memView = OverlayWindowStyle.createMonitorMonoView(context);
        powerView = OverlayWindowStyle.createMonitorMonoView(context);
        tempView = OverlayWindowStyle.createMonitorMonoView(context);
        loadView = OverlayWindowStyle.createMonitorMonoView(context);
        col.addView(memView);
        col.addView(powerView);
        col.addView(tempView);
        col.addView(loadView);
        return col;
    }

    // ─── 小尺寸工厂 ───

    private ImageView tinyIcon(int resId, int marginStartDp) {
        ImageView iv = new ImageView(context);
        iv.setImageResource(resId);
        iv.setAlpha(0.85f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(9), dp(9));
        lp.setMarginStart(dp(marginStartDp));
        iv.setLayoutParams(lp);
        return iv;
    }

    /** 迷你数值文本：等宽白字，给定最小宽度避免数字位数变化时窗口抖动。 */
    private TextView tinyValue(int minWidthPx, int marginStartDp) {
        TextView tv = new TextView(context);
        tv.setTextColor(OverlayWindowStyle.MONITOR_TEXT);
        tv.setTextSize(9);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setIncludeFontPadding(false);
        tv.setSingleLine(true);
        tv.setText("--");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tv.setMinWidth(minWidthPx);
        lp.setMarginStart(dp(marginStartDp));
        tv.setLayoutParams(lp);
        return tv;
    }

    private int dp(int value) {
        return OverlayWindowStyle.dpToPx(context, value);
    }
}
