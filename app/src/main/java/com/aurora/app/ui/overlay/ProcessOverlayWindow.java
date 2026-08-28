package com.aurora.app.ui.overlay;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.aurora.app.core.monitor.ProcessMonitor;

import java.util.List;

/**
 * 进程监视器悬浮窗：
 * 列出当前 CPU 占用最高的前 3 个进程（名称 + 占用百分比）。
 * 刷新周期 2s（遍历 /proc 有一定开销，不宜过快）。
 */
public final class ProcessOverlayWindow extends OverlayWindow {

    /** 展示的进程条数。 */
    private static final int TOP_COUNT = 3;

    /** 数据源：内部维护两次采样的差值基线。 */
    private final ProcessMonitor processMonitor = new ProcessMonitor();

    private final TextView[] rows = new TextView[TOP_COUNT]; // 每行一个进程文本

    public ProcessOverlayWindow(Context context) {
        super(context, MonitorType.PROCESS);
    }

    @Override
    protected View buildContentView() {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = OverlayWindowStyle.dpToPx(context, OverlayWindowStyle.PADDING_DP);
        root.setPadding(pad, pad, pad, pad);
        root.setBackground(OverlayWindowStyle.createWindowBackground(context));

        root.addView(OverlayWindowStyle.createTitleView(context, "进程"));

        // 固定 3 行文本视图，刷新时只改文本，不重建视图，减少开销
        for (int i = 0; i < TOP_COUNT; i++) {
            TextView row = OverlayWindowStyle.createBodyView(context);
            row.setText("--");
            root.addView(row);
            rows[i] = row;
        }
        return root;
    }

    @Override
    protected void refreshContent() {
        List<String> top = processMonitor.readTopProcesses(TOP_COUNT);
        for (int i = 0; i < TOP_COUNT; i++) {
            rows[i].setText(i < top.size() ? top.get(i) : "--");
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
