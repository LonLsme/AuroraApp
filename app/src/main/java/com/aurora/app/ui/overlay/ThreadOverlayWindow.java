package com.aurora.app.ui.overlay;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.aurora.app.core.monitor.ThreadMonitor;

import java.util.List;
import java.util.Locale;

/**
 * 线程监视器悬浮窗（折叠/展开双状态，风格与负载监视器统一）：
 * <ul>
 *   <li>折叠小窗：系统线程总数（大数字）；</li>
 *   <li>展开大窗：线程总数 + 线程数最多的 TOP 5 进程；</li>
 *   <li>交互/状态记忆/浅色穿透背景由基类统一提供。</li>
 * </ul>
 * 数据采集（遍历 /proc 全部 task 目录）在后台线程进行。
 */
public final class ThreadOverlayWindow extends OverlayWindow {

    /** 展开态展示的进程条数。 */
    private static final int TOP_COUNT = 5;

    /** 最近一次采集的线程总数与 TOP 进程（后台线程写，主线程读）。 */
    private int threadCount = -1;
    private List<String> topThreadProcesses = java.util.Collections.emptyList();

    // ─── 折叠态视图引用 ───
    private TextView valueView; // 线程总数大数字

    // ─── 展开态视图引用 ───
    private TextView totalRow;  // 展开态总数行
    private final TextView[] rows = new TextView[TOP_COUNT];

    public ThreadOverlayWindow(Context context) {
        super(context, MonitorType.THREAD);
    }

    @Override
    protected View buildCollapsedView() {
        LinearLayout col = new LinearLayout(context);
        col.setOrientation(LinearLayout.VERTICAL);
        valueView = new TextView(context);
        valueView.setTextColor(OverlayWindowStyle.MONITOR_TEXT);
        valueView.setTextSize(20);
        valueView.setTypeface(Typeface.DEFAULT_BOLD);
        valueView.setText("--");
        col.addView(valueView);
        col.addView(OverlayWindowStyle.createMonitorTitleView(context, "线程"));
        return col;
    }

    @Override
    protected View buildExpandedView() {
        LinearLayout col = new LinearLayout(context);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(OverlayWindowStyle.createMonitorTitleView(context, "线程"));

        totalRow = OverlayWindowStyle.createMonitorBodyView(context);
        totalRow.setText("--");
        totalRow.setTextSize(14);
        totalRow.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(totalRow);

        col.addView(OverlayWindowStyle.createMonitorTitleView(context, "线程数 TOP"));
        for (int i = 0; i < TOP_COUNT; i++) {
            TextView row = OverlayWindowStyle.createMonitorBodyView(context);
            row.setText("--");
            row.setTypeface(Typeface.MONOSPACE);
            row.setTextSize(11);
            col.addView(row);
            rows[i] = row;
        }
        return col;
    }

    @Override
    protected void collectData() {
        // 后台线程：遍历 /proc 全部 task 目录开销大，不阻塞主线程
        threadCount = ThreadMonitor.readSystemThreadCount();
        topThreadProcesses = ThreadMonitor.readTopThreadProcesses(TOP_COUNT);
    }

    @Override
    protected void refreshContent() {
        String totalText = threadCount >= 0
                ? String.format(Locale.US, "%,d", threadCount) : "--";
        if (expanded) {
            totalRow.setText("系统线程总数 " + totalText);
            for (int i = 0; i < TOP_COUNT; i++) {
                rows[i].setText(i < topThreadProcesses.size() ? topThreadProcesses.get(i) : "--");
            }
        } else {
            valueView.setText(totalText);
        }
    }

    @Override
    protected long getRefreshIntervalMs() {
        return 2000L;
    }
}
