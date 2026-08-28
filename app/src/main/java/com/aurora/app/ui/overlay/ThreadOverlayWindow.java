package com.aurora.app.ui.overlay;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.aurora.app.core.monitor.ThreadMonitor;

import java.util.Locale;

/**
 * 线程监视器悬浮窗：
 * 显示系统当前全部线程总数（遍历 /proc/[pid]/task 求和）。
 * 刷新周期 2s（目录遍历开销较大，降低频率以省电）。
 */
public final class ThreadOverlayWindow extends OverlayWindow {

    private TextView valueView; // 线程总数大数字
    private TextView hintView;  // 说明小字

    public ThreadOverlayWindow(Context context) {
        super(context, MonitorType.THREAD);
    }

    @Override
    protected View buildContentView() {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = OverlayWindowStyle.dpToPx(context, OverlayWindowStyle.PADDING_DP);
        root.setPadding(pad, pad, pad, pad);
        root.setBackground(OverlayWindowStyle.createWindowBackground(context));

        root.addView(OverlayWindowStyle.createTitleView(context, "线程"));

        valueView = OverlayWindowStyle.createValueView(context);
        valueView.setText("--");
        root.addView(valueView);

        hintView = OverlayWindowStyle.createBodyView(context);
        hintView.setText("系统全部线程数");
        root.addView(hintView);
        return root;
    }

    @Override
    protected void refreshContent() {
        int count = ThreadMonitor.readSystemThreadCount();
        valueView.setText(String.format(Locale.US, "%,d", count));
    }

    @Override
    protected long getRefreshIntervalMs() {
        return 2000L;
    }
}
