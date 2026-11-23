package com.aurora.app.ui.overlay;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.aurora.app.core.cpu.CpuController;
import com.aurora.app.core.monitor.BatteryMonitor;
import com.aurora.app.core.monitor.CpuCoreMonitor;
import com.aurora.app.core.monitor.FpsSampler;
import com.aurora.app.core.monitor.GpuMonitor;
import com.aurora.app.core.monitor.LoadMonitor;
import com.aurora.app.core.monitor.MemoryMonitor;
import com.aurora.app.core.monitor.PowerMonitor;
import com.aurora.app.core.monitor.TemperatureMonitor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 系统监视器悬浮窗（原「负载监视器」）。
 *
 * <p><b>折叠态</b>：Scene 三环横排 —— CPU / GPU / 电量%，环下小字为实时频率、
 * 电池温度。不显示 MEM 环；内存占用由展开态的 {@code #RAM} 行展示。
 *
 * <p><b>展开态</b>：复刻 Scene 纵向布局，左右两列 ——
 * <ul>
 *   <li>左列：CPU / GPU / 电量 三个圆环纵向排列，环心分别是 CPU、GPU、电量百分比，
 *       环下依次显示 CPU 实时频率、GPU 实时频率、电池温度；</li>
 *   <li>右列：一块等宽小字文本，逐行输出
 *       {@code #RAM}（占用率 + Swap/ZRAM 占用率）、{@code DDR}（内存频率，读不到则省略）、
 *       {@code #CPU}（CPU 温度）、各 CPU 簇标题行（{@code #0~1} + 簇内最高频率）、
 *       每核子行（负载% + 当前频率）、{@code #FPS}、{@code #PWR}。</li>
 * </ul>
 * 右列是单个 TextView，整块文本先比后写，一次 setText 完成刷新；不逐行创建控件，
 * 也不触发父容器重排。三环各自是独立 View，只重绘自身。
 *
 * <p><b>数据采集</b>：{@link #collectData()} 沿用原实现（CPU/GPU 负载、逐核频率与占用、
 * 内存、电池、温度、功耗、FPS）；本次为复刻布局新增 Swap 占用率与 DDR 频率两项慢源读取，
 * 都只在展开态执行。簇拓扑（{@link CpuController#readClusters()}）只读一次并缓存。
 */
public final class SystemOverlayWindow extends OverlayWindow {

    /** 慢源刷新间隔（温度、功耗、逐核频率/占用、Swap、DDR）。 */
    private static final long SLOW_INTERVAL_MS = 3000L;

    private final LoadMonitor loadMonitor = new LoadMonitor();
    private final CpuCoreMonitor coreMonitor = new CpuCoreMonitor();
    /** 系统级帧率采样（sysfs measured_fps 优先，回退 SurfaceFlinger 帧计数差）。 */
    private final FpsSampler fpsSampler = new FpsSampler();

    // ─── 采集结果（后台线程写，主线程读）───
    private int cpuPercent = 0;
    private int gpuPercent = -1;
    private int batteryPercent = -1;
    private int memPercent = -1;
    /** Swap/ZRAM 占用率（0-100）；无 swap 或读不到为 -1。 */
    private int swapPercent = -1;
    /** DDR 频率（MHz）；读不到为 -1，此时右列省略 DDR 行。 */
    private int ddrMhz = -1;
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

    // ─── 折叠态 / 展开态左列共用的三环 ───
    private RingChartView cpuRing;
    private RingChartView gpuRing;
    private RingChartView batteryRing;
    private TextView cpuFreqLabel;
    private TextView gpuFreqLabel;
    private TextView batteryTempLabel;

    // ─── 展开态右列（单块等宽文本）───
    private TextView infoView;
    /** 整块文本先比后写，避免每秒无谓 setText。 */
    private String lastInfoText = "";

    /** 簇 → 核心编号映射，只读一次缓存。 */
    private int[][] clusterGroups;

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

    /**
     * 紧凑毛玻璃底（深灰半透明 + 高光描边 + 圆角），折叠/展开两态共用。
     * 覆写基类默认背景，只作用于本窗口。
     */
    @Override
    protected android.graphics.drawable.Drawable createWindowBackground() {
        return OverlayWindowStyle.createGlassBackground(context);
    }

    /** 内边距压到 3dp（基类默认 5dp），窗口整体再收一圈。 */
    @Override
    protected int contentPaddingDp() {
        return 3;
    }

    // ─── 数据采集（后台线程）───

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
                // 仅展开态需要的两项：Swap 占用率、DDR 频率（都带失败缓存，不重复访问节点）
                swapPercent = MemoryMonitor.swapUsedPercent();
                ddrMhz = MemoryMonitor.readDdrFreqMhz();
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
        if (expanded) {
            refreshExpanded();
            return;
        }
        refreshCollapsed();
    }

    /** 折叠态三环刷新（不含 MEM）。 */
    private void refreshCollapsed() {
        cpuRing.setPercent(cpuPercent);
        gpuRing.setPercent(gpuPercent);
        batteryRing.setPercent(batteryPercent,
                OverlayWindowStyle.batteryColorOf(batteryTempC, batteryPercent));
        batteryRing.setCenterText(batteryPercent < 0 ? "--" : batteryPercent + "%");

        cpuFreqLabel.setText(formatMhz(cpuMhz));
        gpuFreqLabel.setText(formatMhz(gpuMhz));
        batteryTempLabel.setText(TemperatureMonitor.formatTemp(batteryTempC));
    }

    /** 展开态：左列三环 + 环下标签；右列整块文本。 */
    private void refreshExpanded() {
        cpuRing.setPercent(cpuPercent);
        gpuRing.setPercent(gpuPercent);
        batteryRing.setPercent(batteryPercent,
                OverlayWindowStyle.batteryColorOf(batteryTempC, batteryPercent));
        batteryRing.setCenterText(batteryPercent < 0 ? "--" : batteryPercent + "%");

        setTextIfChanged(cpuFreqLabel, formatMhzLong(cpuMhz));
        setTextIfChanged(gpuFreqLabel, formatMhzLong(gpuMhz));
        setTextIfChanged(batteryTempLabel, TemperatureMonitor.formatTemp(batteryTempC));

        String text = buildInfoBlock();
        if (!text.equals(lastInfoText)) {
            lastInfoText = text;
            infoView.setText(text);
        }
    }

    /**
     * 右列整块文本。列宽固定为「标签 4 / 值 6 / 第二值 5」，等宽字体下逐列对齐。
     * 行结构：
     * <pre>
     * #RAM   61%  88%      ← 内存占用率 / Swap(ZRAM) 占用率
     *  DDR   1094          ← DDR 频率，读不到则整行省略
     * #CPU  44.4°C         ← CPU 温度
     * #0~1  672MHz         ← 簇标题：核心区间 + 簇内最高频率
     *    31%   363M         ← 每核：负载% + 当前频率
     * ...
     * #FPS    1.5
     * #PWR  -3.23W
     * </pre>
     */
    private String buildInfoBlock() {
        StringBuilder sb = new StringBuilder(384);

        sb.append(String.format(Locale.US, "%-4s %6s %5s%n",
                "#RAM", pct(memPercent), pct(swapPercent)));
        if (ddrMhz > 0) {
            sb.append(String.format(Locale.US, "%-4s %6s%n", " DDR", ddrMhz));
        }
        sb.append(String.format(Locale.US, "%-4s %6s%n",
                "#CPU", TemperatureMonitor.formatTemp(cpuTempC)));

        int[][] groups = clusterGroups != null ? clusterGroups : new int[0][];
        for (int[] cpus : groups) {
            int maxFreq = 0;
            for (int core : cpus) {
                if (core >= 0 && core < coreStates.length) {
                    maxFreq = Math.max(maxFreq, coreStates[core].freqMhz);
                }
            }
            sb.append(String.format(Locale.US, "%-4s %6s%n",
                    clusterLabel(cpus), maxFreq > 0 ? maxFreq + "MHz" : "--"));

            for (int core : cpus) {
                if (core < 0 || core >= coreStates.length) {
                    continue;
                }
                CpuCoreMonitor.CoreState state = coreStates[core];
                sb.append(String.format(Locale.US, "%-4s %6s %5s%n",
                        "",
                        state.percent + "%",
                        state.freqMhz > 0 ? state.freqMhz + "M" : "--"));
            }
        }

        sb.append(String.format(Locale.US, "%-4s %6s%n",
                "#FPS", fps > 0 ? String.format(Locale.US, "%.1f", fps) : "--"));
        // 末行不加换行，避免 TextView 多算一行高度
        sb.append(String.format(Locale.US, "%-4s %6s",
                "#PWR", Double.isNaN(powerW) ? "--" : String.format(Locale.US, "%+.2fW", powerW)));
        return sb.toString();
    }

    /** 百分比文本；负值视为未知。 */
    private static String pct(int value) {
        return value < 0 ? "--" : value + "%";
    }

    /** 折叠态环下小字：{@code 672M} / {@code --}。 */
    private static String formatMhz(int mhz) {
        return mhz > 0 ? mhz + "M" : "--";
    }

    /** 展开态环下小字：{@code 672MHz} / {@code --}（复刻截图写法）。 */
    private static String formatMhzLong(int mhz) {
        return mhz > 0 ? mhz + "MHz" : "--";
    }

    private static void setTextIfChanged(TextView view, String text) {
        if (!text.equals(view.getText().toString())) {
            view.setText(text);
        }
    }

    // ─── 视图构建 ───

    /** 折叠态：CPU / GPU / 电量 三环横排，无 MEM 环。 */
    @Override
    protected View buildCollapsedView() {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int gap = OverlayWindowStyle.dpToPx(context, 3);

        cpuRing = newRing("CPU");
        gpuRing = newRing("GPU");
        batteryRing = newRing("--");

        cpuFreqLabel = newSubLabel();
        gpuFreqLabel = newSubLabel();
        batteryTempLabel = newSubLabel();

        addRingColumn(row, cpuRing, cpuFreqLabel, gap);
        addRingColumn(row, gpuRing, gpuFreqLabel, gap);
        addRingColumn(row, batteryRing, batteryTempLabel, 0);
        return row;
    }

    /**
     * 展开态：左列三环纵向 + 右列整块等宽文本。
     * 无标题、无分隔线、无逐核条形图。
     */
    @Override
    protected View buildExpandedView() {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.TOP);

        if (clusterGroups == null) {
            clusterGroups = resolveClusterGroups();
        }

        // ── 左列：三环纵向，环下为频率 / 温度 ──
        LinearLayout ringCol = new LinearLayout(context);
        ringCol.setOrientation(LinearLayout.VERTICAL);
        ringCol.setGravity(Gravity.CENTER_HORIZONTAL);

        cpuRing = newRing("CPU");
        gpuRing = newRing("GPU");
        batteryRing = newRing("--");

        cpuFreqLabel = newSubLabel();
        gpuFreqLabel = newSubLabel();
        batteryTempLabel = newSubLabel();

        int ringSize = OverlayWindowStyle.dpToPx(context, 40);
        addRingBlock(ringCol, cpuRing, cpuFreqLabel, ringSize);
        addRingBlock(ringCol, gpuRing, gpuFreqLabel, ringSize);
        addRingBlock(ringCol, batteryRing, batteryTempLabel, ringSize);

        LinearLayout.LayoutParams ringColLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ringColLp.rightMargin = OverlayWindowStyle.dpToPx(context, 4);
        root.addView(ringCol, ringColLp);

        // ── 右列：单块等宽文本 ──
        infoView = new TextView(context);
        infoView.setTextColor(OverlayWindowStyle.MONITOR_TEXT);
        infoView.setTextSize(8);
        infoView.setTypeface(Typeface.MONOSPACE);
        infoView.setIncludeFontPadding(false);
        infoView.setLineSpacing(0f, 1f);
        infoView.setText("--");
        root.addView(infoView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        lastInfoText = "";
        return root;
    }

    /**
     * 读取一次簇拓扑（{@code related_cpus} 相同的核心归为一簇），结果缓存。
     * 这是拓扑信息、不是性能采样，不进 {@link #collectData()} 的采集链路。
     * 簇数异常过多（读不到相关节点而退化为单核一簇）时，均分成 3 段，
     * 避免右列簇行过多把窗口撑高。
     */
    private int[][] resolveClusterGroups() {
        List<int[]> groups = new ArrayList<>();
        try {
            List<CpuController.Cluster> clusters = CpuController.readClusters();
            for (CpuController.Cluster cluster : clusters) {
                if (cluster.cpus != null && cluster.cpus.length > 0) {
                    groups.add(cluster.cpus);
                }
            }
        } catch (Exception ignored) {
            groups.clear();
        }
        if (groups.isEmpty() || groups.size() > 4) {
            int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
            int parts = Math.min(3, cores);
            groups.clear();
            for (int p = 0; p < parts; p++) {
                int from = p * cores / parts;
                int to = (p + 1) * cores / parts;
                int[] chunk = new int[to - from];
                for (int i = from; i < to; i++) {
                    chunk[i - from] = i;
                }
                groups.add(chunk);
            }
        }
        return groups.toArray(new int[0][]);
    }

    /** 簇的核心区间标签：单核 {@code #2}，多核 {@code #0~1}（复刻截图用 ~ 分隔）。 */
    private static String clusterLabel(int[] cpus) {
        return cpus.length == 1
                ? "#" + cpus[0]
                : "#" + cpus[0] + "~" + cpus[cpus.length - 1];
    }

    // ─── 视图工厂 ───

    private RingChartView newRing(String centerText) {
        RingChartView ring = new RingChartView(context);
        ring.setCenterText(centerText);
        return ring;
    }

    /** 环下小字：等宽白 8sp。 */
    private TextView newSubLabel() {
        TextView tv = new TextView(context);
        tv.setTextColor(OverlayWindowStyle.MONITOR_TEXT_DIM);
        tv.setTextSize(8);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setIncludeFontPadding(false);
        tv.setText("--");
        return tv;
    }

    /** 折叠态环列：上方圆环 + 下方小字，列间留 gap。 */
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

    /** 展开态左列环块：圆环 + 紧贴下方的小字，块间留 3dp。 */
    private void addRingBlock(LinearLayout col, RingChartView ring, TextView subLabel, int ringSize) {
        col.addView(ring, new LinearLayout.LayoutParams(ringSize, ringSize));

        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = OverlayWindowStyle.dpToPx(context, 1);
        subLp.bottomMargin = OverlayWindowStyle.dpToPx(context, 3);
        subLp.gravity = Gravity.CENTER_HORIZONTAL;
        col.addView(subLabel, subLp);
    }
}
