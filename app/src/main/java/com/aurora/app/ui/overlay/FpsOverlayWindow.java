package com.aurora.app.ui.overlay;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.aurora.app.core.monitor.FpsRecorder;
import com.aurora.app.core.monitor.FpsSampler;

import java.util.Locale;

/**
 * 帧率悬浮窗（单行形态 + 一键记录控制）：
 * <ul>
 *   <li>折叠小窗：实时 FPS（系统级采样，与 {@link FpsRecorder} 同源，无单帧耗时）；
 *       帧率数字区固定宽度，不因位数变化抖动窗口；</li>
 *   <li><b>单击 = 记录开关</b>：未记录时开始记录（底色变透明红，同时窗口下方
 *       展开「时长(分)」选择行 5/10/15/30，5 秒不选自动收起、默认从点击时刻开始
 *       不限时记录）；记录中再次单击停止记录（底色还原、写入历史）；</li>
 *   <li><b>时长选择行是窗口的一部分</b>：与帧率显示同一悬浮窗，整体拖动跟随，
 *       不再是独立子窗口；</li>
 *   <li>双击 = 关闭窗口（先停止记录）；</li>
 *   <li>记录自动停止（时长到点/在别处停止）后，悬浮窗通过刷新轮询同步还原底色。</li>
 * </ul>
 * 采样走后台线程（FpsSampler 读 sysfs / SurfaceFlinger，经 RootShell 会话）。
 */
public final class FpsOverlayWindow extends OverlayWindow {

    private final FpsSampler fpsSampler = new FpsSampler();
    private volatile float currentFps = -1f;

    // ─── 折叠态视图引用 ───
    private TextView valueView; // FPS 大数字（固定宽）

    // ─── 时长选择行（窗口内嵌，非独立子窗口） ───
    private View durationPickerRow;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable hidePickerTask = this::hideDurationPickerRow;
    private static final int PICKER_AUTO_HIDE_MS = 5000;
    private static final int[] DURATION_MINUTES = {5, 10, 15, 30};

    // ─── 记录控制状态 ───
    private boolean uiRecording = false; // UI 侧当前是否记录中（驱动底色）

    public FpsOverlayWindow(Context context) {
        super(context, MonitorType.FPS);
    }

    /**
     * 折叠小窗：单行紧凑显示「FPS」。
     *
     * <p>不再显示单帧耗时 ms 与记录时长（用户要求减少宽度），单行只保留帧率数字；
     * 帧率数字区固定宽度（数字 1→2→3 位变化时窗口宽度不变，拖动/点击位置稳定）。
     * 时长选择行默认隐藏，单击开始记录时展开。
     */
    @Override
    protected View buildCollapsedView() {
        LinearLayout col = new LinearLayout(context);
        col.setOrientation(LinearLayout.VERTICAL);

        // 第一行：帧率显示（固定宽，仅数字）
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        valueView = new TextView(context);
        valueView.setTextColor(OverlayWindowStyle.MONITOR_TEXT);
        valueView.setTextSize(13);
        valueView.setTypeface(Typeface.DEFAULT_BOLD);
        valueView.setIncludeFontPadding(false);
        valueView.setText("--");
        valueView.setGravity(Gravity.CENTER);
        // 固定宽度：容纳 3 位数字 + 安全余量，位数变化不抖动
        LinearLayout.LayoutParams valueLp = new LinearLayout.LayoutParams(
                OverlayWindowStyle.dpToPx(context, 36), LinearLayout.LayoutParams.WRAP_CONTENT);
        valueView.setLayoutParams(valueLp);
        row.addView(valueView);
        col.addView(row);

        // 第二行：时长选择（默认隐藏，单击记录时展开）
        durationPickerRow = buildDurationPickerRow();
        durationPickerRow.setVisibility(View.GONE);
        col.addView(durationPickerRow);
        return col;
    }

    /** 帧率悬浮窗固定单行形态：展开态与折叠态一致（不提供大窗统计，统计在记录页）。 */
    @Override
    protected View buildExpandedView() {
        return buildCollapsedView();
    }

    @Override
    protected void onShown() {
        // 若历史记忆为展开态，强制回折叠（本窗口固定单行）
        if (expanded) {
            expanded = false;
            OverlayConfig.setExpanded(context, type, false);
            swapContent(buildCollapsedView(), false);
        }
        // 若悬浮窗显示时记录已在跑（例如从记录页启动），同步底色
        syncRecordingUi();
    }

    @Override
    protected void onHidden() {
        hideDurationPickerRow();
        // 关闭悬浮窗时若还在记录，先停止（保存历史）
        FpsRecorder recorder = FpsRecorder.get();
        if (recorder.isRecording()) {
            recorder.stop(context);
        }
        uiRecording = false;
    }

    /** 后台线程采集系统级帧率（FpsSampler 走 RootShell）。 */
    @Override
    protected void collectData() {
        currentFps = fpsSampler.sample();
    }

    @Override
    protected void refreshContent() {
        // 轮询同步记录状态（时长到点自动停止 / 记录页手动停止时还原底色）
        syncRecordingUi();

        float fps = currentFps;
        if (fps > 0) {
            valueView.setText(String.format(Locale.US, "%.0f", fps));
        } else {
            valueView.setText("--");
        }
    }

    @Override
    protected long getRefreshIntervalMs() {
        return 500L;
    }

    // ───────────────── 记录控制（单击/双击） ─────────────────

    /**
     * 单击 = 记录开关：
     * <ul>
     *   <li>未记录 → 开始记录：底色变透明红，展开时长选择行（5 秒不选自动收起，
     *       默认不限时、从点击时刻开始记录）；</li>
     *   <li>记录中 → 停止记录：底色还原、收起时长选择行，写入历史。</li>
     * </ul>
     */
    @Override
    protected void onSingleTap() {
        FpsRecorder recorder = FpsRecorder.get();
        if (recorder.isRecording()) {
            recorder.stop(context);
            hideDurationPickerRow();
            syncRecordingUi();
        } else {
            recorder.bindContext(context);
            recorder.start();
            syncRecordingUi();
            showDurationPickerRow();
        }
    }

    /** 双击：先停止记录（若在记录中），再关闭窗口。 */
    @Override
    protected void onDoubleTap() {
        FpsRecorder recorder = FpsRecorder.get();
        if (recorder.isRecording()) {
            recorder.stop(context);
        }
        hideDurationPickerRow();
        super.onDoubleTap();
    }

    /** 依据 FpsRecorder 实际状态同步本窗口 UI（底色 + uiRecording 标志）。 */
    private void syncRecordingUi() {
        boolean recording = FpsRecorder.get().isRecording();
        if (recording == uiRecording) {
            return;
        }
        uiRecording = recording;
        if (!recording) {
            // 记录结束（手动/到点/外部）：收起时长选择行
            hideDurationPickerRow();
        }
        setWindowBackground(recording
                ? OverlayWindowStyle.createWindowBackgroundRecording(context)
                : OverlayWindowStyle.createMonitorBackground(context));
    }

    // ───────────────── 时长选择行（窗口内嵌） ─────────────────

    /** 展开时长选择行（5/10/15/30 分钟）；5 秒不选择自动收起。 */
    private void showDurationPickerRow() {
        if (durationPickerRow == null) {
            return;
        }
        durationPickerRow.setVisibility(View.VISIBLE);
        relayoutWindow();
        mainHandler.removeCallbacks(hidePickerTask);
        mainHandler.postDelayed(hidePickerTask, PICKER_AUTO_HIDE_MS);
    }

    /** 收起时长选择行（幂等）。 */
    private void hideDurationPickerRow() {
        mainHandler.removeCallbacks(hidePickerTask);
        if (durationPickerRow != null
                && durationPickerRow.getVisibility() == View.VISIBLE) {
            durationPickerRow.setVisibility(View.GONE);
            relayoutWindow();
        }
    }

    /** 构建时长选择行：标题「时长(分)」+ 4 个数字按钮（横向紧凑排列）。 */
    private View buildDurationPickerRow() {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pad = OverlayWindowStyle.dpToPx(context, 2);
        row.setPadding(0, pad, 0, 0);

        TextView title = OverlayWindowStyle.createMonitorTitleView(context, "时长(分)");
        title.setPadding(0, 0, OverlayWindowStyle.dpToPx(context, 6), 0);
        row.addView(title);

        for (int minutes : DURATION_MINUTES) {
            TextView btn = new TextView(context);
            btn.setText(String.valueOf(minutes));
            btn.setTextColor(OverlayWindowStyle.MONITOR_TEXT);
            btn.setTextSize(12);
            btn.setGravity(Gravity.CENTER);
            int hp = OverlayWindowStyle.dpToPx(context, 10);
            int vp = OverlayWindowStyle.dpToPx(context, 5);
            btn.setPadding(hp, vp, hp, vp);
            btn.setBackground(OverlayWindowStyle.createPickerButtonBackground(context));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            blp.setMarginStart(OverlayWindowStyle.dpToPx(context, 4));
            btn.setLayoutParams(blp);
            btn.setOnClickListener(v -> {
                FpsRecorder.get().setMaxDurationSec(minutes * 60L);
                hideDurationPickerRow();
            });
            row.addView(btn);
        }
        return row;
    }
}
