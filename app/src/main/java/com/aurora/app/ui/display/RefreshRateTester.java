package com.aurora.app.ui.display;

import android.animation.ValueAnimator;
import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;
import android.view.View;

import androidx.annotation.Nullable;

import com.aurora.app.core.display.DisplayController;

import java.util.Arrays;
import java.util.Locale;

/**
 * 刷新率实测：用 {@link Choreographer} 统计真实帧间隔，反推屏幕当前实际输出帧率。
 *
 * <h3>为什么不能直接读 {@code mActiveSfDisplayMode}</h3>
 * 那里读到的是「SurfaceFlinger 被要求用哪个模式」，不等于「屏幕上真的在刷多少帧」。
 * ROM 的省电策略、温控、可变刷新率（VRR）都会让实际值低于声明值。
 * 这个工具的价值就是用真实帧间隔去对比声称值 —— 也就是玩机圈常说的
 * 「测一下是不是假高刷」。
 *
 * <h3>为什么要同时跑一个动画</h3>
 * Choreographer 的回调是挂在 vsync 上的，但应用<b>没有待绘制内容时
 * 系统可能直接不给它发 vsync</b>（省电）。所以必须同时跑一个持续动画
 * （{@link ValueAnimator} 无限循环）让界面一直有脏区，vsync 才会稳定到来。
 * 单独只用 Choreographer 自循环，测出来的帧率会偏低且抖动虚高。
 *
 * <h3>指标口径</h3>
 * <ul>
 *   <li><b>平均 FPS</b>：帧数 / 总时长，反映整体水平；</li>
 *   <li><b>1% low</b>：把所有帧间隔从大到小排，取第 1% 那个间隔换算成 FPS。
 *       它反映「最卡的那一小撮帧」，比平均值更能说明卡不卡；</li>
 *   <li><b>抖动</b>：帧间隔的标准差（ms）。高刷屏若与声明值吻合，抖动通常在 1ms 上下；
 *       抖到 4ms 以上说明帧节奏不稳（可能是掉帧或 VRR 在来回切）。</li>
 * </ul>
 *
 * <p>本类在主线程使用（Choreographer 只能在有 Looper 的线程用），但计算量是
 * O(n log n)，n 约等于时长 × 120，最多几千，不会卡 UI。
 */
public final class RefreshRateTester {

    /** 测试时长选项（毫秒）。 */
    public static final long DURATION_SHORT_MS = 3_000L;
    public static final long DURATION_NORMAL_MS = 5_000L;
    public static final long DURATION_LONG_MS = 10_000L;

    /** 一次测试的结果。 */
    public static final class Result {
        /** 实测平均帧率。 */
        public final float avgFps;
        /** 1% low 帧率（越低说明偶发卡顿越严重）。 */
        public final float onePercentLowFps;
        /** 帧间隔标准差，单位 ms。 */
        public final float jitterMs;
        /** 平均帧间隔，单位 ms。 */
        public final float avgFrameMs;
        /** 采到的总帧数。 */
        public final int frames;
        /** 判定为掉帧的帧数（间隔 > 声明间隔的 1.5 倍）。 */
        public final int jankFrames;
        /** 测试时长。 */
        public final long durationMs;
        /** 测试时 {@code dumpsys} 声称的刷新率，用于对比；未知为 0。 */
        public final float declaredHz;

        Result(float avgFps, float onePercentLowFps, float jitterMs, float avgFrameMs,
               int frames, int jankFrames, long durationMs, float declaredHz) {
            this.avgFps = avgFps;
            this.onePercentLowFps = onePercentLowFps;
            this.jitterMs = jitterMs;
            this.avgFrameMs = avgFrameMs;
            this.frames = frames;
            this.jankFrames = jankFrames;
            this.durationMs = durationMs;
            this.declaredHz = declaredHz;
        }

        /** 与声称值的差距说明。 */
        public String verdict() {
            if (avgFps <= 0f) {
                return "未采到有效帧";
            }
            if (declaredHz <= 0f) {
                return "实测 " + fmt(avgFps) + " FPS";
            }
            float ratio = avgFps / declaredHz;
            if (ratio >= 0.95f) {
                return "与声称值一致，是真实 " + Math.round(declaredHz) + "Hz";
            }
            if (ratio >= 0.8f) {
                return "略低于声称值，可能被系统省电策略限制";
            }
            if (ratio >= 0.6f) {
                return "明显低于声称值，当前未跑满";
            }
            return "远低于声称值，疑似未生效或应用本身跑不满";
        }

        /** 一句话总览，直接写进结果卡。 */
        public String summary() {
            return "平均 " + fmt(avgFps) + " FPS · 1% low " + fmt(onePercentLowFps)
                    + " · 抖动 " + fmt(jitterMs) + " ms";
        }

        private static String fmt(float v) {
            return String.format(Locale.US, "%.1f", v);
        }
    }

    /** 测试过程回调，全部在主线程触发。 */
    public interface Listener {
        /** 进度刷新，{@code elapsedMs} 已用时。 */
        void onTick(long elapsedMs, long totalMs);

        /** 测试结束（正常结束或被 {@link #stop()} 中断都会回调），{@code result} 可空。 */
        void onFinish(@Nullable Result result);
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final float[] timestamps = new float[4096];

    /** 用于驱动持续绘制，保证 vsync 稳定。 */
    private final View pulseView;
    private ValueAnimator pulse;

    private int frameCount;
    private long startNanos;
    private long durationMs;
    private boolean running;
    private float declaredHz;
    private Listener listener;

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!running) {
                return;
            }
            if (frameCount >= timestamps.length) {
                finish();
                return;
            }
            // 逐帧记录，最后统一算差值：边走边算容易在收尾处引入偏差
            timestamps[frameCount++] = frameTimeNanos / 1_000_000f;
            long elapsed = (frameTimeNanos - startNanos) / 1_000_000L;
            if (elapsed >= durationMs) {
                finish();
                return;
            }
            if (listener != null && frameCount % 12 == 0) {
                listener.onTick(elapsed, durationMs);
            }
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    /**
     * @param pulseView 用于持续 invalidate 的视图（任意可见小 View 即可）。
     *                  传 null 则退化为「仅 Choreographer 自循环」，
     *                  在有省电策略的机器上可能测不准。
     */
    public RefreshRateTester(@Nullable View pulseView) {
        this.pulseView = pulseView;
    }

    /** 当前是否在测。 */
    public boolean isRunning() {
        return running;
    }

    /**
     * 开始测试。会先读一次声称刷新率用于对比（走 root，无 root 时为 0）。
     * 必须在主线程调用。
     */
    public void start(long durationMs, Listener listener) {
        if (running) {
            return;
        }
        this.durationMs = durationMs;
        this.listener = listener;
        this.frameCount = 0;
        this.running = true;
        this.declaredHz = 0f;

        mainHandler.post(() -> {
            if (!running) {
                return;
            }
            declaredHz = DisplayController.readActiveRefreshRate();
            startNanos = System.nanoTime();
            startPulse();
            Choreographer.getInstance().postFrameCallback(frameCallback);
        });
    }

    /** 主动中断；会回调 {@code onFinish(已有数据的统计结果)}。 */
    public void stop() {
        if (!running) {
            return;
        }
        finish();
    }

    /** 释放资源；页面销毁时必须调用，否则动画与回调会泄漏。 */
    public void release() {
        running = false;
        listener = null;
        stopPulse();
        Choreographer.getInstance().removeFrameCallback(frameCallback);
        mainHandler.removeCallbacksAndMessages(null);
    }

    // ───────────────── 内部 ─────────────────

    private void startPulse() {
        if (pulseView == null) {
            return;
        }
        pulse = ValueAnimator.ofFloat(0f, 1f);
        pulse.setDuration(300L);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.setRepeatMode(ValueAnimator.RESTART);
        pulse.addUpdateListener(animation -> {
            if (pulseView != null) {
                // 只要视图有脏区，系统就会持续发 vsync
                pulseView.invalidate();
            }
        });
        pulse.start();
    }

    private void stopPulse() {
        if (pulse != null) {
            pulse.cancel();
            pulse = null;
        }
    }

    private void finish() {
        if (!running) {
            return;
        }
        running = false;
        stopPulse();
        Choreographer.getInstance().removeFrameCallback(frameCallback);

        Result result = compute();
        Listener l = listener;
        listener = null;
        if (l != null) {
            l.onFinish(result);
        }
    }

    @Nullable
    private Result compute() {
        int n = frameCount;
        if (n < 8) {
            return null;
        }
        float[] deltas = new float[n - 1];
        float sum = 0f;
        for (int i = 1; i < n; i++) {
            float d = timestamps[i] - timestamps[i - 1];
            // 极端值（例如被系统挂起）不计入，否则抖动指标会被一条噪声毁掉
            if (d <= 0f || d > 500f) {
                d = Float.NaN;
            } else {
                sum += d;
            }
            deltas[i - 1] = d;
        }
        int valid = 0;
        for (float d : deltas) {
            if (!Float.isNaN(d)) {
                valid++;
            }
        }
        if (valid < 6) {
            return null;
        }
        float mean = sum / valid;

        // 标准差（只统计有效帧）
        float sq = 0f;
        for (float d : deltas) {
            if (Float.isNaN(d)) {
                continue;
            }
            float diff = d - mean;
            sq += diff * diff;
        }
        float jitter = (float) Math.sqrt(sq / valid);

        // 1% low：按间隔降序取第 1% 个
        float[] sorted = deltas.clone();
        Arrays.sort(sorted);
        // 升序排列，跳过 NaN（NaN 排在末尾），第 1% low 取「最大的那 1% 里最小的一个」
        int lowestValid = 0;
        while (lowestValid < sorted.length && Float.isNaN(sorted[lowestValid])) {
            lowestValid++;
        }
        int rows = sorted.length - lowestValid;
        int p99Index = lowestValid + Math.max(0, (int) Math.ceil(rows * 0.99) - 1);
        if (p99Index >= sorted.length) {
            p99Index = sorted.length - 1;
        }
        float p99Delta = sorted[p99Index];
        if (Float.isNaN(p99Delta) || p99Delta <= 0f) {
            p99Delta = mean;
        }

        float avgFps = 1000f / mean;
        float onePercentLow = 1000f / p99Delta;

        // 掉帧：间隔超过声明间隔 1.5 倍
        float expected = declaredHz > 0f ? 1000f / declaredHz : mean;
        int jank = 0;
        for (float d : deltas) {
            if (!Float.isNaN(d) && d > expected * 1.5f) {
                jank++;
            }
        }

        long totalMs = (long) (timestamps[n - 1] - timestamps[0]);
        return new Result(avgFps, onePercentLow, jitter, mean, valid, jank, totalMs, declaredHz);
    }
}
