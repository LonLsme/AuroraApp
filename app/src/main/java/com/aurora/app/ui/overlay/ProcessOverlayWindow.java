package com.aurora.app.ui.overlay;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.aurora.app.core.monitor.ProcessMonitor;

import java.util.List;
import java.util.Locale;

/**
 * 进程监视器悬浮窗（折叠/展开双状态，风格与负载监视器统一）：
 * <ul>
 *   <li>折叠小窗：CPU 占用最高的进程（名称 + 百分比）；</li>
 *   <li>展开大窗：CPU 占用 TOP 5 进程列表；</li>
 *   <li>交互/状态记忆/浅色穿透背景由基类统一提供。</li>
 * </ul>
 * 数据采集（遍历 /proc 开销大）在后台线程进行。
 */
public final class ProcessOverlayWindow extends OverlayWindow {

    /** 展开态展示的进程条数。 */
    private static final int TOP_COUNT = 5;

    /** 数据源：内部维护两次采样的差值基线（仅后台采集线程访问）。 */
    private final ProcessMonitor processMonitor = new ProcessMonitor();

    /** 最近一次采集的 top 进程列表（后台线程写，主线程读）。 */
    private List<String> topProcesses = java.util.Collections.emptyList();

    // ─── 折叠态视图引用 ───
    private TextView foldRow; // 折叠态：最高 CPU 进程一行

    // ─── 展开态视图引用 ───
    private final TextView[] rows = new TextView[TOP_COUNT]; // 每行一个进程

    public ProcessOverlayWindow(Context context) {
        super(context, MonitorType.PROCESS);
    }

    @Override
    protected View buildCollapsedView() {
        LinearLayout col = new LinearLayout(context);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(OverlayWindowStyle.createDarkTitleView(context, "进程"));
        foldRow = OverlayWindowStyle.createDarkBodyView(context);
        foldRow.setText("--");
        foldRow.setTypeface(Typeface.MONOSPACE);
        col.addView(foldRow);
        return col;
    }

    @Override
    protected View buildExpandedView() {
        LinearLayout col = new LinearLayout(context);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(OverlayWindowStyle.createDarkTitleView(context, "进程 · CPU TOP"));

        // 固定 TOP_COUNT 行文本，刷新时只改文本
        for (int i = 0; i < TOP_COUNT; i++) {
            TextView row = OverlayWindowStyle.createDarkBodyView(context);
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
        // 后台线程：全量遍历 /proc 开销大，不阻塞主线程
        topProcesses = processMonitor.readTopProcesses(TOP_COUNT);
    }

    @Override
    protected void refreshContent() {
        if (expanded) {
            for (int i = 0; i < TOP_COUNT; i++) {
                rows[i].setText(i < topProcesses.size() ? topProcesses.get(i) : "--");
            }
        } else {
            foldRow.setText(topProcesses.isEmpty() ? "--" : topProcesses.get(0));
        }
    }

    @Override
    protected long getRefreshIntervalMs() {
        return 2000L;
    }

    @Override
    protected void onHidden() {
        // 窗口销毁时清空采样基线，避免 pid 引用累积
        processMonitor.reset();
    }
}
