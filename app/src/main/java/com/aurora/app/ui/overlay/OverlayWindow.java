package com.aurora.app.ui.overlay;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;

/**
 * 悬浮窗抽象基类：
 * 封装所有监视窗口共有的能力——
 * <ul>
 *   <li>窗口创建/销毁（WindowManager addView / removeView）；</li>
 *   <li>定时刷新循环（主线程 Handler，周期由子类决定）；</li>
 *   <li>触摸拖动（按住窗口任意位置即可移动，松手停在原地）；</li>
 *   <li>多窗口堆叠的默认初始位置（按 MonitorType 顺序错开）。</li>
 * </ul>
 *
 * <p>子类只需实现三件事：
 * {@link #buildContentView()} 构建窗口内容视图、
 * {@link #refreshContent()} 刷新数据、
 * 可选覆写 {@link #getRefreshIntervalMs()} 调整刷新周期。
 *
 * <p>生命周期由 {@link OverlayWindowManager} 驱动：
 * 开关打开 → {@link #show()}；开关关闭 → {@link #hide()}。
 */
public abstract class OverlayWindow {

    protected final Context context;
    protected final WindowManager windowManager;
    protected final MonitorType type;

    /** 当前挂到 WindowManager 上的内容视图；未显示时为 null。 */
    private View rootView;
    private final WindowManager.LayoutParams params;
    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private boolean running = false;

    // ─── 拖动状态 ───
    private final int touchSlop;
    private float downRawX, downRawY;
    private float windowStartX, windowStartY;
    private boolean dragging = false;

    protected OverlayWindow(Context context, MonitorType type) {
        this.context = context;
        this.type = type;
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        this.touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        this.params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
    }

    /** API 26+ 使用 TYPE_APPLICATION_OVERLAY，旧版本回退 TYPE_PHONE（都需要悬浮窗权限）。 */
    private static int overlayWindowType() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    /**
     * 显示悬浮窗：构建内容视图 → 添加到 WindowManager → 启动定时刷新。
     * 重复调用会被忽略（幂等）。
     */
    public final void show() {
        if (running) {
            return;
        }
        running = true;
        rootView = buildContentView();
        // 初始位置：从屏幕左上角区域开始，按类型顺序向下错开，避免多窗口完全重叠
        params.x = OverlayWindowStyle.dpToPx(context, 16);
        params.y = OverlayWindowStyle.dpToPx(context, 90 + type.ordinal() * 56);
        installDrag(rootView);
        windowManager.addView(rootView, params);
        onShown();
        refreshHandler.removeCallbacks(refreshTask);
        refreshHandler.post(refreshTask);
    }

    /**
     * 销毁悬浮窗：停止刷新 → 从 WindowManager 移除视图。
     * 重复调用会被忽略（幂等）。
     */
    public final void hide() {
        if (!running) {
            return;
        }
        running = false;
        refreshHandler.removeCallbacks(refreshTask);
        onHidden();
        if (rootView != null) {
            try {
                windowManager.removeView(rootView);
            } catch (Exception ignored) {
                // 窗口可能已被系统移除（如权限被收回），忽略
            }
            rootView = null;
        }
    }

    /** 当前是否显示中。 */
    public final boolean isRunning() {
        return running;
    }

    /** 子类可覆写：窗口显示后做额外初始化（如启动 FpsMonitor）。 */
    protected void onShown() {
    }

    /** 子类可覆写：窗口销毁前做清理（如停止 FpsMonitor、清空采样基线）。 */
    protected void onHidden() {
    }

    /** 子类必须实现：构建窗口内容视图。 */
    protected abstract View buildContentView();

    /** 子类必须实现：刷新窗口数据（主线程调用，注意不要做耗时 IO）。 */
    protected abstract void refreshContent();

    /** 子类可覆写：刷新周期（毫秒），默认 1s。 */
    protected long getRefreshIntervalMs() {
        return 1000L;
    }

    // ─── 定时刷新循环 ───
    private final Runnable refreshTask = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            refreshContent();
            refreshHandler.postDelayed(this, getRefreshIntervalMs());
        }
    };

    /**
     * 给窗口内容视图安装拖动手势：
     * 按下记录起点，移动超过 touchSlop 即进入拖动，松手停留在当前位置。
     */
    private void installDrag(View view) {
        view.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawX = event.getRawX();
                    downRawY = event.getRawY();
                    windowStartX = params.x;
                    windowStartY = params.y;
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = event.getRawX() - downRawX;
                    float dy = event.getRawY() - downRawY;
                    if (!dragging && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                        dragging = true;
                    }
                    if (dragging && rootView != null) {
                        params.x = (int) (windowStartX + dx);
                        params.y = (int) (windowStartY + dy);
                        windowManager.updateViewLayout(rootView, params);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    return true;
                default:
                    return false;
            }
        });
    }
}
