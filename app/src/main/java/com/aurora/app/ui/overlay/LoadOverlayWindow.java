package com.aurora.app.ui.overlay;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.aurora.app.core.monitor.BatteryMonitor;
import com.aurora.app.core.monitor.CpuCoreMonitor;
import com.aurora.app.core.monitor.FpsMonitor;
import com.aurora.app.core.monitor.GpuMonitor;
import com.aurora.app.core.monitor.LoadMonitor;
import com.aurora.app.core.monitor.MemoryMonitor;
import com.aurora.app.core.monitor.PowerMonitor;
import com.aurora.app.core.monitor.TemperatureMonitor;

import java.util.List;
import java.util.Locale;

/**
 * 负载监视器悬浮窗（折叠/展开双状态）：
 * <ul>
 *   <li>折叠小窗（默认）：3 个圆环 —— CPU（中心标"CPU"，环下 MHz）、
 *       GPU（中心标"GPU"，环下 MHz）、电池（中心显示电量 %，环下显示温度）；</li>
 *   <li>展开大窗：完整详细数据 —— 各 CPU 核心频率与占用（MHz）、内存、FPS、功耗、温度、负载；</li>
 *   <li>交互：<b>单击</b>切换折叠/展开；<b>双击</b>关闭销毁本悬浮窗（并同步开关状态）；</li>
 *   <li>状态记忆：展开/折叠状态持久化到 {@link OverlayConfig}，App 重启恢复时维持；</li>
 *   <li>背景：浅色半透明，可穿透看到下层内容。</li>
 * </ul>
 * 两种形态共用同一个窗口实例（内部切换内容），不会产生第二个悬浮窗。
 *
 * <p>数据采集在后台线程（{@link #collectData()}），UI 刷新在主线程（{@link #refreshContent()}）。
 */
public final class LoadOverlayWindow extends OverlayWindow {

    // ─── 数据源（每个窗口独立实例，跨刷新保留采样基线；只在后台采集线程使用） ───
    private final LoadMonitor loadMonitor = new LoadMonitor();
    private final CpuCoreMonitor coreMonitor = new CpuCoreMonitor();
    private final FpsMonitor fpsMonitor = new FpsMonitor();

    /** 当前是否展开大窗（false = 折叠小窗）。 */
    private boolean expanded;

    // ─── 采集结果（后台线程写，主线程读） ───
    // 折叠态数据
    private int cpuPercent = 0;
    private int gpuPercent = -1;
    private int batteryPercent = -1;
    private int cpuMhz = 0;
    private int gpuMhz = 0;
    private float batteryTempC = -1f;
    // 展开态数据
    private CpuCoreMonitor.CoreState[] coreStates = new CpuCoreMonitor.CoreState[0];
    private long memTotalKb = 0;
    private long memAvailKb = 0;
    private double powerMw = -1d;
    private float cpuTempC = -1f;
    private float[] loadAvg = new float[]{0f, 0f, 0f};

    // ─── 折叠态视图引用 ───
    private GaugeRingView cpuRing;     // CPU 使用率圆环
    private GaugeRingView gpuRing;     // GPU 使用率圆环
    private GaugeRingView batteryRing; // 电池电量圆环（下方显示温度）
    private TextView cpuFreqView;      // CPU 环下方：实时 MHz
    private TextView gpuFreqView;      // GPU 环下方：实时 MHz
    private TextView batteryTempView;  // 电池环下方：温度

    // ─── 展开态视图引用 ───
    private TextView overviewView;            // CPU · GPU 概览行
    private final List<TextView> coreRows = new java.util.ArrayList<>(); // 每行两个核心
    private TextView memView;                 // 内存
    private TextView fpsView;                 // FPS
    private TextView powerView;               // 功耗
    private TextView tempView;                // 温度
    private TextView loadView;                // 负载

    public LoadOverlayWindow(Context context) {
        super(context, MonitorType.LOAD);
        // 初始形态取上次记忆（默认折叠小窗）
        this.expanded = OverlayConfig.isExpanded(context, MonitorType.LOAD);
    }

    // ─── 生命周期 ───

    @Override
    protected void onShown() {
        // 只有展开态需要 FPS；折叠态不统计，省去无谓的每帧回调
        if (expanded) {
            fpsMonitor.start();
        }
    }

    @Override
    protected void onHidden() {
        fpsMonitor.stop();
    }

    @Override
    protected void onStateChanged(boolean nowExpanded) {
        // 折叠/展开切换时按需启停 FPS 统计
        if (nowExpanded) {
            fpsMonitor.start();
        } else {
            fpsMonitor.stop();
        }
    }

    // ─── 数据采集（后台线程） ───

    @Override
    protected void collectData() {
        if (expanded) {
            collectExpanded();
        } else {
            collectCollapsed();
        }
    }

    /** 折叠态采集：CPU/GPU 使用率、电池电量、CPU/GPU 频率、电池温度。 */
    private void collectCollapsed() {
        cpuPercent = loadMonitor.readCpuUsagePercent();
        gpuPercent = GpuMonitor.readGpuUsagePercent();
        batteryPercent = BatteryMonitor.readBatteryPercent();
        cpuMhz = CpuCoreMonitor.readMaxCoreFreqMhz();
        gpuMhz = GpuMonitor.readGpuFreqMhz();
        batteryTempC = TemperatureMonitor.readBatteryTempC(context);
    }

    /** 展开态采集：全部详细数据（含各核心、内存、功耗、温度、负载）。 */
    private void collectExpanded() {
        cpuPercent = loadMonitor.readCpuUsagePercent();
        gpuPercent = GpuMonitor.readGpuUsagePercent();
        coreStates = coreMonitor.readCoreStates();
        memTotalKb = MemoryMonitor.readTotalKb();
        memAvailKb = MemoryMonitor.readAvailableKb();
        powerMw = PowerMonitor.readPowerMw();
        batteryTempC = TemperatureMonitor.readBatteryTempC(context);
        cpuTempC = TemperatureMonitor.readCpuTempC();
        loadAvg = LoadMonitor.readLoadAvg();
    }

    // ─── UI 刷新（主线程） ───

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

    /** 折叠态刷新：3 个圆环 + 环下 MHz/温度。 */
    private void refreshCollapsed() {
        cpuRing.setProgress(cpuPercent);
        gpuRing.setProgress(gpuPercent);
        batteryRing.setProgress(batteryPercent);
        batteryRing.setCenterText(batteryPercent < 0 ? "--" : batteryPercent + "%");
        cpuFreqView.setText(cpuMhz > 0 ? cpuMhz + "MHz" : "--");
        gpuFreqView.setText(gpuMhz > 0 ? gpuMhz + "MHz" : "--");
        batteryTempView.setText(TemperatureMonitor.formatTemp(batteryTempC));
    }

    /** 展开态刷新：全部详细数据。 */
    private void refreshExpanded() {
        overviewView.setText(String.format(Locale.US, "CPU %d%% · GPU %s",
                cpuPercent, gpuPercent < 0 ? "--" : gpuPercent + "%"));

        // 各核心：每行两个核心
        int rowCount = (coreStates.length + 1) / 2;
        for (int i = 0; i < coreRows.size(); i++) {
            TextView row = coreRows.get(i);
            int a = i * 2;
            int b = i * 2 + 1;
            row.setText(formatCore(coreStates, a) + "    " + formatCore(coreStates, b));
        }

        // 内存 / FPS / 功耗
        if (memTotalKb > 0 && memAvailKb >= 0) {
            memView.setText(String.format(Locale.US, "内存 %s / %s · %d%%",
                    MemoryMonitor.formatGb(memTotalKb - memAvailKb),
                    MemoryMonitor.formatGb(memTotalKb),
                    (int) Math.min(100, Math.max(0, (memTotalKb - memAvailKb) * 100 / memTotalKb))));
        } else {
            memView.setText("内存 --");
        }
        float fps = fpsMonitor.getFps(); // Choreographer 数据在主线程产生，这里直接读
        fpsView.setText(fps > 0 ? String.format(Locale.US, "FPS %.0f", fps) : "FPS --");
        powerView.setText(powerMw > 0 ? String.format(Locale.US, "功耗 %.0f mW", powerMw) : "功耗 --");

        // 温度与负载
        tempView.setText("电池 " + TemperatureMonitor.formatTemp(batteryTempC)
                + " · CPU " + TemperatureMonitor.formatTemp(cpuTempC));
        loadView.setText(String.format(Locale.US, "负载 1m %.2f · 5m %.2f · 15m %.2f",
                loadAvg[0], loadAvg[1], loadAvg[2]));
    }

    /** 单个核心文本：C{index} {freq}MHz {percent}%。 */
    private String formatCore(CpuCoreMonitor.CoreState[] cores, int index) {
        if (index >= cores.length) {
            return "";
        }
        CpuCoreMonitor.CoreState c = cores[index];
        return String.format(Locale.US, "C%d %dMHz %d%%", c.index, c.freqMhz, c.percent);
    }

    // ─── 视图构建 ───

    /** 折叠小窗内容：CPU 环 + GPU 环 + 电池电量环，环下分别显示 MHz/MHz/温度。 */
    @Override
    protected View buildCollapsedView() {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        // 三列：CPU / GPU / 电池
        cpuRing = new GaugeRingView(context, OverlayWindowStyle.COLOR_ACCENT_DARK);
        cpuRing.setCenterText("CPU");
        gpuRing = new GaugeRingView(context, 0xFF00B8D4); // GPU 青色环
        gpuRing.setCenterText("GPU");
        batteryRing = new GaugeRingView(context, 0xFFFB8C00); // 电池橙色环
        batteryRing.setCenterText("--");

        cpuFreqView = createRingSubLabel();
        gpuFreqView = createRingSubLabel();
        batteryTempView = createRingSubLabel();

        row.addView(buildRingColumn(cpuRing, cpuFreqView));
        row.addView(buildRingColumn(gpuRing, gpuFreqView));
        row.addView(buildRingColumn(batteryRing, batteryTempView));
        return row;
    }

    /** 圆环列：上方圆环 + 下方小字（MHz / 温度）。 */
    private View buildRingColumn(GaugeRingView ring, TextView subLabel) {
        LinearLayout col = new LinearLayout(context);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        int ringSize = OverlayWindowStyle.dpToPx(context, 34);
        col.addView(ring, new LinearLayout.LayoutParams(ringSize, ringSize));
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = OverlayWindowStyle.dpToPx(context, 2);
        col.addView(subLabel, subLp);
        return col;
    }

    /** 环下方的标签文字（10sp 深灰）。 */
    private TextView createRingSubLabel() {
        TextView tv = new TextView(context);
        tv.setText("--");
        tv.setTextColor(OverlayWindowStyle.COLOR_TEXT_DARK);
        tv.setTextSize(10);
        tv.setIncludeFontPadding(false);
        return tv;
    }

    /** 展开大窗内容：完整详细数据文本（紧凑排版）。 */
    @Override
    protected View buildExpandedView() {
        LinearLayout col = new LinearLayout(context);
        col.setOrientation(LinearLayout.VERTICAL);

        col.addView(OverlayWindowStyle.createDarkTitleView(context, "负载监视器"));

        // 概览行：CPU · GPU
        overviewView = new TextView(context);
        overviewView.setTextColor(OverlayWindowStyle.COLOR_ACCENT_DARK);
        overviewView.setTextSize(14);
        overviewView.setTypeface(Typeface.DEFAULT_BOLD);
        overviewView.setText("--");
        col.addView(overviewView);

        col.addView(newDivider());

        // 各核心行（动态按行数添加，每行两个核心，等宽字体对齐）
        coreRows.clear();
        int coreCount = Runtime.getRuntime().availableProcessors();
        int rowCount = (coreCount + 1) / 2;
        for (int i = 0; i < rowCount; i++) {
            TextView row = OverlayWindowStyle.createDarkBodyView(context);
            row.setText("--");
            row.setTypeface(Typeface.MONOSPACE);
            row.setTextSize(11);
            col.addView(row);
            coreRows.add(row);
        }

        col.addView(newDivider());

        memView = createDetailRow("内存 --");
        col.addView(memView);

        fpsView = createDetailRow("FPS --");
        col.addView(fpsView);

        powerView = createDetailRow("功耗 --");
        col.addView(powerView);

        tempView = createDetailRow("温度 --");
        col.addView(tempView);

        loadView = createDetailRow("负载 --");
        col.addView(loadView);
        return col;
    }

    /** 展开态详情行（11sp 深灰）。 */
    private TextView createDetailRow(String initial) {
        TextView tv = OverlayWindowStyle.createDarkBodyView(context);
        tv.setText(initial);
        tv.setTextSize(11);
        return tv;
    }

    /** 细分隔线。 */
    private View newDivider() {
        View div = new View(context);
        int margin = OverlayWindowStyle.dpToPx(context, 5);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, OverlayWindowStyle.dpToPx(context, 1));
        lp.setMargins(0, margin, 0, margin);
        div.setBackgroundColor(0x1F000000);
        div.setLayoutParams(lp);
        return div;
    }
}
