package com.aurora.app.core.display;

import android.app.Activity;
import android.os.Build;
import android.util.Log;
import android.view.Display;
import android.view.Window;
import android.view.WindowManager;

/**
 * 向系统申请「本窗口用这块屏支持的最高刷新率」。
 *
 * <h3>为什么需要主动要</h3>
 * 高刷屏默认不一定给第三方应用高刷：多数系统只对白名单应用（或自家桌面）放开，
 * 或者用 LTPO 按内容动态降频。不声明偏好的话，本应用会一直拿到 60Hz，
 * 用户看到的「滑动不跟手」有很大一部分是 60Hz 造成的，与掉帧无关。
 *
 * <h3>为什么用 preferredRefreshRate 而不是 Window#setFrameRate</h3>
 * {@code setFrameRate} 是 API 30+，且语义是「这个窗口按多少帧产生内容」，
 * 需要配合帧率兼容策略；{@code preferredRefreshRate} 从 API 23 就有，
 * 语义就是「我希望屏幕用这个刷新率」，覆盖面更广。
 *
 * <h3>为什么它是安全的</h3>
 * 这只是<b>偏好</b>，不是强制：系统会取
 * {@code min(本偏好, settings system/peak_refresh_rate)}，
 * 用户在系统设置里限了 60Hz、或者开了省电模式时，系统不会听这里的。
 * 所以不需要在 App 内再判断「用户是不是想省电」。
 */
public final class RefreshRate {

    private static final String TAG = "AuroraRefreshRate";

    /** 缓存每块屏的最高刷新率，避免每个 Activity 都遍历一次 supportedModes。 */
    private static volatile float sMaxRefreshRate;

    private RefreshRate() {
    }

    /**
     * 给窗口设上最高刷新率偏好。可在 {@code Activity.onCreate} 里调用。
     *
     * <p>失败或读不到就静默返回 —— 刷新率是锦上添花，不该影响任何功能。
     */
    public static void applyMax(Activity activity) {
        if (activity == null) {
            return;
        }
        try {
            Window window = activity.getWindow();
            if (window == null) {
                return;
            }
            float max = maxRefreshRate(activity);
            if (max <= 0f) {
                return;
            }
            WindowManager.LayoutParams params = window.getAttributes();
            // 已经设过就不重复 setAttributes：那会触发一次窗口重新布局
            if (Math.abs(params.preferredRefreshRate - max) < 0.01f) {
                return;
            }
            params.preferredRefreshRate = max;
            window.setAttributes(params);
            Log.i(TAG, "请求最高刷新率 " + max + "Hz（" + activity.getClass().getSimpleName() + "）");
        } catch (Throwable t) {
            Log.w(TAG, "设置刷新率偏好失败", t);
        }
    }

    /** 这块屏支持的最高刷新率；读不到返回 0。 */
    private static float maxRefreshRate(Activity activity) {
        float cached = sMaxRefreshRate;
        if (cached > 0f) {
            return cached;
        }
        try {
            Display display;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                display = activity.getDisplay();
            } else {
                //noinspection deprecation
                display = activity.getWindowManager().getDefaultDisplay();
            }
            if (display == null) {
                return 0f;
            }
            float max = 0f;
            for (Display.Mode mode : display.getSupportedModes()) {
                max = Math.max(max, mode.getRefreshRate());
            }
            if (max > 0f) {
                sMaxRefreshRate = max;
            }
            return max;
        } catch (Throwable t) {
            Log.w(TAG, "读取支持的刷新率失败", t);
            return 0f;
        }
    }
}
