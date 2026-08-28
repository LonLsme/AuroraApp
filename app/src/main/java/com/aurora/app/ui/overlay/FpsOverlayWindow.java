package com.aurora.app.ui.overlay;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.aurora.app.core.monitor.FpsMonitor;

import java.util.Locale;

/**
 * 帧率记录器悬浮窗：
 * 基于 Choreographer 统计实时 FPS，并显示单帧平均耗时（ms）。
 * 刷新周期 500ms（FPS 本身由帧回调持续统计，这里只更新文本）。
 */
public final class FpsOverlayWindow extends OverlayWindow {

    private final FpsMonitor fpsMonitor = new FpsMonitor();

    private TextView valueView; // FPS 大数字
    private TextView frameView; // 单帧耗时小字

    public FpsOverlayWindow(Context context) {
        super(context, MonitorType.FPS);
    }

    @Override
    protected View buildContentView() {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = OverlayWindowStyle.dpToPx(context, OverlayWindowStyle.PADDING_DP);
        root.setPadding(pad, pad, pad, pad);
        root.setBackground(OverlayWindowStyle.createWindowBackground(context));

        root.addView(OverlayWindowStyle.createTitleView(context, "FPS"));

        valueView = OverlayWindowStyle.createValueView(context);
        valueView.setText("--");
        root.addView(valueView);

        frameView = OverlayWindowStyle.createBodyView(context);
        frameView.setText("--");
        root.addView(frameView);
        return root;
    }

    @Override
    protected void onShown() {
        fpsMonitor.start();
    }

    @Override
    protected void onHidden() {
        fpsMonitor.stop();
    }

    @Override
    protected void refreshContent() {
        float fps = fpsMonitor.getFps();
        if (fps <= 0f) {
            valueView.setText("--");
            frameView.setText("统计中…");
            return;
        }
        valueView.setText(String.format(Locale.US, "%.0f", fps));
        frameView.setText(String.format(Locale.US, "%.1f ms/帧", 1000f / fps));
    }

    @Override
    protected long getRefreshIntervalMs() {
        return 500L;
    }
}
