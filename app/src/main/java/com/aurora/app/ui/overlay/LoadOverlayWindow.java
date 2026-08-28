package com.aurora.app.ui.overlay;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.aurora.app.core.monitor.CpuCoreMonitor;
import com.aurora.app.core.monitor.FpsMonitor;
import com.aurora.app.core.monitor.GpuMonitor;
import com.aurora.app.core.monitor.LoadMonitor;
import com.aurora.app.core.monitor.MemoryMonitor;
import com.aurora.app.core.monitor.PowerMonitor;
import com.aurora.app.core.monitor.TemperatureMonitor;

import java.util.Locale;

/**
 * 负载监视器悬浮窗（折叠/展开双状态）：
 * <ul>
 *   <li>折叠小窗（默认）：两个圆环（CPU / GPU 使用率）+ 电池温度，窗口小巧；</li>
 *   <li>展开大窗：完整详细数据 —— 各 CPU 核心频率与占用、内存、FPS、功耗、温度、负载；</li>
 *   <li>交互：<b>单击</b>切换折叠/展开；<b>双击</b>关闭销毁本悬浮窗（并同步开关状态）；</li>
 *   <li>状态记忆：展开/折叠状态持久化到 {@link OverlayConfig}，App 重启恢复时维持；</li>
 *   <li>背景：浅色半透明，可穿透看到下层内容。</li>
 * </ul>
 * 两种形态共用同一个窗口实例（内部切换内容），不会产生第二个悬浮窗。
 */
public final class LoadOverlayWindow extends OverlayWindow {

    // ─── 数据源（每个窗口独立实例，跨刷新保留采样基线） ───
    private final LoadMonitor loadMonitor = new LoadMonitor();
    private final CpuCoreMonitor coreMonitor = new CpuCoreMonitor();
    private final FpsMonitor fpsMonitor = new FpsMonitor();

    /** 当前是否展开大窗（false = 折叠小窗）。 */
    private boolean expanded;

    // ─── 折叠态视图引用 ───
    private GaugeRingView cpuRing;  // CPU 使用率圆环
    private GaugeRingView gpuRing;  // GPU 使用率圆环
    private TextView batteryTempView; // 折叠态电池温度文本

    // ─── 展开态视图引用 ───
    private TextView overviewView;     // CPU · GPU 概览行
    private final java.util.List<TextView> coreRows = new java.util.ArrayList<>(); // 每行两个核心
    private TextView memView;          // 内存
    private TextView fpsView;          // FPS
    private TextView powerView;        // 功耗
    private TextView tempView;         // 温度
    private TextView loadView;         // 负载

    public LoadOverlayWindow(Context context) {
        super(context, MonitorType.LOAD);
        // 初始形态取上次记忆（默认折叠小窗）
        this.expanded = OverlayConfig.isExpanded(context, MonitorType.LOAD);
    }

    // ─── 生命周期 ───

    @Override
    protected View buildContentView() {
        // 根容器：替换内容时由基类 swapContent 维持同一窗口实例
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = OverlayWindowStyle.dpToPx(context, 10);
        root.setPadding(pad, pad, pad, pad);
        root.setBackground(OverlayWindowStyle.createWindowBackgroundLight(context));
        root.addView(expanded ? buildExpandedContent() : buildCollapsedContent());
        return root;
    }

    @Override
    protected void onShown() {
        // 展开态需要 FPS，折叠态不显示但保持统计以便随时切换
        fpsMonitor.start();
    }

    @Override
    protected void onHidden() {
        fpsMonitor.stop();
    }

    // ─── 交互：单击切换 / 双击关闭 ───

    @Override
    protected void onSingleTap() {
        // 单击：折叠 ↔ 展开，带平滑过渡动画，并记忆状态
        expanded = !expanded;
        OverlayConfig.setExpanded(context, MonitorType.LOAD, expanded);
        swapContent(expanded ? buildExpandedContent() : buildCollapsedContent(), true);
    }

    @Override
    protected void onDoubleTap() {
        // 双击：关闭并销毁本悬浮窗，同时把开关状态同步为关闭
        OverlayConfig.setEnabled(context, MonitorType.LOAD, false);
        MonitorOverlayService.stopOverlay(context, MonitorType.LOAD);
    }

    // ─── 数据刷新 ───

    @Override
    protected void refreshContent() {
        if (expanded) {
            refreshExpanded();
        } else {
            refreshCollapsed();
        }
    }

    @Override
    protected long getRefreshIntervalMs() {
        return 1000L;
    }

    /** 折叠态刷新：两个圆环 + 电池温度。 */
    private void refreshCollapsed() {
        int cpu = loadMonitor.readCpuUsagePercent();
        int gpu = GpuMonitor.readGpuUsagePercent();
        cpuRing.setProgress(cpu);
        gpuRing.setProgress(gpu);
        batteryTempView.setText(TemperatureMonitor.formatTemp(
                TemperatureMonitor.readBatteryTempC(context)));
    }

    /** 展开态刷新：全部详细数据。 */
    private void refreshExpanded() {
        int cpu = loadMonitor.readCpuUsagePercent();
        int gpu = GpuMonitor.readGpuUsagePercent();
        overviewView.setText(String.format(Locale.US, "CPU %d%% · GPU %s",
                cpu, gpu < 0 ? "--" : gpu + "%"));

        // 各核心：每行两个核心
        CpuCoreMonitor.CoreState[] cores = coreMonitor.readCoreStates();
        int rowCount = (cores.length + 1) / 2;
        for (int i = 0; i < coreRows.size(); i++) {
            TextView row = coreRows.get(i);
            int a = i * 2;
            int b = i * 2 + 1;
            row.setText(formatCore(cores, a) + "    " + formatCore(cores, b));
        }

        // 内存 / FPS / 功耗
        long total = MemoryMonitor.readTotalKb();
        long avail = MemoryMonitor.readAvailableKb();
        if (total > 0 && avail >= 0) {
            memView.setText(String.format(Locale.US, "内存 %s / %s · %d%%",
                    MemoryMonitor.formatGb(total - avail), MemoryMonitor.formatGb(total),
                    MemoryMonitor.usedPercent()));
        } else {
            memView.setText("内存 --");
        }
        float fps = fpsMonitor.getFps();
        fpsView.setText(fps > 0 ? String.format(Locale.US, "FPS %.0f", fps) : "FPS --");
        double mw = PowerMonitor.readPowerMw();
        powerView.setText(mw > 0 ? String.format(Locale.US, "功耗 %.0f mW", mw) : "功耗 --");

        // 温度与负载
        tempView.setText("电池 " + TemperatureMonitor.formatTemp(
                TemperatureMonitor.readBatteryTempC(context))
                + " · CPU " + TemperatureMonitor.formatTemp(TemperatureMonitor.readCpuTempC()));
        float[] avg = LoadMonitor.readLoadAvg();
        loadView.setText(String.format(Locale.US, "负载 1m %.2f · 5m %.2f · 15m %.2f",
                avg[0], avg[1], avg[2]));
    }

    /** 单个核心文本：C{index} {freq}MHz {percent}%。 */
    private String formatCore(CpuCoreMonitor.CoreState[] cores, int index) {
        if (index >= cores.length) {
            return "";
        }
        CpuCoreMonitor.CoreState c = cores[index];
        return String.format(Locale.US, "C%d %dM %d%%", c.index, c.freqMhz, c.percent);
    }

    // ─── 视图构建 ───

    /** 折叠小窗内容：CPU 环 + GPU 环 + 电池温度。 */
    private View buildCollapsedContent() {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);

        cpuRing = new GaugeRingView(context, OverlayWindowStyle.COLOR_ACCENT_DARK);
        gpuRing = new GaugeRingView(context, 0xFF00B8D4); // GPU 用青色环

        int ringSize = OverlayWindowStyle.dpToPx(context, 42);
        LinearLayout.LayoutParams ringLp =
                new LinearLayout.LayoutParams(ringSize, ringSize);
        ringLp.setMargins(0, 0, OverlayWindowStyle.dpToPx(context, 4), 0);
        row.addView(cpuRing, ringLp);
        row.addView(gpuRing, ringLp);

        // 温度列
        LinearLayout tempCol = new LinearLayout(context);
        tempCol.setOrientation(LinearLayout.VERTICAL);
        tempCol.setGravity(android.view.Gravity.CENTER);
        batteryTempView = new TextView(context);
        batteryTempView.setTextColor(OverlayWindowStyle.COLOR_TEXT_DARK);
        batteryTempView.setTextSize(14);
        batteryTempView.setTypeface(Typeface.DEFAULT_BOLD);
        batteryTempView.setText("--");
        tempCol.addView(batteryTempView);
        TextView tempLabel = OverlayWindowStyle.createDarkTitleView(context, "温度");
        tempCol.addView(tempLabel);
        row.addView(tempCol);
        return row;
    }

    /** 展开大窗内容：完整详细数据文本。 */
    private View buildExpandedContent() {
        LinearLayout col = new LinearLayout(context);
        col.setOrientation(LinearLayout.VERTICAL);

        col.addView(OverlayWindowStyle.createDarkTitleView(context, "负载监视器"));

        // 概览行：CPU · GPU
        overviewView = new TextView(context);
        overviewView.setTextColor(OverlayWindowStyle.COLOR_ACCENT_DARK);
        overviewView.setTextSize(16);
        overviewView.setTypeface(Typeface.DEFAULT_BOLD);
        overviewView.setText("--");
        col.addView(overviewView);

        col.addView(newDivider());

        // 各核心行（动态按行数添加，每行两个核心）
        coreRows.clear();
        int coreCount = Runtime.getRuntime().availableProcessors();
        int rowCount = (coreCount + 1) / 2;
        for (int i = 0; i < rowCount; i++) {
            TextView row = OverlayWindowStyle.createDarkBodyView(context);
            row.setText("--");
            row.setTypeface(Typeface.MONOSPACE);
            col.addView(row);
            coreRows.add(row);
        }

        col.addView(newDivider());

        memView = OverlayWindowStyle.createDarkBodyView(context);
        memView.setText("内存 --");
        col.addView(memView);

        fpsView = OverlayWindowStyle.createDarkBodyView(context);
        fpsView.setText("FPS --");
        col.addView(fpsView);

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

    /** 细分隔线。 */
    private View newDivider() {
        View div = new View(context);
        int margin = OverlayWindowStyle.dpToPx(context, 6);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, OverlayWindowStyle.dpToPx(context, 1));
        lp.setMargins(0, margin, 0, margin);
        div.setBackgroundColor(0x1F000000);
        div.setLayoutParams(lp);
        return div;
    }
}
