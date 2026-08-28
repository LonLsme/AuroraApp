package com.aurora.app.ui.overlay;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.aurora.app.core.monitor.FpsMonitor;

import java.util.Locale;

/**
 * 帧率记录器悬浮窗（折叠/展开双状态，风格与负载监视器统一）：
 * <ul>
 *   <li>折叠小窗：实时 FPS（大数字）+ 单帧耗时；</li>
 *   <li>展开大窗：实时 FPS + 平均/最高/最低帧率统计；</li>
 *   <li>交互/状态记忆/浅色穿透背景由基类统一提供。</li>
 * </ul>
 * FPS 由 Choreographer 帧回调在主线程统计，无文件 IO，因此不覆写 collectData。
 */
public final class FpsOverlayWindow extends OverlayWindow {

    private final FpsMonitor fpsMonitor = new FpsMonitor();

    // ─── 折叠态视图引用 ───
    private TextView valueView; // FPS 大数字
    private TextView frameView; // 单帧耗时小字

    // ─── 展开态视图引用 ───
    private TextView liveView;  // 实时 FPS
    private TextView avgView;   // 平均
    private TextView maxView;   // 最高
    private TextView minView;   // 最低

    public FpsOverlayWindow(Context context) {
        super(context, MonitorType.FPS);
    }

    @Override
    protected View buildCollapsedView() {
        LinearLayout col = new LinearLayout(context);
        col.setOrientation(LinearLayout.VERTICAL);
        valueView = new TextView(context);
        valueView.setTextColor(OverlayWindowStyle.COLOR_ACCENT_DARK);
        valueView.setTextSize(20);
        valueView.setTypeface(Typeface.DEFAULT_BOLD);
        valueView.setText("--");
        col.addView(valueView);
        frameView = OverlayWindowStyle.createDarkTitleView(context, "--");
        col.addView(frameView);
        return col;
    }

    @Override
    protected View buildExpandedView() {
        LinearLayout col = new LinearLayout(context);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(OverlayWindowStyle.createDarkTitleView(context, "帧率"));

        liveView = OverlayWindowStyle.createDarkBodyView(context);
        liveView.setTextSize(16);
        liveView.setTypeface(Typeface.DEFAULT_BOLD);
        liveView.setText("--");
        col.addView(liveView);

        avgView = OverlayWindowStyle.createDarkBodyView(context);
        avgView.setText("平均 --");
        col.addView(avgView);

        maxView = OverlayWindowStyle.createDarkBodyView(context);
        maxView.setText("最高 --");
        col.addView(maxView);

        minView = OverlayWindowStyle.createDarkBodyView(context);
        minView.setText("最低 --");
        col.addView(minView);
        return col;
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
        if (expanded) {
            liveView.setText(fps > 0 ? String.format(Locale.US, "实时 %.0f FPS", fps) : "实时 --");
            int samples = fpsMonitor.getSampleCount();
            if (samples > 0) {
                avgView.setText(String.format(Locale.US, "平均 %.0f FPS", fpsMonitor.getAvgFps()));
                maxView.setText(String.format(Locale.US, "最高 %.0f FPS", fpsMonitor.getMaxFps()));
                minView.setText(String.format(Locale.US, "最低 %.0f FPS", fpsMonitor.getMinFps()));
            } else {
                avgView.setText("平均 --");
                maxView.setText("最高 --");
                minView.setText("最低 --");
            }
        } else {
            if (fps > 0) {
                valueView.setText(String.format(Locale.US, "%.0f", fps));
                frameView.setText(String.format(Locale.US, "%.1f ms/帧", 1000f / fps));
            } else {
                valueView.setText("--");
                frameView.setText("统计中…");
            }
        }
    }

    @Override
    protected long getRefreshIntervalMs() {
        return 500L;
    }
}
