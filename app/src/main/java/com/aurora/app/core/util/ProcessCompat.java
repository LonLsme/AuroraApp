package com.aurora.app.core.util;

import android.os.Build;
import android.os.SystemClock;

import java.util.concurrent.TimeUnit;

/**
 * {@link Process#waitFor(long, TimeUnit)} 的兼容封装。
 *
 * <p>这个方法在 Android 上是 <b>API 26</b> 才加的。本项目 minSdk 24，直接在 7.x 上调用
 * 命中 {@code NoSuchMethodError}，表现为「点一下按钮应用就没了」，而 logcat 里只有一条
 * 与业务无关的链接错误，很难联想到是系统版本。
 *
 * <p>API 26+ 走原生实现（不轮询）；24/25 用 {@code exitValue} 轮询，
 * 与项目里已有的 adb / qdl 等待逻辑保持一致。
 */
public final class ProcessCompat {

    private ProcessCompat() {
    }

    /**
     * 等待进程结束。
     *
     * @return {@code true} 表示在超时前已结束；{@code false} 表示超时或等待被中断
     */
    public static boolean waitFor(Process process, long timeoutMs) {
        if (process == null) {
            return false;
        }
        long safeTimeout = Math.max(0L, timeoutMs);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                return process.waitFor(safeTimeout, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        long deadline = SystemClock.elapsedRealtime() + safeTimeout;
        while (true) {
            try {
                process.exitValue();
                return true;
            } catch (IllegalThreadStateException running) {
                if (SystemClock.elapsedRealtime() >= deadline) {
                    return false;
                }
                try {
                    Thread.sleep(50L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
    }
}
