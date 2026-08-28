package com.aurora.app.core.monitor;

import android.view.Choreographer;

/**
 * 帧率监视器数据源：
 * 通过 {@link Choreographer.FrameCallback} 统计每秒绘制的帧数（FPS）。
 *
 * <p>原理：Choreographer 在每个 VSync 帧回调一次，以 1 秒为滑动窗口，
 * 窗口内回调次数即 FPS。窗口的 UI 线程用 Handler 定时（如 500ms）读取
 * {@link #getFps()} 刷新显示即可，本类自身只做帧计数，不接触 UI。
 */
public final class FpsMonitor implements Choreographer.FrameCallback {

    private boolean running = false;
    private long lastFrameTimeNanos = 0L; // 上一帧时间，用于去重
    private int frameCount = 0;           // 当前 1s 窗口内的帧数
    private long windowStartNanos = 0L;   // 当前统计窗口起点
    private float currentFps = 0f;        // 最近一个窗口的 FPS

    // ─── 累计统计（展开大窗展示平均/最高/最低） ───
    private int sampleCount = 0;          // 已结算的窗口数
    private float sumFps = 0f;            // 累计 FPS 之和
    private float maxFps = 0f;            // 历史最高 FPS
    private float minFps = Float.MAX_VALUE; // 历史最低 FPS

    /**
     * 开始监听帧回调。重复调用会被忽略。
     */
    public void start() {
        if (running) {
            return;
        }
        running = true;
        frameCount = 0;
        windowStartNanos = 0L;
        lastFrameTimeNanos = 0L;
        Choreographer.getInstance().postFrameCallback(this);
    }

    /**
     * 停止监听。窗口销毁时必须调用，否则回调会一直触发。
     */
    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        Choreographer.getInstance().removeFrameCallback(this);
    }

    /** 当前 FPS（最近 1s 窗口的均值，尚未满 1s 时为 0）。 */
    public float getFps() {
        return currentFps;
    }

    /** 平均 FPS（自启动以来的窗口均值）。 */
    public float getAvgFps() {
        return sampleCount > 0 ? sumFps / sampleCount : 0f;
    }

    /** 历史最高 FPS。 */
    public float getMaxFps() {
        return sampleCount > 0 ? maxFps : 0f;
    }

    /** 历史最低 FPS。 */
    public float getMinFps() {
        return sampleCount > 0 ? minFps : 0f;
    }

    /** 已结算的采样窗口数（供 UI 判断是否有统计数据）。 */
    public int getSampleCount() {
        return sampleCount;
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (!running) {
            return;
        }
        // 同一时间戳的重复回调不计
        if (lastFrameTimeNanos != frameTimeNanos) {
            lastFrameTimeNanos = frameTimeNanos;
            frameCount++;
        }
        if (windowStartNanos == 0L) {
            windowStartNanos = frameTimeNanos;
        }
        long elapsed = frameTimeNanos - windowStartNanos;
        if (elapsed >= 1_000_000_000L) {
            // 满 1s：结算 FPS、更新累计统计并开启新窗口
            currentFps = frameCount * 1_000_000_000f / Math.max(elapsed, 1L);
            sampleCount++;
            sumFps += currentFps;
            maxFps = Math.max(maxFps, currentFps);
            minFps = Math.min(minFps, currentFps);
            frameCount = 0;
            windowStartNanos = frameTimeNanos;
        }
        // 继续监听下一帧
        Choreographer.getInstance().postFrameCallback(this);
    }
}
