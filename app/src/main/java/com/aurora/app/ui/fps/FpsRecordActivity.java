package com.aurora.app.ui.fps;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.aurora.app.R;
import com.aurora.app.core.monitor.FpsRecorder;
import com.google.android.material.button.MaterialButton;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 帧率记录页：开始/停止记录、实时统计、曲线、历史会话摘要。
 *
 * <p>记录由 {@link FpsRecorder} 单例承担（后台线程采集），页面只是展示与控制；
 * 离开页面记录不中断，回来仍能看到进度。
 */
public class FpsRecordActivity extends AppCompatActivity {

    private TextView tvState;
    private TextView tvCur;
    private TextView tvAvg;
    private TextView tvMax;
    private TextView tvMin;
    private TextView tvDuration;
    private MaterialButton btnToggle;
    private FpsChartView chart;
    private LinearLayout layoutHistory;
    private TextView tvNoHistory;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            updateStats();
            mainHandler.postDelayed(this, 1000L);
        }
    };
    private boolean viewCreated;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_fps_record);

        Toolbar toolbar = findViewById(R.id.toolbarFpsRecord);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("帧率记录");
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        tvState = findViewById(R.id.tvFpsState);
        tvCur = findViewById(R.id.tvFpsCur);
        tvAvg = findViewById(R.id.tvFpsAvg);
        tvMax = findViewById(R.id.tvFpsMax);
        tvMin = findViewById(R.id.tvFpsMin);
        tvDuration = findViewById(R.id.tvFpsDuration);
        btnToggle = findViewById(R.id.btnFpsToggle);
        chart = findViewById(R.id.chartFps);
        layoutHistory = findViewById(R.id.layoutFpsHistory);
        tvNoHistory = findViewById(R.id.tvFpsNoHistory);
        viewCreated = true;

        btnToggle.setOnClickListener(v -> {
            FpsRecorder recorder = FpsRecorder.get();
            if (recorder.isRecording()) {
                recorder.stop(this);
                renderHistory();
                Toast.makeText(this, "记录已停止并保存到历史", Toast.LENGTH_SHORT).show();
            } else {
                recorder.start();
            }
            updateControls();
            updateStats();
        });

        updateControls();
        updateStats();
        renderHistory();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mainHandler.post(ticker);
    }

    @Override
    protected void onPause() {
        mainHandler.removeCallbacks(ticker);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        viewCreated = false;
        super.onDestroy();
    }

    /** 刷新按钮与状态文案。 */
    private void updateControls() {
        boolean recording = FpsRecorder.get().isRecording();
        btnToggle.setText(recording ? "停止记录" : "开始记录");
        tvState.setText(recording ? "正在记录系统帧率..." : "未开始记录");
        tvState.setTextColor(getColor(recording
                ? R.color.ios_semantic_positive : R.color.ios_text_secondary));
    }

    /** 刷新统计与曲线（来自 FpsRecorder 快照）。 */
    private void updateStats() {
        if (!viewCreated) {
            return;
        }
        FpsRecorder.Snapshot s = FpsRecorder.get().snapshot();
        if (s.fpsSeries.length > 0) {
            tvCur.setText(fmt(s.currentFps));
            tvAvg.setText(fmt(s.avgFps));
            tvMax.setText(fmt(s.maxFps));
            tvMin.setText(fmt(s.minFps));
        } else {
            tvCur.setText("--");
            tvAvg.setText("--");
            tvMax.setText("--");
            tvMin.setText("--");
        }
        tvDuration.setText("时长 " + formatDuration(s.durationSec)
                + (s.fpsSeries.length > 0 ? " · 样本 " + s.fpsSeries.length : ""));
        if (FpsRecorder.get().isSamplingDead()) {
            tvState.setText("采样不可用：请确认已授权 Root");
            tvState.setTextColor(getColor(R.color.ios_semantic_negative));
        } else if (s.recording) {
            tvState.setText("正在记录系统帧率...");
        }
        chart.setData(s.fpsSeries);
    }

    /** 渲染历史会话列表。 */
    private void renderHistory() {
        if (!viewCreated) {
            return;
        }
        List<FpsRecorder.HistoryEntry> history = FpsRecorder.loadHistory(this);
        layoutHistory.removeAllViews();
        tvNoHistory.setVisibility(history.isEmpty() ? View.VISIBLE : View.GONE);
        SimpleDateFormat df = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
        for (FpsRecorder.HistoryEntry e : history) {
            View row = LayoutInflater.from(this)
                    .inflate(R.layout.item_fps_history, layoutHistory, false);
            ((TextView) row.findViewById(R.id.tvFpsHistoryTime)).setText(
                    df.format(new Date(e.endTimeMs)) + " · " + formatDuration(e.durationSec));
            ((TextView) row.findViewById(R.id.tvFpsHistoryStats)).setText(String.format(
                    Locale.CHINA, "均 %.1f / 高 %.1f / 低 %.1f",
                    e.avgFps, e.maxFps, e.minFps));
            layoutHistory.addView(row);
        }
    }

    private static String fmt(float v) {
        return String.format(Locale.CHINA, "%.1f", v);
    }

    private static String formatDuration(long sec) {
        return sec / 3600 + ":" + String.format(Locale.CHINA, "%02d", (sec % 3600) / 60)
                + ":" + String.format(Locale.CHINA, "%02d", sec % 60);
    }
}
