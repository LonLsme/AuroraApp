package com.aurora.app.ui.overlay;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.aurora.app.core.monitor.BatteryMonitor;
import com.aurora.app.core.monitor.CpuCoreMonitor;
import com.aurora.app.core.monitor.FpsSampler;
import com.aurora.app.core.monitor.GpuMonitor;
import com.aurora.app.core.monitor.LoadMonitor;
import com.aurora.app.core.monitor.MemoryMonitor;
import com.aurora.app.core.monitor.PowerMonitor;
import com.aurora.app.core.monitor.TemperatureMonitor;

import java.util.Locale;

/**
 * 系统监视器悬浮窗（原「负载监视器」）——复刻 HoshinoScene 的
 * {@code fw_monitor2.xml} 布局 + {@code FloatMonitorRender} 的数据组织方式。
 *
 * <p>标准态（折叠）：四个圆环横排，环内标 CPU / GPU / 电量% / MEM，环下小字为
 * 实时频率、电池温度、内存占用率 —— 与星野Scene「新版系统监视器」一致：<br>
 * {@code [CPU环 1.8GHz] [GPU环 600MHz] [BAT环 32℃] [MEM环 62%]}
 *
 * <p>展开态：在四环之下追加
 * <ul>
 *   <li>各核心占用条形图（{@link CoreBarChartView}，柱数 = 核心数）+ 每核频率等宽文本；</li>
 *   <li>系统负载 1/5/15 分钟、实时 FPS、整机功耗、电池与 CPU 温度。</li>
 * </ul>
 *
 * <p>配色规则来自 {@link OverlayWindowStyle}：占用 &gt;85 深橙、&gt;65 橙、其余 Aurora 绿；
 * 电池环另按「温度 ≥48 或电量 &lt;11」等档位上色（复刻 {@code FloatMonitorBatteryView}）。
 *
 * <p>采集节奏沿用原实现的快慢双源：CPU/GPU 占用率与电池电量每秒采；逐核频率/占用、
 * 温度、功耗每 3 秒采（热区遍历与逐核读取开销大，跟得上一秒一次会拖慢刷新链路）。
 */
public final class SystemOverlayWindow extends OverlayWindow {

    /** 慢源刷新间隔（温度、功耗、逐核频率/占用）。 */
    private static final long SLOW_INTERVAL_MS = 3000L;

    private final LoadMonitor loadMonitor = new LoadMonitor();
    private final CpuCoreMonitor coreMonitor = new CpuCoreMonitor();
    /** 系统级帧率采样（sysfs measured_fps 优先，回退 SurfaceFlinger 帧计数差）——比自身进程的
     *  Choreographer 计数准确：后者测的是本进程 VSync 回调频率，游戏掉帧时它照样满帧。 */
    private final FpsSampler fpsSampler = new FpsSampler();

    // ─── 采集结果（后台线程写，主线程读） ───
    private int cpuPercent = 0;
    private int gpuPercent = -1;
    private int batteryPercent = -1;
    private int memPercent = -1;
    private int cpuMhz = 0;
    private int gpuMhz = 0;
    private float batteryTempC = -1f;
    private float cpuTempC = -1f;
    /** 整机功耗（W）：充电为正、放电为负；NaN = 不可用。 */
    private double powerW = Double.NaN;
    private float[] loadAvg = new float[]{0f, 0f, 0f};
    private CpuCoreMonitor.CoreState[] coreStates = new CpuCoreMonitor.CoreState[0];
    private float fps = -1f;

    private long lastSlowCollectMs = 0L;

    // ─── 环与标签（两种形态共用同一套实例，切换形态时重建） ───
    private RingChartView cpuRing;
    private RingChartView gpuRing;
    private RingChartView batteryRing;
    private RingChartView memRing;
    private TextView cpuFreqLabel;
    private TextView gpuFreqLabel;
    private TextView batteryTempLabel;
    private TextView memLabel;

    // ─── 展开态附加视图 ───
    private CoreBarChartView coreBars;
    private TextView coreFreqRow1;
    private TextView coreFreqRow2;
    private TextView loadView;
    private TextView fpsView;
    private TextView powerView;
    private TextView tempView;

    public SystemOverlayWindow(Context context) {
        super(context, MonitorType.SYSTEM);
    }

    // ─── 生命周期 ───

    @Override
    protected void onShown() {
        if (expanded) {
            fpsSampler.resetBaseline();
        }
    }

    @Override
    protected void onHidden() {
        fpsSampler.resetBaseline();
    }

    @Override
    protected void onStateChanged(boolean nowExpanded) {
        // 展开态才显示 FPS：切换时重置差值法基线，避免用陈旧基线算出异常值
        fpsSampler.resetBaseline();
    }

    // ─── 数据采集（后台线程） ───

    @Override
    protected void collectData() {
        cpuPercent = loadMonitor.readCpuUsagePercent();
        gpuPercent = GpuMonitor.readGpuUsagePercent();
        batteryPercent = BatteryMonitor.readBatteryPercent(context);
        memPercent = MemoryMonitor.usedPercent();

        long now = android.os.SystemClock.elapsedRealtime();
        if (lastSlowCollectMs == 0L || now - lastSlowCollectMs >= SLOW_INTERVAL_MS) {
            lastSlowCollectMs = now;
            cpuMhz = CpuCoreMonitor.readMaxCoreFreqMhz();
            gpuMhz = GpuMonitor.readGpuFreqMhz();
            batteryTempC = TemperatureMonitor.readBatteryTempC(context);
            loadAvg = LoadMonitor.readLoadAvg();
            if (expanded) {
                cpuTempC = TemperatureMonitor.readCpuTempC();
                powerW = PowerMonitor.readPowerW(context);
                coreStates = coreMonitor.readCoreStates();
                fps = fpsSampler.sample();
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
        cpuRing.setPercent(cpuPercent);
        gpuRing.setPercent(gpuPercent);
        batteryRing.setPercent(batteryPercent,
                OverlayWindowStyle.batteryColorOf(batteryTempC, batteryPercent));
        batteryRing.setCenterText(batteryPercent < 0 ? "--" : batteryPercent + "%");
        memRing.setPercent(memPercent);

        cpuFreqLabel.setText(formatMhz(cpuMhz));
        gpuFreqLabel.setText(formatMhz(gpuMhz));
        batteryTempLabel.setText(TemperatureMonitor.formatTemp(batteryTempC));
        memLabel.setText(memPercent < 0 ? "--" : memPercent + "%");

        if (!expanded) {
            return;
        }

        // 各核心占用条形图 + 每核频率（等宽文本，两行）
        if (coreStates.length > 0) {
            Integer[] percents = new Integer[coreStates.length];
            for (int i = 0; i < coreStates.length; i++) {
                percents[i] = coreStates[i].percent;
            }
            coreBars.setData(percents);
            coreFreqRow1.setText(formatCoreFreqRow(0));
            coreFreqRow2.setText(formatCoreFreqRow(coreStates.length / 2));
        } else {
            coreFreqRow1.setText("--");
            coreFreqRow2.setText("");
        }

        float currentFps = fps;
        loadView.setText(String.format(Locale.US, "负载 %.2f / %.2f / %.2f",
                loadAvg[0], loadAvg[1], loadAvg[2]));
        fpsView.setText(currentFps > 0
                ? String.format(Locale.US, "FPS %.0f", currentFps) : "FPS --");
        powerView.setText("功耗 " + PowerMonitor.formatPower(powerW));
        tempView.setText("温度 电池 " + TemperatureMonitor.formatTemp(batteryTempC)
                + " · CPU " + TemperatureMonitor.formatTemp(cpuTempC));
    }

    /** 每核频率文本行：半数为一行，形如 {@code C0 1804 C1 1804 C2 2150}。 */
    private String formatCoreFreqRow(int fromIndex) {
        if (coreStates.length == 0) {
            return "";
        }
        int half = (coreStates.length + 1) / 2;
        int to = Math.min(coreStates.length, fromIndex + half);
        StringBuilder sb = new StringBuilder();
        for (int i = fromIndex; i < to; i++) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append('C').append(coreStates[i].index)
                    .append(' ').append(coreStates[i].freqMhz);
        }
        return sb.toString();
    }

    private static String formatMhz(int mhz) {
        return mhz > 0 ? mhz + "M" : "--";
    }

    // ─── 视图构建 ───

    /** 标准态：四环横排（复刻 fw_monitor2 的 fw_chart_list）。 */
    @Override
    protected View buildCollapsedView() {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int gap = OverlayWindowStyle.dpToPx(context, 3);

        cpuRing = newRing("CPU");
        gpuRing = newRing("GPU");
        batteryRing = newRing("--");
        memRing = newRing("MEM");

        cpuFreqLabel = newSubLabel();
        gpuFreqLabel = newSubLabel();
        batteryTempLabel = newSubLabel();
        memLabel = newSubLabel();

        addRingColumn(row, cpuRing, cpuFreqLabel, gap);
        addRingColumn(row, gpuRing, gpuFreqLabel, gap);
        addRingColumn(row, batteryRing, batteryTempLabel, gap);
        addRingColumn(row, memRing, memLabel, 0);
        return row;
    }

    /** 展开态：四环 + 各核条形图 + 负载/FPS/功耗/温度。 */
    @Override
    protected View buildExpandedView() {
        LinearLayout col = new LinearLayout(context);
        col.setOrientation(LinearLayout.VERTICAL);

        col.addView(buildCollapsedView());

        col.addView(divider());

        // 各核心占用条形图（复刻 fw_perf_event_cores 的意图：一眼看出哪个核心在忙）
        coreBars = new CoreBarChartView(context);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, OverlayWindowStyle.dpToPx(context, 16));
        barLp.topMargin = OverlayWindowStyle.dpToPx(context, 2);
        col.addView(coreBars, barLp);

        int coreCount = Math.max(1, Runtime.getRuntime().availableProcessors());
        coreBars.setBarCount(coreCount);

        coreFreqRow1 = monoRow("--");
        coreFreqRow2 = monoRow("");
        col.addView(coreFreqRow1);
        col.addView(coreFreqRow2);

        col.addView(divider());

        loadView = monoRow("负载 --");
        fpsView = monoRow("FPS --");
        powerView = monoRow("功耗 --");
        tempView = monoRow("温度 --");
        col.addView(loadView);
        col.addView(fpsView);
        col.addView(powerView);
        col.addView(tempView);
        return col;
    }

    // ─── 视图工厂 ───

    private RingChartView newRing(String centerText) {
        RingChartView ring = new RingChartView(context);
        ring.setCenterText(centerText);
        return ring;
    }

    /** 环下小字：复用星野Scene 的 5.6dp 尺寸观感，这里取 8sp（可读性更好）。 */
    private TextView newSubLabel() {
        TextView tv = new TextView(context);
        tv.setTextColor(OverlayWindowStyle.MONITOR_TEXT_DIM);
        tv.setTextSize(8);
        tv.setIncludeFontPadding(false);
        tv.setText("--");
        return tv;
    }

    /** 环列：上方圆环 + 下方小字，列间留 gap。 */
    private void addRingColumn(LinearLayout row, RingChartView ring, TextView subLabel, int gap) {
        LinearLayout col = new LinearLayout(context);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        colLp.rightMargin = gap;

        int ringSize = OverlayWindowStyle.dpToPx(context, 38);
        col.addView(ring, new LinearLayout.LayoutParams(ringSize, ringSize));

        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = OverlayWindowStyle.dpToPx(context, 1);
        col.addView(subLabel, subLp);

        row.addView(col, colLp);
    }

    /** 等宽读数行（10sp 白色）。 */
    private TextView monoRow(String initial) {
        TextView tv = new TextView(context);
        tv.setText(initial);
        tv.setTextColor(OverlayWindowStyle.MONITOR_TEXT);
        tv.setTextSize(10);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setIncludeFontPadding(false);
        return tv;
    }

    /** 1px 分隔线（白 14%），上下各留 4dp。 */
    private View divider() {
        LinearLayout wrapper = new LinearLayout(context);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        int pad = OverlayWindowStyle.dpToPx(context, 4);
        wrapper.setPadding(0, pad, 0, pad);
        wrapper.addView(OverlayWindowStyle.createMonitorDivider(context));
        return wrapper;
    }
}
