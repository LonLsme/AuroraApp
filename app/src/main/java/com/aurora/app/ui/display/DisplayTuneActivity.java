package com.aurora.app.ui.display;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.aurora.app.R;
import com.aurora.app.core.display.DisplayController;
import com.aurora.app.core.display.DisplayGuard;
import com.aurora.app.core.root.RootShell;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.textfield.TextInputEditText;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 「屏幕」调节页：分辨率 / 密度 / 刷新率切换 + 刷新率实测。
 *
 * <h3>安全设计（本页最重要的事）</h3>
 * 分辨率与密度是「改错了就点不动」的参数，所以每一次改动都走
 * {@link DisplayGuard} 的断点兜底：改前快照 → 下发 → 置待确认标记 →
 * 15 秒倒计时 → 不点「保留」就按快照回滚。倒计时条固定在窗口底部、
 * 不参与滚动，保证任何情况下都点得到。
 *
 * <h3>为什么要声明 configChanges</h3>
 * 改分辨率/密度会触发 configuration change。若不做处理，系统会重建 Activity，
 * 于是 {@code onDestroy} 被调用、倒计时状态丢失，很容易出现「刚应用就被回滚」
 * 或者反过来「重建后倒计时丢了没人还原」的诡异现象。
 * 因此清单里给本页声明了 {@code screenSize|density|...}，让它就地接收配置变化，
 * 倒计时与待确认状态都保留在同一个实例里。
 *
 * <h3>线程模型</h3>
 * 所有 root 调用走单线程 {@link #executor}（root 会话必须串行）；
 * 测速在主线程（Choreographer 要求）。UI 更新统一 post 回主线程。
 */
public class DisplayTuneActivity extends AppCompatActivity {

    private TextView tvRootStatus;
    private TextView tvSizeSummary;
    private TextView tvRateSummary;
    private ChipGroup groupRefreshModes;
    private ChipGroup groupSizePresets;
    private ChipGroup groupTestDuration;
    private TextInputEditText etWidth;
    private TextInputEditText etHeight;
    private TextInputEditText etDensity;
    private MaterialButton btnApplySize;
    private MaterialButton btnResetSize;
    private MaterialButton btnRefreshAuto;
    private MaterialButton btnRefresh;
    private MaterialButton btnRunTest;
    private MaterialButton btnStopTest;
    private ProgressBar progressTest;
    private TextView tvTestResult;
    private TextView tvTestVerdict;
    private View viewPulse;
    private View cardConfirmBar;
    private ProgressBar progressCountdown;
    private TextView tvCountdownDetail;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "aurora-display-tune");
        t.setDaemon(true);
        return t;
    });

    private boolean viewCreated;
    /** 页面内是否已经点过「保留」；点过之后 onDestroy 不再回滚。 */
    private boolean confirmed;

    private DisplayController.SizeState sizeState;
    private List<DisplayController.Mode> modes;
    private int activeModeId = -1;

    private long countdownStartMs;
    private boolean counting;
    private RefreshRateTester tester;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_display_tune);

        Toolbar toolbar = findViewById(R.id.toolbarDisplayTune);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("屏幕");
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        tvRootStatus = findViewById(R.id.tvDisplayRootStatus);
        tvSizeSummary = findViewById(R.id.tvDisplaySizeSummary);
        tvRateSummary = findViewById(R.id.tvDisplayRateSummary);
        groupRefreshModes = findViewById(R.id.groupRefreshModes);
        groupSizePresets = findViewById(R.id.groupSizePresets);
        groupTestDuration = findViewById(R.id.groupTestDuration);
        etWidth = findViewById(R.id.etDisplayWidth);
        etHeight = findViewById(R.id.etDisplayHeight);
        etDensity = findViewById(R.id.etDisplayDensity);
        btnApplySize = findViewById(R.id.btnApplySize);
        btnResetSize = findViewById(R.id.btnResetSize);
        btnRefreshAuto = findViewById(R.id.btnRefreshAuto);
        btnRefresh = findViewById(R.id.btnDisplayRefresh);
        btnRunTest = findViewById(R.id.btnRunTest);
        btnStopTest = findViewById(R.id.btnStopTest);
        progressTest = findViewById(R.id.progressTest);
        tvTestResult = findViewById(R.id.tvTestResult);
        tvTestVerdict = findViewById(R.id.tvTestVerdict);
        viewPulse = findViewById(R.id.viewPulse);
        cardConfirmBar = findViewById(R.id.cardConfirmBar);
        progressCountdown = findViewById(R.id.progressCountdown);
        tvCountdownDetail = findViewById(R.id.tvCountdownDetail);

        viewCreated = true;
        tester = new RefreshRateTester(viewPulse);

        btnRefresh.setOnClickListener(v -> reload());
        btnRefreshAuto.setOnClickListener(v -> onReleaseRefreshLimit());
        btnApplySize.setOnClickListener(v -> onApplySize());
        btnResetSize.setOnClickListener(v -> onResetSize());
        btnRunTest.setOnClickListener(v -> onRunTest());
        btnStopTest.setOnClickListener(v -> onStopTest());
        findViewById(R.id.btnKeepChanges).setOnClickListener(v -> onKeepChanges());

        buildTestDurationChips();
        reload();
        restorePendingIfAny();
    }

    @Override
    protected void onDestroy() {
        viewCreated = false;
        stopCountdown();
        if (tester != null) {
            tester.release();
            tester = null;
        }
        // 页面退出时如果还有未确认的改动，立刻回滚。
        // 这里不阻塞主线程：回滚失败或进程被抢先杀掉时，
        // 「待确认」标记仍在，下一次应用启动或开机时会继续兜底。
        if (!confirmed && DisplayGuard.isPending(this)) {
            final Context appContext = getApplicationContext();
            executor.execute(() -> {
                DisplayController.OpResult r = DisplayGuard.rollback(appContext);
                android.util.Log.i("DisplayTuneActivity", "退出时回滚: " + r.message);
            });
        }
        executor.shutdown();
        super.onDestroy();
    }

    // ───────────────── 读取与渲染 ─────────────────

    /** 后台重读全部屏幕状态并重建 UI。 */
    private void reload() {
        if (!viewCreated) {
            return;
        }
        tvRootStatus.setText("检测中…");
        executor.execute(() -> {
            boolean rootOk = RootShell.get().isRootAvailable();
            DisplayController.SizeState state = DisplayController.readSizeState(this);
            List<DisplayController.Mode> list = DisplayController.readModes(this);
            int active = DisplayController.readActiveModeId();
            if (!viewCreated) {
                return;
            }
            mainHandler.post(() -> {
                if (!viewCreated) {
                    return;
                }
                sizeState = state;
                modes = list;
                activeModeId = active;
                renderRootStatus(rootOk);
                renderSize(state);
                renderModes(list, active);
            });
        });
    }

    private void renderRootStatus(boolean rootOk) {
        tvRootStatus.setText(rootOk
                ? "已获取 Root 权限 · " + DisplayController.deviceInfo()
                : "未获取 Root 权限 · 只读模式");
        tvRootStatus.setTextColor(getColor(rootOk
                ? R.color.ios_semantic_positive
                : R.color.ios_semantic_negative));
    }

    private void renderSize(@Nullable DisplayController.SizeState state) {
        if (state == null || state.physicalWidth <= 0) {
            tvSizeSummary.setText("分辨率读取失败");
            return;
        }
        tvSizeSummary.setText("分辨率 " + state.label());
        if (etWidth.getText() == null || etWidth.getText().length() == 0) {
            etWidth.setText(String.valueOf(state.activeWidth));
        }
        if (etHeight.getText() == null || etHeight.getText().length() == 0) {
            etHeight.setText(String.valueOf(state.activeHeight));
        }
        if (etDensity.getText() == null || etDensity.getText().length() == 0) {
            etDensity.setText(String.valueOf(state.activeDensity));
        }
        buildSizePresets(state);
    }

    private void renderModes(@Nullable List<DisplayController.Mode> list, int active) {
        groupRefreshModes.removeAllViews();
        if (list == null || list.isEmpty()) {
            tvRateSummary.setText("未读取到显示模式");
            Chip chip = new Chip(this);
            chip.setText("无可用模式");
            chip.setEnabled(false);
            groupRefreshModes.addView(chip);
            return;
        }
        DisplayController.Mode activeMode = null;
        for (DisplayController.Mode m : list) {
            if (m.id == active) {
                activeMode = m;
                break;
            }
        }
        tvRateSummary.setText(activeMode != null
                ? "刷新率 当前模式 " + activeMode.hzLabel() + " · 共 " + list.size() + " 个模式"
                : "刷新率 共 " + list.size() + " 个模式");

        for (final DisplayController.Mode mode : list) {
            Chip chip = new Chip(this);
            chip.setText(mode.hzLabel());
            chip.setCheckable(true);
            chip.setChecked(mode.id == active);
            chip.setOnClickListener(v -> {
                chip.setChecked(true);
                onSwitchMode(mode);
            });
            groupRefreshModes.addView(chip);
        }
    }

    private void buildSizePresets(DisplayController.SizeState state) {
        groupSizePresets.removeAllViews();
        int[] percents = {100, 90, 80, 75, 70, 60};
        for (int percent : percents) {
            Chip chip = new Chip(this);
            chip.setText(percent == 100 ? "原始" : percent + "%");
            chip.setCheckable(false);
            chip.setOnClickListener(v -> fillPreset(state, percent));
            groupSizePresets.addView(chip);
        }
    }

    /** 按比例换算宽高与密度，只填输入框不下发 —— 让用户先看清楚再点应用。 */
    private void fillPreset(DisplayController.SizeState state, int percent) {
        if (state == null || state.physicalWidth <= 0) {
            return;
        }
        int w = Math.round(state.physicalWidth * percent / 100f);
        int h = Math.round(state.physicalHeight * percent / 100f);
        int d = DisplayController.suggestDensity(w, state.physicalWidth, state.physicalDensity);
        etWidth.setText(String.valueOf(w));
        etHeight.setText(String.valueOf(h));
        etDensity.setText(String.valueOf(d));
    }

    private void buildTestDurationChips() {
        groupTestDuration.removeAllViews();
        long[] durations = {
                RefreshRateTester.DURATION_SHORT_MS,
                RefreshRateTester.DURATION_NORMAL_MS,
                RefreshRateTester.DURATION_LONG_MS,
        };
        for (long d : durations) {
            Chip chip = new Chip(this);
            chip.setText((d / 1000) + " 秒");
            chip.setCheckable(true);
            chip.setChecked(d == RefreshRateTester.DURATION_NORMAL_MS);
            chip.setTag(d);
            chip.setOnClickListener(v -> chip.setChecked(true));
            groupTestDuration.addView(chip);
        }
    }

    private long selectedDurationMs() {
        for (int i = 0; i < groupTestDuration.getChildCount(); i++) {
            View child = groupTestDuration.getChildAt(i);
            if (child instanceof Chip && ((Chip) child).isChecked()) {
                Object tag = child.getTag();
                if (tag instanceof Long) {
                    return (Long) tag;
                }
            }
        }
        return RefreshRateTester.DURATION_NORMAL_MS;
    }

    // ───────────────── 操作：刷新率 ─────────────────

    private void onSwitchMode(DisplayController.Mode mode) {
        toast("正在切换到 " + mode.hzLabel() + "…");
        executor.execute(() -> {
            DisplayGuard.snapshotOriginals(this);
            DisplayController.OpResult r = DisplayController.applyMode(mode);
            if (!viewCreated) {
                return;
            }
            mainHandler.post(() -> {
                if (!viewCreated) {
                    return;
                }
                toast(r.message);
                if (!r.success) {
                    return;
                }
                DisplayGuard.markPending(this, "刷新率 → " + mode.hzLabel());
                startCountdown("刷新率 → " + mode.hzLabel());
                executor.execute(() -> {
                    DisplayController.SizeState s = DisplayController.readSizeState(this);
                    int active = DisplayController.readActiveModeId();
                    List<DisplayController.Mode> list = DisplayController.readModes(this);
                    if (!viewCreated) {
                        return;
                    }
                    mainHandler.post(() -> {
                        if (!viewCreated) {
                            return;
                        }
                        sizeState = s;
                        modes = list;
                        activeModeId = active;
                        renderSize(s);
                        renderModes(list, active);
                    });
                });
            });
        });
    }

    private void onReleaseRefreshLimit() {
        executor.execute(() -> {
            DisplayGuard.snapshotOriginals(this);
            DisplayController.OpResult r = DisplayController.releaseRefreshRateLimit();
            if (!viewCreated) {
                return;
            }
            mainHandler.post(() -> {
                if (!viewCreated) {
                    return;
                }
                toast(r.message);
                if (r.success) {
                    DisplayGuard.markPending(this, "刷新率 → 系统自适应");
                    startCountdown("刷新率 → 系统自适应");
                }
            });
        });
    }

    // ───────────────── 操作：分辨率 ─────────────────

    private void onApplySize() {
        if (sizeState == null || sizeState.physicalWidth <= 0) {
            toast("尚未读取到屏幕信息，请先点「重新检测」");
            return;
        }
        int w = parseInt(etWidth.getText() == null ? "" : etWidth.getText().toString());
        int h = parseInt(etHeight.getText() == null ? "" : etHeight.getText().toString());
        int d = parseInt(etDensity.getText() == null ? "" : etDensity.getText().toString());
        if (w <= 0 || h <= 0) {
            toast("请填写有效的宽和高");
            return;
        }
        final int width = w;
        final int height = h;
        final int density = d;
        toast("正在应用 " + width + "x" + height + "…");
        executor.execute(() -> {
            DisplayGuard.snapshotOriginals(this);
            DisplayController.OpResult r = DisplayController.applyResolution(width, height, density);
            if (!viewCreated) {
                return;
            }
            mainHandler.post(() -> {
                if (!viewCreated) {
                    return;
                }
                toast(r.message);
                if (!r.success) {
                    return;
                }
                String desc = "分辨率 → " + width + "x" + height
                        + (density > 0 ? " · " + density + "dpi" : "");
                DisplayGuard.markPending(this, desc);
                startCountdown(desc);
            });
        });
    }

    /**
     * 还原为物理分辨率。这是「回到安全状态」的操作，本身不会再让界面坏掉，
     * 所以不走倒计时，直接执行并把待确认状态清掉。
     */
    private void onResetSize() {
        toast("正在还原…");
        executor.execute(() -> {
            DisplayController.OpResult r = DisplayController.resetResolution();
            if (!viewCreated) {
                return;
            }
            mainHandler.post(() -> {
                if (!viewCreated) {
                    return;
                }
                toast(r.message);
                if (r.success) {
                    stopCountdown();
                    DisplayGuard.confirm(this);
                    confirmed = true;
                    etWidth.setText("");
                    etHeight.setText("");
                    etDensity.setText("");
                    reload();
                }
            });
        });
    }

    // ───────────────── 倒计时与确认 ─────────────────

    private final Runnable countdownTick = new Runnable() {
        @Override
        public void run() {
            if (!counting) {
                return;
            }
            long elapsed = System.currentTimeMillis() - countdownStartMs;
            long remain = DisplayGuard.COUNTDOWN_MS - elapsed;
            if (remain <= 0) {
                progressCountdown.setProgress(1000);
                counting = false;
                doRollback("已到时间，自动还原");
                return;
            }
            progressCountdown.setProgress((int) (1000L * elapsed / DisplayGuard.COUNTDOWN_MS));
            tvCountdownDetail.setText(String.format(Locale.US,
                    "%.0f 秒后自动还原 · 界面正常请点「保留」", remain / 1000f));
            mainHandler.postDelayed(this, 100L);
        }
    };

    private void startCountdown(String desc) {
        confirmed = false;
        counting = true;
        countdownStartMs = System.currentTimeMillis();
        cardConfirmBar.setVisibility(View.VISIBLE);
        progressCountdown.setProgress(0);
        tvCountdownDetail.setText("15 秒后自动还原（" + desc + "）");
        mainHandler.removeCallbacks(countdownTick);
        mainHandler.post(countdownTick);
    }

    private void stopCountdown() {
        counting = false;
        mainHandler.removeCallbacks(countdownTick);
        if (cardConfirmBar != null) {
            cardConfirmBar.setVisibility(View.GONE);
        }
    }

    private void onKeepChanges() {
        DisplayGuard.confirm(this);
        confirmed = true;
        stopCountdown();
        toast("已保留当前显示设置，重启后仍然生效");
    }

    /** 回滚并刷新界面。 */
    private void doRollback(final String reason) {
        toast(reason);
        executor.execute(() -> {
            DisplayController.OpResult r = DisplayGuard.rollback(this);
            if (!viewCreated) {
                return;
            }
            mainHandler.post(() -> {
                if (!viewCreated) {
                    return;
                }
                stopCountdown();
                if (!r.success) {
                    // 回滚失败必须让用户知道救援方式，不能静默
                    tvTestVerdict.setText("自动还原失败：" + r.message);
                }
                etWidth.setText("");
                etHeight.setText("");
                etDensity.setText("");
                reload();
            });
        });
    }

    /**
     * 冷启动兜底：如果上次有未确认的改动残留（应用被杀、设备重启等），
     * 进页面时立即还原。正常情况下 {@code Application} 启动时已经处理过一次，
     * 这里只是防止「进程还活着但 Activity 被销毁重建」的遗漏。
     */
    private void restorePendingIfAny() {
        if (!DisplayGuard.isPending(this)) {
            return;
        }
        executor.execute(() -> {
            String desc = DisplayGuard.pendingDesc(this);
            boolean restored = DisplayGuard.restoreIfPending(this);
            if (!restored) {
                return;
            }
            if (!viewCreated) {
                return;
            }
            mainHandler.post(() -> {
                if (!viewCreated) {
                    return;
                }
                toast("检测到未确认的显示改动" + (desc.isEmpty() ? "" : "（" + desc + "）")
                        + "，已自动还原");
                etWidth.setText("");
                etHeight.setText("");
                etDensity.setText("");
                reload();
            });
        });
    }

    // ───────────────── 刷新率实测 ─────────────────

    private void onRunTest() {
        if (tester == null || tester.isRunning()) {
            return;
        }
        final long duration = selectedDurationMs();
        btnRunTest.setVisibility(View.GONE);
        btnStopTest.setVisibility(View.VISIBLE);
        progressTest.setProgress(0);
        tvTestResult.setText("测试中…");
        tvTestVerdict.setText("");
        tester.start(duration, new RefreshRateTester.Listener() {
            @Override
            public void onTick(long elapsedMs, long totalMs) {
                if (!viewCreated) {
                    return;
                }
                progressTest.setProgress((int) (100 * elapsedMs / Math.max(1L, totalMs)));
            }

            @Override
            public void onFinish(@Nullable RefreshRateTester.Result result) {
                if (!viewCreated) {
                    return;
                }
                progressTest.setProgress(100);
                btnRunTest.setVisibility(View.VISIBLE);
                btnStopTest.setVisibility(View.GONE);
                if (result == null) {
                    tvTestResult.setText("未采到有效帧");
                    tvTestVerdict.setText("请保持本页在前台后重试");
                    return;
                }
                tvTestResult.setText(String.format(Locale.US,
                        "%s\n平均帧间隔 %.2f ms · 掉帧 %d / %d 帧",
                        result.summary(), result.avgFrameMs, result.jankFrames, result.frames));
                tvTestVerdict.setText(result.verdict()
                        + (result.declaredHz > 0f
                        ? "（声称 " + Math.round(result.declaredHz) + "Hz）" : ""));
            }
        });
    }

    private void onStopTest() {
        if (tester != null) {
            tester.stop();
        }
    }

    // ───────────────── 小工具 ─────────────────

    private void toast(String message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private static int parseInt(String raw) {
        if (raw == null) {
            return 0;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
